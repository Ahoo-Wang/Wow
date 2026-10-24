import json,sys,collections,subprocess,os
J=os.environ['JAVA_HOME']+'/bin/jfr'
B=[('kafka producer','org.apache.kafka.clients.producer'),('kafka consumer','org.apache.kafka.clients.consumer'),
   ('snapshot store','.snapshot.'),('subscriptions/sinks','me.ahoo.wow.runtime.subscription','me.ahoo.wow.runtime.sink'),
   ('log tail','Tail'),('pg driver','org.postgresql'),('mongo driver','com.mongodb','org.bson'),
   ('log append','EventLog.append','PartitionAppendQueue'),('state load','StateViews','readSnapshotTail','EventCodec.decode'),
   ('kernel','me.ahoo.wow.kernel'),('json','tools.jackson'),('http/webflux/netty','org.springframework.web','reactor.netty','io.netty','me.ahoo.wow.http')]
def name(f): m=f['method']; return m['type']['name'].replace('/','.')+'.'+m['name']
def run(f,ev):
  out=subprocess.run([J,'print','--json','--stack-depth','96','--events',ev,f],capture_output=True,text=True).stdout
  c=collections.Counter();t=0
  for e in json.loads(out)['recording']['events']:
    fr=[name(x) for x in ((e['values'].get('stackTrace') or {}).get('frames') or [])]
    w=e['values'].get('weight',1) if ev!='jdk.ExecutionSample' else 1
    t+=w; s=' '.join(fr)
    b=next((b[0] for b in B if any(m in s for m in b[1:])),'other' if fr else 'no stack')
    c[b]+=w
  return c,t
for f in sys.argv[1:]:
  cc,ct=run(f,'jdk.ExecutionSample'); ac,at=run(f,'jdk.ObjectAllocationSample')
  print('##',f.split('/')[-2],'cpu samples',ct)
  for k in [b[0] for b in B]+['other','no stack']:
    print(f"  {k:22s} cpu {100*cc[k]/ct:5.1f}%  alloc {100*ac[k]/at:5.1f}%")
