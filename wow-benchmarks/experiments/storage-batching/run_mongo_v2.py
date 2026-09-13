#!/usr/bin/env python3
"""Sender drift repair: new Mongo samples only; old core and Mongo evidence remain frozen."""
import argparse,hashlib,json,pathlib,shutil,subprocess,time
from analyze import read,interval,latency_screen
HERE=pathlib.Path(__file__).resolve().parent

def main():
    p=argparse.ArgumentParser();p.add_argument('--baseline',required=True);p.add_argument('--candidate',required=True);p.add_argument('--output',required=True);p.add_argument('--core-evidence',required=True);a=p.parse_args()
    root=pathlib.Path(a.output);root.mkdir(parents=True,exist_ok=False);classes=root/'classes';classes.mkdir();sources=root/'sources';sources.mkdir()
    jars={'A':str(pathlib.Path(a.baseline).resolve()),'B':str(pathlib.Path(a.candidate).resolve())}
    plan=dict(protocol='mongo-v2-phase-correction',core_evidence=a.core_evidence,calibration=[5000,10000,20000],pairs=3,order=['AB','BA','AB'],fraction=.7,intervals='3 warm + 3 measure x 2s',minimum_gap_fraction=.9,lag_budget_ms=1,calibration_miss_limit=.01,formal_miss_limit=0,error_limit=0,latency_upper=1.10,screen_last_warm_relative_difference=.20,confidence='two-sided 95% df2 paired log ratio',budget_seconds=180,artifacts={k:dict(path=v,sha256=hashlib.sha256(pathlib.Path(v).read_bytes()).hexdigest()) for k,v in jars.items()})
    (root/'plan.json').write_text(json.dumps(plan,indent=2))
    for src in [HERE/'OpenLoop.java',HERE/'analyze.py',HERE/'run_mongo_v2.py',HERE/'BatchLatency.java']:shutil.copy2(src,sources/src.name)
    commands=[];started=time.monotonic()
    def run(cmd,name):
        left=180-(time.monotonic()-started)
        if left<=0:raise RuntimeError('Fixed budget exhausted; do not retry selectively')
        began=time.time()
        with (root/(name+'.log')).open('w') as out:
            try:r=subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT,timeout=min(left,35))
            except subprocess.TimeoutExpired:r=subprocess.CompletedProcess(cmd,124)
        commands.append(dict(name=name,command=cmd,status=r.returncode,started=began,ended=time.time()));(root/'commands.json').write_text(json.dumps(commands,indent=2))
        if r.returncode:raise RuntimeError(name+' failed')
    run(['javac','-proc:none','-cp',jars['B'],'-d',str(classes),str(sources/'BatchLatency.java'),str(sources/'OpenLoop.java')],'compile')
    (root/'harness-hashes.json').write_text(json.dumps({str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*classes.rglob('*'),*sources.glob('*')] if p.is_file()},indent=2))
    def measure(side,name,rate):
        run(['java','-Xms1g','-Xmx1g','-cp',str(classes)+':'+jars[side],'batchharness.OpenLoop',str(rate),'2',str(root/name)],name)
        measured=[read(root/f'{name}-measure-{i}.csv.gz') for i in range(3)];warm=[read(root/f'{name}-warm-{i}.csv.gz') for i in range(3)]
        pooled=read([root/f'{name}-measure-{i}.csv.gz' for i in range(3)]);pooled={k:v for k,v in pooled.items() if '_p9' in k or k in ['planned','sent','success','not_sent','errors']}
        return dict(intervals=measured,warmup=warm,pooled=pooled,screen_pass=latency_screen(warm[-1]['call_p99_ms'],pooled['call_p99_ms']) and all(x['eligible'] for x in measured))
    calibration=[]
    for rate in plan['calibration']:
        result=measure('A',f'calibration-{rate}',rate);calibration.append(dict(rate=rate,eligible=all(x['eligible'] for x in result['intervals']),**result))
    (root/'calibration.json').write_text(json.dumps(calibration,indent=2))
    eligible=[x['rate'] for x in calibration if x['eligible']];chosen=int(max(eligible)*.7) if eligible else None
    (root/'selected-rate.json').write_text(json.dumps(dict(rate=chosen)))
    measurements=[]
    if chosen:
        for pair,order in enumerate(plan['order'],1):
            for side in order:
                measurements.append(dict(pair=pair,side=side,**measure(side,f'latency-p{pair}-{side}',chosen)))
                (root/'latency.json').write_text(json.dumps(measurements,indent=2))
    comparisons=[]
    if chosen:
        for metric in ['scheduled_p95_ms','scheduled_p99_ms','call_p95_ms','call_p99_ms']:
            pairs=[[next(m for m in measurements if m['pair']==pair and m['side']==side)['pooled'][metric] for side in 'AB'] for pair in range(1,4)]
            delivery=all(x['delivery_valid'] for m in measurements for x in m['intervals']);screen=all(m['screen_pass'] for m in measurements)
            comparisons.append(dict(metric=metric,pairs=pairs,delivery_valid=delivery,screen_pass=screen,overall_valid=delivery and screen,**interval(pairs,'latency')))
    (root/'comparisons.json').write_text(json.dumps(comparisons,indent=2));(root/'completed.json').write_text(json.dumps(dict(elapsed=time.monotonic()-started)))
if __name__=='__main__':main()
