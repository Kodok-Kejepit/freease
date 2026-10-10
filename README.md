```
  ███████╗██████╗ ███████╗███████╗ █████╗ ███████╗███████╗
  ██╔════╝██╔══██╗██╔════╝██╔════╝██╔══██╗██╔════╝██╔════╝
  █████╗  ██████╔╝█████╗  █████╗  ███████║███████╗█████╗
  ██╔══╝  ██╔══██╗██╔══╝  ██╔══╝  ██╔══██║╚════██║██╔══╝
  ██║     ██║  ██║███████╗███████╗██║  ██║███████║███████╗
  ╚═╝     ╚═╝  ╚═╝╚══════╝╚══════╝╚═╝  ╚═╝╚══════╝╚══════╝
```

# freease

Tool recon dan audit attack surface berbasis terminal, ditulis dengan Python.
Versi 1.65.0, oleh Kodok-Kejepit.

freease menggabungkan beberapa pekerjaan yang biasanya butuh banyak tool terpisah:
recon domain, port scan, deteksi WAF, WHOIS, cek keamanan email, cek username,
cek kebocoran email, reputasi IP, ekstraksi metadata, pemeriksa tautan
phishing, pencarian lagu dan web, downloader (YouTube, Pinterest, dan ratusan situs
lain), serta pemutar audio/video. Semuanya dijalankan dari satu perintah,
`freease.py`.

> Pakai hanya pada domain, IP, akun, atau aset yang kamu miliki, atau yang
> pemiliknya sudah memberi izin. Penyalahgunaan di luar itu tanggung jawab
> pengguna.


## Daftar isi

- [Instalasi](#instalasi)
- [Pemakaian singkat](#pemakaian-singkat)
- [Navigasi halaman (tombol panah)](#navigasi-halaman-tombol-panah)
- [Modul recon](#modul-recon)
- [URL Safety Scanner](#url-safety-scanner)
- [Pencarian](#pencarian)
- [Media Player](#media-player)
- [Media Downloader](#media-downloader)
- [Log Defender (blue team)](#log-defender-blue-team)
- [Semua opsi](#semua-opsi)
- [Laporan](#laporan)
- [Struktur file](#struktur-file)
- [Batasan](#batasan)
- [Versi & changelog](#versi--changelog)
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
| ffmpeg         | Downloader (gabung video+audio, konversi), metadata player | `sudo apt install ffmpeg`           |
| mpv            | Media player, termasuk video di dalam terminal dan streaming hasil pencarian | `sudo apt install mpv` |
| mpg123         | Media player, alternatif ringan untuk audio         | `sudo apt install mpg123`                   |
| chafa / timg   | Media player, render video tanpa display grafis     | `sudo apt install chafa`                    |
| node / deno    | Downloader & pencarian, supaya semua format YouTube bisa dibaca yt-dlp | `sudo apt install nodejs` |
| exiftool       | Ekstraksi metadata (`-x`)                           | `sudo apt install libimage-exiftool-perl`   |

URL Safety Scanner tidak butuh program tambahan.


## Pemakaian singkat

```bash
python freease.py -d example.com                      # recon domain lengkap
python freease.py -u johndoe                          # cek username
python freease.py -s "https://contoh.xyz/login"       # periksa tautan mencurigakan
python freease.py -S "dewa 19 kangen"                 # cari lagu atau web, lalu putar/unduh/buka
python freease.py -p ./Music --shuffle                # putar satu folder musik
python freease.py -y "https://youtu.be/VIDEO_ID"      # download dari YouTube
python freease.py -y "https://pin.it/xxxxxxx"         # download foto/video Pinterest
```

Beberapa modul bisa digabung dalam satu perintah:

```bash
python freease.py -d example.com -u johndoe -e admin@example.com \
  --hibp-key KEY_HIBP -i 1.2.3.4 -o ./laporan
```


## Navigasi halaman (tombol panah)

Semua daftar panjang di freease ditampilkan per halaman, dan tombolnya sama
di mana pun:

| Tombol                    | Fungsi                                          |
|---------------------------|-------------------------------------------------|
| `↓` `→` `PgDn` (atau `n`) | halaman berikutnya, mis. hasil 11–20, 21–30, …  |
| `↑` `←` `PgUp` (atau `p`) | halaman sebelumnya                              |
| `Home` / `End`            | halaman pertama / terakhir yang sudah dimuat    |
| `Enter`                   | selesai melihat / lanjut                        |

Halaman berganti di tempat, jadi tabel lama tidak menumpuk di layar. Di
hasil pencarian, nomor terus berlanjut (halaman 2 berisi nomor 11–20) dan
hasil berikutnya baru diambil dari sumbernya saat kamu menekan `↓`.

Berlaku untuk:

- hasil pencarian YouTube, YouTube Music, SoundCloud, dan web (`-S`)
- isi halaman yang dijelajahi (aksi `j` di pencarian)
- isi playlist sebelum diunduh (`-y`), lalu pilih item yang mau diunduh
- antrian player (`-p`)
- daftar subdomain (lengkap dengan IP-nya) dan port terbuka (`-d`)
- ringkasan banyak URL (`--scan-list`)

Kalau output dialihkan ke file/pipe, semua daftar dicetak penuh tanpa
menunggu tombol. Paging juga bisa dimatikan dengan `--no-pager` atau
environment variable `FREEASE_NO_PAGER=1`. Di terminal yang tidak punya
tombol panah (beberapa keyboard HP), ketik `n` atau `p` lalu Enter.


## Modul recon

### Domain (`-d`)

Satu flag `-d` menjalankan lima pemeriksaan sekaligus secara paralel:

- **NetworkRecon**: record DNS (A, AAAA, MX, TXT, NS, CNAME, SOA, CAA), status
  DNSSEC, subdomain dari Certificate Transparency (crt.sh, cadangan Cert Spotter)
  sekaligus dicek mana yang masih hidup (resolve ke IP), deteksi server, CMS,
  generator, dan CDN, serta info sertifikat SSL/TLS (protokol, cipher, sisa
  hari). Sertifikat yang tidak valid tetap dibaca detailnya supaya tanggal
  kedaluwarsa dan penerbitnya terlihat.
- **Audit konfigurasi web**: bukan sekadar cek header ada atau tidak, tapi juga
  kualitasnya: HSTS terlalu pendek, CSP dengan `unsafe-inline`/`unsafe-eval`/
  wildcard, proteksi clickjacking, CORS terbuka, header yang membocorkan versi
  software, dan cookie tanpa flag `Secure`/`HttpOnly`/`SameSite`. Ada juga
  pengecekan `security.txt` (RFC 9116).
- **PortScanner**: scan TCP async ke 47 port umum (FTP, SSH, SMB, RDP, VNC,
  MySQL, PostgreSQL, MSSQL, Redis, Elasticsearch, MongoDB, Docker API,
  Kubernetes API, Memcached, dan lain-lain), lengkap dengan banner grabbing.
  Port HTTP yang diam ditanya dengan `HEAD` supaya header `Server`-nya terbaca.
  Layanan yang sering tanpa autentikasi (Docker API, Redis, Elasticsearch,
  MongoDB, Memcached, CouchDB) ditandai **KRITIS**. Daftar port bisa diatur
  dengan `--ports`, atau dilewati dengan `--skip-portscan`.
- **WAFDetector**: mengenali 13 vendor WAF dari header, cookie, dan isi respons.
  Bukti dari DNS ikut dihitung: IP di range resmi Cloudflare, atau CNAME ke
  CloudFront, Akamai, Imperva, Fastly, dan sejenisnya. Jadi CDN/WAF tetap
  terdeteksi walau halaman web-nya tidak bisa diakses.
- **WhoisChecker**: registrar, tanggal registrasi, umur domain, dan sisa hari
  sebelum domain kedaluwarsa lewat RDAP.
- **EmailSecurityChecker**: SPF (termasuk hitungan DNS lookup rekursif terhadap
  batas 10 dari RFC 7208), DKIM (48 selector umum), DMARC (termasuk `sp=`,
  record ganda, dan tag `p=` yang hilang), MTA-STS, TLS-RPT, dan BIMI.

```bash
python freease.py -d example.com
python freease.py -d example.com --skip-portscan -o ./laporan
python freease.py -d example.com --ports top              # 47 port umum + port 1-1024
python freease.py -d example.com --ports 22,80,443,8000-8100 --port-timeout 1
```

| Opsi `--ports`      | Arti                                                 |
|---------------------|------------------------------------------------------|
| `default` (bawaan)  | 47 port umum                                         |
| `top`               | 47 port umum + semua port 1–1024                     |
| `22,80,443`         | daftar port                                          |
| `1-1024,8080`       | rentang dan daftar boleh dicampur (maks 5000 port)   |

Halaman web diambil dengan tiga percobaan berurutan: aiohttp biasa, aiohttp
khusus IPv4 (untuk jaringan yang IPv6-nya bermasalah), lalu `urllib` bawaan
Python. Kalau ketiganya gagal, alasan masing-masing ditampilkan, dan security
header dilaporkan "tidak bisa dicek", bukan dianggap lengkap.

Hal yang sama berlaku untuk keamanan email. Kalau query DNS-nya gagal,
statusnya `UNKNOWN`, bukan `FAIL`. Hasil akhir hanya diberi label kalau
protokol yang gagal dicek tidak mungkin mengubah hasilnya.

### Username (`-u`)

Mengecek keberadaan username di 23 platform: GitHub, GitLab, Codeberg,
Twitter/X, Instagram, LinkedIn, Reddit, TikTok, YouTube, Pinterest, Telegram,
Medium, Dev.to, Keybase, Pastebin, HackerNews, Docker Hub, PyPI, npm, Gravatar,
Flickr, Lichess, dan Chess.com. Platform yang membalas timeout atau rate limit
dicoba ulang sekali.

```bash
python freease.py -u johndoe
```

Hasil `UNKNOWN` berarti platformnya wajib login atau memblokir bot, jadi
keberadaan akun tidak bisa dipastikan secara otomatis. Hasil seperti ini tidak
dihitung sebagai `FOUND`.

### Kebocoran email (`-e`)

Memeriksa email di Have I Been Pwned v3. Butuh API key dari
https://haveibeenpwned.com/API/Key. Beberapa email bisa dipisah dengan koma, dan
jeda antar-request diatur otomatis. Kalau HIBP membalas rate limit, freease
menunggu sesuai header `Retry-After` lalu mencoba lagi.

```bash
python freease.py -e admin@example.com,info@example.com --hibp-key KEY_HIBP
```

### Reputasi IP (`-i`)

Menggabungkan data dari AbuseIPDB (skor abuse, flag TOR/proxy), AlienVault OTX
(jumlah pulse, keluarga malware), dan ip-api.com (lokasi, ISP, ASN). OTX dan
ip-api tidak butuh key. AbuseIPDB butuh key gratis dari
https://www.abuseipdb.com/register. Reverse DNS (PTR) tiap IP ikut ditampilkan.
Kalau tidak ada satu pun feed reputasi yang bisa dihubungi, risikonya ditulis
`UNKNOWN`, bukan `LOW`.

```bash
python freease.py -i 1.2.3.4,8.8.8.8 --abuseipdb-key KEY_ABUSEIPDB
```

### Metadata file (`-x`)

Membaca metadata EXIF, IPTC, dan XMP dari file lokal maupun URL. Kalau ada
koordinat GPS, link Google Maps-nya ikut ditampilkan. freease juga menilai
**risiko privasi** file itu: data apa saja yang ikut tersebar kalau file
dibagikan apa adanya (lokasi, nama pembuat/pemilik, nomor seri kamera, model
HP, software), lengkap dengan perintah untuk menghapusnya.

```bash
python freease.py -x foto.jpg
python freease.py -x https://example.com/gambar.jpg
```


## URL Safety Scanner

Dipakai untuk memeriksa tautan sebelum dibuka, misalnya link dari chat, email,
atau SMS yang terasa janggal. Scanner ini tidak merender halamannya. Tidak ada
JavaScript yang dijalankan dan tidak ada file yang diunduh. Yang dilakukan
hanya membaca struktur URL, melihat ke mana link itu diarahkan, dan
mengecek sertifikat serta umur domainnya. Dengan `--deep`, sumber HTML
halaman tujuan ikut dibaca sebagai teks.

```bash
python freease.py -s "https://paypa1-login.verify-account.tk/signin"
python freease.py -s "http://bit.ly/xxxx" --urlhaus --urlhaus-key KEY
python freease.py -s "https://contoh.xyz/login" --deep
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

- host berupa IP (IPv4/IPv6), termasuk IP yang disamarkan seperti
  `http://3232235777/` atau `http://0xC0A80001/`
- trik `user@host` yang menyembunyikan domain asli
- Punycode (`xn--`), huruf non-Latin, dan campuran aksara (mis. huruf Cyrillic
  `а` di `pаypal.com`), serta karakter mirip seperti `paypa1` atau `g00gle`
- nama domain yang mirip brand terkenal (typosquatting), termasuk brand lokal
  (BCA, BRI, BNI, Mandiri, DANA, OVO, GoPay, Shopee, Tokopedia, dan lainnya).
  Brand pendek hanya dihitung kalau berdiri sebagai kata sendiri atau dengan
  imbuhan khas phishing (`klikbca`, `mybri`), jadi `fabric.com` tidak dianggap
  meniru BRI. Domain resmi brand (mis. `google.co.id`, `user.github.io`) tidak
  ikut dicurigai.
- TLD yang sering disalahgunakan, seperti `.tk`, `.xyz`, `.top`, dan `.zip`
- layanan pemendek URL (bit.ly, s.id, tinyurl, dan lain-lain)
- subdomain yang terlalu dalam, host terlalu panjang, terlalu banyak tanda hubung atau angka
- kata-kata yang umum di halaman phishing (login, verify, wallet, suspend, dan sejenisnya)
- nama domain yang terlihat acak
- skema `data:` dan `javascript:`
- link yang langsung mengarah ke file `.apk`, `.exe`, `.scr`, `.zip`, dan sejenisnya

**Jaringan** (bisa dimatikan dengan `--offline`)

- apakah domainnya benar-benar ada di DNS
- rantai redirect, ditelusuri lewat request `HEAD` satu per satu (pindah ke
  `GET` tanpa membaca isi kalau server menolak `HEAD`)
- perpindahan ke domain lain di tengah rantai redirect. Domain **tujuan
  akhir** ikut dinilai (struktur URL dan umur domainnya), karena itulah yang
  sebenarnya dibuka pengguna
- sertifikat TLS: penerbit, masa berlaku, self-signed, cocok tidaknya dengan hostname, dan sertifikat yang baru terbit
- umur domain lewat RDAP. Domain yang umurnya di bawah 30 hari diberi bobot tinggi.

**Konten halaman** (`--deep`, opsional)

Sumber HTML halaman tujuan dibaca sebagai teks (maks 512 KB). Tidak ada
JavaScript yang dijalankan dan tidak ada gambar atau skrip lain yang dimuat.

- form password, apalagi yang dikirim lewat HTTP
- form yang mengirim data ke domain lain
- meta refresh yang mengalihkan diam-diam ke domain lain
- judul halaman menyebut brand tapi domainnya bukan milik brand itu
- JavaScript yang diobfuskasi (`eval(atob(`, packer, dan sejenisnya)
- iframe tersembunyi, permintaan seed phrase / private key
- link yang langsung menyajikan file unduhan

**Database ancaman** (opsional)

URL awal dan URL tujuan akhir sama-sama dicek.

- abuse.ch URLhaus lewat `--urlhaus`. Sejak 2025 API abuse.ch wajib memakai
  Auth-Key gratis dari https://auth.abuse.ch/, isi lewat `--urlhaus-key KEY`
  atau environment variable `URLHAUS_AUTH_KEY`.
- Google Safe Browsing lewat `--safebrowsing-key KEY` (atau `SAFEBROWSING_API_KEY`)

Kalau database ancaman gagal dihubungi atau key-nya ditolak, alasannya
ditampilkan, bukan diam-diam dianggap bersih.

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


## Pencarian

Cari lagu atau video dari judul maupun potongan liriknya, atau cari apa saja
di web. Hasil yang dipilih bisa langsung diputar, diunduh, dibuka di browser,
atau diperiksa keamanannya dulu.

```bash
python freease.py -S "dewa 19 kangen"
python freease.py -S "aku yang dulu bukanlah yang sekarang" --search-source yt
python freease.py -S "hindia evaluasi" --search-source ytm
python freease.py -S "cara install termux" --search-source web --search-region id-id
```

Tanpa mode interaktif, halaman tertentu bisa langsung diminta:

```bash
python freease.py -S "dewa 19" --search-source yt --search-page 2 --search-action list
python freease.py -S "dewa 19" --search-source yt --search-page 2 --search-action music --search-pick 13
```

Kalau `--search-source` tidak diisi, freease menanyakan sumbernya dulu:

```
  Cari di mana?
    1  YouTube
    2  YouTube Music
    3  SoundCloud
    4  Web (DuckDuckGo)
```

| Sumber  | Cocok untuk                                                            |
|---------|------------------------------------------------------------------------|
| `yt`    | Judul atau potongan lirik, termasuk video lirik, cover, dan murottal   |
| `ytm`   | Hanya lagu resmi dari bagian "Songs" YouTube Music                     |
| `sc`    | Lagu di SoundCloud                                                     |
| `web`   | Halaman web apa saja, lewat DuckDuckGo (tanpa API key)                 |

Hasilnya tampil sebagai tabel bernomor, urut dari yang paling relevan,
10 hasil per halaman (atur dengan `--search-limit`, maks 50). Tekan `↓` untuk
memuat hasil berikutnya (11–20, 21–30, sampai 300 hasil), `↑` untuk kembali.
Halaman yang sudah pernah tampil isinya tetap sama dan tidak ada hasil yang
muncul dua kali. Nomor bisa dipilih satu (`3`), beberapa (`1,4`), rentang
(`2-5`), atau semua di halaman yang sedang tampil (`a`). Setelah nomor dipilih, freease menanyakan aksi, dan aksi itu
dijalankan untuk semua nomor yang dipilih. Contoh: `a` lalu `s` memeriksa
keamanan semua link sekaligus. Setelah aksi selesai, daftar hasil muncul
lagi. Ketik `c` untuk mencari kata kunci lain, `k` untuk kembali ke daftar
sebelumnya (setelah menjelajah halaman), dan `q` untuk keluar.

Aksi yang tersedia:

| Tombol | Aksi                                   | Hasil media | Hasil web            |
|--------|----------------------------------------|-------------|----------------------|
| `p`    | putar audio                            | ya          | link media saja      |
| `v`    | putar video di terminal                | ya          | link media saja      |
| `h`    | putar video HD di aplikasi pemutar     | ya          | link media saja      |
| `m`    | unduh musik (audio)                    | ya          | link media saja      |
| `d`    | unduh video                            | ya          | link media saja      |
| `j`    | jelajahi isi halaman                   | —           | ya                   |
| `s`    | cek keamanan link (URL Safety Scanner) | —           | ya                   |
| `o`    | buka di browser                        | —           | ya                   |
| `l`    | tampilkan link lengkap                 | —           | ya                   |

Di hasil web, link yang bisa langsung diputar atau diunduh (YouTube, TikTok,
SoundCloud, dan situs lain yang dikenali yt-dlp) diberi label **media**.
Sebelum membuka link di browser, freease memeriksa link itu secara cepat
tanpa membukanya. Kalau hasilnya mencurigakan atau berbahaya, freease meminta
konfirmasi dulu.

### Menjelajahi isi halaman

Aksi `j` membuka sebuah halaman hasil pencarian, lalu mengumpulkan semua
yang ada di dalamnya menjadi daftar baru: video atau audio yang tertanam,
link YouTube/SoundCloud/situs media lain, player yang disematkan (iframe),
halaman lain di situs yang sama, dan link ke situs luar. Media ditaruh di
urutan teratas. Dari daftar itu Anda bisa memutar, mengunduh, atau
menjelajah lebih dalam lagi, lalu kembali dengan `k`.

Sebelum dijelajahi, halaman diperiksa dulu tanpa dibuka. Kalau hasilnya
mencurigakan, freease meminta konfirmasi. freease tidak menjalankan
JavaScript, jadi situs yang memuat isinya lewat JavaScript (banyak situs
streaming film dan anime) sering tidak memperlihatkan videonya. Situs seperti
itu juga sering memuat iklan berbahaya dan konten bajakan; gunakan dengan
hati-hati dan hanya untuk konten yang memang boleh diakses.

Pencarian web memakai DuckDuckGo versi HTML. Google dan Yandex sengaja tidak
dipakai, karena keduanya cepat memblokir pencarian otomatis dengan captcha.
Kalau DuckDuckGo meminta verifikasi anti-bot, freease akan memberi tahu.
Tunggu beberapa menit, lalu coba lagi.

Kalau koneksi ke DuckDuckGo dibelokkan oleh jaringan (misalnya diblokir
ISP lewat DNS), freease mencari alamat asli DuckDuckGo lewat DNS-over-HTTPS
lalu menyambung langsung, dengan sertifikat tetap diverifikasi. Kalau
pemblokirannya lebih dalam dari DNS, gunakan VPN atau aplikasi 1.1.1.1.

Tanpa menu (berguna untuk script), pakai `--search-action` dan `--search-pick`:

```bash
python freease.py -S "tulus hati-hati di jalan" --search-source yt --search-action music
python freease.py -S "nadin amizah" --search-source yt --search-action play --search-pick 1-5
python freease.py -S "phishing bank" --search-source web --search-action scan --search-pick 1-3
```

| Opsi                | Fungsi                                                         |
|---------------------|----------------------------------------------------------------|
| `--search-source`   | `yt`, `ytm`, `sc`, atau `web`; kalau kosong ditanyakan          |
| `--search-limit N`  | Jumlah hasil, default 10, maksimal 50                           |
| `--search-action`   | `list`, `play`, `play-video`, `play-hd`, `music`, `video`, `scan`, `open`, `link`, `explore` |
| `--search-pick`     | Nomor hasil yang diproses, contoh `1`, `1,3`, `2-4` (default 1) |
| `--search-region`   | Wilayah pencarian web, contoh `id-id`, `us-en` (default global) |

Unduhan dari hasil pencarian memakai opsi downloader yang sama (`--yt-dir`,
`--yt-audio-format`, `--yt-bitrate`, `--yt-quality`). Pemutarannya memakai
opsi player yang sama (`--volume`, `--speed`, `--pl-backend`, `--pl-quality`).

Pinterest tidak ada di daftar sumber, karena yt-dlp tidak menyediakan
pencarian Pinterest. Untuk Pinterest, tempel link pin atau board ke `-y`.


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
python freease.py -p "https://youtu.be/VIDEO_ID" --audio-only
python freease.py -p "https://youtu.be/VIDEO_ID" --external
python freease.py -p ./Music --list
```

Sumbernya bisa berupa file, folder (otomatis jadi playlist, termasuk
subfolder), pola glob, atau link. Link YouTube dan situs lain diputar
langsung tanpa diunduh dulu. mpv membukanya lewat yt-dlp; untuk ffplay,
mplayer, dan vlc, freease mengubahnya dulu menjadi URL stream. Kalau stream
online gagal dibuka, freease mencoba sekali lagi secara otomatis.

Program pemutarnya dipilih otomatis dari yang terpasang:

| Kondisi                          | Urutan yang dicoba                     |
|----------------------------------|----------------------------------------|
| Video, ada display (X/Wayland)   | mpv, ffplay, mplayer, vlc              |
| Video, tanpa display (Termux/SSH)| mpv `--vo=tct`, timg, ffmpeg + chafa   |
| Audio                            | mpv, ffplay, mplayer, mpg123, cvlc     |

### Kualitas video di terminal dan mode HD

Video yang diputar di dalam terminal memang terlihat kotak-kotak, berapa pun
resolusi videonya. Mode `tct` menggambar video dengan karakter teks, dan
setiap karakter hanya bisa menampilkan 2 piksel berwarna. Terminal HP yang
lebarnya sekitar 100 kolom berarti gambarnya cuma sekitar 100×80 piksel.
Karena itu stream untuk mode ini dibatasi 480p: resolusi lebih tinggi tidak
membuat gambar lebih tajam, hanya membuang kuota dan CPU.

Untuk video yang jernih, ada dua cara:

1. **`--external` (atau `--hd`, atau aksi `h` di pencarian).** Video dibuka
   di aplikasi pemutar. Di Android (Termux), freease mengirimkan stream ke
   aplikasi seperti VLC, MX Player, atau mpv-android lewat menu "Buka dengan".
   Di Linux desktop, video dibuka di jendela mpv. Resolusi targetnya diatur
   dengan `--pl-quality` (default 720).

   Aplikasi pemutar Android butuh satu link yang berisi video dan audio
   sekaligus. YouTube biasanya tidak menyediakan link seperti itu, jadi
   freease menawarkan untuk mengunduh videonya dulu (video dan audio digabung
   ffmpeg), lalu membukanya di aplikasi. Saat mengunduh, daftar resolusi yang
   tersedia ditampilkan untuk dipilih, kecuali `--pl-quality` sudah diisi.

   Supaya aplikasi pemutar bisa membaca file dari Termux, Termux harus
   mengizinkannya. Tanpa pengaturan ini, aplikasi pemutar hanya menampilkan
   "Pemutar eror". freease akan menawarkan untuk mengaktifkannya, atau
   aktifkan sendiri:

   ```bash
   echo "allow-external-apps = true" >> ~/.termux/termux.properties
   termux-reload-settings
   ```

   Pengaturan ini juga membolehkan aplikasi yang Anda beri izin "Run commands
   in Termux" menjalankan perintah di Termux, jadi jangan berikan izin itu ke
   aplikasi yang tidak dikenal.

2. **`--pl-vo sixel` atau `--pl-vo kitty`.** Video digambar dengan piksel
   asli di dalam terminal, sehingga jauh lebih tajam dari `tct`. Ini hanya
   bisa dipakai di terminal yang mendukung grafis sixel (misalnya foot,
   WezTerm, xterm dengan sixel) atau kitty. freease menanyakan dukungan
   sixel ke terminal sebelum memutar; kalau tidak didukung, otomatis kembali
   ke mode `tct` supaya layar tidak dipenuhi karakter acak.

Saat video tampil di terminal, baris status mpv dimatikan supaya tidak
menimpa gambar. Di mesin tanpa perangkat audio, mpv tetap memutar videonya.

Kontrol selama pemutaran memakai tombol bawaan pemutarnya. Di mpv dan ffplay:
`Space` untuk pause, panah kiri/kanan untuk maju-mundur, `9`/`0` untuk volume,
`q` untuk lanjut ke lagu berikutnya, dan `Ctrl+C` untuk berhenti.

| Opsi                 | Fungsi                                                    |
|----------------------|-----------------------------------------------------------|
| `--audio-only`       | Putar suaranya saja walaupun sumbernya video              |
| `--external`, `--hd` | Putar video HD di aplikasi pemutar                        |
| `--pl-quality P`     | Resolusi target untuk `--external` dan sixel/kitty (default 720; ditanyakan saat perlu mengunduh) |
| `--pl-vo`            | `tct` (default), `sixel`, atau `kitty`                    |
| `--loop`             | Ulangi file atau seluruh playlist                         |
| `--shuffle`          | Acak urutan playlist                                      |
| `--volume N`         | Volume awal, 0–200 (default 100)                          |
| `--start POS`        | Mulai dari detik tertentu, contoh `90` atau `1:30`        |
| `--speed X`          | Kecepatan putar, contoh `1.5` (mpv dan mplayer)           |
| `--subtitle FILE`    | Subtitle dari file terpisah (mpv)                         |
| `--pl-backend`       | Paksa pakai pemutar tertentu                              |
| `--no-recursive`     | Jangan ikut membaca subfolder                             |
| `--list`             | Tampilkan antrian saja, tanpa memutar                     |


## Media Downloader

Download video, musik, atau foto dari link. Yang didukung: YouTube, YouTube
Music, Pinterest, dan semua situs lain yang dikenali yt-dlp, misalnya TikTok,
Instagram, X/Twitter, Facebook, SoundCloud, Vimeo, Twitch, Bandcamp, dan
ratusan lainnya. Flag `-y` dan `-D` sama saja.

```bash
python freease.py -y "https://youtu.be/VIDEO_ID"
python freease.py -y "https://youtu.be/VIDEO_ID" --yt-mode video --yt-quality 4k
python freease.py -y "https://music.youtube.com/watch?v=ID" --yt-mode musik
python freease.py -y "https://music.youtube.com/watch?v=ID" --yt-mode musik \
  --yt-audio-format m4a --yt-bitrate 320
python freease.py -y "https://youtube.com/playlist?list=ID" --yt-mode musik --yt-playlist
python freease.py -D "https://www.tiktok.com/@user/video/ID"
```

Kalau mode dan kualitasnya tidak diisi, pilihannya akan ditanyakan. Untuk
link playlist, isi playlist ditampilkan dulu per halaman, lalu kamu memilih
item mana yang diunduh (`a` = semua, atau mis. `1-5,8`). Link dari
YouTube Music, SoundCloud, Bandcamp, Audiomack, dan Mixcloud otomatis
ditawarkan sebagai musik.

### Pinterest

```bash
python freease.py -y "https://www.pinterest.com/pin/ID/"     # satu pin
python freease.py -y "https://pin.it/xxxxxxx"                # link pendek dari aplikasi
python freease.py -y "https://www.pinterest.com/user/board/" # seluruh isi board
```

- **Pin video** diunduh seperti video biasa.
- **Pin foto** diunduh dalam resolusi asli (versi `originals`), bukan thumbnail.
  yt-dlp sendiri tidak bisa mengunduh pin foto, jadi freease mengambil URL
  gambarnya dari metadata lalu mengunduhnya langsung.
- **Board** diunduh pin per pin ke subfolder bernama sesuai board. Isinya
  boleh campuran foto dan video.

| Opsi                | Nilai                                                        |
|---------------------|--------------------------------------------------------------|
| `--yt-mode`         | `video`, atau `musik`/`audio`                                |
| `--yt-quality`      | `4k`, `2k`, `hd`/`1080`, `720`, `480`, `360`, `240`, `144`, `best` |
| `--yt-audio-format` | `mp3` (default), `m4a`, `opus`, `flac`, `wav`                |
| `--yt-bitrate`      | `128`, `192` (default), `256`, `320`                         |
| `--yt-container`    | `mp4` (default) atau `mkv`                                   |
| `--yt-playlist`     | Download seluruh playlist, album, atau board tanpa ditanya   |
| `--yt-items SEL`    | Hanya item playlist tertentu, mis. `1-5,8`                   |
| `--yt-dir`          | Folder tujuan (default `./freease_downloads`)                |
| `--yt-cookies FILE` | File `cookies.txt` (format Netscape) untuk konten yang butuh login / dibatasi umur |
| `--yt-browser-cookies B` | Ambil cookies langsung dari browser: `chrome`, `firefox`, `edge`, `brave`, … |
| `--yt-subs LANG`    | Sematkan subtitle ke video, mis. `id` atau `id,en` (butuh ffmpeg) |

Kalau resolusi yang diminta tidak tersedia, download turun ke resolusi
terdekat di bawahnya. Musik otomatis diberi tag judul, artis, dan cover.
Kalau download gagal atau dibatalkan, file sementara (`.part`, thumbnail,
subtitle) yang sempat dibuat langsung dibersihkan.

Gunakan hanya untuk konten milikmu sendiri atau yang memang boleh diunduh.


## Log Defender (blue team)

Membaca log server Anda, mengenali pola serangan, lalu meringkasnya jadi daftar
insiden per IP yang berperingkat. Modul ini **pasif**: hanya membaca log yang
sudah ada, tidak menyerang, tidak memblokir, dan tidak mengubah apa pun di
sistem. Cocok dipasangkan dengan lab uji sendiri — serangan yang Anda jalankan
akan muncul di sini sebagai insiden.

```bash
python freease.py -L auto                                    # cari log umum lalu deteksi
python freease.py -L /var/log/auth.log /var/log/nginx/access.log
python freease.py -L journal --since "1 hour ago"            # sshd dari journald
python freease.py -L docker:web-1 --enrich -o ./laporan      # docker + reputasi IP
journalctl -u ssh | python freease.py -L -                   # dari pipe
```

Sumber log boleh digabung dan formatnya dideteksi otomatis:

| Sumber          | Artinya                                              |
|-----------------|------------------------------------------------------|
| `FILE`          | file log apa saja (auth.log, secure, access.log, …)  |
| `auto`          | cari lokasi log umum di sistem ini                   |
| `journal`       | log sshd dari systemd-journald (`journalctl`)        |
| `docker:<nama>` | keluaran `docker logs <nama>`                        |
| `-`             | baca dari stdin (pipe)                               |

Yang dikenali:

- **SSH** — brute-force (gagal login beruntun), percobaan user tidak valid,
  enumerasi banyak username, dan login berhasil setelah banyak gagal. Yang
  terakhir dinilai **KRITIS**, karena itu indikasi brute-force yang tembus.
- **Web** (nginx/apache) — enumerasi path (banjir 404/penolakan), probing path
  sensitif (`/.env`, `/wp-login.php`, `/.git`, phpMyAdmin, …), User-Agent
  perkakas pemindai (sqlmap, nikto, nmap, …), tanda injeksi/traversal di URL,
  dan laju permintaan tidak wajar.

Dengan `--enrich`, IP penyerang teratas dicek ke modul reputasi IP (AbuseIPDB,
AlienVault OTX, ip-api) untuk menambahkan negara, ISP, dan skor risiko. Ambang
deteksi bisa diatur dengan `--ssh-threshold` dan `--web-threshold`. IP privat
atau LAN dilewati kecuali `--include-private`.

Hasil tampil sebagai daftar insiden berperingkat (KRITIS / TINGGI / SEDANG),
plus laporan JSON dan HTML. Exit code mengikuti tingkat tertinggi: `0` bersih,
`1` ada insiden, `2` ada yang kritis — berguna untuk pemantauan terjadwal
(mis. cron).

Membaca `/var/log/*` biasanya butuh hak akses root; jalankan dengan `sudo` bila
perlu. Gunakan hanya pada log dari sistem milik Anda sendiri atau yang Anda
kelola.


## Semua opsi

| Opsi                    | Keterangan                                          |
|-------------------------|-----------------------------------------------------|
| `-d DOMAIN`             | Recon domain (5 modul paralel)                      |
| `-u USERNAME`           | Cek username di 23 platform                         |
| `-e EMAIL[,EMAIL]`      | Cek kebocoran email (HIBP)                          |
| `-i IP[,IP]`            | Reputasi IP atau domain                             |
| `-x FILE/URL`           | Ekstraksi metadata                                  |
| `-s URL`                | Periksa satu tautan                                 |
| `--scan-list FILE`      | Periksa banyak tautan dari file                     |
| `-S QUERY`              | Cari lagu/video/web, lalu putar, unduh, atau buka   |
| `-p SRC [SRC ...]`      | Putar audio/video (file, folder, atau link)         |
| `-y URL`, `-D URL`      | Download dari YouTube, Pinterest, dan situs lain    |
| `-L SRC [SRC ...]`      | Log Defender: deteksi serangan dari log             |
| `--since WAKTU`         | Batas waktu untuk `-L journal`/`docker` (mis. "1 hour ago") |
| `--enrich`              | Cek reputasi IP penyerang teratas (`-L`)            |
| `--ssh-threshold N`     | Ambang brute-force SSH per IP (default 10)          |
| `--web-threshold N`     | Ambang enumerasi path web per IP (default 25)       |
| `--include-private`     | Ikut laporkan IP privat/LAN di `-L`                 |
| `--hibp-key KEY`        | API key Have I Been Pwned                           |
| `--abuseipdb-key KEY`   | API key AbuseIPDB                                   |
| `--skip-portscan`       | Lewati port scan                                    |
| `--ports SPEC`          | Port yang dipindai: `default`, `top`, `22,80`, `1-1024` |
| `--port-timeout SEC`    | Timeout koneksi per port (default 2)                |
| `--offline`             | Scanner tanpa koneksi jaringan                      |
| `--deep`                | Scanner ikut membaca sumber HTML halaman tujuan     |
| `--urlhaus`             | Scanner ikut cek ke URLhaus                         |
| `--urlhaus-key KEY`     | Auth-Key abuse.ch untuk URLhaus                     |
| `--safebrowsing-key KEY`| Scanner ikut cek ke Google Safe Browsing            |
| `--scan-timeout SEC`    | Timeout tiap request scanner (default 10)           |
| `--max-redirects N`     | Batas redirect yang ditelusuri (default 10)         |
| `-o DIR`                | Folder laporan (default `./freease_output`)         |
| `--no-export`           | Tampilkan di terminal saja, tanpa file laporan      |
| `--no-pager`            | Cetak semua daftar sekaligus, tanpa halaman         |
| `--search-page N`       | Pencarian mulai dari halaman ke-N                   |
| `-V`, `--version`       | Tampilkan versi                                     |

Semua API key juga bisa diisi lewat environment variable supaya tidak
tersimpan di riwayat shell: `HIBP_API_KEY`, `ABUSEIPDB_API_KEY`,
`URLHAUS_AUTH_KEY`, dan `SAFEBROWSING_API_KEY`.

```bash
export HIBP_API_KEY="..."
python freease.py -e admin@example.com
```

Opsi lengkap pencarian, player, dan downloader ada di bagian masing-masing
di atas, atau lihat lewat `python freease.py --help`.

Penanda di output terminal:

| Penanda | Arti                     |
|---------|--------------------------|
| `[+]`   | berhasil / aman          |
| `[x]`   | gagal / bahaya           |
| `[!]`   | peringatan               |
| `[*]`   | informasi                |
| `[>]`   | sedang berjalan          |


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
freease_defend.py            Log Defender (deteksi serangan dari log)
freease_search.py            pencarian lagu, video, dan web
freease_player.py            media player
freease_youtube.py           media downloader (YouTube, Pinterest, dll)
freease_exiftool_module.py   ekstraksi metadata + penilaian risiko privasi
freease_ui.py                tombol panah & tampilan berhalaman (dipakai semua modul)
freease_version.py           nomor versi (satu-satunya tempat versi ditulis)
CHANGELOG.md                 catatan perubahan tiap versi
requirements.txt
```

Modul scanner, pencarian, player, dan downloader juga bisa dijalankan
sendiri tanpa lewat `freease.py`:

```bash
python freease_scan.py "https://contoh.xyz"
python freease_search.py "dewa 19 kangen"
python freease_player.py ./Music --shuffle
python freease_youtube.py "https://youtu.be/VIDEO_ID"
python freease_exiftool_module.py foto.jpg
```


## Batasan

- Hasil cek username untuk platform yang wajib login (Instagram, X, TikTok,
  LinkedIn, Pinterest) tidak bisa dipastikan, jadi akan muncul sebagai `UNKNOWN`.
- crt.sh, OTX, dan layanan publik lain kadang lambat atau menolak request.
  Kalau satu sumber gagal, modul lain tetap jalan.
- Scanner hanya bisa menilai apa yang terlihat dari luar. Halaman phishing yang
  dipasang di domain lama yang sudah diretas bisa saja lolos.
- Downloader dan pencarian bergantung pada yt-dlp. Kalau YouTube atau situs lain
  mengubah sistemnya, perbarui dengan `pip install -U yt-dlp`.
- Pin Pinterest yang diprivat atau butuh login tidak bisa diunduh. Hal yang
  sama berlaku di Instagram, Facebook, dan X: banyak kontennya hanya bisa
  diambil oleh akun yang sudah login. Untuk konten milikmu sendiri, pakai
  `--yt-cookies` atau `--yt-browser-cookies`.
- freease tidak melakukan brute force, eksploitasi, atau akses ke sistem
  tanpa izin.


## Versi & changelog

Nomor versi hanya ditulis di satu tempat, `freease_version.py`. Banner,
`--version`, laporan JSON/HTML, dan semua modul membacanya dari sana.
Riwayat perubahan ada di [CHANGELOG.md](CHANGELOG.md).

```bash
python freease.py --version
```


## Etika

freease hanya boleh dipakai pada aset milik sendiri atau aset pihak lain yang
sudah memberi izin tertulis. Baca [ETIKA.md](ETIKA.md) sebelum memakai modul
yang menyentuh sistem atau data orang lain. Penyalahgunaan adalah tanggung
jawab pengguna sepenuhnya.

## Lisensi

Lihat [LICENSE](LICENSE).
