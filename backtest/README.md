# Backtest strategi mt5-bot

Data yang dipakai: candle 1 menit OANDA tahun 2018 dan 2019 (repo `FutureSharks/financial-data`), untuk EURUSD, GBPUSD, AUDUSD, USDCAD, EURJPY, dan XAUUSD.

Cara simulasi:
- Entry di harga open candle berikutnya, sudah ditambah spread (EURUSD 1,2 pip, GBPUSD 1,8, AUDUSD 1,5, USDCAD 1,8, EURJPY 1,8, XAU 30 sen).
- Jika SL dan TP sama-sama kena di satu candle, SL dihitung kena duluan.
- Hasil dicatat dalam R, yaitu kelipatan dari risiko per trade.

| Strategi | TF | Tahun | Trade | Win rate | Rata-rata per trade |
|---|---|---|---|---|---|
| Scalping agresif + layering (preset lama) | M1 | 2019 | ~74 rb keranjang | ~70% | **−0,18 R** |
| Scalping agresif tanpa layering | M1 | 2019 | ~295 rb | ~30% | **−0,58 R** |
| EMA cross + RSI | M1 | 2019 | ~47 rb | ~15% | **−0,63 R** |
| **Trend Breakout (55, SL 3 ATR, trailing 5 ATR, 07–20 UTC)** | H1 | 2018 | 367 | 32% | **−0,01 R** (impas) |
| **Trend Breakout** | H1 | 2019 | 371 | 35% | **+0,15 R** |

| Limit Buy & Sell OCO (jarak 0,3 / TP 0,5 / SL 1,0 ATR), simulasi menit-per-menit | H1 | 2018 / 2019 | ~11 rb / ~11 rb | ~58% | **−0,11 / −0,15 R** |
| Limit OCO, semua 162 variasi (M5/M15/H1, limit & stop) | – | 2018–2019 | – | – | **semua rugi** |

Kesimpulan: di M1, spread kira-kira sama besar dengan ATR, jadi semua strategi M1 rugi. Trend Breakout H1 impas di 2018 dan untung di 2019. Tidak ada strategi yang terbukti selalu untung.

Untuk menjalankan ulang: `python run.py old`, `python grid2.py`, `python detail.py`. Path data ada di `bt.py`.
