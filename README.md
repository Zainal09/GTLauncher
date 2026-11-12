# GT Launcher

Launcher Android sederhana (Kotlin + XML, tanpa Compose) yang dibuild otomatis oleh GitHub Actions.
Cocok ditulis di laptop spek rendah: laptop cuma dipakai menulis kode, server GitHub yang membuild APK.

## Fitur
- Tema bergaya iOS: ikon squircle seragam, widget jam kaca, dock kaca
- Mode edit ala iOS: tahan lama, ikon bergoyang (jiggle mode), tombol hapus (-) di pojok, dan tombol Selesai
- Drag and drop: geser posisi ikon di layar utama dan antar-halaman otomatis saat digeser ke tepi layar
- Folder: seret ikon ke ikon lain untuk membuat folder, pratinjau mini 3x3 kaca, dialog buka folder dengan nama yang bisa diubah
- Dock interaktif: atur urutan ikon dengan drag, folder di dock, dan tombol Pustaka Aplikasi di dock (opsional di Pengaturan)
- Banyak halaman layar utama geser horizontal dengan titik indikator (dots indicator)
- Ukuran grid bebas: 4x4, 4x5, 4x6, 5x5, 5x6, 6x6, hingga 6x7
- Kunci tata letak (mencegah perubahan/penghapusan tidak sengaja)
- Aplikasi baru otomatis dialokasikan ke sel/halaman kosong, halaman dituju otomatis, dan ikon diberi sorotan
- Widget Android dari aplikasi lain (cuaca, kalender, dll.): tahan area kosong > Tambah widget; pemilih widget menampilkan pratinjau gambar
- Ubah ukuran widget di mode edit: tombol panah di pojok kanan bawah, pilih 1x1, 2x2, 4x2, dan ukuran lain yang muat
- Pustaka Aplikasi: geser ke atas; grid, pencarian, tombol Go membuka hasil teratas
- Kategori otomatis ala iOS App Library: Semua, Sosial, Game, Produktivitas, Hiburan, Lainnya
- Indeks huruf A-Z di tepi kanan dengan fast scroller
- Urutkan aplikasi: A-Z, terbaru dipasang, paling sering dipakai
- Baris "Terakhir dipakai" dan "Paling sering" di atas daftar (butuh izin Usage Access)
- Ganti nama dan ganti ikon per aplikasi (ikon dari galeri, disimpan permanen)
- Pilih banyak aplikasi sekaligus untuk ditambahkan ke layar utama, disembunyikan, atau dicopot
- Geser ke bawah di layar utama: panel notifikasi
- Tahan aplikasi di Pustaka: tambah ke layar utama / dock, ganti nama/ikon, pilih banyak, sembunyikan, info, copot
- Ketuk widget jam: buka aplikasi jam
- Animasi: aplikasi membesar dari ikon saat dibuka, layar utama memudar masuk saat kembali
- Pengaturan (tahan area kosong > Pengaturan launcher): ukuran ikon, grid, kunci tata letak, tombol dock, kelola halaman, nama aplikasi, warna nama,
  widget jam (tampil, 12/24 jam, tanggal, warna), transparansi kaca, paksa refresh rate tertinggi,
  aplikasi tersembunyi, atur ulang, jadikan launcher default, serta pengaturan Pustaka Aplikasi

## Fokus pembaruan ini (Fase 3)
Bagian 1 (penempatan & layout) dan bagian 2 (Pustaka Aplikasi) diprioritaskan:
- resize widget + pemilih widget berpratinjau
- kategori otomatis, indeks A-Z, urutan, baris terakhir/paling sering
- ganti nama/ikon per aplikasi, pilih banyak aplikasi
- sorotan aplikasi baru

Fitur bagian 3-10 (pencarian universal, gesture lanjutan, widget bawaan, tema lanjutan, control center, privasi, mode gamer, backup) menyusul.

## Izin opsional
- **Usage Access** (Pengaturan > Akses khusus > Akses penggunaan): baris "Terakhir dipakai"/"Paling sering".
- **Akses notifikasi**: badge dan pratinjau notifikasi (fitur lanjutan).
- **Aksesibilitas**: kunci layar dari gesture (fitur lanjutan).


## Pembaruan otomatis dengan Obtainium
Setiap `git push` ke branch main membuat GitHub Release baru berisi APK (versi naik sendiri, mis. 1.3.7).
1. Pasang **Obtainium** di HP (unduh APK-nya dari https://github.com/ImranR98/Obtainium/releases).
2. Buka Obtainium, ketuk **Add App**, tempel alamat repo `https://github.com/USERNAME/GTLauncher`, lalu **Add**.
3. Pasang GT Launcher lewat Obtainium. Setelah itu, versi baru muncul sebagai pembaruan, tinggal ketuk **Update**.

## Cara pakai (sekali setup)

### 1. Siapkan
1. Buat akun di https://github.com
2. Pasang **Git** (https://git-scm.com) dan **VS Code** di laptop
3. Di GitHub, buat repo baru bernama `GTLauncher`, pilih **Public** (build gratis tanpa batas menit untuk repo publik), jangan centang README/.gitignore

### 2. Upload proyek ini dari VS Code
Buka folder `GTLauncher` di VS Code, buka Terminal (Ctrl + `), lalu:

```
git init
git add .
git commit -m "launcher pertama"
git branch -M main
git remote add origin https://github.com/USERNAME_KAMU/GTLauncher.git
git push -u origin main
```
(ganti `USERNAME_KAMU`; saat diminta login, ikuti jendela yang muncul)

### 3. Ambil APK
1. Buka repo di GitHub, tab **Actions**
2. Klik run **Build APK** yang paling atas, tunggu centang hijau (sekitar 3-8 menit)
3. Di bagian bawah halaman run, bagian **Artifacts**, unduh `GTLauncher-release-apk` (berisi file zip, ekstrak untuk mendapat `app-release.apk`)
4. Kirim ke HP, buka, izinkan "pasang dari sumber ini", lalu pasang

### 4. Jadikan launcher
Tekan tombol Home, pilih GT Launcher, lalu **Sekali saja** untuk uji coba. Kalau cocok:
**Pengaturan > Aplikasi default > Aplikasi layar utama > GT Launcher**.
Untuk kembali ke launcher bawaan, ganti di menu yang sama.

## Setiap kali mengubah kode
```
git add .
git commit -m "ubah sesuatu"
git push
```
Tunggu build selesai di tab Actions, unduh APK baru, lalu pasang (APK baru menimpa yang lama).

## Struktur proyek
```
.github/workflows/build.yml                  <- build otomatis
app/src/main/AndroidManifest.xml             <- pendaftaran sebagai launcher
app/src/main/java/com/gtlauncher/app/
    MainActivity.kt                          <- logika utama (drawer, dock, gesture)
    AppAdapter.kt                            <- daftar/grid aplikasi
    AppInfo.kt                               <- data aplikasi
app/src/main/res/layout/                     <- tampilan (XML)
app/src/main/res/values/                     <- warna, teks, tema
```

## Kalau build gagal
Buka run yang merah di tab Actions, klik langkah **Build APK release**, salin pesan error
(baris yang mengandung `error:` atau `e: file://...`), lalu tanyakan ke Claude.

## Catatan
- Tidak ada Gradle wrapper di proyek ini; workflow memasang Gradle 8.9 sendiri.
- Kalau HP menutup launcher di latar belakang, atur baterai GT Launcher ke **Tanpa batasan**.
- Hasilnya APK release (ditandatangani kunci tetap di app/launcher.keystore), cukup untuk pemakaian pribadi.
