#!/usr/bin/env python3
"""
freease — Modul 10: YouTube Downloader
by: Kodok-Kejepit

Download video / musik dari link YouTube biasa maupun YouTube Music.
  - Mode   : video (mp4/mkv) atau audio (mp3/m4a/opus/flac/wav)
  - Kualitas: 4K (2160p), 2K (1440p), Full HD (1080p), HD (720p), 480p … 144p
  - Engine : yt-dlp + ffmpeg (merge video+audio, konversi audio)

Bisa dipakai lewat freease.py (-y URL) atau langsung:
  python freease_youtube.py URL [--mode video|audio] [--quality 1080]

Gunakan hanya untuk konten milik sendiri atau yang memang boleh diunduh.
"""

import argparse
import re
import shutil
import sys
from pathlib import Path
from typing import Optional

from rich import box
from rich.console import Console
from rich.markup import escape
from rich.progress import (
    BarColumn, DownloadColumn, Progress, SpinnerColumn, TextColumn,
    TimeRemainingColumn, TransferSpeedColumn,
)
from rich.prompt import Confirm, Prompt
from rich.table import Table

# Resolusi standar (sisi pendek frame) → label
QUALITY_LABELS = {
    4320: "8K",
    2160: "4K",
    1440: "2K",
    1080: "Full HD",
    720:  "HD",
    480:  "SD",
    360:  "360p",
    240:  "240p",
    144:  "144p",
}

# Alias yang diterima --yt-quality
QUALITY_ALIASES = {
    "8k": 4320, "4k": 2160, "uhd": 2160, "2k": 1440, "qhd": 1440,
    "fhd": 1080, "fullhd": 1080, "hd": 1080, "sd": 480,
}

AUDIO_FORMATS  = ["mp3", "m4a", "opus", "flac", "wav"]
AUDIO_BITRATES = ["128", "192", "256", "320"]
CONTAINERS     = ["mp4", "mkv"]

DEFAULT_QUALITY = 1080

_YT_URL_RE = re.compile(
    r"^https?://((www|m|music)\.)?(youtube\.com|youtube-nocookie\.com|youtu\.be)/\S+$",
    re.IGNORECASE,
)
_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def is_youtube_url(url: str) -> bool:
    return bool(_YT_URL_RE.match(url.strip()))


def parse_quality(value) -> Optional[int]:
    """
    Ubah input kualitas ('4k', '2K', 'hd', '720p', '1080', 'best') menjadi
    tinggi dalam pixel. Return None untuk 'best' (ambil yang tertinggi).
    Raise ValueError kalau tidak dikenali.
    """
    if value is None:
        return None
    v = str(value).strip().lower().replace(" ", "")
    if v in ("best", "max", "tertinggi"):
        return None
    if v in QUALITY_ALIASES:
        return QUALITY_ALIASES[v]
    m = re.fullmatch(r"(\d{3,4})p?", v)
    if m:
        return int(m.group(1))
    raise ValueError(
        f"Kualitas '{value}' tidak dikenali. "
        "Pakai: 4k, 2k, hd/1080, 720, 480, 360, 240, 144, atau best"
    )


def quality_label(height: int) -> str:
    label = QUALITY_LABELS.get(height)
    return f"{height}p ({label})" if label and not label.endswith("p") else f"{height}p"


def _fmt_size(n: Optional[float]) -> str:
    if not n:
        return "?"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit in ("B", "KB") else f"{n:.1f} {unit}"
        n /= 1024
    return "?"


def _fmt_duration(sec: Optional[float]) -> str:
    if not sec:
        return "?"
    sec = int(sec)
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


class _QuietLogger:
    """Logger yt-dlp: sembunyikan noise, simpan warning untuk ditampilkan belakangan."""

    def __init__(self):
        self.warnings: list = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        msg = _ANSI_RE.sub("", str(msg))
        if msg not in self.warnings:
            self.warnings.append(msg)

    def error(self, msg):
        pass


class YouTubeDownloader:
    """
    Download video/audio YouTube & YouTube Music via yt-dlp.

    Alur: probe() → (opsional) choose_interactive() → download().
    run() menjalankan semuanya dan me-return dict hasil.
    """

    def __init__(
        self,
        url: str,
        mode: Optional[str] = None,
        quality=None,
        output_dir: str = "./freease_downloads",
        audio_format: Optional[str] = None,
        audio_bitrate: str = "192",
        container: str = "mp4",
        playlist: bool = False,
        interactive: Optional[bool] = None,
        console: Optional[Console] = None,
    ):
        self.url           = url.strip()
        self.mode          = mode
        self.quality       = quality
        self.output_dir    = Path(output_dir).expanduser()
        self.audio_format  = audio_format
        self.audio_bitrate = str(audio_bitrate)
        self.container     = container
        self.playlist      = playlist
        self.interactive   = sys.stdin.isatty() if interactive is None else interactive
        self.console       = console or Console()
        self.has_ffmpeg    = shutil.which("ffmpeg") is not None
        self.info: dict    = {}
        self.is_playlist   = False
        self.heights: list = []
        self._logger       = _QuietLogger()
        self.results: dict = {
            "url": self.url, "title": None, "mode": None, "quality": None,
            "files": [], "error": None,
        }

    # ── Public API ────────────────────────────────────────────

    def run(self) -> dict:
        try:
            import yt_dlp  # noqa: F401
        except ImportError:
            return self._fail("yt-dlp belum terpasang. Install: pip install -U yt-dlp")

        if not is_youtube_url(self.url):
            return self._fail(
                "Link bukan URL YouTube / YouTube Music yang valid "
                "(contoh: https://youtu.be/ID atau https://music.youtube.com/watch?v=ID)"
            )
        try:
            requested = parse_quality(self.quality)
        except ValueError as e:
            return self._fail(str(e))

        if not self.has_ffmpeg:
            self.console.print(
                "  [yellow]⚠ ffmpeg tidak ditemukan — video dibatasi ke format gabungan "
                "(biasanya ≤360p) dan audio tidak dikonversi. "
                "Install: sudo apt install ffmpeg[/yellow]"
            )

        try:
            with self.console.status("[cyan]Mengambil info dari YouTube…"):
                self.probe()
        except Exception as e:
            return self._fail(self._clean_error(e))

        self._print_info()

        try:
            mode, height = self._resolve_choices(requested)
        except (KeyboardInterrupt, EOFError):
            return self._fail("Dibatalkan pengguna")

        self.results["mode"] = mode
        self.results["quality"] = (
            f"{self.audio_format} {self.audio_bitrate}kbps" if mode == "audio"
            else quality_label(height) if height else "best"
        )

        try:
            self.download(mode, height)
        except KeyboardInterrupt:
            return self._fail("Download dibatalkan pengguna")
        except Exception as e:
            return self._fail(self._clean_error(e))

        self._print_done()
        return self.results

    def probe(self) -> dict:
        """Ambil metadata tanpa download. Playlist diambil 'flat' supaya cepat."""
        import yt_dlp

        opts = self._base_opts()
        opts.update({"skip_download": True, "extract_flat": "in_playlist"})
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(self.url, download=False)
        if not info:
            raise RuntimeError("Tidak ada info yang bisa diambil dari link tersebut")

        self.info = info
        self.is_playlist = info.get("_type") == "playlist"
        self.results["title"] = info.get("title")
        if not self.is_playlist:
            self.heights = self._available_heights(info)
        return info

    def download(self, mode: str, height: Optional[int]) -> list:
        import yt_dlp

        self.output_dir.mkdir(parents=True, exist_ok=True)
        opts = self._base_opts()
        opts.update(self._audio_opts() if mode == "audio" else self._video_opts(height))

        name = "%(title).150B [%(id)s].%(ext)s"
        if self.is_playlist:
            name = "%(playlist_title).100B/%(playlist_index)03d - " + name
        opts["outtmpl"] = str(self.output_dir / name)

        files: list = []

        with Progress(
            SpinnerColumn(), TextColumn("{task.description}"), BarColumn(),
            DownloadColumn(), TransferSpeedColumn(), TimeRemainingColumn(),
            console=self.console,
        ) as prog:
            tasks: dict = {}

            def on_progress(d: dict):
                fn = d.get("filename") or "?"
                if fn not in tasks:
                    label = Path(fn).name
                    label = label if len(label) <= 44 else label[:41] + "…"
                    tasks[fn] = prog.add_task(f"[cyan]{escape(label)}", total=None)
                tid = tasks[fn]
                total = d.get("total_bytes") or d.get("total_bytes_estimate")
                if d["status"] == "downloading":
                    prog.update(tid, completed=d.get("downloaded_bytes", 0), total=total)
                elif d["status"] == "finished":
                    done = d.get("total_bytes") or d.get("downloaded_bytes") or total
                    prog.update(tid, completed=done, total=done)

            post_task: dict = {}

            def on_post(d: dict):
                pp = d.get("postprocessor", "")
                if d["status"] == "started" and pp in (
                    "Merger", "ExtractAudio", "VideoRemuxer", "EmbedThumbnail",
                ):
                    text = {
                        "Merger":         "Menggabungkan video + audio…",
                        "ExtractAudio":   f"Konversi audio → {self.audio_format}…",
                        "VideoRemuxer":   f"Remux → {self.container}…",
                        "EmbedThumbnail": "Menyematkan cover…",
                    }[pp]
                    if "id" not in post_task:
                        post_task["id"] = prog.add_task(f"[magenta]{text}", total=None)
                    else:
                        prog.update(post_task["id"], description=f"[magenta]{text}")
                elif d["status"] == "finished" and pp == "MoveFiles":
                    fp = (d.get("info_dict") or {}).get("filepath")
                    if fp and fp not in files:
                        files.append(fp)
                    if "id" in post_task:
                        prog.remove_task(post_task.pop("id"))

            opts["progress_hooks"] = [on_progress]
            opts["postprocessor_hooks"] = [on_post]

            with yt_dlp.YoutubeDL(opts) as ydl:
                ydl.extract_info(self.url, download=True)

        self.results["files"] = [
            {"path": f, "size": Path(f).stat().st_size} for f in files if Path(f).exists()
        ]
        if not self.results["files"]:
            raise RuntimeError("Download selesai tanpa menghasilkan file")
        return files

    # ── yt-dlp options ────────────────────────────────────────

    def _base_opts(self) -> dict:
        opts = {
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "logger": self._logger,
            "noplaylist": not self.playlist,
            "ignoreerrors": "only_download" if self.playlist else False,
            "retries": 10,
            "fragment_retries": 10,
            "concurrent_fragment_downloads": 8,
            "socket_timeout": 20,
        }
        # YouTube butuh JS runtime untuk membuka semua format; pakai yang tersedia.
        runtimes = {
            name: {"path": path}
            for name, exe in (("deno", "deno"), ("node", "node"), ("bun", "bun"), ("quickjs", "qjs"))
            if (path := shutil.which(exe))
        }
        if runtimes:
            opts["js_runtimes"] = runtimes
        return opts

    def _video_opts(self, height: Optional[int]) -> dict:
        # 'res:N' = pilih resolusi terbesar yang ≤ N; kalau tidak ada, yang terdekat di atasnya.
        sort = [f"res:{height}"] if height else ["res"]
        if not self.has_ffmpeg:
            return {"format": "b", "format_sort": sort}
        if self.container == "mp4":
            sort.append("ext:mp4:m4a")
        return {
            "format": "bv*+ba/b",
            "format_sort": sort,
            "merge_output_format": self.container,
            "postprocessors": [
                {"key": "FFmpegVideoRemuxer", "preferedformat": self.container},
                {"key": "FFmpegMetadata"},
            ],
        }

    def _audio_opts(self) -> dict:
        fmt = self.audio_format
        if not self.has_ffmpeg:
            return {"format": "ba[ext=m4a]/ba/b"}
        pps = [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": fmt,
            "preferredquality": self.audio_bitrate,
        }]
        opts = {"format": "ba[ext=m4a]/ba/b" if fmt == "m4a" else "ba/b"}
        if fmt != "wav":
            pps.append({"key": "FFmpegMetadata"})
            if fmt in ("mp3", "m4a") or self._has_mutagen():
                opts["writethumbnail"] = True
                pps.append({"key": "EmbedThumbnail"})
        opts["postprocessors"] = pps
        return opts

    @staticmethod
    def _has_mutagen() -> bool:
        try:
            import mutagen  # noqa: F401
            return True
        except ImportError:
            return False

    # ── Pilihan mode & kualitas ───────────────────────────────

    def _resolve_choices(self, requested: Optional[int]) -> tuple:
        mode = self.mode
        if mode is None:
            if self.interactive:
                # Link YouTube Music → default ke audio
                default = "2" if "music.youtube.com" in self.url.lower() else "1"
                self.console.print(
                    "  [bold]Mau download apa?[/bold]\n"
                    "    [cyan]1[/cyan]  🎬 Video\n"
                    "    [cyan]2[/cyan]  🎵 Musik (audio saja)"
                )
                pick = Prompt.ask("  Pilih", choices=["1", "2"], default=default,
                                  console=self.console)
                mode = "video" if pick == "1" else "audio"
            else:
                mode = "audio" if "music.youtube.com" in self.url.lower() else "video"

        if self.is_playlist and self.interactive and not self.playlist:
            count = len(self.info.get("entries") or [])
            if not Confirm.ask(
                f"  Link ini playlist berisi [bold]{count}[/bold] item. Download semuanya?",
                default=True, console=self.console,
            ):
                raise KeyboardInterrupt
        if self.is_playlist:
            self.playlist = True

        if mode == "audio":
            if self.audio_format is None:
                self.audio_format = "mp3"
                if self.interactive:
                    self.audio_format = Prompt.ask(
                        "  Format audio", choices=AUDIO_FORMATS, default="mp3",
                        console=self.console,
                    )
            return mode, None

        # Video
        if self.quality is not None:
            height = requested
            if height and self.heights and height not in self.heights:
                lower = [h for h in self.heights if h <= height]
                actual = max(lower) if lower else min(self.heights)
                self.console.print(
                    f"  [yellow]⚠ {height}p tidak tersedia untuk video ini — "
                    f"pakai {quality_label(actual)}[/yellow]"
                )
                height = actual
            elif height is None and self.heights:
                height = max(self.heights)
            return mode, height

        options = sorted(self.heights, reverse=True) or [2160, 1440, 1080, 720, 480, 360, 240, 144]
        if not self.has_ffmpeg and self.heights:
            options = self._available_heights(self.info, progressive_only=True) or options
        fallback = [h for h in options if h <= DEFAULT_QUALITY]
        default_h = max(fallback) if fallback else min(options)
        if not self.interactive:
            return mode, default_h

        t = Table(title="Pilih kualitas video", box=box.ROUNDED, style="dim")
        t.add_column("#", style="bold cyan", justify="right")
        t.add_column("Resolusi", style="bold white")
        t.add_column("Perkiraan ukuran", justify="right")
        for i, h in enumerate(options, 1):
            size = _fmt_size(self._estimate_size(h)) if self.heights else "—"
            mark = "  [green]← default[/green]" if h == default_h else ""
            t.add_row(str(i), quality_label(h) + mark, size)
        self.console.print(t)
        pick = Prompt.ask(
            "  Pilih nomor",
            choices=[str(i) for i in range(1, len(options) + 1)],
            default=str(options.index(default_h) + 1),
            show_choices=False, console=self.console,
        )
        return mode, options[int(pick) - 1]

    @staticmethod
    def _res(f: dict) -> Optional[int]:
        """Resolusi = sisi pendek frame, supaya video vertikal (Shorts) tetap benar."""
        w, h = f.get("width"), f.get("height")
        if w and h:
            return min(w, h)
        return h

    def _available_heights(self, info: dict, progressive_only: bool = False) -> list:
        out = set()
        for f in info.get("formats") or []:
            if f.get("vcodec") in (None, "none"):
                continue
            if progressive_only and f.get("acodec") in (None, "none"):
                continue
            res = self._res(f)
            if res:
                out.add(res)
        return sorted(out)

    def _estimate_size(self, height: int) -> Optional[float]:
        fmts = self.info.get("formats") or []
        size = lambda f: f.get("filesize") or f.get("filesize_approx")  # noqa: E731
        vids = [size(f) for f in fmts
                if f.get("vcodec") not in (None, "none") and self._res(f) == height and size(f)]
        auds = [size(f) for f in fmts
                if f.get("vcodec") in (None, "none") and f.get("acodec") not in (None, "none")
                and size(f)]
        if not vids:
            return None
        return min(vids) + (max(auds) if auds else 0)

    # ── Output ────────────────────────────────────────────────

    def _print_info(self) -> None:
        i = self.info
        t = Table(box=box.ROUNDED, style="dim", show_header=False)
        t.add_column("k", style="bold yellow", width=14)
        t.add_column("v", style="white", overflow="fold")
        t.add_row("Judul", f"[bold]{escape(i.get('title') or '?')}[/bold]")
        if self.is_playlist:
            t.add_row("Tipe", "Playlist / Album")
            t.add_row("Jumlah item", str(len(i.get("entries") or [])))
            if i.get("uploader") or i.get("channel"):
                t.add_row("Channel", escape(i.get("uploader") or i.get("channel")))
        else:
            t.add_row("Channel", escape(i.get("channel") or i.get("uploader") or "?"))
            if i.get("artist"):
                t.add_row("Artis", escape(str(i["artist"])))
            t.add_row("Durasi", _fmt_duration(i.get("duration")))
            if self.heights:
                t.add_row("Resolusi", ", ".join(f"{h}p" for h in reversed(self.heights)))
        self.console.print(t)

    def _print_done(self) -> None:
        for w in self._logger.warnings[:3]:
            self.console.print(f"  [dim yellow]⚠ {escape(w[:200])}[/dim yellow]")
        for f in self.results["files"]:
            self.console.print(
                f"  [bold green]✓ Tersimpan:[/bold green] [cyan]{escape(f['path'])}[/cyan]"
                f"  [dim]({_fmt_size(f['size'])})[/dim]",
                highlight=False,
            )

    def _fail(self, msg: str) -> dict:
        self.results["error"] = msg
        self.console.print(f"  [bold red]✗ YouTube:[/bold red] [red]{escape(msg)}[/red]")
        return self.results

    @staticmethod
    def _clean_error(e: Exception) -> str:
        msg = _ANSI_RE.sub("", str(e)).strip()
        msg = re.sub(r"^ERROR:\s*", "", msg)
        return msg[:400] or e.__class__.__name__


# ── Argumen CLI (dipakai juga oleh freease.py) ────────────────

def add_youtube_args(p) -> None:
    g = p.add_argument_group("YouTube Downloader")
    g.add_argument("-y", "--youtube", metavar="URL",
                   help="Link YouTube / YouTube Music yang mau di-download")
    g.add_argument("--yt-mode", choices=["video", "audio", "musik", "music"], default=None,
                   help="video atau audio/musik (default: ditanya interaktif)")
    g.add_argument("--yt-quality", metavar="Q", default=None,
                   help="Kualitas video: 4k, 2k, hd/1080, 720, 480, 360, 240, 144, best "
                        "(default: ditanya interaktif)")
    g.add_argument("--yt-audio-format", choices=AUDIO_FORMATS, default=None,
                   help="Format audio (default: mp3)")
    g.add_argument("--yt-bitrate", choices=AUDIO_BITRATES, default="192",
                   help="Bitrate audio kbps untuk mp3/m4a/opus (default: 192)")
    g.add_argument("--yt-container", choices=CONTAINERS, default="mp4",
                   help="Container video (default: mp4)")
    g.add_argument("--yt-playlist", action="store_true",
                   help="Download seluruh playlist/album, bukan cuma satu video")
    g.add_argument("--yt-dir", default="./freease_downloads",
                   help="Folder hasil download (default: ./freease_downloads)")


def downloader_from_args(args, console: Optional[Console] = None) -> YouTubeDownloader:
    mode = args.yt_mode
    if mode in ("musik", "music"):
        mode = "audio"
    return YouTubeDownloader(
        url=args.youtube,
        mode=mode,
        quality=args.yt_quality,
        output_dir=args.yt_dir,
        audio_format=args.yt_audio_format,
        audio_bitrate=args.yt_bitrate,
        container=args.yt_container,
        playlist=args.yt_playlist,
        console=console,
    )


def main() -> None:
    p = argparse.ArgumentParser(
        prog="freease_youtube",
        description="freease — YouTube / YouTube Music downloader",
    )
    p.add_argument("url", nargs="?", help="Link YouTube / YouTube Music")
    add_youtube_args(p)
    args = p.parse_args()
    args.youtube = args.youtube or args.url
    if not args.youtube:
        p.error("masukkan link YouTube")
    res = downloader_from_args(args).run()
    sys.exit(1 if res.get("error") else 0)


if __name__ == "__main__":
    main()
