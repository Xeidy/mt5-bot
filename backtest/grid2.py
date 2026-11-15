import itertools, numpy as np, pandas as pd
from bt import resample, simulate, stats, atr, SPREAD
import strats as S
from final import load_year, SYMS
raw={(s,y):load_year(s,y) for s in SYMS for y in (2018,2019)}
rows=[]
def add(name,tf,params,fn,mg):
    for (s,y),df in raw.items():
        d=resample(df,tf); a=atr(d.high.values,d.low.values,d.close.values)
        r=simulate(d,fn(d),SPREAD[s],a=a,**mg)
        rows.append(dict(name=name,tf=tf,params=params,sym=s,year=y,**stats(r)))
for tf in ("60min","240min","1D"):
    sess_opts=(True,False) if tf=="60min" else (False,)
    for n,sl,tr,sess in itertools.product((20,55,100),(1.5,2.0,3.0),(2.0,3.0,5.0),sess_opts):
        add("donchian",tf,f"n{n} sl{sl} tr{tr} s{int(sess)}",lambda d,n=n,sl=sl,sess=sess:S.donchian(d,n=n,sl_atr=sl,sess=sess),dict(trail_atr=tr))
    for rr,sl,adx_min in itertools.product((1.5,2.0,3.0),(1.5,2.0),(15,20,25)):
        add("pullback",tf,f"rr{rr} sl{sl} adx{adx_min}",lambda d,rr=rr,sl=sl,am=adx_min:S.trend_pullback(d,sl_atr=sl,rr=rr,adx_min=am,sess=(tf=="60min")),{})
    for am,sl in itertools.product((15,20,25),(1.0,1.5,2.0)):
        add("bbrev",tf,f"adx{am} sl{sl}",lambda d,am=am,sl=sl:S.bb_revert(d,adx_max=am,sl_atr=sl,sess=(tf=="60min")),{})
df=pd.DataFrame(rows); df.to_csv("grid2.csv",index=False)
g=df.groupby(["name","tf","params","year"]).agg(exp=("exp","mean"),pos=("exp",lambda x:int((x>0).sum())),n=("n","sum")).reset_index()
p=g.pivot_table(index=["name","tf","params"],columns="year",values=["exp","pos","n"])
p.columns=[f"{a}{b}" for a,b in p.columns]
p["worst"]=p[["exp2018","exp2019"]].min(axis=1)
p=p.sort_values("worst",ascending=False)
pd.set_option("display.width",220)
print(p.head(25).round(3).to_string())
print()
print(p.groupby(level=[0,1]).apply(lambda x: pd.Series({"cfgs":len(x),"both_pos":(x.worst>0).mean().round(2),"med_worst":x.worst.median().round(3)})).to_string())
