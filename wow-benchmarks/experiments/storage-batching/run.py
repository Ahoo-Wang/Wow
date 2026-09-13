#!/usr/bin/env python3
"""Bounded fixed sample experiment. Existing output directories cannot be reused."""
import argparse,hashlib,json,pathlib,subprocess,time,shutil,re,statistics
from analyze import read,interval,latency_screen,warm_values
HERE=pathlib.Path(__file__).resolve().parent
CONFIGS=[('prepared','none',1),('prepared','enabled',1),('prepared','enabled',4),('burst','none',1)]
def main():
    p=argparse.ArgumentParser();p.add_argument('--baseline',required=True);p.add_argument('--candidate',required=True);p.add_argument('--output',required=True);p.add_argument('--run',action='store_true');a=p.parse_args()
    plan=dict(protocol='phase-correction-v2',minimum_gap_fraction=.9,pairs=3,order=['AB','BA','AB'],core=CONFIGS,warmup='3 x 3s',measurement='3 x 3s',calibration=[5000,10000,20000],latency_intervals='3 warm + 3 measure, each 2s',rate_fraction=.7,miss_limit=.01,errors=0,lag_budget_ms=1,throughput_lower=.95,allocation_upper=1.05,latency_upper=1.10,confidence='two-sided 95%, paired log ratios, df=2',budget_seconds=900,stability_screen=dict(core_last_warm_relative_difference=.10,core_measure_cv=.10,mongo_last_warm_call_p99_relative_difference=.20,note='screen only, not proof of long-term steady state'))
    if not a.run:print(json.dumps(plan,indent=2));return
    root=pathlib.Path(a.output);root.mkdir(parents=True,exist_ok=False)
    jars={'A':str(pathlib.Path(a.baseline).resolve()),'B':str(pathlib.Path(a.candidate).resolve())}
    plan['artifacts']={k:dict(path=v,sha256=hashlib.sha256(pathlib.Path(v).read_bytes()).hexdigest()) for k,v in jars.items()}
    (root/'plan.json').write_text(json.dumps(plan,indent=2));classes=root/'classes';classes.mkdir();commands=[];start=time.monotonic()
    def run(command,name,timeout=80):
        if time.monotonic()-start>900:raise RuntimeError('Budget exhausted; report incomplete, never selectively rerun')
        began=time.time()
        with (root/(name+'.log')).open('w') as out:
            try:
                r=subprocess.run(command,stdout=out,stderr=subprocess.STDOUT,timeout=min(timeout,max(.1,900-(time.monotonic()-start))))
            except subprocess.TimeoutExpired:
                r=subprocess.CompletedProcess(command,124)
        commands.append(dict(name=name,command=command,status=r.returncode,started=began,ended=time.time()))
        (root/'commands.json').write_text(json.dumps(commands,indent=2))
        if r.returncode:raise RuntimeError(name+' failed; inspect retained log')
    run(['javac','-cp',jars['B'],'-d',str(classes),str(HERE/'BatchLatency.java'),str(HERE/'CoreBoundary.java'),str(HERE/'OpenLoop.java')],'compile')
    (root/'sources').mkdir()
    for src in [*HERE.glob('*.java'),*HERE.glob('*.py'),HERE/'BatchLatency.java']:shutil.copy2(src,root/'sources'/src.name)
    (root/'harness-hashes.json').write_text(json.dumps({str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*classes.rglob('*'),*(root/'sources').glob('*')] if p.is_file()},indent=2))
    java=lambda side:['java','-Xms1g','-Xmx1g','-cp',str(classes)+':'+jars[side]]
    # Calibration is independent of JMH throughput and always completes the fixed ladder.
    calibration=[]
    for rate in plan['calibration']:
        prefix=f'calibration-{rate}';run(java('A')+['batchharness.OpenLoop',str(rate),'2',str(root/prefix)],prefix)
        values=[read(root/f'{prefix}-measure-{i}.csv.gz') for i in range(3)]
        calibration.append(dict(rate=rate,intervals=values,eligible=all(x['eligible'] for x in values)))
    (root/'calibration.json').write_text(json.dumps(calibration,indent=2))
    eligible=[x['rate'] for x in calibration if x['eligible']]
    chosen=int(max(eligible)*.7) if eligible else None
    (root/'selected-rate.json').write_text(json.dumps(dict(rate=chosen,meaning='70% of observed eligible lower bound; not exact capacity')))
    measurements=[]
    for pair,order in enumerate(plan['order'],1):
        for boundary,metrics,threads in CONFIGS:
            for side in order:
                name=f'core-{boundary}-{metrics}-t{threads}-p{pair}-{side}'
                run(java(side)+['org.openjdk.jmh.Main','batchharness.CoreBoundary.'+boundary,'-p','metrics='+metrics,'-t',str(threads),'-f','1','-wi','3','-w','3s','-i','3','-r','3s','-prof','gc','-rf','json','-rff',str(root/(name+'.json'))],name)
        if chosen:
            for side in order:
                name=f'latency-p{pair}-{side}'
                run(java(side)+['batchharness.OpenLoop',str(chosen),'2',str(root/name)],name)
                measurements.append(dict(pair=pair,side=side,warmup=[read(root/f'{name}-warm-{i}.csv.gz') for i in range(3)],intervals=[read(root/f'{name}-measure-{i}.csv.gz') for i in range(3)],pooled=read([root/f'{name}-measure-{i}.csv.gz' for i in range(3)])))
    for m in measurements:
        warm=m['warmup'][-1]['call_p99_ms'];measured=m['pooled']['call_p99_ms']
        m['screen_pass']=latency_screen(warm,measured) and all(x['eligible'] for x in m['intervals'])
        m['pooled']={k:v for k,v in m['pooled'].items() if '_p9' in k or k in ['planned','sent','success','not_sent','errors']}
    (root/'latency.json').write_text(json.dumps(measurements,indent=2))
    core_screens=[]
    for command in commands:
        name=command['name']
        if not name.startswith('core-'):continue
        entry=json.loads((root/(name+'.json')).read_text())[0]
        values=entry['primaryMetric']['rawData'][0]
        warm=warm_values((root/(name+'.log')).read_text())
        cv=statistics.stdev(values)/statistics.mean(values)
        difference=abs(warm[-1]/statistics.mean(values)-1) if len(warm)==3 else None
        core_screens.append(dict(name=name,warmup=warm,measurement=values,cv=cv,last_warm_relative_difference=difference,screen_pass=difference is not None and difference<=.10 and cv<=.10))
    (root/'core-stability.json').write_text(json.dumps(core_screens,indent=2))
    comparisons=[]
    for boundary,metrics,threads in CONFIGS:
        for key,kind in [('primaryMetric','throughput'),('gc.alloc.rate.norm','allocation')]:
            pairs=[]
            for pair in range(1,4):
                scores=[]
                for side in 'AB':
                    entry=json.loads((root/f'core-{boundary}-{metrics}-t{threads}-p{pair}-{side}.json').read_text())[0]
                    scores.append((entry['primaryMetric'] if key=='primaryMetric' else entry['secondaryMetrics'][key])['score'])
                pairs.append(scores)
            comparisons.append(dict(boundary=boundary,metrics=metrics,threads=threads,metric=kind,pairs=pairs,screen_pass=all(x['screen_pass'] for x in core_screens if x['name'].startswith(f'core-{boundary}-{metrics}-t{threads}-')),overall_valid=all(x['screen_pass'] for x in core_screens if x['name'].startswith(f'core-{boundary}-{metrics}-t{threads}-')),**interval(pairs,kind)))
    if chosen:
        for metric in ['scheduled_p95_ms','scheduled_p99_ms','call_p95_ms','call_p99_ms']:
            pairs=[]
            for pair in range(1,4):
                pairs.append([next(m for m in measurements if m['pair']==pair and m['side']==side)['pooled'][metric] for side in 'AB'])
            comparisons.append(dict(metric=metric,pairs=pairs,delivery_valid=all(x['delivery_valid'] for m in measurements for x in m['intervals']),screen_pass=all(m['screen_pass'] for m in measurements),overall_valid=all(m['screen_pass'] and all(x['delivery_valid'] for x in m['intervals']) for m in measurements),**interval(pairs,'latency')))
    (root/'comparisons.json').write_text(json.dumps(comparisons,indent=2))
    (root/'completed.json').write_text(json.dumps(dict(elapsed=time.monotonic()-start)))
if __name__=='__main__':main()
