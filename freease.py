#!/usr/bin/env python3
"""
freease v2.1.0 - Attack Surface Management Tool
by: Kodok-Kejepit

DISCLAIMER: Tool ini hanya menggunakan data publik yang tersedia.
Gunakan hanya pada domain/aset yang Anda miliki atau memiliki
izin eksplisit untuk melakukan pengujian. Penyalahgunaan
merupakan tanggung jawab pengguna sepenuhnya.

Modul:
  1. NetworkRecon       — DNS, subdomain (crt.sh), web tech, SSL/TLS
  2. PortScanner        — Async TCP port scan + banner grabbing
  3. WAFDetector        — Web Application Firewall fingerprinting
  4. WhoisChecker       — Domain registration, registrar, age
  5. EmailSecurityChecker — SPF / DKIM / DMARC DNS validation
  6. UsernameChecker    — 20+ platform username lookup
  7. BreachChecker      — HaveIBeenPwned v3 email breach check
  8. IPReputationChecker — AbuseIPDB + AlienVault OTX + ip-api.com
  9. ExifToolExtractor  — File/URL metadata (EXIF, GPS → Maps, IPTC, XMP)
 10. YouTubeDownloader  — Download video/musik YouTube & YouTube Music (freease_youtube.py)
"""

import asyncio
import aiohttp
import argparse
import dns.resolver
import html
import ipaddress
import json
import os
import sys
import socket
import ssl
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request
import urllib.parse
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Optional

from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn
from rich import box
from rich.markup import escape

from freease_youtube import add_youtube_args, downloader_from_args
from freease_player import add_player_args, player_from_args
from freease_scan import add_scan_args, run_scan_from_args

VERSION = "2.2.0"
USER_AGENT = f"freease-ASM-Tool/{VERSION} (Defensive Security Audit)"
# Banyak situs menolak UA non-browser, jadi request halaman web pakai UA browser.
BROWSER_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0 Safari/537.36"
)

console = Console()


def normalize_domain(value: str) -> str:
    """'https://Example.com:443/path' → 'example.com'."""
    v = value.strip().lower()
    if "://" in v:
        v = urllib.parse.urlparse(v).netloc
    v = v.split("/")[0].split("@")[-1]
    if v.count(":") == 1:
        v = v.split(":")[0]
    return v.strip(".")


PUBLIC_DNS = ["1.1.1.1", "8.8.8.8", "9.9.9.9"]


def _dns_resolver(timeout: float = 5, public: bool = False) -> dns.resolver.Resolver:
    r = dns.resolver.Resolver(configure=not public)
    if public:
        r.nameservers = PUBLIC_DNS
    r.timeout = r.lifetime = timeout
    # EDNS: jawaban besar (TXT/NS) tidak terpotong di UDP
    r.use_edns(0, 0, 1232)
    return r


def dns_query(name: str, rtype: str):
    """
    Resolve via DNS sistem; kalau resolver lokal gagal menjawab (router rumahan
    sering menolak jawaban besar / TCP), ulangi lewat DNS publik.
    NXDOMAIN / NoAnswer adalah jawaban sah dan langsung diteruskan ke pemanggil.
    """
    try:
        return _dns_resolver().resolve(name, rtype)
    except (dns.resolver.NXDOMAIN, dns.resolver.NoAnswer):
        raise
    except Exception:
        return _dns_resolver(public=True).resolve(name, rtype)


# ══════════════════════════════════════════════════════════════
#  BANNER
# ══════════════════════════════════════════════════════════════

_BANNER_ART = [
    r"  ███████╗██████╗ ███████╗ █████╗ ███████╗███████╗",
    r"  ██╔════╝██╔══██╗██╔════╝██╔══██╗██╔════╝██╔════╝",
    r"  █████╗  ██████╔╝█████╗  ███████║███████╗█████╗  ",
    r"  ██╔══╝  ██╔══██╗██╔══╝  ██╔══██║╚════██║██╔══╝  ",
    r"  ██║     ██║  ██║███████╗██║  ██║███████║███████╗",
    r"  ╚═╝     ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝╚══════╝╚══════╝",
    r"",
    r"   █████╗ ███████╗███╗   ███╗    ████████╗ ██████╗  ██████╗ ██╗",
    r"  ██╔══██╗██╔════╝████╗ ████║       ██╔══╝██╔═══██╗██╔═══██╗██║",
    r"  ███████║███████╗██╔████╔██║       ██║   ██║   ██║██║   ██║██║",
    r"  ██╔══██║╚════██║██║╚██╔╝██║       ██║   ██║   ██║██║   ██║██║",
    r"  ██║  ██║███████║██║ ╚═╝ ██║       ██║   ╚██████╔╝╚██████╔╝███████╗",
    r"  ╚═╝  ╚═╝╚══════╝╚═╝     ╚═╝       ╚═╝    ╚═════╝  ╚═════╝ ╚══════╝",
]


def _print_banner() -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    console.print()
    for i, line in enumerate(_BANNER_ART):
        color = "bold green" if i < 6 else "bold cyan"
        console.print(f"[{color}]{line}[/{color}]")
    console.print(
        f"\n  [bold white]⚡ Professional Ethical Recon Suite[/bold white]"
        f"  [dim]v{VERSION}  |  by: Kodok-Kejepit[/dim]"
        f"   [dim][ {now} ][/dim]"
    )
    console.print(
        "  [dim]Modules: NetworkRecon · PortScan · WAF · WHOIS · "
        "EmailSec · Username · Breach · IPReputation · ExifTool · YouTube · "
        "Player · URLScanner[/dim]"
    )
    console.print(f"  [dim]{'─' * 78}[/dim]\n")


# ══════════════════════════════════════════════════════════════
#  KONFIGURASI PLATFORM USERNAME CHECKER
# ══════════════════════════════════════════════════════════════

# Deteksi per platform:
#   url        — URL profil yang ditampilkan ke user
#   check      — URL yang benar-benar di-request (default: url)
#   absent     — teks di body yang menandakan profil TIDAK ada (walau status 200)
#   present    — teks di body yang wajib ada kalau profil memang ada
#   blocked    — teks di body yang menandakan halaman anti-bot (hasil = UNKNOWN)
#   unreliable — platform selalu balas 200 / wajib login, hasilnya tidak bisa dipercaya
USERNAME_PLATFORMS = {
    "GitHub":     {"url": "https://github.com/{}"},
    "GitLab":     {"url": "https://gitlab.com/{}", "check": "https://gitlab.com/api/v4/users?username={}",
                   "absent": "[]", "exact_absent": True},
    "Twitter/X":  {"url": "https://x.com/{}", "unreliable": True},
    "Instagram":  {"url": "https://www.instagram.com/{}/", "unreliable": True},
    "LinkedIn":   {"url": "https://www.linkedin.com/in/{}", "unreliable": True},
    "Reddit":     {"url": "https://www.reddit.com/user/{}",
                   "check": "https://www.reddit.com/user/{}/about.json"},
    "TikTok":     {"url": "https://www.tiktok.com/@{}", "unreliable": True},
    "YouTube":    {"url": "https://www.youtube.com/@{}"},
    "Pinterest":  {"url": "https://www.pinterest.com/{}/", "unreliable": True},
    "Telegram":   {"url": "https://t.me/{}", "present": "tgme_page_title"},
    "Medium":     {"url": "https://medium.com/@{}"},
    "Dev.to":     {"url": "https://dev.to/{}"},
    "Keybase":    {"url": "https://keybase.io/{}"},
    "Pastebin":   {"url": "https://pastebin.com/u/{}"},
    "HackerNews": {"url": "https://news.ycombinator.com/user?id={}", "absent": "No such user."},
    "Docker Hub": {"url": "https://hub.docker.com/u/{}", "check": "https://hub.docker.com/v2/users/{}/"},
    "PyPI":       {"url": "https://pypi.org/user/{}/", "blocked": "Client Challenge"},
    "npm":        {"url": "https://www.npmjs.com/~{}"},
    "Gravatar":   {"url": "https://gravatar.com/{}", "check": "https://gravatar.com/{}.json"},
    "Flickr":     {"url": "https://www.flickr.com/people/{}"},
}


# ══════════════════════════════════════════════════════════════
#  MODUL 1: NETWORK RECONNAISSANCE
# ══════════════════════════════════════════════════════════════

class NetworkRecon:
    """DNS lookup, subdomain enumeration via crt.sh, web tech detection, SSL info."""

    def __init__(self, domain: str):
        self.domain = domain
        self.results: dict = {
            "dns_records": {}, "subdomains": [],
            "web_tech": {}, "ssl_info": {},
        }

    async def run_all(self, session: aiohttp.ClientSession) -> dict:
        """Jalankan keempat pemeriksaan secara paralel."""
        await asyncio.gather(
            self.get_dns_records(),
            self.enumerate_subdomains_crtsh(session),
            self.detect_web_technologies(session),
            self.get_ssl_info(),
        )
        return self.results

    def _resolve_rtype(self, rtype: str) -> list:
        try:
            return [str(r) for r in dns_query(self.domain, rtype)]
        except dns.resolver.NXDOMAIN:
            return ["DOMAIN_NOT_FOUND"]
        except Exception:
            return []

    async def get_dns_records(self) -> dict:
        rtypes = ["A", "AAAA", "MX", "TXT", "NS", "CNAME", "SOA"]
        # dnspython sinkron — jalankan di thread supaya event loop tidak ke-block
        answers = await asyncio.gather(
            *[asyncio.to_thread(self._resolve_rtype, rt) for rt in rtypes]
        )
        out = dict(zip(rtypes, answers))
        self.results["dns_records"] = out
        return out

    async def enumerate_subdomains_crtsh(self, session: aiohttp.ClientSession) -> list:
        subs: set = set()
        url = f"https://crt.sh/?q=%25.{self.domain}&output=json"
        error = None
        # crt.sh sering balas 502/503 sesaat — coba ulang sekali
        for attempt in range(2):
            try:
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=25)) as resp:
                    if resp.status != 200:
                        error = f"HTTP {resp.status}"
                        await asyncio.sleep(1.5)
                        continue
                    for entry in await resp.json(content_type=None):
                        for name in entry.get("name_value", "").split("\n"):
                            name = name.strip().lower()
                            if name.startswith("*."):
                                name = name[2:]
                            if name == self.domain or name.endswith("." + self.domain):
                                subs.add(name)
                    error = None
                    break
            except asyncio.TimeoutError:
                error = "timeout"
            except Exception as e:
                error = str(e) or e.__class__.__name__
        if error:
            # Fallback: Cert Spotter (CT log juga, tanpa API key)
            try:
                async with session.get(
                    "https://api.certspotter.com/v1/issuances",
                    params={"domain": self.domain, "include_subdomains": "true",
                            "expand": "dns_names"},
                    timeout=aiohttp.ClientTimeout(total=15),
                ) as resp:
                    if resp.status == 200:
                        for entry in await resp.json(content_type=None):
                            for name in entry.get("dns_names", []):
                                name = name.strip().lower().removeprefix("*.")
                                if name.endswith("." + self.domain):
                                    subs.add(name)
                        error = f"crt.sh {error}; pakai Cert Spotter (hasil bisa lebih sedikit)"
            except Exception:
                pass
        subs.discard(self.domain)
        self.results["subdomain_error"] = error
        result = sorted(subs)
        self.results["subdomains"] = result
        return result

    async def detect_web_technologies(self, session: aiohttp.ClientSession) -> dict:
        tech: dict = {
            "server": None, "powered_by": None, "cms": None, "cdn": None,
            "security_headers": {}, "cookies": [], "status_code": None,
            "redirect_url": None, "raw_headers": {},
        }
        sec_header_keys = [
            "Strict-Transport-Security", "Content-Security-Policy",
            "X-Frame-Options", "X-Content-Type-Options",
            "X-XSS-Protection", "Referrer-Policy", "Permissions-Policy",
        ]
        cms_map = {
            "WordPress": ["wp-content", "wp-json", "wp-includes"],
            "Joomla":    ["/media/jui/", "joomla!", "/components/com_"],
            "Drupal":    ["drupal-settings-json", "/sites/default/files", "drupal 1", "drupal 9", "drupal 8", "drupal 7"],
            "Magento":   ["mage/cookies", "magento", "x-magento"],
            "Shopify":   ["cdn.shopify.com", "myshopify.com"],
            "Wix":       ["static.wixstatic.com", "x-wix-"],
        }
        cdn_map = {
            "Cloudflare":    ["cf-ray", "cf-cache-status"],
            "AWS CloudFront":["x-amz-cf-id", "cloudfront"],
            "Fastly":        ["x-fastly-request-id", "x-served-by"],
            "Akamai":        ["x-akamai-transformed", "akamaighost", "akamai-grn"],
            "Vercel":        ["x-vercel-id"],
            "Netlify":       ["x-nf-request-id"],
        }
        for scheme in ["https", "http"]:
            try:
                url = f"{scheme}://{self.domain}"
                async with session.get(
                    url, timeout=aiohttp.ClientTimeout(total=10),
                    headers={"User-Agent": BROWSER_UA},
                    allow_redirects=True, ssl=False
                ) as resp:
                    tech["status_code"]  = resp.status
                    final_url = str(resp.url)
                    tech["redirect_url"] = final_url if final_url.rstrip("/") != url else None
                    # resp.headers case-insensitive; dict() biasa tidak
                    h = resp.headers
                    tech["raw_headers"]  = dict(h)
                    tech["server"]       = h.get("Server")
                    tech["powered_by"]   = h.get("X-Powered-By")
                    for sh in sec_header_keys:
                        tech["security_headers"][sh] = h.get(sh)
                    hl = {k.lower(): v.lower() for k, v in h.items()}
                    header_blob = " ".join(hl.values())
                    for cdn, inds in cdn_map.items():
                        if any(i.lower() in hl or i.lower() in header_blob for i in inds):
                            tech["cdn"] = cdn
                            break
                    try:
                        body = (await resp.text(errors="ignore"))[:20000].lower()
                        for cms, inds in cms_map.items():
                            if any(i.lower() in body or i.lower() in header_blob for i in inds):
                                tech["cms"] = cms
                                break
                    except Exception:
                        pass
                    for c in resp.cookies.values():
                        tech["cookies"].append({
                            "name": c.key,
                            "httponly": bool(c["httponly"]),
                            "secure":   bool(c["secure"]),
                            "samesite": c["samesite"] or None,
                        })
                    break
            except Exception:
                continue
        self.results["web_tech"] = tech
        return tech

    async def get_ssl_info(self) -> dict:
        try:
            ctx = ssl.create_default_context()

            def _fetch():
                # create_connection: timeout berlaku sejak connect, IPv4 & IPv6
                with socket.create_connection((self.domain, 443), timeout=6) as raw:
                    with ctx.wrap_socket(raw, server_hostname=self.domain) as s:
                        return s.getpeercert()

            cert = await asyncio.to_thread(_fetch)
            expires_in = None
            try:
                exp = datetime.fromtimestamp(
                    ssl.cert_time_to_seconds(cert["notAfter"]), timezone.utc
                )
                expires_in = (exp - datetime.now(timezone.utc)).days
            except Exception:
                pass
            info = {
                "days_left":     expires_in,
                "subject":       dict(x[0] for x in cert.get("subject", [])),
                "issuer":        dict(x[0] for x in cert.get("issuer", [])),
                "version":       cert.get("version"),
                "serial_number": cert.get("serialNumber"),
                "not_before":    cert.get("notBefore"),
                "not_after":     cert.get("notAfter"),
                "san":           [x[1] for x in cert.get("subjectAltName", []) if x[0] == "DNS"],
            }
        except ssl.SSLCertVerificationError as e:
            info = {"error": f"Sertifikat tidak valid: {e.verify_message or e}"}
        except Exception as e:
            info = {"error": str(e) or e.__class__.__name__}
        self.results["ssl_info"] = info
        return info


# ══════════════════════════════════════════════════════════════
#  MODUL 2: PORT SCANNER (Async TCP + Banner Grabbing)
# ══════════════════════════════════════════════════════════════

class PortScanner:
    """
    Async TCP port scanner dengan banner grabbing dasar.
    Hanya melakukan koneksi TCP standar ke port yang ditentukan — murni read-only.
    Gunakan hanya pada host milik sendiri atau yang diizinkan secara eksplisit.
    """

    DEFAULT_PORTS = [
        21, 22, 23, 25, 53, 80, 110, 143, 443, 465,
        587, 993, 995, 3306, 3389, 5432, 6379, 8080,
        8443, 8888, 9200, 27017,
    ]

    SERVICE_MAP = {
        21: "FTP",     22: "SSH",      23: "Telnet",  25: "SMTP",
        53: "DNS",     80: "HTTP",     110: "POP3",   143: "IMAP",
        443: "HTTPS",  465: "SMTPS",   587: "SMTP/TLS", 993: "IMAPS",
        995: "POP3S",  3306: "MySQL",  3389: "RDP",   5432: "PostgreSQL",
        6379: "Redis", 8080: "HTTP-Alt", 8443: "HTTPS-Alt", 8888: "Dev/Jupyter",
        9200: "Elasticsearch", 27017: "MongoDB",
    }

    SENSITIVE_PORTS = {23, 3306, 3389, 5432, 6379, 9200, 27017}

    def __init__(self, host: str, ports: Optional[list] = None, timeout: float = 2.0):
        self.host    = host
        self.ports   = ports or self.DEFAULT_PORTS
        self.timeout = timeout
        self.results: dict = {"host": host, "open_ports": [], "scan_time": None}

    async def _scan_port(self, port: int) -> dict:
        """Coba buka koneksi TCP ke port, ambil banner jika memungkinkan."""
        entry = {
            "port":    port,
            "service": self.SERVICE_MAP.get(port, "Unknown"),
            "state":   "closed",
            "banner":  None,
            "warning": None,
        }
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(self.host, port),
                timeout=self.timeout,
            )
            entry["state"] = "open"
            if port in self.SENSITIVE_PORTS:
                entry["warning"] = "⚠ Port sensitif terbuka — verifikasi akses publik!"

            try:
                banner_bytes = await asyncio.wait_for(reader.read(256), timeout=1.5)
                if banner_bytes:
                    banner = banner_bytes.decode("utf-8", errors="replace").strip()
                    banner = re.sub(r'[^\x20-\x7E\n]', '', banner)[:120]
                    entry["banner"] = banner if banner else None
            except (asyncio.TimeoutError, Exception):
                pass

            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

        except (asyncio.TimeoutError, ConnectionRefusedError, OSError):
            entry["state"] = "closed"
        except Exception as e:
            entry["state"] = "error"
            entry["error"] = str(e)[:60]

        return entry

    async def scan(self, semaphore_limit: int = 50) -> dict:
        """Pindai semua port secara concurrent dengan Semaphore."""
        start = datetime.now()
        sem   = asyncio.Semaphore(semaphore_limit)

        async def _guarded(port: int):
            async with sem:
                return await self._scan_port(port)

        all_results = await asyncio.gather(*[_guarded(p) for p in self.ports])
        elapsed = (datetime.now() - start).total_seconds()

        open_ports = [r for r in all_results if r["state"] == "open"]
        self.results = {
            "host":          self.host,
            "ports_scanned": len(self.ports),
            "open_count":    len(open_ports),
            "open_ports":    open_ports,
            "all_ports":     all_results,
            "elapsed_sec":   round(elapsed, 2),
            "scan_time":     start.isoformat(),
        }
        return self.results


# ══════════════════════════════════════════════════════════════
#  MODUL 3: WAF DETECTOR
# ══════════════════════════════════════════════════════════════

class WAFDetector:
    """
    Deteksi Web Application Firewall berdasarkan HTTP response headers,
    status codes, dan pola body yang unik.
    Hanya membaca response HTTP publik — tidak melakukan serangan atau fuzzing aktif.
    """

    WAF_SIGNATURES = {
        "Cloudflare": {
            "headers": ["cf-ray", "cf-cache-status", "cf-request-id"],
            "server":  ["cloudflare"],
            "cookies": ["__cflb", "__cf_bm", "cf_clearance"],
            "body":    ["Attention Required! | Cloudflare", "cf-error-details", "cdn-cgi/challenge-platform"],
        },
        "AWS WAF / CloudFront": {
            "headers": ["x-amz-cf-id", "x-amzn-requestid", "x-amz-apigw-id"],
            "server":  ["awselb", "awsalb", "cloudfront"],
            "body":    ["AWS WAF", "Generated by cloudfront (CloudFront)"],
        },
        "Akamai": {
            "headers": ["x-check-cacheable", "x-akamai-transformed", "akamai-origin-hop"],
            "server":  ["akamaighost", "akamai"],
            "body":    ["errors.edgesuite.net"],
        },
        "Sucuri": {
            "headers": ["x-sucuri-id", "x-sucuri-cache"],
            "server":  ["sucuri"],
            "body":    ["Sucuri WebSite Firewall", "sucuri.net", "Access Denied - Sucuri"],
        },
        "Imperva / Incapsula": {
            "headers": ["x-iinfo"],
            "cookies": ["visid_incap", "incap_ses"],
            "body":    ["incapsula", "Incapsula incident", "_Incapsula_Resource"],
        },
        "F5 BIG-IP ASM": {
            "headers": ["x-wa-info", "x-cnection"],
            "cookies": ["BIGipServer", "F5_"],
            "body":    ["The requested URL was rejected", "Please consult with your administrator"],
        },
        "Barracuda WAF": {
            "headers": ["x-barracuda-url"],
            "cookies": ["barra_counter_session"],
            "body":    ["Barracuda Networks"],
        },
        "ModSecurity": {
            "headers": ["x-modsecurity-action"],
            "server":  ["mod_security", "modsecurity"],
            "body":    ["ModSecurity", "mod_security"],
        },
        "Fortinet FortiGate": {
            "headers": ["x-waf-event-info"],
            "cookies": ["FORTIWAFSID"],
            "body":    ["FortiGate", "fortigate.com", "FortiWeb"],
        },
        "Vercel Edge WAF": {
            "headers": ["x-vercel-id", "x-vercel-cache"],
            "body":    [],
        },
        "Netlify Edge": {
            "headers": ["x-nf-request-id"],
            "body":    [],
        },
        "Fastly": {
            "headers": ["x-fastly-request-id", "fastly-restarts"],
            "body":    ["Fastly error"],
        },
        "Wordfence": {
            "body":    ["Generated by Wordfence", "wordfence_"],
            "headers": [],
        },
    }

    def __init__(self, domain: str):
        self.domain  = domain
        self.results: dict = {}

    async def detect(self, session: aiohttp.ClientSession) -> dict:
        """
        Kirim dua request HTTP:
          1. Request normal — untuk membaca header baseline
          2. Request dengan path tidak ada — untuk memancing WAF block page
        Analisis response untuk fingerprint WAF.
        """
        confidence_scores: dict = {}
        raw_info: dict = {
            "headers": {}, "status_normal": None, "status_probe": None,
            "cookies": [], "server": "", "body_snippet": "",
        }

        headers_ua = {"User-Agent": BROWSER_UA}

        # Request 1: Normal
        for scheme in ["https", "http"]:
            try:
                url = f"{scheme}://{self.domain}"
                async with session.get(
                    url, headers=headers_ua,
                    timeout=aiohttp.ClientTimeout(total=10),
                    allow_redirects=True, ssl=False
                ) as resp:
                    raw_info["status_normal"] = resp.status
                    raw_info["headers"]       = {k.lower(): v.lower() for k, v in resp.headers.items()}
                    raw_info["server"]        = resp.headers.get("Server", "").lower()
                    # Cookie dari seluruh rantai redirect, bukan cuma response terakhir
                    raw_info["cookies"]       = [
                        c.key.lower()
                        for r_ in (*resp.history, resp) for c in r_.cookies.values()
                    ]
                    try:
                        raw_info["body_snippet"] = (await resp.text(errors="ignore"))[:20000].lower()
                    except Exception:
                        pass
                    break
            except Exception:
                continue

        # Request 2: Probe path tidak ada (trigger WAF block)
        for scheme in ["https", "http"]:
            try:
                probe_url = f"{scheme}://{self.domain}/.freease-waf-probe-test-404xyz"
                async with session.get(
                    probe_url, headers=headers_ua,
                    timeout=aiohttp.ClientTimeout(total=8),
                    allow_redirects=False, ssl=False
                ) as resp:
                    raw_info["status_probe"] = resp.status
                    for k, v in resp.headers.items():
                        raw_info["headers"].setdefault(k.lower(), v.lower())
                    try:
                        probe_body = (await resp.text(errors="ignore"))[:20000].lower()
                        raw_info["body_snippet"] += probe_body
                    except Exception:
                        pass
                    break
            except Exception:
                continue

        if raw_info["status_normal"] is None and raw_info["status_probe"] is None:
            raise RuntimeError("Web server tidak merespons — WAF tidak bisa dideteksi")

        # Fingerprinting
        for waf_name, sigs in self.WAF_SIGNATURES.items():
            score = 0
            matched: list = []

            for hdr in sigs.get("headers", []):
                if hdr.lower() in raw_info["headers"]:
                    score += 3
                    matched.append(f"header:{hdr}")

            for srv in sigs.get("server", []):
                if srv.lower() in raw_info["server"]:
                    score += 3
                    matched.append(f"server:{srv}")

            for ck in sigs.get("cookies", []):
                if any(c.startswith(ck.lower()) for c in raw_info["cookies"]):
                    score += 2
                    matched.append(f"cookie:{ck}")

            for kw in sigs.get("body", []):
                if kw.lower() in raw_info["body_snippet"]:
                    score += 2
                    matched.append(f"body:{kw}")

            if score > 0:
                confidence = min(100, score * 20)
                confidence_scores[waf_name] = {
                    "score": score, "confidence": confidence, "matched": matched
                }

        sorted_waf    = sorted(confidence_scores.items(), key=lambda x: x[1]["score"], reverse=True)
        primary_waf   = sorted_waf[0][0] if sorted_waf else None
        primary_conf  = sorted_waf[0][1]["confidence"] if sorted_waf else 0

        self.results = {
            "domain":        self.domain,
            "waf_detected":  bool(sorted_waf),
            "primary_waf":   primary_waf,
            "confidence":    primary_conf,
            "all_detections": [
                {"name": n, "confidence": d["confidence"], "matched": d["matched"]}
                for n, d in sorted_waf
            ],
            "raw": {
                "status_normal": raw_info["status_normal"],
                "status_probe":  raw_info["status_probe"],
                "server_header": raw_info["server"],
                "detected_headers": list(raw_info["headers"].keys())[:20],
            },
        }
        return self.results


# ══════════════════════════════════════════════════════════════
#  MODUL 4: WHOIS & DOMAIN AGE CHECKER
# ══════════════════════════════════════════════════════════════

class WhoisChecker:
    """
    Ambil data WHOIS domain: tanggal registrasi, expiry, registrar, dan usia domain.
    Menggunakan RDAP (Registration Data Access Protocol) publik sebagai sumber utama.
    """

    # rdap.org = bootstrap publik: redirect ke registry yang benar untuk TLD apa pun
    RDAP_FALLBACK = "https://rdap.org/domain/"

    RDAP_SERVERS = {
        "com":  "https://rdap.verisign.com/com/v1/domain/",
        "net":  "https://rdap.verisign.com/net/v1/domain/",
        "org":  "https://rdap.publicinterestregistry.org/rdap/domain/",
        "io":   "https://rdap.nic.io/domain/",
        "id":   "https://rdap.pandi.or.id/domain/",
        "co":   "https://rdap.nic.co/domain/",
        "info": "https://rdap.afilias.net/rdap/domain/",
        "biz":  "https://rdap.nic.biz/domain/",
        "dev":  "https://rdap.nic.google/domain/",
        "app":  "https://rdap.nic.google/domain/",
        "edu":  "https://rdap.educause.net/rdap/domain/",
        "gov":  "https://rdap.dotgov.gov/domain/",
    }

    def __init__(self, domain: str):
        self.domain = domain
        self.tld    = domain.split(".")[-1].lower()
        self.results: dict = {}

    @staticmethod
    def _parse_rdap_date(date_str: Optional[str]) -> Optional[str]:
        if not date_str:
            return None
        try:
            dt = datetime.fromisoformat(date_str.replace("Z", "+00:00"))
            return dt.strftime("%Y-%m-%d %H:%M UTC")
        except Exception:
            return date_str

    @staticmethod
    def _calc_age_days(creation_str: Optional[str]) -> Optional[int]:
        if not creation_str:
            return None
        try:
            dt = datetime.fromisoformat(creation_str.replace("Z", "+00:00"))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            now = datetime.now(timezone.utc)
            return (now - dt).days
        except Exception:
            return None

    def _candidates(self) -> list:
        """'www.shop.example.co.id' → coba dari yang terpanjang sampai 2 label."""
        labels = self.domain.split(".")
        return [".".join(labels[i:]) for i in range(0, max(1, len(labels) - 1))]

    async def _fetch_rdap(self, session: aiohttp.ClientSession, name: str) -> tuple:
        """Return (data, error). data None kalau gagal."""
        bases = [b for b in (self.RDAP_SERVERS.get(self.tld), self.RDAP_FALLBACK) if b]
        error = None
        for base in bases:
            try:
                async with session.get(
                    f"{base}{name}", timeout=aiohttp.ClientTimeout(total=12),
                    headers={"Accept": "application/rdap+json, application/json"},
                ) as resp:
                    if resp.status == 200:
                        return await resp.json(content_type=None), None
                    if resp.status == 404:
                        error = "Domain tidak ditemukan di RDAP registry"
                    else:
                        error = f"RDAP HTTP {resp.status}"
            except asyncio.TimeoutError:
                error = "RDAP: Timeout"
            except Exception as e:
                error = f"RDAP: {e or e.__class__.__name__}"
        return None, error

    async def lookup(self, session: aiohttp.ClientSession) -> dict:
        result = {
            "domain":      self.domain,
            "registrar":   None,
            "registered":  None,
            "updated":     None,
            "expires":     None,
            "age_days":    None,
            "age_years":   None,
            "status":      [],
            "nameservers": [],
            "source":      "RDAP",
            "error":       None,
        }

        data = None
        for name in self._candidates():
            data, result["error"] = await self._fetch_rdap(session, name)
            if data:
                result["domain"] = name
                break

        if data:
            events    = {e.get("eventAction"): e.get("eventDate")
                         for e in data.get("events", [])}
            created   = events.get("registration") or events.get("last registration")
            updated   = events.get("last changed")
            expiry    = events.get("expiration")

            registrar = None
            for entity in data.get("entities", []):
                if "registrar" in entity.get("roles", []):
                    vcard = entity.get("vcardArray") or [None, []]
                    for field in vcard[1] if len(vcard) > 1 else []:
                        if field and field[0] == "fn" and len(field) > 3:
                            registrar = field[3]
                            break
                    registrar = registrar or entity.get("handle")
                    break

            age_days = self._calc_age_days(created)
            result.update({
                "registrar":   registrar,
                "registered":  self._parse_rdap_date(created),
                "updated":     self._parse_rdap_date(updated),
                "expires":     self._parse_rdap_date(expiry),
                "age_days":    age_days,
                "age_years":   round(age_days / 365.25, 1) if age_days is not None else None,
                "status":      data.get("status", []),
                "nameservers": [ns.get("ldhName", "").lower()
                                for ns in data.get("nameservers", [])],
                "error":       None,
            })

        self.results = result
        return result


# ══════════════════════════════════════════════════════════════
#  MODUL 5: EMAIL SECURITY CHECKER (SPF / DKIM / DMARC)
# ══════════════════════════════════════════════════════════════

class EmailSecurityChecker:
    """
    Validasi konfigurasi keamanan email domain via DNS records:
      - SPF   (Sender Policy Framework)
      - DKIM  (DomainKeys Identified Mail)
      - DMARC (Domain-based Message Auth)
      - MTA-STS (Mail Transfer Agent Strict Transport Security)
    """

    DKIM_SELECTORS = [
        "default", "google", "mail", "dkim", "selector1", "selector2",
        "k1", "k2", "smtp", "email", "s1", "s2", "key1", "key2",
        "protonmail", "zoho", "sendgrid", "mailchimp", "ses",
    ]

    def __init__(self, domain: str):
        self.domain  = domain
        self.results: dict = {}

    @staticmethod
    def _dns_txt(name: str) -> list:
        try:
            # TXT panjang dipecah jadi beberapa string — gabungkan lagi
            return [
                b"".join(r.strings).decode("utf-8", errors="replace")
                for r in dns_query(name, "TXT")
            ]
        except Exception:
            return []

    def _check_spf(self) -> dict:
        out = {
            "present": False, "record": None, "mechanism": None,
            "all_qualifier": None, "includes": [], "grade": "FAIL",
            "issues": [],
        }
        txts = self._dns_txt(self.domain)
        spf_records = [t for t in txts if t.lower().startswith("v=spf1")]

        if not spf_records:
            out["issues"].append("Tidak ada SPF record — domain rentan terhadap spoofing")
            return out

        if len(spf_records) > 1:
            out["issues"].append("Multiple SPF records ditemukan — ini invalid per RFC 7208")

        record = spf_records[0]
        out["present"] = True
        out["record"]  = record

        all_match = re.search(r'(?:^|\s)([~+\-?]?)all\b', record, re.IGNORECASE)
        if all_match:
            qualifier = all_match.group(1) or "+"   # 'all' tanpa qualifier = +all
            qual_map = {
                "-": "HARDFAIL (ideal)", "~": "SOFTFAIL (ok)",
                "+": "PASS (bahaya!)", "?": "NEUTRAL",
            }
            out["all_qualifier"] = qualifier
            out["mechanism"]     = qual_map[qualifier]
            if qualifier == "+":
                out["issues"].append("+all berarti semua server boleh kirim — SANGAT TIDAK AMAN")
            elif qualifier == "?":
                out["issues"].append("?all (neutral) tidak memberikan perlindungan")
        elif "redirect=" not in record.lower():
            out["issues"].append("Tidak ada mekanisme 'all' — kebijakan default jadi neutral")

        out["includes"] = re.findall(r'include:(\S+)', record, re.IGNORECASE)

        if out["all_qualifier"] == "-":
            out["grade"] = "PASS"
        elif out["all_qualifier"] == "+":
            out["grade"] = "FAIL"
        else:
            out["grade"] = "WARN"

        return out

    def _check_dmarc(self) -> dict:
        out = {
            "present": False, "record": None, "policy": None,
            "subdomain_policy": None, "pct": None, "rua": [],
            "ruf": [], "grade": "FAIL", "issues": [],
        }
        txts = self._dns_txt(f"_dmarc.{self.domain}")
        dmarc_recs = [t for t in txts if t.upper().startswith("V=DMARC1")]

        if not dmarc_recs:
            out["issues"].append("Tidak ada DMARC record — tidak ada kebijakan penanganan spoofing")
            return out

        record         = dmarc_recs[0]
        out["present"] = True
        out["record"]  = record

        p_match = re.search(r'(?:^|;)\s*p\s*=\s*(\w+)', record, re.IGNORECASE)
        if p_match:
            policy      = p_match.group(1).lower()
            out["policy"] = policy
            if policy == "none":
                out["issues"].append("p=none hanya monitoring, tidak memblokir spoofing")
                out["grade"] = "WARN"
            elif policy in ["quarantine", "reject"]:
                out["grade"] = "PASS"

        sp_match = re.search(r'(?:^|;)\s*sp\s*=\s*(\w+)', record, re.IGNORECASE)
        if sp_match:
            out["subdomain_policy"] = sp_match.group(1).lower()

        pct_match = re.search(r'(?:^|;)\s*pct\s*=\s*(\d+)', record, re.IGNORECASE)
        if pct_match:
            pct = int(pct_match.group(1))
            out["pct"] = pct
            if pct < 100:
                out["issues"].append(f"pct={pct} — kebijakan hanya berlaku untuk {pct}% email")

        out["rua"] = [x.strip() for x in re.findall(r'rua\s*=\s*([^;]+)', record, re.IGNORECASE)]
        out["ruf"] = [x.strip() for x in re.findall(r'ruf\s*=\s*([^;]+)', record, re.IGNORECASE)]

        if not out["rua"]:
            out["issues"].append("Tidak ada rua= — laporan aggregate tidak akan diterima")

        return out

    def _check_dkim_selector(self, sel: str) -> Optional[tuple]:
        """Return (selector, record, revoked) kalau selector punya DKIM key."""
        for t in self._dns_txt(f"{sel}._domainkey.{self.domain}"):
            if t.lower().startswith(("v=spf1", "v=dmarc1")):
                continue   # wildcard TXT, bukan DKIM
            m = re.search(r'(?:^|;)\s*p\s*=\s*([^;]*)', t)
            if m and ("v=DKIM1" in t or "k=" in t or len(m.group(1)) > 40):
                return sel, t, not m.group(1).strip()
        return None

    def _check_dkim(self) -> dict:
        out = {"present": False, "found_selectors": [], "revoked_selectors": [],
               "records": {}, "grade": "FAIL", "issues": []}
        with ThreadPoolExecutor(max_workers=len(self.DKIM_SELECTORS)) as pool:
            hits = [h for h in pool.map(self._check_dkim_selector, self.DKIM_SELECTORS) if h]
        for sel, record, revoked in hits:
            out["records"][sel] = record[:200]
            # p= kosong berarti key sudah dicabut (RFC 6376) — bukan DKIM aktif
            (out["revoked_selectors"] if revoked else out["found_selectors"]).append(sel)
        if out["found_selectors"]:
            out["present"] = True
            out["grade"]   = "PASS"
        elif out["revoked_selectors"]:
            out["issues"].append(
                "Hanya ditemukan DKIM key yang dicabut (p= kosong) — domain tidak menandatangani email"
            )
        else:
            out["issues"].append(
                f"Tidak ada DKIM ditemukan dari {len(self.DKIM_SELECTORS)} selector umum. "
                "DKIM mungkin menggunakan selector custom."
            )
        return out

    def _check_mta_sts(self) -> dict:
        out = {"present": False, "record": None}
        txts = self._dns_txt(f"_mta-sts.{self.domain}")
        for t in txts:
            if "v=STSv1" in t:
                out["present"] = True
                out["record"]  = t
                break
        return out

    def _check_bimi(self) -> dict:
        out = {"present": False, "record": None}
        txts = self._dns_txt(f"default._bimi.{self.domain}")
        for t in txts:
            if "v=BIMI1" in t:
                out["present"] = True
                out["record"]  = t
                break
        return out

    async def check_all(self) -> dict:
        spf, dmarc, dkim, mta_sts, bimi = await asyncio.gather(
            asyncio.to_thread(self._check_spf),
            asyncio.to_thread(self._check_dmarc),
            asyncio.to_thread(self._check_dkim),
            asyncio.to_thread(self._check_mta_sts),
            asyncio.to_thread(self._check_bimi),
        )

        grades  = {"PASS": 2, "WARN": 1, "FAIL": 0}
        total   = grades[spf["grade"]] + grades[dmarc["grade"]] + grades[dkim["grade"]]
        max_sc  = 6
        pct     = int(total / max_sc * 100)
        if pct >= 80:   overall = "SECURE"
        elif pct >= 50: overall = "PARTIAL"
        else:           overall = "VULNERABLE"

        self.results = {
            "domain":        self.domain,
            "overall":       overall,
            "overall_score": pct,
            "spf":           spf,
            "dkim":          dkim,
            "dmarc":         dmarc,
            "mta_sts":       mta_sts,
            "bimi":          bimi,
        }
        return self.results


# ══════════════════════════════════════════════════════════════
#  MODUL 6: USERNAME CHECKER
# ══════════════════════════════════════════════════════════════

class UsernameChecker:
    """Cek keberadaan username di 20 platform publik."""

    def __init__(self, username: str):
        self.username = username
        self.results: dict = {}

    async def _check_one(
        self, session: aiohttp.ClientSession, platform: str, cfg: dict
    ) -> tuple:
        name = urllib.parse.quote(self.username, safe="")
        res = {"url": cfg["url"].format(name), "status": "UNKNOWN", "status_code": None}
        if cfg.get("unreliable"):
            res["note"] = "Wajib login / selalu balas 200 — cek manual"
            return platform, res
        try:
            async with session.get(
                cfg.get("check", cfg["url"]).format(name),
                timeout=aiohttp.ClientTimeout(total=10),
                headers={"User-Agent": BROWSER_UA, "Accept-Language": "en-US,en;q=0.9"},
                allow_redirects=True,
            ) as resp:
                res["status_code"] = resp.status
                if resp.status in (404, 410):
                    res["status"] = "NOT_FOUND"
                elif resp.status == 200:
                    absent, present = cfg.get("absent"), cfg.get("present")
                    blocked = cfg.get("blocked")
                    body = await resp.text(errors="ignore") if (absent or present or blocked) else ""
                    if blocked and blocked in body:
                        res["note"] = "Halaman anti-bot — cek manual"
                        return platform, res
                    if cfg.get("exact_absent"):
                        missing = body.strip() == absent
                    else:
                        missing = bool(absent and absent in body) or \
                                  bool(present and present not in body)
                    res["status"] = "NOT_FOUND" if missing else "FOUND"
                else:
                    # 403/429/999 dst = diblokir anti-bot, bukan bukti ada/tidaknya profil
                    res["note"] = f"HTTP {resp.status} — diblokir / rate limit"
        except asyncio.TimeoutError:
            res["status"] = "TIMEOUT"
        except Exception as e:
            res["status"] = "ERROR"
            res["error"]  = str(e) or e.__class__.__name__
        return platform, res

    async def check_all_platforms(self, session: aiohttp.ClientSession) -> dict:
        sem = asyncio.Semaphore(10)

        async def _guarded(p, c):
            async with sem:
                return await self._check_one(session, p, c)

        results = await asyncio.gather(*[
            _guarded(p, c) for p, c in USERNAME_PLATFORMS.items()
        ])
        self.results = dict(results)
        return self.results


# ══════════════════════════════════════════════════════════════
#  MODUL 7: DATA BREACH CHECKER  (HIBP v3)
# ══════════════════════════════════════════════════════════════

class BreachChecker:
    """
    Periksa kebocoran kredensial email via HaveIBeenPwned API v3.
    CATATAN ETIS: Gunakan hanya untuk email organisasi milik sendiri.
    API key gratis: https://haveibeenpwned.com/API/Key
    """

    RATE_DELAY = 1.6   # detik antar request (batas HIBP)

    def __init__(self, hibp_api_key: Optional[str] = None):
        self.hibp_api_key = hibp_api_key
        self.results: dict = {}

    async def check_hibp(self, session: aiohttp.ClientSession, email: str) -> dict:
        res = {
            "email": email, "breached": False, "breach_count": 0,
            "breaches": [], "pastes": [], "source": "HaveIBeenPwned", "error": None,
        }
        if not self.hibp_api_key:
            res["error"] = "API key tidak ada. Daftar gratis di haveibeenpwned.com/API/Key"
            return res
        hdrs = {
            "hibp-api-key": self.hibp_api_key,
            "User-Agent": USER_AGENT,
        }
        account = urllib.parse.quote(email, safe="")
        try:
            url = f"https://haveibeenpwned.com/api/v3/breachedaccount/{account}?truncateResponse=false"
            async with session.get(url, headers=hdrs, timeout=aiohttp.ClientTimeout(total=10)) as r:
                if r.status == 200:
                    data = await r.json(content_type=None)
                    res.update({
                        "breached":     True,
                        "breach_count": len(data),
                        "breaches": [{
                            "name":         b.get("Name"),
                            "domain":       b.get("Domain"),
                            "breach_date":  b.get("BreachDate"),
                            "description":  re.sub(r"<[^>]+>", "", b.get("Description") or "")[:200],
                            "data_classes": b.get("DataClasses", []),
                            "is_verified":  b.get("IsVerified", False),
                            "pwn_count":    b.get("PwnCount", 0),
                        } for b in data],
                    })
                elif r.status == 404:
                    pass
                elif r.status == 401:
                    res["error"] = "API key tidak valid"
                elif r.status == 429:
                    res["error"] = "Rate limit HIBP tercapai. Tunggu sebentar lalu coba lagi."
                else:
                    res["error"] = f"HIBP: HTTP {r.status}"
        except asyncio.TimeoutError:
            res["error"] = "HIBP: Timeout"
        except Exception as e:
            res["error"] = f"HIBP: {e}"

        if res["error"]:
            return res

        # Endpoint paste kena rate limit yang sama dengan endpoint breach
        await asyncio.sleep(self.RATE_DELAY)
        try:
            url2 = f"https://haveibeenpwned.com/api/v3/pasteaccount/{account}"
            async with session.get(url2, headers=hdrs, timeout=aiohttp.ClientTimeout(total=10)) as r:
                if r.status == 200:
                    res["pastes"] = [{
                        "source":      p.get("Source"),
                        "title":       p.get("Title"),
                        "date":        p.get("Date"),
                        "email_count": p.get("EmailCount"),
                    } for p in await r.json(content_type=None)]
        except Exception:
            pass

        return res

    async def check_emails_bulk(self, session: aiohttp.ClientSession, emails: list) -> dict:
        results = {}
        # Buang entri kosong & duplikat, urutan tetap
        emails = list(dict.fromkeys(e.strip().lower() for e in emails if e.strip()))
        requested = False
        for email in emails:
            if not re.match(r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$', email):
                results[email] = {"error": "Format email tidak valid", "email": email}
                continue
            if requested and self.hibp_api_key:
                await asyncio.sleep(self.RATE_DELAY)
            results[email] = await self.check_hibp(session, email)
            requested = True
        self.results = results
        return results


# ══════════════════════════════════════════════════════════════
#  MODUL 8: IP REPUTATION CHECKER
# ══════════════════════════════════════════════════════════════

class IPReputationChecker:
    """
    Periksa reputasi IP dari tiga threat-intel feed publik gratis:
      ① AbuseIPDB   — abuse confidence score, TOR/proxy flag, usage type
      ② AlienVault OTX — pulse count, malware families, community tags
      ③ ip-api.com  — geolokasi, ISP, ASN, proxy/hosting flag
    """

    _RISK_TABLE = [
        (0,  20,  "LOW",      "green"),
        (21, 50,  "MEDIUM",   "yellow"),
        (51, 75,  "HIGH",     "red"),
        (76, 100, "CRITICAL", "bold red"),
    ]

    def __init__(self, abuseipdb_key: Optional[str] = None):
        self.abuseipdb_key = abuseipdb_key
        self.results: dict = {}

    @staticmethod
    def _risk_label(score: int) -> tuple:
        for lo, hi, label, color in IPReputationChecker._RISK_TABLE:
            if lo <= score <= hi:
                return label, color
        return "UNKNOWN", "dim"

    @staticmethod
    def _resolve_domain_to_ips(domain: str) -> list:
        try:
            return [str(r) for r in dns_query(domain, "A")]
        except Exception:
            return []

    async def _query_abuseipdb(self, session: aiohttp.ClientSession, ip: str) -> dict:
        out = {
            "source": "AbuseIPDB", "available": False,
            "abuse_score": None, "total_reports": None,
            "last_reported": None, "usage_type": None,
            "isp": None, "country": None,
            "is_tor": None, "is_proxy": None, "error": None,
        }
        if not self.abuseipdb_key:
            out["error"] = "Tidak ada API key. Daftar gratis di abuseipdb.com/register"
            return out
        try:
            async with session.get(
                "https://api.abuseipdb.com/api/v2/check",
                headers={"Key": self.abuseipdb_key, "Accept": "application/json"},
                params={"ipAddress": ip, "maxAgeInDays": 90, "verbose": ""},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                if resp.status == 200:
                    d = (await resp.json(content_type=None)).get("data", {})
                    out.update({
                        "available":     True,
                        "abuse_score":   d.get("abuseConfidenceScore", 0),
                        "total_reports": d.get("totalReports", 0),
                        "last_reported": d.get("lastReportedAt"),
                        "usage_type":    d.get("usageType"),
                        "isp":           d.get("isp"),
                        "country":       d.get("countryCode"),
                        "domain":        d.get("domain"),
                        "is_tor":        d.get("isTor", False),
                        "is_proxy":      d.get("isPublicAccessPoint", False),
                    })
                elif resp.status == 401:
                    out["error"] = "AbuseIPDB: API key tidak valid"
                elif resp.status == 429:
                    out["error"] = "AbuseIPDB: Rate limit harian tercapai"
                else:
                    out["error"] = f"AbuseIPDB: HTTP {resp.status}"
        except asyncio.TimeoutError:
            out["error"] = "AbuseIPDB: Timeout"
        except Exception as e:
            out["error"] = f"AbuseIPDB: {e}"
        return out

    async def _query_otx(self, session: aiohttp.ClientSession, ip: str) -> dict:
        out = {
            "source": "AlienVault OTX", "available": False,
            "pulse_count": 0, "reputation": None,
            "country": None, "asn": None, "city": None,
            "malware_families": [], "tags": [],
            "threat_score": 0, "error": None,
        }
        kind = "IPv6" if ":" in ip else "IPv4"
        try:
            async with session.get(
                f"https://otx.alienvault.com/api/v1/indicators/{kind}/{ip}/general",
                headers={"User-Agent": USER_AGENT},
                timeout=aiohttp.ClientTimeout(total=15),
            ) as resp:
                if resp.status == 200:
                    d   = await resp.json(content_type=None)
                    pi  = d.get("pulse_info") or {}
                    pulses    = pi.get("pulses", [])
                    families: set = set()
                    tags: set     = set()
                    for p in pulses[:10]:
                        for mf in p.get("malware_families", []):
                            families.add(mf.get("display_name", ""))
                        for tag in p.get("tags", []):
                            tags.add(tag)
                    pulse_count = pi.get("count", 0)
                    out.update({
                        "available":        True,
                        "pulse_count":      pulse_count,
                        "reputation":       d.get("reputation", 0),
                        "country":          d.get("country_name"),
                        "asn":              d.get("asn"),
                        "city":             d.get("city"),
                        "malware_families": sorted(filter(None, families))[:5],
                        "tags":             sorted(filter(None, tags))[:10],
                        "threat_score":     min(100, pulse_count * 5),
                    })
                elif resp.status == 404:
                    out["available"] = True
                else:
                    out["error"] = f"OTX: HTTP {resp.status}"
        except asyncio.TimeoutError:
            out["error"] = "OTX: Timeout"
        except Exception as e:
            out["error"] = f"OTX: {e}"
        return out

    async def _query_ipapi(self, session: aiohttp.ClientSession, ip: str) -> dict:
        out = {
            "source": "ip-api.com", "available": False,
            "country": None, "region": None, "city": None,
            "isp": None, "org": None, "asn": None, "timezone": None,
            "is_proxy": None, "is_hosting": None, "error": None,
        }
        try:
            fields = "status,country,regionName,city,isp,org,as,timezone,proxy,hosting"
            async with session.get(
                f"http://ip-api.com/json/{ip}?fields={fields}",
                timeout=aiohttp.ClientTimeout(total=8),
            ) as resp:
                if resp.status == 200:
                    d = await resp.json(content_type=None)
                    if d.get("status") == "success":
                        out.update({
                            "available":  True,
                            "country":    d.get("country"),
                            "region":     d.get("regionName"),
                            "city":       d.get("city"),
                            "isp":        d.get("isp"),
                            "org":        d.get("org"),
                            "asn":        d.get("as"),
                            "timezone":   d.get("timezone"),
                            "is_proxy":   d.get("proxy", False),
                            "is_hosting": d.get("hosting", False),
                        })
                    else:
                        out["error"] = "ip-api: IP private atau tidak valid"
                elif resp.status == 429:
                    out["error"] = "ip-api: Rate limit (45 req/menit)"
                else:
                    out["error"] = f"ip-api: HTTP {resp.status}"
        except asyncio.TimeoutError:
            out["error"] = "ip-api: Timeout"
        except Exception as e:
            out["error"] = f"ip-api: {e}"
        return out

    async def check_single_ip(self, session: aiohttp.ClientSession, ip: str) -> dict:
        abuse, otx, geo = await asyncio.gather(
            self._query_abuseipdb(session, ip),
            self._query_otx(session, ip),
            self._query_ipapi(session, ip),
        )
        abuse_score = abuse.get("abuse_score") or 0
        otx_score   = otx.get("threat_score")  or 0
        # Bobot 60/40 hanya kalau kedua feed tersedia; kalau cuma satu, pakai yang ada
        if abuse.get("available") and otx.get("available"):
            combined = int(abuse_score * 0.6 + otx_score * 0.4)
        elif abuse.get("available"):
            combined = int(abuse_score)
        else:
            combined = int(otx_score)
        risk_label, risk_color = self._risk_label(combined)
        flags = []
        if abuse.get("is_tor"):                            flags.append("TOR_EXIT_NODE")
        if abuse.get("is_proxy") or geo.get("is_proxy"):  flags.append("PROXY_DETECTED")
        if geo.get("is_hosting"):                          flags.append("HOSTING_PROVIDER")
        if otx.get("malware_families"):                    flags.append("MALWARE_ASSOCIATED")
        if (abuse.get("total_reports") or 0) > 100:       flags.append("HIGH_REPORT_COUNT")
        return {
            "ip":                ip,
            "overall_risk_score":combined,
            "risk_level":        risk_label,
            "risk_color":        risk_color,
            "flags":             flags,
            "abuseipdb":         abuse,
            "alienvault_otx":    otx,
            "geolocation":       geo,
        }

    async def check_ips_bulk(self, session: aiohttp.ClientSession, targets: list) -> dict:
        resolved: list = []
        for item in targets:
            item = item.strip()
            if not item:
                continue
            try:
                addr = ipaddress.ip_address(item)
                if not addr.is_global:
                    console.print(
                        f"  [yellow]⚠ {escape(item)} bukan IP publik (private/loopback) — dilewati[/yellow]"
                    )
                    continue
                resolved.append(str(addr))
            except ValueError:
                item = normalize_domain(item)
                ips = await asyncio.to_thread(self._resolve_domain_to_ips, item)
                if ips:
                    console.print(
                        f"  [dim]→ [cyan]{item}[/cyan] resolved: [green]{', '.join(ips)}[/green][/dim]"
                    )
                    resolved.extend(ips)
                else:
                    console.print(f"  [yellow]⚠ Tidak bisa resolve: {escape(item)}[/yellow]")
        unique = list(dict.fromkeys(resolved))
        sem    = asyncio.Semaphore(5)

        async def _check(ip):
            async with sem:
                return ip, await self.check_single_ip(session, ip)

        results = await asyncio.gather(*[_check(ip) for ip in unique])
        self.results = dict(results)
        return self.results


# ══════════════════════════════════════════════════════════════
#  MODUL 9: EXIFTOOL METADATA EXTRACTOR
# ══════════════════════════════════════════════════════════════

class ExifToolExtractor:
    """
    Wrapper profesional untuk binary ExifTool.
    Mendukung file lokal maupun URL remote (auto-download → extract → cleanup).
    Menampilkan metadata krusial dalam tabel Rich yang konsisten dengan modul lain.
    Me-return dictionary lengkap untuk integrasi ke laporan JSON/HTML.
    """

    _PRIORITY_FIELDS: list = [
        ("FileName",         "File Name"),
        ("FileSize",         "File Size"),
        ("FileType",         "File Type"),
        ("MIMEType",         "MIME Type"),
        ("ImageWidth",       "Image Width"),
        ("ImageHeight",      "Image Height"),
        ("ColorSpace",       "Color Space"),
        ("BitDepth",         "Bit Depth"),
        ("Compression",      "Compression"),
        ("CreateDate",       "Create Date"),
        ("DateTimeOriginal", "Date Time Original"),
        ("ModifyDate",       "Modify Date"),
        ("FileModifyDate",   "File Modify Date"),
        ("Software",         "Software / Creator"),
        ("Creator",          "Creator"),
        ("Author",           "Author"),
        ("Producer",         "Producer"),
        ("Make",             "Camera Make"),
        ("Model",            "Camera Model"),
        ("LensModel",        "Lens Model"),
        ("ExposureTime",     "Exposure Time"),
        ("FNumber",          "F-Number"),
        ("ISO",              "ISO Speed"),
        ("FocalLength",      "Focal Length"),
        ("Flash",            "Flash"),
        ("GPSLatitude",      "GPS Latitude"),
        ("GPSLongitude",     "GPS Longitude"),
        ("GPSAltitude",      "GPS Altitude"),
        ("GPSLatitudeRef",   "GPS Lat Ref"),
        ("GPSLongitudeRef",  "GPS Lon Ref"),
        ("GPSPosition",      "GPS Position"),
        ("Comment",          "Comment"),
        ("Description",      "Description"),
        ("Title",            "Title"),
        ("Keywords",         "Keywords"),
        ("Copyright",        "Copyright"),
        ("XMPToolkit",       "XMP Toolkit"),
        ("DocumentID",       "Document ID"),
        ("InstanceID",       "Instance ID"),
        ("PageCount",        "Page Count"),
        ("Language",         "Language"),
    ]

    _PRIORITY_KEYS: set = {k for k, _ in _PRIORITY_FIELDS}

    MAX_DOWNLOAD_BYTES = 200 * 1024 * 1024

    def __init__(self, target: str):
        self.target    = target
        self.is_url    = target.lower().startswith(("http://", "https://"))
        self._tmp_file: Optional[Path] = None
        self.results: dict = {
            "target":       target,
            "type":         "url" if self.is_url else "local",
            "error":        None,
            "raw_metadata": {},
            "filtered":     {},
            "gps_coords":   None,
            "maps_link":    None,
        }

    # ── Public API ────────────────────────────────────────────

    def run(self) -> dict:
        """Entry point utama. Return self.results dict."""
        if not self._check_exiftool():
            self.results["error"] = (
                "ExifTool binary tidak ditemukan. "
                "Install: sudo apt install libimage-exiftool-perl"
            )
            return self.results

        file_path: Optional[Path] = None
        try:
            file_path = self._resolve_file()
            if file_path is None:
                return self.results

            raw = self._run_exiftool(file_path)
            if raw is None:
                return self.results

            self.results["raw_metadata"] = raw
            self.results["filtered"]     = self._filter_metadata(raw)
            self._extract_gps(raw)

        finally:
            self._cleanup()

        return self.results

    def print_results(self, console_obj) -> None:
        """Cetak tabel Rich ke console."""
        r = self.results

        if r["error"]:
            console_obj.print(f"  [bold red]✗ ERROR:[/bold red] [red]{escape(r['error'])}[/red]")
            console_obj.print()
            return

        filtered: dict = r.get("filtered", {})
        if not filtered:
            console_obj.print("  [yellow]⚠ Tidak ada metadata yang dapat diekstrak.[/yellow]")
            console_obj.print()
            return

        t = Table(
            title="[bold cyan]Metadata Summary[/bold cyan]",
            box=box.ROUNDED,
            style="dim",
            show_header=True,
            header_style="bold cyan",
            min_width=72,
        )
        t.add_column("Field", style="cyan",       width=26, no_wrap=True)
        t.add_column("Value", style="bold white", width=48, overflow="fold")

        for key, label in self._PRIORITY_FIELDS:
            if key not in filtered:
                continue
            val = escape(str(filtered[key]))
            if key in ("GPSLatitude", "GPSLongitude", "GPSPosition"):
                t.add_row(
                    f"[bold yellow]{label}[/bold yellow]",
                    f"[bold yellow]{val}[/bold yellow]"
                )
            else:
                t.add_row(label, val)

        console_obj.print(t)

        if r["gps_coords"] and r["maps_link"]:
            lat, lon = r["gps_coords"]
            console_obj.print()
            gps_table = Table(
                title="[bold yellow]⚠  GPS INTELLIGENCE — LOCATION DETECTED[/bold yellow]",
                box=box.DOUBLE_EDGE,
                style="yellow",
                show_header=False,
                min_width=72,
            )
            gps_table.add_column("Key",   style="bold yellow", width=24)
            gps_table.add_column("Value", style="bold white",  width=50, overflow="fold")
            gps_table.add_row("Latitude",  str(lat))
            gps_table.add_row("Longitude", str(lon))
            gps_table.add_row(
                "Google Maps",
                f"[bold underline cyan]{r['maps_link']}[/bold underline cyan]"
            )
            console_obj.print(gps_table)

        total_raw   = len(r.get("raw_metadata", {}))
        total_shown = len(filtered)
        console_obj.print(
            f"\n  [dim]Ditampilkan [bold]{total_shown}[/bold] field krusial "
            f"dari [bold]{total_raw}[/bold] total metadata.[/dim]"
        )
        console_obj.print()

    # ── Private helpers ───────────────────────────────────────

    def _check_exiftool(self) -> bool:
        return shutil.which("exiftool") is not None

    def _resolve_file(self) -> Optional[Path]:
        if self.is_url:
            return self._download_url(self.target)
        else:
            p = Path(self.target)
            if not p.exists():
                self.results["error"] = f"File tidak ditemukan: {self.target}"
                return None
            if not p.is_file():
                self.results["error"] = f"Path bukan file: {self.target}"
                return None
            return p

    def _download_url(self, url: str) -> Optional[Path]:
        try:
            parsed   = urllib.parse.urlparse(url)
            url_path = parsed.path.rstrip("/")
            ext      = Path(url_path).suffix[:10] if Path(url_path).suffix else ".tmp"

            fd, tmp_path = tempfile.mkstemp(suffix=ext, prefix="freease_exif_")
            os.close(fd)
            self._tmp_file = Path(tmp_path)

            req = urllib.request.Request(
                url,
                headers={"User-Agent": BROWSER_UA}
            )
            with urllib.request.urlopen(req, timeout=30) as resp, \
                 open(tmp_path, "wb") as out:
                total = 0
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > self.MAX_DOWNLOAD_BYTES:
                        self.results["error"] = (
                            f"File remote terlalu besar "
                            f"(>{self.MAX_DOWNLOAD_BYTES // 1024 // 1024} MB) — dibatalkan"
                        )
                        return None
                    out.write(chunk)

            return self._tmp_file

        except urllib.error.HTTPError as e:
            self.results["error"] = f"Gagal download URL — HTTP {e.code} {e.reason}"
            return None
        except urllib.error.URLError as e:
            self.results["error"] = f"Gagal download URL — {e.reason}"
            return None
        except TimeoutError:
            self.results["error"] = "Download timeout (>30 detik)"
            return None
        except Exception as e:
            self.results["error"] = f"Download error: {e}"
            return None

    def _run_exiftool(self, file_path: Path) -> Optional[dict]:
        try:
            proc = subprocess.run(
                ["exiftool", "-json", "-n", str(file_path)],
                capture_output=True,
                text=True,
                timeout=30,
            )

            if proc.returncode not in (0, 1):
                stderr_msg = proc.stderr.strip()[:200] if proc.stderr else "Unknown error"
                self.results["error"] = f"ExifTool error (rc={proc.returncode}): {stderr_msg}"
                return None

            if not proc.stdout.strip():
                self.results["error"] = (
                    "ExifTool tidak menghasilkan output. "
                    "File mungkin rusak atau tidak didukung."
                )
                return None

            parsed = json.loads(proc.stdout)
            if not parsed or not isinstance(parsed, list):
                self.results["error"] = "Output ExifTool kosong atau format tidak dikenal."
                return None

            return parsed[0]

        except subprocess.TimeoutExpired:
            self.results["error"] = "ExifTool timeout (>30 detik) — file mungkin terlalu besar."
            return None
        except json.JSONDecodeError as e:
            self.results["error"] = f"Gagal parse JSON output ExifTool: {e}"
            return None
        except FileNotFoundError:
            self.results["error"] = "Binary 'exiftool' tidak ditemukan di PATH."
            return None
        except Exception as e:
            self.results["error"] = f"Unexpected error saat menjalankan ExifTool: {e}"
            return None

    def _filter_metadata(self, raw: dict) -> dict:
        filtered: dict = {}
        for key, _label in self._PRIORITY_FIELDS:
            val = raw.get(key)
            if val is not None and str(val).strip() not in ("", "0", "Unknown"):
                filtered[key] = val
        return filtered

    def _extract_gps(self, raw: dict) -> None:
        lat = raw.get("GPSLatitude")
        lon = raw.get("GPSLongitude")

        if (lat is None or lon is None) and raw.get("GPSPosition"):
            lat, lon = self._parse_gps_position(raw["GPSPosition"])

        if lat is None or lon is None:
            return

        try:
            lat_f = float(lat)
            lon_f = float(lon)

            lat_ref = raw.get("GPSLatitudeRef", "N")
            lon_ref = raw.get("GPSLongitudeRef", "E")
            if isinstance(lat_ref, str) and lat_ref.upper() == "S":
                lat_f = -abs(lat_f)
            if isinstance(lon_ref, str) and lon_ref.upper() == "W":
                lon_f = -abs(lon_f)

            if not (-90 <= lat_f <= 90 and -180 <= lon_f <= 180) or (lat_f == 0 and lon_f == 0):
                return   # koordinat kosong / tidak masuk akal

            self.results["gps_coords"] = (round(lat_f, 7), round(lon_f, 7))
            self.results["maps_link"]  = (
                f"https://www.google.com/maps?q={lat_f:.7f},{lon_f:.7f}"
            )
            self.results["filtered"]["GPS_MapsLink"] = self.results["maps_link"]

        except (ValueError, TypeError):
            pass

    def _parse_gps_position(
        self, gps_str: str
    ) -> tuple:
        m = re.match(
            r"^\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*$",
            gps_str.strip()
        )
        if m:
            return float(m.group(1)), float(m.group(2))

        dms_pattern = re.compile(
            r"(\d+)\s*deg\s+(\d+)'\s*([\d.]+)\"\s*([NSEW])"
        )
        matches = dms_pattern.findall(gps_str)
        if len(matches) >= 2:
            def dms_to_dec(d, m, s, ref):
                dec = float(d) + float(m) / 60 + float(s) / 3600
                if ref in ("S", "W"):
                    dec = -dec
                return dec
            lat = dms_to_dec(*matches[0])
            lon = dms_to_dec(*matches[1])
            return lat, lon

        return None, None

    def _cleanup(self) -> None:
        if self._tmp_file and self._tmp_file.exists():
            try:
                self._tmp_file.unlink()
            except Exception:
                pass


# ══════════════════════════════════════════════════════════════
#  REPORT GENERATOR  (JSON + HTML)
# ══════════════════════════════════════════════════════════════

class ReportGenerator:
    """Export hasil scan ke file JSON terstruktur dan HTML dashboard interaktif."""

    def __init__(self, scan_results: dict, output_dir: str = "."):
        self.scan_results = scan_results
        self.output_dir   = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.ts = datetime.now().strftime("%Y%m%d_%H%M%S")

    # ── Utility ──────────────────────────────────────────────

    @staticmethod
    def _badge(cls: str, text: str) -> str:
        """Generate HTML badge span (teks di-escape)."""
        return f'<span class="badge badge-{cls}">{html.escape(str(text))}</span>'

    # ── JSON ─────────────────────────────────────────────────

    def save_json(self, filename: Optional[str] = None) -> str:
        fp = self.output_dir / (filename or f"freease_report_{self.ts}.json")
        with open(fp, "w", encoding="utf-8") as f:
            json.dump({
                "tool":       "freease",
                "author":     "Kodok-Kejepit",
                "version":    VERSION,
                "scan_time":  datetime.now().isoformat(),
                "disclaimer": "Tool ini hanya untuk riset akademis dan penggunaan defensif.",
                "results":    self.scan_results,
            }, f, indent=2, ensure_ascii=False, default=str)
        return str(fp)

    # ── HTML ─────────────────────────────────────────────────

    def save_html(self, filename: Optional[str] = None) -> str:
        fp = self.output_dir / (filename or f"freease_report_{self.ts}.html")

        r = self.scan_results
        _badge = self._badge   # local shorthand

        # Semua data hasil scan berasal dari pihak luar (banner, TXT record, header…)
        # → wajib di-escape sebelum masuk HTML.
        def _e(value) -> str:
            return html.escape(str(value))

        ran = set(r.get("modules_run", []))

        def _hide(module: str) -> str:
            """Sembunyikan section/kartu milik modul yang tidak dijalankan."""
            return "" if module in ran else ' style="display:none"'
        # ── Statistik ringkasan ───────────────────────────────
        recon    = r.get("network_recon", {})
        subs     = recon.get("subdomains", [])
        web_tech = recon.get("web_tech", {})
        sec_hdrs = web_tech.get("security_headers", {})
        sec_score = int(sum(1 for v in sec_hdrs.values() if v) / max(len(sec_hdrs), 1) * 100)

        user_res   = r.get("username_check", {})
        found_u    = [p for p, d in user_res.items() if d.get("status") == "FOUND"]

        breach_res  = r.get("breach_check", {})
        breached_em = [e for e, d in breach_res.items() if d.get("breached")]

        ip_res    = r.get("ip_reputation", {})
        risky_ips = [ip for ip, d in ip_res.items() if d.get("overall_risk_score", 0) > 20]

        port_res   = r.get("port_scan", {})
        open_ports = port_res.get("open_ports", [])

        waf_res   = r.get("waf_detection", {})
        whois_res = r.get("whois", {})
        email_sec = r.get("email_security", {})
        exif_res  = r.get("exif_metadata", {})

        results_json = json.dumps(r, indent=2, default=str, ensure_ascii=False)
        raw_json_html = _e(results_json[:15000])
        if len(results_json) > 15000:
            raw_json_html += "\n…(truncated — lihat file JSON untuk data lengkap)"

        summary    = r.get("intelligence_summary") or {}
        risk_level = summary.get("overall_risk", "—")
        risk_cls   = {"LOW": "sg", "MEDIUM": "sy", "HIGH": "sr", "CRITICAL": "sr"}.get(risk_level, "sb")

        # ── DNS rows ──────────────────────────────────────────
        dns_rows = ""
        for rtype, records in recon.get("dns_records", {}).items():
            if records:
                recs = "<br>".join(f"<code>{_e(rec)}</code>" for rec in records)
                dns_rows += f"<tr><td>{_badge('blue', rtype)}</td><td>{recs}</td></tr>\n"

        # ── Subdomain pills ───────────────────────────────────
        sub_pills = "".join(
            f'<div class="subdomain-item">{_e(s)}</div>' for s in subs
        ) or '<div style="color:var(--text2)">—</div>'

        # ── Security header rows ──────────────────────────────
        sec_rows = ""
        for h, v in sec_hdrs.items():
            icon = "✅" if v else "❌"
            bc   = "green" if v else "red"
            val  = (v[:80] + "…") if v and len(v) > 80 else (v or "MISSING")
            sec_rows += f"""
        <div class="header-check">
          <span>{icon}</span>
          <span style="flex:1">{h}</span>
          {_badge(bc, val)}
        </div>"""

        # ── Port scan rows ────────────────────────────────────
        port_rows = ""
        if open_ports:
            for p in open_ports:
                warn_badge = (
                    f' {_badge("red", p["warning"][:30])}'
                    if p.get("warning") else ""
                )
                banner_td = (
                    f'<code style="font-size:.72rem">{_e(p["banner"][:60])}</code>'
                    if p.get("banner")
                    else "<span style='color:var(--text2)'>—</span>"
                )
                port_rows += (
                    f"<tr><td><strong style='color:var(--blue)'>{p['port']}</strong></td>"
                    f"<td>{_badge('green', 'OPEN')}</td>"
                    f"<td>{p.get('service','?')}{warn_badge}</td>"
                    f"<td>{banner_td}</td></tr>\n"
                )
        else:
            port_rows = (
                "<tr><td colspan='4' style='color:var(--text2);text-align:center'>"
                "Tidak ada port terbuka yang ditemukan</td></tr>"
            )

        # ── WAF section ───────────────────────────────────────
        waf_html = ""
        if waf_res:
            if waf_res.get("waf_detected"):
                conf = waf_res.get("confidence", 0)
                bc   = "green" if conf >= 60 else "yellow"
                waf_html = f"""
          <div style="display:flex;align-items:center;gap:1rem;flex-wrap:wrap;margin-bottom:.8rem">
            {_badge(bc, f"WAF TERDETEKSI: {waf_res['primary_waf']}")}
            <span style="font-size:.8rem;color:var(--text2)">Confidence: <strong style="color:var(--blue)">{conf}%</strong></span>
          </div>"""
                for det in waf_res.get("all_detections", []):
                    matched_str = _e(", ".join(det["matched"][:5]))
                    waf_html += f"""
          <div style="font-size:.78rem;padding:.3rem 0;border-bottom:1px solid var(--border)">
            <span style="color:var(--accent)">{_e(det['name'])}</span>
            <span style="color:var(--text2);margin:0 .5rem">|</span>
            Confidence: <strong>{det['confidence']}%</strong>
            <span style="color:var(--text2);margin:0 .5rem">|</span>
            Matched: <code style="font-size:.7rem">{matched_str}</code>
          </div>"""
            else:
                waf_html = (
                    f'<div style="color:var(--text2)">Tidak ada WAF terdeteksi. '
                    f'{_badge("yellow", "UNPROTECTED / UNKNOWN")}</div>'
                )
        else:
            waf_html = (
                '<div style="color:var(--text2)">Modul WAF tidak dijalankan. '
                'Gunakan flag <code>-d</code>.</div>'
            )

        # ── WHOIS section ─────────────────────────────────────
        whois_html = ""
        if whois_res:
            if whois_res.get("error"):
                whois_html = f'<div style="color:var(--yellow)">⚠ {_e(whois_res["error"])}</div>'
            else:
                age_str  = (
                    f"{whois_res.get('age_years', '?')} tahun "
                    f"({whois_res.get('age_days', '?')} hari)"
                    if whois_res.get("age_days") else "—"
                )
                ns_pills = "".join(
                    f'<div class="subdomain-item">{_e(ns)}</div>'
                    for ns in whois_res.get("nameservers", [])
                )
                whois_html = f"""
          <table style="margin-bottom:.8rem">
            <thead><tr><th>Field</th><th>Value</th></tr></thead>
            <tbody>
              <tr><td>Registrar</td><td><strong>{_e(whois_res.get("registrar") or "—")}</strong></td></tr>
              <tr><td>Tanggal Registrasi</td><td>{_e(whois_res.get("registered") or "—")}</td></tr>
              <tr><td>Terakhir Update</td><td>{_e(whois_res.get("updated") or "—")}</td></tr>
              <tr><td>Expiry</td><td>{_e(whois_res.get("expires") or "—")}</td></tr>
              <tr><td>Usia Domain</td><td><strong style="color:var(--blue)">{age_str}</strong></td></tr>
              <tr><td>Status</td><td>{' '.join(_badge('gray', s) for s in whois_res.get('status', [])[:4])}</td></tr>
            </tbody>
          </table>
          <div style="font-size:.8rem;color:var(--text2);margin-bottom:.3rem">Nameservers:</div>
          <div class="subdomain-grid">{ns_pills or "—"}</div>"""
        else:
            whois_html = (
                '<div style="color:var(--text2)">Modul WHOIS tidak dijalankan. '
                'Gunakan flag <code>-d</code>.</div>'
            )

        # ── Email Security section ─────────────────────────────

        def _grade_badge(grade: str) -> str:
            mp = {
                "PASS": "green", "WARN": "yellow", "FAIL": "red",
                "SECURE": "green", "PARTIAL": "yellow", "VULNERABLE": "red",
            }
            return _badge(mp.get(grade, "gray"), grade)

        email_sec_html = ""
        if email_sec:
            ov_score = email_sec.get("overall_score", 0)
            email_sec_html = f"""
          <div style="display:flex;align-items:center;gap:1rem;margin-bottom:1rem">
            {_grade_badge(email_sec.get("overall","UNKNOWN"))}
            <span style="font-size:.8rem;color:var(--text2)">Overall Score: <strong style="color:var(--blue)">{ov_score}%</strong></span>
          </div>
          <div class="score-bar" style="margin-bottom:1.2rem">
            <div class="score-fill" style="width:{ov_score}%;background:{'#3fb950' if ov_score>=80 else '#d29922' if ov_score>=50 else '#f85149'}"></div>
          </div>"""

            for proto, key in [("SPF", "spf"), ("DKIM", "dkim"), ("DMARC", "dmarc")]:
                d = email_sec.get(key, {})
                issues_html = "".join(
                    f'<div style="color:var(--yellow);font-size:.75rem;margin-top:.2rem">⚠ {_e(i)}</div>'
                    for i in d.get("issues", [])
                )
                record_txt = ""
                if d.get("record"):
                    record_txt = f'<code style="font-size:.7rem;word-break:break-all">{_e(str(d["record"])[:160])}</code>'
                elif d.get("found_selectors"):
                    record_txt = f'Selectors: {_e(", ".join(d["found_selectors"]))}'

                email_sec_html += f"""
          <div style="background:var(--bg3);border:1px solid var(--border);border-radius:6px;padding:.8rem;margin-bottom:.5rem">
            <div style="display:flex;align-items:center;gap:.6rem;margin-bottom:.4rem">
              <strong style="color:var(--accent)">{proto}</strong>
              {_grade_badge(d.get("grade","FAIL"))}
            </div>
            {record_txt}
            {issues_html}
          </div>"""

            mta  = email_sec.get("mta_sts", {})
            bimi = email_sec.get("bimi", {})
            email_sec_html += f"""
          <div style="display:flex;gap:.5rem;margin-top:.5rem">
            MTA-STS: {_badge('green','PRESENT') if mta.get('present') else _badge('gray','ABSENT')}
            &nbsp;BIMI: {_badge('green','PRESENT') if bimi.get('present') else _badge('gray','ABSENT')}
          </div>"""
        else:
            email_sec_html = (
                '<div style="color:var(--text2)">Modul Email Security tidak dijalankan. '
                'Gunakan flag <code>-d</code>.</div>'
            )

        # ── Username rows ─────────────────────────────────────
        uname_rows = ""
        for plat, d in user_res.items():
            st   = d.get("status", "UNKNOWN")
            bc   = {"FOUND":"green","NOT_FOUND":"gray","TIMEOUT":"yellow","ERROR":"red"}.get(st,"gray")
            url  = d.get("url","")
            link = (
                f'<a href="{_e(url)}" target="_blank" rel="noopener noreferrer" '
                f'style="color:var(--blue);font-size:.75rem">'
                f'{_e(url[:55])}{"…" if len(url)>55 else ""}</a>'
                if st == "FOUND" else
                f'<span style="color:var(--text2);font-size:.75rem">'
                f'{_e(d.get("note") or d.get("error") or "—")}</span>'
            )
            uname_rows += f"<tr><td>{plat}</td><td>{_badge(bc, st)}</td><td>{link}</td></tr>\n"

        # ── Breach section HTML ───────────────────────────────
        breach_html = ""
        for email, d in breach_res.items():
            if d.get("error"):
                breach_html += f'<div style="color:var(--yellow);margin-bottom:.5rem">⚠ {_e(email)}: {_e(d["error"])}</div>'
                continue
            st_badge    = _badge("red","⚠ BREACHED") if d.get("breached") else _badge("green","✓ CLEAN")
            breach_html += '<div style="margin-bottom:1.2rem;padding-bottom:1.2rem;border-bottom:1px solid var(--border)">'
            breach_html += f'<div style="display:flex;align-items:center;gap:.8rem;margin-bottom:.6rem"><strong>{_e(email)}</strong>{st_badge}'
            if d.get("breached"):
                breach_html += _badge("red", f'{d["breach_count"]} breach')
            if d.get("pastes"):
                breach_html += _badge("yellow", f'{len(d["pastes"])} paste')
            breach_html += "</div>"
            for b in d.get("breaches", []):
                dcs = "".join(_badge("yellow", dc) + " " for dc in b.get("data_classes",[])[:6])
                breach_html += (
                    f'<div style="background:var(--bg3);border:1px solid rgba(248,81,73,.3);'
                    f'border-radius:6px;padding:.8rem;margin-bottom:.5rem">'
                    f'<div style="color:var(--red);font-weight:bold;margin-bottom:.3rem">'
                    f'🔐 {_e(b["name"])} ({_e(b.get("breach_date") or "?")})</div>'
                    f'<div style="font-size:.78rem;color:var(--text2)">Domain: {_e(b.get("domain") or "—")} | Akun: {b.get("pwn_count") or 0:,}</div>'
                    f'<div style="margin-top:.4rem">{dcs}</div></div>'
                )
            breach_html += "</div>"
        if not breach_html:
            breach_html = (
                '<div style="color:var(--text2)">Tidak ada email yang dicek. '
                'Gunakan flag <code>-e</code>.</div>'
            )

        # ── IP Reputation HTML ────────────────────────────────
        ip_html = ""
        if ip_res:
            for ip, d in ip_res.items():
                score    = d.get("overall_risk_score", 0)
                rlevel   = d.get("risk_level", "UNKNOWN")
                flags    = d.get("flags", [])
                abuse    = d.get("abuseipdb", {})
                otx      = d.get("alienvault_otx", {})
                geo      = d.get("geolocation", {})
                bar_color = {
                    "LOW":"#3fb950","MEDIUM":"#d29922",
                    "HIGH":"#f85149","CRITICAL":"#f85149"
                }.get(rlevel,"#8b949e")
                badge_cls = {
                    "LOW":"green","MEDIUM":"yellow","HIGH":"red","CRITICAL":"red"
                }.get(rlevel,"gray")
                flag_badges = "".join(_badge("red", fl) + " " for fl in flags)
                mal      = _e(", ".join(otx.get("malware_families", [])) or "—")
                tags_str = _e(", ".join(otx.get("tags", [])[:6]) or "—")

                def _v(d_: dict, key: str) -> str:
                    val = d_.get(key)
                    return _e(val) if val not in (None, "") else "—"
                ip_html += f"""
      <div style="background:var(--bg3);border:1px solid var(--border);border-radius:8px;padding:1rem;margin-bottom:1rem">
        <div style="display:flex;align-items:center;gap:1rem;flex-wrap:wrap;margin-bottom:.8rem">
          <strong style="color:var(--blue);font-size:1rem">{_e(ip)}</strong>
          {_badge(badge_cls, f"⚠ {rlevel}")}
          <span style="color:var(--text2);font-size:.8rem">Risk Score: <strong style="color:{bar_color}">{score}/100</strong></span>
          {flag_badges}
        </div>
        <div style="background:var(--bg);border-radius:20px;height:6px;margin-bottom:1rem">
          <div style="width:{score}%;height:100%;border-radius:20px;background:{bar_color}"></div>
        </div>
        <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:.8rem;font-size:.8rem">
          <div>
            <div style="color:var(--text2);margin-bottom:.3rem">📍 Geolokasi</div>
            <div>{_v(geo, 'country')} / {_v(geo, 'city')}</div>
            <div style="color:var(--text2)">{_v(geo, 'isp')}</div>
            <div style="color:var(--text2)">{_v(geo, 'asn')}</div>
          </div>
          <div>
            <div style="color:var(--text2);margin-bottom:.3rem">🛡 AbuseIPDB</div>
            <div>Score: <strong style="color:{bar_color}">{_v(abuse, 'abuse_score')}</strong></div>
            <div>Reports: {_v(abuse, 'total_reports')}</div>
            <div style="color:var(--text2)">{_e(abuse.get('error') or '')}</div>
          </div>
          <div>
            <div style="color:var(--text2);margin-bottom:.3rem">🔴 AlienVault OTX</div>
            <div>Pulses: <strong>{_v(otx, 'pulse_count')}</strong></div>
            <div style="color:var(--text2)">{_e(otx.get('error') or '')}</div>
            <div>Malware: <span style="color:var(--red)">{mal}</span></div>
            <div style="color:var(--text2)">Tags: {tags_str}</div>
          </div>
        </div>
      </div>"""
        else:
            ip_html = (
                '<div style="color:var(--text2)">Tidak ada IP yang dicek. '
                'Gunakan flag <code>-i</code>.</div>'
            )

        # ── Exif section HTML ─────────────────────────────────
        exif_html = ""
        if exif_res and not exif_res.get("error"):
            filtered_meta = exif_res.get("filtered", {})
            gps_coords    = exif_res.get("gps_coords")
            maps_link     = exif_res.get("maps_link")
            exif_html = '<table><thead><tr><th>Field</th><th>Value</th></tr></thead><tbody>'
            for key, label in ExifToolExtractor._PRIORITY_FIELDS:
                if key not in filtered_meta:
                    continue
                val = _e(filtered_meta[key])
                highlight = (
                    'style="color:var(--yellow);font-weight:bold"'
                    if key in ("GPSLatitude", "GPSLongitude", "GPSPosition")
                    else ''
                )
                exif_html += f'<tr {highlight}><td>{label}</td><td><code>{val}</code></td></tr>\n'
            exif_html += "</tbody></table>"
            if gps_coords and maps_link:
                lat, lon = gps_coords
                exif_html += f"""
          <div style="margin-top:1rem;padding:.8rem;background:rgba(210,153,34,.1);
               border:1px solid rgba(210,153,34,.4);border-radius:6px">
            <strong style="color:var(--yellow)">⚠ GPS LOCATION DETECTED</strong><br>
            <span style="font-size:.8rem">Lat: {lat} | Lon: {lon}</span><br>
            <a href="{_e(maps_link)}" target="_blank" rel="noopener noreferrer" style="color:var(--blue)">📍 Buka di Google Maps</a>
          </div>"""
        elif exif_res and exif_res.get("error"):
            exif_html = f'<div style="color:var(--red)">✗ Error: {_e(exif_res["error"])}</div>'
        else:
            exif_html = (
                '<div style="color:var(--text2)">Modul ExifTool tidak dijalankan. '
                'Gunakan flag <code>-x FILE/URL</code>.</div>'
            )

        # ── SSL badge / WAF header badge ──────────────────────
        ssl_info = recon.get("ssl_info", {})
        ssl_snippet = ""
        if ssl_info and not ssl_info.get("error"):
            ssl_snippet = (
                f'  <div><span class="ml">SSL: </span>'
                f'<span class="mv">{_e(ssl_info.get("issuer",{}).get("organizationName","?"))} — '
                f'exp {_e(ssl_info.get("not_after","?"))}</span></div>'
            )

        waf_badge_header = ""
        if waf_res and waf_res.get("waf_detected"):
            waf_badge_header = (
                f'  <div><span class="ml">WAF: </span>'
                f'<span class="mv">{_e(waf_res["primary_waf"])}</span></div>'
            )

        modules_run = _e(", ".join(r.get("modules_run", [])))
        target      = _e(r.get("target") or "N/A")
        age_years   = whois_res.get("age_years")
        age_card    = age_years if age_years is not None else "—"
        waf_card    = _e((waf_res.get("primary_waf") or "—")[:10]) if waf_res.get("waf_detected") else "NONE"
        status_code = web_tech.get("status_code")
        status_ok   = isinstance(status_code, int) and 200 <= status_code < 400

        page = f"""<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>freease Report — {target} — {self.ts}</title>
<style>
:root{{--bg:#0d1117;--bg2:#161b22;--bg3:#21262d;--border:#30363d;
  --green:#3fb950;--red:#f85149;--yellow:#d29922;--blue:#58a6ff;
  --text:#e6edf3;--text2:#8b949e;--accent:#00ff88;}}
*{{margin:0;padding:0;box-sizing:border-box}}
body{{background:var(--bg);color:var(--text);font-family:'Courier New',monospace}}
.hdr{{background:linear-gradient(135deg,#0d1117,#161b22,#0d2818);border-bottom:1px solid var(--accent);padding:2rem;position:relative;overflow:hidden}}
.hdr::before{{content:'';position:absolute;inset:0;background:repeating-linear-gradient(0deg,transparent,transparent 2px,rgba(0,255,136,.02) 2px,rgba(0,255,136,.02) 4px);pointer-events:none}}
.hdr-inner{{max-width:1200px;margin:0 auto}}
.logo{{font-size:2.2rem;font-weight:bold;color:var(--accent);letter-spacing:3px}}
.meta{{display:flex;gap:1.5rem;margin-top:.8rem;flex-wrap:wrap;font-size:.8rem}}
.ml{{color:var(--text2)}}.mv{{color:var(--blue)}}
.disc{{max-width:1200px;margin:.8rem auto;padding:.7rem 1.2rem;background:rgba(248,81,73,.1);border:1px solid rgba(248,81,73,.3);border-radius:6px;font-size:.76rem;color:#f85149}}
.con{{max-width:1200px;margin:0 auto;padding:2rem}}
.stats{{display:grid;grid-template-columns:repeat(auto-fit,minmax(155px,1fr));gap:1rem;margin-bottom:2rem}}
.sc{{background:var(--bg2);border:1px solid var(--border);border-radius:8px;padding:1.1rem;transition:border-color .2s}}
.sc:hover{{border-color:var(--accent)}}
.sn{{font-size:2rem;font-weight:bold}}.sl{{color:var(--text2);font-size:.78rem;margin-top:.2rem}}
.sg{{color:var(--green)}}.sr{{color:var(--red)}}.sy{{color:var(--yellow)}}.sb{{color:var(--blue)}}
.sec{{background:var(--bg2);border:1px solid var(--border);border-radius:8px;margin-bottom:1.2rem;overflow:hidden}}
.sh{{background:var(--bg3);border-bottom:1px solid var(--border);padding:.75rem 1.1rem;display:flex;align-items:center;gap:.5rem;cursor:pointer;user-select:none}}
.sh:hover{{background:rgba(255,255,255,.05)}}
.st{{font-size:.88rem;font-weight:bold;color:var(--accent);letter-spacing:1px}}
.sb2{{padding:1.1rem}}.ci{{margin-left:auto;color:var(--text2)}}
table{{width:100%;border-collapse:collapse;font-size:.81rem}}
th{{color:var(--text2);text-align:left;padding:.45rem .75rem;border-bottom:1px solid var(--border)}}
td{{padding:.45rem .75rem;border-bottom:1px solid rgba(48,54,61,.5);word-break:break-all}}
tr:last-child td{{border-bottom:none}}tr:hover td{{background:rgba(255,255,255,.02)}}
.badge{{display:inline-block;padding:.12rem .45rem;border-radius:4px;font-size:.68rem;font-weight:bold}}
.badge-green{{background:rgba(63,185,80,.2);color:var(--green);border:1px solid rgba(63,185,80,.3)}}
.badge-red{{background:rgba(248,81,73,.2);color:var(--red);border:1px solid rgba(248,81,73,.3)}}
.badge-yellow{{background:rgba(210,153,34,.2);color:var(--yellow);border:1px solid rgba(210,153,34,.3)}}
.badge-blue{{background:rgba(88,166,255,.2);color:var(--blue);border:1px solid rgba(88,166,255,.3)}}
.badge-gray{{background:rgba(139,148,158,.2);color:var(--text2);border:1px solid rgba(139,148,158,.3)}}
.subdomain-grid{{display:flex;flex-wrap:wrap;gap:.35rem}}
.subdomain-item{{background:var(--bg3);border:1px solid var(--border);border-radius:4px;padding:.25rem .55rem;font-size:.73rem;color:var(--blue)}}
.score-bar{{background:var(--bg3);border-radius:20px;height:7px;overflow:hidden;margin-top:.4rem}}
.score-fill{{height:100%;border-radius:20px}}
.header-check{{display:flex;align-items:center;gap:.5rem;padding:.28rem 0;border-bottom:1px solid rgba(48,54,61,.5);font-size:.8rem}}
.header-check:last-child{{border-bottom:none}}
pre{{background:var(--bg3);border:1px solid var(--border);border-radius:6px;padding:1rem;overflow-x:auto;font-size:.73rem;max-height:400px}}
footer{{text-align:center;padding:2rem;color:var(--text2);font-size:.73rem;border-top:1px solid var(--border)}}
a{{color:var(--blue)}}
</style>
</head>
<body>

<div class="hdr">
  <div class="hdr-inner">
    <div class="logo">freease</div>
    <div style="color:var(--text2);font-size:.83rem;margin-top:.2rem">Attack Surface Management Tool — by Kodok-Kejepit v{VERSION}</div>
    <div class="meta">
      <div><span class="ml">TARGET: </span><span class="mv">{target}</span></div>
      <div><span class="ml">SCAN: </span><span class="mv">{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</span></div>
      <div><span class="ml">MODULES: </span><span class="mv">{modules_run}</span></div>
      {ssl_snippet}
      {waf_badge_header}
    </div>
  </div>
</div>

<div style="max-width:1200px;margin:0 auto;padding:0 2rem">
  <div class="disc">⚠️ <strong>DISCLAIMER:</strong> Laporan ini dibuat untuk riset akademis &amp; keamanan defensif. Data bersumber dari informasi publik. Penggunaan ilegal merupakan tanggung jawab pengguna.</div>
</div>

<div class="con">

  <!-- STAT CARDS -->
  <div class="stats">
    <div class="sc"><div class="sn {risk_cls}">{_e(risk_level)}</div><div class="sl">⚡ Overall Risk ({summary.get('risk_score', 0)}/100)</div></div>
    <div class="sc"{_hide('network_recon')}><div class="sn sb">{len(subs)}</div><div class="sl">📡 Subdomain</div></div>
    <div class="sc"{_hide('breach_check')}><div class="sn {'sr' if breached_em else 'sg'}">{len(breached_em)}</div><div class="sl">🔓 Email Bocor</div></div>
    <div class="sc"{_hide('username_check')}><div class="sn sy">{len(found_u)}</div><div class="sl">👤 Username Aktif</div></div>
    <div class="sc"{_hide('network_recon')}><div class="sn {'sg' if sec_score>=60 else 'sy' if sec_score>=30 else 'sr'}">{sec_score}%</div><div class="sl">🛡 Sec Headers</div></div>
    <div class="sc"{_hide('ip_reputation')}><div class="sn {'sr' if risky_ips else 'sg'}">{len(risky_ips)}</div><div class="sl">🌐 IP Berisiko</div></div>
    <div class="sc"{_hide('port_scan')}><div class="sn {'sy' if open_ports else 'sg'}">{len(open_ports)}</div><div class="sl">🔌 Port Terbuka</div></div>
    <div class="sc"{_hide('waf_detection')}><div class="sn {'sg' if waf_res.get('waf_detected') else 'sy'}" style="font-size:1.3rem">{waf_card}</div><div class="sl">🧱 WAF</div></div>
    <div class="sc"{_hide('whois')}><div class="sn sb">{age_card}</div><div class="sl">📅 Domain Age (yr)</div></div>
  </div>

  <!-- DNS -->
  <div class="sec"{_hide('network_recon')}>
    <div class="sh" onclick="tog('dns','di')"><span>📋</span><span class="st">DNS RECORDS</span><span class="ci" id="di">▼</span></div>
    <div class="sb2" id="dns">
      <table><thead><tr><th>Type</th><th>Records</th></tr></thead><tbody>{dns_rows}</tbody></table>
    </div>
  </div>

  <!-- SUBDOMAINS -->
  <div class="sec"{_hide('network_recon')}>
    <div class="sh" onclick="tog('sub','si')"><span>🌐</span><span class="st">SUBDOMAINS ({len(subs)} ditemukan)</span><span class="ci" id="si">▼</span></div>
    <div class="sb2" id="sub"><div class="subdomain-grid">{sub_pills}</div></div>
  </div>

  <!-- WEB TECH -->
  <div class="sec"{_hide('network_recon')}>
    <div class="sh" onclick="tog('tech','ti')"><span>⚙️</span><span class="st">WEB TECHNOLOGIES &amp; SECURITY HEADERS</span><span class="ci" id="ti">▼</span></div>
    <div class="sb2" id="tech">
      <table style="margin-bottom:1.2rem"><thead><tr><th>Property</th><th>Value</th></tr></thead><tbody>
        <tr><td>Status</td><td>{_badge('green' if status_ok else 'red', status_code if status_code is not None else '—')}</td></tr>
        <tr><td>Server</td><td><code>{_e(web_tech.get('server') or '—')}</code></td></tr>
        <tr><td>X-Powered-By</td><td><code>{_e(web_tech.get('powered_by') or '—')}</code></td></tr>
        <tr><td>CMS</td><td>{_badge('blue', web_tech.get('cms') or '—')}</td></tr>
        <tr><td>CDN</td><td>{_badge('blue', web_tech.get('cdn') or '—')}</td></tr>
      </tbody></table>
      <div style="font-size:.83rem;color:var(--text2);margin-bottom:.5rem">Security Headers Score: <strong style="color:var(--accent)">{sec_score}%</strong></div>
      <div class="score-bar"><div class="score-fill" style="width:{sec_score}%;background:{'#3fb950' if sec_score>=60 else '#d29922' if sec_score>=30 else '#f85149'}"></div></div>
      <div style="margin-top:.8rem">{sec_rows}</div>
    </div>
  </div>

  <!-- PORT SCAN -->
  <div class="sec"{_hide('port_scan')}>
    <div class="sh" onclick="tog('ports','poi')"><span>🔌</span><span class="st">PORT SCANNER ({len(open_ports)} port terbuka dari {port_res.get('ports_scanned', 0)} dipindai)</span><span class="ci" id="poi">▼</span></div>
    <div class="sb2" id="ports">
      <table><thead><tr><th>Port</th><th>State</th><th>Service</th><th>Banner</th></tr></thead>
      <tbody>{port_rows}</tbody></table>
      <div style="color:var(--text2);font-size:.75rem;margin-top:.5rem">Elapsed: {port_res.get('elapsed_sec','—')}s</div>
    </div>
  </div>

  <!-- WAF DETECTION -->
  <div class="sec"{_hide('waf_detection')}>
    <div class="sh" onclick="tog('waf','wi')"><span>🧱</span><span class="st">WAF DETECTION</span><span class="ci" id="wi">▼</span></div>
    <div class="sb2" id="waf">{waf_html}</div>
  </div>

  <!-- WHOIS -->
  <div class="sec"{_hide('whois')}>
    <div class="sh" onclick="tog('who','whi')"><span>📅</span><span class="st">WHOIS &amp; DOMAIN AGE</span><span class="ci" id="whi">▼</span></div>
    <div class="sb2" id="who">{whois_html}</div>
  </div>

  <!-- EMAIL SECURITY -->
  <div class="sec"{_hide('email_security')}>
    <div class="sh" onclick="tog('eml','emi')"><span>📧</span><span class="st">EMAIL SECURITY (SPF / DKIM / DMARC)</span><span class="ci" id="emi">▼</span></div>
    <div class="sb2" id="eml">{email_sec_html}</div>
  </div>

  <!-- IP REPUTATION -->
  <div class="sec"{_hide('ip_reputation')}>
    <div class="sh" onclick="tog('ipr','ipi')"><span>🌐</span><span class="st">IP REPUTATION ({len(ip_res)} IP diperiksa)</span><span class="ci" id="ipi">▼</span></div>
    <div class="sb2" id="ipr">{ip_html}</div>
  </div>

  <!-- USERNAME -->
  <div class="sec"{_hide('username_check')}>
    <div class="sh" onclick="tog('usr','ui')"><span>👤</span><span class="st">USERNAME CHECKER ({len(found_u)} ditemukan)</span><span class="ci" id="ui">▼</span></div>
    <div class="sb2" id="usr">
      <table><thead><tr><th>Platform</th><th>Status</th><th>URL</th></tr></thead><tbody>{uname_rows}</tbody></table>
    </div>
  </div>

  <!-- BREACH -->
  <div class="sec"{_hide('breach_check')}>
    <div class="sh" onclick="tog('brc','bi')"><span>🔓</span><span class="st">DATA BREACH CHECK ({len(breached_em)} email bocor)</span><span class="ci" id="bi">▼</span></div>
    <div class="sb2" id="brc">{breach_html}</div>
  </div>

  <!-- EXIFTOOL -->
  <div class="sec"{_hide('exif_metadata')}>
    <div class="sh" onclick="tog('exif','exi')"><span>🗂</span><span class="st">EXIFTOOL METADATA EXTRACTOR</span><span class="ci" id="exi">▼</span></div>
    <div class="sb2" id="exif">{exif_html}</div>
  </div>

  <!-- RAW JSON -->
  <div class="sec">
    <div class="sh" onclick="tog('raw','ri')"><span>📄</span><span class="st">RAW JSON</span><span class="ci" id="ri">▶</span></div>
    <div class="sb2" id="raw" style="display:none"><pre>{raw_json_html}</pre></div>
  </div>

</div>

<footer>
  Generated by <strong>freease v{VERSION}</strong> — by Kodok-Kejepit<br>
  9 Modules: NetworkRecon · PortScan · WAF · WHOIS · EmailSec · Username · Breach · IPReputation · ExifTool<br>
  Hanya untuk riset akademis dan penggunaan defensif. Data bersumber dari informasi publik.
</footer>

<script>
function tog(bodyId,iconId){{
  var b=document.getElementById(bodyId),ic=document.getElementById(iconId);
  if(b.style.display==='none'){{b.style.display='block';ic.textContent='▼'}}
  else{{b.style.display='none';ic.textContent='▶'}}
}}
</script>
</body>
</html>"""

        with open(fp, "w", encoding="utf-8") as f:
            f.write(page)
        return str(fp)


# ══════════════════════════════════════════════════════════════
#  MAIN ENGINE  (Orchestrator — Class-based)
# ══════════════════════════════════════════════════════════════

class FreeaseEngine:
    """
    Orchestrator utama freease.
    Lima modul domain (recon, port scan, WAF, WHOIS, email security) dijalankan
    paralel; modul lain berurutan. Hasil dikumpulkan di self.all_results.
    """

    DOMAIN_MODULES = [
        ("network_recon",  "MODULE 1 — NETWORK RECONNAISSANCE", "green"),
        ("port_scan",      "MODULE 2 — PORT SCANNER",           "cyan"),
        ("waf_detection",  "MODULE 3 — WAF DETECTION",          "magenta"),
        ("whois",          "MODULE 4 — WHOIS & DOMAIN AGE",     "yellow"),
        ("email_security", "MODULE 5 — EMAIL SECURITY ANALYZER", "blue"),
    ]

    def __init__(self, args):
        self.args = args
        targets = [args.domain, args.username, args.emails, args.ips, args.exif_target]
        self.all_results: dict = {
            "target":         next((t for t in targets if t), None),
            "scan_time":      datetime.now().isoformat(),
            "modules_run":    [],
            "network_recon":  {},
            "port_scan":      {},
            "waf_detection":  {},
            "whois":          {},
            "email_security": {},
            "username_check": {},
            "breach_check":   {},
            "ip_reputation":  {},
            "exif_metadata":  {},
            "intelligence_summary": {},
        }

    # ══════════════════════════════════════════════════════════
    #  RUN — Entry point async
    # ══════════════════════════════════════════════════════════

    async def run(self):
        connector = aiohttp.TCPConnector(limit=60, ttl_dns_cache=300)
        async with aiohttp.ClientSession(
            headers={"User-Agent": USER_AGENT}, connector=connector
        ) as session:

            # ── MODUL 1–5: semua modul berbasis domain (paralel) ──
            if self.args.domain:
                await self._run_domain_modules(session)

            # ── MODUL 6: USERNAME CHECKER ─────────────────────
            if self.args.username:
                await self._run_username_check(session)

            # ── MODUL 7: BREACH CHECKER ───────────────────────
            if self.args.emails:
                await self._run_breach_check(session)

            # ── MODUL 8: IP REPUTATION ────────────────────────
            if self.args.ips:
                await self._run_ip_reputation(session)

            # ── MODUL 9: EXIFTOOL ─────────────────────────────
            if self.args.exif_target:
                await self._run_exif()

        # ── INTELLIGENCE SUMMARY ──────────────────────────────
        self.all_results["intelligence_summary"] = self.generate_summary()
        self._print_summary()

        # ── EXPORT LAPORAN ────────────────────────────────────
        if self.args.output:
            self._export_reports()

    # ══════════════════════════════════════════════════════════
    #  MODULE RUNNERS
    # ══════════════════════════════════════════════════════════

    @staticmethod
    def _spinner() -> Progress:
        return Progress(SpinnerColumn(), TextColumn("{task.description}"),
                        console=console, transient=True)

    @staticmethod
    async def _port_scan_job(domain: str) -> dict:
        try:
            target_ip = await asyncio.to_thread(socket.gethostbyname, domain)
        except OSError:
            raise RuntimeError(f"Tidak bisa resolve {domain} ke alamat IP")
        res = await PortScanner(target_ip).scan()
        res["domain"] = domain
        return res

    async def _run_domain_modules(self, session: aiohttp.ClientSession):
        domain = self.args.domain
        jobs = {
            "network_recon":  NetworkRecon(domain).run_all(session),
            "port_scan":      self._port_scan_job(domain),
            "waf_detection":  WAFDetector(domain).detect(session),
            "whois":          WhoisChecker(domain).lookup(session),
            "email_security": EmailSecurityChecker(domain).check_all(),
        }
        if self.args.skip_portscan:
            jobs.pop("port_scan").close()

        console.print(Panel(
            f"[bold green]🌐 Domain:[/bold green] [cyan]{escape(domain)}[/cyan]\n"
            f"[dim]{len(jobs)} modul berjalan paralel: DNS · crt.sh · Web Tech · SSL"
            f"{'' if self.args.skip_portscan else ' · Port Scan'} · WAF · WHOIS · "
            f"SPF/DKIM/DMARC[/dim]",
            title="[bold]DOMAIN RECON[/bold]", border_style="green"
        ))

        results: dict = {}
        errors: dict  = {}
        with Progress(SpinnerColumn(), TextColumn("{task.description}"), BarColumn(),
                      TextColumn("{task.completed}/{task.total}"),
                      console=console, transient=True) as prog:
            t = prog.add_task("[cyan]Scanning…", total=len(jobs))

            async def _tracked(name: str, coro):
                try:
                    results[name] = await coro
                except Exception as e:
                    errors[name] = str(e) or e.__class__.__name__
                prog.advance(t)
                prog.update(t, description=f"[cyan]Selesai: {name}")

            await asyncio.gather(*[_tracked(n, c) for n, c in jobs.items()])

        printers = {
            "network_recon":  self._print_recon,
            "port_scan":      self._print_port_scan,
            "waf_detection":  self._print_waf,
            "whois":          self._print_whois,
            "email_security": self._print_email_security,
        }
        for name, title, color in self.DOMAIN_MODULES:
            if name not in jobs:
                continue
            console.print()
            console.rule(f"[bold {color}]{title}[/bold {color}]", style=color)
            if name in errors:
                console.print(f"  [red]✗ Modul gagal: {escape(errors[name])}[/red]")
                continue
            self.all_results["modules_run"].append(name)
            self.all_results[name] = results[name]
            printers[name](results[name])

    async def _run_username_check(self, session: aiohttp.ClientSession):
        self.all_results["modules_run"].append("username_check")
        console.print(Panel(
            f"[bold green]👤 Username:[/bold green] [cyan]{escape(self.args.username)}[/cyan]",
            title="[bold]MODULE 6 — USERNAME CHECKER[/bold]",
            border_style="blue"
        ))
        checker = UsernameChecker(self.args.username)
        with self._spinner() as prog:
            prog.add_task(f"[cyan]Memeriksa {len(USERNAME_PLATFORMS)} platform…", total=None)
            res = await checker.check_all_platforms(session)
        self.all_results["username_check"] = res
        self._print_username(res)

    async def _run_breach_check(self, session: aiohttp.ClientSession):
        self.all_results["modules_run"].append("breach_check")
        emails = [e.strip() for e in self.args.emails.split(",") if e.strip()]
        console.print(Panel(
            f"[bold green]📧 Emails:[/bold green] [cyan]{escape(', '.join(emails))}[/cyan]",
            title="[bold]MODULE 7 — DATA BREACH CHECKER[/bold]",
            border_style="red"
        ))
        bc = BreachChecker(hibp_api_key=self.args.hibp_key)
        with self._spinner() as prog:
            prog.add_task(
                f"[cyan]Memeriksa {len(emails)} email (rate-limited {bc.RATE_DELAY}s/req)…",
                total=None
            )
            res = await bc.check_emails_bulk(session, emails)
        self.all_results["breach_check"] = res
        self._print_breach(res)

    async def _run_ip_reputation(self, session: aiohttp.ClientSession):
        self.all_results["modules_run"].append("ip_reputation")
        targets = [x.strip() for x in self.args.ips.split(",") if x.strip()]
        console.print(Panel(
            f"[bold green]🌐 Targets:[/bold green] [cyan]{escape(', '.join(targets))}[/cyan]\n"
            f"[dim]Sumber: AbuseIPDB · AlienVault OTX · ip-api.com[/dim]",
            title="[bold]MODULE 8 — IP REPUTATION CHECKER[/bold]",
            border_style="yellow"
        ))
        ipr = IPReputationChecker(abuseipdb_key=self.args.abuseipdb_key)
        with self._spinner() as prog:
            prog.add_task(f"[cyan]Query 3 feed untuk {len(targets)} target…", total=None)
            res = await ipr.check_ips_bulk(session, targets)
        self.all_results["ip_reputation"] = res
        self._print_ip_reputation(res)

    async def _run_exif(self):
        """Jalankan ExifToolExtractor di thread agar tidak block event loop."""
        self.all_results["modules_run"].append("exif_metadata")
        console.print(Panel(
            f"[bold green]🗂 Target:[/bold green] [cyan]{escape(self.args.exif_target)}[/cyan]",
            title="[bold]MODULE 9 — EXIFTOOL METADATA EXTRACTOR[/bold]",
            border_style="cyan"
        ))
        extractor = ExifToolExtractor(self.args.exif_target)
        with self._spinner() as prog:
            prog.add_task("[cyan]Extracting metadata via ExifTool…", total=None)
            exif_res = await asyncio.to_thread(extractor.run)
        extractor.print_results(console)
        self.all_results["exif_metadata"] = exif_res

    # ══════════════════════════════════════════════════════════
    #  INTELLIGENCE SUMMARY
    # ══════════════════════════════════════════════════════════

    def generate_summary(self) -> dict:
        """
        Rangkum semua temuan scan menjadi satu dict ringkasan.
        Hanya modul yang benar-benar dijalankan yang ikut dihitung ke risk score —
        modul yang tidak jalan tidak boleh dianggap "temuan buruk".
        """
        r   = self.all_results
        ran = set(r["modules_run"])

        # Network Recon
        recon      = r.get("network_recon", {})
        subs       = recon.get("subdomains", [])
        web_tech   = recon.get("web_tech", {})
        sec_hdrs   = web_tech.get("security_headers", {})
        # Header hanya bisa dinilai kalau web server-nya memang merespons
        web_up     = web_tech.get("status_code") is not None
        missing_hdrs = [h for h, v in sec_hdrs.items() if not v] if web_up else []
        ssl_info   = recon.get("ssl_info", {})
        ssl_ok     = (
            bool(ssl_info and not ssl_info.get("error"))
            if "network_recon" in ran else None
        )

        # Port Scan
        port_res   = r.get("port_scan", {})
        open_ports = port_res.get("open_ports", [])
        sensitive_open = [
            p["port"] for p in open_ports
            if p["port"] in PortScanner.SENSITIVE_PORTS
        ]

        # WAF
        waf_res    = r.get("waf_detection", {})
        waf_name   = waf_res.get("primary_waf") if waf_res.get("waf_detected") else None

        # WHOIS
        whois_res  = r.get("whois", {})
        domain_age = whois_res.get("age_years")

        # Email Security
        email_sec  = r.get("email_security", {})
        email_ovrl = email_sec.get("overall")
        email_score = email_sec.get("overall_score")

        # Username
        user_res   = r.get("username_check", {})
        found_usernames = [p for p, d in user_res.items() if d.get("status") == "FOUND"]

        # Breach
        breach_res   = r.get("breach_check", {})
        breached_emails = [
            {"email": e, "count": d.get("breach_count", 0)}
            for e, d in breach_res.items() if d.get("breached")
        ]

        # IP Reputation
        ip_res    = r.get("ip_reputation", {})
        risky_ips = [
            {"ip": ip, "risk": d.get("risk_level"), "score": d.get("overall_risk_score", 0),
             "flags": d.get("flags", [])}
            for ip, d in ip_res.items()
            if d.get("overall_risk_score", 0) > 20
        ]

        # Exif / GPS
        exif_res  = r.get("exif_metadata", {})
        gps_found = bool(exif_res.get("gps_coords"))
        gps_coords = exif_res.get("gps_coords")
        maps_link  = exif_res.get("maps_link")

        # Overall risk level
        risk_score = 0
        if breached_emails:      risk_score += 30
        if risky_ips:            risk_score += 25
        if sensitive_open:       risk_score += 20
        if "waf_detection" in ran and web_up and not waf_name:
            risk_score += 10
        if missing_hdrs:         risk_score += min(10, len(missing_hdrs) * 2)
        if "email_security" in ran and email_score < 50:
            risk_score += 10
        if ssl_ok is False and web_up:
            risk_score += 10
        if gps_found:            risk_score += 5

        if risk_score >= 60:     overall_risk = "CRITICAL"
        elif risk_score >= 40:   overall_risk = "HIGH"
        elif risk_score >= 20:   overall_risk = "MEDIUM"
        else:                    overall_risk = "LOW"

        return {
            "overall_risk":      overall_risk,
            "risk_score":        min(100, risk_score),
            "subdomain_count":   len(subs),
            "open_port_count":   len(open_ports),
            "sensitive_ports":   sensitive_open,
            "missing_sec_headers": missing_hdrs,
            "ssl_valid":         ssl_ok,
            "ssl_error":         ssl_info.get("error"),
            "waf_detected":      waf_name,
            "domain_age_years":  domain_age,
            "email_sec_grade":   email_ovrl,
            "email_sec_score":   email_score,
            "found_usernames":   found_usernames,
            "breached_emails":   breached_emails,
            "risky_ips":         risky_ips,
            "gps_found":         gps_found,
            "gps_coords":        gps_coords,
            "maps_link":         maps_link,
        }

    def _print_summary(self):
        """Cetak tabel Intelligence Summary ke terminal."""
        s = self.all_results.get("intelligence_summary", {})
        if not s or not self.all_results["modules_run"]:
            return

        risk    = s.get("overall_risk", "UNKNOWN")
        score   = s.get("risk_score", 0)
        risk_color = {
            "LOW": "bold green", "MEDIUM": "bold yellow",
            "HIGH": "bold red",  "CRITICAL": "bold red",
        }.get(risk, "dim")

        console.print()
        console.print(Panel(
            f"[{risk_color}]⚡ OVERALL RISK: {risk}  (Score: {score}/100)[/{risk_color}]",
            title="[bold white]🧠 INTELLIGENCE SUMMARY[/bold white]",
            border_style="bright_cyan"
        ))

        t = Table(box=box.ROUNDED, style="dim", show_header=True, header_style="bold cyan")
        t.add_column("Kategori",  style="bold cyan",  width=28)
        t.add_column("Temuan",    style="bold white",  width=18)
        t.add_column("Detail",    style="dim",          width=40)

        def _row(cat, val, detail, val_color="white"):
            t.add_row(cat, f"[{val_color}]{val}[/{val_color}]", detail)

        ran = set(self.all_results["modules_run"])

        if "network_recon" in ran:
            _row("📡 Subdomain", str(s.get("subdomain_count", 0)), "via Certificate Transparency")
            _row("🔒 SSL/TLS", "✓ Valid" if s.get("ssl_valid") else "✗ Error",
                 escape(str(s.get("ssl_error") or ""))[:40],
                 "green" if s.get("ssl_valid") else "red")
            _row("🛡 Missing Sec Headers", str(len(s.get("missing_sec_headers", []))),
                 ", ".join(s.get("missing_sec_headers", [])[:3]) or "Semua hadir",
                 "red" if s.get("missing_sec_headers") else "green")
        if "port_scan" in ran:
            _row("🔌 Port Terbuka", str(s.get("open_port_count", 0)),
                 f"Sensitif: {', '.join(str(p) for p in s.get('sensitive_ports', [])) or '—'}",
                 "red" if s.get("sensitive_ports") else "green")
        if "waf_detection" in ran:
            _row("🧱 WAF", escape(s.get("waf_detected") or "Tidak Terdeteksi"), "",
                 "green" if s.get("waf_detected") else "yellow")
        if "whois" in ran:
            age = s.get("domain_age_years")
            _row("📅 Domain Age", f"{age} tahun" if age is not None else "—", "")
        if "email_security" in ran:
            _row("📧 Email Security", s.get("email_sec_grade") or "—",
                 f"Score: {s.get('email_sec_score', 0)}%",
                 "green" if s.get("email_sec_grade") == "SECURE" else
                 "yellow" if s.get("email_sec_grade") == "PARTIAL" else "red")
        if "username_check" in ran:
            _row("👤 Username Aktif",
                 str(len(s.get("found_usernames", []))),
                 ", ".join(s.get("found_usernames", [])[:5]) or "—",
                 "yellow" if s.get("found_usernames") else "dim")
        if "breach_check" in ran:
            unchecked = sum(1 for d in self.all_results["breach_check"].values() if d.get("error"))
            _row("🔓 Email Bocor",
                 str(len(s.get("breached_emails", []))),
                 escape(", ".join(b["email"] for b in s.get("breached_emails", [])[:3]))
                 or (f"{unchecked} email gagal dicek" if unchecked else "—"),
                 "bold red" if s.get("breached_emails") else "yellow" if unchecked else "green")
        if "ip_reputation" in ran:
            _row("🌐 IP Berisiko",
                 str(len(s.get("risky_ips", []))),
                 ", ".join(f"{i['ip']}({i['risk']})" for i in s.get("risky_ips", [])[:3]) or "—",
                 "red" if s.get("risky_ips") else "green")
        if "exif_metadata" in ran:
            _row("📍 GPS Metadata",
                 "DITEMUKAN ⚠" if s.get("gps_found") else "Tidak ada",
                 str(s.get("gps_coords", "")) if s.get("gps_found") else "",
                 "bold yellow" if s.get("gps_found") else "dim")

        console.print(t)
        if s.get("maps_link"):
            console.print(
                f"  [bold yellow]📍 Maps Link:[/bold yellow] "
                f"[underline cyan]{s['maps_link']}[/underline cyan]"
            )
        console.print()

    # ══════════════════════════════════════════════════════════
    #  EXPORT
    # ══════════════════════════════════════════════════════════

    def _export_reports(self):
        reporter = ReportGenerator(self.all_results, self.args.output)
        jp = reporter.save_json()
        hp = reporter.save_html()
        console.print(Panel(
            f"[bold green]✓ JSON:[/bold green] [cyan]{jp}[/cyan]\n"
            f"[bold green]✓ HTML:[/bold green] [cyan]{hp}[/cyan]",
            title="[bold]📁 LAPORAN TERSIMPAN[/bold]",
            border_style="bright_black"
        ))

    # ══════════════════════════════════════════════════════════
    #  TERMINAL PRINT HELPERS
    # ══════════════════════════════════════════════════════════

    def _print_recon(self, r: dict):
        dns = r.get("dns_records", {})
        if dns:
            t = Table(title="DNS Records", box=box.ROUNDED, style="dim")
            t.add_column("Type",    style="bold cyan", width=8)
            t.add_column("Records", style="green")
            for rt, recs in dns.items():
                if recs:
                    t.add_row(rt, escape("\n".join(recs[:5])))
            console.print(t)
            if dns.get("A") == ["DOMAIN_NOT_FOUND"]:
                console.print("  [bold red]✗ Domain tidak ditemukan di DNS (NXDOMAIN)[/bold red]")

        subs = r.get("subdomains", [])
        if r.get("subdomain_error"):
            console.print(
                f"\n  [yellow]⚠ Subdomain: {escape(r['subdomain_error'])} — "
                f"daftar mungkin tidak lengkap[/yellow]"
            )
        if subs:
            console.print(f"\n[bold cyan]📡 Subdomain ({len(subs)}):[/bold cyan]")
            console.print("  ".join(f"[blue]{escape(s)}[/blue]" for s in subs[:30]))
            if len(subs) > 30:
                console.print(f"  [dim]… +{len(subs)-30} lainnya[/dim]")

        tech = r.get("web_tech", {})
        if tech and tech.get("status_code") is None:
            console.print("\n  [yellow]⚠ Web server tidak merespons di port 80/443[/yellow]")
        elif tech:
            t2 = Table(title="Web Tech & Security Headers", box=box.ROUNDED, style="dim")
            t2.add_column("Property", style="bold yellow")
            t2.add_column("Value")
            if tech.get("server"):     t2.add_row("Server",       escape(tech["server"]))
            if tech.get("powered_by"): t2.add_row("X-Powered-By", escape(tech["powered_by"]))
            if tech.get("redirect_url"): t2.add_row("Redirect →", escape(tech["redirect_url"][:80]))
            if tech.get("cms"):        t2.add_row("CMS",          f"[bold]{tech['cms']}[/bold]")
            if tech.get("cdn"):        t2.add_row("CDN",          f"[bold blue]{tech['cdn']}[/bold blue]")
            t2.add_row("Status Code", str(tech.get("status_code", "—")))
            for h, v in tech.get("security_headers", {}).items():
                icon = "[green]✓[/green]" if v else "[red]✗[/red]"
                t2.add_row(f"{icon} {h}", escape(str(v or "MISSING")[:80]))
            console.print(t2)

        ssl = r.get("ssl_info", {})
        if ssl and not ssl.get("error"):
            days = ssl.get("days_left")
            days_txt = ""
            if days is not None:
                dc = "red" if days < 14 else "yellow" if days < 30 else "green"
                days_txt = f"  [{dc}]({days} hari lagi)[/{dc}]"
            console.print(
                f"\n[bold]🔒 SSL[/bold]  Issuer: [cyan]{escape(ssl.get('issuer',{}).get('organizationName','?'))}[/cyan]"
                f"  Expires: [yellow]{ssl.get('not_after','?')}[/yellow]{days_txt}"
            )
        elif ssl.get("error"):
            console.print(f"\n[bold]🔒 SSL[/bold]  [red]✗ {escape(ssl['error'])}[/red]")

    def _print_port_scan(self, r: dict):
        open_ports = r.get("open_ports", [])
        elapsed    = r.get("elapsed_sec", "?")
        if not open_ports:
            console.print(
                f"  [green]✓ Tidak ada port terbuka dari {r.get('ports_scanned',0)} "
                f"port yang dipindai[/green]  [dim]({elapsed}s)[/dim]"
            )
            return
        t = Table(
            title=f"Open Ports — {r.get('host','?')}  [dim]({r.get('ports_scanned', 0)} port, {elapsed}s)[/dim]",
            box=box.ROUNDED, style="dim"
        )
        t.add_column("Port",    style="bold cyan",  no_wrap=True)
        t.add_column("Service", style="bold",        no_wrap=True)
        t.add_column("Banner",  style="dim green",   overflow="fold")
        t.add_column("Warning", style="bold red")
        for p in open_ports:
            t.add_row(
                str(p["port"]),
                p.get("service", "?"),
                escape((p.get("banner") or "")[:50]),
                p.get("warning", "") or "",
            )
        console.print(t)

    def _print_waf(self, r: dict):
        if r.get("waf_detected"):
            conf  = r.get("confidence", 0)
            color = "green" if conf >= 60 else "yellow"
            console.print(
                f"\n  [bold {color}]🧱 WAF TERDETEKSI: {r['primary_waf']}[/bold {color}]"
                f"  [dim]Confidence: {conf}%[/dim]"
            )
            t = Table(title="WAF Detection Details", box=box.ROUNDED, style="dim")
            t.add_column("WAF",               style="bold cyan")
            t.add_column("Confidence",        justify="right")
            t.add_column("Matched Indicators",style="dim")
            for det in r.get("all_detections", []):
                t.add_row(
                    det["name"],
                    f"[{'green' if det['confidence']>=60 else 'yellow'}]{det['confidence']}%",
                    escape(", ".join(det["matched"][:5])),
                )
            console.print(t)
        else:
            console.print("  [yellow]⚠ Tidak ada WAF terdeteksi atau WAF tidak teridentifikasi[/yellow]")
        raw = r.get("raw", {})
        console.print(
            f"  [dim]Status normal: {raw.get('status_normal','—')}  "
            f"| Status probe: {raw.get('status_probe','—')}  "
            f"| Server: {escape((raw.get('server_header') or '—')[:30])}[/dim]"
        )

    def _print_whois(self, r: dict):
        if r.get("error"):
            console.print(f"  [yellow]⚠ WHOIS: {escape(r['error'])}[/yellow]")
            return
        t = Table(title=f"WHOIS — {r.get('domain','?')}", box=box.ROUNDED, style="dim")
        t.add_column("Field",  style="bold yellow", width=20)
        t.add_column("Value",  style="green")
        t.add_row("Registrar",   escape(r.get("registrar") or "—"))
        t.add_row("Registered",  r.get("registered") or "—")
        t.add_row("Updated",     r.get("updated") or "—")
        t.add_row("Expires",     r.get("expires") or "—")
        age_str = (
            f"{r.get('age_years','?')} tahun  ({r.get('age_days','?')} hari)"
            if r.get("age_days") is not None else "—"
        )
        t.add_row("Domain Age",  f"[bold cyan]{age_str}[/bold cyan]")
        t.add_row("Status",      escape(", ".join(r.get("status", [])[:4])) or "—")
        t.add_row("Nameservers", escape("\n".join(r.get("nameservers", [])[:4])) or "—")
        console.print(t)

    def _print_email_security(self, r: dict):
        overall   = r.get("overall", "UNKNOWN")
        score     = r.get("overall_score", 0)
        color_map = {"SECURE":"green","PARTIAL":"yellow","VULNERABLE":"red"}
        color     = color_map.get(overall, "dim")
        console.print(
            f"\n  [bold {color}]📧 Email Security: {overall}[/bold {color}]"
            f"  [dim]Score: {score}%[/dim]"
        )
        t = Table(title=f"Email Security — {r.get('domain','?')}", box=box.ROUNDED, style="dim")
        t.add_column("Protocol", style="bold cyan", width=10)
        t.add_column("Status",   justify="center",   width=8)
        t.add_column("Detail",   style="dim",        width=55)

        for proto, key in [("SPF","spf"), ("DKIM","dkim"), ("DMARC","dmarc")]:
            d     = r.get(key, {})
            grade = d.get("grade", "FAIL")
            gc    = {"PASS":"bold green","WARN":"bold yellow","FAIL":"bold red"}.get(grade,"dim")
            detail_parts = []
            if d.get("record"):
                detail_parts.append(escape(str(d["record"])[:55]))
            elif d.get("found_selectors"):
                detail_parts.append(f"selectors: {', '.join(d['found_selectors'])}")
            if d.get("issues"):
                detail_parts.append(f"[yellow]⚠ {escape(d['issues'][0])}[/yellow]")
            t.add_row(proto, f"[{gc}]{grade}[/{gc}]", "\n".join(detail_parts) or "—")

        mta  = r.get("mta_sts", {})
        bimi = r.get("bimi", {})
        t.add_row("MTA-STS",
                  "[green]✓[/green]" if mta.get("present") else "[dim]—[/dim]",
                  escape((mta.get("record") or "")[:55]) or "Tidak ditemukan")
        t.add_row("BIMI",
                  "[green]✓[/green]" if bimi.get("present") else "[dim]—[/dim]",
                  escape((bimi.get("record") or "")[:55]) or "Tidak ditemukan")
        console.print(t)

    def _print_username(self, results: dict):
        t = Table(title="Username Check Results", box=box.ROUNDED, style="dim")
        t.add_column("Platform", style="bold")
        t.add_column("Status",   justify="center")
        t.add_column("URL / Catatan")
        for plat, d in results.items():
            st = d.get("status", "UNKNOWN")
            sc = {
                "FOUND":     "[bold green]FOUND[/bold green]",
                "NOT_FOUND": "[dim]NOT_FOUND[/dim]",
                "TIMEOUT":   "[yellow]TIMEOUT[/yellow]",
                "ERROR":     "[red]ERROR[/red]",
            }.get(st, "[dim]UNKNOWN[/dim]")
            if st == "FOUND":
                detail = escape(d.get("url", ""))
            else:
                detail = f"[dim]{escape((d.get('note') or d.get('error') or '')[:60])}[/dim]"
            t.add_row(plat, sc, detail)
        console.print(t)
        found = sum(1 for d in results.values() if d.get("status") == "FOUND")
        unknown = sum(1 for d in results.values() if d.get("status") == "UNKNOWN")
        console.print(
            f"  [dim]{found} ditemukan · {unknown} tidak bisa diverifikasi otomatis "
            f"dari {len(results)} platform[/dim]"
        )

    def _print_breach(self, results: dict):
        for email, d in results.items():
            if d.get("error"):
                console.print(f"[yellow]⚠ {escape(email)}: {escape(d['error'])}[/yellow]")
                continue
            if d.get("breached"):
                console.print(
                    f"\n[bold red]🔓 {email}[/bold red] — [red]{d['breach_count']} breach[/red]"
                )
                for b in d.get("breaches", [])[:5]:
                    console.print(
                        f"  [dim]•[/dim] [cyan]{escape(str(b['name']))}[/cyan] ({b['breach_date']}) — "
                        f"[yellow]{escape(', '.join(b['data_classes'][:4]))}[/yellow]"
                    )
                if d.get("pastes"):
                    console.print(f"  [dim]+ {len(d['pastes'])} paste(s)[/dim]")
            else:
                console.print(f"[green]✓ {email} — Bersih[/green]")

    def _print_ip_reputation(self, results: dict):
        if not results:
            console.print("  [yellow]⚠ Tidak ada IP publik yang bisa diperiksa[/yellow]")
            return
        t = Table(title="IP Reputation Results", box=box.ROUNDED, style="dim")
        t.add_column("IP",          style="bold cyan",  overflow="fold")
        t.add_column("Risk",        justify="center",   no_wrap=True)
        t.add_column("Score",       justify="center",   no_wrap=True)
        t.add_column("Country/ISP", style="dim")
        t.add_column("Abuse",       justify="center",   no_wrap=True)
        t.add_column("OTX",         justify="center",   no_wrap=True)
        t.add_column("Flags")
        color_map = {
            "LOW": "green", "MEDIUM": "yellow",
            "HIGH": "red",  "CRITICAL": "bold red",
        }
        for ip, d in results.items():
            rl    = d.get("risk_level", "UNKNOWN")
            score = d.get("overall_risk_score", 0)
            color = color_map.get(rl, "dim")
            geo   = d.get("geolocation", {})
            abuse = d.get("abuseipdb", {})
            otx   = d.get("alienvault_otx", {})
            flags = "  ".join(
                f"[red]{f}[/red]" for f in d.get("flags", [])
            ) or "[dim]—[/dim]"
            loc   = escape(
                f"{geo.get('country') or '?'} / "
                f"{(geo.get('isp') or geo.get('org') or '?')[:18]}"
            )
            ab_sc = (
                str(abuse.get("abuse_score", "—"))
                if abuse.get("available") else "[dim]n/a[/dim]"
            )
            otx_c = (
                str(otx.get("pulse_count", "—"))
                if otx.get("available") else "[dim]n/a[/dim]"
            )
            t.add_row(
                ip,
                f"[{color}]{rl}[/{color}]",
                f"[{color}]{score}[/{color}]",
                loc, ab_sc, otx_c, flags,
            )
        console.print(t)
        # Alasan feed "n/a" cukup ditampilkan sekali
        notes = {
            d.get(feed, {}).get("error")
            for d in results.values() for feed in ("abuseipdb", "alienvault_otx", "geolocation")
        } - {None}
        for note in sorted(notes):
            console.print(f"  [dim]ℹ {escape(note)}[/dim]")
        for ip, d in results.items():
            otx = d.get("alienvault_otx", {})
            if otx.get("malware_families"):
                console.print(
                    f"  [red]⚠ {ip} Malware families:[/red] "
                    f"[yellow]{escape(', '.join(otx['malware_families']))}[/yellow]"
                )
            abuse = d.get("abuseipdb", {})
            if abuse.get("last_reported"):
                console.print(
                    f"  [dim]{ip} Last reported:[/dim] [yellow]{abuse['last_reported']}[/yellow]"
                    f"  [dim]Usage:[/dim] {escape(abuse.get('usage_type') or '—')}"
                )


# ══════════════════════════════════════════════════════════════
#  CLI ARGUMENT PARSER
# ══════════════════════════════════════════════════════════════

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="freease",
        description=f"freease v{VERSION} — Attack Surface Management Tool by Kodok-Kejepit",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=r"""
Modul yang tersedia:
  -d   NetworkRecon + PortScan + WAF Detection + WHOIS + Email Security (5 modul paralel)
  -u   Username Checker (20 platform)
  -e   Data Breach Check via HaveIBeenPwned
  -i   IP Reputation (AbuseIPDB + AlienVault OTX + ip-api.com)
  -x   ExifTool Metadata Extractor (file lokal atau URL remote)
  -y   YouTube / YouTube Music downloader (video atau musik, 144p sampai 4K)
  -s   URL Safety Scanner (deteksi phishing / tautan berbahaya, analisis pasif)
  -p   Media Player (putar audio/video di terminal — Linux/Termux)

Contoh penggunaan:
  python freease.py -d example.com
  python freease.py -d example.com --skip-portscan -o ./laporan
  python freease.py -u johndoe
  python freease.py -e admin@example.com --hibp-key HIBP_KEY
  python freease.py -i 1.2.3.4,8.8.8.8 --abuseipdb-key KEY
  python freease.py -x /path/to/photo.jpg
  python freease.py -x https://example.com/image.jpg

  python freease.py -y "https://youtu.be/VIDEO_ID"                       (pilih interaktif)
  python freease.py -y "https://youtu.be/VIDEO_ID" --yt-mode video --yt-quality 4k
  python freease.py -y "https://music.youtube.com/watch?v=ID" --yt-mode musik
  python freease.py -y "https://youtube.com/playlist?list=ID" --yt-mode musik --yt-playlist

  python freease.py -s https://contoh-mencurigakan.tld/login              (cek satu URL)
  python freease.py -s http://bit.ly/xxxx --urlhaus                       (+ cek URLhaus)
  python freease.py --scan-list daftar_url.txt -o ./laporan               (batch dari file)
  python freease.py -s https://example.com --offline                     (tanpa jaringan)

  python freease.py -p lagu.mp3                                           (putar audio)
  python freease.py -p ./Music --shuffle --loop                          (playlist folder)
  python freease.py -p video.mkv --audio-only                            (video → audio saja)
  python freease.py -p "https://youtu.be/ID" --pl-backend mpv            (stream URL)

API Keys (semua gratis):
  HIBP        https://haveibeenpwned.com/API/Key       (breach check)
  AbuseIPDB   https://www.abuseipdb.com/register       (IP reputation, 1.000 req/hari)
  OTX         Tidak butuh key                           (publik)
  ip-api      Tidak butuh key                           (publik, 45 req/menit)
  RDAP/crt.sh Tidak butuh key                           (publik)
        """
    )
    p.add_argument("-V", "--version", action="version", version=f"freease {VERSION}")
    p.add_argument("-d", "--domain",      help="Domain untuk NetworkRecon + PortScan + WAF + WHOIS + EmailSec")
    p.add_argument("-u", "--username",    help="Username untuk dicek di 20 platform")
    p.add_argument("-e", "--emails",      help="Email(s) breach check (pisah koma)")
    p.add_argument("-i", "--ips",         help="IP/domain untuk IP reputation (pisah koma)")
    p.add_argument("-x", "--exif",
                   dest="exif_target",
                   metavar="FILE/URL",
                   help="ExifTool metadata extractor (file lokal atau URL remote)")
    p.add_argument("--hibp-key",          dest="hibp_key",      default=None,
                                          help="HaveIBeenPwned API key")
    p.add_argument("--abuseipdb-key",     dest="abuseipdb_key", default=None,
                                          help="AbuseIPDB API key (gratis)")
    p.add_argument("--skip-portscan",     dest="skip_portscan", action="store_true",
                                          help="Lewati modul Port Scanner")
    p.add_argument("-o", "--output",      default="./freease_output",
                                          help="Direktori output JSON & HTML (default: ./freease_output)")
    p.add_argument("--no-export",         action="store_true",
                                          help="Jangan export file laporan")
    add_youtube_args(p)
    add_player_args(p)
    add_scan_args(p)
    return p


# ══════════════════════════════════════════════════════════════
#  ENTRY POINT
# ══════════════════════════════════════════════════════════════

_DOMAIN_RE   = re.compile(r"^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
_USERNAME_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


def main():
    parser = build_parser()
    args   = parser.parse_args()

    scan_requested = any([args.domain, args.username, args.emails, args.ips, args.exif_target])
    url_scan_requested = bool(args.scan or args.scan_list)
    player_requested   = bool(args.play)

    _print_banner()

    if not scan_requested and not args.youtube and not url_scan_requested and not player_requested:
        console.print("[yellow]Gunakan minimal satu modul:[/yellow]")
        console.print("  [cyan]-d[/cyan]  domain          NetworkRecon + PortScan + WAF + WHOIS + EmailSec")
        console.print("  [cyan]-u[/cyan]  username        Username Checker (20 platform)")
        console.print("  [cyan]-e[/cyan]  email[,email]   Data Breach Check (HIBP)")
        console.print("  [cyan]-i[/cyan]  ip[,ip/domain]  IP Reputation (AbuseIPDB · OTX · ip-api)")
        console.print("  [cyan]-x[/cyan]  file / URL      ExifTool Metadata Extractor")
        console.print("  [cyan]-y[/cyan]  link YouTube    Download video / musik (144p – 4K)")
        console.print("  [cyan]-s[/cyan]  URL             URL Safety Scanner (deteksi phishing/berbahaya)")
        console.print("  [cyan]-p[/cyan]  file/URL        Media Player (audio/video di terminal)")
        console.print("\n[dim]Jalankan dengan --help untuk bantuan lengkap[/dim]")
        sys.exit(0)

    # Validasi input sebelum mulai — lebih baik gagal di awal dengan pesan jelas
    if args.domain:
        args.domain = normalize_domain(args.domain)
        if not _DOMAIN_RE.match(args.domain):
            parser.error(f"domain tidak valid: '{args.domain}' (contoh: example.com)")
    if args.username:
        args.username = args.username.strip().lstrip("@")
        if not _USERNAME_RE.match(args.username):
            parser.error("username hanya boleh huruf, angka, titik, underscore, dan strip")

    if args.no_export:
        args.output = None

    exit_code = 0
    try:
        # YouTube downloader berdiri sendiri — tidak masuk ke laporan ASM
        if args.youtube:
            console.print(Panel(
                f"[bold green]▶ Link:[/bold green] [cyan]{escape(args.youtube)}[/cyan]",
                title="[bold]MODULE 10 — YOUTUBE DOWNLOADER[/bold]",
                border_style="red"
            ))
            yt_res = downloader_from_args(args, console).run()
            if yt_res.get("error"):
                exit_code = 1
            console.print()

        # Media player berdiri sendiri — tidak masuk ke laporan ASM
        if player_requested:
            console.print(Panel(
                f"[bold green]▶ Memutar {len(args.play)} sumber[/bold green]",
                title="[bold]MODULE 11 — MEDIA PLAYER[/bold]",
                border_style="green"
            ))
            pl_res = player_from_args(args, console).run()
            if pl_res.get("error"):
                exit_code = 1

        # URL Safety Scanner berdiri sendiri (punya laporan JSON/HTML sendiri)
        if url_scan_requested:
            console.print(Panel(
                "[bold]Analisis pasif tautan — target tidak dibuka/dieksekusi[/bold]",
                title="[bold]MODULE 12 — URL SAFETY SCANNER[/bold]",
                border_style="yellow"
            ))
            sc_res = run_scan_from_args(args, console)
            if sc_res.get("error"):
                exit_code = 1
            elif exit_code == 0:
                worst = sc_res.get("worst_score", 0)
                # Exit code mencerminkan risiko: 2 berbahaya · 1 mencurigakan · 0 aman
                exit_code = 2 if worst >= 60 else 1 if worst >= 25 else 0

        if scan_requested:
            asyncio.run(FreeaseEngine(args).run())
    except KeyboardInterrupt:
        console.print("\n[yellow]⚠ Dihentikan pengguna[/yellow]")
        sys.exit(130)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
