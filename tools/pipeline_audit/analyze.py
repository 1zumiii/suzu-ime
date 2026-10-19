# 汇总 probe 的输出：有无 AI 的首选准确率，以及变差的题里 AI 首选的特征。用法：python3 -I analyze.py <probe 输出.tsv>
import json,re,collections,sys
py=json.load(open(__import__('os').environ.get('PINYIN_JSON','pinyin.json'),encoding='utf-8'))
voc=set(l.rstrip('\n') for l in open(__import__('os').environ.get('SUZU_VOCAB','suzu.vocab.txt'),encoding='utf-8'))
def han(t): return any('一'<=c<='鿿' or '㐀'<=c<='䶿' or '\U00020000'<=c<='\U0003134f' for c in t)
def aligned(text,code):
    # 词典读音（任一读法，逐字多音取单字读音）能否按“每音节取前缀”对齐输入
    rs=[]
    for ch in text:
        r=py.get(ch)
        if not r: return None
        rs.append(sorted(set(x.replace("'",'') for x in r)))
    def m(i,pos):
        if i==len(rs): return pos==len(code)
        for sy in rs[i]:
            for l in range(1,len(sy)+1):
                if code[pos:pos+l]==sy[:l] and m(i+1,pos+l): return True
        return False
    return m(0,0)
rows=collections.defaultdict(dict)
for ln in open(__import__('sys').argv[1],encoding='utf-8'):
    f=ln.rstrip('\n').split('\t')
    if len(f)<6: continue
    i,dom,ans,code,mode=f[:5]
    cands=[x.split('|')[0] for x in f[5:]]
    rows[i]['dom']=dom;rows[i]['ans']=ans;rows[i]['code']=code
    rows[i][mode]=cands
n=0;cN=cA=0;help_=harm=0
cat=collections.Counter();catn=collections.Counter()
harm_ex=collections.defaultdict(list)
byd=collections.defaultdict(lambda:[0,0,0])
for i,r in rows.items():
    if 'N' not in r or 'A' not in r or not r['N'] or not r['A']: continue
    n+=1; a=r['ans']
    okN=r['N'][0]==a; okA=r['A'][0]==a
    cN+=okN;cA+=okA
    d=byd[r['dom']];d[0]+=1;d[1]+=okN;d[2]+=okA
    if okA and not okN: help_+=1
    if okN and not okA:
        harm+=1
        t=r['A'][0]; code=r['code']
        al=aligned(t,code)
        feats=[]
        feats.append('单字' if len(t)==1 else ('二字' if len(t)==2 else '三字以上'))
        if not all(c in voc for c in t): feats.append('含词表外字')
        if not han(t): feats.append('非汉字')
        if al is False: feats.append('读音对不齐(模糊音)')
        for f_ in feats: cat[f_]+=1
        harm_ex[feats[0]].append((r['ans'],t,r['N'][:3],r['A'][:3],code))
print(f"有效 {n} 题: 无AI 首选 {cN/n*100:.2f}%  有AI {cA/n*100:.2f}%  变好 {help_} 变差 {harm}")
for k,v in byd.items(): print(f"  {k}: n={v[0]} 无AI {v[1]/v[0]*100:.1f}% 有AI {v[2]/v[0]*100:.1f}%")
print("变差的题里，AI 首选的特征：",dict(cat))
for k,v in harm_ex.items():
    print(k,len(v)); 
    for e in v[:6]: print('   ',e)
