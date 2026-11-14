# GT Launcher

Launcher Android sederhana (Kotlin + XML, tanpa Compose) yang dibuild otomatis oleh GitHub Actions.
Cocok ditulis di laptop spek rendah: laptop cuma dipakai menulis kode, server GitHub yang membuild APK.

## Fitur
- Tema bergaya iOS: ikon squircle seragam, jam kaca, dock kaca
- Banyak halaman layar utama (geser kiri-kanan, titik indikator, maks. 8 halaman)
- Mode edit ala iOS: tahan ikon/area kosong, ikon bergoyang, tombol - / x di pojok untuk hapus
- Seret ikon, widget, dan jam ke posisi baru; posisi tersimpan. Seret ke tepi layar untuk pindah/buat halaman
- Folder: seret ikon ke ikon lain; ketuk folder untuk membuka dan mengganti nama; seret keluar untuk mengeluarkan
- Dock: atur urutan dengan drag, bisa berisi folder (4 slot, 5 jika grid 5+ kolom)
- Ukuran grid 4x5 sampai 6x7 (Pengaturan > Ukuran grid)
- Widget Android: pemilih dengan gambar pratinjau, geser, ubah ukuran (ketuk widget saat mode edit)
- Kunci tata letak (tombol Kunci di mode edit, atau Pengaturan)
- Aplikasi baru otomatis masuk ke slot kosong dengan titik biru sampai pertama kali dibuka
- Pustaka Aplikasi: geser ke atas; grid, pencarian, tombol Go membuka hasil teratas
- Geser ke bawah di layar utama: panel notifikasi
- Pengaturan: ukuran ikon, nama aplikasi, warna nama, widget jam, transparansi kaca, refresh rate tertinggi,
  aplikasi tersembunyi, atur ulang, jadikan launcher default

## Belum ada (menyusul)
Badge notifikasi, icon pack, gesture ketuk dua kali untuk kunci layar, backup pengaturan.

## Pembaruan otomatis dengan Obtainium
Setiap `git push` ke branch main membuat GitHub Release baru berisi APK (versi naik sendiri, mis. 1.4.7).
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
