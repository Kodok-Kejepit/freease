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
import os
import random
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


class Track:
    """Satu entri antrian pemutaran."""

    __slots__ = ("src", "is_url", "kind")

    def __init__(self, src: str):
        self.src = src
        self.is_url = _is_url(src)
        if self.is_url:
            self.kind = "url"
        else:
            ext = Path(src).suffix.lower()
            self.kind = "audio" if ext in AUDIO_EXT else (
                "video" if ext in VIDEO_EXT else "media")

    @property
    def name(self) -> str:
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
        sources: list[str],
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

        self.has_display = _has_display()
        self.has_yt_dlp = shutil.which("yt-dlp") is not None
        self.has_ffprobe = shutil.which("ffprobe") is not None

        self.queue: list[Track] = self._build_queue(sources)

    # ── Pembentukan antrian ────────────────────────────────────
    def _build_queue(self, sources: list[str]) -> list[Track]:
        tracks: list[Track] = []
        for src in sources:
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
                    f"  [yellow]⚠ Dilewati (tidak ditemukan):[/yellow] {escape(src)}")
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
                f"  [yellow]⚠ Backend '{bo}' tidak ditemukan, pakai auto-detect.[/yellow]")

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

        if backend == "mpv":
            cmd = ["mpv", "--no-config", f"--volume={self.volume}",
                   f"--speed={self.speed}", "--msg-level=all=error,statusline=status"]
            if self.audio_only or not track.has_video:
                cmd.append("--no-video")
            elif term_video:
                # Render video langsung di terminal (true-color).
                cmd += ["--vo=tct", "--profile=sw-fast"]
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
                   f"-volume", str(min(100, self.volume))]
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
            return ["mpg123", "-q", src]

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

    # ── Eksekusi ───────────────────────────────────────────────
    def run(self) -> dict:
        if not self.queue:
            self.console.print("  [red]✗ Tidak ada media yang bisa diputar.[/red]")
            return {"error": "antrian kosong", "played": 0}

        self._print_queue()
        if self.list_only:
            return {"error": None, "played": 0, "queued": len(self.queue)}

        av = self._available()
        if not any(av.get(x) for x in ("mpv", "ffplay", "mplayer", "mpg123", "cvlc", "vlc")):
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
        try:
            while loop_pass:
                for idx, track in enumerate(self.queue, 1):
                    backend = self._choose_backend(track)
                    if not backend:
                        self.console.print(
                            f"  [yellow]⚠ Lewati {escape(track.name)} "
                            f"(tidak ada backend cocok).[/yellow]")
                        continue
                    self._now_playing(idx, track, backend)
                    cmd = self._build_cmd(backend, track)
                    rc = self._spawn(cmd)
                    if rc == 0:
                        played += 1
                    elif rc == 130:
                        raise KeyboardInterrupt
                    else:
                        self.console.print(
                            f"  [yellow]⚠ Player keluar dengan kode {rc}.[/yellow]")
                loop_pass = self.loop and len(self.queue) > 1
                if loop_pass:
                    self.console.print("  [dim]↻ Mengulang playlist…[/dim]\n")
        except KeyboardInterrupt:
            self.console.print("\n  [yellow]⏹ Pemutaran dihentikan.[/yellow]")

        self.console.print(
            f"\n  [green]✓ Selesai.[/green] [dim]{played}/{len(self.queue)} "
            f"track diputar.[/dim]\n")
        return {"error": None, "played": played, "queued": len(self.queue)}

    def _spawn(self, cmd: list[str]) -> int:
        """Jalankan player dengan stdio diwariskan agar kontrol keyboard aktif."""
        try:
            return subprocess.run(cmd).returncode
        except FileNotFoundError:
            self.console.print(
                f"  [red]✗ Perintah tidak ditemukan:[/red] {escape(cmd[0])}")
            return 127
        except KeyboardInterrupt:
            return 130

    # ── Tampilan ───────────────────────────────────────────────
    def _print_queue(self) -> None:
        t = Table(box=box.SIMPLE_HEAVY, title="[bold]Antrian Pemutaran[/bold]",
                  title_justify="left", header_style="bold cyan")
        t.add_column("#", justify="right", style="dim", width=3)
        t.add_column("Media", overflow="fold")
        t.add_column("Jenis", width=7)
        t.add_column("Ukuran", justify="right", width=9)
        for i, tr in enumerate(self.queue, 1):
            kind = {"audio": "[green]audio[/green]", "video": "[magenta]video[/magenta]",
                    "url": "[cyan]url[/cyan]"}.get(tr.kind, "[dim]media[/dim]")
            size = "—" if tr.is_url else _fmt_size(Path(tr.src))
            t.add_row(str(i), escape(tr.name), kind, size)
        self.console.print(t)

    def _print_controls(self) -> None:
        self.console.print(
            "  [dim]Kontrol (mpv/ffplay): "
            "[white]Space[/white] pause · [white]← →[/white] seek · "
            "[white]9 0[/white] volume · [white]q[/white] next/stop · "
            "[white]Ctrl-C[/white] keluar[/dim]\n")

    def _now_playing(self, idx: int, track: Track, backend: str) -> None:
        info = self._probe(track)
        mode = "audio" if (self.audio_only or not track.has_video) else "video"
        if mode == "video" and not self.has_display and backend in ("mpv", "timg", "ffmpeg-chafa"):
            mode = "video (terminal)"
        lines = [f"[bold green]▶ {escape(track.name)}[/bold green]"]
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
