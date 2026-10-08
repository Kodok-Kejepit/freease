"""
╔══════════════════════════════════════════════════════════════╗
║   freease v2.0.0 — MODULE 7: EXIFTOOL METADATA EXTRACTOR    ║
║   by: Kodok-Kejepit                                          ║
║   Integrate ke dalam class FreeaseEngine & build_parser()   ║
╚══════════════════════════════════════════════════════════════╝

INTEGRASI CEPAT:
  1. Tambahkan import di bagian atas freease.py (lihat IMPORTS di bawah)
  2. Paste class ExifToolExtractor ke dalam freease.py
  3. Tambahkan argumen CLI ke build_parser()  (lihat ARGPARSE PATCH)
  4. Tambahkan pemanggilan di FreeaseEngine.run() (lihat ENGINE PATCH)
  5. Tambahkan _print_exiftool() ke FreeaseEngine  (sudah include di class)
"""

# ──────────────────────────────────────────────────────────────
#  IMPORTS TAMBAHAN (merge ke bagian import freease.py)
# ──────────────────────────────────────────────────────────────
import os
import re
import json
import shutil
import subprocess
import tempfile
import urllib.request
import urllib.parse
from pathlib import Path
from typing import Optional

# rich sudah di-import di freease.py (Console, Table, box, Panel, Progress, dll)
# Tidak perlu import ulang, cukup reuse `console` yang sudah ada.


# ══════════════════════════════════════════════════════════════
#  MODULE 7: EXIFTOOL METADATA EXTRACTOR
# ══════════════════════════════════════════════════════════════

class ExifToolExtractor:
    """
    Wrapper profesional untuk binary ExifTool.
    Mendukung file lokal maupun URL remote (auto-download → extract → cleanup).
    Menampilkan metadata krusial dalam tabel Rich yang konsisten dengan modul lain.
    Me-return dictionary lengkap untuk integrasi ke laporan JSON/HTML.
    """

    # Field yang diprioritaskan untuk ditampilkan (urutan tampilan)
    _PRIORITY_FIELDS: list[tuple[str, str]] = [
        # (exiftool_key,          label_tampilan)
        ("FileName",              "File Name"),
        ("FileSize",              "File Size"),
        ("FileType",              "File Type"),
        ("MIMEType",              "MIME Type"),
        ("ImageWidth",            "Image Width"),
        ("ImageHeight",           "Image Height"),
        ("ColorSpace",            "Color Space"),
        ("BitDepth",              "Bit Depth"),
        ("Compression",           "Compression"),
        ("CreateDate",            "Create Date"),
        ("DateTimeOriginal",      "Date Time Original"),
        ("ModifyDate",            "Modify Date"),
        ("FileModifyDate",        "File Modify Date"),
        ("Software",              "Software / Creator"),
        ("Creator",               "Creator"),
        ("Author",                "Author"),
        ("Producer",              "Producer"),
        ("Make",                  "Camera Make"),
        ("Model",                 "Camera Model"),
        ("LensModel",             "Lens Model"),
        ("ExposureTime",          "Exposure Time"),
        ("FNumber",               "F-Number"),
        ("ISO",                   "ISO Speed"),
        ("FocalLength",           "Focal Length"),
        ("Flash",                 "Flash"),
        ("GPSLatitude",           "GPS Latitude"),
        ("GPSLongitude",          "GPS Longitude"),
        ("GPSAltitude",           "GPS Altitude"),
        ("GPSLatitudeRef",        "GPS Lat Ref"),
        ("GPSLongitudeRef",       "GPS Lon Ref"),
        ("GPSPosition",           "GPS Position"),
        ("Comment",               "Comment"),
        ("Description",           "Description"),
        ("Title",                 "Title"),
        ("Keywords",              "Keywords"),
        ("Copyright",             "Copyright"),
        ("XMPToolkit",            "XMP Toolkit"),
        ("DocumentID",            "Document ID"),
        ("InstanceID",            "Instance ID"),
        ("PageCount",             "Page Count"),
        ("Language",              "Language"),
    ]

    _PRIORITY_KEYS: set[str] = {k for k, _ in _PRIORITY_FIELDS}

    def __init__(self, target: str):
        """
        target : path file lokal ATAU URL (http/https).
        """
        self.target      = target
        self.is_url      = target.lower().startswith(("http://", "https://"))
        self._tmp_file   : Optional[Path] = None
        self.results     : dict = {
            "target":       target,
            "type":         "url" if self.is_url else "local",
            "error":        None,
            "raw_metadata": {},
            "filtered":     {},
            "gps_coords":   None,
            "maps_link":    None,
        }

    # ──────────────────────────────────────────────────────────
    #  PUBLIC API
    # ──────────────────────────────────────────────────────────

    def run(self) -> dict:
        """
        Entry point utama. Panggil ini, lalu tampilkan via print_results() atau
        ambil self.results untuk laporan.
        """
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
                return self.results          # error sudah diset di _resolve_file

            raw = self._run_exiftool(file_path)
            if raw is None:
                return self.results          # error sudah diset di _run_exiftool

            self.results["raw_metadata"] = raw
            self.results["filtered"]     = self._filter_metadata(raw)
            self._extract_gps(raw)

        finally:
            self._cleanup()

        return self.results

    def print_results(self, console_obj) -> None:
        """
        Cetak tabel Rich ke console. Terima console dari freease.py agar tidak
        instantiate ulang.
        """
        r = self.results

        # ── Header section ──────────────────────────────────────
        console_obj.print()
        console_obj.rule(
            "[bold cyan]MODULE 7 — EXIFTOOL METADATA EXTRACTOR[/bold cyan]",
            style="dim cyan"
        )
        console_obj.print(
            f"  [dim]Target:[/dim] [bold white]{r['target']}[/bold white]"
            f"   [dim]Type:[/dim] [cyan]{r['type'].upper()}[/cyan]"
        )
        console_obj.print()

        # ── Error state ──────────────────────────────────────────
        if r["error"]:
            console_obj.print(f"  [bold red]✗ ERROR:[/bold red] [red]{r['error']}[/red]")
            console_obj.print()
            return

        filtered: dict = r.get("filtered", {})
        if not filtered:
            console_obj.print("  [yellow]⚠ Tidak ada metadata yang dapat diekstrak.[/yellow]")
            console_obj.print()
            return

        # ── Metadata table ───────────────────────────────────────
        t = Table(
            title="[bold cyan]Metadata Summary[/bold cyan]",
            box=box.ROUNDED,
            style="dim",
            show_header=True,
            header_style="bold cyan",
            min_width=72,
        )
        t.add_column("Field",  style="cyan",        width=26, no_wrap=True)
        t.add_column("Value",  style="bold white",  width=48, overflow="fold")

        # Render field sesuai urutan prioritas
        for key, label in self._PRIORITY_FIELDS:
            if key not in filtered:
                continue
            val = str(filtered[key])

            # GPS field — beri highlight khusus
            if key in ("GPSLatitude", "GPSLongitude", "GPSPosition"):
                t.add_row(
                    f"[bold yellow]{label}[/bold yellow]",
                    f"[bold yellow]{val}[/bold yellow]"
                )
            else:
                t.add_row(label, val)

        console_obj.print(t)

        # ── GPS Intelligence ─────────────────────────────────────
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
            gps_table.add_row("Latitude",   str(lat))
            gps_table.add_row("Longitude",  str(lon))
            gps_table.add_row(
                "Google Maps",
                f"[bold underline cyan]{r['maps_link']}[/bold underline cyan]"
            )
            console_obj.print(gps_table)

        # ── Stats footer ─────────────────────────────────────────
        total_raw  = len(r.get("raw_metadata", {}))
        total_shown = len(filtered)
        console_obj.print(
            f"\n  [dim]Ditampilkan [bold]{total_shown}[/bold] field krusial "
            f"dari [bold]{total_raw}[/bold] total metadata.[/dim]"
        )
        console_obj.print()

    # ──────────────────────────────────────────────────────────
    #  PRIVATE HELPERS
    # ──────────────────────────────────────────────────────────

    def _check_exiftool(self) -> bool:
        """Verifikasi binary exiftool tersedia di PATH."""
        return shutil.which("exiftool") is not None

    def _resolve_file(self) -> Optional[Path]:
        """
        Return path ke file yang siap diproses.
        Jika URL → download ke /tmp/, set self._tmp_file untuk cleanup.
        Jika lokal → validasi exist, return Path-nya.
        """
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
        """
        Download file dari URL ke direktori temp /tmp/freease_exif_*.
        Return path file temp, atau None jika gagal.
        """
        try:
            # Tentukan ekstensi dari URL
            parsed  = urllib.parse.urlparse(url)
            url_path = parsed.path.rstrip("/")
            ext     = Path(url_path).suffix[:10] if Path(url_path).suffix else ".tmp"

            # Buat file temp
            fd, tmp_path = tempfile.mkstemp(
                suffix=ext,
                prefix="freease_exif_",
                dir="/tmp"
            )
            os.close(fd)
            self._tmp_file = Path(tmp_path)

            # Download dengan timeout & user-agent agar tidak diblok
            req = urllib.request.Request(
                url,
                headers={"User-Agent": "Mozilla/5.0 (freease/2.0.0 OSINT-Tool)"}
            )
            with urllib.request.urlopen(req, timeout=30) as resp, \
                 open(tmp_path, "wb") as out:
                chunk_size = 8192
                while True:
                    chunk = resp.read(chunk_size)
                    if not chunk:
                        break
                    out.write(chunk)

            return self._tmp_file

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
        """
        Jalankan: exiftool -json -n <file>
        -n = output numerik untuk GPS (memudahkan parsing koordinat desimal).
        Return dict metadata, atau None jika gagal.
        """
        try:
            proc = subprocess.run(
                ["exiftool", "-json", "-n", str(file_path)],
                capture_output=True,
                text=True,
                timeout=30,
            )

            if proc.returncode not in (0, 1):
                # ExifTool return 1 jika ada warning tapi masih output data
                stderr_msg = proc.stderr.strip()[:200] if proc.stderr else "Unknown error"
                self.results["error"] = f"ExifTool error (rc={proc.returncode}): {stderr_msg}"
                return None

            if not proc.stdout.strip():
                self.results["error"] = "ExifTool tidak menghasilkan output. File mungkin rusak atau tidak didukung."
                return None

            parsed = json.loads(proc.stdout)
            if not parsed or not isinstance(parsed, list):
                self.results["error"] = "Output ExifTool kosong atau format tidak dikenal."
                return None

            return parsed[0]  # ExifTool selalu return list, ambil elemen pertama

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
        """
        Ambil field-field krusial saja dari raw metadata.
        Hapus field ExifTool internal (SourceFile, ExifToolVersion, dll).
        """
        filtered: dict = {}
        for key, _label in self._PRIORITY_FIELDS:
            val = raw.get(key)
            if val is not None and str(val).strip() not in ("", "0", "Unknown"):
                filtered[key] = val
        return filtered

    def _extract_gps(self, raw: dict) -> None:
        """
        Ekstrak koordinat GPS dan generate Google Maps link.
        ExifTool dengan flag -n mengembalikan koordinat dalam format desimal murni.
        """
        lat = raw.get("GPSLatitude")
        lon = raw.get("GPSLongitude")

        # Fallback: coba parse dari GPSPosition string jika ada
        if (lat is None or lon is None) and raw.get("GPSPosition"):
            lat, lon = self._parse_gps_position(raw["GPSPosition"])

        if lat is None or lon is None:
            return

        try:
            lat_f = float(lat)
            lon_f = float(lon)

            # Koreksi tanda berdasarkan GPSLatitudeRef / GPSLongitudeRef
            lat_ref = raw.get("GPSLatitudeRef", "N")
            lon_ref = raw.get("GPSLongitudeRef", "E")
            if isinstance(lat_ref, str) and lat_ref.upper() == "S":
                lat_f = -abs(lat_f)
            if isinstance(lon_ref, str) and lon_ref.upper() == "W":
                lon_f = -abs(lon_f)

            self.results["gps_coords"] = (round(lat_f, 7), round(lon_f, 7))
            self.results["maps_link"]  = (
                f"https://www.google.com/maps?q={lat_f:.7f},{lon_f:.7f}"
            )

            # Inject ke filtered juga agar masuk laporan
            self.results["filtered"]["GPS_MapsLink"] = self.results["maps_link"]

        except (ValueError, TypeError):
            pass

    def _parse_gps_position(self, gps_str: str) -> tuple[Optional[float], Optional[float]]:
        """
        Parse string GPS Position seperti '6.123456, 106.654321' atau
        '6 deg 7\' 24.44" N, 106 deg 39\' 15.56" E' ke tuple float.
        """
        # Format 1: desimal langsung "lat, lon"
        m = re.match(
            r"^\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*$",
            gps_str.strip()
        )
        if m:
            return float(m.group(1)), float(m.group(2))

        # Format 2: DMS dengan arah — "6 deg 7' 24.44" N, 106 deg 39' 15.56" E"
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
        """Hapus file temporary jika ada."""
        if self._tmp_file and self._tmp_file.exists():
            try:
                self._tmp_file.unlink()
            except Exception:
                pass


# ══════════════════════════════════════════════════════════════
#  PATCH: ARGPARSE — tambahkan ke fungsi build_parser()
# ══════════════════════════════════════════════════════════════
#
# Di dalam fungsi build_parser(), tambahkan baris ini sebelum `return p`:
#
#   p.add_argument(
#       "-x", "--exif",
#       dest="exif_target",
#       metavar="FILE_OR_URL",
#       help="ExifTool metadata extractor (file lokal atau URL remote)"
#   )
#
# ══════════════════════════════════════════════════════════════


# ══════════════════════════════════════════════════════════════
#  PATCH: FreeaseEngine.run() — tambahkan blok ini
# ══════════════════════════════════════════════════════════════
#
# Di dalam method async def run(self) pada class FreeaseEngine,
# tambahkan blok berikut (sebelum atau sesudah blok modul lain):
#
#   if self.args.exif_target:
#       console.rule("[bold cyan]MODULE 7 · ExifTool Metadata Extractor[/bold cyan]", style="dim cyan")
#       extractor = ExifToolExtractor(self.args.exif_target)
#       exif_result = extractor.run()
#       extractor.print_results(console)
#       self.all_results["exif_metadata"] = exif_result
#
# ══════════════════════════════════════════════════════════════


# ══════════════════════════════════════════════════════════════
#  PATCH: Update deskripsi modul di _BANNER_ART / build_parser epilog
# ══════════════════════════════════════════════════════════════
#
# Di epilog build_parser(), tambahkan baris:
#   -x   ExifTool Metadata Extractor (file lokal / URL)
#
# Di modul docstring atas file, tambahkan:
#   7. ExifToolExtractor — File/URL metadata (EXIF, GPS → Maps, IPTC, XMP)
#
# ══════════════════════════════════════════════════════════════


# ══════════════════════════════════════════════════════════════
#  STANDALONE TEST — hapus/comment-out sebelum integrasi
# ══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    from rich.console import Console
    from rich.table   import Table
    from rich         import box

    console = Console()

    import sys
    if len(sys.argv) < 2:
        console.print("[yellow]Usage: python freease_exiftool_module.py <file_or_url>[/yellow]")
        console.print("[dim]Contoh:[/dim]")
        console.print("  python freease_exiftool_module.py /path/to/photo.jpg")
        console.print("  python freease_exiftool_module.py https://example.com/document.pdf")
        sys.exit(0)

    target = sys.argv[1]
    console.print(f"\n[bold cyan]freease v2.0.0[/bold cyan] [dim]— ExifTool Metadata Extractor test[/dim]\n")

    extractor = ExifToolExtractor(target)
    result    = extractor.run()
    extractor.print_results(console)

    # Tampilkan juga raw JSON untuk debug
    if "--raw" in sys.argv:
        console.print("\n[dim]── RAW RESULT DICT ──[/dim]")
        console.print_json(json.dumps(result, default=str, indent=2))
