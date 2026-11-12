/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { MEASURE, WALK, readMeasure, walkTables } from './bench-measure.mjs';
import {
  anomalies,
  pairs,
  renderReport,
  renderSuite,
  runSuite,
  SUITES,
  suitesOf,
  usesBase,
} from './bench.mjs';
import { quantile } from './bench-common.mjs';

// A stand-in for bench.sh's `measure` command: each run writes its measure.json, the times, answers and query log of
// two queries.
function fakeMeasureBench(dir) {
  const path = join(dir, 'bench.sh');
  writeFileSync(
    path,
    `#!/usr/bin/env node
const fs = require('node:fs');
const [command, ...args] = process.argv.slice(2);
const opt = (name) => args.find((a) => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
const out = opt('out');
fs.mkdirSync(out, { recursive: true });
fs.appendFileSync(${JSON.stringify(join(dir, 'calls.log'))}, [command, ...args, 'props=' + (process.env.SPRING_APPLICATION_JSON ?? '')].join(' ') + '\\n');
  // The policy's arm answers each query in 12 ms, the other in 10; one of the policy's list answers fails.
  const policy = (process.env.SPRING_APPLICATION_JSON ?? '').includes('"dataset"');
  fs.writeFileSync(out + '/measure.json', JSON.stringify({ runs: 4, warmUpRuns: 1, shapes: ['list', 'count'] }));
  const seconds = policy ? '0.012' : '0.010';
  fs.writeFileSync(out + '/list.times', ['200', '200', '200', policy ? '500' : '200'].map(code => code + ' ' + seconds).join('\\n') + '\\n');
  fs.writeFileSync(out + '/count.times', ['200 ' + seconds, '200 ' + seconds].join('\\n') + '\\n');
  fs.writeFileSync(out + '/list.json', JSON.stringify(Array.from({ length: 50 }, (_, i) => ({ id: 'r' + i }))));
  fs.writeFileSync(out + '/count.json', JSON.stringify({ count: policy ? 100000 : 1000000 }));
  fs.writeFileSync(out + '/list.log.jsonl', JSON.stringify({ query_duration_ms: '4', read_rows: '1000', read_bytes: '20000', memory_usage: '1048576' }) + '\\n');
`,
  );
  chmodSync(path, 0o755);
  return path;
}

/**
 * A fake bench.sh for the walk suites: each run writes its measure.json, the deposits' p99 by run ([p99s], 300 ms for
 * any other), and the waits the store showed.
 */
function fakeWalkBench(dir, p99s) {
  const benchPath = join(dir, 'bench.sh');
  writeFileSync(
    benchPath,
    `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
const opt = name => args.find(a => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
fs.appendFileSync(${JSON.stringify(join(dir, 'calls.log'))}, args.join(' ') + ' ' + (process.env.SPRING_APPLICATION_JSON ?? '') + '\\n');
const run = opt('out').split('/').pop();
const props = JSON.parse(process.env.SPRING_APPLICATION_JSON ?? '{}');
const share = props['wow.access.walk-share'];
const walks = opt('walk') !== 'none';
const rate = Number(opt('rate'));
const p99 = ${JSON.stringify(p99s)}[run] ?? 300;
fs.mkdirSync(opt('out'), { recursive: true });
fs.writeFileSync(opt('out') + '/measure.json', JSON.stringify({
  program: 'walk', walks, rate, walkRate: null, walkShare: share === undefined ? null : String(share),
  completeWalk: false, targets: opt('targets') ?? 'stamped',
  walk: walks ? { completed: false, walked: 3000, seconds: 50, aggregatesPerSecond: 60, windowWalked: 2000, windowSeconds: 40, windowPerSecond: 50 } : null,
  e1: rate > 0 ? { ops: 100, failed: {}, latencyMs: { p50: 2, p99, p999: 50 } } : null,
  waits: { samples: 40, events: { 'IO:WALSync': 12, 'CPU:running': 8, 'IPC:x': 1, 'LWLock:y': 1 }, poolMax: { active: 3, pending: 0, total: 10 } },
  cpu: { windowSeconds: 40, jvmCores: 1.2, storeCores: 0.8 },
}));
`,
  );
  chmodSync(benchPath, 0o755);
  return benchPath;
}

test('the walk share suites run the head at each share between two runs with nothing to walk, and name the largest within the band', () => {
  assert.deepEqual(suitesOf('access-walk'), [
    'access-walk-share-pg',
    'access-walk-share-mongo',
    'access-walk-pg',
    'access-walk-mongo',
  ]);
  for (const name of suitesOf('access-walk')) assert.ok(usesBase(name));
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeWalkBench(dir, {
    'none-1': 6,
    'none-2': 5,
    'none-3': 4,
    'none-4': 5.5,
    base: 120,
    'share-5': 5.5,
    'share-10': 6.9,
    'share-25': 9,
  });
  const suite = runSuite('access-walk-share-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    dataset: '1k',
    installs: { base: '/b' },
  });
  assert.equal(suite.failures, 0);
  assert.equal(suite.program, 'walk');
  assert.deepEqual(
    suite.runs.map(run => run.name),
    [
      'none-1',
      'base',
      'share-5',
      'none-2',
      'share-10',
      'share-25',
      'none-3',
      'none-4',
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /^measure --program=walk --backend=postgres --install=\/b --aggregates=1000 --streams=10 --walk=none --rate=200 --warm-up=10 --measure=40 --out=.*\/none-1 $/m,
  );
  // The head's seeding node walks what it seeded with the whole share; the baseline's knows no share.
  assert.match(
    calls,
    /--walk=yes --rate=200 --warm-up=10 --measure=40 --seed\.wow\.access\.walk-share=1 --out=.*\/share-10 \{"wow\.access\.walk-share":0\.1\}$/m,
  );
  assert.doesNotMatch(calls.split('\n')[1], /walk-share/);
  const table = walkTables(suite).join('\n');
  // 2,000 aggregates in the 40 s window: 50 a second, 100k in 2,000 s, 1M in 5.6 h, 10M in 2.3 days.
  assert.match(
    table,
    /\| share-10 \| head \| 10% \| 2000 in 40\.0 s \| 50 \| 33 min \| 5\.6 h \| 2\.3 d \| 200 \| 2\.0 ms \| 6\.9 ms \| 50\.0 ms \| 0 \| yes \| IO:WALSync 12 · CPU:running 8 · IPC:x 1 \(40\) \| 3 in use, 0 waiting of 10 \| 1\.20, 0\.80 \|/,
  );
  assert.match(
    table,
    /\| share-25 \| head \| 25% .* \*\*no\*\* \(9\.0 ms > 7\.0 ms, ×1\.5\) \|/,
  );
  assert.match(table, /\| base \| base \| none \(walk-rate\) \|/);
  assert.match(table, /\| none-1 \| base \| nothing to walk \|/);
  assert.match(
    table,
    /Main's A\/A band: E1 p99 6\.0 ms, 5\.0 ms, 4\.0 ms, 5\.5 ms with nothing to walk\. A p99 less than 1 ms or 10% above its top, whichever is larger, is no harm: at most 7\.0 ms\./,
  );
  assert.match(table, /Default share: 10%, the largest within the band\./);
  assert.deepEqual(anomalies(suite), []);
  assert.deepEqual(pairs(suite), []);
  const text = renderSuite(suite);
  assert.match(text, /1000 accounts of 10 streams; E1 at 200\/s/);
  assert.doesNotMatch(renderReport([suite]), /NaN/);
});

test("the walk suites judge the head at its defaults, ABBA, against main's A/A band, and time each build idle", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeWalkBench(dir, {
    'none-1': 6,
    'none-2': 5,
    'none-3': 5.5,
    'none-4': 4,
    'base-1': 120,
    'base-2': 110,
    'head-1': 6,
    'head-2': 8,
  });
  const suite = runSuite('access-walk-mongo', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    dataset: '1k',
    installs: { base: '/b' },
  });
  assert.deepEqual(
    suite.runs.map(run => run.name),
    [
      'none-1',
      'base-1',
      'head-1',
      'none-2',
      'head-2',
      'base-2',
      'none-3',
      'none-4',
      'head-same',
      'head-follower',
      'base-follower',
      'head-idle',
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /--backend=mongo .*--targets=all --rate=200 .*\/head-follower $/m,
  );
  assert.match(calls, /--walk=yes --targets=first --rate=200 .*\/head-same $/m);
  assert.match(calls, /--install=\/b .*--targets=all .*\/base-follower $/m);
  assert.match(calls, /--walk=yes --rate=0 .*\/head-idle $/m);
  const table = walkTables(suite).join('\n');
  assert.match(
    table,
    /\| head-idle \| head \| default, no deposits \| 2000 in 40\.0 s \| 50 \|/,
  );
  assert.match(table, /\| head-same \| head \| default, same aggregates \|/);
  assert.match(
    table,
    /\| head-follower \| head \| default, deposits follow unstamped \|/,
  );
  // The runs beside the verdict and the idle one are not judged: 8 ms against at most 7 (6, plus 1) is the worst judged.
  assert.match(
    table,
    /Verdict: \*\*outside\*\* main's A\/A band, by 1\.0 ms at worst \(6\.0 ms, 8\.0 ms against at most 7\.0 ms\)\./,
  );
  // Without the baseline's build, its runs are unavailable and there is no band.
  const alone = runSuite('access-walk-pg', {
    benchPath,
    out: join(dir, 'alone'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    dataset: '1k',
    installs: {},
  });
  assert.equal(
    alone.runs.filter(run => run.status === 'unavailable').length,
    7,
  );
  assert.match(
    walkTables(alone).join('\n'),
    /No verdict: the runs with nothing to walk did not both pass\./,
  );
  assert.equal(WALK.shares.length, 3);
});

test("the codec reads suite times the dataset's queries with its _state under LZ4 and under the Sink's codecs, ABBA", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeMeasureBench(dir);
  const suite = runSuite('access-dataset-ch-codecs', {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    out: join(dir, 'results', 'access-dataset-ch-codecs'),
  });
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    [
      ['1-lz4', 'ok'],
      ['1-codecs', 'ok'],
      ['2-codecs', 'ok'],
      ['2-lz4', 'ok'],
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  // Only the LZ4 arm strips the codecs; both run under the dataset policy.
  assert.deepEqual(
    calls.map(call => call.includes('--codecs=lz4')),
    [true, false, false, true],
  );
  for (const call of calls) assert.match(call, /"benchmark.access":"dataset"/);
});

test('the access-dataset suite times each dataset query of both arms, ABBA, and judges them by the A/A band', () => {
  assert.deepEqual(suitesOf('access-dataset'), ['access-dataset-ch']);
  assert.deepEqual(
    SUITES['access-dataset-ch'].arms.map(arm => arm.props),
    [{ 'benchmark.access': 'claims' }, { 'benchmark.access': 'dataset' }],
  );
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeMeasureBench(dir);
  const suite = runSuite('access-dataset-ch', {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    out: join(dir, 'results', 'access-dataset-ch'),
  });
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    [
      ['1-claims', 'ok'],
      ['1-policy', 'ok'],
      ['2-policy', 'ok'],
      ['2-claims', 'ok'],
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  assert.equal(calls.length, 4);
  for (const call of calls)
    assert.match(
      call,
      new RegExp(
        `^measure --backend=postgres --runs=${MEASURE.runs} --warm-up-runs=${MEASURE.warmUpRuns} `,
      ),
    );
  assert.match(calls[1], /"benchmark.access":"dataset"/);
  const list = suite.runs[1].measure.shapes.list;
  assert.equal(list.timed, 4);
  assert.equal(list.failed, 1);
  assert.equal(list.p50, 12);
  assert.equal(list.answer, 50);
  assert.equal(list.clickhouse.readRows, 1000);
  assert.equal(suite.runs[0].measure.shapes.count.answer, 1000000);
  assert.equal(suite.runs[0].measure.shapes.count.clickhouse.queries, 0);
  const text = renderSuite(suite);
  assert.match(
    text,
    /\| list \| claims \| 10\.0 ms \| 10\.0 ms \| 10\.0 ms \| 0\.0% \| 0 \| 50 \|/,
  );
  assert.match(text, /\| list \| policy \| 12\.0 ms .* \| 2 \| 50 \|/);
  assert.match(text, /\| count \| ×1\.20 \| no \|/);
  // The policy's failed list answers, one a run.
  assert.deepEqual(
    anomalies(suite).map(row => [row.run, row.context, row.kind]),
    [
      ['1-policy', 'list', 'failed queries'],
      ['2-policy', 'list', 'failed queries'],
    ],
  );
  assert.match(text, /Anomalies:/);
  assert.equal(readMeasure(join(dir, 'nothing')), undefined);
  assert.equal(quantile([], 0.5), null);
  assert.equal(quantile([3, 1, 2], 0.99), 3);
});
