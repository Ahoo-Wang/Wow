import json,sys,glob,os
O=sys.argv[1]
def row(d):
  r=json.load(open(d+'/result.json'))
  res=r['results'];R=r['resources'];sec=R['seconds'];ops=res['ok']
  n=R['processes']['node-1']
  out=dict(name=os.path.basename(d),rate=r['load']['rate'],thr=res['throughput'],p50=res['latencyMs']['p50'],p99=res['latencyMs']['p99'],
    cpu=n['cpuSeconds']*1000/ops,alloc=n['allocatedBytes']/ops/1024,lg=R['loadGenerator']['cpuSeconds']*1000/ops)
  for c,v in R['containers'].items():
    out[c.split('-')[-1]]=v['cpuPercentAverage']/100*sec*1000/ops
  st=R.get('stores',{})
  if 'postgres' in st:
    b,a=st['postgres']['before'],st['postgres']['after']
    for k in ['xact_commit','tup_inserted','tup_updated','tup_fetched','wal_records','wal_bytes','wal_sync']:
      out[k]=(a[k]-b[k])/ops
  for k,v in st.items():
    if k!='postgres': out['store_'+k]=json.dumps(v)[:0]
  return out
rows=[row(d) for d in sorted(glob.glob(O+'/s[01]-*')) if os.path.exists(d+'/result.json')]
keys=[]
for r in rows:
  for k in r:
    if k not in keys and not k.startswith('store_'): keys.append(k)
print('\t'.join(keys))
for r in rows: print('\t'.join((f"{r[k]:.3g}" if isinstance(r.get(k),float) else str(r.get(k,''))) for k in keys))
