#!/usr/bin/env python3
"""
freease — Modul 12: URL Safety Scanner (deteksi phishing / tautan berbahaya)
by: Kodok-Kejepit

Scanner DEFENSIF untuk memeriksa sebuah tautan SEBELUM Anda membukanya.
Tujuannya persis seperti memeriksa paket sebelum dibuka: semua pemeriksaan
bersifat pasif / analitis. freease TIDAK pernah "membuka", merender, atau
mengeksekusi isi halaman target — tidak ada JavaScript yang dijalankan dan
tidak ada file yang diunduh. Yang dilakukan hanyalah:

  LEKSIKAL (tanpa jaringan)
    • Host berupa IP mentah, trik "user@host", port tak lazim
    • Punycode / IDN homograph (xn--), karakter lookalike
    • Kedalaman subdomain, panjang URL, banyak tanda hubung/digit
    • TLD yang sering disalahgunakan, layanan URL shortener
    • Kata kunci phishing (login, verify, secure, wallet, …)
    • Impersonasi brand / typosquatting (jarak Levenshtein ke brand populer)
    • Entropi label domain (indikasi domain acak / DGA)
    • Skema berbahaya (data:, javascript:), ekstensi file berisiko di path

  JARINGAN (opsional, aktif secara default; matikan dengan --offline)
    • Resolusi DNS (apakah domain benar-benar ada)
    • Jejak REDIRECT lewat HEAD (ke mana link ini sebenarnya mengarah)
    • Sertifikat TLS (penerbit, masa berlaku, self-signed, cocok/tidak hostname)
    • Umur domain via RDAP (domain yang baru dibuat lebih berisiko)

  INTELIJEN ANCAMAN (opt-in)
    • abuse.ch URLhaus         → --urlhaus          (gratis, tanpa key)
    • Google Safe Browsing v4  → --safebrowsing-key KEY

Hasil diringkas menjadi skor risiko 0–100 dan verdict:
  AMAN (<25) · MENCURIGAKAN (25–59) · BERBAHAYA (≥60)

Pemakaian:
  python freease.py -s https://contoh-mencurigakan.tld/login
  python freease.py -s http://bit.ly/xxxx --urlhaus --deep
  python freease.py --scan-list daftar_url.txt -o ./freease_output

Scanner ini membantu Anda MENGHINDARI tautan berbahaya. Gunakan secara wajar.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import socket
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from rich import box
from rich.console import Console
from rich.markup import escape
from rich.panel import Panel
from rich.table import Table

BROWSER_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0 Safari/537.36"
)

# ── Basis pengetahuan heuristik ────────────────────────────────
SHORTENERS = {
    "bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly", "is.gd", "buff.ly",
    "cutt.ly", "rb.gy", "shorturl.at", "rebrand.ly", "bit.do", "t.ly",
    "s.id", "tiny.cc", "lnkd.in", "trib.al", "soo.gd", "clck.ru", "v.gd",
}
# TLD yang secara historis punya rasio penyalahgunaan tinggi (sumber publik).
RISKY_TLDS = {
    "zip", "mov", "xyz", "top", "tk", "ml", "ga", "cf", "gq", "work", "click",
    "link", "country", "kim", "loan", "men", "download", "stream", "gdn",
    "racing", "review", "science", "party", "date", "faith", "cricket",
    "accountant", "win", "bid", "trade", "webcam", "rest", "fit", "cam",
    "quest", "sbs", "cfd", "autos", "lol", "icu", "monster",
}
PHISH_KEYWORDS = {
    "login", "log-in", "signin", "sign-in", "verify", "verification",
    "secure", "security", "account", "update", "confirm", "banking", "bank",
    "wallet", "unlock", "suspend", "suspended", "recover", "recovery",
    "password", "credential", "billing", "invoice", "payment", "gift",
    "bonus", "reward", "claim", "support", "helpdesk", "authenticate",
    "validation", "webscr", "limited", "unusual", "reactivate",
}
# Brand yang sering ditiru → domain resmi (untuk deteksi typosquat).
BRANDS = {
    "google": "google.com", "facebook": "facebook.com", "instagram": "instagram.com",
    "whatsapp": "whatsapp.com", "paypal": "paypal.com", "microsoft": "microsoft.com",
    "apple": "apple.com", "amazon": "amazon.com", "netflix": "netflix.com",
    "twitter": "twitter.com", "linkedin": "linkedin.com", "tiktok": "tiktok.com",
    "steam": "steampowered.com", "binance": "binance.com", "coinbase": "coinbase.com",
    "metamask": "metamask.io", "telegram": "telegram.org", "discord": "discord.com",
    "dana": "dana.id", "ovo": "ovo.id", "gojek": "gojek.com", "shopee": "shopee.co.id",
    "tokopedia": "tokopedia.com", "bca": "bca.co.id", "mandiri": "bankmandiri.co.id",
    "bri": "bri.co.id", "bni": "bni.co.id",
}
RISKY_PATH_EXT = {
    ".exe", ".scr", ".apk", ".bat", ".cmd", ".msi", ".jar", ".vbs", ".ps1",
    ".js", ".hta", ".zip", ".rar", ".7z", ".iso", ".img", ".dmg",
}
# Substitusi karakter lookalike (leetspeak/homoglyph ASCII) → bentuk kanonik.
# Dipakai untuk membongkar tiruan brand seperti "paypa1" → "paypal".
LOOKALIKE_MAP = str.maketrans({
    "0": "o", "1": "l", "3": "e", "4": "a", "5": "s", "7": "t", "9": "g",
    "$": "s", "@": "a", "|": "l", "!": "i",
})


def _deleet(s: str) -> str:
    """Normalisasi karakter lookalike dan buang pemisah untuk cek typosquat."""
    return s.translate(LOOKALIKE_MAP).replace("-", "").replace("_", "")

PUBLIC_DNS = ["1.1.1.1", "8.8.8.8", "9.9.9.9"]


class Finding:
    """Satu temuan dengan bobot risiko dan kategori."""

    __slots__ = ("severity", "score", "category", "detail")

    def __init__(self, severity: str, score: int, category: str, detail: str):
        self.severity = severity          # info | low | medium | high | critical
        self.score = score                # kontribusi ke skor total
        self.category = category
        self.detail = detail


def _levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _shannon_entropy(s: str) -> float:
    if not s:
        return 0.0
    freq = {c: s.count(c) for c in set(s)}
    n = len(s)
    return -sum((c / n) * math.log2(c / n) for c in freq.values())


def _registered_domain(host: str) -> str:
    """
    Perkiraan sederhana 'eTLD+1' tanpa daftar PSL penuh. Cukup untuk heuristik:
    tangani beberapa SLD dua-tingkat yang umum (co.id, co.uk, com.au, dst).
    """
    parts = host.split(".")
    if len(parts) <= 2:
        return host
    two = {"co", "com", "net", "org", "gov", "ac", "edu", "sch", "or", "mil", "go"}
    if parts[-2] in two and len(parts[-1]) <= 3:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


class URLScanner:
    """Analisis satu URL. `scan()` mengembalikan dict hasil terstruktur."""

    def __init__(
        self,
        url: str,
        *,
        console: Optional[Console] = None,
        offline: bool = False,
        deep: bool = False,
        timeout: float = 10.0,
        max_redirects: int = 10,
        use_urlhaus: bool = False,
        safebrowsing_key: Optional[str] = None,
    ):
        self.raw = url.strip()
        self.console = console or Console()
        self.offline = offline
        self.deep = deep
        self.timeout = timeout
        self.max_redirects = max_redirects
        self.use_urlhaus = use_urlhaus
        self.sb_key = safebrowsing_key
        self.findings: list[Finding] = []

    # ── util ───────────────────────────────────────────────────
    def _add(self, severity: str, score: int, category: str, detail: str) -> None:
        self.findings.append(Finding(severity, score, category, detail))

    def _normalize(self) -> str:
        u = self.raw
        if not re.match(r"^[a-zA-Z][a-zA-Z0-9+.\-]*://", u):
            u = "http://" + u
        return u

    # ── 1) analisis leksikal ───────────────────────────────────
    def _lexical(self, parsed: urllib.parse.ParseResult) -> None:
        scheme = parsed.scheme.lower()
        host = (parsed.hostname or "").lower()
        full = self.raw.lower()
        path = (parsed.path or "")
        userinfo = ""
        if "@" in parsed.netloc:
            userinfo = parsed.netloc.split("@", 1)[0]

        if scheme in ("javascript", "data"):
            self._add("critical", 45, "skema",
                      f"Skema '{scheme}:' dapat mengeksekusi kode / menyembunyikan payload.")
        elif scheme == "http":
            self._add("low", 6, "transport",
                      "Tanpa HTTPS — lalu lintas tidak terenkripsi.")

        if userinfo:
            self._add("high", 25, "struktur",
                      f"Ada bagian '{escape(userinfo)}@' sebelum host — trik "
                      "menyamarkan domain tujuan yang sebenarnya.")

        # Host berupa IP mentah
        is_ip = False
        try:
            socket.inet_aton(host)
            is_ip = bool(re.match(r"^\d{1,3}(\.\d{1,3}){3}$", host))
        except OSError:
            is_ip = False
        if is_ip:
            self._add("high", 22, "host",
                      f"Host berupa alamat IP mentah ({host}), bukan nama domain — "
                      "lazim pada halaman phishing/sementara.")

        if parsed.port and parsed.port not in (80, 443):
            self._add("medium", 12, "host",
                      f"Port tak lazim untuk web: {parsed.port}.")

        if host and not is_ip:
            labels = host.split(".")
            tld = labels[-1] if labels else ""
            regdom = _registered_domain(host)

            if "xn--" in host:
                self._add("high", 24, "homograph",
                          "Domain memakai Punycode (xn--) — berpotensi IDN "
                          "homograph yang meniru domain asli.")
            if tld in RISKY_TLDS:
                self._add("medium", 14, "tld",
                          f"TLD '.{tld}' punya reputasi penyalahgunaan tinggi.")
            if regdom in SHORTENERS:
                self._add("medium", 15, "shortener",
                          f"URL shortener ({regdom}) — tujuan asli tersembunyi. "
                          "Jejak redirect akan ditelusuri di bawah.")
            depth = len(labels) - len(regdom.split("."))
            if depth >= 3:
                self._add("medium", 10, "struktur",
                          f"Subdomain sangat dalam ({depth} tingkat) — sering dipakai "
                          "untuk menyelipkan nama brand di depan.")
            if len(host) > 40:
                self._add("low", 6, "struktur", f"Hostname sangat panjang ({len(host)} char).")
            hyphens = host.count("-")
            if hyphens >= 3:
                self._add("low", 7, "struktur",
                          f"Banyak tanda hubung di host ({hyphens}).")
            digits = sum(c.isdigit() for c in regdom.replace(".", ""))
            if digits >= 4:
                self._add("low", 6, "struktur",
                          f"Banyak digit di nama domain ({digits}).")

            # Entropi label domain (indikasi string acak / DGA).
            # Lewati label berhubung ('verify-account') — itu frasa, bukan acak,
            # dan sudah tertangkap heuristik lain.
            sld = regdom.split(".")[0]
            ent = _shannon_entropy(sld)
            if len(sld) >= 10 and ent >= 3.6 and "-" not in sld:
                self._add("medium", 10, "entropi",
                          f"Label domain '{sld}' terlihat acak "
                          f"(entropi {ent:.1f}) — ciri domain yang dibuat otomatis.")

            # Impersonasi brand / typosquatting.
            # Cek host apa adanya DAN bentuk lookalike-nya ('paypa1' → 'paypal').
            host_deleet = _deleet(host)
            sld_deleet = _deleet(sld)
            for brand, official in BRANDS.items():
                off_reg = _registered_domain(official)
                if regdom == off_reg:
                    break  # memang domain resmi
                in_sub = (brand in host or brand in host_deleet) and regdom != off_reg
                dist = min(_levenshtein(sld, brand), _levenshtein(sld_deleet, brand))
                near = 0 < dist <= max(1, len(brand) // 5) and abs(len(sld) - len(brand)) <= 2
                if in_sub:
                    self._add("high", 26, "impersonasi",
                              f"Menyebut brand '{brand}' tapi domain terdaftar "
                              f"'{regdom}' ≠ resmi '{off_reg}'.")
                    break
                if near:
                    self._add("high", 24, "typosquat",
                              f"Domain '{sld}' sangat mirip brand '{brand}' "
                              f"(beda {dist} huruf) — kemungkinan typosquatting.")
                    break

        # Kata kunci phishing di host + path
        hay = (host + " " + path).lower()
        hits = sorted({k for k in PHISH_KEYWORDS if k in hay})
        if hits:
            sev, sc = ("medium", 12) if len(hits) >= 2 else ("low", 7)
            self._add(sev, sc, "kata-kunci",
                      "Kata kunci bernuansa phishing: " + ", ".join(hits[:6]) +
                      ("…" if len(hits) > 6 else ""))

        if "https" in host.replace("https", "", 0) and host.count("https") > 0:
            self._add("medium", 12, "deception",
                      "Kata 'https' muncul di dalam nama host — trik agar terlihat aman.")

        # % encoding berlebihan
        pct = full.count("%")
        if pct >= 6:
            self._add("low", 6, "encoding",
                      f"Banyak karakter ter-encode ({pct}×) — bisa menyembunyikan isi URL.")

        # Ekstensi file berisiko di path
        low_path = path.lower()
        for ext in RISKY_PATH_EXT:
            if low_path.endswith(ext):
                self._add("high", 20, "unduhan",
                          f"Path mengarah ke file berisiko ({ext}).")
                break

        if len(self.raw) > 100:
            self._add("low", 5, "struktur", f"URL sangat panjang ({len(self.raw)} char).")

    # ── 2) DNS ─────────────────────────────────────────────────
    def _dns(self, host: str) -> dict:
        res = {"resolves": None, "addresses": []}
        if not host:
            return res
        try:
            infos = socket.getaddrinfo(host, None)
            addrs = sorted({i[4][0] for i in infos})
            res["resolves"] = True
            res["addresses"] = addrs
        except socket.gaierror:
            res["resolves"] = False
            self._add("high", 18, "dns",
                      f"Domain '{host}' tidak dapat di-resolve (NXDOMAIN / tidak ada A/AAAA).")
        except Exception:
            res["resolves"] = None
        return res

    # ── 3) jejak redirect (HEAD, tidak mengunduh isi) ──────────
    def _redirects(self, start_url: str) -> dict:
        chain: list[dict] = []
        res = {"chain": chain, "final_url": start_url, "final_status": None,
               "cross_domain": False, "final_server": None}
        current = start_url
        first_host = urllib.parse.urlparse(start_url).hostname

        class _NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None  # jangan auto-follow; kita telusuri manual

        opener = urllib.request.build_opener(_NoRedirect)
        for _ in range(self.max_redirects + 1):
            method = "HEAD"
            try:
                req = urllib.request.Request(
                    current, method=method,
                    headers={"User-Agent": BROWSER_UA, "Accept": "*/*"})
                resp = opener.open(req, timeout=self.timeout)
                status = resp.getcode()
                server = resp.headers.get("Server")
                location = resp.headers.get("Location")
                resp.close()
            except urllib.error.HTTPError as e:
                status = e.code
                server = e.headers.get("Server") if e.headers else None
                location = e.headers.get("Location") if e.headers else None
            except Exception as e:
                chain.append({"url": current, "status": "error", "note": str(e)[:120]})
                res["final_url"] = current
                break

            entry = {"url": current, "status": status, "server": server}
            chain.append(entry)
            res["final_status"] = status
            res["final_server"] = server
            res["final_url"] = current

            if status in (301, 302, 303, 307, 308) and location:
                nxt = urllib.parse.urljoin(current, location)
                entry["redirect_to"] = nxt
                current = nxt
                continue
            break
        else:
            self._add("medium", 12, "redirect",
                      f"Rantai redirect melebihi {self.max_redirects} lompatan — "
                      "kemungkinan loop/cloaking.")

        final_host = urllib.parse.urlparse(res["final_url"]).hostname
        if first_host and final_host and first_host != final_host:
            res["cross_domain"] = True
            self._add("medium", 14, "redirect",
                      f"Tautan berpindah domain: {first_host} → {final_host}.")
        if len(chain) >= 4:
            self._add("low", 6, "redirect",
                      f"Rantai redirect panjang ({len(chain)} lompatan).")
        return res

    # ── 4) sertifikat TLS ──────────────────────────────────────
    def _tls(self, host: str, port: int) -> dict:
        info = {"valid": None, "issuer": None, "not_after": None,
                "not_before": None, "hostname_match": None, "self_signed": None}
        ctx = ssl.create_default_context()
        try:
            with socket.create_connection((host, port), timeout=self.timeout) as sock:
                with ctx.wrap_socket(sock, server_hostname=host) as ssock:
                    cert = ssock.getpeercert()
            info["valid"] = True
            info["hostname_match"] = True
        except ssl.SSLCertVerificationError as e:
            info["valid"] = False
            msg = str(e).lower()
            if "hostname mismatch" in msg or "doesn't match" in msg:
                info["hostname_match"] = False
                self._add("high", 22, "tls",
                          "Sertifikat TLS tidak cocok dengan hostname.")
            elif "self signed" in msg or "self-signed" in msg:
                info["self_signed"] = True
                self._add("high", 20, "tls", "Sertifikat TLS self-signed.")
            elif "expired" in msg:
                self._add("high", 20, "tls", "Sertifikat TLS kedaluwarsa.")
            else:
                self._add("medium", 14, "tls",
                          f"Verifikasi TLS gagal: {escape(str(e)[:80])}")
            cert = self._peek_cert(host, port)
        except (socket.timeout, ConnectionRefusedError, OSError):
            return info
        except Exception:
            return info

        if cert:
            issuer = dict(x[0] for x in cert.get("issuer", ()) if x)
            info["issuer"] = issuer.get("organizationName") or issuer.get("commonName")
            info["not_after"] = cert.get("notAfter")
            info["not_before"] = cert.get("notBefore")
            # Sertifikat yang baru terbit (beberapa hari) sering menyertai phishing.
            try:
                nb = datetime.strptime(cert["notBefore"], "%b %d %H:%M:%S %Y %Z").replace(
                    tzinfo=timezone.utc)
                age_days = (datetime.now(timezone.utc) - nb).days
                if 0 <= age_days <= 7:
                    self._add("medium", 10, "tls",
                              f"Sertifikat baru diterbitkan {age_days} hari lalu.")
            except Exception:
                pass
        return info

    def _peek_cert(self, host: str, port: int) -> Optional[dict]:
        """Ambil detail sertifikat tanpa verifikasi (hanya untuk ditampilkan)."""
        ctx = ssl._create_unverified_context()
        try:
            with socket.create_connection((host, port), timeout=self.timeout) as sock:
                with ctx.wrap_socket(sock, server_hostname=host) as ssock:
                    return ssock.getpeercert() or self._der_to_dict(ssock)
        except Exception:
            return None

    def _der_to_dict(self, ssock) -> Optional[dict]:
        return None

    # ── 5) umur domain via RDAP ────────────────────────────────
    def _domain_age(self, host: str) -> dict:
        info = {"registered": None, "age_days": None}
        regdom = _registered_domain(host)
        try:
            req = urllib.request.Request(
                f"https://rdap.org/domain/{regdom}",
                headers={"User-Agent": BROWSER_UA, "Accept": "application/rdap+json"})
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                data = json.loads(resp.read().decode("utf-8", "replace"))
        except Exception:
            return info
        for ev in data.get("events", []):
            if ev.get("eventAction") == "registration":
                info["registered"] = ev.get("eventDate")
                try:
                    dt = datetime.fromisoformat(
                        ev["eventDate"].replace("Z", "+00:00"))
                    age = (datetime.now(timezone.utc) - dt).days
                    info["age_days"] = age
                    if age <= 30:
                        self._add("high", 22, "umur-domain",
                                  f"Domain sangat baru ({age} hari) — faktor risiko kuat.")
                    elif age <= 90:
                        self._add("medium", 12, "umur-domain",
                                  f"Domain relatif baru ({age} hari).")
                except Exception:
                    pass
                break
        return info

    # ── 6) intel ancaman (opt-in) ──────────────────────────────
    def _urlhaus(self, url: str) -> dict:
        info = {"listed": None}
        try:
            data = urllib.parse.urlencode({"url": url}).encode()
            req = urllib.request.Request(
                "https://urlhaus-api.abuse.ch/v1/url/",
                data=data, headers={"User-Agent": BROWSER_UA})
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                j = json.loads(resp.read().decode("utf-8", "replace"))
        except Exception:
            return info
        status = j.get("query_status")
        if status == "ok":
            info["listed"] = True
            threat = j.get("threat") or "malware_download"
            self._add("critical", 50, "urlhaus",
                      f"Terdaftar di abuse.ch URLhaus sebagai ancaman ({threat}).")
        elif status == "no_results":
            info["listed"] = False
        return info

    def _safebrowsing(self, url: str) -> dict:
        info = {"listed": None}
        if not self.sb_key:
            return info
        try:
            body = json.dumps({
                "client": {"clientId": "freease", "clientVersion": "2.2.0"},
                "threatInfo": {
                    "threatTypes": ["MALWARE", "SOCIAL_ENGINEERING",
                                    "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"],
                    "platformTypes": ["ANY_PLATFORM"],
                    "threatEntryTypes": ["URL"],
                    "threatEntries": [{"url": url}],
                }}).encode()
            req = urllib.request.Request(
                "https://safebrowsing.googleapis.com/v4/threatMatches:find?key="
                + urllib.parse.quote(self.sb_key),
                data=body, headers={"Content-Type": "application/json",
                                    "User-Agent": BROWSER_UA})
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                j = json.loads(resp.read().decode("utf-8", "replace"))
        except Exception:
            return info
        if j.get("matches"):
            info["listed"] = True
            types = ", ".join(sorted({m.get("threatType", "?") for m in j["matches"]}))
            self._add("critical", 55, "safebrowsing",
                      f"Google Safe Browsing menandai URL ini ({types}).")
        else:
            info["listed"] = False
        return info

    # ── orkestrasi ─────────────────────────────────────────────
    def scan(self) -> dict:
        url = self._normalize()
        parsed = urllib.parse.urlparse(url)
        host = (parsed.hostname or "").lower()
        port = parsed.port or (443 if parsed.scheme == "https" else 80)

        self._lexical(parsed)

        net: dict = {}
        if not self.offline and parsed.scheme in ("http", "https") and host:
            net["dns"] = self._dns(host)
            if net["dns"].get("resolves"):
                net["redirects"] = self._redirects(url)
                if parsed.scheme == "https" or port == 443:
                    net["tls"] = self._tls(host, 443)
                net["domain_age"] = self._domain_age(host)
            if self.use_urlhaus:
                net["urlhaus"] = self._urlhaus(url)
            if self.sb_key:
                net["safebrowsing"] = self._safebrowsing(url)

        total = min(100, sum(f.score for f in self.findings))
        if total >= 60:
            verdict, vcolor = "BERBAHAYA", "bold red"
        elif total >= 25:
            verdict, vcolor = "MENCURIGAKAN", "bold yellow"
        else:
            verdict, vcolor = "AMAN", "bold green"

        return {
            "url": self.raw, "normalized": url, "host": host,
            "scanned_at": datetime.now(timezone.utc).isoformat(),
            "score": total, "verdict": verdict, "verdict_color": vcolor,
            "offline": self.offline,
            "findings": [{"severity": f.severity, "score": f.score,
                          "category": f.category, "detail": f.detail}
                         for f in self.findings],
            "network": net,
        }


# ──────────────────────────────────────────────────────────────
#  Tampilan & laporan
# ──────────────────────────────────────────────────────────────
_SEV_ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3, "info": 4}
_SEV_STYLE = {"critical": "bold red", "high": "red", "medium": "yellow",
              "low": "cyan", "info": "dim"}


def render_result(res: dict, console: Console) -> None:
    host = res["host"] or "—"
    net = res.get("network", {})
    header = [
        f"[bold]URL   :[/bold] {escape(res['url'])}",
        f"[bold]Host  :[/bold] {escape(host)}",
    ]
    if net.get("redirects") and net["redirects"].get("final_url") != res["normalized"]:
        header.append(f"[bold]Tujuan:[/bold] {escape(net['redirects']['final_url'])}")
    if net.get("dns"):
        addrs = net["dns"].get("addresses") or []
        header.append(f"[bold]IP    :[/bold] " + (", ".join(addrs[:4]) if addrs else "—"))
    if net.get("domain_age", {}).get("age_days") is not None:
        header.append(f"[bold]Umur  :[/bold] {net['domain_age']['age_days']} hari")
    if net.get("tls", {}).get("issuer"):
        header.append(f"[bold]TLS   :[/bold] {escape(str(net['tls']['issuer']))}")

    console.print(Panel(
        "\n".join(header) +
        f"\n\n[{res['verdict_color']}]VERDICT: {res['verdict']}  "
        f"(skor risiko {res['score']}/100)[/{res['verdict_color']}]",
        title="[bold]URL SAFETY SCANNER[/bold]",
        subtitle="[dim]analisis pasif — target tidak dibuka/dieksekusi[/dim]",
        border_style=res["verdict_color"].split()[-1], box=box.ROUNDED))

    findings = sorted(res["findings"], key=lambda f: _SEV_ORDER.get(f["severity"], 9))
    if findings:
        t = Table(box=box.SIMPLE_HEAVY, header_style="bold cyan",
                  title="[bold]Temuan[/bold]", title_justify="left")
        t.add_column("Severitas", width=10)
        t.add_column("Kategori", width=13)
        t.add_column("Keterangan", overflow="fold")
        t.add_column("+", justify="right", width=3)
        for f in findings:
            st = _SEV_STYLE.get(f["severity"], "white")
            t.add_row(f"[{st}]{f['severity'].upper()}[/{st}]",
                      f["category"], escape(f["detail"]), str(f["score"]))
        console.print(t)
    else:
        console.print("  [green]✓ Tidak ada sinyal mencurigakan yang terdeteksi.[/green]")

    if net.get("redirects", {}).get("chain"):
        chain = net["redirects"]["chain"]
        if len(chain) > 1:
            rt = Table(box=box.MINIMAL, header_style="dim",
                       title="[bold]Jejak Redirect[/bold]", title_justify="left")
            rt.add_column("#", width=3, justify="right")
            rt.add_column("Status", width=7)
            rt.add_column("URL", overflow="fold")
            for i, hop in enumerate(chain, 1):
                rt.add_row(str(i), str(hop.get("status", "—")), escape(hop["url"]))
            console.print(rt)
    console.print()


def _write_reports(results: list[dict], out_dir: str, console: Console) -> None:
    try:
        Path(out_dir).mkdir(parents=True, exist_ok=True)
    except OSError as e:
        console.print(f"  [yellow]⚠ Gagal membuat folder output: {escape(str(e))}[/yellow]")
        return
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    jpath = Path(out_dir) / f"freease_scan_{ts}.json"
    try:
        jpath.write_text(json.dumps(results, indent=2, ensure_ascii=False), "utf-8")
        console.print(f"  [dim]JSON:[/dim] {escape(str(jpath))}")
    except OSError as e:
        console.print(f"  [yellow]⚠ Gagal menulis JSON: {escape(str(e))}[/yellow]")
    # HTML ringkas
    rows = []
    for r in results:
        color = {"BERBAHAYA": "#e5484d", "MENCURIGAKAN": "#f5a623",
                 "AMAN": "#30a46c"}.get(r["verdict"], "#888")
        items = "".join(
            f"<li><b>[{f['severity'].upper()}]</b> {_esc(f['category'])}: "
            f"{_esc(f['detail'])} <i>(+{f['score']})</i></li>"
            for f in r["findings"]) or "<li>Tidak ada sinyal mencurigakan.</li>"
        rows.append(
            f"<section><h2>{_esc(r['url'])}</h2>"
            f"<p>Host: <code>{_esc(r['host'])}</code> · "
            f"<b style='color:{color}'>{r['verdict']}</b> "
            f"(skor {r['score']}/100)</p><ul>{items}</ul></section>")
    html = (
        "<!doctype html><meta charset=utf-8><title>freease URL scan</title>"
        "<style>body{font:14px/1.5 system-ui,sans-serif;max-width:900px;margin:2rem auto;"
        "padding:0 1rem;background:#0f1115;color:#e6e6e6}h1{color:#6cc}code{color:#9cf}"
        "section{border:1px solid #333;border-radius:8px;padding:1rem;margin:1rem 0;"
        "background:#161a22}li{margin:.25rem 0}</style>"
        f"<h1>freease — URL Safety Report</h1><p>{datetime.now():%Y-%m-%d %H:%M:%S}</p>"
        + "".join(rows))
    hpath = Path(out_dir) / f"freease_scan_{ts}.html"
    try:
        hpath.write_text(html, "utf-8")
        console.print(f"  [dim]HTML:[/dim] {escape(str(hpath))}")
    except OSError:
        pass


def _esc(s) -> str:
    import html as _h
    return _h.escape(str(s))


# ──────────────────────────────────────────────────────────────
#  CLI
# ──────────────────────────────────────────────────────────────
def add_scan_args(p) -> None:
    g = p.add_argument_group("URL Safety Scanner")
    g.add_argument("-s", "--scan", metavar="URL", default=None,
                   help="Periksa satu URL terhadap indikator phishing / berbahaya")
    g.add_argument("--scan-list", dest="scan_list", metavar="FILE", default=None,
                   help="File berisi daftar URL (satu per baris) untuk diperiksa")
    g.add_argument("--offline", action="store_true",
                   help="Hanya analisis leksikal, tanpa koneksi jaringan")
    g.add_argument("--deep", action="store_true",
                   help="Analisis lebih dalam (reserved untuk pemeriksaan tambahan)")
    g.add_argument("--scan-timeout", dest="scan_timeout", type=float, default=10.0,
                   metavar="SEC", help="Timeout per permintaan jaringan (default: 10)")
    g.add_argument("--max-redirects", dest="max_redirects", type=int, default=10,
                   metavar="N", help="Batas lompatan redirect yang ditelusuri (default: 10)")
    g.add_argument("--urlhaus", action="store_true",
                   help="Cek URL di abuse.ch URLhaus (gratis, tanpa key)")
    g.add_argument("--safebrowsing-key", dest="safebrowsing_key", default=None,
                   metavar="KEY", help="Google Safe Browsing API key (opsional)")


def _collect_urls(args) -> list[str]:
    urls: list[str] = []
    if args.scan:
        urls.append(args.scan)
    if getattr(args, "scan_list", None):
        try:
            for line in Path(args.scan_list).read_text("utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    urls.append(line)
        except OSError as e:
            raise SystemExit(f"Gagal membaca --scan-list: {e}")
    # dedup stabil
    seen, out = set(), []
    for u in urls:
        if u not in seen:
            seen.add(u)
            out.append(u)
    return out


def run_scan_from_args(args, console: Optional[Console] = None) -> dict:
    console = console or Console()
    urls = _collect_urls(args)
    if not urls:
        console.print("  [yellow]Tidak ada URL. Pakai -s URL atau --scan-list FILE.[/yellow]")
        return {"error": "tidak ada URL", "results": []}

    results = []
    worst = 0
    for url in urls:
        scanner = URLScanner(
            url, console=console,
            offline=getattr(args, "offline", False),
            deep=getattr(args, "deep", False),
            timeout=getattr(args, "scan_timeout", 10.0),
            max_redirects=getattr(args, "max_redirects", 10),
            use_urlhaus=getattr(args, "urlhaus", False),
            safebrowsing_key=getattr(args, "safebrowsing_key", None),
        )
        with console.status(f"[cyan]Memindai {escape(url[:60])}…"):
            res = scanner.scan()
        render_result(res, console)
        results.append(res)
        worst = max(worst, res["score"])

    out_dir = getattr(args, "output", None)
    if out_dir and not getattr(args, "no_export", False):
        _write_reports(results, out_dir, console)

    return {"error": None, "results": results, "worst_score": worst}


def main() -> None:
    p = argparse.ArgumentParser(
        prog="freease_scan",
        description="freease — URL safety scanner (deteksi phishing/berbahaya)")
    p.add_argument("url", nargs="?", help="URL yang diperiksa")
    p.add_argument("-o", "--output", default="./freease_output",
                   help="Folder laporan JSON/HTML (default: ./freease_output)")
    p.add_argument("--no-export", action="store_true", help="Jangan tulis laporan")
    add_scan_args(p)
    args = p.parse_args()
    if args.url and not args.scan:
        args.scan = args.url
    res = run_scan_from_args(args)
    worst = res.get("worst_score", 0)
    # exit code mencerminkan tingkat risiko tertinggi: 0 aman, 1 mencurigakan, 2 bahaya
    sys.exit(2 if worst >= 60 else 1 if worst >= 25 else 0)


if __name__ == "__main__":
    main()
