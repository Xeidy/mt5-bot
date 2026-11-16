"""Strategi SMC: FVG + BOS + liquidity sweep. Deteksi setup di candle TF (vektor),
eksekusi disimulasikan menit-per-menit (numba) dengan spread & aturan konservatif."""
import itertools, sys
import numpy as np, pandas as pd
from numba import njit
from bt import resample, atr, ema, stats, SPREAD
from final import load_year, SYMS


def secs(idx):
    return ((idx - pd.Timestamp(0)) // pd.Timedelta("1s")).values.astype(np.int64)


# ------------------------------------------------------------------ deteksi setup
def fvg_setups(d, tf_sec, k_imp=1.0, gap_min=0.1, bos_n=10, entry="top", sl_mode="zone",
               bias=True, rr=2.0, expire=12, sess=True):
    """Bullish FVG: low[i] > high[i-2]; candle i-1 = displacement (body >= k_imp*ATR)
    yang menembus swing high bos_n candle (BOS). Entry limit saat harga kembali ke FVG."""
    o, h, l, c = (d[k].values for k in ("open", "high", "low", "close"))
    a = atr(h, l, c); e = ema(c, 200)
    hr = d.index.hour.values; t = secs(d.index)
    n = len(c)
    hh = pd.Series(h).shift(2).rolling(bos_n).max().values   # swing high sebelum displacement
    ll = pd.Series(l).shift(2).rolling(bos_n).min().values
    body = np.abs(np.roll(c, 1) - np.roll(o, 1))              # body candle i-1
    c1 = np.roll(c, 1); o1 = np.roll(o, 1)
    h2 = np.roll(h, 2); l2 = np.roll(l, 2)
    ok = np.arange(n) > max(bos_n + 3, 210)
    if sess:
        ok &= (hr >= 7) & (hr < 20)
    bull = ok & (l - h2 >= gap_min * a) & (c1 > o1) & (body >= k_imp * a) & (c1 > hh)
    bear = ok & (l2 - h >= gap_min * a) & (c1 < o1) & (body >= k_imp * a) & (c1 < ll)
    if bias:
        bull &= c > e; bear &= c < e
    out = []
    for i in np.nonzero(bull)[0]:
        top, bot = l[i], h2[i]
        en = top if entry == "top" else (top + bot) / 2
        sl = (bot - 0.1 * a[i]) if sl_mode == "zone" else (min(l2[i], l[i - 1]) - 0.1 * a[i])
        r = en - sl
        out.append((t[i] + tf_sec, t[i] + tf_sec * (1 + expire), 1, en, sl, en + rr * r, 0))
    for i in np.nonzero(bear)[0]:
        bot, top = h[i], l2[i]
        en = bot if entry == "top" else (top + bot) / 2
        sl = (top + 0.1 * a[i]) if sl_mode == "zone" else (max(h2[i], h[i - 1]) + 0.1 * a[i])
        r = sl - en
        out.append((t[i] + tf_sec, t[i] + tf_sec * (1 + expire), -1, en, sl, en - rr * r, 0))
    return out


def sweep_setups(d, tf_sec, rr=2.0, levels="pd", bias=False, sess=True):
    """Liquidity sweep: candle menembus high/low hari sebelumnya (pd) atau range Asia
    (asia, 00-07 UTC) lalu MENUTUP kembali di dalam -> entry berlawanan di open berikutnya.
    SL di atas/bawah ujung sumbu (wick)."""
    o, h, l, c = (d[k].values for k in ("open", "high", "low", "close"))
    a = atr(h, l, c); e = ema(c, 200)
    t = secs(d.index); hr = d.index.hour.values
    day = d.index.normalize()
    daily = d.groupby(day).agg(H=("high", "max"), L=("low", "min"))
    if levels == "pd":
        prev = daily.shift(1).reindex(day)
        LH, LL = prev.H.values, prev.L.values
    else:
        asia = d[d.index.hour < 7].groupby(d[d.index.hour < 7].index.normalize()).agg(H=("high", "max"), L=("low", "min"))
        cur = asia.reindex(day)
        LH, LL = cur.H.values, cur.L.values
        LH = np.where(hr >= 7, LH, np.nan); LL = np.where(hr >= 7, LL, np.nan)
    ok = ~np.isnan(LH) & (np.arange(len(c)) > 210)
    if sess:
        ok &= (hr >= 7) & (hr < 20)
    sell = ok & (h > LH) & (c < LH) & (c < o)
    buy = ok & (l < LL) & (c > LL) & (c > o)
    if bias:
        sell &= c < e; buy &= c > e
    out = []
    for i in np.nonzero(buy)[0]:
        sl = l[i] - 0.1 * a[i]
        out.append((t[i] + tf_sec, t[i] + tf_sec, 1, np.nan, sl, rr, 1))
    for i in np.nonzero(sell)[0]:
        sl = h[i] + 0.1 * a[i]
        out.append((t[i] + tf_sec, t[i] + tf_sec, -1, np.nan, sl, rr, 1))
    return out


# ------------------------------------------------------------------ eksekusi menit
@njit(cache=True)
def execute(S_act, S_exp, S_side, S_en, S_sl, S_tp, S_mkt, m_t, m_o, m_h, m_l, m_c, sp):
    res = np.empty(len(S_act)); nr = 0
    n = len(m_t); busy = 0; p = 0
    for s in range(len(S_act)):
        if S_act[s] < busy:
            continue
        while p < n and m_t[p] < S_act[s]:
            p += 1
        if p >= n:
            break
        side = S_side[s]; slp = S_sl[s]
        q = p; filled = False; L = 0.0
        if S_mkt[s] == 1:
            # market di open menit pertama candle berikutnya
            L = m_o[q] + sp / 2 if side > 0 else m_o[q] - sp / 2
            filled = True
            risk = (L - slp) * side
            if risk <= sp:
                continue
            tp = L + side * S_tp[s] * risk   # S_tp = rr untuk market
        else:
            L = S_en[s]; tp = S_tp[s]
            while q < n and m_t[q] < S_exp[s]:
                if side > 0:
                    if m_l[q] + sp / 2 <= L:
                        filled = True; break
                    if m_h[q] - sp / 2 >= tp:   # TP tercapai sebelum terisi -> batal
                        break
                else:
                    if m_h[q] - sp / 2 >= L:
                        filled = True; break
                    if m_l[q] + sp / 2 <= tp:
                        break
                q += 1
            if not filled:
                continue
            risk = (L - slp) * side
            if risk <= sp:
                continue
        k = q; out = 0.0; done = False
        while k < n:
            if side > 0:
                lo = m_l[k] - sp / 2; hi = m_h[k] - sp / 2
                if lo <= slp:
                    out = slp; done = True; break
                if (k > q or S_mkt[s] == 1) and hi >= tp:
                    out = tp; done = True; break
            else:
                lo = m_l[k] + sp / 2; hi = m_h[k] + sp / 2
                if hi >= slp:
                    out = slp; done = True; break
                if (k > q or S_mkt[s] == 1) and lo <= tp:
                    out = tp; done = True; break
            k += 1
        if not done:
            out = m_c[n - 1]; k = n - 1
        res[nr] = (out - L) * side / risk; nr += 1
        busy = m_t[k] + 60
    return res[:nr]


def run_setups(setups, m1, sp):
    if not setups:
        return np.array([])
    s = sorted(setups, key=lambda x: x[0])
    A = np.array(s, dtype=np.float64)
    return execute(A[:, 0].astype(np.int64), A[:, 1].astype(np.int64), A[:, 2].astype(np.int64),
                   A[:, 3], A[:, 4], A[:, 5], A[:, 6].astype(np.int64),
                   secs(m1.index), m1.open.values, m1.high.values, m1.low.values, m1.close.values, sp)


if __name__ == "__main__":
    raw = {(sy, y): load_year(sy, y) for sy in SYMS for y in (2018, 2019)}
    rows = []
    for tf in ("5min", "15min", "60min"):
        tfs = int(pd.Timedelta(tf).total_seconds())
        D = {k: resample(v, tf) for k, v in raw.items()}
        for k_imp, entry, sl_mode, bias, rr in itertools.product((0.8, 1.5), ("top", "mid"), ("zone", "candle1"), (True, False), (1.5, 2.0, 3.0)):
            for (sy, y), d in D.items():
                r = run_setups(fvg_setups(d, tfs, k_imp=k_imp, entry=entry, sl_mode=sl_mode, bias=bias, rr=rr), raw[(sy, y)], SPREAD[sy])
                rows.append(dict(strat="fvg", tf=tf, cfg=f"k{k_imp} {entry} {sl_mode} b{int(bias)} rr{rr}", sym=sy, year=y, **stats(r)))
        for lv, bias, rr in itertools.product(("pd", "asia"), (True, False), (1.5, 2.0, 3.0)):
            for (sy, y), d in D.items():
                r = run_setups(sweep_setups(d, tfs, rr=rr, levels=lv, bias=bias), raw[(sy, y)], SPREAD[sy])
                rows.append(dict(strat="sweep", tf=tf, cfg=f"{lv} b{int(bias)} rr{rr}", sym=sy, year=y, **stats(r)))
        print("done", tf, flush=True)
    df = pd.DataFrame(rows); df.to_csv("smc.csv", index=False)
    g = df.groupby(["strat", "tf", "cfg", "year"]).agg(exp=("exp", "mean"), wr=("wr", "mean"), n=("n", "sum"), pos=("exp", lambda x: int((x > 0).sum()))).reset_index()
    p = g.pivot_table(index=["strat", "tf", "cfg"], columns="year", values=["exp", "wr", "n", "pos"])
    p.columns = [f"{a}{b}" for a, b in p.columns]
    p["worst"] = p[["exp2018", "exp2019"]].min(axis=1)
    pd.set_option("display.width", 250)
    print(p.sort_values("worst", ascending=False).head(25).round(3).to_string())
    print()
    print(p.groupby(level=[0, 1]).apply(lambda x: pd.Series({"cfgs": len(x), "positif_2th": round((x.worst > 0).mean(), 2), "median_worst": round(x.worst.median(), 3)})).to_string())
