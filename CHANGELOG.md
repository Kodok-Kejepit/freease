# Changelog

Semua perubahan penting freease dicatat di file ini.

## [1.64.2]

### Umum
- Nomor versi kini hanya ditulis di satu tempat (`freease_version.py`). Banner,
  `--version`, laporan JSON/HTML, URL scanner, dan modul ExifTool membacanya
  dari sana.
- Banner diperbaiki: tulisan **FREEASE** (sebelumnya kurang satu huruf E).
- Semua API key bisa diisi lewat environment variable: `HIBP_API_KEY`,
  `ABUSEIPDB_API_KEY`, `URLHAUS_AUTH_KEY`, `SAFEBROWSING_API_KEY`.

### Navigasi halaman (tombol panah) — berlaku di semua modul
- Modul baru `freease_ui.py`: semua daftar panjang tampil per halaman.
  `↓`/`→`/`PgDn` halaman berikutnya, `↑`/`←`/`PgUp` sebelumnya, `Home`/`End`
  halaman pertama/terakhir. Halaman berganti di tempat tanpa menumpuk tabel.
- Pencarian (`-S`): `↓` memuat hasil berikutnya langsung dari sumbernya
  (11–20, 21–30, … sampai 300 hasil) untuk YouTube, YouTube Music, SoundCloud,
  dan web. Nomor berlanjut lintas halaman, halaman yang sudah tampil tidak
  berubah isinya, dan tidak ada hasil ganda. Opsi baru `--search-page N`.
  `a` kini berarti semua hasil di halaman yang sedang tampil.
- Downloader (`-y`): isi playlist ditampilkan berhalaman, lalu bisa memilih
  item tertentu (mis. `1-5,8`). Opsi baru `--yt-items`.
- Antrian player, daftar subdomain (beserta IP), port terbuka, dan ringkasan
  `--scan-list` ikut berhalaman.
- Output yang dialihkan ke file/pipe tetap dicetak penuh. Paging bisa
  dimatikan dengan `--no-pager` atau `FREEASE_NO_PAGER=1`.

### Recon domain (`-d`)
- DNS: record CAA dan status DNSSEC.
- Subdomain dari Certificate Transparency kini dicek mana yang masih hidup
  (resolve ke IP), ditandai hijau di terminal dan laporan HTML.
- Audit konfigurasi web: kualitas HSTS, CSP (`unsafe-inline`, `unsafe-eval`,
  wildcard), clickjacking, CORS, header yang membocorkan versi, meta generator,
  dan flag cookie (`Secure`, `HttpOnly`, `SameSite`).
- Pengecekan `security.txt` (RFC 9116), termasuk tanggal kedaluwarsanya.
- Deteksi CMS/framework baru: Ghost, Blogger, Squarespace, Webflow, Next.js,
  Nuxt, Laravel.
- SSL/TLS: versi protokol, cipher, deteksi self-signed, peringatan protokol
  usang dan sertifikat yang hampir/sudah kedaluwarsa. Detail sertifikat tetap
  dibaca walau verifikasinya gagal.
- Port scanner: 47 port default (sebelumnya 22), termasuk SMB, VNC, MSSQL,
  Oracle, NFS, Docker API, Kubernetes API, Memcached, CouchDB, RabbitMQ.
  Opsi baru `--ports` dan `--port-timeout`. Banner port HTTP dibaca lewat
  `HEAD`. Layanan yang sering tanpa autentikasi ditandai KRITIS.
- Port terbuka diurutkan: layanan kritis dan sensitif tampil paling atas.
- WHOIS: sisa hari sebelum domain kedaluwarsa.
- Email security: hitungan DNS lookup SPF rekursif (batas 10, RFC 7208),
  peringatan mekanisme `ptr`, include yang tidak bisa di-resolve, DMARC
  `sp=none`, record DMARC ganda, tag `p=` yang hilang, pengecekan TLS-RPT,
  dan 48 selector DKIM (sebelumnya 19).
- Intelligence Summary ikut menghitung temuan konfigurasi web, port kritis,
  sertifikat yang hampir kedaluwarsa, dan domain yang hampir kedaluwarsa.

### Username (`-u`)
- 3 platform baru: Codeberg, Lichess, Chess.com (total 23).
- Hasil diurutkan: akun yang ditemukan tampil paling atas.
- Coba ulang sekali saat timeout atau rate limit (HTTP 429).

### Kebocoran email (`-e`)
- Saat kena rate limit, menunggu sesuai `Retry-After` lalu mencoba lagi.

### Reputasi IP (`-i`)
- Reverse DNS (PTR) untuk tiap IP.
- Risiko ditulis `UNKNOWN` kalau tidak ada feed reputasi yang bisa dihubungi
  (sebelumnya terbaca `LOW`).

### Metadata (`-x`)
- `freease_exiftool_module.py` kini menjadi satu-satunya sumber modul ExifTool
  dan bisa dijalankan sendiri.
- Penilaian risiko privasi: lokasi, identitas (nama pembuat/pemilik/perusahaan),
  dan perangkat (nomor seri, model, software), plus perintah untuk menghapus
  metadata.
- Field baru: Artist, OwnerName, SerialNumber, HostComputer, LastModifiedBy,
  Company, CreatorTool, GPSDateTime, dan lainnya.
- Ukuran file remote dicek dari `Content-Length` sebelum diunduh.

### URL Safety Scanner (`-s`)
- `--deep` kini berfungsi: membaca sumber HTML halaman tujuan sebagai teks
  untuk mendeteksi form password, form ke domain lain, meta refresh, judul
  yang menyebut brand, JavaScript yang diobfuskasi, iframe tersembunyi, dan
  permintaan seed phrase.
- Deteksi IP yang disamarkan (desimal/hex/oktal) dan host IPv6.
- Deteksi homograph Unicode: huruf non-Latin dan campuran aksara.
- Domain tujuan akhir setelah redirect ikut dinilai, termasuk umur domainnya.
- Brand lokal dan global baru, plus daftar domain resmi tambahan supaya
  `google.co.id`, `user.github.io`, atau `klikbca.com` tidak dianggap tiruan.
  Brand pendek (BCA, BRI, OVO, DANA) tidak lagi salah cocok dengan kata lain
  seperti `fabric`.
- Rantai redirect pindah ke `GET` (tanpa membaca isi) kalau server menolak `HEAD`.
- Sertifikat TLS dicek di port yang benar untuk URL dengan port khusus.
- URLhaus memakai Auth-Key (`--urlhaus-key`), sesuai aturan baru abuse.ch.
  URL awal dan URL tujuan sama-sama dicek ke URLhaus dan Safe Browsing.
- Kegagalan database ancaman ditampilkan, tidak lagi diam-diam dianggap bersih.
- Satu URL yang gagal tidak menghentikan pemindaian `--scan-list`.
- `--scan-list` diakhiri tabel ringkasan, URL paling berisiko di atas.

### Downloader (`-y`)
- Opsi baru `--yt-cookies`, `--yt-browser-cookies`, dan `--yt-subs`.
- Daftar file hasil unduhan lebih andal dan tidak tercatat ganda.
- File sementara dan thumbnail dibersihkan kalau download gagal/dibatalkan.

### Player (`-p`)
- `--loop` untuk satu file kini berfungsi di semua backend, bukan hanya mpv.
- Pengulangan berhenti otomatis kalau tidak ada track yang berhasil diputar.
- Volume dan `--start` berlaku juga di mpg123.
- `--start` dan `--subtitle` yang tidak valid diberi peringatan, bukan
  membuat player gagal.
