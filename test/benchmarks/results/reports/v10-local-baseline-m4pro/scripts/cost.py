import json, sys, glob, os

O = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..")

def row(d):
    r = json.load(open(d + '/result.json'))
    res = r['results']
    R = r['resources']
    sec = R['seconds']
    ops = res['ok']
    n = R['processes']['node-1']
    failed = res.get('failed', 0)
    failed_cnt = failed if isinstance(failed, int) else sum(failed.values())
    out = dict(
        name=os.path.basename(d),
        rate=r['load']['rate'],
        thr=res['throughput'],
        ok=ops,
        failed=failed_cnt,
        p50=res['latencyMs']['p50'],
        p99=res['latencyMs']['p99'],
        max=res['latencyMs']['max'],
        cpu=n['cpuSeconds'] * 1000 / ops if ops else 0,
        alloc=n['allocatedBytes'] / ops / 1024 if ops else 0,
        gc_count=n.get('gcCountDelta', 0),
        gc_sec=n.get('gcSecondsDelta', 0),
        lg=R['loadGenerator']['cpuSeconds'] * 1000 / ops if ops else 0
    )
    for c, v in R.get('containers', {}).items():
        out[c.split('-')[-1]] = v['cpuPercentAverage'] / 100 * sec * 1000 / ops if ops else 0
    st = R.get('stores', {})
    if 'postgres' in st:
        b, a = st['postgres']['before'], st['postgres']['after']
        for k in ['xact_commit', 'tup_inserted', 'tup_updated', 'tup_fetched', 'wal_records', 'wal_bytes', 'wal_sync']:
            if k in a and k in b and ops:
                out[k] = (a[k] - b[k]) / ops
    if 'mongo' in st:
        b, a = st['mongo']['before'], st['mongo']['after']
        for k in ['insert', 'query', 'update', 'command']:
            if k in a and k in b and ops:
                out['mongo_' + k] = (a[k] - b[k]) / ops
    return out

run_dirs = sorted([os.path.dirname(p) for p in glob.glob(O + '/**/result.json', recursive=True)])
rows = [row(d) for d in run_dirs if os.path.exists(d + '/result.json')]
if rows:
    keys = []
    for r in rows:
        for k in r:
            if k not in keys: keys.append(k)
    print('\t'.join(keys))
    for r in rows:
        print('\t'.join((f"{r[k]:.3g}" if isinstance(r.get(k), float) else str(r.get(k, ''))) for k in keys))
