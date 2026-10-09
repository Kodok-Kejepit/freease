#!/usr/bin/env python3
"""
freease — Modul 11: Media Player (Audio / Video di terminal)
by: Kodok-Kejepit

Memutar hampir semua jenis audio & video langsung dari terminal Linux / Termux.
Engine di-deteksi otomatis dengan prioritas kualitas vs kompatibilitas:

  Video (ada display X/Wayland) : mpv > ffplay > mplayer > vlc
  Video (tanpa display, terminal): mpv --vo=tct  >  timg  >  (ffmpeg|chafa)
  Audio                          : mpv > ffplay > mplayer > mpg123 > cvlc

Semua format yang didukung ffmpeg/mpv bisa diputar: mp3, m4a, opus, flac, wav,
aac, ogg, wma, mp4, mkv, webm, avi, mov, flv, ts, dan lain-lain. Sumber boleh
berupa file, folder (otomatis jadi playlist), pola glob, atau URL stream
(http/https — di-resolve lewat yt-dlp bila tersedia).

Pemakaian lewat freease.py:
  python freease.py -p lagu.mp3
  python freease.py -p ./Music --shuffle --loop
  python freease.py -p video.mkv --audio-only
  python freease.py -p "https://youtu.be/ID" --pl-backend mpv

Atau langsung:
  python freease_player.py lagu.mp3 video.mp4 --shuffle
"""

from __future__ import annotations

import argparse
import glob as _glob
import mimetypes
import os
import random
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Optional

from rich import box
from rich.console import Console
from rich.markup import escape
from rich.table import Table
from rich.panel import Panel

from freease_ui import paginate


# ──────────────────────────────────────────────────────────────
#  Ekstensi yang dikenali (hanya untuk klasifikasi & auto-playlist;
#  playback sebenarnya tetap diserahkan ke ffmpeg/mpv yang jauh lebih luas).
# ──────────────────────────────────────────────────────────────
AUDIO_EXT = {
    ".mp3", ".m4a", ".aac", ".opus", ".ogg", ".oga", ".flac", ".wav",
    ".wma", ".alac", ".aiff", ".aif", ".ape", ".mka", ".ac3", ".dts",
    ".amr", ".3ga", ".weba", ".mid", ".midi",
}
VIDEO_EXT = {
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".ts", ".m2ts",
    ".mpg", ".mpeg", ".wmv", ".m4v", ".3gp", ".ogv", ".vob", ".divx",
    ".mts", ".f4v", ".rm", ".rmvb",
}
MEDIA_EXT = AUDIO_EXT | VIDEO_EXT


def _is_url(src: str) -> bool:
    return src.startswith(("http://", "https://", "rtmp://", "rtsp://"))


def _is_termux() -> bool:
    return bool(os.environ.get("TERMUX_VERSION")) or "com.termux" in os.environ.get("PREFIX", "")


def _has_display() -> bool:
    """True bila ada server grafis (X / Wayland) untuk jendela video."""
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def _fmt_duration(sec: Optional[float]) -> str:
    if not sec or sec <= 0:
        return "—"
    sec = int(sec)
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def _fmt_size(path: Path) -> str:
    try:
        n = float(path.stat().st_size)
    except OSError:
        return "—"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return "—"


_MPV_PROFILES: Optional[str] = None


def _mpv_has_profile(name: str) -> bool:
    """Profil bawaan mpv berbeda antar versi; cek dulu sebelum dipakai."""
    global _MPV_PROFILES
    if _MPV_PROFILES is None:
        try:
            _MPV_PROFILES = subprocess.run(
                ["mpv", "--profile=help"], capture_output=True, text=True, timeout=10
            ).stdout
        except (OSError, subprocess.SubprocessError):
            _MPV_PROFILES = ""
    return any(line.strip() == name for line in _MPV_PROFILES.splitlines())


class _SilentLogger:
    """yt-dlp tetap menulis ERROR ke stderr walau quiet=True; logger ini menahannya."""
    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg): pass
    def error(self, msg): pass


_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def _clean_err(e: BaseException) -> str:
    msg = _ANSI_RE.sub("", str(e)).strip()
    return re.sub(r"^ERROR:\s*(\[[^\]]+\]\s*\S+:\s*)?", "", msg)[:200]


def _safe_share_path(path: str) -> str:
    """
    Beberapa aplikasi pemutar Android gagal membuka file yang namanya berisi
    spasi atau tanda kurung siku. File seperti itu dibagikan lewat symlink
    bernama aman di ~/.cache/freease/open/ (file aslinya tidak disalin/diubah).
    """
    real = os.path.realpath(path)
    name = os.path.basename(real)
    if re.fullmatch(r"[A-Za-z0-9._-]+", name):
        return real
    import hashlib
    folder = Path.home() / ".cache" / "freease" / "open"
    try:
        folder.mkdir(parents=True, exist_ok=True)
        link = folder / ("freease_" + hashlib.sha1(real.encode()).hexdigest()[:12]
                         + Path(real).suffix.lower())
        if link.is_symlink() or link.exists():
            link.unlink()
        link.symlink_to(real)
        return str(link)
    except OSError:
        return real


def _parse_da1(buf: bytes) -> Optional[bool]:
    """Jawaban DA1 'ESC [ ? 62;4;22 c' — kode 4 berarti terminal mendukung sixel."""
    m = re.search(rb"\x1b\[\?([\d;]*)c", buf)
    if not m:
        return None
    return "4" in m.group(1).decode().split(";")


def terminal_supports_sixel(timeout: float = 0.6) -> Optional[bool]:
    """Tanya terminal lewat DA1. None = tidak bisa dipastikan (bukan TTY / tidak menjawab)."""
    if not (sys.stdin.isatty() and sys.stdout.isatty()):
        return None
    try:
        import select
        import termios
        import time
        import tty
    except ImportError:
        return None
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    buf = b""
    try:
        tty.setraw(fd)
        os.write(sys.stdout.fileno(), b"\x1b[c")
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            ready, _, _ = select.select([fd], [], [], max(0.0, end - time.monotonic()))
            if not ready:
                break
            buf += os.read(fd, 64)
            if buf.endswith(b"c"):
                break
    except OSError:
        return None
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
    return _parse_da1(buf)


def terminal_supports_kitty() -> bool:
    env = os.environ
    return (env.get("TERM") == "xterm-kitty" or "KITTY_WINDOW_ID" in env
            or env.get("TERM_PROGRAM") in ("WezTerm", "ghostty"))


_TERMUX_PROPS = ("~/.termux/termux.properties", "~/.config/termux/termux.properties")


def termux_external_allowed() -> bool:
    """
    Aplikasi lain hanya boleh membaca file dari Termux (lewat TermuxContentProvider)
    kalau allow-external-apps = true di termux.properties.
    """
    for p in _TERMUX_PROPS:
        try:
            for line in Path(p).expanduser().read_text("utf-8", "replace").splitlines():
                key, _, val = line.partition("=")
                if key.strip() == "allow-external-apps" and val.strip().lower() == "true":
                    return True
        except OSError:
            continue
    return False


class Track:
    """Satu entri antrian pemutaran."""

    __slots__ = ("src", "is_url", "kind", "title", "duration", "direct")

    def __init__(self, src: str, title: Optional[str] = None,
                 duration: Optional[float] = None):
        self.src = src
        self.title = title          # judul dari hasil pencarian / metadata stream
        self.duration = duration
        self.direct: Optional[str] = None   # URL stream langsung hasil resolve yt-dlp
        self.is_url = _is_url(src)
        if self.is_url:
            self.kind = "url"
        else:
            ext = Path(src).suffix.lower()
            self.kind = "audio" if ext in AUDIO_EXT else (
                "video" if ext in VIDEO_EXT else "media")

    @property
    def name(self) -> str:
        if self.title:
            return self.title
        return self.src if self.is_url else Path(self.src).name

    @property
    def has_video(self) -> bool:
        # URL & file tak dikenal diperlakukan berpotensi punya video.
        return self.kind in ("video", "url", "media")


class MediaPlayer:
    """
    Orchestrator pemutar. Membangun antrian dari sumber, mendeteksi backend
    terbaik yang tersedia, lalu memutar tiap track dengan menyerahkan kontrol
    keyboard ke player bawaan (mpv/ffplay punya keybinding sendiri).
    """

    def __init__(
        self,
        sources: list,
        *,
        console: Optional[Console] = None,
        audio_only: bool = False,
        loop: bool = False,
        shuffle: bool = False,
        volume: int = 100,
        start: Optional[str] = None,
        speed: float = 1.0,
        subtitle: Optional[str] = None,
        backend: Optional[str] = None,
        recursive: bool = True,
        list_only: bool = False,
        external: bool = False,
        quality: Optional[int] = None,
        vo: Optional[str] = None,
        interactive: Optional[bool] = None,
        download_dir: str = "./freease_downloads",
    ):
        self.console = console or Console()
        self.audio_only = audio_only
        self.loop = loop
        self.shuffle = shuffle
        self.volume = max(0, min(200, volume))
        self.start = start
        self.speed = speed if speed > 0 else 1.0
        self.subtitle = subtitle
        self.backend_override = backend
        self.recursive = recursive
        self.list_only = list_only
        self.external = external          # buka di aplikasi pemutar (HD), bukan di terminal
        # target resolusi untuk --external / sixel / kitty; None = belum ditentukan
        # pengguna, sehingga saat mengunduh untuk mode HD resolusinya ditanyakan
        self.ask_quality = quality is None
        self.quality = max(144, quality or 720)
        self.vo = vo                      # tct (default) / sixel / kitty untuk video di terminal
        self.interactive = sys.stdin.isatty() if interactive is None else interactive
        self.download_dir = download_dir

        if self.start and _to_seconds(self.start) is None:
            self.console.print(f"  [yellow]\\[!] --start '{escape(str(self.start))}' tidak valid "
                               "(contoh: 90 atau 1:30) — diabaikan.[/yellow]")
            self.start = None
        if self.subtitle and not Path(self.subtitle).expanduser().is_file():
            self.console.print(f"  [yellow]\\[!] File subtitle tidak ditemukan: "
                               f"{escape(self.subtitle)} — diabaikan.[/yellow]")
            self.subtitle = None

        self.has_display = _has_display()
        self.has_yt_dlp = shutil.which("yt-dlp") is not None
        self.has_ffprobe = shutil.which("ffprobe") is not None

        self.queue: list[Track] = self._build_queue(sources)

    # ── Pembentukan antrian ────────────────────────────────────
    def _build_queue(self, sources: list[str]) -> list[Track]:
        tracks: list[Track] = []
        for src in sources:
            if isinstance(src, Track):
                tracks.append(src)
                continue
            if _is_url(src):
                tracks.append(Track(src))
                continue
            p = Path(src).expanduser()
            if p.is_dir():
                tracks.extend(self._scan_dir(p))
            elif any(ch in src for ch in "*?[") and not p.exists():
                for match in sorted(_glob.glob(os.path.expanduser(src), recursive=True)):
                    mp = Path(match)
                    if mp.is_file() and mp.suffix.lower() in MEDIA_EXT:
                        tracks.append(Track(str(mp)))
            elif p.is_file():
                tracks.append(Track(str(p)))
            else:
                self.console.print(
                    f"  [yellow]\\[!] Dilewati (tidak ditemukan):[/yellow] {escape(src)}")
        if self.shuffle:
            random.shuffle(tracks)
        return tracks

    def _scan_dir(self, directory: Path) -> list[Track]:
        it = directory.rglob("*") if self.recursive else directory.glob("*")
        files = sorted(
            str(p) for p in it
            if p.is_file() and p.suffix.lower() in MEDIA_EXT
        )
        return [Track(f) for f in files]

    # ── Metadata (best-effort, tidak wajib) ────────────────────
    def _probe(self, track: Track) -> dict:
        info: dict = {"duration": None, "resolution": None,
                      "artist": None, "title": None, "codec": None}
        if track.is_url or not self.has_ffprobe:
            return info
        try:
            out = subprocess.run(
                ["ffprobe", "-v", "quiet", "-show_entries",
                 "format=duration:stream=codec_type,codec_name,width,height:"
                 "format_tags=title,artist",
                 "-of", "default=noprint_wrappers=1", track.src],
                capture_output=True, text=True, timeout=15,
            ).stdout
        except (subprocess.SubprocessError, OSError):
            return info
        w = h = None
        for line in out.splitlines():
            if "=" not in line:
                continue
            k, _, v = line.partition("=")
            v = v.strip()
            if not v or v == "N/A":
                continue
            if k == "duration":
                try:
                    info["duration"] = float(v)
                except ValueError:
                    pass
            elif k == "width":
                w = v
            elif k == "height":
                h = v
            elif k == "codec_name" and not info["codec"]:
                info["codec"] = v
            elif k == "TAG:title" or k == "title":
                info["title"] = v
            elif k == "TAG:artist" or k == "artist":
                info["artist"] = v
        if w and h:
            info["resolution"] = f"{w}×{h}"
        return info

    # ── Pemilihan backend ──────────────────────────────────────
    def _available(self) -> dict:
        return {name: shutil.which(name) for name in
                ("mpv", "ffplay", "mplayer", "vlc", "cvlc", "mpg123",
                 "timg", "chafa", "ffmpeg")}

    def _choose_backend(self, track: Track) -> Optional[str]:
        """Kembalikan nama backend yang akan dipakai untuk track ini."""
        av = self._available()
        wants_video = track.has_video and not self.audio_only

        if self.backend_override:
            bo = self.backend_override
            if av.get(bo):
                return bo
            self.console.print(
                f"  [yellow]\\[!] Backend '{bo}' tidak ditemukan, pakai auto-detect.[/yellow]")

        if wants_video:
            if self.has_display:
                for name in ("mpv", "ffplay", "mplayer", "vlc"):
                    if av.get(name):
                        return name
            # Tanpa display → render di dalam terminal.
            if av.get("mpv"):
                return "mpv"            # mpv --vo=tct: video+audio di terminal
            if av.get("timg"):
                return "timg"           # timg: video (tanpa audio) di terminal
            if av.get("ffmpeg") and (av.get("chafa") or av.get("timg")):
                return "ffmpeg-chafa"
        # Audio (atau --audio-only).
        for name in ("mpv", "ffplay", "mplayer", "mpg123", "cvlc", "vlc"):
            if av.get(name):
                return name
        return None

    # ── Pembentukan perintah per backend ───────────────────────
    def _build_cmd(self, backend: str, track: Track) -> list[str]:
        src = track.src
        wants_video = track.has_video and not self.audio_only
        term_video = wants_video and not self.has_display

        audio_only = self.audio_only or not track.has_video
        if track.is_url and backend != "mpv":
            # ffplay/mplayer/vlc tidak mengerti link halaman (YouTube, dll):
            # ubah dulu jadi URL stream langsung.
            src = self._resolve_stream(track, audio_only)

        if backend == "mpv":
            # Baris status mpv ikut tergambar di atas frame saat video dirender di
            # terminal, jadi dimatikan di mode itu.
            status = "no" if term_video else "status"
            cmd = ["mpv", "--no-config", f"--volume={self.volume}",
                   f"--speed={self.speed}", f"--msg-level=all=error,statusline={status}",
                   # tanpa perangkat audio (server/SSH) video tetap jalan, bukan gagal
                   "--audio-fallback-to-null=yes"]
            if audio_only:
                cmd.append("--no-video")
            elif term_video:
                vo = self._usable_vo()
                cmd.append(f"--vo={vo}")
                if vo == "tct" and _mpv_has_profile("sw-fast"):
                    cmd.append("--profile=sw-fast")
            if track.is_url:
                if shutil.which("yt-dlp"):
                    # mpv memanggil yt-dlp sendiri; batasi resolusi saat render di
                    # terminal supaya tidak boros CPU.
                    cmd.append("--ytdl-format=" + (
                        "bestaudio/best" if audio_only else
                        # tct menggambar ~2 piksel per karakter: stream di atas 480p
                        # tidak menambah ketajaman, hanya membuang kuota & CPU.
                        "bv*[height<=480]+ba/b[height<=480]/b"
                        if term_video and (self.vo or "tct") == "tct" else
                        f"bv*[height<={self.quality}]+ba/b[height<={self.quality}]/b"
                        if term_video else
                        "bv*[height<=1080]+ba/b"))
                else:
                    src = self._resolve_stream(track, audio_only)
            if self.start:
                cmd.append(f"--start={self.start}")
            if self.subtitle:
                cmd.append(f"--sub-file={self.subtitle}")
            if self.loop and len(self.queue) == 1:
                cmd.append("--loop-file=inf")
            cmd.append(src)
            return cmd

        if backend == "ffplay":
            cmd = ["ffplay", "-hide_banner", "-loglevel", "error", "-autoexit",
                   "-volume", str(min(100, self.volume))]
            if self.audio_only or not track.has_video:
                cmd.append("-nodisp")
            if self.start:
                cmd += ["-ss", self.start]
            cmd.append(src)
            return cmd

        if backend == "mplayer":
            cmd = ["mplayer", "-volume", str(min(100, self.volume))]
            if self.audio_only or not track.has_video:
                cmd += ["-vo", "null"]
            if self.start:
                cmd += ["-ss", self.start]
            if self.speed != 1.0:
                cmd += ["-speed", str(self.speed)]
            cmd.append(src)
            return cmd

        if backend == "mpg123":
            # -f = faktor skala output; 32768 = volume normal (100%)
            cmd = ["mpg123", "-q", "-f", str(int(32768 * self.volume / 100))]
            if self.start:
                secs = _to_seconds(self.start)
                if secs:
                    cmd += ["-k", str(int(secs * 38.28))]   # ≈ frame MP3 per detik (44.1 kHz)
            cmd.append(src)
            return cmd

        if backend in ("cvlc", "vlc"):
            cmd = [backend, "--play-and-exit", "--intf", "dummy"]
            if self.audio_only or not track.has_video:
                cmd.append("--no-video")
            cmd.append(src)
            return cmd

        if backend == "timg":
            # timg memutar video (tanpa audio) frame-by-frame di terminal.
            return ["timg", "--loops=1", src]

        if backend == "ffmpeg-chafa":
            # Fallback paling dasar: ekstrak frame low-fps via ffmpeg, render chafa.
            # Dijalankan lewat shell pipe; audio tidak ikut (keterbatasan fallback).
            renderer = "chafa -" if shutil.which("chafa") else "timg -"
            return ["sh", "-c",
                    f"ffmpeg -v error -i {_shq(src)} -vf fps=10 -f image2pipe "
                    f"-vcodec ppm - | {renderer}"]

        return [backend, src]

    def _usable_vo(self) -> str:
        """
        sixel / kitty hanya dipakai kalau terminal memang mendukungnya; kalau tidak,
        data gambarnya tercetak sebagai teks acak dan memenuhi layar.
        """
        vo = self.vo or "tct"
        if vo == "sixel" and not getattr(self, "_sixel_ok", None):
            ok = terminal_supports_sixel()
            if ok is not True:
                why = "tidak mendukung" if ok is False else "tidak menjawab pemeriksaan"
                self.console.print(f"  [yellow]\\[!] Terminal ini {why} grafis sixel; "
                                   f"memakai mode tct.[/yellow]")
                self.vo = vo = "tct"
            else:
                self._sixel_ok = True
        elif vo == "kitty" and not terminal_supports_kitty():
            self.console.print("  [yellow]\\[!] Terminal ini bukan kitty/WezTerm/Ghostty; "
                               "memakai mode tct.[/yellow]")
            self.vo = vo = "tct"
        return vo

    def _resolve_stream(self, track: Track, audio_only: bool) -> str:
        """Link halaman → URL stream langsung lewat yt-dlp (hasil di-cache per track)."""
        if track.direct:
            return track.direct
        try:
            import yt_dlp
        except ImportError:
            return track.src
        opts = {
            "quiet": True, "no_warnings": True, "noplaylist": True,
            # backend non-mpv butuh satu URL berisi audio (+video), bukan dua stream terpisah
            "format": "ba/b" if audio_only else "b[vcodec!=none][acodec!=none][height<=720]/b",
        }
        runtimes = {n: {"path": p} for n, e in (("deno", "deno"), ("node", "node"), ("bun", "bun"))
                    if (p := shutil.which(e))}
        if runtimes:
            opts["js_runtimes"] = runtimes
        try:
            with self.console.status("[cyan]Menyiapkan stream…"):
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(track.src, download=False)
        except Exception as e:
            self.console.print(f"  [yellow]\\[!] Gagal menyiapkan stream: {escape(str(e)[:160])}[/yellow]")
            return track.src
        track.title = track.title or info.get("title")
        track.duration = track.duration or info.get("duration")
        track.direct = info.get("url") or track.src
        return track.direct

    # ── Eksekusi ───────────────────────────────────────────────
    def run(self) -> dict:
        if not self.queue:
            self.console.print("  [red]\\[x] Tidak ada media yang bisa diputar.[/red]")
            return {"error": "antrian kosong", "played": 0}

        self._print_queue()
        if self.list_only:
            return {"error": None, "played": 0, "queued": len(self.queue)}

        av = self._available()
        if not self.external and not any(av.get(x) for x in ("mpv", "ffplay", "mplayer", "mpg123", "cvlc", "vlc")):
            self.console.print(Panel(
                "[red]Tidak ada player yang terpasang.[/red]\n\n"
                "Pasang salah satu (urutan rekomendasi):\n"
                "  [cyan]mpv[/cyan]      — terbaik, bisa video di terminal (--vo=tct)\n"
                "                [dim]apt install mpv  /  pkg install mpv[/dim]\n"
                "  [cyan]ffmpeg[/cyan]   — menyediakan ffplay + ffprobe\n"
                "                [dim]apt install ffmpeg  /  pkg install ffmpeg[/dim]\n"
                "  [cyan]mpg123[/cyan]   — pemutar audio ringan",
                title="[bold red]Backend tidak ditemukan[/bold red]",
                border_style="red"))
            return {"error": "tidak ada backend", "played": 0}

        self._print_controls()

        played = 0
        loop_pass = True
        last_backend = None
        try:
            while loop_pass:
                played_before = played
                for idx, track in enumerate(self.queue, 1):
                    backend = self._choose_backend(track)
                    use_external = self.external and track.has_video and not self.audio_only
                    if not backend and not use_external:
                        self.console.print(
                            f"  [yellow]\\[!] Lewati {escape(track.name)} "
                            f"(tidak ada backend cocok).[/yellow]")
                        continue
                    if use_external:
                        self._now_playing(idx, track, "aplikasi eksternal")
                        rc = self._play_external(track)
                        if rc == 0 and idx < len(self.queue) and self.interactive:
                            self.console.input("  [dim]Tekan Enter untuk item berikutnya…[/dim]")
                    else:
                        last_backend = backend
                        self._now_playing(idx, track, backend)
                        rc = self._spawn(self._build_cmd(backend, track))
                        if rc not in (0, 130) and track.is_url:
                            # Stream online kadang gagal sesaat (TLS/CDN); coba sekali lagi
                            self.console.print("  [yellow]\\[!] Stream gagal dibuka, mencoba ulang…[/yellow]")
                            track.direct = None
                            rc = self._spawn(self._build_cmd(backend, track))
                    if rc == 0:
                        played += 1
                    elif rc == 130:
                        raise KeyboardInterrupt
                    else:
                        self.console.print(
                            f"  [yellow]\\[!] Player keluar dengan kode {rc}.[/yellow]")
                # mpv mengulang satu file sendiri (--loop-file); backend lain diulang di sini
                loop_pass = self.loop and (len(self.queue) > 1 or last_backend != "mpv")
                if loop_pass and played == played_before:
                    self.console.print("  [yellow]\\[!] Tidak ada track yang berhasil diputar; "
                                       "pengulangan dihentikan.[/yellow]")
                    loop_pass = False
                if loop_pass:
                    self.console.print("  [dim]Mengulang playlist…[/dim]\n")
        except KeyboardInterrupt:
            self.console.print("\n  [yellow]\\[-] Pemutaran dihentikan.[/yellow]")

        self.console.print(
            f"\n  [green]\\[+] Selesai.[/green] [dim]{played}/{len(self.queue)} "
            f"track diputar.[/dim]\n")
        return {"error": None, "played": played, "queued": len(self.queue)}

    # ── Pemutaran HD di aplikasi eksternal ─────────────────────
    def _opener(self, target: str, mime: str) -> Optional[list[str]]:
        """Perintah untuk membuka file/URL di aplikasi pemutar sistem."""
        if _is_termux():
            if _is_url(target):
                if shutil.which("am"):
                    return ["am", "start", "-a", "android.intent.action.VIEW",
                            "-d", target, "-t", mime]
                return ["termux-open-url", target] if shutil.which("termux-open-url") else None
            if shutil.which("termux-open"):
                return ["termux-open", "--view", "--content-type", mime, target]
            return None
        if self.has_display:
            # Di desktop, jendela mpv sudah memutar sampai 1080p
            if shutil.which("mpv"):
                return ["mpv", "--no-config", f"--volume={self.volume}",
                        f"--ytdl-format=bv*[height<={self.quality}]+ba/b", target]
            for opener in ("xdg-open", "open"):
                if shutil.which(opener):
                    return [opener, target]
        return None

    def _resolve_hd(self, track: Track) -> tuple[Optional[str], Optional[int]]:
        """
        Satu URL berisi video+audio dengan resolusi setinggi mungkin (<= quality).
        Aplikasi pemutar Android tidak bisa menggabungkan stream video & audio
        terpisah. Untuk YouTube, stream gabungan HD hanya ada sebagai HLS dari
        client web_safari, jadi client itu ikut diminta.
        """
        try:
            import yt_dlp
        except ImportError:
            return None, None
        q = self.quality
        opts = {
            "quiet": True, "no_warnings": True, "noplaylist": True,
            "format": f"b[height<={q}][vcodec!=none][acodec!=none]/b[vcodec!=none][acodec!=none]/b",
            "format_sort": [f"res:{q}"],
            "extractor_args": {"youtube": {"player_client": ["default", "web_safari"]}},
            "logger": _SilentLogger(),
        }
        runtimes = {n: {"path": p} for n, e in (("deno", "deno"), ("node", "node"), ("bun", "bun"))
                    if (p := shutil.which(e))}
        if runtimes:
            opts["js_runtimes"] = runtimes
        try:
            with self.console.status("[cyan]Mencari stream HD…"):
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(track.src, download=False)
        except Exception as e:
            self.console.print(f"  [dim]Stream gabungan tidak tersedia: {escape(_clean_err(e))}[/dim]")
            return None, None
        track.title = track.title or info.get("title")
        track.duration = track.duration or info.get("duration")
        h = info.get("height")
        w = info.get("width")
        res = min(w, h) if (w and h) else h
        return info.get("url"), res

    def _enable_external_apps(self) -> bool:
        """Jelaskan syarat Termux; tawarkan mengaktifkannya (hanya dengan persetujuan)."""
        self.console.print(
            "  [yellow]\\[!] Termux belum mengizinkan aplikasi lain membaca file-nya, jadi "
            "aplikasi pemutar akan gagal membuka video.[/yellow]\n"
            "  [dim]Perlu baris [white]allow-external-apps = true[/white] di "
            "~/.termux/termux.properties. Pengaturan ini juga membolehkan aplikasi yang Anda "
            "beri izin \"Run commands in Termux\" menjalankan perintah di Termux.[/dim]")
        if not self.interactive:
            self.console.print(
                "  [dim]Aktifkan manual:\n"
                "    echo \"allow-external-apps = true\" >> ~/.termux/termux.properties\n"
                "    termux-reload-settings[/dim]")
            return False
        from rich.prompt import Confirm
        if not Confirm.ask("  Aktifkan sekarang?", default=False, console=self.console):
            return False
        props = Path("~/.termux/termux.properties").expanduser()
        try:
            props.parent.mkdir(parents=True, exist_ok=True)
            with open(props, "a", encoding="utf-8") as fh:
                fh.write("\nallow-external-apps = true\n")
        except OSError as e:
            self.console.print(f"  [red]\\[x] Gagal menulis {props}: {escape(str(e))}[/red]")
            return False
        if shutil.which("termux-reload-settings"):
            subprocess.run(["termux-reload-settings"], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
        self.console.print(f"  [green]\\[+] allow-external-apps diaktifkan di {props}[/green]")
        return True

    def _play_external(self, track: Track) -> int:
        mime = "video/*"
        target = track.src
        if track.is_url:
            url, res = self._resolve_hd(track)
            want = min(self.quality, 720)
            if url and res and res >= want:
                self.console.print(f"  [green]\\[+] Stream {res}p siap.[/green]")
                target = url
            else:
                if res:
                    why = f"Stream yang bisa dikirim langsung ke aplikasi hanya {res}p."
                else:
                    why = ("Situs ini tidak menyediakan stream video+audio dalam satu link, "
                           "jadi tidak bisa dikirim langsung ke aplikasi.")
                choose = self.interactive and self.ask_quality
                target_txt = "resolusi pilihan Anda" if choose else f"{self.quality}p"
                self.console.print(
                    f"  [yellow]\\[!] {why} Video diunduh dulu dalam {target_txt} "
                    f"(video dan audio digabung ffmpeg), lalu dibuka.[/yellow]")
                download = True
                if self.interactive:
                    from rich.prompt import Confirm
                    download = Confirm.ask("  Unduh lalu buka?", default=True, console=self.console)
                if download:
                    from freease_youtube import MediaDownloader
                    # quality None + interaktif → downloader menampilkan tabel resolusi
                    res_dl = MediaDownloader(
                        url=track.src, mode="video",
                        quality=None if choose else str(self.quality),
                        output_dir=self.download_dir, interactive=choose,
                        console=self.console).run()
                    files = res_dl.get("files") or []
                    if not files:
                        return 1
                    target = files[0]["path"]
                elif url:
                    target = url
                else:
                    return 1
        if not _is_url(target):
            mime = mimetypes.guess_type(target)[0] or "video/mp4"
            if _is_termux():
                if not termux_external_allowed() and not self._enable_external_apps():
                    return 1
                target = _safe_share_path(target)
        cmd = self._opener(target, mime)
        if not cmd:
            self.console.print(
                "  [yellow]\\[!] Tidak ada cara membuka aplikasi pemutar di sistem ini. "
                "Link / file:[/yellow]")
            self.console.print(f"  {escape(target)}", soft_wrap=True)
            return 1
        rc = self._spawn(cmd)
        if rc == 0 and _is_termux():
            self.console.print("  [dim]Dibuka di aplikasi pemutar Android.[/dim]")
        return rc

    def _spawn(self, cmd: list[str]) -> int:
        """Jalankan player dengan stdio diwariskan agar kontrol keyboard aktif."""
        try:
            return subprocess.run(cmd).returncode
        except FileNotFoundError:
            self.console.print(
                f"  [red]\\[x] Perintah tidak ditemukan:[/red] {escape(cmd[0])}")
            return 127
        except KeyboardInterrupt:
            return 130

    # ── Tampilan ───────────────────────────────────────────────
    QUEUE_PAGE = 15

    def _print_queue(self) -> None:
        def render(start: int, end: int, page: int, pages: int) -> Table:
            title = "[bold]Antrian Pemutaran[/bold]"
            if pages > 1:
                title += f"  [dim]({len(self.queue)} item · halaman {page + 1}/{pages})[/dim]"
            t = Table(box=box.SIMPLE_HEAVY, title=title,
                      title_justify="left", header_style="bold cyan")
            t.add_column("#", justify="right", style="dim", width=max(3, len(str(end))))
            t.add_column("Media", overflow="fold")
            t.add_column("Jenis", width=7)
            t.add_column("Ukuran", justify="right", width=9)
            for i, tr in enumerate(self.queue[start:end], start + 1):
                kind = {"audio": "[green]audio[/green]", "video": "[magenta]video[/magenta]",
                        "url": "[cyan]url[/cyan]"}.get(tr.kind, "[dim]media[/dim]")
                size = "—" if tr.is_url else _fmt_size(Path(tr.src))
                t.add_row(str(i), escape(tr.name), kind, size)
            return t

        paginate(self.console, len(self.queue), render, page_size=self.QUEUE_PAGE,
                 label="track", done_label="selesai" if self.list_only else "mulai putar")

    def _print_controls(self) -> None:
        self.console.print(
            "  [dim]Kontrol (mpv/ffplay): "
            "[white]Space[/white] pause · [white]← →[/white] seek · "
            "[white]9 0[/white] volume · [white]q[/white] next/stop · "
            "[white]Ctrl-C[/white] keluar[/dim]\n")

    def _now_playing(self, idx: int, track: Track, backend: str) -> None:
        info = self._probe(track)
        if not info.get("duration") and track.duration:
            info["duration"] = track.duration
        mode = "audio" if (self.audio_only or not track.has_video) else "video"
        if mode == "video" and not self.has_display and backend in ("mpv", "timg", "ffmpeg-chafa"):
            mode = "video (terminal)"
        lines = [f"[bold green]\\[>] {escape(track.name)}[/bold green]"]
        meta = []
        if info.get("artist") or info.get("title"):
            tt = info.get("title") or ""
            ar = info.get("artist") or ""
            meta.append(escape(f"{ar} — {tt}".strip(" —")))
        if info.get("resolution"):
            meta.append(info["resolution"])
        if info.get("codec"):
            meta.append(info["codec"])
        meta.append(_fmt_duration(info.get("duration")))
        lines.append("[dim]" + "  ·  ".join(m for m in meta if m) + "[/dim]")
        self.console.print(Panel(
            "\n".join(lines),
            title=f"[bold]#{idx}/{len(self.queue)}  ·  {mode}  ·  engine: {backend}[/bold]",
            border_style="green", box=box.ROUNDED))


def _to_seconds(pos: str) -> Optional[float]:
    """'90' → 90 · '1:30' → 90 · '1:02:03' → 3723. None kalau formatnya salah."""
    try:
        parts = [float(x) for x in str(pos).strip().split(":")]
    except ValueError:
        return None
    if not 1 <= len(parts) <= 3 or any(x < 0 for x in parts):
        return None
    total = 0.0
    for x in parts:
        total = total * 60 + x
    return total


def _shq(s: str) -> str:
    """Quote sederhana untuk argumen di dalam `sh -c`."""
    return "'" + s.replace("'", "'\\''") + "'"


# ──────────────────────────────────────────────────────────────
#  Integrasi CLI
# ──────────────────────────────────────────────────────────────
def add_player_args(p) -> None:
    g = p.add_argument_group("Media Player")
    g.add_argument("-p", "--play", metavar="SRC", nargs="+", default=None,
                   help="File / folder / glob / URL audio-video untuk diputar")
    g.add_argument("--audio-only", action="store_true",
                   help="Putar hanya audio walau file punya video")
    g.add_argument("--loop", action="store_true",
                   help="Ulang (satu file atau seluruh playlist)")
    g.add_argument("--shuffle", action="store_true",
                   help="Acak urutan playlist")
    g.add_argument("--volume", type=int, default=100, metavar="0-200",
                   help="Volume awal persen (default: 100)")
    g.add_argument("--start", metavar="POS", default=None,
                   help="Mulai dari posisi, mis. 90 atau 1:30 (mpv/ffplay/mplayer)")
    g.add_argument("--speed", type=float, default=1.0, metavar="X",
                   help="Kecepatan putar, mis. 1.5 (mpv/mplayer)")
    g.add_argument("--subtitle", metavar="FILE", default=None,
                   help="File subtitle eksternal (mpv)")
    g.add_argument("--pl-backend", dest="pl_backend", default=None,
                   choices=["mpv", "ffplay", "mplayer", "mpg123", "cvlc", "vlc", "timg"],
                   help="Paksa backend tertentu (default: auto-detect)")
    g.add_argument("--no-recursive", dest="pl_recursive", action="store_false",
                   help="Jangan menelusuri subfolder saat sumber berupa folder")
    g.add_argument("--list", dest="pl_list", action="store_true",
                   help="Hanya tampilkan antrian, jangan diputar")
    g.add_argument("--external", "--hd", dest="pl_external", action="store_true",
                   help="Putar video HD di aplikasi pemutar (Android: VLC/mpv-android/MX; "
                        "desktop: jendela mpv) alih-alih di dalam terminal")
    g.add_argument("--pl-quality", dest="pl_quality", type=int, default=None, metavar="P",
                   help="Resolusi target untuk --external dan --pl-vo sixel/kitty "
                        "(default: 720; untuk --external yang perlu mengunduh, ditanyakan)")
    g.add_argument("--pl-vo", dest="pl_vo", default=None, choices=["tct", "sixel", "kitty"],
                   help="Cara menggambar video di terminal: tct (default, semua terminal), "
                        "sixel / kitty (piksel asli, hanya terminal yang mendukung)")


def player_from_args(args, console: Optional[Console] = None) -> MediaPlayer:
    return MediaPlayer(
        list(args.play),
        console=console,
        audio_only=getattr(args, "audio_only", False),
        loop=getattr(args, "loop", False),
        shuffle=getattr(args, "shuffle", False),
        volume=getattr(args, "volume", 100),
        start=getattr(args, "start", None),
        speed=getattr(args, "speed", 1.0),
        subtitle=getattr(args, "subtitle", None),
        backend=getattr(args, "pl_backend", None),
        recursive=getattr(args, "pl_recursive", True),
        list_only=getattr(args, "pl_list", False),
        external=getattr(args, "pl_external", False),
        quality=getattr(args, "pl_quality", None),
        vo=getattr(args, "pl_vo", None),
        download_dir=getattr(args, "yt_dir", "./freease_downloads"),
    )


def main() -> None:
    p = argparse.ArgumentParser(
        prog="freease_player",
        description="freease — pemutar audio/video terminal (Linux/Termux)")
    p.add_argument("src", nargs="*", help="File / folder / glob / URL")
    add_player_args(p)
    args = p.parse_args()
    srcs = list(args.src) + (list(args.play) if args.play else [])
    if not srcs:
        p.error("masukkan minimal satu file / folder / URL")
    args.play = srcs
    res = player_from_args(args).run()
    sys.exit(1 if res.get("error") else 0)


if __name__ == "__main__":
    main()
