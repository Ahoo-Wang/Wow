#!/usr/bin/env python3
"""Fixed three-pair log-ratio t intervals, df=2; descriptive stability, no optional stopping."""
import csv,gzip,json,math,pathlib,statistics,sys,re

def positive(value):
    return value is not None and math.isfinite(value) and value>0

def latency_screen(warm,measured):
    return positive(warm) and positive(measured) and abs(warm/measured-1)<=.20

def interval(pairs,kind):
    if len(pairs)!=3 or not all(positive(a) and positive(b) for a,b in pairs):
        return dict(ratio=None,low=None,high=None,noninferior=False,equivalent=False,improved=False,
                    reason='missing_or_nonpositive_samples')
    x=[math.log(b/a) for a,b in pairs];m=statistics.mean(x);d=4.30265273*statistics.stdev(x)/math.sqrt(3)
    ratio,lo,hi=map(math.exp,(m,m-d,m+d));lower,upper=(.95,1.05) if kind!='latency' else (.9,1.1)
    return dict(ratio=ratio,low=lo,high=hi,noninferior=lo>=lower if kind=='throughput' else hi<=upper,
                equivalent=lo>=lower and hi<=upper,improved=lo>1 if kind=='throughput' else hi<1)

def quantile(x,q):
    return sorted(x)[max(0,math.ceil(len(x)*q)-1)] if x else None

def read(path):
    paths=path if isinstance(path,list) else [path]
    rows=[]
    for path in paths:
        with gzip.open(path,'rt') as f:rows.extend(csv.DictReader(f))
    success=[r for r in rows if r['result']=='success'];sent=[r for r in rows if int(r['submitted_ns'])>0]
    duration=max((int(r['planned_ns']) for r in rows),default=0)+1
    def pending(t):return sum(int(r['submitted_ns'])<=t<int(r['terminal_ns']) for r in sent)
    backlog=[pending(duration*i//10) for i in range(1,10)]
    metrics={}
    for label,start in [('scheduled','planned_ns'),('call','submitted_ns')]:
        values=[(int(r['terminal_ns'])-int(r[start]))/1e6 for r in success]
        for q in [.95,.99]:metrics[f'{label}_p{int(q*100)}_ms']=quantile(values,q)
    lag=[(int(r['submitted_ns'])-int(r['planned_ns']))/1e6 for r in sent]
    missed=sum(r['result']=='not_sent' for r in rows);errors=len(rows)-missed-len(success)
    # At most 1% producer misses, no errors, <= max(8, 1ms arrivals) backlog growth.
    tolerance=max(8,len(rows)/(duration/1e9)*.001)
    healthy=bool(rows) and missed/len(rows)<=.01 and errors==0 and max(backlog[-3:])<=max(backlog[:3])+tolerance
    return dict(planned=len(rows),sent=len(sent),success=len(success),not_sent=missed,errors=errors,
                lag_p99_ms=quantile(lag,.99),backlog=backlog,eligible=healthy,delivery_valid=healthy and missed==0,**metrics)

def warm_values(text):
    blocks=re.findall(r'# Warmup Iteration\s+\d+:(.*?)(?=# Warmup Iteration|\nIteration|\Z)',text,re.S)
    return [float(re.search(r'([0-9.]+) ops/s',block).group(1)) for block in blocks]

def correct_warmup(root):
    screens=json.loads((root/'core-stability.json').read_text())
    for screen in screens:
        warm=warm_values((root/(screen['name']+'.log')).read_text())
        difference=abs(warm[-1]/statistics.mean(screen['measurement'])-1) if len(warm)==3 else None
        screen.update(warmup=warm,last_warm_relative_difference=difference,
                      screen_pass=difference is not None and difference<=.10 and screen['cv']<=.10)
    comparisons=json.loads((root/'comparisons.json').read_text())
    for result in comparisons:
        if 'boundary' not in result:continue
        prefix=f"core-{result['boundary']}-{result['metrics']}-t{result['threads']}-"
        result['screen_pass']=result['overall_valid']=all(x['screen_pass'] for x in screens if x['name'].startswith(prefix))
    (root/'core-stability-corrected.json').write_text(json.dumps(screens,indent=2))
    (root/'comparisons-corrected.json').write_text(json.dumps(comparisons,indent=2))

def self_test():
    assert interval([(1,1)]*3,'throughput')['equivalent']
    assert not interval([(1,.9)]*3,'throughput')['noninferior']
    assert interval([(1,.9)]*3,'latency')['noninferior']
    assert not interval([(1,1.2)]*3,'latency')['noninferior']
    assert quantile([3,1,2],.99)==3
    for pair in [(None,1),(1,None),(0,1),(float('nan'),1)]:
        result=interval([pair]*3,'latency')
        assert result['ratio'] is None and not result['noninferior'] and not result['equivalent'] and not result['improved']
    assert not latency_screen(None,1) and not latency_screen(1,None)
    assert not latency_screen(1,0) and latency_screen(1,1)
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        path=pathlib.Path(directory)/'samples.csv.gz'
        for outcome in ['not_sent','error',None]:
            with gzip.open(path,'wt') as stream:
                stream.write('planned_ns,submitted_ns,terminal_ns,result\n')
                if outcome is not None:
                    stream.write(f"0,{0 if outcome=='not_sent' else 1},2000000,{outcome}\n")
            sample=read(path)
            assert sample['success']==0 and sample['call_p99_ms'] is None
            assert not sample['eligible'] and not sample['delivery_valid']
            assert not latency_screen(sample['call_p99_ms'],sample['call_p99_ms'])

    sample='# Warmup Iteration   1: kotlin-logging: initializing...\n1329421.397 ops/s\n# Warmup Iteration   2: 1430000.100 ops/s\n# Warmup Iteration   3: 1440000.000 ops/s\nIteration   1: 1550000.000 ops/s'
    assert warm_values(sample)==[1329421.397,1430000.1,1440000.0]
    print('statistics and multiline warmup self-check passed')
if __name__=='__main__':
    if sys.argv[1:]==['--self-test']:self_test()
    elif sys.argv[1]=='--correct-warmup':correct_warmup(pathlib.Path(sys.argv[2]))
    else:print(json.dumps(read(pathlib.Path(sys.argv[1])),indent=2))
