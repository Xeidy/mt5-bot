# mt5-bot (Android)

Aplikasi Android untuk auto trading MetaTrader 5. Aplikasi terhubung ke akun MT5 melalui **MetaApi** (metaapi.cloud), jadi tidak butuh PC.

## Download APK
Buka tab **Releases** di repo ini, lalu unduh `mt5-bot.apk` dari build terbaru. Setiap kali ada push ke `main`, GitHub Actions membangun APK baru secara otomatis.

## Persiapan
1. Daftar di https://app.metaapi.cloud.
2. **Accounts → Add account**: isi login, password (password trading, bukan investor), dan server MT5. Pilih platform MT5.
3. Salin **Account ID** akun tersebut, dan **API token** dari menu *API access*.
4. Install APK. Kalau Android minta izin, izinkan *install dari sumber tidak dikenal*.
5. Buka aplikasi → tab **Akun** → isi Account ID dan token → **Simpan & hubungkan**.
6. Masih di tab Akun, tekan **Matikan optimasi baterai**.
7. Tab **Settings** → isi **Pair** (mis. `XAUUSD,EURUSD`, atau `ALL` untuk 28 pair forex + emas) dan risiko. Akhiran broker seperti `m` di Exness dicocokkan otomatis.
8. Tab **Bot** → **Start bot**.

## Cara kerja
- Bot berjalan di HP sebagai *foreground service* (ada notifikasi permanen "mt5-bot"), jadi tetap jalan walau aplikasi ditutup atau layar mati.
- HP harus menyala dan tersambung internet. Kalau HP mati, bot berhenti; posisi yang sudah terbuka tetap aman karena SL/TP tersimpan di broker.
- Saat kamu membuka aplikasi lagi, bot otomatis melanjutkan jika sebelumnya dalam kondisi Running.
- Tombol back hanya meminimalkan aplikasi. Untuk menghentikan bot, tekan **Stop bot**.

## Strategi & risiko
Sama seperti versi PC:
- **Strategi**: EMA 9/21 cross + filter EMA 50 + RSI.
- **SL/TP**: berbasis ATR, dengan breakeven dan trailing.
- **Lot**: dihitung otomatis dari % risiko.
- **Pengaman**: batas rugi harian, batas drawdown, filter spread, dan jam trading (UTC).

**Maks pair bersamaan** membatasi berapa pair yang boleh punya posisi terbuka sekaligus, jadi total risiko tetap terkendali saat memantau banyak pair.

Bot menolak trading di akun **REAL** sampai opsi "Izinkan akun real" dicentang di Settings.

## Struktur kode
- `app/src/main/java/com/mt5bot/app/Engine.kt` — mesin bot (loop, risiko, manajemen posisi)
- `Strategy.kt` — indikator & sinyal
- `MetaApi.kt` — klien REST MetaApi
- `BotService.kt` — foreground service + notifikasi
- `MainActivity.kt` — WebView + jembatan JavaScript
- `app/src/main/assets/index.html` — tampilan aplikasi
