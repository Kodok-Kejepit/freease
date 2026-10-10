#!/usr/bin/env python3
"""
freease — Modul 14: Log Defender (deteksi serangan dari log, sisi blue team)
by: Kodok-Kejepit

Membaca log yang dihasilkan server Anda, mengenali pola serangan, lalu
meringkasnya jadi daftar insiden berperingkat beserta IP penyerangnya.

Modul ini PASIF dan DEFENSIF: hanya membaca log yang sudah ada. Tidak
menyerang, tidak memblokir, tidak mengubah sistem. Keluarannya adalah
pengetahuan situasi — apa yang sedang terjadi, dari mana, dan seberapa serius.

  Sumber log (boleh digabung, deteksi format otomatis):
    FILE              file log apa saja (auth.log, secure, access.log, …)
    auto              cari lokasi log umum di sistem ini
    journal           log sshd dari systemd-journald (journalctl)
    docker:<nama>     keluaran `docker logs <nama>`
    -                 baca dari stdin (pipe)

  Yang dideteksi:
    SSH  — brute-force (gagal login beruntun), percobaan user tidak valid,
           enumerasi banyak username, dan login berhasil setelah banyak gagal
           (indikasi brute-force yang tembus — paling kritis).
    Web  — enumerasi path (banjir 404), probing ke path sensitif
           (/.env, /wp-login.php, /.git, phpMyAdmin, …), User-Agent perkakas
           pemindai (sqlmap, nikto, nmap, …), tanda percobaan injeksi/traversal
           di URL, dan laju permintaan yang tidak wajar.

Pemakaian:
  python freease.py -L auto
  python freease.py -L /var/log/auth.log /var/log/nginx/access.log
  python freease.py -L journal --since "1 hour ago"
  python freease.py -L docker:web-1 --enrich -o ./laporan
  journalctl -u ssh | python freease.py -L -

Gunakan hanya pada log dari sistem milik Anda sendiri atau yang Anda kelola.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import shutil
import subprocess
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Optional

from rich import box
from rich.console import Console
from rich.markup import escape
from rich.panel import Panel
from rich.table import Table

try:
    from freease_ui import paginate
except Exception:                                   # modul bisa jalan sendiri
    def paginate(console, total, render, *, page_size=15, label="item", done_label="selesai"):
        console.print(render(0, total, 0, 1))

__all__ = ["add_defend_args", "run_defend_from_args", "LogDefender"]

# ──────────────────────────────────────────────────────────────
#  Basis pengetahuan deteksi
# ──────────────────────────────────────────────────────────────

# Lokasi log umum yang dicari oleh sumber "auto".
_AUTO_PATHS = [
    "/var/log/auth.log", "/var/log/secure",                 # SSH (Debian / RHEL)
    "/var/log/nginx/access.log", "/var/log/apache2/access.log",
    "/var/log/httpd/access_log",                            # web
]

# Path yang jarang diminta pengguna sah; permintaan ke sini = probing.
_SENSITIVE_PATHS = (
    "/.env", "/.git", "/.aws", "/.ssh", "/wp-login.php", "/wp-admin", "/xmlrpc.php",
    "/phpmyadmin", "/pma", "/adminer", "/.well-known/",
    "/admin", "/administrator", "/manager/html", "/solr", "/actuator",
    "/config", "/backup", "/.svn", "/server-status", "/struts",
    "/vendor/phpunit", "/cgi-bin/", "/boaform", "/shell", "/cmd",
    "/.docker", "/druid", "/jenkins", "/.htpasswd", "/wp-config",
)

# User-Agent perkakas pemindai/serangan yang dikenal.
_SCANNER_UA = (
    "sqlmap", "nikto", "nmap", "masscan", "zgrab", "nuclei", "acunetix",
    "nessus", "openvas", "dirbuster", "gobuster", "wpscan", "hydra",
    "fuzz", "feroxbuster", "httpx", "wfuzz", "xsser", "whatweb", "zmeu",
    "python-requests", "curl/", "libwww", "go-http-client",
)

# Tanda percobaan injeksi / path traversal di URL (hanya untuk deteksi).
_INJECTION_MARKERS = (
    "../", "..%2f", "%2e%2e", "union+select", "union%20select", " or 1=1",
    "'or'", "<script", "%3cscript", "/etc/passwd", "etc%2fpasswd",
    "${jndi:", "{{", "`;", "|cat ", ";wget", ";curl", "cmd=", "exec(",
    "base64_decode", "php://", "data:text/html",
)

# Status HTTP yang menandakan penolakan/ketidakhadiran — pemetaan permukaan.
_PROBE_STATUS = {401, 403, 404, 405, 500, 501}

_IP_RE = re.compile(r"\b(\d{1,3}(?:\.\d{1,3}){3}|[0-9a-fA-F:]{3,}:[0-9a-fA-F:]*)\b")


def _valid_ip(s: str) -> bool:
    try:
        ipaddress.ip_address(s)
        return True
    except ValueError:
        return False


def _is_private(ip: str) -> bool:
    try:
        return ipaddress.ip_address(ip).is_private
    except ValueError:
        return False


# ──────────────────────────────────────────────────────────────
#  Parser baris log
# ──────────────────────────────────────────────────────────────

# OpenSSH (syslog maupun journald). IP diambil lewat regex apa pun format waktunya.
_SSH_FAIL = re.compile(r"Failed (password|publickey) for (invalid user )?(?P<user>\S+) from (?P<ip>\S+)")
_SSH_INVALID = re.compile(r"Invalid user (?P<user>\S+) from (?P<ip>\S+)")
_SSH_ACCEPT = re.compile(r"Accepted (password|publickey|keyboard-interactive/\S+) for (?P<user>\S+) from (?P<ip>\S+)")
_SSH_PREAUTH = re.compile(r"(?:Connection closed|Disconnected) (?:by|from) (?:authenticating user \S+ |invalid user \S+ )?(?P<ip>\d{1,3}(?:\.\d{1,3}){3}|[0-9a-fA-F:]+) .*\[preauth\]")
_SSH_REPEAT = re.compile(r"message repeated (?P<n>\d+) times:\s*\[\s*(?P<inner>.+?)\s*\]")

# Web access log gabungan (combined/common): IP ... "METHOD path HTTP/x" status size "ref" "ua"
_WEB_RE = re.compile(
    r'^(?P<ip>\S+)\s+\S+\s+\S+\s+\[(?P<ts>[^\]]+)\]\s+'
    r'"(?P<method>[A-Z]+)\s+(?P<path>[^"\s]+)(?:\s+HTTP/[\d.]+)?"\s+'
    r'(?P<status>\d{3})\s+(?P<size>\S+)(?:\s+"(?P<ref>[^"]*)"\s+"(?P<ua>[^"]*)")?'
)

_SYSLOG_TS = re.compile(r"^(?P<mon>[A-Z][a-z]{2})\s+(?P<day>\d{1,2})\s+(?P<time>\d{2}:\d{2}:\d{2})")
_ISO_TS = re.compile(r"^(?P<iso>\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})")
_MONTHS = {m: i for i, m in enumerate(
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"], 1)}


def _parse_syslog_ts(line: str) -> Optional[datetime]:
    m = _ISO_TS.match(line)
    if m:
        try:
            return datetime.fromisoformat(m.group("iso").replace(" ", "T"))
        except ValueError:
            return None
    m = _SYSLOG_TS.match(line)
    if m:
        try:
            now = datetime.now()
            hh, mm, ss = map(int, m.group("time").split(":"))
            return datetime(now.year, _MONTHS[m.group("mon")], int(m.group("day")), hh, mm, ss)
        except (ValueError, KeyError):
            return None
    return None


def _parse_web_ts(ts: str) -> Optional[datetime]:
    # 02/Jan/2026:03:04:05 +0000
    try:
        return datetime.strptime(ts.split()[0], "%d/%b/%Y:%H:%M:%S")
    except (ValueError, IndexError):
        return None


class Event:
    """Satu peristiwa yang sudah diklasifikasi dari satu baris log."""

    __slots__ = ("kind", "ip", "user", "status", "path", "ua", "ts", "weight")

    def __init__(self, kind, ip, *, user=None, status=None, path=None, ua=None, ts=None, weight=1):
        self.kind = kind          # ssh_fail | ssh_invalid | ssh_accept | web
        self.ip = ip
        self.user = user
        self.status = status
        self.path = path
        self.ua = ua
        self.ts = ts
        self.weight = weight      # jumlah kejadian (untuk "message repeated N times")


def parse_line(line: str) -> list[Event]:
    """Klasifikasikan satu baris log menjadi 0+ Event. Format dideteksi otomatis."""
    line = line.rstrip("\n")
    if not line:
        return []

    # "message repeated N times: [ ... ]" — perluas jadi N kejadian dari baris dalam
    repeat = 1
    m = _SSH_REPEAT.search(line)
    if m:
        repeat = min(int(m.group("n")), 100000)
        line = line[:m.start()] + m.group("inner")

    ts = _parse_syslog_ts(line)

    # SSH lebih dulu (lebih spesifik), lalu web
    m = _SSH_ACCEPT.search(line)
    if m and _valid_ip(m.group("ip")):
        return [Event("ssh_accept", m.group("ip"), user=m.group("user"), ts=ts, weight=repeat)]
    m = _SSH_FAIL.search(line)
    if m and _valid_ip(m.group("ip")):
        invalid = "invalid user" in line.lower()
        return [Event("ssh_invalid" if invalid else "ssh_fail", m.group("ip"),
                      user=m.group("user"), ts=ts, weight=repeat)]
    m = _SSH_INVALID.search(line)
    if m and _valid_ip(m.group("ip")):
        return [Event("ssh_invalid", m.group("ip"), user=m.group("user"), ts=ts, weight=repeat)]
    m = _SSH_PREAUTH.search(line)
    if m and _valid_ip(m.group("ip")):
        return [Event("ssh_fail", m.group("ip"), user=None, ts=ts, weight=repeat)]

    m = _WEB_RE.match(line)
    if m and _valid_ip(m.group("ip")):
        try:
            status = int(m.group("status"))
        except ValueError:
            status = 0
        return [Event("web", m.group("ip"), status=status, path=m.group("path"),
                      ua=(m.group("ua") or ""), ts=_parse_web_ts(m.group("ts") or ""), weight=1)]
    return []


# ──────────────────────────────────────────────────────────────
#  Agregasi per IP + penilaian
# ──────────────────────────────────────────────────────────────

class IPProfile:
    def __init__(self, ip: str):
        self.ip = ip
        self.ssh_fail = 0
        self.ssh_invalid = 0
        self.ssh_accept = 0
        self.ssh_users: set = set()
        self.accept_users: set = set()
        self.web_total = 0
        self.web_probe = 0                 # status penolakan (401/403/404/…)
        self.sensitive: set = set()
        self.scanner_ua: set = set()
        self.injection: set = set()
        self.first: Optional[datetime] = None
        self.last: Optional[datetime] = None

    def touch(self, ts: Optional[datetime]):
        if ts is None:
            return
        self.first = ts if self.first is None or ts < self.first else self.first
        self.last = ts if self.last is None or ts > self.last else self.last

    def add(self, ev: Event):
        self.touch(ev.ts)
        w = ev.weight
        if ev.kind == "ssh_fail":
            self.ssh_fail += w
            if ev.user:
                self.ssh_users.add(ev.user)
        elif ev.kind == "ssh_invalid":
            self.ssh_invalid += w
            if ev.user:
                self.ssh_users.add(ev.user)
        elif ev.kind == "ssh_accept":
            self.ssh_accept += w
            if ev.user:
                self.accept_users.add(ev.user)
        elif ev.kind == "web":
            self.web_total += w
            if ev.status in _PROBE_STATUS:
                self.web_probe += w
            path = (ev.path or "").lower()
            if any(sp in path for sp in _SENSITIVE_PATHS):
                self.sensitive.add(ev.path.split("?")[0][:80])
            if any(mark in path for mark in _INJECTION_MARKERS):
                self.injection.add(ev.path.split("?")[0][:80])
            ua = (ev.ua or "").lower()
            for tool in _SCANNER_UA:
                if tool in ua:
                    self.scanner_ua.add(tool)
                    break


_SEV_RANK = {"critical": 3, "high": 2, "medium": 1, "info": 0}


def assess(p: IPProfile, cfg: dict) -> Optional[dict]:
    """Ubah profil IP jadi insiden (atau None kalau tak ada sinyal berarti)."""
    reasons: list[tuple[str, str]] = []            # (severity, detail)
    ssh_attempts = p.ssh_fail + p.ssh_invalid

    # ── SSH ──
    if p.ssh_accept and ssh_attempts >= cfg["ssh_threshold"]:
        reasons.append(("critical",
                        f"Login SSH berhasil setelah {ssh_attempts} percobaan gagal "
                        f"(user: {', '.join(sorted(p.accept_users)) or '?'}) — "
                        "indikasi brute-force yang tembus"))
    if ssh_attempts >= cfg["ssh_threshold"]:
        sev = "critical" if ssh_attempts >= cfg["ssh_threshold"] * 10 else \
              "high" if ssh_attempts >= cfg["ssh_threshold"] * 3 else "medium"
        reasons.append((sev, f"Brute-force SSH: {ssh_attempts} percobaan gagal "
                             f"({p.ssh_invalid} user tidak valid)"))
    if len(p.ssh_users) >= cfg["user_enum_threshold"]:
        reasons.append(("high", f"Enumerasi user SSH: {len(p.ssh_users)} username berbeda dicoba"))

    # ── Web ──
    if p.scanner_ua:
        reasons.append(("high", "User-Agent perkakas pemindai: " + ", ".join(sorted(p.scanner_ua))))
    if p.injection:
        ex = list(p.injection)[:3]
        reasons.append(("high", f"Tanda injeksi/traversal di URL ({len(p.injection)}): "
                               + ", ".join(ex)))
    if len(p.sensitive) >= 3:
        reasons.append(("high", f"Probing {len(p.sensitive)} path sensitif berbeda, mis. "
                               + ", ".join(list(p.sensitive)[:3])))
    elif p.sensitive:
        reasons.append(("medium", "Akses ke path sensitif: " + ", ".join(list(p.sensitive)[:3])))
    if p.web_probe >= cfg["web_404_threshold"]:
        sev = "high" if p.web_probe >= cfg["web_404_threshold"] * 4 else "medium"
        reasons.append((sev, f"Enumerasi path: {p.web_probe} respons ditolak/tidak ada "
                             f"(dari {p.web_total} permintaan)"))
    elif p.web_total >= cfg["rate_threshold"]:
        reasons.append(("medium", f"Laju permintaan tinggi: {p.web_total} permintaan"))

    if not reasons:
        return None
    severity = max((r[0] for r in reasons), key=lambda s: _SEV_RANK[s])
    score = (p.ssh_fail + p.ssh_invalid + p.web_probe
             + len(p.sensitive) * 5 + len(p.injection) * 10
             + len(p.scanner_ua) * 10 + p.ssh_accept * 50)
    return {
        "ip": p.ip, "severity": severity, "score": score, "private": _is_private(p.ip),
        "ssh_failed": p.ssh_fail, "ssh_invalid": p.ssh_invalid, "ssh_accepted": p.ssh_accept,
        "ssh_users": len(p.ssh_users), "web_total": p.web_total, "web_probe": p.web_probe,
        "sensitive_paths": sorted(p.sensitive)[:10], "injection": sorted(p.injection)[:10],
        "scanner_ua": sorted(p.scanner_ua),
        "first_seen": p.first.isoformat() if p.first else None,
        "last_seen": p.last.isoformat() if p.last else None,
        "reasons": [{"severity": s, "detail": d} for s, d in
                    sorted(reasons, key=lambda r: -_SEV_RANK[r[0]])],
    }


# ──────────────────────────────────────────────────────────────
#  Pembaca sumber log
# ──────────────────────────────────────────────────────────────

def _read_lines(source: str, since: Optional[str], console: Console):
    """Yield baris dari sebuah sumber. Mengembalikan generator, menutup sendiri."""
    if source == "-":
        yield from sys.stdin
        return
    if source == "journal":
        if not shutil.which("journalctl"):
            console.print("  [yellow]\\[!] journalctl tidak ada; lewati sumber 'journal'.[/yellow]")
            return
        cmd = ["journalctl", "--no-pager", "-o", "short-iso", "_COMM=sshd"]
        if since:
            cmd += ["--since", since]
        yield from _run_lines(cmd, console)
        return
    if source.startswith("docker:"):
        name = source.split(":", 1)[1]
        if not shutil.which("docker"):
            console.print("  [yellow]\\[!] docker tidak ada; lewati sumber docker.[/yellow]")
            return
        cmd = ["docker", "logs", name]
        if since:
            cmd += ["--since", since]
        yield from _run_lines(cmd, console)
        return
    # file
    p = Path(source).expanduser()
    if not p.is_file():
        console.print(f"  [yellow]\\[!] Bukan file: {escape(source)}[/yellow]")
        return
    try:
        with p.open("r", encoding="utf-8", errors="replace") as fh:
            yield from fh
    except PermissionError:
        console.print(f"  [red]\\[x] Tidak punya izin baca {escape(source)} "
                      "(coba jalankan dengan sudo).[/red]")
    except OSError as e:
        console.print(f"  [yellow]\\[!] Gagal baca {escape(source)}: {escape(str(e))}[/yellow]")


def _run_lines(cmd: list[str], console: Console):
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError) as e:
        console.print(f"  [yellow]\\[!] Gagal menjalankan {escape(cmd[0])}: {escape(str(e))}[/yellow]")
        return
    out = proc.stdout + ("\n" + proc.stderr if proc.stderr else "")
    yield from out.splitlines()


def _resolve_sources(sources: list[str], console: Console) -> list[str]:
    out: list[str] = []
    for s in sources:
        if s == "auto":
            found = [p for p in _AUTO_PATHS if Path(p).is_file()]
            if not found:
                console.print("  [yellow]\\[!] 'auto': tidak menemukan log umum di sistem ini. "
                              "Sebutkan path-nya langsung.[/yellow]")
            out += found
        else:
            out.append(s)
    # dedup stabil
    seen, res = set(), []
    for s in out:
        if s not in seen:
            seen.add(s)
            res.append(s)
    return res


# ──────────────────────────────────────────────────────────────
#  Orkestrator
# ──────────────────────────────────────────────────────────────

class LogDefender:
    def __init__(self, sources, *, console=None, since=None, enrich=False,
                 abuseipdb_key=None, ssh_threshold=10, web_404_threshold=25,
                 rate_threshold=300, user_enum_threshold=5, include_private=False):
        self.sources = sources
        self.console = console or Console()
        self.since = since
        self.enrich = enrich
        self.abuseipdb_key = abuseipdb_key
        self.include_private = include_private
        self.cfg = {
            "ssh_threshold": max(1, ssh_threshold),
            "web_404_threshold": max(1, web_404_threshold),
            "rate_threshold": max(1, rate_threshold),
            "user_enum_threshold": max(2, user_enum_threshold),
        }
        self.lines = 0
        self.events = 0
        self.span = [None, None]

    def run(self) -> dict:
        srcs = _resolve_sources(self.sources, self.console)
        if not srcs:
            self.console.print("  [red]\\[x] Tidak ada sumber log yang bisa dibaca.[/red]")
            return {"error": "tidak ada sumber", "incidents": []}

        profiles: dict[str, IPProfile] = {}
        with self.console.status("[cyan]Membaca log…") as status:
            for src in srcs:
                status.update(f"[cyan]Membaca {escape(src)}…")
                for line in _read_lines(src, self.since, self.console):
                    self.lines += 1
                    for ev in parse_line(line):
                        self.events += 1
                        if ev.ts:
                            self.span[0] = ev.ts if not self.span[0] or ev.ts < self.span[0] else self.span[0]
                            self.span[1] = ev.ts if not self.span[1] or ev.ts > self.span[1] else self.span[1]
                        profiles.setdefault(ev.ip, IPProfile(ev.ip)).add(ev)

        incidents = []
        for ip, prof in profiles.items():
            if prof.ip and _is_private(ip) and not self.include_private:
                continue
            inc = assess(prof, self.cfg)
            if inc:
                incidents.append(inc)
        incidents.sort(key=lambda d: (-_SEV_RANK[d["severity"]], -d["score"]))

        if self.enrich and incidents:
            self._enrich(incidents)

        worst = max((_SEV_RANK[i["severity"]] for i in incidents), default=0)
        result = {
            "error": None,
            "scanned_at": datetime.now().isoformat(),
            "sources": srcs,
            "lines": self.lines, "events": self.events,
            "time_span": [self.span[0].isoformat() if self.span[0] else None,
                          self.span[1].isoformat() if self.span[1] else None],
            "incident_count": len(incidents),
            "worst_rank": worst,
            "incidents": incidents,
        }
        self._render(result)
        return result

    def _enrich(self, incidents: list[dict]) -> None:
        """Tambah reputasi IP publik untuk beberapa penyerang teratas (opsional)."""
        try:
            import asyncio
            import aiohttp
            from freease import IPReputationChecker, USER_AGENT
        except Exception as e:
            self.console.print(f"  [yellow]\\[!] Enrichment dilewati: {escape(str(e)[:120])}[/yellow]")
            return
        targets = [i["ip"] for i in incidents if not i["private"]][:10]
        if not targets:
            return

        async def _go():
            conn = aiohttp.TCPConnector(limit=20, ttl_dns_cache=300)
            async with aiohttp.ClientSession(headers={"User-Agent": USER_AGENT},
                                             connector=conn) as session:
                checker = IPReputationChecker(abuseipdb_key=self.abuseipdb_key)
                return await checker.check_ips_bulk(session, targets)

        try:
            with self.console.status("[cyan]Mengecek reputasi IP penyerang…"):
                rep = asyncio.run(_go())
        except Exception as e:
            self.console.print(f"  [yellow]\\[!] Enrichment gagal: {escape(str(e)[:120])}[/yellow]")
            return
        for inc in incidents:
            data = rep.get(inc["ip"]) if isinstance(rep, dict) else None
            if isinstance(data, dict):
                geo = data.get("geolocation") or {}
                inc["reputation"] = {
                    "risk": data.get("risk_level"),
                    "score": data.get("overall_risk_score"),
                    "country": geo.get("country"),
                    "isp": geo.get("isp"),
                    "flags": data.get("flags") or [],
                }

    # ── Tampilan ───────────────────────────────────────────────
    def _render(self, res: dict) -> None:
        span = res["time_span"]
        span_txt = f"{span[0]} → {span[1]}" if span[0] else "—"
        head = [
            f"[bold]Sumber :[/bold] {escape(', '.join(res['sources']))}",
            f"[bold]Dibaca :[/bold] {res['lines']} baris, {res['events']} peristiwa terkait",
            f"[bold]Rentang:[/bold] {span_txt}",
        ]
        rank_name = {3: "KRITIS", 2: "TINGGI", 1: "SEDANG", 0: "AMAN"}[res["worst_rank"]]
        rank_color = {3: "bold red", 2: "red", 1: "yellow", 0: "green"}[res["worst_rank"]]
        verdict = (f"{res['incident_count']} IP mencurigakan" if res["incident_count"]
                   else "Tidak ada aktivitas mencurigakan terdeteksi")
        self.console.print(Panel(
            "\n".join(head) + f"\n\n[{rank_color}]TINGKAT TERTINGGI: {rank_name}  ·  {verdict}[/{rank_color}]",
            title="[bold]LOG DEFENDER[/bold]",
            subtitle="[dim]analisis pasif — hanya membaca log[/dim]",
            border_style=rank_color.split()[-1], box=box.ROUNDED))

        incidents = res["incidents"]
        if not incidents:
            self.console.print("  [green]\\[+] Bersih sejauh yang terbaca dari log.[/green]\n")
            return

        sev_style = {"critical": "bold red", "high": "red", "medium": "yellow", "info": "dim"}

        def _render_page(start, end, page, pages):
            t = Table(box=box.SIMPLE_HEAVY, header_style="bold cyan",
                      title="[bold]Insiden per IP[/bold]", title_justify="left")
            t.add_column("Severitas", width=9)
            t.add_column("IP", overflow="fold", width=24)
            t.add_column("Ringkas", overflow="fold")
            t.add_column("Gagal", justify="right", width=6)
            t.add_column("Web", justify="right", width=6)
            for inc in incidents[start:end]:
                st = sev_style.get(inc["severity"], "white")
                rep = inc.get("reputation") or {}
                ipcell = escape(inc["ip"])
                if rep.get("risk"):
                    ipcell += f"\n[dim]{escape(str(rep.get('country') or ''))} · {escape(str(rep['risk']))}[/dim]"
                top = escape(inc["reasons"][0]["detail"]) if inc["reasons"] else ""
                if len(inc["reasons"]) > 1:
                    top += f"  [dim](+{len(inc['reasons']) - 1} lagi)[/dim]"
                t.add_row(f"[{st}]{inc['severity'].upper()}[/{st}]", ipcell, top,
                          str(inc["ssh_failed"] + inc["ssh_invalid"]), str(inc["web_total"]))
            return t

        paginate(self.console, len(incidents), _render_page, page_size=12,
                 label="insiden", done_label="lanjut")
        self.console.print()


# ──────────────────────────────────────────────────────────────
#  Laporan JSON / HTML
# ──────────────────────────────────────────────────────────────

def _esc(s) -> str:
    import html as _h
    return _h.escape(str(s))


def write_reports(res: dict, out_dir: str, console: Console) -> None:
    try:
        Path(out_dir).mkdir(parents=True, exist_ok=True)
    except OSError as e:
        console.print(f"  [yellow]\\[!] Gagal membuat folder output: {escape(str(e))}[/yellow]")
        return
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    jpath = Path(out_dir) / f"freease_defend_{ts}.json"
    try:
        jpath.write_text(json.dumps(res, indent=2, ensure_ascii=False), "utf-8")
        console.print(f"  [dim]JSON:[/dim] {escape(str(jpath))}")
    except OSError as e:
        console.print(f"  [yellow]\\[!] Gagal menulis JSON: {escape(str(e))}[/yellow]")

    color = {"critical": "#e5484d", "high": "#f5a623", "medium": "#d29922", "info": "#8b949e"}
    rows = []
    for inc in res["incidents"]:
        items = "".join(f"<li><b>[{r['severity'].upper()}]</b> {_esc(r['detail'])}</li>"
                        for r in inc["reasons"])
        rep = inc.get("reputation") or {}
        rep_txt = (f" · <span style='color:#9cf'>{_esc(rep.get('country') or '')} "
                   f"{_esc(rep.get('isp') or '')} (risk {_esc(rep.get('risk') or '?')})</span>"
                   if rep.get("risk") else "")
        rows.append(
            f"<section><h2 style='color:{color.get(inc['severity'], '#ccc')}'>"
            f"{_esc(inc['ip'])} <small>[{inc['severity'].upper()}]</small></h2>"
            f"<p class=m>SSH gagal {inc['ssh_failed'] + inc['ssh_invalid']} · "
            f"berhasil {inc['ssh_accepted']} · web {inc['web_total']} "
            f"(ditolak {inc['web_probe']}){rep_txt}</p><ul>{items}</ul></section>")
    span = res["time_span"]
    html = (
        "<!doctype html><meta charset=utf-8><title>freease — Log Defender</title>"
        "<style>body{font:14px/1.6 system-ui,sans-serif;max-width:900px;margin:2rem auto;"
        "padding:0 1rem;background:#0f1115;color:#e6e6e6}h1{color:#6cc}"
        "section{border:1px solid #2a2f3a;border-radius:8px;padding:.6rem 1rem;margin:1rem 0;"
        "background:#161a22}h2{font-size:1rem;margin:.3rem 0}.m{color:#8b949e;font-size:.82rem}"
        "small{color:#8b949e}li{margin:.25rem 0}</style>"
        f"<h1>freease — Log Defender</h1>"
        f"<p class=m>{datetime.now():%Y-%m-%d %H:%M:%S} · {res['lines']} baris · "
        f"{res['incident_count']} IP mencurigakan · rentang {_esc(span[0] or '—')} → {_esc(span[1] or '—')}</p>"
        + ("".join(rows) or "<p>Tidak ada insiden.</p>"))
    hpath = Path(out_dir) / f"freease_defend_{ts}.html"
    try:
        hpath.write_text(html, "utf-8")
        console.print(f"  [dim]HTML:[/dim] {escape(str(hpath))}")
    except OSError:
        pass


# ──────────────────────────────────────────────────────────────
#  CLI
# ──────────────────────────────────────────────────────────────

def add_defend_args(p) -> None:
    g = p.add_argument_group("Log Defender (blue team)")
    g.add_argument("-L", "--log-scan", dest="log_sources", nargs="+", default=None,
                   metavar="SRC",
                   help="Deteksi serangan dari log: FILE, auto, journal, docker:<nama>, atau -")
    g.add_argument("--since", dest="log_since", default=None, metavar="WAKTU",
                   help="Batasi rentang waktu untuk journal/docker, mis. \"1 hour ago\"")
    g.add_argument("--enrich", dest="log_enrich", action="store_true",
                   help="Cek reputasi IP penyerang teratas (AbuseIPDB/OTX/ip-api, butuh internet)")
    g.add_argument("--ssh-threshold", dest="ssh_threshold", type=int, default=10, metavar="N",
                   help="Minimal percobaan SSH gagal per IP untuk dihitung brute-force (default: 10)")
    g.add_argument("--web-threshold", dest="web_threshold", type=int, default=25, metavar="N",
                   help="Minimal respons ditolak/404 per IP untuk dihitung enumerasi (default: 25)")
    g.add_argument("--include-private", dest="log_private", action="store_true",
                   help="Ikut laporkan IP privat/LAN (default: dilewati)")


def run_defend_from_args(args, console: Optional[Console] = None) -> dict:
    console = console or Console()
    res = LogDefender(
        list(args.log_sources),
        console=console,
        since=getattr(args, "log_since", None),
        enrich=getattr(args, "log_enrich", False),
        abuseipdb_key=getattr(args, "abuseipdb_key", None),
        ssh_threshold=getattr(args, "ssh_threshold", 10),
        web_404_threshold=getattr(args, "web_threshold", 25),
        include_private=getattr(args, "log_private", False),
    ).run()
    out_dir = getattr(args, "output", None)
    if out_dir and not getattr(args, "no_export", False) and not res.get("error"):
        write_reports(res, out_dir, console)
    return res


def main() -> None:
    p = argparse.ArgumentParser(
        prog="freease_defend",
        description="freease — Log Defender (deteksi serangan dari log, blue team)")
    p.add_argument("-o", "--output", default="./freease_output",
                   help="Folder laporan JSON/HTML (default: ./freease_output)")
    p.add_argument("--no-export", action="store_true", help="Jangan tulis laporan")
    p.add_argument("--abuseipdb-key", dest="abuseipdb_key",
                   default=os.environ.get("ABUSEIPDB_API_KEY"))
    add_defend_args(p)
    args = p.parse_args()
    if not args.log_sources:
        p.error("masukkan minimal satu sumber log (mis. -L auto)")
    res = run_defend_from_args(args)
    # exit code: 2 kritis · 1 ada insiden · 0 bersih
    sys.exit(2 if res.get("worst_rank") == 3 else 1 if res.get("incident_count") else 0)


if __name__ == "__main__":
    main()
