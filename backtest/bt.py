"""Backtester sederhana tapi jujur: entry di open candle berikutnya + spread,
SL/TP dicek pakai high/low, bila SL & TP kena di candle yang sama dianggap SL dulu."""
import numpy as np
import pandas as pd
from pathlib import Path

D = Path("/home/claude/futuresharks/financial-data/pyfinancialdata/data")

# spread dalam satuan harga (perkiraan broker retail)
SPREAD = {"EURUSD": 0.00012, "GBPUSD": 0.00018, "AUDUSD": 0.00015, "USDCAD": 0.00018, "EURJPY": 0.018, "XAUUSD": 0.30, "BTCUSD": None}


def load(sym):
    if sym == "BTCUSD":
        df = pd.read_csv(D / "cryptocurrencies/bitstamp/BTC_USD/2018.csv", parse_dates=["date"])
        df = df.rename(columns={"date": "time"})
    else:
        s = sym[:3] + "_" + sym[3:]
        parts = [pd.read_csv(D / f"currencies/oanda/{s}/2019/oanda-{s}-2019-{m}.csv", parse_dates=["time"])
                 for m in range(1, 13)]
        df = pd.concat(parts)
    df = df.sort_values("time").drop_duplicates("time").set_index("time")
    return df[["open", "high", "low", "close"]].astype(float)


def resample(df, rule):
    if rule == "1min":
        return df
    r = df.resample(rule, label="left", closed="left").agg(
        {"open": "first", "high": "max", "low": "min", "close": "last"})
    return r.dropna()


# ---------------------------------------------------------------- indikator
def ema(x, n):
    return pd.Series(x).ewm(span=n, adjust=False).mean().values


def rsi(c, n=14):
    d = np.diff(c, prepend=c[0])
    up = pd.Series(np.maximum(d, 0)).ewm(alpha=1 / n, adjust=False).mean()
    dn = pd.Series(np.maximum(-d, 0)).ewm(alpha=1 / n, adjust=False).mean()
    return (100 - 100 / (1 + up / dn.replace(0, 1e-12))).values


def atr(h, l, c, n=14):
    pc = np.roll(c, 1); pc[0] = c[0]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    return pd.Series(tr).ewm(alpha=1 / n, adjust=False).mean().values


def adx(h, l, c, n=14):
    up = h - np.roll(h, 1); dn = np.roll(l, 1) - l
    up[0] = dn[0] = 0
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    ndm = np.where((dn > up) & (dn > 0), dn, 0.0)
    a = atr(h, l, c, n)
    pdi = 100 * pd.Series(pdm).ewm(alpha=1 / n, adjust=False).mean().values / a
    ndi = 100 * pd.Series(ndm).ewm(alpha=1 / n, adjust=False).mean().values / a
    dx = 100 * abs(pdi - ndi) / np.maximum(pdi + ndi, 1e-12)
    return pd.Series(dx).ewm(alpha=1 / n, adjust=False).mean().values


def bbands(c, n=20, k=2.0):
    s = pd.Series(c)
    m = s.rolling(n).mean().values
    sd = s.rolling(n).std().values
    return m - k * sd, m, m + k * sd


# ---------------------------------------------------------------- simulasi 1 posisi
def simulate(df, sig, spread, be_r=None, trail_atr=None, max_bars=None, a=None):
    """sig: list of (i, side, sl_dist, tp_dist) – sinyal di close bar i, entry open bar i+1.
    Return list of R hasil per trade (setelah spread)."""
    o, h, l, c = (df[k].values for k in ("open", "high", "low", "close"))
    n = len(c)
    res = []
    busy_until = -1
    for (i, side, sld, tpd) in sig:
        if i <= busy_until or i + 1 >= n:
            continue
        e = o[i + 1] + (spread / 2 if side > 0 else -spread / 2)
        sl = e - side * sld
        tp = e + side * tpd if tpd else None
        R = sld
        j = i + 1
        out = None
        while j < n:
            # harga bid/ask: untuk buy keluar di bid (= mid - spread/2)
            hi = h[j] - side * spread / 2 if side > 0 else h[j] + spread / 2
            lo = l[j] - spread / 2 if side > 0 else l[j] + spread / 2
            if side > 0:
                if lo <= sl: out = sl; break
                if tp is not None and hi >= tp: out = tp; break
            else:
                if hi >= sl: out = sl; break
                if tp is not None and lo <= tp: out = tp; break
            # manajemen setelah candle tutup
            cl = c[j] - side * spread / 2
            gain = (cl - e) * side
            if be_r and gain >= be_r * R and (sl - e) * side < 0:
                sl = e
            if trail_atr and a is not None:
                cand = cl - side * trail_atr * a[j]
                if (cand - sl) * side > 0:
                    sl = cand
            if max_bars and j - i >= max_bars:
                out = cl; break
            j += 1
        if out is None:
            out = c[n - 1]
        res.append(((out - e) * side) / R)
        busy_until = j
    return np.array(res)


def stats(r):
    if len(r) == 0:
        return dict(n=0, wr=0, pf=0, exp=0, total=0, mdd=0)
    eq = np.cumsum(r)
    mdd = np.max(np.maximum.accumulate(eq) - eq) if len(eq) else 0
    gp = r[r > 0].sum(); gl = -r[r < 0].sum()
    return dict(n=len(r), wr=round(100 * np.mean(r > 0), 1),
                pf=round(gp / gl, 2) if gl > 0 else 99,
                exp=round(r.mean(), 3), total=round(r.sum(), 1), mdd=round(mdd, 1))
