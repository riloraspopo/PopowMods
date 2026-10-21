# Panduan Compile Release dan Install APK

Panduan langkah demi langkah untuk meng-compile APK release Popow Mods dan menginstalnya ke perangkat Android.

---

## 1. Persyaratan Sistem

- **JDK 21**: Disarankan menggunakan Java bawaan Android Studio (`jbr`) atau OpenJDK 21.
- **Android SDK**: Sudah terkonfigurasi (misalnya melalui Android Studio).
- **Perangkat Android**: Sudah di-root (Magisk / KernelSU / APatch) dan terpasang **LSPosed** / **Vector Manager**.
- **ADB** *(Opsional tapi disarankan)*: Untuk instalasi langsung via kabel USB/WiFi debugging.

---

## 2. Cara Compile APK Release

### Windows (PowerShell)

1. Buka PowerShell di folder project:
   ```powershell
   cd d:\rilo\Project\PopowMods
   ```

2. Tentukan `JAVA_HOME` ke JDK 21 (sesuaikan path jika berbeda):
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   ```

3. Jalankan perintah build release:
   ```powershell
   .\gradlew.bat assembleRelease
   ```

### Linux / macOS

```bash
chmod +x gradlew
./gradlew assembleRelease
```

---

## 3. Lokasi File Output APK

Setelah build selesai dengan status `BUILD SUCCESSFUL`, file APK release otomatis tersimpan di:

```text
app/build/outputs/apk/release/app-release.apk
```

> **Catatan:** APK release sudah otomatis di-sign menggunakan signing config debug bawaan Gradle, sehingga langsung siap diinstal ke HP tanpa perlu signing manual.

---

## 4. Cara Install APK ke Perangkat

### Opsi A: Lewat ADB (Rekomendasi Cepat)

Pastikan USB Debugging di HP aktif dan HP terhubung ke komputer:

```powershell
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Opsi B: Transfer Manual ke HP

1. Salin file `app-release.apk` ke penyimpanan internal ponsel (via kabel data, Google Drive, atau WhatsApp Web).
2. Buka File Manager di ponsel.
3. Ketuk file `app-release.apk` lalu pilih **Install** / **Update**.

---

## 5. Aktivasi & Konfigurasi Modul

1. Buka aplikasi **LSPosed** / **Vector Manager**.
2. Masuk ke tab **Modules** dan aktifkan switch toggle untuk **Popow Mods**.
3. Pastikan scope berikut tercentang:
   - `System UI` (`com.android.systemui`)
   - `Nexus Launcher` / `Launcher3` (`com.google.android.apps.nexuslauncher` / `com.android.launcher3`)
4. Buka aplikasi **Popow Mods**:
   - Berikan izin root saat diminta.
   - Pada kartu **Pengaturan Interval Net Speed**, pilih preset (misal `500ms`) atau ketik angka kustom (misal `500` atau `0.5`).
   - Tekan tombol **💾 Simpan Interval**.
   - Tekan tombol **⚡ Restart System UI** untuk memastikan seluruh hook aktif dengan optimal.

