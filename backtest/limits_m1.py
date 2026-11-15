"""Pending order (limit/stop OCO) dengan level dari candle TF, tapi eksekusi
disimulasikan di data 1 menit -> urutan sentuh harga lebih akurat."""
import itertools, sys
import numpy as np, pandas as pd
from numba import njit
from bt import resample, atr, stats, SPREAD
from final import load_year, SYMS


@njit(cache=True)
def run(tf_start, tf_close, tf_atr, tf_hour, m_t, m_h, m_l, m_c, tf_sec, sp,
        d, t, s, is_limit, expire, sess):
    res = np.empty(len(tf_close)); nres = 0
    n_tf = len(tf_close); n_m = len(m_t)
    busy_until = 0  # waktu (detik) sampai posisi selesai
    p = 0
    for i in range(20, n_tf - 1):
        start = tf_start[i] + tf_sec          # order aktif mulai candle berikutnya
        if start < busy_until:
            continue
        if sess and not (7 <= tf_hour[i] < 20):
            continue
        A = tf_atr[i]; c = tf_close[i]
        if is_limit:
            bl = c - d * A; sl_ = c + d * A
        else:
            bl = c + d * A; sl_ = c - d * A
        end = start + expire * tf_sec
        while p < n_m and m_t[p] < start:
            p += 1
        q = p; side = 0; L = 0.0
        while q < n_m and m_t[q] < end:
            alo = m_l[q] + sp / 2; ahi = m_h[q] + sp / 2
            blo = m_l[q] - sp / 2; bhi = m_h[q] - sp / 2
            if is_limit:
                bf = alo <= bl; sf = bhi >= sl_
            else:
                bf = ahi >= bl; sf = blo <= sl_
            if bf and sf:
                side = 2; break
            if bf:
                side = 1; L = bl; break
            if sf:
                side = -1; L = sl_; break
            q += 1
        if side == 0:
            continue
        if side == 2:
            res[nres] = -1.0; nres += 1; busy_until = m_t[q] + 60; continue
        R = s * A
        tp = L + side * t * A; slp = L - side * s * A
        # menit saat terisi: cek SL saja (konservatif), lalu menit berikutnya normal
        k = q; out = 0.0; done = False
        while k < n_m:
            if side > 0:
                lo = m_l[k] - sp / 2; hi = m_h[k] - sp / 2
                if lo <= slp:
                    out = slp; done = True; break
                if k > q and hi >= tp:
                    out = tp; done = True; break
            else:
                lo = m_l[k] + sp / 2; hi = m_h[k] + sp / 2
                if hi >= slp:
                    out = slp; done = True; break
                if k > q and lo <= tp:
                    out = tp; done = True; break
            k += 1
        if not done:
            out = m_c[n_m - 1]; k = n_m - 1
        res[nres] = (out - L) * side / R; nres += 1
        busy_until = m_t[k] + 60
    return res[:nres]


def prep(m1, tf):
    d = resample(m1, tf)
    a = atr(d.high.values, d.low.values, d.close.values)
    ts = ((d.index - pd.Timestamp(0)) // pd.Timedelta("1s")).values.astype(np.int64)
    mt = ((m1.index - pd.Timestamp(0)) // pd.Timedelta("1s")).values.astype(np.int64)
    return (ts, d.close.values, a, d.index.hour.values.astype(np.int64),
            mt, m1.high.values, m1.low.values, m1.close.values,
            int(pd.Timedelta(tf).total_seconds()))


if __name__ == "__main__":
    tfs = sys.argv[1].split(",") if len(sys.argv) > 1 else ["60min", "15min"]
    raw = {(sy, y): load_year(sy, y) for sy in SYMS for y in (2018, 2019)}
    rows = []
    for tf in tfs:
        P = {k: prep(v, tf) for k, v in raw.items()}
        for kind in ("limit", "stop"):
            for d, t, s in itertools.product((0.3, 0.6, 1.0), (0.3, 0.5, 1.0), (0.3, 0.5, 1.0)):
                for (sy, y), args in P.items():
                    r = run(*args, SPREAD[sy], d, t, s, kind == "limit", 1, True)
                    rows.append(dict(tf=tf, kind=kind, d=d, t=t, s=s, sym=sy, year=y, **stats(r)))
        print("done", tf, flush=True)
    df = pd.DataFrame(rows); df.to_csv("limits_m1.csv", index=False)
    g = df.groupby(["tf", "kind", "d", "t", "s", "year"]).agg(
        exp=("exp", "mean"), wr=("wr", "mean"), n=("n", "sum")).reset_index()
    p = g.pivot_table(index=["tf", "kind", "d", "t", "s"], columns="year", values=["exp", "wr", "n"])
    p.columns = [f"{a}{b}" for a, b in p.columns]
    p["worst"] = p[["exp2018", "exp2019"]].min(axis=1)
    pd.set_option("display.width", 220)
    print(p.sort_values("worst", ascending=False).head(15).round(3).to_string())
    print()
    print(p.groupby(level=[0, 1]).apply(lambda x: pd.Series({
        "cfgs": len(x), "positif_2th": round((x.worst > 0).mean(), 2),
        "median_exp": round(x[["exp2018", "exp2019"]].mean(axis=1).median(), 3),
        "median_wr": round(x[["wr2018", "wr2019"]].mean(axis=1).median(), 1)})).to_string())
