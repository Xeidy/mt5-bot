import numpy as np, pandas as pd
from bt import resample, simulate, stats, atr, SPREAD
import strats as S
from final import load_year, SYMS
for y in (2018,2019):
    tot=[]; 
    for s in SYMS:
        d=resample(load_year(s,y),"60min"); a=atr(d.high.values,d.low.values,d.close.values)
        r=simulate(d,S.donchian(d,n=55,sl_atr=3.0,sess=True),SPREAD[s],a=a,trail_atr=5.0)
        tot.append(r)
    R=np.concatenate(tot); st=stats(R)
    print(y, st, "rata2 menang %.2fR, rata2 kalah %.2fR, terbesar %.1fR"%(R[R>0].mean(),R[R<0].mean(),R.max()))
