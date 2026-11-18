#!/usr/bin/env python3
# The recovery report's numbers, from the raw inputs beside this directory; bench-headline.mjs reads summary.json.
#
#   python3 -I scripts/summarize.py <report dir>          (prints the report's tables)
#   python3 -I scripts/summarize.py <report dir> --json   (writes <report dir>/summary.json)
#
# lost-leases/<arm>-<n>: #6379's pair. The node's log (cluster logging at info) gives when each partition's lease was
#   lost (overdue, or not given back) and when it was acquired again, against pause.txt's pause.
# handover/<arm>-<n>: #6407's e3-handover rounds. result.json gives the commands that failed (every one a saga's
#   timed-out wait) and the p99; install.txt the build.
# rehearsal/<run>/<job>: migration-rehearsal.yml's live orders during the V10 host's catch-up on the imported log,
#   with two permits and with no bound, at #6412's head. offline-live.log gives the totals, offline-live.jsonl one line
#   a second (by completion), timing.txt the hosts' TIMING lines, jvm.txt the host's JVM.
# first-start/<run>[-a<attempt>]/<job>: the same step at two permits, by pairs run in one window: #6412 against its
#   parent, then the patch that replaced its free read (#6424) against the same parent.
import datetime
import json
import os
import re
import statistics
import sys


def ts(text):
    """A log line's stamp with its offset (2026-11-17T05:34:40.901-08:00, or ...Z), else taken as UTC."""
    m = re.match(r'\d{4}-\d\d-\d\dT[\d:.]+(Z|[+-]\d\d:\d\d)?', text)
    t = datetime.datetime.fromisoformat(m.group(0).replace('Z', '+00:00'))
    return (t if t.tzinfo else t.replace(tzinfo=datetime.timezone.utc)).timestamp()


def rounds(directory):
    """The round directories, by round, then arm."""
    if not os.path.isdir(directory):
        return []
    return sorted(os.listdir(directory), key=lambda r: (r.rsplit('-', 1)[1], r))


def build(path):
    """install.txt's server build: its commit cut to ten characters, and whether its tree had changes."""
    line = next(l for l in open(path) if l.startswith('server='))
    m = re.search(r'commit=([0-9a-f]+) dirty=(\w+)', line)
    return {'commit': m.group(1)[:10], 'changed': m.group(2) == 'yes'}


def jdk(result):
    m = re.search(r'Temurin-(\d+\.\d+\.\d+\+\d+)', result['meta']['serverJvm'])
    return m.group(1) if m else 'not recorded'


LOST = re.compile(r'(?:has not renewed the lease of|lost the lease of) partition (\d+) ')
BACK = re.compile(r'acquired partition (\d+) ')


def lost_leases(base):
    arms = {}
    for run in rounds(f'{base}/lost-leases'):
        d = f'{base}/lost-leases/{run}'
        arm, n = run.rsplit('-', 1)
        marks = dict(l.split() for l in open(f'{d}/pause.txt'))
        pause = float(marks['pause'])
        lost, back = {}, {}
        for line in open(f'{d}/node-1.log', errors='replace'):
            m = LOST.search(line)
            if m and ts(line) >= pause - 1:
                lost.setdefault(int(m.group(1)), ts(line))
            m = BACK.search(line)
            if m and int(m.group(1)) in lost and int(m.group(1)) not in back:
                back[int(m.group(1))] = ts(line)
        took = sorted(back[p] - lost[p] for p in back)
        result = json.load(open(f'{d}/result.json'))
        arms.setdefault(arm, {**build(f'{d}/install.txt'), 'jdk': jdk(result), 'rounds': []})['rounds'].append({
            'round': int(n),
            'lost': len(lost),
            'back': len(back),
            'lostAfterPauseMs': round((min(lost.values()) - pause) * 1000),
            'firstBackMs': round(took[0] * 1000),
            'lastBackMs': round(took[-1] * 1000) if len(back) == len(lost) else None,
            'ok': result['results']['sent']['ok'],
            'failed': result['results']['sent']['failed'],
        })
    return arms


HANDOVER_ARMS = {
    'main': 'mongo-main', 'fix': 'mongo-fix', 'pgfix': 'postgres-fix',
    'm6412': 'mongo-6412', 'patch': 'mongo-patch', 'pgpatch': 'postgres-patch',
}


def handover(base):
    arms = {}
    for run in rounds(f'{base}/handover'):
        d = f'{base}/handover/{run}'
        arm, n = run.rsplit('-', 1)
        result = json.load(open(f'{d}/result.json'))
        res = result['results']
        arms.setdefault(HANDOVER_ARMS[arm], {
            **build(f'{d}/install.txt'), 'jdk': jdk(result), 'backend': result['meta']['backend'], 'rounds': [],
        })['rounds'].append({
            'round': int(n),
            'sent': res['sent']['ok'] + res['sent']['failed'],
            'failedWaits': res['errors'].get('http-504-wow.wait.timeout', 0),
            'failed': res['sent']['failed'],
            'p99Ms': round(res['latencyMs']['p99'], 1),
            'worstSecondP99Ms': round(res['worstSecondP99Ms'], 1),
        })
    return arms


TIMING = re.compile(r'(\S+)\s+WARN \d+ --- \[([\w-]+)\].*TIMING caughtUp node=\S+ ms=(\d+)')
REHEARSAL_ARMS = {'44137290807': ('bound-2', 'f33284fa69', '0853cbf27b', 2),
                  '44137289607': ('bound-0', 'f33284fa69', '7c0896effd', 0)}


def live(d):
    """One job's live orders: the totals, the worst second's p99 and each service's first catch-up in the window."""
    total = json.loads([l for l in open(f'{d}/offline-live.log') if l.startswith('{')][-1])
    seconds = [json.loads(l) for l in open(f'{d}/offline-live.jsonl')]
    step = next(json.loads(l) for l in open(f'{d}/steps.jsonl') if '"offline-live"' in l)
    start, end = step['startMs'] / 1000, (step['startMs'] + step['millis']) / 1000
    # The first catch-up of each service that ended within the live window: the V10 host's on the imported log.
    caught = {}
    for line in open(f'{d}/timing.txt'):
        m = TIMING.match(line)
        if m and start - 120 <= ts(m.group(1)) <= end and m.group(2) not in caught:
            caught[m.group(2)] = int(m.group(3))
    m = re.search(r'using Java (\S+)', open(f'{d}/jvm.txt').read())
    return {
        'sent': total['commands'],
        'failed': total['failed'],
        'errors': total['errors'],
        'p50Ms': total['p50'],
        'p99Ms': total['p99'],
        'maxMs': total['max'],
        'worstSecondP99Ms': max(s['p99'] for s in seconds),
        'catchUpMs': caught,
        'jdk': m.group(1) if m else 'not recorded',
    }


def rehearsal(base):
    arms = {}
    for run in sorted(os.listdir(f'{base}/rehearsal')) if os.path.isdir(f'{base}/rehearsal') else []:
        arm, fix, commit, permits = REHEARSAL_ARMS[run]
        jobs = {job: live(f'{base}/rehearsal/{run}/{job}') for job in ('runner', 'kind')}
        arms[arm] = {'run': run, 'fix': fix, 'commit': commit, 'maxCatchUps': permits, 'jobs': jobs}
    return arms


# Each first-start directory: its pair, its arm, the code it measured and the commit the run built (that code plus the
# rehearsal's instrumentation), all at two permits.
FIRST_START = {
    '44194947161-a1': ('6412-1', 'parent', '0d2017aa28', '95bd118e96'),
    '44194947617-a1': ('6412-1', '6412', '60c63cdc6f', '17ce2d07ff'),
    '44194947161-a2': ('6412-2', 'parent', '0d2017aa28', '95bd118e96'),
    '44194947617-a2': ('6412-2', '6412', '60c63cdc6f', '17ce2d07ff'),
    '44203434208': ('patch-1', 'parent', '0d2017aa28', '95bd118e96'),
    '44203434241': ('patch-1', 'patch', '0249db185b', '6781a666aa'),
    '44203470768': ('patch-2', 'parent', '0d2017aa28', '95bd118e96'),
    '44203470883': ('patch-2', 'patch', '0249db185b', '6781a666aa'),
}


def first_start(base):
    arms = {}
    for name in sorted(os.listdir(f'{base}/first-start')) if os.path.isdir(f'{base}/first-start') else []:
        pair, arm, code, commit = FIRST_START[name]
        run, _, attempt = name.partition('-a')
        arms[f'{pair}.{arm}'] = {
            'pair': pair, 'arm': arm, 'code': code, 'commit': commit, 'maxCatchUps': 2, 'dir': name,
            'run': run, 'attempt': int(attempt or 1),
            'jobs': {job: live(f'{base}/first-start/{name}/{job}') for job in ('runner', 'kind')},
        }
    return dict(sorted(arms.items()))


def summary(base):
    return {
        'lostLeases': lost_leases(base), 'handover': handover(base), 'rehearsal': rehearsal(base),
        'firstStart': first_start(base),
    }


def seconds(ms):
    return '–' if ms is None else f'{ms / 1000:.1f} s'


def tables(s):
    print('lost leases (all 64 at once): partitions back, and the time from its loss to the last one back')
    for arm, a in s['lostLeases'].items():
        for r in a['rounds']:
            print(f"  {arm:5} {a['commit']} round {r['round']}: lost {r['lost']}, back {r['back']}, first back "
                  f"{seconds(r['firstBackMs'])}, last back {seconds(r['lastBackMs'])}")
    print('hand-over (e3-handover): failed waits and p99 per round')
    for arm, a in s['handover'].items():
        print(f"  {arm:12} {a['commit']}: failed waits {[r['failedWaits'] for r in a['rounds']]}, "
              f"p99 {[r['p99Ms'] for r in a['rounds']]} ms of {[r['sent'] for r in a['rounds']]} sent")
    print('rehearsal (live orders during the catch-up)')
    for arm, a in s['rehearsal'].items():
        for job, j in a['jobs'].items():
            print(f"  {arm} {job:6} run {a['run']}: failed {j['failed']} of {j['sent']}, p99 {j['p99Ms']} ms, "
                  f"worst second p99 {j['worstSecondP99Ms']} ms, catch-up {j['catchUpMs']}")
    print('first start (two permits, by pairs in one window)')
    for key, a in s['firstStart'].items():
        for job, j in a['jobs'].items():
            print(f"  {key:15} {a['code']} {job:6} run {a['run']} attempt {a['attempt']}: failed {j['failed']} of "
                  f"{j['sent']}, p99 {j['p99Ms']} ms, worst second p99 {j['worstSecondP99Ms']} ms, catch-up "
                  f"{j['catchUpMs'].get('example-service')} ms")


if __name__ == '__main__':
    base = sys.argv[1]
    s = summary(base)
    if '--json' in sys.argv:
        with open(f'{base}/summary.json', 'w') as out:
            json.dump(s, out, indent=2)
            out.write('\n')
    else:
        tables(s)
