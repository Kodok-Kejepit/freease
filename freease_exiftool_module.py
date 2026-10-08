#!/usr/bin/env python3
"""
freease — Modul 9: ExifTool Metadata Extractor
by: Kodok-Kejepit

Ekstraksi metadata file lokal atau URL remote (EXIF, GPS → Google Maps, IPTC,
XMP, metadata dokumen) lewat binary ExifTool, plus penilaian privasi: data apa
saja di file ini yang bisa membocorkan lokasi, identitas, atau perangkat.

Dipakai oleh freease.py (-x FILE/URL) atau langsung:
  python freease_exiftool_module.py foto.jpg
  python freease_exiftool_module.py https://example.com/dokumen.pdf --raw

Butuh ExifTool:  sudo apt install libimage-exiftool-perl  ·  pkg install exiftool
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Optional

from rich import box
from rich.markup import escape
from rich.table import Table

BROWSER_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0 Safari/537.36"
)


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
        ("Artist",           "Artist"),
        ("OwnerName",        "Owner Name"),
        ("SerialNumber",     "Serial Number"),
        ("BodySerialNumber", "Body Serial"),
        ("LensSerialNumber", "Lens Serial"),
        ("HostComputer",     "Host Computer"),
        ("LastModifiedBy",   "Last Modified By"),
        ("Company",          "Company"),
        ("CreatorTool",      "Creator Tool"),
        ("GPSDateTime",      "GPS Date/Time"),
        ("OffsetTimeOriginal", "Timezone Offset"),
        ("UserComment",      "User Comment"),
    ]

    # Field yang membocorkan identitas / perangkat / lokasi → (kategori, saran)
    _PRIVACY_FIELDS: dict = {
        "GPSLatitude":      ("lokasi", "Koordinat GPS — lokasi pengambilan bisa dilacak"),
        "GPSPosition":      ("lokasi", "Koordinat GPS — lokasi pengambilan bisa dilacak"),
        "GPSDateTime":      ("lokasi", "Waktu GPS — kapan pemilik berada di lokasi itu"),
        "Artist":           ("identitas", "Nama pembuat tertanam di file"),
        "Author":           ("identitas", "Nama penulis dokumen"),
        "Creator":          ("identitas", "Nama pembuat dokumen"),
        "OwnerName":        ("identitas", "Nama pemilik kamera"),
        "LastModifiedBy":   ("identitas", "Akun terakhir yang mengedit dokumen"),
        "Company":          ("identitas", "Nama organisasi"),
        "Copyright":        ("identitas", "Nama pemegang hak cipta"),
        "SerialNumber":     ("perangkat", "Nomor seri perangkat — mengaitkan semua foto dari kamera sama"),
        "BodySerialNumber": ("perangkat", "Nomor seri bodi kamera"),
        "LensSerialNumber": ("perangkat", "Nomor seri lensa"),
        "HostComputer":     ("perangkat", "Nama/model komputer pembuat"),
        "Model":            ("perangkat", "Model perangkat/HP"),
        "Software":         ("perangkat", "Software & versi yang dipakai"),
        "OffsetTimeOriginal": ("lokasi", "Zona waktu — memperkirakan wilayah"),
    }

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
            "privacy":      [],
            "privacy_risk": None,
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
            self._assess_privacy(raw)

        finally:
            self._cleanup()

        return self.results

    def print_results(self, console_obj) -> None:
        """Cetak tabel Rich ke console."""
        r = self.results

        if r["error"]:
            console_obj.print(f"  [bold red]\\[x] ERROR:[/bold red] [red]{escape(r['error'])}[/red]")
            console_obj.print()
            return

        filtered: dict = r.get("filtered", {})
        if not filtered:
            console_obj.print("  [yellow]\\[!] Tidak ada metadata yang dapat diekstrak.[/yellow]")
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
                title="[bold yellow]GPS INTELLIGENCE — LOCATION DETECTED[/bold yellow]",
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

        if r.get("privacy"):
            console_obj.print()
            pc = {"HIGH": "bold red", "MEDIUM": "yellow", "LOW": "cyan"}.get(
                r.get("privacy_risk"), "dim")
            pt = Table(
                title=f"[{pc}]Risiko Privasi: {r.get('privacy_risk')}[/{pc}]",
                box=box.ROUNDED, style="dim", min_width=72,
            )
            pt.add_column("Kategori", style="bold", width=10)
            pt.add_column("Field", style="cyan", width=18)
            pt.add_column("Keterangan", overflow="fold")
            for it in r["privacy"]:
                pt.add_row(it["category"], it["field"], escape(it["note"]))
            console_obj.print(pt)
            console_obj.print(
                "  [dim]Hapus metadata sebelum membagikan file: "
                "[bold]exiftool -all= -overwrite_original FILE[/bold][/dim]")

        total_raw   = len(r.get("raw_metadata", {}))
        total_shown = len(filtered)
        console_obj.print(
            f"\n  [dim]Ditampilkan [bold]{total_shown}[/bold] field krusial "
            f"dari [bold]{total_raw}[/bold] total metadata.[/dim]"
        )
        console_obj.print()

    # ── Private helpers ───────────────────────────────────────

    def _assess_privacy(self, raw: dict) -> None:
        """Daftar data sensitif yang ikut tersebar kalau file ini dibagikan apa adanya."""
        items, seen = [], set()
        for key, (cat, why) in self._PRIVACY_FIELDS.items():
            val = raw.get(key)
            if val is None or str(val).strip() in ("", "0", "Unknown"):
                continue
            if key == "GPSPosition" and self.results.get("gps_coords") is None:
                continue
            if why in seen:
                continue
            seen.add(why)
            items.append({"field": key, "category": cat, "value": str(val)[:80], "note": why})
        self.results["privacy"] = items
        cats = {i["category"] for i in items}
        self.results["privacy_risk"] = (
            "HIGH" if "lokasi" in cats and self.results.get("gps_coords") else
            "MEDIUM" if cats & {"identitas", "perangkat"} and len(items) >= 2 else
            "LOW" if items else "NONE")

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
                declared = resp.headers.get("Content-Length")
                if declared and declared.isdigit() and int(declared) > self.MAX_DOWNLOAD_BYTES:
                    self.results["error"] = (
                        f"File remote terlalu besar ({int(declared) // 1024 // 1024} MB, "
                        f"batas {self.MAX_DOWNLOAD_BYTES // 1024 // 1024} MB) — dibatalkan"
                    )
                    return None
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
                ["exiftool", "-json", "-n", "-api", "LargeFileSupport=1", str(file_path)],
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


def main() -> None:
    import sys

    from rich.console import Console

    from freease_version import __version__

    console = Console()
    args = [a for a in sys.argv[1:] if a != "--raw"]
    if not args:
        console.print("[yellow]Usage: python freease_exiftool_module.py <file_or_url> [--raw][/yellow]")
        sys.exit(0)

    console.print(f"\n[bold cyan]freease v{__version__}[/bold cyan] "
                  "[dim]— ExifTool Metadata Extractor[/dim]\n")
    extractor = ExifToolExtractor(args[0])
    result = extractor.run()
    extractor.print_results(console)
    if "--raw" in sys.argv:
        console.print_json(json.dumps(result, default=str, indent=2))
    sys.exit(1 if result.get("error") else 0)


if __name__ == "__main__":
    main()
