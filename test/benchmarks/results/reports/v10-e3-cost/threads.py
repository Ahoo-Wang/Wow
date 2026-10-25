# Cores each kind of thread used over a run (jdk.ThreadCPULoad, a fraction of the machine per sample), and per op.
import json, sys, collections, subprocess, os, re
J = os.environ['JAVA_HOME'] + '/bin/jfr'; cores = int(sys.argv[1])
for d in sys.argv[2:]:
    f = os.path.join(d, 'node-1.jfr')
    text = open(os.path.join(d, 'summary.md')).read()
    rate = float(re.search(r'\| ([0-9.]+)/s \|', text).group(1))
    ev = json.loads(subprocess.run([J, 'print', '--json', '--events', 'jdk.ThreadCPULoad', f], capture_output=True, text=True).stdout)['recording']['events']
    per = collections.defaultdict(list)
    for e in ev:
        v = e['values']; t = v.get('eventThread') or {}
        n = re.sub(r'\d+', 'N', t.get('javaName') or t.get('osName') or '?')
        per[(n, t.get('javaThreadId') or t.get('osThreadId'))].append(v['user'] + v['system'])
    grp = collections.Counter()
    for (n, _), l in per.items(): grp[n] += sum(l) / len(l) * cores
    tot = sum(grp.values())
    print(f'## {d.split("/")[-2]}/{d.split("/")[-1]} at {rate:.0f}/s: {tot:.2f} cores, {tot * 1000 / rate:.3f} ms/op')
    for k, v in grp.most_common(9): print(f'  {v:5.2f} cores {v * 1000 / rate:6.3f} ms/op {100 * v / tot:5.1f}% {k}')
