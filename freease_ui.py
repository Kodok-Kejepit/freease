#!/usr/bin/env python3
"""
freease — antarmuka terminal bersama: tombol panah & halaman (paging)

Dipakai semua modul yang menampilkan daftar panjang (hasil pencarian, isi
halaman web, antrian player, subdomain, port terbuka, ringkasan scan URL).

Tombol di semua daftar:
    ↓  →  PgDn  Spasi   halaman berikutnya  (mis. hasil 11–20)
    ↑  ←  PgUp          halaman sebelumnya
    Home / End          halaman pertama / terakhir yang sudah dimuat
    n / p               sama dengan ↓ / ↑ (untuk terminal tanpa tombol panah)

Kalau terminal tidak interaktif (output dialihkan ke file/pipe, atau
--no-pager / FREEASE_NO_PAGER=1), semua fungsi di sini otomatis jatuh ke
perilaku biasa: daftar dicetak penuh tanpa menunggu tombol.
"""

from __future__ import annotations

import os
import sys
from typing import Callable, Optional

from rich.console import Console

# Token yang dikembalikan read_line() saat tombol navigasi ditekan di baris kosong.
NEXT = "\x00next"
PREV = "\x00prev"
FIRST = "\x00first"
LAST = "\x00last"
NAV_TOKENS = {NEXT, PREV, FIRST, LAST}

_NAV_KEYS = {
    "down": NEXT, "right": NEXT, "pgdn": NEXT,
    "up": PREV, "left": PREV, "pgup": PREV,
    "home": FIRST, "end": LAST,
}

_disabled = bool(os.environ.get("FREEASE_NO_PAGER"))


def disable_paging(flag: bool = True) -> None:
    """Dipanggil dari --no-pager."""
    global _disabled
    _disabled = flag


def is_interactive() -> bool:
    """True kalau stdin & stdout terminal sungguhan dan paging tidak dimatikan."""
    try:
        return (not _disabled) and sys.stdin.isatty() and sys.stdout.isatty()
    except (ValueError, OSError):
        return False


# ──────────────────────────────────────────────────────────────
#  Pembacaan tombol (POSIX & Windows)
# ──────────────────────────────────────────────────────────────
_CSI = {
    "A": "up", "B": "down", "C": "right", "D": "left", "H": "home", "F": "end",
}
_CSI_TILDE = {"1": "home", "7": "home", "4": "end", "8": "end", "5": "pgup", "6": "pgdn"}


def _decode_escape(read: Callable[[], Optional[str]]) -> str:
    """Terjemahkan urutan escape terminal ('[A', '[6~', 'OB', …) menjadi nama tombol."""
    first = read()
    if first is None:
        return "esc"
    if first not in ("[", "O"):
        return "esc"
    seq = ""
    while True:
        ch = read()
        if ch is None:
            return "esc"
        seq += ch
        if ch.isalpha() or ch == "~":
            break
        if len(seq) > 6:
            return "esc"
    final = seq[-1]
    if final == "~":
        return _CSI_TILDE.get(seq[:-1].split(";")[0], "esc")
    return _CSI.get(final, "esc")


def read_key() -> str:
    """
    Baca satu tombol. Hasil: 'up' 'down' 'left' 'right' 'pgup' 'pgdn' 'home' 'end'
    'enter' 'esc' 'backspace' 'tab', atau satu karakter biasa.
    Ctrl+C → KeyboardInterrupt, Ctrl+D → EOFError.
    """
    if os.name == "nt":
        return _read_key_windows()
    return _read_key_posix()


def _read_key_windows() -> str:  # pragma: no cover - hanya jalan di Windows
    import msvcrt

    ch = msvcrt.getwch()
    if ch in ("\x00", "\xe0"):
        code = msvcrt.getwch()
        return {"H": "up", "P": "down", "K": "left", "M": "right",
                "I": "pgup", "Q": "pgdn", "G": "home", "O": "end"}.get(code, "esc")
    if ch == "\x03":
        raise KeyboardInterrupt
    if ch == "\x1a":
        raise EOFError
    if ch in ("\r", "\n"):
        return "enter"
    if ch == "\x08":
        return "backspace"
    if ch == "\x1b":
        return "esc"
    return ch


def _read_key_posix() -> str:
    import select
    import termios
    import tty

    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)

    def _byte(timeout: Optional[float]) -> Optional[bytes]:
        if timeout is not None:
            ready, _, _ = select.select([fd], [], [], timeout)
            if not ready:
                return None
        data = os.read(fd, 1)
        return data or None

    def _next_char() -> Optional[str]:
        b = _byte(0.05)       # lanjutan escape datang hampir seketika
        return b.decode("latin-1") if b else None

    try:
        tty.setcbreak(fd)     # Ctrl+C tetap jadi SIGINT
        b = _byte(None)
        if b is None:
            raise EOFError
        if b == b"\x1b":
            return _decode_escape(_next_char)
        if b in (b"\r", b"\n"):
            return "enter"
        if b in (b"\x7f", b"\x08"):
            return "backspace"
        if b == b"\x04":
            raise EOFError
        if b == b"\t":
            return "tab"
        # karakter UTF-8 multi-byte
        lead = b[0]
        extra = 3 if lead >= 0xF0 else 2 if lead >= 0xE0 else 1 if lead >= 0xC0 else 0
        buf = b
        for _ in range(extra):
            nxt = _byte(0.05)
            if nxt is None:
                break
            buf += nxt
        return buf.decode("utf-8", "replace")
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)


# ──────────────────────────────────────────────────────────────
#  Prompt satu baris yang mengerti tombol panah
# ──────────────────────────────────────────────────────────────
def read_line(console: Console, prompt: str, default: str = "") -> str:
    """
    Seperti Prompt.ask(), tapi kalau baris masih kosong, tombol navigasi
    (↓ ↑ ← → PgUp PgDn Home End) langsung dikembalikan sebagai token
    NEXT / PREV / FIRST / LAST. Enter di baris kosong mengembalikan `default`.
    Di terminal non-interaktif jatuh ke input() biasa.
    """
    if not is_interactive():
        console.print(prompt, end="")
        try:
            text = input("")
        except EOFError:
            raise
        return text.strip() or default

    console.print(prompt + (f" [dim]({default})[/dim]" if default else "") + " ", end="")
    console.file.flush()
    buf: list[str] = []
    while True:
        key = read_key()
        if key == "enter":
            console.file.write("\n")
            console.file.flush()
            return "".join(buf).strip() or default
        if key == "backspace":
            if buf:
                buf.pop()
                console.file.write("\b \b")
                console.file.flush()
            continue
        if key in _NAV_KEYS and not buf:
            return _NAV_KEYS[key]
        if key == "esc":
            if buf:                      # Esc menghapus ketikan
                console.file.write("\b \b" * len(buf))
                console.file.flush()
                buf.clear()
            continue
        if len(key) == 1 and key.isprintable():
            buf.append(key)
            console.file.write(key)
            console.file.flush()


# ──────────────────────────────────────────────────────────────
#  Menghapus blok yang sudah tercetak (supaya halaman berganti di tempat)
# ──────────────────────────────────────────────────────────────
class Block:
    """
    Cetak teks Rich ke layar sambil mengingat jumlah barisnya, lalu bisa
    dihapus lagi. Dipakai agar pindah halaman mengganti tabel di tempat,
    bukan menumpuk tabel baru di bawah yang lama.
    """

    def __init__(self, console: Console):
        self.console = console
        self.lines = 0

    def draw(self, renderable) -> None:
        with self.console.capture() as cap:
            self.console.print(renderable)
        text = cap.get()
        self.lines = text.count("\n")
        self.console.file.write(text)
        self.console.file.flush()

    def erase(self, extra_lines_below: int = 0) -> None:
        """Hapus blok dan baris prompt di bawahnya (kursor ada di baris prompt)."""
        if not self.lines or not self.console.is_terminal:
            return
        up = self.lines + extra_lines_below
        self.console.file.write(f"\r\x1b[{up}A\x1b[J")
        self.console.file.flush()
        self.lines = 0


def clip(text: str, width: int) -> str:
    """Potong teks supaya satu baris prompt tidak melipat ke baris berikutnya."""
    return text if len(text) <= width else text[: max(1, width - 1)] + "…"


# ──────────────────────────────────────────────────────────────
#  Pager untuk daftar yang sudah lengkap di memori
# ──────────────────────────────────────────────────────────────
def paginate(
    console: Console,
    total: int,
    render: Callable[[int, int, int, int], object],
    *,
    page_size: int = 10,
    label: str = "item",
    done_label: str = "selesai",
) -> None:
    """
    Tampilkan daftar `total` item per halaman dengan tombol panah.

    render(start, end, page, pages) harus mengembalikan renderable Rich untuk
    item [start, end). Kalau semua item muat dalam satu halaman, atau terminal
    tidak interaktif, semuanya dicetak sekaligus tanpa menunggu tombol.
    """
    page_size = max(1, page_size)
    pages = max(1, -(-total // page_size))
    if total <= page_size or not is_interactive():
        console.print(render(0, total, 0, 1))
        return

    block = Block(console)
    page, notice = 0, ""
    while True:
        start = page * page_size
        end = min(total, start + page_size)
        block.draw(render(start, end, page, pages))
        width = max(30, console.width - 2)
        keys = (["↓ berikutnya"] if page + 1 < pages else []) + \
               (["↑ sebelumnya"] if page > 0 else []) + [f"Enter {done_label}"]
        hint = (f"{label} {start + 1}–{end} dari {total} · halaman {page + 1}/{pages} · "
                + " · ".join(keys))
        if notice:
            hint = notice
        console.print(f"  [dim]{clip(hint, width)}[/dim] ", end="")
        console.file.flush()
        notice = ""
        try:
            key = read_key()
        except (KeyboardInterrupt, EOFError):
            console.file.write("\n")
            return
        if key in ("enter", "q", "esc"):
            console.file.write("\n")
            return
        if key in ("down", "right", "pgdn", " ", "n"):
            if page + 1 < pages:
                page += 1
            else:
                notice = f"Sudah di halaman terakhir · ↑ sebelumnya · Enter {done_label}"
        elif key in ("up", "left", "pgup", "p"):
            if page > 0:
                page -= 1
            else:
                notice = f"Sudah di halaman pertama · ↓ berikutnya · Enter {done_label}"
        elif key == "home":
            page = 0
        elif key == "end":
            page = pages - 1
        else:
            block.erase(extra_lines_below=0)
            continue
        block.erase(extra_lines_below=0)
