import json,sys,collections,subprocess,os,re
J=os.environ['JAVA_HOME']+'/bin/jfr'
for f in sys.argv[1:]:
  out=subprocess.run([J,'print','--json','--events','jdk.ThreadCPULoad',f],capture_output=True,text=True).stdout
  ev=json.loads(out)['recording']['events']
  per=collections.defaultdict(list)
  for e in ev:
    v=e['values']; t=v.get('eventThread') or {}
    n=re.sub(r'\d+','N',t.get('javaName') or t.get('osName') or '?')
    per[(n,t.get('javaThreadId') or t.get('osThreadId'))].append(v['user']+v['system'])
  # per-thread average load in cores-of-machine fraction -> JFR reports fraction of total machine; multiply by cores
  grp=collections.Counter()
  for (n,_),l in per.items(): grp[n]+=sum(l)/len(l)
  tot=sum(grp.values())
  print('##',f.split('/')[-2],'sum of thread loads x10 cores = %.2f cores'%(tot*10))
  for k,v in grp.most_common(10): print(f"  {v*10:5.2f} cores {100*v/tot:5.1f}% {k}")
