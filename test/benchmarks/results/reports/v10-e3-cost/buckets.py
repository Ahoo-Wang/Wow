# Roadmap §6 item 16: an E3 command's node CPU and allocation by component, against E1's, from the node's flight
# recordings: each sample's stack goes to the first bucket one of its frames names, and a bucket's share times the
# run's cost per operation (summary.md) is its cost per operation.
import json, sys, collections, subprocess, os, re
J = os.environ['JAVA_HOME'] + '/bin/jfr'
B = [
    ('kafka producer', 'org.apache.kafka.clients.producer'),
    ('kafka consumer', 'org.apache.kafka.clients.consumer'),
    ('wait signals', 'me.ahoo.wow.runtime.wait'),
    ('saga handling', 'me.ahoo.wow.runtime.binding', 'ProcessorBinding', 'SagaContext', 'TransferSaga'),
    ('saga commands', 'me.ahoo.wow.runtime.client', 'me.ahoo.wow.runtime.call', 'CommandCalls'),
    ('snapshot store', '.snapshot.'),
    ('subscriptions/sinks', 'me.ahoo.wow.runtime.subscription', 'me.ahoo.wow.runtime.sink', 'me.ahoo.wow.runtime.source'),
    ('log tail', 'Tail'),
    ('pg driver', 'org.postgresql', 'com.zaxxer'),
    ('mongo driver', 'com.mongodb', 'org.bson'),
    ('log append', 'EventLog.append', 'PartitionAppendQueue'),
    ('state load', 'StateViews', 'readSnapshotTail', 'EventCodec.decode'),
    ('kernel', 'me.ahoo.wow.kernel'),
    ('json', 'tools.jackson'),
    ('http/webflux/netty', 'org.springframework.web', 'reactor.netty', 'io.netty', 'me.ahoo.wow.http'),
]
NAMES = [b[0] for b in B] + ['other', 'no stack']

def frame(f):
    m = f['method']
    return m['type']['name'].replace('/', '.') + '.' + m['name']

def buckets(path, event):
    out = subprocess.run([J, 'print', '--json', '--stack-depth', '96', '--events', event, path],
                         capture_output=True, text=True).stdout
    counts = collections.Counter(); total = 0
    for e in json.loads(out)['recording']['events']:
        frames = [frame(x) for x in ((e['values'].get('stackTrace') or {}).get('frames') or [])]
        weight = 1 if event == 'jdk.ExecutionSample' else e['values'].get('weight', 1)
        total += weight
        text = ' '.join(frames)
        name = next((b[0] for b in B if any(m in text for m in b[1:])), 'other' if frames else 'no stack')
        counts[name] += weight
    return counts, total

def costs(summary):
    text = open(summary).read()
    row = re.search(r'\| node-1 \| ([0-9.]+) \|', text)
    ok = int(re.search(r'\| [0-9.]+/s \| (\d+) \|', text).group(1))
    seconds = float(re.search(r'([0-9]+) s measured', text).group(1))
    cores = float(row.group(1))
    return cores * seconds * 1000 / ok

for phase_dir in sys.argv[1:]:
    jfrs = [os.path.join(phase_dir, f) for f in os.listdir(phase_dir) if f.endswith('.jfr')]
    if not jfrs:
        print('no recording in', phase_dir); continue
    cpu, cpu_total = buckets(jfrs[0], 'jdk.ExecutionSample')
    alloc, alloc_total = buckets(jfrs[0], 'jdk.ObjectAllocationSample')
    per_op = costs(os.path.join(phase_dir, 'summary.md'))
    print(f'## {phase_dir}  node CPU {per_op:.3f} ms/op, {cpu_total} CPU samples')
    for k in NAMES:
        share = cpu[k] / cpu_total if cpu_total else 0
        print(f'  {k:22s} cpu {100 * share:5.1f}%  {per_op * share:6.3f} ms/op   alloc {100 * alloc[k] / alloc_total if alloc_total else 0:5.1f}%')
