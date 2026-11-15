import sys
import numpy as np
import pandas as pd
from bt import load, resample, simulate, stats, atr, SPREAD
import strats as S

SYMS = ["EURUSD", "GBPUSD", "AUDUSD", "USDCAD", "EURJPY", "XAUUSD"]
data = {s: load(s) for s in SYMS}


def spread_of(sym, df):
    if sym == "BTCUSD":
        return float(df["close"].median()) * 0.0002   # ~0.02% (BTC CFD retail)
    return SPREAD[sym]


def split(df):
    cut = df.index[0] + (df.index[-1] - df.index[0]) * 2 / 3
    return df[df.index < cut], df[df.index >= cut]


rows = []


def run(name, tf, fn, mgmt=None, layer=False):
    mgmt = mgmt or {}
    for sym in SYMS:
        df = resample(data[sym], tf)
        sp = spread_of(sym, df)
        for part, d in zip(("IS", "OOS"), split(df)):
            if layer:
                r = S.layering_sim(d, sp)
            else:
                a = atr(d.high.values, d.low.values, d.close.values)
                r = simulate(d, fn(d), sp, a=a, **mgmt)
            st = stats(r)
            rows.append(dict(strategy=name, tf=tf, sym=sym, part=part, **st))


if __name__ == "__main__":
  which = sys.argv[1] if len(sys.argv) > 1 else "all"
else:
  which = "none"
if which in ("old", "all"):
    run("old_ema_rsi", "1min", S.old_ema_rsi, dict(be_r=None))
    run("old_scalp", "1min", S.old_scalp)
    run("old_scalp_layer", "1min", None, layer=True)
if which in ("new", "all"):
    for tf in ("5min", "15min", "60min"):
        run("trend_pullback", tf, S.trend_pullback)
        run("trend_pullback_be", tf, S.trend_pullback, dict(be_r=1.0))
        run("bb_revert", tf, S.bb_revert)
        run("donchian_trail", tf, S.donchian, dict(trail_atr=2.5))

if which != "none":
    df = pd.DataFrame(rows)
    df.to_csv(f"/home/claude/bt/results_{which}.csv", index=False)
    pd.set_option("display.width", 200)
    agg = df.groupby(["strategy", "tf", "part"]).agg(
        trades=("n", "sum"), wr=("wr", "mean"), pf=("pf", "median"), exp=("exp", "mean"),
        totalR=("total", "sum"), syms_pos=("exp", lambda x: int((x > 0).sum()))).round(3)
    print(agg.to_string())
