import json, sys, collections, subprocess, os
J = os.environ['JAVA_HOME'] + '/bin/jfr'
def frame(f):
    m = f['method']; return m['type']['name'].replace('/', '.') + '.' + m['name']
for path in sys.argv[1:]:
    out = subprocess.run([J, 'print', '--json', '--stack-depth', '128', '--events', 'jdk.ExecutionSample', path], capture_output=True, text=True).stdout
    leaf = collections.Counter(); caller = collections.Counter(); thread = collections.Counter(); kafkaish = 0; total = 0
    http_caller = collections.Counter()
    for e in json.loads(out)['recording']['events']:
        frames = [frame(x) for x in ((e['values'].get('stackTrace') or {}).get('frames') or [])]
        total += 1
        text = ' '.join(frames)
        if 'org.apache.kafka.clients.producer' in text:
            kafkaish += 1
            leaf[frames[0]] += 1
            wow = next((f for f in frames if f.startswith('me.ahoo.wow')), 'none')
            caller[wow] += 1
            t = (e['values'].get('sampledThread') or {}).get('javaName') or '?'
            thread[t.rstrip('0123456789-')] += 1
        elif any(m in text for m in ('reactor.netty', 'io.netty', 'org.springframework.web')):
            wow = next((f for f in frames if f.startswith('me.ahoo.wow')), 'none')
            http_caller[wow] += 1
    print('##', path, 'samples', total, 'kafka', kafkaish)
    print(' leaf frames:'); [print('   ', n, f) for f, n in leaf.most_common(8)]
    print(' first Wow frame:'); [print('   ', n, f) for f, n in caller.most_common(6)]
    print(' threads:'); [print('   ', n, f) for f, n in thread.most_common(5)]
    print(' http, first Wow frame:'); [print('   ', n, f) for f, n in http_caller.most_common(6)]
