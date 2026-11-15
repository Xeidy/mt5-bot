import numpy as np, pandas as pd
from bt import D, resample, simulate, stats, atr, SPREAD
import strats as S
SYMS=["EURUSD","GBPUSD","AUDUSD","USDCAD","EURJPY","XAUUSD"]
def load_year(sym,y):
    s=sym[:3]+"_"+sym[3:]
    df=pd.concat([pd.read_csv(D/f"currencies/oanda/{s}/{y}/oanda-{s}-{y}-{m}.csv",parse_dates=["time"]) for m in range(1,13)])
    return df.sort_values("time").drop_duplicates("time").set_index("time")[["open","high","low","close"]].astype(float)

def sim_times(d,sig,sp,a,**kw):
    # simulate tapi juga simpan waktu entry
    out=[]
    for s in sig:
        r=simulate(d,[s],sp,a=a,**kw)
        if len(r): out.append((d.index[s[0]],r[0]))
    # buang yang tumpang tindih: pakai simulate sekali untuk urutan valid
    return out

CFG=dict(n=55,sl_atr=2.0,sess=True); TR=3.0
for y in (2018,2019):
    allr=[]; rows=[]
    for sym in SYMS:
        d=resample(load_year(sym,y),"60min"); a=atr(d.high.values,d.low.values,d.close.values)
        sig=S.donchian(d,**CFG)
        r=simulate(d,sig,SPREAD[sym],a=a,trail_atr=TR)
        st=stats(r); rows.append(dict(sym=sym,**st))
        # waktu entry untuk kurva gabungan (urutan sinyal yang benar-benar diambil)
        taken=[]; busy=-1
        o=d.open.values
        idx=0
        for s_ in sig:
            pass
        allr.append(r)
    t=pd.DataFrame(rows); print(f"=== {y} ==="); print(t.to_string(index=False))
    R=np.concatenate(allr); print("TOTAL", stats(R))
