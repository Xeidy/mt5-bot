import numpy as np
from bt import ema, rsi, atr, adx, bbands


def arrays(df):
    return (df[k].values for k in ("open", "high", "low", "close"))


def session_mask(df, start=7, end=20):
    h = df.index.hour
    return (h >= start) & (h < end)


# ---------------------------------------------------------------- strategi lama
def old_ema_rsi(df, sl=1.5, tp=2.0):
    o, h, l, c = arrays(df)
    f, s, t, r, a = ema(c, 9), ema(c, 21), ema(c, 50), rsi(c), atr(h, l, c)
    out = []
    for i in range(60, len(c) - 1):
        up = f[i - 1] <= s[i - 1] and f[i] > s[i]
        dn = f[i - 1] >= s[i - 1] and f[i] < s[i]
        if up and c[i] > t[i] and 50 < r[i] < 70:
            out.append((i, 1, sl * a[i], tp * a[i]))
        elif dn and c[i] < t[i] and 30 < r[i] < 50:
            out.append((i, -1, sl * a[i], tp * a[i]))
    return out


def old_scalp(df, sl=2.0, tp=0.4):
    o, h, l, c = arrays(df)
    f, a = ema(c, 9), atr(h, l, c)
    out = []
    for i in range(30, len(c) - 1):
        if c[i] > o[i] and c[i] > f[i]:
            out.append((i, 1, sl * a[i], tp * a[i]))
        elif c[i] < o[i] and c[i] < f[i]:
            out.append((i, -1, sl * a[i], tp * a[i]))
    return out


def layering_sim(df, spread, step=0.5, max_layers=5, tp=0.4, sl=2.0):
    """Simulasi preset agresif + layering (lot sama tiap layer).
    Return hasil per keranjang dalam R, R = rugi maksimum keranjang bila SL kena."""
    o, h, l, c = arrays(df)
    f, a = ema(c, 9), atr(h, l, c)
    n = len(c)
    res = []
    i = 30
    while i < n - 2:
        side = 1 if (c[i] > o[i] and c[i] > f[i]) else (-1 if (c[i] < o[i] and c[i] < f[i]) else 0)
        if side == 0:
            i += 1
            continue
        A = a[i]
        e0 = o[i + 1] + side * spread / 2
        entries = [e0]
        slp = e0 - side * (sl * A + step * A * (max_layers - 1))
        worst = sum(abs(e - slp) for e in [e0 - side * step * A * k for k in range(max_layers)])
        tpp = e0 + side * tp * A
        j = i + 1
        out = None
        while j < n:
            bid_hi = h[j] - spread / 2; bid_lo = l[j] - spread / 2
            ask_hi = h[j] + spread / 2; ask_lo = l[j] + spread / 2
            hi, lo = (bid_hi, bid_lo) if side > 0 else (ask_hi, ask_lo)
            # layer baru bila harga melawan
            while len(entries) < max_layers:
                nxt = entries[-1] - side * step * A
                if (side > 0 and lo <= nxt) or (side < 0 and hi >= nxt):
                    entries.append(nxt)
                    avg = sum(entries) / len(entries)
                    tpp = avg + side * tp * A
                else:
                    break
            if (side > 0 and lo <= slp) or (side < 0 and hi >= slp):
                out = slp; break
            if (side > 0 and hi >= tpp) or (side < 0 and lo <= tpp):
                out = tpp; break
            j += 1
        if out is None:
            out = c[n - 1]
        pnl = sum((out - e) * side for e in entries)
        res.append(pnl / worst)
        i = j + 1
    return np.array(res)


# ---------------------------------------------------------------- calon baru
def trend_pullback(df, sl_atr=1.5, rr=2.0, adx_min=20, sess=True):
    """Tren (EMA50 vs EMA200 + ADX) -> tunggu pullback ke EMA20 -> entry saat candle
    menutup kembali searah tren. SL di bawah swing / 1.5 ATR, TP = rr × risiko."""
    o, h, l, c = arrays(df)
    e20, e50, e200 = ema(c, 20), ema(c, 50), ema(c, 200)
    a, ad, r = atr(h, l, c), adx(h, l, c), rsi(c)
    ms = session_mask(df) if sess else np.ones(len(c), bool)
    out = []
    for i in range(210, len(c) - 1):
        if not ms[i] or ad[i] < adx_min:
            continue
        if e50[i] > e200[i] and c[i] > e50[i] and e50[i] > e50[i - 5]:
            if l[i] <= e20[i] and c[i] > e20[i] and c[i] > o[i] and r[i] > 45:
                swing = min(l[i - 4:i + 1])
                sld = max(c[i] - swing + 0.3 * a[i], sl_atr * a[i] * 0.7)
                sld = min(sld, sl_atr * a[i] * 1.5)
                out.append((i, 1, sld, rr * sld))
        elif e50[i] < e200[i] and c[i] < e50[i] and e50[i] < e50[i - 5]:
            if h[i] >= e20[i] and c[i] < e20[i] and c[i] < o[i] and r[i] < 55:
                swing = max(h[i - 4:i + 1])
                sld = max(swing - c[i] + 0.3 * a[i], sl_atr * a[i] * 0.7)
                sld = min(sld, sl_atr * a[i] * 1.5)
                out.append((i, -1, sld, rr * sld))
    return out


def bb_revert(df, adx_max=20, sl_atr=1.5, sess=True):
    """Pasar datar (ADX rendah): harga keluar Bollinger lalu menutup kembali ke dalam
    -> entry balik ke tengah. TP = garis tengah BB."""
    o, h, l, c = arrays(df)
    lo, mid, up = bbands(c)
    a, ad, r = atr(h, l, c), adx(h, l, c), rsi(c)
    ms = session_mask(df) if sess else np.ones(len(c), bool)
    out = []
    for i in range(30, len(c) - 1):
        if not ms[i] or ad[i] > adx_max or np.isnan(mid[i]):
            continue
        if c[i - 1] < lo[i - 1] and c[i] > lo[i] and r[i - 1] < 35:
            tpd = mid[i] - c[i]
            if tpd > 0.5 * a[i]:
                out.append((i, 1, sl_atr * a[i], tpd))
        elif c[i - 1] > up[i - 1] and c[i] < up[i] and r[i - 1] > 65:
            tpd = c[i] - mid[i]
            if tpd > 0.5 * a[i]:
                out.append((i, -1, sl_atr * a[i], tpd))
    return out


def donchian(df, n=20, sl_atr=2.0, sess=True):
    o, h, l, c = arrays(df)
    a, e200 = atr(h, l, c), ema(c, 200)
    ms = session_mask(df) if sess else np.ones(len(c), bool)
    out = []
    for i in range(210, len(c) - 1):
        if not ms[i]:
            continue
        hh = max(h[i - n:i]); ll = min(l[i - n:i])
        if c[i] > hh and c[i] > e200[i]:
            out.append((i, 1, sl_atr * a[i], 0))
        elif c[i] < ll and c[i] < e200[i]:
            out.append((i, -1, sl_atr * a[i], 0))
    return out
