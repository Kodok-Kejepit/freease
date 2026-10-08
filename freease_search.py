#!/usr/bin/env python3
"""
freease — Modul 13: Search
by: Kodok-Kejepit

Cari lagu / video dari judul atau penggalan lirik, atau cari apa saja di web,
lalu langsung putar, unduh, buka, atau periksa keamanan link hasil pilihan.

  Sumber pencarian:
    yt   YouTube         (paling cocok untuk penggalan lirik)
    ytm  YouTube Music   (hanya bagian "Songs": lagu resmi, bukan video)
    sc   SoundCloud
    web  Web (DuckDuckGo) — halaman web biasa, tanpa API key

Contoh:
  python freease.py -S "dewa 19 kangen"                     (sumber ditanyakan)
  python freease.py -S "aku yang dulu bukanlah yang sekarang" --search-source yt
  python freease.py -S "hindia evaluasi" --search-source ytm --search-action music
  python freease.py -S "cara install termux" --search-source web
  python freease_search.py "tulus hati-hati di jalan"

Mode interaktif: hasil ditampilkan sebagai tabel bernomor, pilih nomor
(1 / 1,3 / 2-5 / a = semua), lalu pilih aksi. Setelah selesai, daftar hasil
ditampilkan lagi sampai Anda keluar dengan q. Ketik c untuk cari kata kunci lain.
"""

from __future__ import annotations

import argparse
import html as _html
import http.client
import json
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from typing import Optional

from rich import box
from rich.console import Console
from rich.markup import escape
from rich.prompt import Confirm, Prompt
from rich.table import Table

from freease_player import MediaPlayer, Track, _is_termux
from freease_youtube import MediaDownloader, detect_platform

SOURCES = {
    "yt":  "YouTube",
    "ytm": "YouTube Music",
    "sc":  "SoundCloud",
    "web": "Web (DuckDuckGo)",
}
MEDIA_SOURCES = ("yt", "ytm", "sc")

# aksi untuk hasil media: key → (nama, deskripsi)
ACTIONS = {
    "p": ("play",       "putar audio"),
    "v": ("play-video", "putar video di terminal"),
    "h": ("play-hd",    "putar video HD di aplikasi"),
    "m": ("music",      "unduh musik (audio)"),
    "d": ("video",      "unduh video"),
}
# aksi tambahan untuk hasil web
WEB_ACTIONS = {
    "j": ("explore", "jelajahi isi halaman"),
    "s": ("scan", "cek keamanan link"),
    "o": ("open", "buka di browser"),
    "l": ("link", "tampilkan link lengkap"),
}
ACTION_BY_NAME = {name: key for key, (name, _) in {**ACTIONS, **WEB_ACTIONS}.items()}

BROWSER_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0 Safari/537.36"
)


def _fmt_duration(sec) -> str:
    try:
        sec = int(float(sec))
    except (TypeError, ValueError):
        return "—"
    if sec <= 0:
        return "—"
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def _fmt_views(n) -> str:
    if not isinstance(n, (int, float)) or n <= 0:
        return "—"
    for div, suf in ((1e9, " M"), (1e6, " jt"), (1e3, " rb")):
        if n >= div:
            return f"{n / div:.1f}".rstrip("0").rstrip(".") + suf
    return str(int(n))


def parse_selection(text: str, count: int) -> list[int]:
    """
    '3' → [3], '1,4' → [1, 4], '2-5' → [2, 3, 4, 5], 'a' → semua.
    Nomor di luar jangkauan diabaikan; urutan dan duplikat dirapikan.
    """
    text = text.strip().lower()
    if text in ("a", "all", "semua"):
        return list(range(1, count + 1))
    picked: list[int] = []
    for part in re.split(r"[,\s]+", text):
        if not part:
            continue
        m = re.fullmatch(r"(\d+)-(\d+)", part)
        if m:
            lo, hi = sorted((int(m.group(1)), int(m.group(2))))
            nums = range(lo, hi + 1)
        elif part.isdigit():
            nums = [int(part)]
        else:
            raise ValueError(f"'{part}' bukan nomor yang valid")
        for n in nums:
            if 1 <= n <= count and n not in picked:
                picked.append(n)
    return picked


# ──────────────────────────────────────────────────────────────
#  Pencarian web: DuckDuckGo versi HTML (tanpa JavaScript, tanpa API key)
# ──────────────────────────────────────────────────────────────
class _DDGParser(HTMLParser):
    """
    Ambil hasil dari dua tampilan DuckDuckGo:
      html.duckduckgo.com  →  <a class="result__a">  +  class="result__snippet"
      lite.duckduckgo.com  →  <a class="result-link"> +  <td class="result-snippet">
    """

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.results: list[dict] = []
        self._cur: Optional[str] = None     # "title" | "snippet"
        self._buf: list[str] = []
        self._href: Optional[str] = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        cls = a.get("class") or ""
        if tag == "a" and ("result__a" in cls or "result-link" in cls):
            self._cur, self._buf, self._href = "title", [], a.get("href")
        elif "result__snippet" in cls or "result-snippet" in cls:
            self._cur, self._buf = "snippet", []

    def handle_endtag(self, tag):
        if self._cur == "title" and tag == "a":
            self.results.append({"title": " ".join("".join(self._buf).split()),
                                 "href": self._href or "", "snippet": ""})
            self._cur = None
        elif self._cur == "snippet" and tag in ("a", "td", "div"):
            if self.results and not self.results[-1]["snippet"]:
                self.results[-1]["snippet"] = " ".join("".join(self._buf).split())
            self._cur = None

    def handle_data(self, data):
        if self._cur:
            self._buf.append(data)


def _unwrap_ddg(href: str) -> Optional[str]:
    """//duckduckgo.com/l/?uddg=<url-asli>&rut=… → url asli. Iklan (y.js) dibuang."""
    href = _html.unescape(href or "").strip()
    if not href:
        return None
    if href.startswith("//"):
        href = "https:" + href
    elif href.startswith("/"):
        href = "https://duckduckgo.com" + href
    p = urllib.parse.urlparse(href)
    if p.netloc.endswith("duckduckgo.com"):
        if p.path.startswith("/y.js"):
            return None                      # tautan iklan
        target = urllib.parse.parse_qs(p.query).get("uddg", [None])[0]
        return target
    return href if p.scheme in ("http", "https") else None


def parse_ddg_html(page: str) -> list[dict]:
    parser = _DDGParser()
    parser.feed(page)
    out, seen = [], set()
    for r in parser.results:
        url = _unwrap_ddg(r["href"])
        if not url or url in seen or not r["title"]:
            continue
        seen.add(url)
        out.append({
            "title":   r["title"],
            "url":     url,
            "domain":  urllib.parse.urlparse(url).netloc.removeprefix("www."),
            "snippet": r["snippet"],
        })
    return out


# DNS-over-HTTPS lewat alamat IP langsung, supaya permintaan DNS-nya sendiri
# tidak ikut dibelokkan resolver ISP.
_DOH = (
    ("https://1.1.1.1/dns-query?name={host}&type=A", {"Accept": "application/dns-json"}),
    ("https://8.8.8.8/resolve?name={host}&type=A", {}),
)


def doh_resolve(host: str, timeout: float = 8) -> list[str]:
    for url, headers in _DOH:
        try:
            req = urllib.request.Request(url.format(host=host),
                                         headers={"User-Agent": BROWSER_UA, **headers})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = json.loads(resp.read().decode("utf-8", "replace"))
            ips = [a["data"] for a in data.get("Answer", []) if a.get("type") == 1]
            if ips:
                return ips
        except Exception:
            continue
    return []


class _PinnedHTTPS(http.client.HTTPSConnection):
    """Sambung ke IP tertentu, tapi SNI & verifikasi sertifikat tetap atas nama host asli."""

    def __init__(self, host: str, ip: str, timeout: float):
        super().__init__(host, 443, timeout=timeout)
        self._ip = ip
        self._ctx = ssl.create_default_context()

    def connect(self):
        raw = socket.create_connection((self._ip, 443), self.timeout)
        self.sock = self._ctx.wrap_socket(raw, server_hostname=self.host)


def _post(url: str, data: bytes, timeout: float, ip: Optional[str] = None) -> str:
    headers = {
        "User-Agent": BROWSER_UA,
        "Content-Type": "application/x-www-form-urlencoded",
        "Referer": url,
    }
    if ip is None:
        req = urllib.request.Request(url, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read().decode("utf-8", "replace")
    u = urllib.parse.urlparse(url)
    conn = _PinnedHTTPS(u.hostname, ip, timeout)
    try:
        conn.request("POST", u.path or "/", body=data, headers=headers)
        resp = conn.getresponse()
        if resp.status >= 400:
            raise RuntimeError(f"HTTP {resp.status}")
        return resp.read().decode("utf-8", "replace")
    finally:
        conn.close()


_MAX_PAGE = 3 * 1024 * 1024


def fetch_page_text(url: str, timeout: float = 15) -> tuple[str, str]:
    """
    GET sebuah halaman (maks 3 MB). Kembalikan (url_akhir, html).
    Kalau koneksi dibelokkan jaringan, ulangi lewat IP dari DNS-over-HTTPS
    dengan verifikasi sertifikat tetap aktif. Tidak ada JavaScript yang dijalankan.
    """
    headers = {"User-Agent": BROWSER_UA, "Accept": "text/html,application/xhtml+xml,*/*;q=0.8"}
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            charset = resp.headers.get_content_charset() or "utf-8"
            return resp.geturl(), resp.read(_MAX_PAGE).decode(charset, "replace")
    except Exception as e:
        if not _is_intercepted(e) or not url.startswith("https://"):
            raise
    u = urllib.parse.urlparse(url)
    last: Optional[Exception] = None
    for ip in doh_resolve(u.hostname)[:3]:
        conn = _PinnedHTTPS(u.hostname, ip, timeout)
        try:
            path = (u.path or "/") + (f"?{u.query}" if u.query else "")
            conn.request("GET", path, headers={**headers, "Host": u.hostname})
            resp = conn.getresponse()
            if resp.status >= 400:
                raise RuntimeError(f"HTTP {resp.status}")
            return url, resp.read(_MAX_PAGE).decode("utf-8", "replace")
        except Exception as e2:
            last = e2
        finally:
            conn.close()
    raise RuntimeError("Koneksi dibelokkan jaringan dan jalur DNS-over-HTTPS gagal"
                       + (f": {last}" if last else ""))


# ──────────────────────────────────────────────────────────────
#  Jelajahi isi halaman: ambil link & media yang tertanam
# ──────────────────────────────────────────────────────────────
_MEDIA_EXT = (".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".ts", ".m3u8", ".mpd",
              ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav")
_ASSET_EXT = (".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp",
              ".woff", ".woff2", ".ttf", ".json", ".xml", ".rss", ".pdf", ".zip", ".apk", ".exe")


class _LinkParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = ""
        self.links: list[tuple[str, str, str]] = []   # (url, teks, jenis)
        self._in_title = False
        self._a_href: Optional[str] = None
        self._a_text: list[str] = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "title":
            self._in_title = True
        elif tag == "a" and a.get("href"):
            self._a_href, self._a_text = a["href"], []
        elif tag in ("video", "audio", "source") and a.get("src"):
            self.links.append((a["src"], a.get("title") or "", "media"))
        elif tag in ("iframe", "embed") and a.get("src"):
            self.links.append((a["src"], a.get("title") or "", "embed"))

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
        elif tag == "a" and self._a_href is not None:
            text = " ".join("".join(self._a_text).split())
            self.links.append((self._a_href, text, "link"))
            self._a_href = None

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        if self._a_href is not None:
            self._a_text.append(data)


def explore_page(url: str, limit: int = 80, timeout: float = 15) -> tuple[str, list[dict]]:
    """
    Buka satu halaman (tanpa JavaScript) dan kumpulkan isinya:
      media langsung (<video>, file .mp4/.m3u8/.mp3, link yang dikenali yt-dlp),
      player tertanam (<iframe>), lalu halaman lain di situs yang sama dan situs luar.
    """
    final_url, page = fetch_page_text(url, timeout)
    parser = _LinkParser()
    try:
        parser.feed(page)
    except Exception:
        pass
    base_host = urllib.parse.urlparse(final_url).netloc.removeprefix("www.")
    seen: set[str] = {final_url.split("#")[0]}
    buckets: dict[str, list[dict]] = {"media": [], "embed": [], "same": [], "other": []}
    for raw, text, kind in parser.links:
        raw = (raw or "").strip()
        if not raw or raw.startswith(("#", "javascript:", "mailto:", "tel:", "data:")):
            continue
        full = urllib.parse.urljoin(final_url, raw).split("#")[0]
        p = urllib.parse.urlparse(full)
        if p.scheme not in ("http", "https") or full in seen:
            continue
        path = p.path.lower()
        if path.endswith(_ASSET_EXT):
            continue
        seen.add(full)
        domain = p.netloc.removeprefix("www.")
        plat = detect_platform(full)
        is_media = kind == "media" or path.endswith(_MEDIA_EXT) or bool(plat and plat != "generic")
        if is_media:
            bucket, label = "media", "media langsung" if kind == "media" or path.endswith(_MEDIA_EXT) \
                else f"media ({plat})"
        elif kind == "embed":
            bucket, label = "embed", "player tertanam (iframe)"
        elif domain == base_host:
            bucket, label = "same", "halaman di situs ini"
        else:
            bucket, label = "other", "halaman luar"
        title = text or urllib.parse.unquote(p.path.rstrip("/").rsplit("/", 1)[-1]) or domain
        buckets[bucket].append({"title": title[:150], "url": full, "domain": domain,
                                "snippet": label, "media": is_media})
    items = buckets["media"] + buckets["embed"] + buckets["same"] + buckets["other"]
    return " ".join(parser.title.split()) or base_host, items[:limit]


def _is_intercepted(e: BaseException) -> bool:
    """Sertifikat tidak cocok / tidak tepercaya = koneksi dibelokkan di jaringan."""
    reason = getattr(e, "reason", e)
    return isinstance(reason, ssl.SSLCertVerificationError) or "CERTIFICATE_VERIFY_FAILED" in str(e)


def ddg_search(query: str, limit: int = 10, region: str = "wt-wt",
               timeout: float = 15) -> list[dict]:
    """
    Cari lewat DuckDuckGo versi HTML, cadangan versi lite.

    Kalau koneksi biasa dibelokkan (sertifikat tidak cocok, ciri pemblokiran DNS
    oleh ISP), IP asli dicari lewat DNS-over-HTTPS lalu disambung langsung dengan
    sertifikat tetap diverifikasi. Verifikasi TLS tidak pernah dimatikan.
    """
    data = urllib.parse.urlencode({"q": query, "kl": region}).encode()
    blocked_bot = False
    intercepted = False
    errors: list[str] = []
    for endpoint in ("https://html.duckduckgo.com/html/", "https://lite.duckduckgo.com/lite/"):
        host = urllib.parse.urlparse(endpoint).hostname
        pages: list[str] = []
        try:
            pages.append(_post(endpoint, data, timeout))
        except Exception as e:
            if _is_intercepted(e):
                intercepted = True
                for ip in doh_resolve(host)[:3]:
                    try:
                        pages.append(_post(endpoint, data, timeout, ip=ip))
                        break
                    except Exception as e2:
                        errors.append(f"{host} via {ip}: {str(e2)[:120]}")
            else:
                errors.append(f"{host}: {str(getattr(e, 'reason', e))[:120]}")
        for page in pages:
            results = parse_ddg_html(page)
            if results:
                return results[:limit]
            low = page.lower()
            blocked_bot = blocked_bot or "anomaly" in low or "captcha" in low
    if blocked_bot:
        raise RuntimeError("DuckDuckGo meminta verifikasi anti-bot; coba lagi beberapa menit lagi")
    if intercepted:
        raise RuntimeError(
            "Koneksi ke DuckDuckGo dibelokkan oleh jaringan (sertifikat yang diterima bukan "
            "milik DuckDuckGo). Ini biasanya tanda pemblokiran oleh ISP. Jalur DNS-over-HTTPS "
            "juga gagal" + (f" ({errors[-1]})" if errors else "") +
            ". Coba lewat VPN atau aplikasi 1.1.1.1 (WARP), atau pakai sumber YouTube.")
    if errors:
        raise RuntimeError("; ".join(errors[:2]))
    return []


def open_in_browser(url: str) -> bool:
    """Buka URL di browser sistem. True kalau perintahnya berhasil dijalankan."""
    if _is_termux() and shutil.which("termux-open-url"):
        cmd = ["termux-open-url", url]
    elif shutil.which("xdg-open") and (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        cmd = ["xdg-open", url]
    elif sys.platform == "darwin" and shutil.which("open"):
        cmd = ["open", url]
    else:
        return False
    try:
        return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    except OSError:
        return False


# ──────────────────────────────────────────────────────────────
#  Pencarian + aksi
# ──────────────────────────────────────────────────────────────
class MediaSearch:
    """Cari → tampilkan → pilih → putar / unduh / buka / cek keamanan."""

    def __init__(
        self,
        query: str,
        *,
        source: Optional[str] = None,
        limit: int = 10,
        action: Optional[str] = None,
        pick: Optional[str] = None,
        region: str = "wt-wt",
        interactive: Optional[bool] = None,
        console: Optional[Console] = None,
        download_opts: Optional[dict] = None,
        player_opts: Optional[dict] = None,
    ):
        self.query = query.strip()
        self.source = source if source in SOURCES else None
        self.limit = max(1, min(50, limit))
        self.action = action
        self.pick = pick
        self.region = region
        self.interactive = sys.stdin.isatty() if interactive is None else interactive
        self.console = console or Console()
        self.download_opts = download_opts or {}
        self.player_opts = player_opts or {}
        self.results: list[dict] = []
        self._label: Optional[str] = None          # judul tabel saat menjelajah halaman
        self._stack: list[tuple] = []               # riwayat untuk tombol k (kembali)

    @property
    def is_web(self) -> bool:
        return self.source == "web"

    # ── Pilih sumber ──────────────────────────────────────────
    def _ask_source(self) -> str:
        keys = list(SOURCES)
        self.console.print("  [bold]Cari di mana?[/bold]")
        for i, k in enumerate(keys, 1):
            self.console.print(f"    [cyan]{i}[/cyan]  {SOURCES[k]}")
        pick = Prompt.ask("  Pilih", choices=[str(i) for i in range(1, len(keys) + 1)],
                          default="1", console=self.console)
        return keys[int(pick) - 1]

    # ── Pencarian ─────────────────────────────────────────────
    def _search_target(self) -> str:
        q = self.query
        if self.source == "ytm":
            return "https://music.youtube.com/search?q=" + urllib.parse.quote_plus(q) + "#songs"
        if self.source == "sc":
            return f"scsearch{self.limit}:{q}"
        return f"ytsearch{self.limit}:{q}"

    def search(self) -> list[dict]:
        if self.is_web:
            self.results = ddg_search(self.query, self.limit, self.region)
            for r in self.results:
                plat = detect_platform(r["url"])
                r["media"] = bool(plat and plat != "generic")
            return self.results

        import yt_dlp

        opts = {
            "quiet": True, "no_warnings": True, "skip_download": True,
            "extract_flat": "in_playlist", "playlistend": self.limit,
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(self._search_target(), download=False)
        out = []
        for e in (info or {}).get("entries") or []:
            if not isinstance(e, dict):
                continue
            url = e.get("webpage_url") or e.get("url")
            if not url:
                continue
            if not url.startswith("http") and e.get("ie_key", "").startswith("Youtube"):
                url = f"https://www.youtube.com/watch?v={e.get('id') or url}"
            artists = e.get("artists") or ([e["artist"]] if e.get("artist") else [])
            out.append({
                "title":    e.get("title") or "(tanpa judul)",
                "url":      url,
                "channel":  ", ".join(artists) if artists else
                            (e.get("channel") or e.get("uploader") or "—"),
                "duration": e.get("duration"),
                "views":    e.get("view_count"),
                "media":    True,
            })
            if len(out) >= self.limit:
                break
        self.results = out
        return out

    # ── Tampilan ──────────────────────────────────────────────
    def _print_results(self) -> None:
        t = Table(box=box.SIMPLE_HEAVY, header_style="bold cyan",
                  title=(f"[bold]{escape(self._label)}[/bold]" if self._label else
                         f"[bold]Hasil pencarian {SOURCES[self.source]}:[/bold] "
                         f"[white]{escape(self.query)}[/white]"),
                  title_justify="left")
        t.add_column("#", justify="right", style="bold cyan", width=3)
        if self.is_web:
            t.add_column("Judul / Cuplikan", overflow="fold", ratio=4)
            t.add_column("Situs", overflow="fold", ratio=2, style="green")
            for i, r in enumerate(self.results, 1):
                title = f"[bold]{escape(r['title'])}[/bold]"
                if r.get("snippet"):
                    title += f"\n[dim]{escape(r['snippet'][:180])}[/dim]"
                site = escape(r["domain"]) + ("\n[magenta]media[/magenta]" if r.get("media") else "")
                t.add_row(str(i), title, site)
        else:
            t.add_column("Judul", overflow="fold", ratio=3)
            t.add_column("Channel / Artis", overflow="fold", ratio=2, style="dim")
            t.add_column("Durasi", justify="right", width=8)
            t.add_column("Ditonton", justify="right", width=9, style="dim")
            for i, r in enumerate(self.results, 1):
                title = escape(r["title"])
                if i == 1:
                    title = f"[bold]{title}[/bold] [green](teratas)[/green]"
                t.add_row(str(i), title, escape(r["channel"]),
                          _fmt_duration(r["duration"]), _fmt_views(r["views"]))
        self.console.print(t)
        if self.is_web:
            self.console.print("  [dim]Label [magenta]media[/magenta] = link bisa langsung "
                               "diputar / diunduh.[/dim]")

    # ── Aksi ──────────────────────────────────────────────────
    def _do(self, action: str, items: list[dict]) -> None:
        if action == "link":
            for r in items:
                self.console.print(f"  {escape(r['url'])}", soft_wrap=True, highlight=False)
            return

        if action == "scan":
            from freease_scan import URLScanner, render_result
            for r in items:
                with self.console.status(f"[cyan]Memeriksa {escape(r['domain'] if 'domain' in r else r['url'][:50])}…"):
                    res = URLScanner(r["url"], console=self.console).scan()
                render_result(res, self.console)
            return

        if action == "open":
            from freease_scan import URLScanner
            for r in items:
                # Cek cepat tanpa jaringan sebelum membuka — link berisiko diminta konfirmasi
                res = URLScanner(r["url"], console=self.console, offline=True).scan()
                if res["score"] >= 25:
                    self.console.print(
                        f"  [yellow]\\[!] {escape(r['url'][:80])} dinilai {res['verdict']} "
                        f"(skor {res['score']}). Pakai aksi s untuk pemeriksaan lengkap.[/yellow]")
                    if not (self.interactive and Confirm.ask("  Tetap buka?", default=False,
                                                             console=self.console)):
                        continue
                if open_in_browser(r["url"]):
                    self.console.print(f"  [green]\\[+] Dibuka:[/green] {escape(r['url'][:90])}")
                else:
                    self.console.print("  [yellow]\\[!] Tidak ada browser yang bisa dibuka "
                                       "dari sini. Link:[/yellow]")
                    self.console.print(f"  {escape(r['url'])}", soft_wrap=True, highlight=False)
            return

        if action == "explore":
            self._explore(items)
            return

        media = [r for r in items if r.get("media")]
        skipped = [r for r in items if not r.get("media")]
        if len(skipped) == 1:
            self.console.print(f"  [yellow]\\[!] Dilewati, bukan link media: "
                               f"{escape(skipped[0]['url'][:80])}[/yellow]")
        elif skipped:
            self.console.print(f"  [yellow]\\[!] {len(skipped)} item dilewati karena bukan link "
                               f"media (pakai j untuk menjelajahi isinya).[/yellow]")
        if not media:
            return

        if action in ("play", "play-video", "play-hd"):
            tracks = [Track(r["url"], title=r["title"], duration=r.get("duration")) for r in media]
            MediaPlayer(tracks, console=self.console, interactive=self.interactive,
                        audio_only=(action == "play"), external=(action == "play-hd"),
                        **self.player_opts).run()
            return

        mode = "audio" if action == "music" else "video"
        for r in media:
            self.console.print(f"\n  [bold cyan]\\[>][/bold cyan] [bold]{escape(r['title'])}[/bold]")
            MediaDownloader(url=r["url"], mode=mode, interactive=self.interactive,
                            console=self.console, **self.download_opts).run()

    def _explore(self, items: list[dict]) -> None:
        if len(items) > 1:
            self.console.print("  [dim]Menjelajah dilakukan satu halaman sekali jalan; "
                               "yang dibuka nomor pertama yang dipilih.[/dim]")
        r = items[0]
        from freease_scan import URLScanner
        res = URLScanner(r["url"], console=self.console, offline=True).scan()
        if res["score"] >= 25:
            self.console.print(
                f"  [yellow]\\[!] {escape(r['url'][:80])} dinilai {res['verdict']} "
                f"(skor {res['score']}).[/yellow]")
            if not (self.interactive and Confirm.ask("  Tetap jelajahi?", default=False,
                                                     console=self.console)):
                return
        try:
            with self.console.status(f"[cyan]Membaca halaman {escape(r['url'][:60])}…"):
                title, found = explore_page(r["url"])
        except Exception as e:
            self.console.print(f"  [red]\\[x] Gagal membuka halaman: {escape(str(e)[:200])}[/red]")
            return
        if not found:
            self.console.print("  [yellow]\\[!] Tidak ada link yang bisa diambil dari halaman ini. "
                               "Situs yang memuat isinya dengan JavaScript tidak bisa dibaca "
                               "tanpa browser.[/yellow]")
            return
        n_media = sum(1 for x in found if x["media"])
        self._stack.append((self.source, self.query, self.results, self._label))
        self.source, self.results = "web", found
        self._label = f"Isi halaman: {title[:70]}  ({n_media} media, {len(found)} link)"

    def _go_back(self) -> bool:
        if not self._stack:
            return False
        self.source, self.query, self.results, self._label = self._stack.pop()
        return True

    def _ask_action(self, count: int = 1) -> Optional[str]:
        menu = dict(ACTIONS)
        if self.is_web:
            menu = {**WEB_ACTIONS, **ACTIONS}
        line = "  ".join(f"[cyan]{k}[/cyan] {desc}" for k, (_, desc) in menu.items())
        self.console.print(f"  {line}  [cyan]b[/cyan] batal", soft_wrap=False)
        label = f"  Aksi untuk {count} item terpilih" if count > 1 else "  Aksi"
        key = Prompt.ask(label, choices=[*menu, "b"], default="o" if self.is_web else "p",
                         show_choices=False, console=self.console)
        return None if key == "b" else menu[key][0]

    # ── Orkestrasi ────────────────────────────────────────────
    def _run_search(self) -> Optional[dict]:
        label = SOURCES[self.source]
        try:
            with self.console.status(f"[cyan]Mencari di {label}: {escape(self.query[:60])}…"):
                self.search()
        except Exception as e:
            msg = re.sub(r"^ERROR:\s*", "", re.sub(r"\x1b\[[0-9;]*m", "", str(e))).strip()
            return self._fail(f"Pencarian gagal: {msg[:300]}")
        if not self.results:
            return self._fail("Tidak ada hasil. Coba kata kunci lain atau sumber lain.")
        self._print_results()
        return None

    def run(self) -> dict:
        if self.source in MEDIA_SOURCES or self.source is None:
            try:
                import yt_dlp  # noqa: F401
            except ImportError:
                if self.source is not None:
                    return self._fail("yt-dlp belum terpasang. Install: pip install -U yt-dlp")
        if not self.query:
            return self._fail("Kata kunci pencarian kosong")

        if self.source is None:
            if self.interactive:
                try:
                    self.source = self._ask_source()
                except (KeyboardInterrupt, EOFError):
                    return self._fail("Dibatalkan pengguna")
            else:
                self.source = "yt"

        failed = self._run_search()
        if failed:
            return failed

        # Non-interaktif: jalankan sekali sesuai --search-action / --search-pick
        if not self.interactive or self.action:
            if not self.action or self.action == "list":
                return {"error": None, "results": self.results}
            try:
                picks = parse_selection(self.pick or "1", len(self.results))
            except ValueError as e:
                return self._fail(str(e))
            if not picks:
                return self._fail("Nomor pilihan di luar daftar hasil")
            self._do(self.action, [self.results[i - 1] for i in picks])
            if self.action == "explore" and self._label:
                self._print_results()
            return {"error": None, "results": self.results}

        # Interaktif: pilih → aksi → kembali ke daftar
        while True:
            try:
                back = ", k=kembali" if self._stack else ""
                text = Prompt.ask(
                    f"  Pilih nomor [dim](1 / 1,3 / 2-5 / a=semua, c=cari lagi{back}, q=keluar)[/dim]",
                    default="1", console=self.console)
                cmd = text.strip().lower()
                if cmd in ("q", "quit", "keluar", "x"):
                    break
                if cmd in ("k", "kembali"):
                    if self._go_back():
                        self._print_results()
                    else:
                        self.console.print("  [dim]Sudah di daftar paling awal.[/dim]")
                    continue
                if cmd in ("c", "cari"):
                    new_q = Prompt.ask("  Kata kunci baru", console=self.console).strip()
                    if new_q:
                        self.query = new_q
                        self.source = self._ask_source()
                        self._label, self._stack = None, []
                        self._run_search()
                    continue
                try:
                    picks = parse_selection(text, len(self.results))
                except ValueError as e:
                    self.console.print(f"  [yellow]\\[!] {escape(str(e))}[/yellow]")
                    continue
                if not picks:
                    self.console.print("  [yellow]\\[!] Nomor di luar daftar hasil[/yellow]")
                    continue
                action = self._ask_action(len(picks))
                if action:
                    self._do(action, [self.results[i - 1] for i in picks])
                    if self.results:
                        self._print_results()
            except (KeyboardInterrupt, EOFError):
                self.console.print()
                break
        return {"error": None, "results": self.results}

    def _fail(self, msg: str) -> dict:
        self.console.print(f"  [bold red]\\[x] Search:[/bold red] [red]{escape(msg)}[/red]")
        return {"error": msg, "results": self.results}


# ──────────────────────────────────────────────────────────────
#  Integrasi CLI
# ──────────────────────────────────────────────────────────────
def add_search_args(p) -> None:
    g = p.add_argument_group("Search")
    g.add_argument("-S", "--search", metavar="QUERY", default=None,
                   help="Cari lagu/video dari judul atau penggalan lirik, atau cari di web")
    g.add_argument("--search-source", dest="search_source", choices=list(SOURCES), default=None,
                   help="Sumber: yt (YouTube), ytm (YouTube Music), sc (SoundCloud), "
                        "web (DuckDuckGo). Kalau kosong: ditanyakan")
    g.add_argument("--search-limit", dest="search_limit", type=int, default=10, metavar="N",
                   help="Jumlah hasil yang ditampilkan (default: 10, maks 50)")
    g.add_argument("--search-action", dest="search_action", default=None,
                   choices=["list", *ACTION_BY_NAME],
                   help="Langsung jalankan aksi tanpa menu: list, play, play-video, play-hd, "
                        "music, video, scan, open, link, explore")
    g.add_argument("--search-pick", dest="search_pick", default=None, metavar="SEL",
                   help="Nomor hasil untuk --search-action, mis. 1 atau 1,3 atau 2-4 (default: 1)")
    g.add_argument("--search-region", dest="search_region", default="wt-wt", metavar="KODE",
                   help="Wilayah pencarian web, mis. id-id (Indonesia), us-en. Default: wt-wt (global)")


def search_from_args(args, console: Optional[Console] = None) -> MediaSearch:
    dl = {
        "quality":       getattr(args, "yt_quality", None),
        "output_dir":    getattr(args, "yt_dir", "./freease_downloads"),
        "audio_format":  getattr(args, "yt_audio_format", None),
        "audio_bitrate": getattr(args, "yt_bitrate", "192"),
        "container":     getattr(args, "yt_container", "mp4"),
        "cookies":       getattr(args, "yt_cookies", None),
        "cookies_from_browser": getattr(args, "yt_browser_cookies", None),
        "subtitles":     getattr(args, "yt_subs", None),
    }
    pl = {
        "volume":       getattr(args, "volume", 100),
        "speed":        getattr(args, "speed", 1.0),
        "backend":      getattr(args, "pl_backend", None),
        "loop":         getattr(args, "loop", False),
        "quality":      getattr(args, "pl_quality", None),
        "vo":           getattr(args, "pl_vo", None),
        "download_dir": getattr(args, "yt_dir", "./freease_downloads"),
    }
    return MediaSearch(
        args.search, source=args.search_source, limit=args.search_limit,
        action=args.search_action, pick=args.search_pick,
        region=getattr(args, "search_region", "wt-wt"),
        console=console, download_opts=dl, player_opts=pl)


def main() -> None:
    from freease_player import add_player_args
    from freease_youtube import add_youtube_args

    p = argparse.ArgumentParser(prog="freease_search",
                                description="freease — cari lagu/video/web lalu putar, unduh, atau buka")
    p.add_argument("query", nargs="*", help="Judul, penggalan lirik, atau kata kunci")
    add_search_args(p)
    add_youtube_args(p)
    add_player_args(p)
    args = p.parse_args()
    args.search = args.search or " ".join(args.query)
    if not args.search:
        p.error("masukkan kata kunci pencarian")
    res = search_from_args(args).run()
    sys.exit(1 if res.get("error") else 0)


if __name__ == "__main__":
    main()
