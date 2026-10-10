# Etika & Penggunaan yang Bertanggung Jawab

> Dokumen ini adalah bagian dari freease dan berlaku untuk setiap orang yang
> menggunakan, menyalin, atau mengembangkannya. Dengan menjalankan freease,
> Anda dianggap menyetujui ketentuan di bawah ini.
>
> **Versi bahasa Inggris ada di bawah / English version below.**

---

## Ringkas

freease adalah alat bantu keamanan. Alat tidak punya niat; yang punya niat
adalah orang yang memegangnya. Sebagian besar kemampuan freease bisa dipakai
untuk melindungi, dan bisa juga disalahgunakan untuk merugikan. Garis
pemisahnya satu dan sederhana: **izin**.

Gunakan freease hanya pada:

1. aset yang **Anda miliki sendiri**, atau
2. aset milik pihak lain yang telah memberi Anda **izin tertulis** untuk diuji.

Di luar dua hal itu, jangan dijalankan.

---

## Yang boleh

- Mengaudit domain, server, akun, dan metadata **milik Anda sendiri**.
- Menguji aset pihak lain dalam rangka pekerjaan yang **berizin**: kontrak
  penetration testing, program bug bounty sesuai aturannya, atau penugasan
  tertulis dari pemilik aset.
- Memeriksa sebuah tautan sebelum Anda buka, untuk melindungi diri dari
  phishing.
- Belajar, meneliti, dan mengajar keamanan di lingkungan yang Anda kendalikan
  (laboratorium, mesin virtual, CTF, target latihan yang memang disediakan).

## Yang dilarang

- Mengakses, memindai, atau mengumpulkan data dari sistem **tanpa izin**
  pemiliknya.
- Mengumpulkan atau menyebarkan **data pribadi orang lain** tanpa dasar yang
  sah — termasuk menguntit (*stalking*), doxing, atau pelecehan.
- Memakai informasi hasil freease untuk menipu, memeras, mengakses akun orang
  lain, atau tindakan melawan hukum lainnya.
- Menjadikan freease bagian dari serangan terhadap pihak ketiga.

Bahwa sebuah data "tersedia publik" **tidak** otomatis membuat pengumpulan dan
penggunaannya menjadi sah. Konteks dan tujuan tetap menentukan.

---

## Dasar hukum (Indonesia)

Bagian ini bukan nasihat hukum, dan penulisnya bukan ahli hukum. Nomor dan isi
pasal dapat berubah; **periksa sendiri ke teks resmi** sebelum mengandalkannya.
Tujuannya hanya mengingatkan bahwa penyalahgunaan alat seperti ini punya
konsekuensi nyata.

- **UU ITE** — UU No. 11 Tahun 2008 tentang Informasi dan Transaksi
  Elektronik, sebagaimana diubah terakhir dengan UU No. 1 Tahun 2024.
  Mengakses sistem elektronik milik orang lain dengan cara apa pun tanpa hak
  diatur di sekitar **Pasal 30**, dengan ancaman pidana di sekitar **Pasal 46**.
- **UU PDP** — UU No. 27 Tahun 2022 tentang Pelindungan Data Pribadi.
  Memperoleh atau mengumpulkan data pribadi yang bukan miliknya secara melawan
  hukum diatur di sekitar **Pasal 65**, dengan ancaman pidana di sekitar
  **Pasal 67**. Ini paling relevan untuk modul yang menyentuh data orang lain
  (pencarian username, cek email, reputasi IP).

Di luar Indonesia, berlaku hukum di wilayah Anda dan wilayah tempat target
berada. Banyak negara punya aturan sejenis (misalnya CFAA di Amerika Serikat,
Computer Misuse Act di Inggris, GDPR di Uni Eropa).

---

## Tanggung jawab

Tanggung jawab atas penggunaan freease sepenuhnya ada pada **penggunanya**.
Penulis dan kontributor freease tidak bertanggung jawab atas kerugian atau
pelanggaran hukum yang timbul dari penyalahgunaan. Ketentuan ini melengkapi,
bukan menggantikan, penafian pada berkas [LICENSE](LICENSE).

## Melaporkan penyalahgunaan

Kalau Anda menemukan freease dipakai untuk merugikan orang lain, laporkan ke
pemelihara proyek melalui *issue* di repositori. Kalau Anda menemukan celah
keamanan di freease sendiri, laporkan secara privat lebih dulu, jangan
diumumkan sebelum ada perbaikan.

---
---

# Ethics & Responsible Use

> This document is part of freease and applies to everyone who uses, copies, or
> develops it. By running freease, you agree to the terms below.

## In short

freease is a security tool. A tool has no intent; the person holding it does.
Most of what freease can do may be used to protect, and may also be misused to
harm. The dividing line is one simple thing: **permission.**

Use freease only on:

1. assets you **own yourself**, or
2. assets belonging to others who have given you **written permission** to test.

Anything beyond those two, do not run.

## What is allowed

- Auditing domains, servers, accounts, and metadata that are **your own**.
- Testing someone else's assets as part of **authorized** work: a penetration
  testing contract, a bug bounty program within its rules, or a written
  engagement from the asset owner.
- Checking a link before you open it, to protect yourself from phishing.
- Learning, researching, and teaching security in environments you control
  (labs, virtual machines, CTFs, intentionally vulnerable practice targets).

## What is prohibited

- Accessing, scanning, or collecting data from systems **without the owner's
  permission**.
- Collecting or distributing **other people's personal data** without a lawful
  basis — including stalking, doxing, or harassment.
- Using information from freease to deceive, extort, access other people's
  accounts, or commit any other unlawful act.
- Making freease part of an attack against a third party.

The fact that data is "publicly available" does **not** by itself make
collecting and using it lawful. Context and purpose still decide.

## Legal note

This section is not legal advice, and the author is not a lawyer. Statute
numbers and wording change; **verify against official texts** before relying on
them. The point is only to remind you that misusing a tool like this has real
consequences.

In Indonesia, the most relevant laws are the Electronic Information and
Transactions Law (UU ITE, Law 11/2008 as last amended by Law 1/2024) on
unauthorized access to electronic systems, and the Personal Data Protection Law
(UU PDP, Law 27/2022) on unlawfully obtaining or collecting other people's
personal data. Outside Indonesia, the laws of your jurisdiction and of the
target's jurisdiction apply — many countries have equivalents (for example the
CFAA in the United States, the Computer Misuse Act in the United Kingdom, and
the GDPR across the European Union).

## Responsibility

Responsibility for using freease rests entirely with its **user**. The authors
and contributors of freease are not liable for any damage or legal violation
arising from misuse. This complements, and does not replace, the disclaimer in
the [LICENSE](LICENSE) file.

## Reporting misuse

If you find freease being used to harm others, report it to the project
maintainer via an issue in the repository. If you find a security flaw in
freease itself, report it privately first; do not disclose it publicly before a
fix is available.
