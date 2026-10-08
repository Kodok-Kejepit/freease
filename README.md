```
  ███████╗██████╗ ███████╗ █████╗ ███████╗███████╗
  ██╔════╝██╔══██╗██╔════╝██╔══██╗██╔════╝██╔════╝
  █████╗  ██████╔╝█████╗  ███████║███████╗█████╗
  ██╔══╝  ██╔══██╗██╔══╝  ██╔══██║╚════██║██╔══╝
  ██║     ██║  ██║███████╗██║  ██║███████║███████╗
  ╚═╝     ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝╚══════╝╚══════╝
```

# freease

Tool recon dan audit attack surface berbasis terminal, ditulis dengan Python.
Versi 2.2.0, oleh Kodok-Kejepit.

freease menggabungkan beberapa pekerjaan yang biasanya butuh banyak tool terpisah:
recon domain, port scan, deteksi WAF, WHOIS, cek keamanan email, cek username,
cek kebocoran email, reputasi IP, ekstraksi metadata, pemeriksa tautan
phishing, downloader YouTube, dan pemutar audio/video. Semuanya dijalankan
dari satu perintah, `freease.py`.

> Pakai hanya pada domain, IP, akun, atau aset yang kamu miliki, atau yang
> pemiliknya sudah memberi izin. Penyalahgunaan di luar itu tanggung jawab
> pengguna.


## Daftar isi

- [Instalasi](#instalasi)
- [Pemakaian singkat](#pemakaian-singkat)
- [Modul recon](#modul-recon)
- [URL Safety Scanner](#url-safety-scanner)
- [Media Player](#media-player)
- [YouTube Downloader](#youtube-downloader)
- [Semua opsi](#semua-opsi)
- [Laporan](#laporan)
- [Struktur file](#struktur-file)
- [Batasan](#batasan)
- [Lisensi](#lisensi)


## Instalasi

Butuh Python 3.9 atau lebih baru.

```bash
git clone https://github.com/Kodok-Kejepit/freease.git
cd freease
pip install -r requirements.txt
python freease.py --help
```

Di Termux:

```bash
pkg install python git ffmpeg mpv exiftool
git clone https://github.com/Kodok-Kejepit/freease.git
cd freease
pip install -r requirements.txt
```

Beberapa modul memakai program sistem. Pasang hanya yang kamu perlukan:

| Program        | Dipakai oleh                                        | Debian/Ubuntu                               |
|----------------|-----------------------------------------------------|---------------------------------------------|
| ffmpeg         | YouTube (gabung video+audio, konversi), metadata player | `sudo apt install ffmpeg`              |
| mpv            | Media player, termasuk video di dalam terminal      | `sudo apt install mpv`                      |
| mpg123         | Media player, alternatif ringan untuk audio         | `sudo apt install mpg123`                   |
| chafa / timg   | Media player, render video tanpa display grafis     | `sudo apt install chafa`                    |
| node / deno    | YouTube, supaya semua format bisa dibaca yt-dlp     | `sudo apt install nodejs`                   |
| exiftool       | Ekstraksi metadata (`-x`)                           | `sudo apt install libimage-exiftool-perl`   |

URL Safety Scanner tidak butuh program tambahan.


## Pemakaian singkat

```bash
python freease.py -d example.com                      # recon domain lengkap
python freease.py -u johndoe                          # cek username
python freease.py -s "https://contoh.xyz/login"       # periksa tautan mencurigakan
python freease.py -p ./Music --shuffle                # putar satu folder musik
python freease.py -y "https://youtu.be/VIDEO_ID"      # download dari YouTube
```

Beberapa modul bisa digabung dalam satu perintah:

```bash
python freease.py -d example.com -u johndoe -e admin@example.com \
  --hibp-key KEY_HIBP -i 1.2.3.4 -o ./laporan
```


## Modul recon

### Domain (`-d`)

Satu flag `-d` menjalankan lima pemeriksaan sekaligus secara paralel:

- **NetworkRecon**: record DNS (A, AAAA, MX, TXT, NS, CNAME, SOA), subdomain dari
  Certificate Transparency (crt.sh, cadangan Cert Spotter), deteksi server,
  CMS, dan CDN, audit security header, serta info sertifikat SSL/TLS.
- **PortScanner**: scan TCP async ke 22 port umum (FTP, SSH, SMTP, HTTP/S,
  MySQL, RDP, PostgreSQL, Redis, Elasticsearch, MongoDB, dan lain-lain),
  lengkap dengan banner grabbing. Port database dan remote access yang terbuka
  ditandai sebagai sensitif. Bisa dilewati dengan `--skip-portscan`.
- **WAFDetector**: mengenali 13 vendor WAF dari header, cookie, dan isi respons.
- **WhoisChecker**: registrar, tanggal registrasi, dan umur domain lewat RDAP.
- **EmailSecurityChecker**: SPF, DKIM (19 selector umum), DMARC, MTA-STS, dan BIMI.

```bash
python freease.py -d example.com
python freease.py -d example.com --skip-portscan -o ./laporan
```

### Username (`-u`)

Mengecek keberadaan username di 20 platform: GitHub, GitLab, Twitter/X,
Instagram, LinkedIn, Reddit, TikTok, YouTube, Pinterest, Telegram, Medium,
Dev.to, Keybase, Pastebin, HackerNews, Docker Hub, PyPI, npm, Gravatar, dan Flickr.

```bash
python freease.py -u johndoe
```

Hasil `UNKNOWN` berarti platformnya wajib login atau memblokir bot, jadi
keberadaan akun tidak bisa dipastikan secara otomatis. Hasil seperti ini tidak
dihitung sebagai `FOUND`.

### Kebocoran email (`-e`)

Memeriksa email di Have I Been Pwned v3. Butuh API key dari
https://haveibeenpwned.com/API/Key. Beberapa email bisa dipisah dengan koma, dan
jeda antar-request diatur otomatis.

```bash
python freease.py -e admin@example.com,info@example.com --hibp-key KEY_HIBP
```

### Reputasi IP (`-i`)

Menggabungkan data dari AbuseIPDB (skor abuse, flag TOR/proxy), AlienVault OTX
(jumlah pulse, keluarga malware), dan ip-api.com (lokasi, ISP, ASN). OTX dan
ip-api tidak butuh key. AbuseIPDB butuh key gratis dari
https://www.abuseipdb.com/register.

```bash
python freease.py -i 1.2.3.4,8.8.8.8 --abuseipdb-key KEY_ABUSEIPDB
```

### Metadata file (`-x`)

Membaca metadata EXIF, IPTC, dan XMP dari file lokal maupun URL. Kalau ada
koordinat GPS, link Google Maps-nya ikut ditampilkan.

```bash
python freease.py -x foto.jpg
python freease.py -x https://example.com/gambar.jpg
```


## URL Safety Scanner

Dipakai untuk memeriksa tautan sebelum dibuka, misalnya link dari chat, email,
atau SMS yang terasa janggal. Scanner ini tidak membuka halamannya. Tidak ada
JavaScript yang dijalankan dan tidak ada file yang diunduh. Yang dilakukan
hanya membaca struktur URL, melihat ke mana link itu diarahkan, dan
mengecek sertifikat serta umur domainnya.

```bash
python freease.py -s "https://paypa1-login.verify-account.tk/signin"
python freease.py -s "http://bit.ly/xxxx" --urlhaus
python freease.py -s "https://contoh.xyz" --offline
python freease.py --scan-list daftar_url.txt -o ./laporan
```

Contoh hasil:

```
╭─────────────────────────── URL SAFETY SCANNER ───────────────────────────╮
│ URL   : http://paypa1-login.verify-account.tk/signin/update               │
│ Host  : paypa1-login.verify-account.tk                                    │
│                                                                           │
│ VERDICT: MENCURIGAKAN  (skor risiko 58/100)                               │
╰─────────────── analisis pasif — target tidak dibuka/dieksekusi ───────────╯
  HIGH     impersonasi   Menyebut brand 'paypal' tapi domain terdaftar
                         'verify-account.tk' ≠ resmi 'paypal.com'.       26
  MEDIUM   tld           TLD '.tk' punya reputasi penyalahgunaan tinggi.  14
  MEDIUM   kata-kunci    Kata kunci bernuansa phishing: account, login,
                         signin, update, verify                           12
  LOW      transport     Tanpa HTTPS — lalu lintas tidak terenkripsi.      6
```

Pemeriksaannya dibagi tiga tahap.

**Struktur URL** (selalu jalan, tanpa internet)

- host berupa IP, bukan nama domain
- trik `user@host` yang menyembunyikan domain asli
- Punycode (`xn--`) dan karakter yang mirip huruf lain, misalnya `paypa1` atau `g00gle`
- nama domain yang mirip brand terkenal (typosquatting)
- TLD yang sering disalahgunakan, seperti `.tk`, `.xyz`, `.top`, dan `.zip`
- layanan pemendek URL (bit.ly, s.id, tinyurl, dan lain-lain)
- subdomain yang terlalu dalam, host terlalu panjang, terlalu banyak tanda hubung atau angka
- kata-kata yang umum di halaman phishing (login, verify, wallet, suspend, dan sejenisnya)
- nama domain yang terlihat acak
- skema `data:` dan `javascript:`
- link yang langsung mengarah ke file `.apk`, `.exe`, `.scr`, `.zip`, dan sejenisnya

**Jaringan** (bisa dimatikan dengan `--offline`)

- apakah domainnya benar-benar ada di DNS
- rantai redirect, ditelusuri lewat request `HEAD` satu per satu
- perpindahan ke domain lain di tengah rantai redirect
- sertifikat TLS: penerbit, masa berlaku, self-signed, cocok tidaknya dengan hostname, dan sertifikat yang baru terbit
- umur domain lewat RDAP. Domain yang umurnya di bawah 30 hari diberi bobot tinggi.

**Database ancaman** (opsional)

- abuse.ch URLhaus lewat `--urlhaus` (gratis, tanpa key)
- Google Safe Browsing lewat `--safebrowsing-key KEY`

Setiap temuan menambah skor. Totalnya dibatasi 0 sampai 100, lalu dikelompokkan:

| Skor    | Verdict       | Exit code |
|---------|---------------|-----------|
| 0–24    | AMAN          | 0         |
| 25–59   | MENCURIGAKAN  | 1         |
| 60–100  | BERBAHAYA     | 2         |

Karena exit code-nya mengikuti tingkat risiko, scanner ini bisa dipakai di
dalam script:

```bash
python freease_scan.py --no-export "$URL" || echo "jangan dibuka"
```

Untuk `--scan-list`, isi file-nya satu URL per baris. Baris yang diawali `#`
dianggap komentar. Kalau ada banyak URL, exit code mengikuti skor tertinggi.

Skor ini berasal dari heuristik, bukan vonis. Domain baru yang sah bisa
terkena skor sedang, dan halaman phishing di domain lama yang diretas bisa
lolos dengan skor rendah. Untuk link yang benar-benar meragukan, gabungkan
dengan `--urlhaus` atau Safe Browsing.


## Media Player

Memutar audio dan video langsung dari terminal. Formatnya mengikuti apa yang
bisa dibuka ffmpeg/mpv, jadi praktis semua format umum didukung: mp3, m4a,
flac, opus, ogg, wav, aac, mp4, mkv, webm, avi, mov, ts, dan lain-lain.

```bash
python freease.py -p lagu.mp3
python freease.py -p ./Music --shuffle --loop
python freease.py -p "./Music/*.flac"
python freease.py -p film.mkv --subtitle film.srt --start 1:30
python freease.py -p film.mkv --audio-only
python freease.py -p "https://youtu.be/VIDEO_ID" --pl-backend mpv
python freease.py -p ./Music --list
```

Sumbernya bisa berupa file, folder (otomatis jadi playlist, termasuk
subfolder), pola glob, atau URL.

Program pemutarnya dipilih otomatis dari yang terpasang:

| Kondisi                          | Urutan yang dicoba                     |
|----------------------------------|----------------------------------------|
| Video, ada display (X/Wayland)   | mpv, ffplay, mplayer, vlc              |
| Video, tanpa display (Termux/SSH)| mpv `--vo=tct`, timg, ffmpeg + chafa   |
| Audio                            | mpv, ffplay, mplayer, mpg123, cvlc     |

Di Termux atau sesi SSH, mpv bisa menampilkan video langsung di terminal
lengkap dengan suaranya. timg dan chafa hanya menampilkan gambar tanpa suara,
jadi keduanya cuma dipakai kalau mpv tidak ada.

Kontrol selama pemutaran memakai tombol bawaan pemutarnya. Di mpv dan ffplay:
`Space` untuk pause, panah kiri/kanan untuk maju-mundur, `9`/`0` untuk volume,
`q` untuk lanjut ke lagu berikutnya, dan `Ctrl+C` untuk berhenti.

| Opsi              | Fungsi                                                  |
|-------------------|---------------------------------------------------------|
| `--audio-only`    | Putar suaranya saja walaupun file-nya video             |
| `--loop`          | Ulangi file atau seluruh playlist                       |
| `--shuffle`       | Acak urutan playlist                                    |
| `--volume N`      | Volume awal, 0–200 (default 100)                        |
| `--start POS`     | Mulai dari detik tertentu, contoh `90` atau `1:30`      |
| `--speed X`       | Kecepatan putar, contoh `1.5` (mpv dan mplayer)         |
| `--subtitle FILE` | Subtitle dari file terpisah (mpv)                       |
| `--pl-backend`    | Paksa pakai pemutar tertentu                            |
| `--no-recursive`  | Jangan ikut membaca subfolder                           |
| `--list`          | Tampilkan antrian saja, tanpa memutar                   |


## YouTube Downloader

Download video atau musik dari YouTube dan YouTube Music, mulai dari 144p
sampai 4K. Kalau mode dan kualitasnya tidak diisi, pilihannya akan ditanyakan.

```bash
python freease.py -y "https://youtu.be/VIDEO_ID"
python freease.py -y "https://youtu.be/VIDEO_ID" --yt-mode video --yt-quality 4k
python freease.py -y "https://music.youtube.com/watch?v=ID" --yt-mode musik
python freease.py -y "https://music.youtube.com/watch?v=ID" --yt-mode musik \
  --yt-audio-format m4a --yt-bitrate 320
python freease.py -y "https://youtube.com/playlist?list=ID" --yt-mode musik --yt-playlist
```

| Opsi                | Nilai                                                        |
|---------------------|--------------------------------------------------------------|
| `--yt-mode`         | `video`, atau `musik`/`audio`                                |
| `--yt-quality`      | `4k`, `2k`, `hd`/`1080`, `720`, `480`, `360`, `240`, `144`, `best` |
| `--yt-audio-format` | `mp3` (default), `m4a`, `opus`, `flac`, `wav`                |
| `--yt-bitrate`      | `128`, `192` (default), `256`, `320`                         |
| `--yt-container`    | `mp4` (default) atau `mkv`                                   |
| `--yt-playlist`     | Download seluruh playlist atau album                         |
| `--yt-dir`          | Folder tujuan (default `./freease_downloads`)                |

Kalau resolusi yang diminta tidak tersedia, download turun ke resolusi
terdekat di bawahnya. Musik otomatis diberi tag judul, artis, dan cover.

Gunakan hanya untuk konten milikmu sendiri atau yang memang boleh diunduh.


## Semua opsi

| Opsi                    | Keterangan                                          |
|-------------------------|-----------------------------------------------------|
| `-d DOMAIN`             | Recon domain (5 modul paralel)                      |
| `-u USERNAME`           | Cek username di 20 platform                         |
| `-e EMAIL[,EMAIL]`      | Cek kebocoran email (HIBP)                          |
| `-i IP[,IP]`            | Reputasi IP atau domain                             |
| `-x FILE/URL`           | Ekstraksi metadata                                  |
| `-s URL`                | Periksa satu tautan                                 |
| `--scan-list FILE`      | Periksa banyak tautan dari file                     |
| `-p SRC [SRC ...]`      | Putar audio/video                                   |
| `-y URL`                | Download dari YouTube                               |
| `--hibp-key KEY`        | API key Have I Been Pwned                           |
| `--abuseipdb-key KEY`   | API key AbuseIPDB                                   |
| `--skip-portscan`       | Lewati port scan                                    |
| `--offline`             | Scanner tanpa koneksi jaringan                      |
| `--urlhaus`             | Scanner ikut cek ke URLhaus                         |
| `--safebrowsing-key KEY`| Scanner ikut cek ke Google Safe Browsing            |
| `--scan-timeout SEC`    | Timeout tiap request scanner (default 10)           |
| `--max-redirects N`     | Batas redirect yang ditelusuri (default 10)         |
| `-o DIR`                | Folder laporan (default `./freease_output`)         |
| `--no-export`           | Tampilkan di terminal saja, tanpa file laporan      |
| `-V`, `--version`       | Tampilkan versi                                     |

Opsi lengkap media player dan YouTube ada di bagian masing-masing di atas,
atau lihat lewat `python freease.py --help`.


## Laporan

Kecuali dijalankan dengan `--no-export`, hasil disimpan ke folder output
(default `./freease_output`):

```
freease_output/
├── freease_report_20260602_121521.json    hasil modul recon
├── freease_report_20260602_121521.html
├── freease_scan_20260602_130044.json      hasil URL scanner
└── freease_scan_20260602_130044.html
```

File JSON berisi data mentah yang bisa diolah lagi. File HTML bisa dibuka di
browser dan berisi ringkasan skor, temuan per modul, serta badge status.


## Struktur file

```
freease.py                   entry point, modul recon, laporan
freease_scan.py              URL Safety Scanner
freease_player.py            media player
freease_youtube.py           YouTube downloader
freease_exiftool_module.py   ekstraksi metadata
requirements.txt
```

`freease_scan.py`, `freease_player.py`, dan `freease_youtube.py` juga bisa
dijalankan sendiri tanpa lewat `freease.py`:

```bash
python freease_scan.py "https://contoh.xyz"
python freease_player.py ./Music --shuffle
python freease_youtube.py "https://youtu.be/VIDEO_ID"
```


## Batasan

- Hasil cek username untuk platform yang wajib login (Instagram, X, TikTok,
  LinkedIn, Pinterest) tidak bisa dipastikan, jadi akan muncul sebagai `UNKNOWN`.
- crt.sh, OTX, dan layanan publik lain kadang lambat atau menolak request.
  Kalau satu sumber gagal, modul lain tetap jalan.
- Scanner hanya bisa menilai apa yang terlihat dari luar. Halaman phishing yang
  dipasang di domain lama yang sudah diretas bisa saja lolos.
- freease tidak melakukan brute force, eksploitasi, atau akses ke sistem
  tanpa izin.


## Lisensi

Lihat [LICENSE](LICENSE).
