#!/usr/bin/env python3
"""
freease — Modul 15: Host Compromise Scanner (sisi blue team)
by: Kodok-Kejepit

Memeriksa sebuah host untuk sisa-sisa kompromi: web shell yang ditanam di
direktori web, dan mekanisme persistence yang ditinggalkan penyerang (cron,
systemd, kunci SSH, berkas rc shell, ld.so.preload). Ini melengkapi Log
Defender — yang satu melihat serangan saat terjadi dari log, yang ini mencari
jejak yang tertinggal setelah penyerang berhasil masuk.

Modul ini PASIF dan DEFENSIF: hanya MEMBACA berkas lalu melaporkan temuan.
Tidak pernah mengubah, menghapus, mengkarantina, atau menjalankan apa pun.
Keluarannya adalah daftar temuan untuk ditinjau manusia — sebuah temuan
berarti "perlu diperiksa", bukan vonis otomatis.

  Yang diperiksa:
    Web shell  — pola kode berbahaya di berkas skrip (PHP/JSP/ASP/dll),
                 nama berkas mencurigakan (ekstensi ganda, skrip di folder
                 unggahan), dan skrip yang baru diubah.
    Persistence— entri cron yang memanggil pengunduh/penyandi, unit systemd
                 yang menunjuk lokasi tak lazim, kunci di ~/.ssh/authorized_keys,
                 baris mencurigakan di berkas rc shell, dan /etc/ld.so.preload.

Pemakaian:
  python freease.py -C /var/www                         (pindai direktori web)
  python freease.py -C /var/www/html --recent 7         (hanya yang berubah 7 hari terakhir)
  python freease.py -C auto --persistence -o ./laporan  (web root umum + cek persistence)
  python freease.py -C auto --persistence --enrich      (+ reputasi IP dari kunci/temuan)

Membaca /etc, /var/spool, dan home orang lain biasanya butuh root; jalankan
dengan sudo bila perlu. Gunakan hanya pada host milik Anda sendiri atau yang
Anda kelola.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import stat
import sys
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

from rich import box
from rich.console import Console
from rich.markup import escape
from rich.panel import Panel
from rich.table import Table

try:
    from freease_ui import paginate
except Exception:
    def paginate(console, total, render, *, page_size=15, label="item", done_label="selesai"):
        console.print(render(0, total, 0, 1))

__all__ = ["add_hostcheck_args", "run_hostcheck_from_args", "HostChecker"]

# ──────────────────────────────────────────────────────────────
#  Lokasi default
# ──────────────────────────────────────────────────────────────
_AUTO_WEBROOTS = [
    "/var/www", "/var/www/html", "/srv/www", "/srv/http", "/usr/share/nginx/html",
    "/opt/lampp/htdocs", "/home/*/public_html", "/home/*/www",
]

# Ekstensi berkas yang dianggap "skrip sisi server" (kandidat web shell).
_SCRIPT_EXT = {
    ".php", ".php3", ".php4", ".php5", ".php7", ".phtml", ".pht", ".phar",
    ".jsp", ".jspx", ".jspf", ".asp", ".aspx", ".ashx", ".asmx", ".cfm",
    ".pl", ".cgi", ".py", ".rb", ".sh",
}
# Nama folder (komponen path) tempat skrip sisi server TIDAK seharusnya ada.
_UPLOAD_DIRS = {"upload", "uploads", "images", "img", "static", "assets",
                "media", "files", "cache", "backup", "attachments", "userfiles"}
_UPLOAD_SUBPATHS = ("wp-content/uploads", "sites/default/files")

# ──────────────────────────────────────────────────────────────
#  Signature deteksi web shell (DEFENSIF — pola untuk DICOCOKKAN, bukan payload)
#
#  Setiap aturan: (nama, severity, regex). Regex sengaja mencari KOMBINASI
#  "sink berbahaya + input dari request" atau pola obfuscation yang khas pada
#  web shell, supaya tidak menandai kode sah yang hanya kebetulan memakai satu
#  fungsi biasa.
# ──────────────────────────────────────────────────────────────
_REQ = r"\$_(?:GET|POST|REQUEST|COOKIE|SERVER|FILES)\b|php://input|getenv\s*\("
_PHP_SINK = r"(?:system|exec|shell_exec|passthru|popen|proc_open|pcntl_exec|assert|eval)"

_WEBSHELL_RULES = [
    ("eval-request", "critical",
     re.compile(r"\b(?:eval|assert)\s*\(\s*(?:stripslashes\s*\(\s*)?(?:" + _REQ + r")", re.I)),
    ("sink-request", "critical",
     re.compile(_PHP_SINK + r"\s*\(\s*[^;)]{0,60}(?:" + _REQ + r")", re.I)),
    ("eval-decoded", "high",
     re.compile(r"\b(?:eval|assert)\s*\(\s*(?:base64_decode|gzinflate|gzuncompress|str_rot13|rawurldecode|hex2bin|pack)\s*\(", re.I)),
    ("preg-e-modifier", "high",
     re.compile(r"preg_replace\s*\(\s*['\"][^'\"]*/[a-z]*e[a-z]*['\"]", re.I)),
    ("create-function", "high",
     re.compile(r"create_function\s*\(.*(?:" + _REQ + r")", re.I)),
    ("callback-request", "high",
     re.compile(r"\b(?:call_user_func(?:_array)?|array_map|array_filter|register_shutdown_function)\s*\(\s*(?:" + _REQ + r")", re.I)),
    ("dynamic-call-request", "medium",
     re.compile(r"\$[a-z_]\w*\s*\(\s*(?:" + _REQ + r")", re.I)),
    ("obfuscation-chain", "high",
     re.compile(r"(?:base64_decode|gzinflate|gzuncompress|str_rot13)\s*\(\s*(?:base64_decode|gzinflate|gzuncompress|str_rot13)\s*\(", re.I)),
    ("php-in-nonphp", "high",
     re.compile(r"<\?php", re.I)),              # hanya diaktifkan untuk berkas non-skrip (lihat scan)
    ("known-shell-marker", "critical",
     re.compile(r"\b(?:c99shell|r57shell|wso\s*shell|b374k|weevely|FilesMan|phpspy|\bWSO\b|Mini[Ss]hell|IndoXploit|b374k)\b")),
    ("jsp-runtime-exec", "critical",
     re.compile(r"Runtime\.getRuntime\(\)\.exec\s*\(|new\s+ProcessBuilder\s*\(", re.I)),
    ("asp-eval-request", "critical",
     re.compile(r"(?:eval|execute)\s*\(\s*request\b|Server\.CreateObject\s*\(\s*['\"]WScript\.Shell", re.I)),
]

# Pola persistence: baris skrip yang "mengunduh lalu menjalankan" atau menyandi perintah.
_PERSIST_SUSPECT = re.compile(
    r"(?:curl|wget|fetch)\b[^\n|]*\|\s*(?:ba)?sh\b"
    r"|(?:base64\s+-d|base64\s+--decode)[^\n]*\|\s*(?:ba)?sh\b"
    r"|\beval\s+\"?\$\(.*(?:curl|wget)"
    r"|python[0-9]?\s+-c\s+['\"].*(?:socket|exec|base64)"
    r"|/dev/tcp/\d|nc\s+-e\b|ncat\s+-e\b|mkfifo\b"
    r"|/tmp/|/dev/shm/|/var/tmp/\.",
    re.I,
)

_IP_RE = re.compile(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b")


def _shannon(s: str) -> float:
    if not s:
        return 0.0
    freq = {c: s.count(c) for c in set(s)}
    n = len(s)
    return -sum((c / n) * math.log2(c / n) for c in freq.values())


class Finding:
    __slots__ = ("severity", "category", "path", "detail", "line_no", "excerpt")

    def __init__(self, severity, category, path, detail, line_no=None, excerpt=""):
        self.severity = severity
        self.category = category        # webshell | persistence | permission
        self.path = path
        self.detail = detail
        self.line_no = line_no
        self.excerpt = excerpt

    def to_dict(self) -> dict:
        return {"severity": self.severity, "category": self.category, "path": self.path,
                "detail": self.detail, "line": self.line_no, "excerpt": self.excerpt}


_SEV_RANK = {"critical": 3, "high": 2, "medium": 1, "low": 0, "info": 0}


# ──────────────────────────────────────────────────────────────
#  Scanner
# ──────────────────────────────────────────────────────────────
class HostChecker:
    def __init__(self, targets, *, console=None, recent_days=None, persistence=False,
                 max_size_mb=5, enrich=False, abuseipdb_key=None):
        self.targets = targets
        self.console = console or Console()
        self.recent_days = recent_days
        self.persistence = persistence
        self.max_size = max(1, int(max_size_mb * 1024 * 1024))
        self.enrich = enrich
        self.abuseipdb_key = abuseipdb_key
        self.findings: list[Finding] = []
        self.files_scanned = 0
        self.errors = 0
        self._cutoff = (datetime.now() - timedelta(days=recent_days)) if recent_days else None

    # ── util ───────────────────────────────────────────────────
    def _add(self, *a, **kw):
        self.findings.append(Finding(*a, **kw))

    def _read_text(self, path: Path, limit: int) -> Optional[str]:
        try:
            if path.stat().st_size > limit:
                with path.open("rb") as fh:
                    data = fh.read(limit)
            else:
                data = path.read_bytes()
            return data.decode("utf-8", "replace")
        except (OSError, PermissionError):
            self.errors += 1
            return None

    def _expand(self, target: str) -> list[Path]:
        import glob
        if target == "auto":
            out = []
            for g in _AUTO_WEBROOTS:
                out += [Path(p) for p in glob.glob(g) if Path(p).is_dir()]
            return out
        if any(ch in target for ch in "*?["):
            return [Path(p) for p in glob.glob(os.path.expanduser(target))]
        return [Path(target).expanduser()]

    # ── pindai web shell ───────────────────────────────────────
    def scan_webshells(self) -> bool:
        """Pindai web shell. Mengembalikan False kalau tidak ada target yang ada."""
        roots: list[Path] = []
        for t in self.targets:
            roots += self._expand(t)
        roots = [r for r in roots if r.exists()]
        if not roots:
            self.console.print("  [yellow]\\[!] Tidak ada direktori target yang ada. "
                               "Sebutkan path web root, mis. /var/www.[/yellow]")
            return False

        for root in roots:
            if root.is_file():
                self._scan_file(root, root.parent)
            else:
                for f in self._walk(root):
                    self._scan_file(f, root)
        return True

    def _walk(self, root: Path):
        skip_dirs = {".git", "node_modules", "vendor", ".svn", "__pycache__"}
        for dirpath, dirnames, filenames in os.walk(root, onerror=lambda e: None):
            dirnames[:] = [d for d in dirnames if d not in skip_dirs]
            for name in filenames:
                yield Path(dirpath) / name

    def _rel_parts(self, f: Path, root: Path) -> list:
        try:
            return [p.lower() for p in f.relative_to(root).parts]
        except ValueError:
            return [f.name.lower()]

    def _scan_file(self, f: Path, root: Path) -> None:
        try:
            st = f.lstat()
        except OSError:
            self.errors += 1
            return
        if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
            return
        if st.st_size == 0 or st.st_size > self.max_size:
            return

        ext = f.suffix.lower()
        name = f.name.lower()
        parts = self._rel_parts(f, root)              # komponen relatif thd root
        dir_parts = set(parts[:-1])
        rel = "/".join(parts)
        in_upload = bool(dir_parts & _UPLOAD_DIRS) or any(h in rel for h in _UPLOAD_SUBPATHS)
        is_script = ext in _SCRIPT_EXT
        mtime = datetime.fromtimestamp(st.st_mtime)
        recent = self._cutoff is not None and mtime >= self._cutoff
        rec_txt = f"  (diubah {mtime:%Y-%m-%d %H:%M})"

        # Nama/lokasi mencurigakan (tanpa harus baca isi)
        double_ext = re.search(r"\.(?:php\d?|phtml|pht|phar|jsp|asp|aspx)\.(?:jpg|jpeg|png|gif|txt|ico|zip)$", name)
        if double_ext:
            self._add("high", "webshell", str(f),
                      "Ekstensi ganda menyamarkan skrip sebagai berkas media/arsip" + (rec_txt if recent else ""))
        if is_script and in_upload:
            self._add("high", "webshell", str(f),
                      f"Skrip sisi server ({ext}) berada di folder unggahan/aset" + (rec_txt if recent else ""))

        # Isi
        text = self._read_text(f, self.max_size)
        if text is None:
            return
        self.files_scanned += 1

        hits = []            # (severity, rule_name, line_no, excerpt)
        for rule_name, sev, rx in _WEBSHELL_RULES:
            if rule_name == "php-in-nonphp" and (is_script or ext in (".html", ".htm", ".xml", ".md", ".rst")):
                continue
            m = rx.search(text)
            if m:
                ln = text.count("\n", 0, m.start()) + 1
                hits.append((sev, rule_name, ln, _excerpt(text, m.start())))
        if hits:
            top = min(hits, key=lambda h: _SEV_RANK.get(h[0], 0) * -1)   # severity tertinggi
            sev = top[0]
            if recent and sev != "critical":
                sev = {"high": "critical", "medium": "high"}.get(sev, sev)
            rules = ", ".join(sorted({h[1] for h in hits}))
            self._add(sev, "webshell", str(f),
                      f"Pola web shell: {rules}" + (rec_txt if recent else ""),
                      line_no=top[2], excerpt=top[3])

        # Blob base64 panjang + entropi tinggi di berkas kecil (payload terbungkus)
        if is_script and st.st_size < 200_000:
            for mb in re.finditer(r"['\"]([A-Za-z0-9+/=]{200,})['\"]", text):
                blob = mb.group(1)
                if _shannon(blob) >= 5.0:
                    ln = text.count("\n", 0, mb.start()) + 1
                    self._add("medium", "webshell", str(f),
                              f"Blob ter-encode panjang ({len(blob)} char, entropi tinggi)",
                              line_no=ln)
                    break

    # ── persistence ────────────────────────────────────────────
    def scan_persistence(self) -> None:
        self._check_cron()
        self._check_systemd()
        self._check_authorized_keys()
        self._check_shell_rc()
        self._check_ld_preload()

    def _flag_lines(self, path: Path, category: str, sev_hit="high"):
        text = self._read_text(path, 512 * 1024)
        if text is None:
            return
        for i, line in enumerate(text.splitlines(), 1):
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if _PERSIST_SUSPECT.search(line):
                self._add(sev_hit, category, str(path),
                          "Baris memanggil pengunduh/penyandi atau lokasi tak lazim",
                          line_no=i, excerpt=s[:160])

    def _iter_paths(self, patterns):
        import glob
        for pat in patterns:
            for p in glob.glob(pat):
                pp = Path(p)
                if pp.is_file():
                    yield pp

    def _check_cron(self):
        crons = ["/etc/crontab", "/etc/cron.d/*", "/etc/cron.hourly/*", "/etc/cron.daily/*",
                 "/var/spool/cron/*", "/var/spool/cron/crontabs/*"]
        for p in self._iter_paths(crons):
            self._flag_lines(p, "persistence")

    def _check_systemd(self):
        units = ["/etc/systemd/system/*.service", "/etc/systemd/system/*/*.service",
                 "/run/systemd/system/*.service",
                 os.path.expanduser("~/.config/systemd/user/*.service")]
        for p in self._iter_paths(units):
            text = self._read_text(p, 256 * 1024)
            if text is None:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                s = line.strip()
                if not s.lower().startswith(("execstart", "execstartpre", "execstartpost")):
                    continue
                if _PERSIST_SUSPECT.search(s) or re.search(r"=\s*/?(?:tmp|dev/shm|var/tmp)/", s):
                    self._add("high", "persistence", str(p),
                              "Unit systemd menjalankan perintah dari lokasi/pola mencurigakan",
                              line_no=i, excerpt=s[:160])

    def _check_authorized_keys(self):
        import glob
        homes = ["/root"] + glob.glob("/home/*")
        for home in homes:
            ak = Path(home) / ".ssh" / "authorized_keys"
            if not ak.is_file():
                continue
            try:
                mode = ak.stat().st_mode
                if mode & (stat.S_IWGRP | stat.S_IWOTH):
                    self._add("high", "persistence", str(ak),
                              "authorized_keys bisa ditulis grup/other (izin terlalu longgar)")
            except OSError:
                pass
            text = self._read_text(ak, 256 * 1024)
            if text is None:
                continue
            keys = [l for l in text.splitlines() if l.strip() and not l.strip().startswith("#")]
            if keys:
                self._add("info", "persistence", str(ak),
                          f"{len(keys)} kunci SSH terpasang (tinjau: pastikan semua milik Anda)")
            for i, line in enumerate(keys, 1):
                low = line.lower()
                if "command=" in low and ("curl" in low or "wget" in low or "/tmp/" in low or "base64" in low):
                    self._add("high", "persistence", str(ak),
                              "Kunci SSH dengan command= yang menjalankan perintah mencurigakan",
                              line_no=i, excerpt=line[:160])

    def _check_shell_rc(self):
        import glob
        rcs = ["/etc/profile", "/etc/bash.bashrc", "/etc/profile.d/*.sh", "/root/.bashrc",
               "/root/.bash_profile", "/root/.profile"]
        for h in glob.glob("/home/*"):
            rcs += [f"{h}/.bashrc", f"{h}/.bash_profile", f"{h}/.profile", f"{h}/.zshrc"]
        for p in self._iter_paths(rcs):
            self._flag_lines(p, "persistence")

    def _check_ld_preload(self):
        f = Path("/etc/ld.so.preload")
        if f.is_file():
            text = self._read_text(f, 64 * 1024)
            libs = [l.strip() for l in (text or "").splitlines() if l.strip()]
            if libs:
                self._add("critical", "persistence", str(f),
                          "/etc/ld.so.preload berisi pustaka (sering dipakai rootkit userland): "
                          + ", ".join(libs[:5]))

    # ── enrichment (reputasi IP yang muncul di temuan) ─────────
    def _enrich(self) -> None:
        ips = set()
        for fnd in self.findings:
            ips.update(_IP_RE.findall(fnd.excerpt or ""))
        ips = [ip for ip in ips if not _is_private(ip)][:10]
        if not ips:
            return
        try:
            import asyncio
            import aiohttp
            from freease import IPReputationChecker, USER_AGENT
        except Exception:
            return

        async def _go():
            async with aiohttp.ClientSession(headers={"User-Agent": USER_AGENT}) as s:
                return await IPReputationChecker(abuseipdb_key=self.abuseipdb_key).check_ips_bulk(s, ips)
        try:
            with self.console.status("[cyan]Mengecek reputasi IP di temuan…"):
                rep = asyncio.run(_go())
        except Exception:
            return
        for ip, data in (rep or {}).items():
            if isinstance(data, dict) and data.get("risk_level") not in (None, "LOW"):
                geo = data.get("geolocation") or {}
                self._add("high", "persistence", "(reputasi IP)",
                          f"IP {ip} di temuan berisiko: {data.get('risk_level')} "
                          f"(skor {data.get('overall_risk_score')}, {geo.get('country') or '?'})")

    # ── run ────────────────────────────────────────────────────
    def run(self) -> dict:
        found_target = self.scan_webshells()
        # Tanpa target dan tanpa pemeriksaan persistence, tidak ada yang diperiksa sama sekali.
        # Itu bukan "bersih" — laporkan sebagai galat.
        if not found_target and not self.persistence:
            self.console.print("  [bold red]\\[x] Tidak ada yang diperiksa, jadi tidak ada hasil "
                               "(ini BUKAN berarti host bersih).[/bold red]")
            return {"error": "tidak ada target yang diperiksa", "findings": [],
                    "finding_count": 0, "worst_rank": 0}
        if self.persistence:
            self.scan_persistence()
        if self.enrich and self.findings:
            self._enrich()

        self.findings.sort(key=lambda f: (-_SEV_RANK[f.severity], f.category, f.path))
        worst = max((_SEV_RANK[f.severity] for f in self.findings
                     if f.severity != "info"), default=0)
        result = {
            "error": None,
            "scanned_at": datetime.now().isoformat(),
            "targets": self.targets,
            "persistence_checked": self.persistence,
            "files_scanned": self.files_scanned,
            "read_errors": self.errors,
            "finding_count": sum(1 for f in self.findings if f.severity != "info"),
            "not_inspected": self.files_scanned == 0 and not self.persistence,
            "worst_rank": worst,
            "findings": [f.to_dict() for f in self.findings],
        }
        self._render(result)
        return result

    def _render(self, res: dict) -> None:
        head = [
            f"[bold]Target :[/bold] {escape(', '.join(res['targets']))}",
            f"[bold]Berkas :[/bold] {res['files_scanned']} dibaca"
            + (f" · {res['read_errors']} tak terbaca (izin?)" if res['read_errors'] else ""),
            f"[bold]Persistence:[/bold] {'diperiksa' if res['persistence_checked'] else 'dilewati (pakai --persistence)'}",
        ]
        rank_name = {3: "KRITIS", 2: "TINGGI", 1: "SEDANG", 0: "BERSIH"}[res["worst_rank"]]
        rank_color = {3: "bold red", 2: "red", 1: "yellow", 0: "green"}[res["worst_rank"]]
        verdict = (f"{res['finding_count']} temuan perlu ditinjau" if res["finding_count"]
                   else "Tidak ada indikator kompromi ditemukan")
        nothing_read = res["files_scanned"] == 0 and not res["persistence_checked"]
        if nothing_read and not res["finding_count"]:
            rank_name, rank_color = "TIDAK TERPERIKSA", "yellow"
            verdict = "0 berkas dibaca — hasil ini tidak berarti host bersih"
        if res["read_errors"] and res["worst_rank"] == 0:
            verdict += " (sebagian berkas tak terbaca — coba sudo)"
        self.console.print(Panel(
            "\n".join(head) + f"\n\n[{rank_color}]TINGKAT TERTINGGI: {rank_name}  ·  {verdict}[/{rank_color}]",
            title="[bold]HOST COMPROMISE SCANNER[/bold]",
            subtitle="[dim]analisis pasif — hanya membaca berkas[/dim]",
            border_style=rank_color.split()[-1], box=box.ROUNDED))

        findings = res["findings"]
        if not findings:
            self.console.print("  [green]\\[+] Bersih sejauh yang terbaca.[/green]\n")
            return

        sev_style = {"critical": "bold red", "high": "red", "medium": "yellow",
                     "low": "cyan", "info": "dim"}
        cat_name = {"webshell": "web shell", "persistence": "persistence", "permission": "izin"}

        def _page(start, end, page, pages):
            t = Table(box=box.SIMPLE_HEAVY, header_style="bold cyan",
                      title="[bold]Temuan[/bold]", title_justify="left")
            t.add_column("Severitas", width=9)
            t.add_column("Jenis", width=11)
            t.add_column("Lokasi & keterangan", overflow="fold")
            for fnd in findings[start:end]:
                st = sev_style.get(fnd["severity"], "white")
                loc = escape(fnd["path"])
                if fnd.get("line"):
                    loc += f"[dim]:{fnd['line']}[/dim]"
                body = f"{loc}\n[dim]{escape(fnd['detail'])}[/dim]"
                if fnd.get("excerpt"):
                    body += f"\n[dim]  › {escape(fnd['excerpt'][:120])}[/dim]"
                t.add_row(f"[{st}]{fnd['severity'].upper()}[/{st}]",
                          cat_name.get(fnd["category"], fnd["category"]), body)
            return t

        paginate(self.console, len(findings), _page, page_size=8,
                 label="temuan", done_label="lanjut")
        self.console.print()


def _excerpt(text: str, pos: int, width: int = 120) -> str:
    start = text.rfind("\n", 0, pos) + 1
    end = text.find("\n", pos)
    if end == -1:
        end = len(text)
    line = text[start:end].strip()
    return line[:width]


def _is_private(ip: str) -> bool:
    import ipaddress
    try:
        return ipaddress.ip_address(ip).is_private
    except ValueError:
        return True


# ──────────────────────────────────────────────────────────────
#  Laporan
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
    jpath = Path(out_dir) / f"freease_hostcheck_{ts}.json"
    try:
        jpath.write_text(json.dumps(res, indent=2, ensure_ascii=False), "utf-8")
        console.print(f"  [dim]JSON:[/dim] {escape(str(jpath))}")
    except OSError as e:
        console.print(f"  [yellow]\\[!] Gagal menulis JSON: {escape(str(e))}[/yellow]")

    color = {"critical": "#e5484d", "high": "#f5a623", "medium": "#d29922",
             "low": "#58a6ff", "info": "#8b949e"}
    rows = []
    for f in res["findings"]:
        loc = _esc(f["path"]) + (f":{f['line']}" if f.get("line") else "")
        exc = f"<pre>{_esc(f['excerpt'])}</pre>" if f.get("excerpt") else ""
        rows.append(
            f"<tr><td><b style='color:{color.get(f['severity'], '#ccc')}'>{f['severity'].upper()}</b></td>"
            f"<td>{_esc(f['category'])}</td><td><code>{loc}</code><div class=d>{_esc(f['detail'])}</div>{exc}</td></tr>")
    html = (
        "<!doctype html><meta charset=utf-8><title>freease — Host Compromise Scanner</title>"
        "<style>body{font:14px/1.6 system-ui,sans-serif;max-width:1000px;margin:2rem auto;"
        "padding:0 1rem;background:#0f1115;color:#e6e6e6}h1{color:#6cc}table{border-collapse:collapse;"
        "width:100%}td{border-bottom:1px solid #222;padding:.5rem;vertical-align:top}"
        "code{color:#9cf;word-break:break-all}.d{color:#8b949e;font-size:.85rem;margin-top:.2rem}"
        "pre{background:#161a22;border:1px solid #2a2f3a;border-radius:6px;padding:.4rem;"
        "overflow-x:auto;font-size:.8rem;color:#ddd;margin:.3rem 0 0}</style>"
        f"<h1>freease — Host Compromise Scanner</h1>"
        f"<p style='color:#8b949e'>{datetime.now():%Y-%m-%d %H:%M:%S} · "
        f"{res['files_scanned']} berkas dibaca · {res['finding_count']} temuan</p>"
        + (f"<table>{''.join(rows)}</table>" if rows else "<p>Tidak ada temuan.</p>"))
    hpath = Path(out_dir) / f"freease_hostcheck_{ts}.html"
    try:
        hpath.write_text(html, "utf-8")
        console.print(f"  [dim]HTML:[/dim] {escape(str(hpath))}")
    except OSError:
        pass


# ──────────────────────────────────────────────────────────────
#  CLI
# ──────────────────────────────────────────────────────────────
def add_hostcheck_args(p) -> None:
    g = p.add_argument_group("Host Compromise Scanner (blue team)")
    g.add_argument("-C", "--host-check", dest="hostcheck_targets", nargs="+", default=None,
                   metavar="PATH",
                   help="Pindai web shell di PATH (atau 'auto' untuk web root umum)")
    g.add_argument("--persistence", dest="hostcheck_persistence", action="store_true",
                   help="Ikut periksa persistence: cron, systemd, authorized_keys, rc, ld.so.preload")
    g.add_argument("--recent", dest="hostcheck_recent", type=int, default=None, metavar="HARI",
                   help="Tandai/utamakan berkas yang berubah dalam N hari terakhir")
    g.add_argument("--max-size", dest="hostcheck_maxsize", type=float, default=5.0, metavar="MB",
                   help="Lewati berkas lebih besar dari MB ini (default: 5)")


def run_hostcheck_from_args(args, console: Optional[Console] = None) -> dict:
    console = console or Console()
    res = HostChecker(
        list(args.hostcheck_targets),
        console=console,
        recent_days=getattr(args, "hostcheck_recent", None),
        persistence=getattr(args, "hostcheck_persistence", False),
        max_size_mb=getattr(args, "hostcheck_maxsize", 5.0),
        enrich=getattr(args, "log_enrich", False),
        abuseipdb_key=getattr(args, "abuseipdb_key", None),
    ).run()
    out_dir = getattr(args, "output", None)
    if out_dir and not getattr(args, "no_export", False) and not res.get("error"):
        write_reports(res, out_dir, console)
    return res


def main() -> None:
    p = argparse.ArgumentParser(
        prog="freease_hostcheck",
        description="freease — Host Compromise Scanner (web shell + persistence, blue team)")
    p.add_argument("-o", "--output", default="./freease_output",
                   help="Folder laporan JSON/HTML (default: ./freease_output)")
    p.add_argument("--no-export", action="store_true", help="Jangan tulis laporan")
    p.add_argument("--enrich", dest="log_enrich", action="store_true",
                   help="Cek reputasi IP yang muncul di temuan")
    p.add_argument("--abuseipdb-key", dest="abuseipdb_key",
                   default=os.environ.get("ABUSEIPDB_API_KEY"))
    add_hostcheck_args(p)
    args = p.parse_args()
    if not args.hostcheck_targets:
        p.error("masukkan path yang dipindai (mis. -C /var/www atau -C auto)")
    res = run_hostcheck_from_args(args)
    if res.get("error"):
        sys.exit(1)
    if res.get("not_inspected"):
        sys.exit(3)          # tidak ada berkas terbaca: BUKAN berarti host bersih
    sys.exit(2 if res.get("worst_rank") == 3 else 1 if res.get("finding_count") else 0)


if __name__ == "__main__":
    main()
