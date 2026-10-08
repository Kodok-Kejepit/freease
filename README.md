# 🐸 freease — Attack Surface Management Tool
**by: Kodok-Kejepit** | v2.2.0

```
  __  __               
 / _|_ _ ___ __ _ ___ ___ 
|  _| '_/ -_) _` (_-</ -_)
|_| |_| \___\__,_/__/\___|
  Attack Surface Management Tool
```

> **⚠️ DISCLAIMER:** Tool ini dirancang **khusus untuk riset akademis dan keamanan defensif**.
> Seluruh data yang dikumpulkan bersumber dari **informasi publik** (DNS publik, Certificate Transparency Logs, HTTP headers, dll).
> **Gunakan HANYA pada domain/aset yang Anda miliki** atau memiliki izin eksplisit tertulis.
> Penyalahgunaan tool ini merupakan tanggung jawab pengguna sepenuhnya.

---

## 📦 Instalasi

```bash
# Clone / download project
cd freease/

# Install dependencies
pip install -r requirements.txt

# Jalankan
python freease.py --help
```

**Kebutuhan:** Python 3.9+

Tool sistem tambahan (opsional, sesuai modul yang dipakai):

| Tool | Untuk | Install |
|------|-------|---------|
| `ffmpeg` | YouTube merge (≥720p) & mp3; metadata player (`ffprobe`) | `sudo apt install ffmpeg` |
| `mpv` | Media player `-p` (direkomendasikan; video di terminal `--vo=tct`) | `sudo apt install mpv` / `pkg install mpv` |
| `mpg123` | Pemutar audio ringan alternatif untuk `-p` | `sudo apt install mpg123` |
| `chafa` / `timg` | Render video ke ANSI saat tanpa display (Termux) | `sudo apt install chafa` |
| `node` / `deno` | YouTube: membuka semua format/resolusi | `sudo apt install nodejs` |
| `exiftool` | Modul `-x` | `sudo apt install libimage-exiftool-perl` |

> Modul **URL Safety Scanner (`-s`)** tidak butuh tool tambahan apa pun — cukup Python standar.

---

## 🚀 Penggunaan

### Sintaks Dasar
```
python freease.py [opsi] -o ./output
```

### Opsi Tersedia

| Flag | Deskripsi |
|------|-----------|
| `-d DOMAIN` | Domain target untuk Network Recon |
| `-u USERNAME` | Username untuk dicek di 20 platform |
| `-e EMAIL[,EMAIL2]` | Email untuk Data Breach Check (pisah koma) |
| `-i IP[,IP/DOMAIN]` | IP Reputation (AbuseIPDB · OTX · ip-api) |
| `-x FILE/URL` | ExifTool metadata extractor |
| `-y URL` | Download video/musik dari YouTube / YouTube Music |
| `-s URL` | URL Safety Scanner — deteksi phishing / tautan berbahaya (pasif) |
| `--scan-list FILE` | Scan banyak URL sekaligus dari file (satu URL per baris) |
| `-p SRC...` | Media Player — putar audio/video (file/folder/glob/URL) di terminal |
| `--hibp-key KEY` | HaveIBeenPwned API key |
| `--abuseipdb-key KEY` | AbuseIPDB API key (gratis) |
| `--skip-portscan` | Lewati port scanner |
| `-o DIR` | Direktori output laporan JSON & HTML |
| `--no-export` | Tampilkan di terminal saja, tanpa export |

---

## 🧩 Contoh Penggunaan

### 1️⃣ Network Recon Saja
```bash
python freease.py -d example.com -o ./laporan
```
Output: DNS records, subdomain dari crt.sh, web tech, SSL info, security headers.

### 2️⃣ Username Checker Saja
```bash
python freease.py -u johndoe -o ./laporan
```
Memeriksa username `johndoe` di 20 platform: GitHub, GitLab, Instagram, Twitter/X, Reddit, TikTok, Medium, dll.

### 3️⃣ Data Breach Check (Butuh API Key)
```bash
python freease.py -e admin@example.com,info@example.com --hibp-key APIKEY -o ./laporan
```
HIBP API key **gratis** tersedia di: https://haveibeenpwned.com/API/Key

### 4️⃣ Scan Lengkap Semua Modul
```bash
python freease.py \
  -d example.com \
  -u johndoe \
  -e admin@example.com,cto@example.com \
  --hibp-key YOUR_HIBP_KEY \
  -o ./laporan_asm
```

### 5️⃣ Tanpa Export (hanya tampilan terminal)
```bash
python freease.py -d example.com --no-export
```

### 6️⃣ YouTube / YouTube Music Downloader
```bash
# Interaktif: masukkan link → pilih Video/Musik → pilih kualitas dari daftar
python freease.py -y "https://youtu.be/VIDEO_ID"

# Langsung tanpa ditanya
python freease.py -y "https://youtu.be/VIDEO_ID" --yt-mode video --yt-quality 4k
python freease.py -y "https://youtu.be/VIDEO_ID" --yt-mode video --yt-quality 720
python freease.py -y "https://music.youtube.com/watch?v=ID" --yt-mode musik
python freease.py -y "https://music.youtube.com/watch?v=ID" --yt-mode musik --yt-audio-format m4a --yt-bitrate 320

# Seluruh playlist / album
python freease.py -y "https://youtube.com/playlist?list=ID" --yt-mode musik --yt-playlist
```

| Flag | Deskripsi |
|------|-----------|
| `--yt-mode` | `video` atau `musik`/`audio` (kalau kosong: ditanya) |
| `--yt-quality` | `4k` (2160p), `2k` (1440p), `hd`/`1080`, `720`, `480`, `360`, `240`, `144`, `best` |
| `--yt-audio-format` | `mp3` (default), `m4a`, `opus`, `flac`, `wav` |
| `--yt-bitrate` | `128`, `192` (default), `256`, `320` kbps |
| `--yt-container` | `mp4` (default) atau `mkv` |
| `--yt-playlist` | Download semua item playlist/album |
| `--yt-dir` | Folder hasil (default `./freease_downloads`) |

Kalau resolusi yang diminta tidak tersedia, otomatis turun ke resolusi terdekat di bawahnya.
Musik otomatis diberi tag judul/artis dan cover. Modul ini juga bisa dijalankan sendiri:
`python freease_youtube.py URL`.

> Download hanya konten milik sendiri atau yang memang diizinkan untuk diunduh.

### 7️⃣ URL Safety Scanner (deteksi phishing / tautan berbahaya)
```bash
# Cek satu URL sebelum Anda membukanya
python freease.py -s "https://contoh-mencurigakan.tld/login"

# Tambah cek intel ancaman abuse.ch URLhaus (gratis, tanpa key)
python freease.py -s "http://bit.ly/xxxx" --urlhaus

# Hanya analisis leksikal, tanpa menyentuh jaringan sama sekali
python freease.py -s "https://g00gle-verify.xyz/login" --offline

# Batch: periksa banyak URL dari file, simpan laporan JSON/HTML
python freease.py --scan-list daftar_url.txt -o ./laporan

# Google Safe Browsing (opsional, pakai API key gratis Google)
python freease.py -s "https://contoh.tld" --safebrowsing-key YOUR_KEY
```

| Flag | Deskripsi |
|------|-----------|
| `--offline` | Hanya heuristik leksikal, tanpa koneksi jaringan |
| `--urlhaus` | Cek URL di abuse.ch URLhaus (gratis) |
| `--safebrowsing-key KEY` | Google Safe Browsing v4 (opsional) |
| `--scan-timeout SEC` | Timeout per permintaan jaringan (default 10) |
| `--max-redirects N` | Batas lompatan redirect yang ditelusuri (default 10) |
| `--scan-list FILE` | Daftar URL (satu per baris) untuk batch |

Scanner bersifat **pasif**: freease hanya menganalisis struktur URL, menelusuri
jejak redirect lewat `HEAD`, memeriksa sertifikat TLS, dan umur domain (RDAP) —
**target tidak pernah dibuka, dirender, atau dieksekusi.** Hasil diberi skor
risiko 0–100 dan verdict **AMAN / MENCURIGAKAN / BERBAHAYA**. Exit code
mengikuti risiko tertinggi: `0` aman · `1` mencurigakan · `2` berbahaya — berguna
untuk scripting. Bisa dijalankan sendiri: `python freease_scan.py URL`.

### 8️⃣ Media Player (audio/video di terminal)
```bash
# Putar satu file audio
python freease.py -p lagu.mp3

# Putar seluruh folder sebagai playlist, diacak dan diulang
python freease.py -p ./Music --shuffle --loop

# Putar video (mpv merender langsung di terminal bila tanpa display)
python freease.py -p film.mkv

# Ambil audionya saja dari sebuah video
python freease.py -p film.mkv --audio-only

# Stream langsung dari URL (butuh yt-dlp/mpv)
python freease.py -p "https://youtu.be/ID" --pl-backend mpv

# Lihat daftar antrian tanpa memutar
python freease.py -p ./Music --list
```

| Flag | Deskripsi |
|------|-----------|
| `--audio-only` | Putar audionya saja walau file punya video |
| `--loop` | Ulang satu file atau seluruh playlist |
| `--shuffle` | Acak urutan playlist |
| `--volume 0-200` | Volume awal persen (default 100) |
| `--start POS` | Mulai dari posisi, mis. `90` atau `1:30` |
| `--speed X` | Kecepatan putar, mis. `1.5` |
| `--subtitle FILE` | Subtitle eksternal (mpv) |
| `--pl-backend` | Paksa engine: `mpv`/`ffplay`/`mplayer`/`mpg123`/`cvlc`/`vlc`/`timg` |
| `--no-recursive` | Jangan telusuri subfolder |
| `--list` | Tampilkan antrian saja, jangan diputar |

Engine dipilih **otomatis** sesuai yang terpasang dan apakah ada display grafis.
Di Termux/Linux tanpa X, **mpv** memutar video langsung di dalam terminal
(`--vo=tct`) lengkap dengan audio; bila mpv tidak ada, video dirender ke ANSI via
`timg`/`chafa`. Untuk audio, urutan fallback: `mpv → ffplay → mplayer → mpg123 →
cvlc`. Hampir semua format yang didukung ffmpeg/mpv bisa diputar. Bisa dijalankan
sendiri: `python freease_player.py FILE/FOLDER/URL`.

> Kontrol saat memutar (mpv/ffplay): **Space** pause · **← →** seek · **9 0** volume · **q** lanjut/stop · **Ctrl-C** keluar.

---

## 🔍 Fitur Detail

### Modul 1: Network Reconnaissance
- **DNS Records:** A, AAAA, MX, TXT, NS, CNAME, SOA
- **Subdomain Enum:** via crt.sh, fallback ke Cert Spotter (Certificate Transparency Logs — data publik)
- **Web Technology Detection:** Server, CMS (WordPress, Joomla, Drupal, dll), CDN (Cloudflare, AWS, Fastly, dll)
- **Security Headers Audit:** HSTS, CSP, X-Frame-Options, X-Content-Type-Options, dll
- **SSL/TLS Info:** Issuer, expiry date, Subject Alt Names

### Modul 2: Username Checker
Platform yang dicek (20):
GitHub, GitLab, Twitter/X, Instagram, LinkedIn, Reddit, TikTok, YouTube, Pinterest, Telegram, Medium, Dev.to, Keybase, Pastebin, HackerNews, Docker Hub, PyPI, npm, Gravatar, Flickr

Status `UNKNOWN` berarti platform wajib login atau memblokir bot (Instagram, X, TikTok, LinkedIn, Pinterest, dll.) —
hasilnya tidak bisa dipastikan otomatis, jadi tidak dilaporkan sebagai `FOUND`.

### Modul 3: Data Breach Checker
- Integrasi **HaveIBeenPwned API v3**
- Cek breach & paste per email
- Support **bulk email** dengan auto rate-limiting (1.6 detik/request)
- Output: nama breach, tanggal, jenis data bocor, jumlah akun

### Modul: URL Safety Scanner (`-s`)
Pemeriksaan **pasif** terhadap sebuah tautan, dikelompokkan jadi tiga lapis:
- **Leksikal (tanpa jaringan):** host IP mentah, trik `user@host`, Punycode/IDN
  homograph, karakter lookalike (`paypa1`→`paypal`), typosquat brand (jarak
  Levenshtein), TLD berisiko, URL shortener, kedalaman subdomain, kata kunci
  phishing, entropi label domain (indikasi DGA), skema `data:`/`javascript:`,
  ekstensi file berisiko di path.
- **Jaringan (opsional):** resolusi DNS, jejak redirect via `HEAD` (ke mana link
  benar-benar mengarah, deteksi lintas-domain), sertifikat TLS (penerbit, masa
  berlaku, self-signed, cocok/tidak hostname, sertifikat yang baru terbit), umur
  domain via RDAP.
- **Intel ancaman (opt-in):** abuse.ch URLhaus (`--urlhaus`) dan Google Safe
  Browsing (`--safebrowsing-key`).

### Modul: Media Player (`-p`)
Pemutar audio/video terminal dengan deteksi engine otomatis. Mendukung playlist
dari folder/glob, shuffle, loop, audio-only, volume, seek awal, kecepatan,
subtitle, dan streaming URL. Di lingkungan tanpa display grafis (Termux), video
dirender langsung di terminal (mpv `--vo=tct`, atau `timg`/`chafa`).

### Modul 4: Reporting
- **JSON:** Data terstruktur lengkap untuk analisis lanjutan
- **HTML:** Dashboard interaktif dengan security score, collapsible sections, badge berwarna

---

## 📁 Struktur Output

```
laporan_output/
├── freease_report_20240101_120000.json   ← Data mentah terstruktur
└── freease_report_20240101_120000.html   ← Dashboard visual interaktif
```

---

## ⚙️ Arsitektur Teknis

```
freease.py
├── NetworkRecon         ← DNS, subdomain (CT logs), web tech, SSL
├── PortScanner          ← async TCP + banner grabbing
├── WAFDetector          ← fingerprint header/cookie/body
├── WhoisChecker         ← RDAP (semua TLD via rdap.org)
├── EmailSecurityChecker ← SPF / DKIM / DMARC / MTA-STS / BIMI
├── UsernameChecker      ← asyncio.gather + Semaphore(10)
├── BreachChecker        ← HIBP API v3 + rate limiting
├── IPReputationChecker  ← AbuseIPDB + OTX + ip-api
├── ExifToolExtractor    ← metadata + GPS
├── ReportGenerator      ← JSON + HTML generator
└── FreeaseEngine        ← Orchestrator (modul domain jalan paralel)
freease_youtube.py
└── YouTubeDownloader    ← yt-dlp + ffmpeg
freease_scan.py
└── URLScanner          ← heuristik leksikal + DNS/redirect/TLS/RDAP + intel opsional
freease_player.py
└── MediaPlayer         ← auto-detect backend (mpv/ffplay/mpg123/chafa/timg)
```

**Semua request HTTP berjalan secara async (concurrent)** menggunakan `asyncio` + `aiohttp`, sehingga jauh lebih cepat dibanding pendekatan sinkron biasa.

---

## 🛡️ Etika & Legalitas

Sumber data yang digunakan:
| Sumber | Jenis Data | Publik? |
|--------|-----------|---------|
| DNS resolver | DNS records | ✅ Ya |
| crt.sh | Certificate Transparency Logs | ✅ Ya |
| HTTP headers | Server info, teknologi | ✅ Ya |
| Platform URLs | Keberadaan profil publik | ✅ Ya |
| HaveIBeenPwned | Data breach publik | ✅ Ya (dengan API key) |

**Tool ini TIDAK melakukan:**
- ❌ Brute force / fuzzing
- ❌ Akses ke sistem tanpa izin
- ❌ Eksploitasi kerentanan
- ❌ Akses data non-publik

---

*freease — by Kodok-Kejepit*
