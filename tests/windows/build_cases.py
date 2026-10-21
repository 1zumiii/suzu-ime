# Builds the held-out parity cases like the Mac check (same rule: candidates are rime-ice homophones of the same length, top 4 competitors by weight)
# usage: CN_DICTS=<cn_dicts 目录> python build_cases.py <tests dir with holdout/ and domain/> <output dir>
import re,collections,json,sys,os
R=sys.argv[1]; OUT=sys.argv[2]
D=os.environ.get('CN_DICTS','cn_dicts').rstrip('/\\')+'/'   # 词库目录：词库包安装后的 cn_dicts（含 8105、base 等）
chars={}; index=collections.defaultdict(dict)
def rows(name):
    body=False
    for ln in open(D+name+'.dict.yaml',encoding='utf-8'):
        if ln.startswith('...'): body=True; continue
        if not body or not ln.strip() or ln.startswith('#'): continue
        yield ln.rstrip('\n').split('\t')
for f in rows('8105'): chars.setdefault(f[0],f[1])
for name in ('base','ext','others'):
    for f in rows(name):
        if len(f)<2: continue
        w,py=f[0],f[1]
        wt=int(f[2]) if len(f)>2 and f[2].isdigit() else 100
        if len(w)==len(py.split()): index[py][w]=max(wt,index[py].get(w,0))
for f in rows('tencent'):
    w=f[0]
    if all(c in chars for c in w):
        py=' '.join(chars[c] for c in w)
        index[py].setdefault(w,int(f[1]) if len(f)>1 and f[1].isdigit() else 100)
cases=[];meta=[]
for dom in ('chat','tech','anime'):
    for src in ('holdout','domain'):
        for ln in open(f'{R}/{src}/{dom}.txt',encoding='utf-8'):
            ln=ln.strip()
            if not ln or ln.startswith('#'): continue
            for m in re.finditer(r'【([^】]+)】',ln):
                w=m.group(1)
                ctx=ln[:m.start()].replace('【','').replace('】','')
                if len(ctx)<2 or len(w)<2: continue
                if not all(c in chars for c in w): continue
                py=' '.join(chars[c] for c in w)
                d=index.get(py,{})
                comp=sorted(((x,wt) for x,wt in d.items() if x!=w and len(x)==len(w)),key=lambda t:-t[1])[:4]
                if not comp: continue
                meta.append(dict(dom=dom,src=src,ans=w))
                cases.append((ctx,[w]+[x for x,_ in comp]))
with open(OUT+'/cases.tsv','w',encoding='utf-8') as f:
    for i,(ctx,c) in enumerate(cases): f.write(f"{i}\t0\t{ctx}\t{'|'.join(c)}\n")
json.dump(meta,open(OUT+'/meta.json','w'),ensure_ascii=False)
print(len(cases),collections.Counter((m['dom'],m['src']) for m in meta))
