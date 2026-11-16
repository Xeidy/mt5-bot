"""Penyempurnaan: (A) ICT: sweep -> displacement -> FVG, killzone; (B) bias Trend H1 + entry FVG M15."""
import itertools
import numpy as np, pandas as pd
from bt import resample, atr, ema, stats, SPREAD
from final import load_year, SYMS
from smc import secs, run_setups

KZ = lambda hr: ((hr >= 7) & (hr < 10)) | ((hr >= 12) & (hr < 15))   # London & NY open (UTC)


def fvg_core(d, k_imp, bos_n=10):
    o, h, l, c = (d[k].values for k in ("open", "high", "low", "close"))
    a = atr(h, l, c); n = len(c)
    hh = pd.Series(h).shift(2).rolling(bos_n).max().values
    ll = pd.Series(l).shift(2).rolling(bos_n).min().values
    c1, o1 = np.roll(c, 1), np.roll(o, 1); h2, l2 = np.roll(h, 2), np.roll(l, 2)
    body = np.abs(c1 - o1)
    base = np.arange(n) > 215
    bull = base & (l - h2 >= 0.1 * a) & (c1 > o1) & (body >= k_imp * a) & (c1 > hh)
    bear = base & (l2 - h >= 0.1 * a) & (c1 < o1) & (body >= k_imp * a) & (c1 < ll)
    return o, h, l, c, a, h2, l2, bull, bear


def make(d, tf_sec, bull, bear, h, l, a, h2, l2, rr, entry, expire=8):
    t = secs(d.index); out = []
    for i in np.nonzero(bull)[0]:
        top, bot = l[i], h2[i]; en = top if entry == "top" else (top + bot) / 2
        sl = min(l2[i], l[i - 1]) - 0.1 * a[i]
        out.append((t[i] + tf_sec, t[i] + tf_sec * (1 + expire), 1, en, sl, en + rr * (en - sl), 0))
    for i in np.nonzero(bear)[0]:
        bot, top = h[i], l2[i]; en = bot if entry == "top" else (top + bot) / 2
        sl = max(h2[i], h[i - 1]) + 0.1 * a[i]
        out.append((t[i] + tf_sec, t[i] + tf_sec * (1 + expire), -1, en, sl, en - rr * (sl - en), 0))
    return out


def ict(d, tf_sec, k_imp, rr, entry, lookback=12):
    """Bullish: dalam `lookback` candle terakhir ada sweep low hari sebelumnya (atau Asia),
    lalu displacement naik membentuk FVG, di killzone."""
    o, h, l, c, a, h2, l2, bull, bear = fvg_core(d, k_imp)
    day = d.index.normalize()
    daily = d.groupby(day).agg(H=("high", "max"), L=("low", "min")).shift(1).reindex(day)
    PDH, PDL = daily.H.values, daily.L.values
    swept_low = pd.Series(l < PDL).rolling(lookback).max().fillna(0).values.astype(bool)
    swept_high = pd.Series(h > PDH).rolling(lookback).max().fillna(0).values.astype(bool)
    hr = d.index.hour.values
    bull = bull & swept_low & KZ(hr); bear = bear & swept_high & KZ(hr)
    return make(d, tf_sec, bull, bear, h, l, a, h2, l2, rr, entry)


def trend_fvg(d, h1, tf_sec, k_imp, rr, entry, n=55, hold=24):
    """Bias dari breakout Donchian H1 (55) + EMA200: berlaku `hold` jam setelah breakout.
    Entry: FVG searah bias di TF kecil, sesi 07-20 UTC."""
    hc, hh_, hl_ = h1.close.values, h1.high.values, h1.low.values
    e = ema(hc, 200)
    up = (hc > pd.Series(hh_).shift(1).rolling(n).max().values) & (hc > e)
    dn = (hc < pd.Series(hl_).shift(1).rolling(n).min().values) & (hc < e)
    bias = pd.Series(np.where(up, 1, np.where(dn, -1, np.nan)), index=h1.index + pd.Timedelta("1h"))
    bias = bias.ffill(limit=hold).fillna(0)
    b = bias.reindex(d.index, method="ffill").fillna(0).values
    o, h, l, c, a, h2, l2, bull, bear = fvg_core(d, k_imp)
    hr = d.index.hour.values; s = (hr >= 7) & (hr < 20)
    return make(d, tf_sec, bull & (b > 0) & s, bear & (b < 0) & s, h, l, a, h2, l2, rr, entry)


if __name__ == "__main__":
    raw = {(sy, y): load_year(sy, y) for sy in SYMS for y in (2018, 2019)}
    H1 = {k: resample(v, "60min") for k, v in raw.items()}
    rows = []
    for tf in ("5min", "15min"):
        tfs = int(pd.Timedelta(tf).total_seconds())
        D = {k: resample(v, tf) for k, v in raw.items()}
        for k_imp, rr, entry in itertools.product((0.8, 1.5), (2.0, 3.0), ("top", "mid")):
            for key, d in D.items():
                sp = SPREAD[key[0]]
                r = run_setups(ict(d, tfs, k_imp, rr, entry), raw[key], sp)
                rows.append(dict(strat="ict_sweep_fvg", tf=tf, cfg=f"k{k_imp} rr{rr} {entry}", sym=key[0], year=key[1], **stats(r)))
                r = run_setups(trend_fvg(d, H1[key], tfs, k_imp, rr, entry), raw[key], sp)
                rows.append(dict(strat="trendH1_fvg", tf=tf, cfg=f"k{k_imp} rr{rr} {entry}", sym=key[0], year=key[1], **stats(r)))
        print("done", tf, flush=True)
    df = pd.DataFrame(rows); df.to_csv("smc2.csv", index=False)
    g = df.groupby(["strat", "tf", "cfg", "year"]).agg(exp=("exp", "mean"), wr=("wr", "mean"), n=("n", "sum"), pos=("exp", lambda x: int((x > 0).sum()))).reset_index()
    p = g.pivot_table(index=["strat", "tf", "cfg"], columns="year", values=["exp", "wr", "n", "pos"])
    p.columns = [f"{a}{b}" for a, b in p.columns]
    p["worst"] = p[["exp2018", "exp2019"]].min(axis=1)
    pd.set_option("display.width", 250)
    print(p.sort_values("worst", ascending=False).round(3).to_string())
