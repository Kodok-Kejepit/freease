# Freease Music (Android)

Aplikasi musik pribadi tanpa iklan, terinspirasi Spotify, dengan sumber lagu dari
YouTube & YouTube Music. Native Android: **Kotlin + Jetpack Compose + Media3 (ExoPlayer)**.

## Fitur

**Putar & suara**
- Streaming audio kualitas tertinggi (Opus/AAC ±160–256 kbps), pilihan Normal/Hemat data, otomatis hemat di seluler (opsional)
- Jalan di latar belakang: notifikasi media, layar kunci, tombol headset/Bluetooth, tombol ❤ di notifikasi
- Fade halus saat play/pause (tanpa bunyi "klik"), crossfade 0–12 detik, gapless
- Equalizer bawaan (preset + band manual), bass boost, virtualizer 3D, penguat volume; juga terbuka untuk EQ sistem (Dolby, dll.)
- **Profil EQ per perangkat**: speaker HP, headset kabel, DAC USB, dan tiap perangkat Bluetooth punya setelan sendiri, berganti otomatis
- **Normalisasi volume** (LUFS, ITU-R BS.1770) dengan limiter: lagu pelan & keras terdengar sama; level Pelan/Normal/Keras
- Lewati bagian hening, kecepatan & nada (0,5×–2×), timer tidur (menit / akhir lagu) dengan fade-out
- Cache streaming (lagu yang pernah diputar tidak diunduh ulang), buffer besar supaya tidak putus-putus
- Mode **Lagu ↔ Video** di player yang sama (video HD sampai 1080p + audio terbaik), layar penuh, siaran LIVE (HLS)
- Antrean lengkap: putar berikutnya, tambah ke antrean, geser urutan, hapus; putar otomatis lagu serupa saat antrean habis; "Radio lagu"
- Antrean terakhir disimpan & dilanjutkan saat app dibuka lagi
- **Smart Shuffle**: tekan tombol acak 2× → lagu rekomendasi yang cocok dengan isi playlist ikut diselipkan
- **Mode Karaoke**: vokal dihilangkan dari lagu (paling bagus untuk rekaman studio stereo), pas dipadu lirik karaoke
- **Kecepatan & nada per lagu**: "Ingat untuk lagu ini" + naik/turun kunci per semitone
- **Canvas**: potongan video klip ±8 detik berulang di belakang player (hanya Wi-Fi secara bawaan);
  untuk versi audio, Freease mencari video klip resmi dari kanal artisnya
- **Musik di HP**: putar MP3/FLAC/M4A yang tersimpan di HP, lengkap dengan sampul album
- **Android Auto** (jelajah koleksi, cari, putar dari layar mobil) + **Mode Mobil** dengan tombol besar & cari pakai suara
- **Alarm musik**: bangun dengan lagu dari playlist pilihan, volume naik perlahan, tunda 10 menit;
  kalau internet mati memakai unduhan/musik di HP, atau nada alarm bawaan

**Jelajah**
- **Daily Mix**: 3–4 mix baru tiap hari — artis yang sering kamu dengar bersamaan dikelompokkan, lalu dicampur lagu baru yang mirip
- **Kenali Lagu** (seperti Shazam): dengarkan lagu di sekitar lewat mikrofon → judul, artis, sampul, langsung putar; ada tile Pengaturan Cepat & riwayat
- Beranda: sapaan, pintasan, Pilihan cepat (dari lagu yang kamu dengar), Baru diputar, Trending musik, lagu dari artis yang diikuti, playlist "Karena kamu mendengarkan …", tangga lagu, kategori suasana
- Cari: saran otomatis, riwayat pencarian, filter Lagu / Video / Album / Playlist / Artis, scroll tanpa batas, tempel link YouTube langsung
- Halaman artis (ikuti, radio artis, album & playlist), halaman playlist/album (simpan ke koleksi, unduh semua)
- Bagikan link YouTube ke Freease dari aplikasi lain → langsung diputar
- **Impor playlist Spotify**: tempel/bagikan link playlist atau album Spotify → tiap lagu dicarikan versi YouTube-nya (maks. 100 lagu per link)

**Koleksi**
- Lagu yang Disukai, playlist buatan sendiri (sampul mozaik, ubah nama, sematkan, urutkan, hapus), artis yang diikuti
- Unduhan offline (berjalan di latar belakang, opsi khusus Wi-Fi)
- Riwayat putar per hari, **Rekap Kamu** (total menit, grafik harian, artis & lagu teratas)
- **Freease Wrapped**: rekap setahun ala story (menit, artis & lagu teratas, bulan terpadat, kebiasaan, tipe pendengar),
  tiap slide bisa dibagikan sebagai gambar, lagu teratas bisa disimpan jadi playlist
- Lirik berjalan ala Spotify (YouTube Music / LyricFind, cadangan LRCLIB): kartu lirik yang bergulir halus
  di player + layar lirik penuh, titik "• • •" saat intro/jeda, ketuk baris untuk lompat
- **Sinkron lirik otomatis untuk video klip**: waktu lirik dibuat untuk versi lagu, jadi Freease
  mencocokkan suara video dengan suara versi lagunya (intro, adegan sisipan, bagian terpotong ikut
  terdeteksi) lalu menggeser tiap baris ke waktu yang pas. Sekali per video (±1,5 MB), hasilnya disimpan.
- **Lirik karaoke per kata** (kalau tersedia lirik berwaktu per kata), **cara baca latin** untuk lirik Korea & Jepang
- **Kartu lirik**: pilih sampai 6 baris → gambar siap dibagikan ke Instagram/WhatsApp (4 gaya warna)
- **Lirik melayang** di atas aplikasi lain + **lirik di layar kunci** (notifikasi)

**Tampilan**
- Tema gelap dengan 6 warna aksen (Aurora, Mint, Sunset, Ocean, Lime, Rose), mode AMOLED
- Warna latar player & halaman mengikuti warna sampul lagu
- Mini player (geser kiri/kanan untuk ganti lagu), artwork player bisa digeser untuk skip
- Widget layar utama: pemutar, **lirik berjalan**, dan **Lanjutkan** (antrean terakhir, Disukai, Daily Mix, playlist)

## Build

Butuh JDK 17+ dan Android SDK 35.

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # perlu signingConfig sendiri
```

Konfigurasi Gradle (`app/build.gradle.kts`, `gradle/libs.versions.toml`) sudah lengkap
dan bisa langsung di-build; silakan sesuaikan (applicationId, signing, versi) sesuai kebutuhan.

## Struktur

```
app/src/main/java/com/freease/music/
├── data/          model, Room DB, YouTube (NewPipeExtractor), lirik, pengaturan, unduhan,
│                  impor Spotify, musik lokal, alarm, Daily Mix, kenali lagu, Wrapped
├── playback/      PlaybackService (MediaLibraryService + Android Auto), resolver stream, cache,
│                  equalizer & profil per perangkat, normalisasi volume, fade/crossfade,
│                  timer tidur, lirik melayang, mode karaoke, Canvas, PlayerConnection (sisi UI)
└── ui/            tema, komponen, layar (Beranda, Cari, Koleksi, Artis, Playlist,
                   Statistik, Pengaturan, Equalizer), player (full, mini, lirik, antrean)
```

## Catatan

- YouTube sering mengubah sistemnya. Kalau suatu saat lagu gagal diputar/dicari,
  naikkan versi `newpipe` di `gradle/libs.versions.toml` ke rilis terbaru
  NewPipeExtractor lalu build ulang.
- Uji: `./gradlew testDebugUnitTest --tests "*LogicTest*"`. Ada juga uji screenshot
  Robolectric (`ScreenshotTest`) yang merender layar asli ke PNG:
  `./gradlew testDebugUnitTest --tests "*ScreenshotTest*" -PshotsDir=build/shots`.
- Kenali Lagu memakai layanan pengenal lagu milik Shazam (endpoint publik aplikasinya, bukan API resmi);
  kalau suatu saat berhenti bekerja, kemungkinan layanannya berubah.
- Untuk penggunaan pribadi.
