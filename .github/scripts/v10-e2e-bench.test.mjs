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
import {
  anomalies,
  anomaliesOf,
  CLUSTER,
  drifted,
  mergeSignals,
  planMatrix,
  parseJob,
  bootstrapRatio,
  confidence,
  halves,
  noiseFile,
  verdict,
  metrics,
  pairs,
  queueVerdict,
  ratios,
  readSuites,
  renderReport,
  resolveInputs,
  roundOrder,
  lastSignals,
  runSettings,
  runSuite,
  spread,
  SUITES,
} from './v10-e2e-bench.mjs';

const result = (throughput, p99, cores = 1) => ({
  results: {
    throughput,
    ok: throughput * 10,
    failed: 0,
    latencyMs: { p50: p99 / 4, p95: p99 / 2, p99, p999: p99 * 2 },
    worstSecondP99Ms: p99 * 1.5,
  },
  resources: {
    processes: { 'node-1': { averageCores: cores } },
    containers: { 'wow-e2e-bench-mongo': { cpuPercentAverage: 50 } },
  },
});

test('resolveInputs checks the inputs and expands all', () => {
  const all = resolveInputs({
    suites: 'all',
    warmUp: '20',
    measure: '40',
    repeats: '2',
    fraction: '0.8',
  });
  // `all` is every suite but the 9.x ones; `vs-9x` names those.
  assert.deepEqual(
    all.suites,
    Object.keys(SUITES).filter(name => SUITES[name].group !== '9x'),
  );
  assert.equal(all.fraction, 0.8);
  assert.equal(all.p99LimitMs, 200);
  assert.deepEqual(
    resolveInputs({
      suites: 'vs-9x e3-pg',
      warmUp: 1,
      measure: 5,
      repeats: 1,
      fraction: 1,
      p99LimitMs: '500',
    }),
    {
      suites: ['vs-9x-e1', 'vs-9x-e3', 'vs-9x-cliff-e3', 'e3-pg'],
      warmUp: 1,
      measure: 5,
      repeats: 1,
      fraction: 1,
      p99LimitMs: 500,
      tier: 'custom',
      datasets: ['100k'],
      aa: false,
    },
  );
  // No suites named: the tier's list, L2 by default.
  const byTier = tier =>
    resolveInputs({ tier, warmUp: 30, measure: 60, repeats: 2, fraction: 0.8 });
  assert.deepEqual(byTier(undefined).suites, [
    'e1-mongo-uniform',
    'e1-pg-uniform',
    'e3-mongo',
    'e3-pg',
  ]);
  assert.equal(byTier(undefined).tier, 'L2');
  assert.deepEqual(byTier('L4').suites.slice(0, 2), [
    'sustainable-mongo',
    'sustainable-pg',
  ]);
  assert.throws(() => byTier('L9'), /Unknown tier/);
  // The data sets: L3 runs its grid at 1k and 100k; `dataset` replaces the tier's.
  assert.deepEqual(byTier('L3').datasets, ['1k', '100k']);
  assert.deepEqual(byTier('L2').datasets, ['100k']);
  assert.deepEqual(
    resolveInputs({
      dataset: '1M, 1k',
      aa: 'true',
      warmUp: 30,
      measure: 60,
      repeats: 2,
      fraction: 0.8,
    }),
    {
      suites: ['e1-mongo-uniform', 'e1-pg-uniform', 'e3-mongo', 'e3-pg'],
      warmUp: 30,
      measure: 60,
      repeats: 2,
      fraction: 0.8,
      p99LimitMs: 200,
      tier: 'L2',
      datasets: ['1M', '1k'],
      aa: true,
    },
  );
  assert.throws(
    () =>
      resolveInputs({
        dataset: '10k',
        warmUp: 30,
        measure: 60,
        repeats: 2,
        fraction: 0.8,
      }),
    /Unknown data set/,
  );
  assert.deepEqual(parseJob('e1-mongo-uniform.100k'), {
    suite: 'e1-mongo-uniform',
    dataset: '100k',
  });
  assert.deepEqual(
    resolveInputs({
      suites: 'e3-pg, e1-mongo-zipf e3-pg',
      warmUp: 0,
      measure: 5,
      repeats: 1,
      fraction: '1',
    }).suites,
    ['e3-pg', 'e1-mongo-zipf'],
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'e9',
        warmUp: 1,
        measure: 5,
        repeats: 1,
        fraction: 1,
      }),
    /Unknown suite/,
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'all',
        warmUp: 1,
        measure: 5,
        repeats: 9,
        fraction: 1,
      }),
    /repeats/,
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'all',
        warmUp: 1,
        measure: 5,
        repeats: 1,
        fraction: '1.5',
      }),
    /fraction/,
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'all',
        warmUp: 'x',
        measure: 5,
        repeats: 1,
        fraction: 1,
      }),
    /warm_up/,
  );
});

test('metrics, ratios, spread and drift', () => {
  const m = metrics(result(100, 20, 1.5));
  assert.equal(m.serverCores, 1.5);
  assert.equal(m.containerCores, 0.5);
  assert.equal(m.cpuMsPerOp, 20);
  assert.deepEqual(m.containers, { mongo: 50 });
  const r = ratios(m, metrics(result(120, 10, 1.5)));
  assert.equal(r.throughput, 1.2);
  assert.equal(r.p99, 0.5);
  assert.equal(spread([100, 104]), 0.040000000000000036);
  assert.equal(spread([100]), 0);
  const arms = [{ id: 'a' }];
  const run = (throughput, p99) => ({
    arm: 'a',
    status: 'ok',
    metrics: { throughput, p99 },
  });
  assert.equal(drifted([run(100, 10), run(103, 10.4)], arms), false);
  assert.equal(drifted([run(100, 10), run(100, 11)], arms), true);
  assert.deepEqual(roundOrder(['a', 'b'], 0), ['a', 'b']);
  assert.deepEqual(roundOrder(['a', 'b'], 1), ['b', 'a']);
});

test('queueVerdict reads saturation and same-load p99', () => {
  const suite = (on, p99On) => ({
    arms: [{ id: 'queue-off' }, { id: 'queue-on' }],
    saturation: {
      'queue-off': { throughput: 1000 },
      'queue-on': { throughput: on },
    },
    runs: [
      {
        round: 0,
        arm: 'queue-off',
        mode: 'equal',
        status: 'ok',
        metrics: metrics(result(800, 10)),
      },
      {
        round: 0,
        arm: 'queue-on',
        mode: 'equal',
        status: 'ok',
        metrics: metrics(result(800, p99On)),
      },
    ],
  });
  assert.match(queueVerdict(suite(1200, 10)).verdict, /on/);
  assert.match(queueVerdict(suite(900, 10)).verdict, /off/);
  assert.match(queueVerdict(suite(1020, 10.2)).verdict, /no clear difference/);
  assert.match(queueVerdict(suite(970, 6.8)).verdict, /on/);
});

// A stand-in for bench.sh: writes what the real one writes, a saturation of 100/s for 9x and 150/s for v10, and
// throughput = rate; the first v10 run is slower by 10%, so the drift check adds a round.
function fakeBench(dir) {
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
if (process.env.SPRING_APPLICATION_JSON?.includes('bench.signals.enabled')) {
  fs.writeFileSync(out + '/node-1.log', 'x bench-signals {"t":1}\\nx WARN bench-signals {"t":2,"tailRead":{"gap":3}}\\n');
}
// The head is the V10 build without --install, the baseline the one with it.
const head = opt('version') === 'v10' && !opt('install');
if (command === 'saturate') {
  fs.writeFileSync(out + '/saturation.json', JSON.stringify({ saturationThroughput: opt('version') === '9x' ? 100 : 150, saturationConcurrency: 16 }));
} else {
  console.log('[run] fake: measure');
  // A cluster run lingers a moment, so the hand-over helper sees the measured phase start.
  if (opt('nodes')) require('node:child_process').execSync('sleep 2');
  const rate = Number(opt('rate'));
  const slow = head && /r1-(head|b)$/.test(opt('label')) ? 1.1 : 1;
  // The head keeps up with at most 100/s in open loop, so its probe at 120/s fails and the next, at 90/s, holds.
  const failed = head && rate > 100 ? rate * 10 : 0;
  fs.writeFileSync(out + '/result.json', JSON.stringify({
    load: { rate }, results: { perSecond: [{ ok: failed ? 0 : rate }], throughput: failed ? 0 : rate, ok: failed ? 0 : rate * 10, failed, errors: failed ? { 'http-504-wow.wait.timeout': failed } : {}, latencyMs: { p50: 2, p95: 5, p99: 10 * slow, p999: 20 }, worstSecondP99Ms: 15 },
    resources: { processes: { 'node-1': { averageCores: 1 } }, containers: { 'wow-e2e-bench-mongo': { cpuPercentAverage: 40 }, 'wow-e2e-bench-kafka': { cpuPercentAverage: 20 } } },
  }));
}
`,
  );
  chmodSync(path, 0o755);
  return path;
}

test('runSuite pairs the arms, adds a round on drift, and the report combines the suites', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = fakeBench(dir);
  const options = {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
    installs: { base: '/builds/base/e2e-bench-v10' },
    refs: { baseRef: 'base', baseSha: 'b1', headRef: 'v10', headSha: 'h1' },
  };
  const e1 = runSuite('e1-mongo-uniform', {
    ...options,
    out: join(dir, 'results', 'e1-mongo-uniform'),
  });
  assert.equal(e1.failures, 0);
  assert.deepEqual(e1.rates, { base: 120, head: 90 });
  assert.deepEqual(
    e1.probes.map(probe => probe.name),
    ['probe1-base', 'probe1-head', 'probe2-head'],
  );
  assert.deepEqual(e1.calibration.head, { sustainable: true, share: 0.6 });
  assert.equal(e1.extraRound, true);
  assert.deepEqual(
    e1.runs.map(run => run.name),
    [
      'r1-base',
      'r1-head',
      'r2-head',
      'r2-base',
      'r3-base',
      'r3-head',
      'e1-base',
      'e1-head',
      'e2-head',
      'e2-base',
    ],
  );
  assert.equal(pairs(e1).length, 3);
  assert.equal(pairs(e1)[0].ratios.throughput, 0.75);
  // The equal-rate rounds: both arms at the lower calibrated rate, 90/s.
  assert.equal(e1.equalRate, 90);
  assert.deepEqual(
    e1.runs.filter(run => run.mode === 'equal').map(run => run.rate),
    [90, 90, 90, 90],
  );
  assert.equal(pairs(e1, 'equal').length, 2);
  assert.equal(pairs(e1, 'equal')[0].ratios.throughput, 1);

  const queue = runSuite('pg-append-queue', {
    ...options,
    out: join(dir, 'results', 'pg-append-queue'),
  });
  assert.deepEqual(queue.rates, { 'queue-off': 90, 'queue-on': 90 });
  assert.deepEqual(
    queue.probes.map(probe => probe.name),
    ['probe1-queue-off', 'probe2-queue-off'],
  );
  const diag = runSuite('e3-mongo-diag', {
    ...options,
    out: join(dir, 'results', 'e3-mongo-diag'),
  });
  assert.equal(diag.runs.length, SUITES['e3-mongo-diag'].runs.length);
  const vs9x = runSuite('vs-9x-e1', {
    ...options,
    out: join(dir, 'results', 'vs-9x-e1'),
  });
  assert.deepEqual(vs9x.rates, { '9x': 80, head: 90 });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  // Every V10 run turns the signals on, its own properties beside them; the baseline runs its own build.
  assert.match(
    calls,
    /props=\{"bench\.signals\.enabled":true,"wow\.log\.append-queue\.enabled":true\}/,
  );
  assert.match(
    calls,
    /--scenario=E3.*props=\{"bench\.signals\.enabled":true,"wow\.log\.handoff\.gap-wait":"20ms"\}/,
  );
  assert.match(
    calls,
    /saturate --version=v10 --backend=mongo --install=\/builds\/base\/e2e-bench-v10 --scenario=E1 --distribution=uniform --accounts=100000 --seed-concurrency=256 --prime --warm-up=3 --step=8/,
  );
  assert.match(
    calls,
    /saturate --version=9x --backend=mongo --scenario=E1 .*props=\n/,
  );

  const suites = readSuites(join(dir, 'results'));
  assert.deepEqual(
    suites.map(s => s.name),
    ['e1-mongo-uniform', 'e3-mongo-diag', 'pg-append-queue', 'vs-9x-e1'],
  );
  const report = renderReport(suites, {
    ...suites[0].refs,
    skipped: ['e3-pg (no V10 baseline)'],
  });
  assert.match(
    report,
    /Head: `v10` h1; V10 baseline: `base` b1; 9\.x: not run/,
  );
  assert.match(report, /\*\*Skipped:\*\* e3-pg \(no V10 baseline\)/);
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| head\/base \| calibrated \(unequal load\) \| 1\.000 \| 0\.750 \(0\.750–0\.750, n=3\)/,
  );
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| head\/base \| equal rate 90\.0\/s \| 1\.000 \| 1\.000 \(1\.000–1\.000, n=2\)/,
  );
  assert.match(report, /\| vs-9x-e1@100k \| head\/9x \| calibrated/);
  assert.match(report, /## E3 on MongoDB: diagnostics/);
  assert.match(report, /wow\.log\.max-poll-interval=5s/);
  assert.match(report, /## PostgreSQL append queue/);
  // The fake node logs say the tails read the store after a gap: an anomaly on every V10 run.
  assert.match(report, /## Anomalies/);
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| r1-head \| steady \| \*\*tail read the store\*\* \| gap=3 \|/,
  );
  assert.match(
    readFileSync(
      join(dir, 'results', 'e1-mongo-uniform', 'summary.md'),
      'utf8',
    ),
    /\| r1-base \| base \| 120\.0\/s \| 120\.0\/s/,
  );
});

test('planMatrix skips the suites whose commits are missing and lists the builds', () => {
  const suites = ['e1-mongo-uniform', 'cliff-e3-pg', 'vs-9x-e1'];
  assert.deepEqual(
    planMatrix({ suites, headSha: 'h', baseSha: '', nineXSha: 'n' }),
    {
      suites: ['cliff-e3-pg', 'vs-9x-e1'],
      jobs: ['cliff-e3-pg.100k', 'vs-9x-e1.100k'],
      skipped: [
        'e1-mongo-uniform (no V10 baseline: set base_ref; no v10.* release tag has the benchmark yet)',
      ],
      builds: [
        { side: 'head', sha: 'h', scope: 'v10', dir: 'bench-v10' },
        { side: '9x', sha: 'n', scope: 'all', dir: 'bench-9x' },
      ],
    },
  );
  assert.deepEqual(
    planMatrix({
      suites,
      headSha: 'h',
      baseSha: 'b',
      nineXSha: 'n',
    }).builds.map(build => build.side),
    ['head', 'base', '9x'],
  );
});

test('a capacity suite bisects for the highest rate within the error and p99 limits', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = fakeBench(dir);
  const options = {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
  };
  const suite = runSuite('sustainable-pg', {
    ...options,
    out: join(dir, 'pg'),
  });
  // Saturation 150/s; the head keeps up with at most 100/s: 75 holds, 112.5 fails, 93.75 holds, 103.1 fails,
  // 98.4 holds, and the bracket is then within 5% of the top.
  assert.deepEqual(Object.keys(suite.capacity), ['E1', 'E3']);
  assert.equal(suite.capacity.E1.rate, 98.4375);
  assert.equal(suite.capacity.E1.p99, 10);
  assert.deepEqual(
    suite.probes.filter(p => p.scenario === 'E1').map(p => [p.rate, p.held]),
    [
      [75, true],
      [112.5, false],
      [93.75, true],
      [103.125, false],
      [98.4375, true],
    ],
  );
  const strict = runSuite('sustainable-pg', {
    ...options,
    p99LimitMs: 5,
    out: join(dir, 'strict'),
  });
  assert.equal(strict.capacity.E1.rate, undefined);
  const report = renderReport([suite]);
  assert.match(report, /## Sustainable rates/);
  assert.match(report, /\| E1 \| \*\*98\.4\/s\*\* \| 2\.0 ms \| 10\.0 ms \|/);
});

test('a cluster suite runs three nodes steady and with a hand-over', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = fakeBench(dir);
  const suite = runSuite('e5-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    cluster: { ...CLUSTER, stopAfter: 0, down: 0 },
  });
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.scenario, run.handover ?? false]),
    [
      ['e1-steady', 'E1', false],
      ['e1-handover', 'E1', true],
      ['e3-steady', 'E3', false],
      ['e3-handover', 'E3', true],
    ],
  );
  assert.equal(suite.failures, 0);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /run --version=v10 --backend=postgres --scenario=E1 --distribution=uniform --accounts=100000 --seed-concurrency=256 --prime --nodes=3 --rate=150\.0 --warm-up=15 --measure=90/,
  );
  // The hand-over helper ran beside bench.sh and logged what it did (here it finds no node to stop).
  assert.match(
    readFileSync(join(dir, 'out', 'e1-handover', 'handover.log'), 'utf8'),
    /\[handover\] no node on port 18081/,
  );
  assert.match(renderReport([suite]), /## E5 cluster: steady and hand-over/);
});

test('anomalies flag pauses, store fallbacks, error spikes, dips and long tails', () => {
  const run = (m, signals) => ({
    name: 'r',
    status: 'ok',
    metrics: {
      ok: 1000,
      failed: 0,
      weakSeconds: 0,
      p50: 5,
      p99: 20,
      errors: {},
      ...m,
    },
    signals,
  });
  assert.deepEqual(anomaliesOf(run({})), []);
  assert.deepEqual(
    anomaliesOf(run({ failed: 20, errors: { 'http-408': 20 } })).map(
      a => a.kind,
    ),
    ['error spike'],
  );
  assert.deepEqual(
    anomaliesOf(run({ weakSeconds: 2, minSecondOk: 10 })).map(a => a.kind),
    ['throughput dip'],
  );
  assert.deepEqual(
    anomaliesOf(run({ p50: 5, p99: 60 })).map(a => a.kind),
    ['p99 > 10× p50'],
  );
  // A 30 ms p99 over a 2 ms median is below the floor.
  assert.deepEqual(anomaliesOf(run({ p50: 2, p99: 30 })), []);
  const paused = run(
    { failed: 500 },
    {
      partitionPaused: { 'TransferSaga.onPrepared': 2 },
      tailRead: { idle: 9, overflow: 1 },
    },
  );
  assert.deepEqual(
    anomaliesOf(paused).map(a => a.kind),
    ['partition paused', 'tail read the store', 'error spike'],
  );
  // Past the knee by design, errors are expected; the signals still count.
  assert.deepEqual(
    anomaliesOf(paused, { overload: true }).map(a => a.kind),
    ['partition paused', 'tail read the store'],
  );
  const suite = {
    name: 'cliff',
    kind: 'steps',
    runs: [{ ...paused, sustained: false }],
    probes: [],
  };
  assert.deepEqual(
    anomalies(suite).map(a => a.context),
    ['overload (cliff step)', 'overload (cliff step)'],
  );
  assert.deepEqual(
    mergeSignals([
      { t: 3, tailRead: { gap: 1 } },
      undefined,
      { t: 5, tailRead: { gap: 2, idle: 4 } },
    ]),
    { t: 5, tailRead: { gap: 3, idle: 4 } },
  );
});

test('a failed bench.sh step is recorded and counted', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = join(dir, 'bench.sh');
  writeFileSync(benchPath, '#!/bin/sh\nexit 3\n');
  chmodSync(benchPath, 0o755);
  const suite = runSuite('e3-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
  });
  assert.equal(suite.failures, 2);
  assert.equal(suite.saturation.base.status, 'failed');
  assert.equal(suite.saturation.head.status, 'failed');
  assert.equal(suite.runs.length, 0);
  assert.match(renderReport([suite]), /Failed steps:\*\* e3-pg \(2\)/);
});

test('runSettings: dispatch inputs, or the defaults with the run file over them on a push', () => {
  assert.equal(
    runSettings({ event: 'workflow_dispatch', inputs: { repeats: '3' } })
      .repeats,
    '3',
  );
  assert.equal(runSettings({ event: 'push' }).nine_x_ref, 'v10-9x-baseline');
  assert.equal(runSettings({ event: 'push' }).base_ref, '');
  assert.equal(
    runSettings({
      event: 'push',
      config: { base_ref: 'v10.0.0-beta.1', p99_limit_ms: 500 },
    }).p99_limit_ms,
    '500',
  );
  const pushed = runSettings({
    event: 'push',
    inputs: { repeats: '' },
    config: { suites: ['e3-pg', 'e1-mongo-zipf'], repeats: 3 },
  });
  assert.equal(pushed.suites, 'e3-pg,e1-mongo-zipf');
  assert.equal(pushed.repeats, '3');
  assert.equal(pushed.measure, '40');
  assert.throws(
    () => runSettings({ event: 'push', config: { head_ref: 'x' } }),
    /Unknown key/,
  );
});

test('lastSignals reads the last signals line', () => {
  assert.deepEqual(
    lastSignals(
      'a\n2026 WARN BenchSignals bench-signals {"t":1}\nb bench-signals {"t":2,"refused":{"wow.command.busy":4}}\nc',
    ),
    { t: 2, refused: { 'wow.command.busy': 4 } },
  );
  assert.equal(lastSignals('nothing'), undefined);
});

test('a cliff suite steps up until two steps in a row are not kept up', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = fakeBench(dir);
  const suite = runSuite('cliff-e3-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
  });
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.sustained]),
    [
      ['s1-50', true],
      ['s2-100', true],
      ['s3-200', false],
      ['s4-300', false],
    ],
  );
  assert.deepEqual(suite.runs[0].signals, { t: 2, tailRead: { gap: 3 } });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /--scenario=E3 .*--rate=50\.0 --warm-up=10 --measure=60 .*props=\{"bench\.signals\.enabled":true\}/,
  );
  const report = renderReport([suite]);
  assert.match(report, /## E3 cliff steps/);
  assert.match(report, /\| s3-200 \| 200\.0\/s \| 0\.0\/s \| \*\*no\*\* \|/);
  assert.match(report, /tailRead gap=3/);
});

test('a saturation found at the concurrency cap is marked as a lower bound', () => {
  const suite = {
    name: 'e3-mongo',
    kind: 'paired',
    arms: [{ id: 'base' }, { id: 'head' }],
    saturation: {
      base: { status: 'ok', throughput: 200, concurrency: 128 },
      head: { status: 'ok', throughput: 500, concurrency: 4096, capped: true },
    },
    rates: {},
    runs: [],
    failures: 0,
  };
  const report = renderReport([suite]);
  assert.match(report, /\| e3-mongo@100k \| head \| ≥ 500\.0\/s \| 4096 \|/);
});

test('A/A runs give the noise thresholds; bootstrap intervals decide what clears them', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = fakeBench(dir);
  const suite = runSuite('e1-mongo-uniform', {
    benchPath,
    out: join(dir, 'aa'),
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
    aa: true,
    dataset: '1k',
    cpus: { load: '3', server: '0-2' },
  });
  // The head against itself, no baseline build; pinned CPUs and the 1k data set on every call.
  assert.equal(suite.aa, true);
  assert.deepEqual(
    suite.arms.map(arm => [arm.id, arm.install]),
    [
      ['a', 'head'],
      ['b', 'head'],
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.doesNotMatch(calls, /--install=/);
  assert.match(
    calls,
    /--accounts=1000 --seed-concurrency=32 --load-cpus=3 --server-cpus=0-2/,
  );
  // At 1k the runs are not primed; an A/A run takes at least 4 equal-rate rounds and no drift round.
  assert.equal(suite.primed, false);
  assert.doesNotMatch(calls, /--prime/);
  assert.equal(suite.runs.filter(run => run.mode === 'equal').length, 8);
  assert.equal(suite.extraRound, undefined);
  const noise = noiseFile([suite], { 'e3-pg@100k': { p99: 0.1 } });
  assert.deepEqual(Object.keys(noise), ['e3-pg@100k', 'e1-mongo-uniform@1k']);
  // The thresholds are the equal-rate intervals' half-widths, at least 2%: identical arms there give the floor.
  assert.deepEqual(noise['e1-mongo-uniform@1k'], {
    throughput: 0.02,
    p50: 0.02,
    p99: 0.02,
  });
  const report = renderReport([suite], { noise: {} });
  assert.match(report, /## A\/A noise/);
  assert.match(report, /e1-mongo-uniform@1k \(A\/A: head against head\)/);

  // Bootstrap: identical samples give a ratio of 1 with a tight interval; a 20% shift clears a 5% threshold.
  const a = Array.from({ length: 40 }, (_, i) => 100 + (i % 5));
  const same = bootstrapRatio(a, a, 'mean');
  assert.ok(same.lo <= 1 && same.hi >= 1 && same.hi - same.lo < 0.03);
  const faster = bootstrapRatio(
    a,
    a.map(v => v * 1.2),
    'mean',
  );
  assert.ok(faster.lo > 1.15);
  assert.equal(verdict(faster, 0.05), 'higher');
  assert.equal(verdict(same, 0.05), 'within noise');
  assert.equal(verdict(faster, undefined), 'uncalibrated');
  assert.equal(verdict(undefined, 0.05), 'no data');
  assert.equal(
    confidence({ arms: [{ id: 'x' }], runs: [] }, 'equal').p99,
    undefined,
  );
});

test('stability compares the halves; a busy load generator and an unstable run are flagged', () => {
  // p99 is compared only with a thousand tail samples a half (a hundred thousand operations): not here.
  assert.deepEqual(
    halves([100, 100, 100, 100], [10, 10, 10, 10], [2, 2, 2, 2]),
    {
      throughputChange: 0,
      p50Ratio: 1,
      p99Ratio: undefined,
    },
  );
  assert.deepEqual(halves([100, 100, 80, 80], [10, 10, 20, 20], [2, 2, 3, 3]), {
    throughputChange: -0.19999999999999996,
    p50Ratio: 1.5,
    p99Ratio: undefined,
  });
  assert.deepEqual(
    halves([60000, 60000, 60000, 60000], [10, 10, 20, 20]).p99Ratio,
    2,
  );
  assert.equal(halves([1, 2], [1, 2]), undefined);
  const run = m => ({
    name: 'r',
    status: 'ok',
    metrics: {
      ok: 1000,
      failed: 0,
      weakSeconds: 0,
      p50: 5,
      p99: 20,
      errors: {},
      ...m,
    },
  });
  assert.deepEqual(
    anomaliesOf(
      run({ stability: { throughputChange: -0.2, p99Ratio: 1 } }),
    ).map(a => a.kind),
    ['unstable'],
  );
  assert.deepEqual(
    anomaliesOf(run({ stability: { throughputChange: 0.01, p99Ratio: 1.2 } })),
    [],
  );
  // The suite's A/A noise replaces the defaults: a 20% p50 drift is within ±25%, beyond an A/A ±10%.
  const drift = run({ stability: { throughputChange: 0, p50Ratio: 1.2 } });
  assert.deepEqual(anomaliesOf(drift), []);
  assert.match(
    anomaliesOf(drift, { noise: { throughput: 0.02, p50: 0.1, p99: 0.5 } })[0]
      .detail,
    /beyond the A\/A noise: p50 \+20\.0% \(±10\.0%\)/,
  );
  assert.deepEqual(
    anomaliesOf(run({ loadGeneratorCores: 0.95, loadGeneratorCpus: 1 })).map(
      a => a.kind,
    ),
    ['load generator saturated'],
  );
  const m = metrics({
    load: { rate: 100 },
    results: {
      throughput: 100,
      perSecond: [
        { ok: 100, p50Ms: 1, p99Ms: 5 },
        { ok: 100, p50Ms: 1, p99Ms: 5 },
      ],
    },
    resources: {
      processes: { 'node-1': { averageCores: 0.5 } },
      containers: { 'wow-e2e-bench-mongo': { cpuPercentAverage: 120 } },
      loadGenerator: { averageCores: 0.3, availableProcessors: 1 },
    },
  });
  assert.deepEqual(m.bottleneck, { side: 'mongo', cores: 1.2 });
  assert.deepEqual(m.p99Series, [5, 5]);
});

test('a mixed suite sends its mix and shows each scenario', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = fakeBench(dir);
  runSuite('mixed-pg', {
    benchPath,
    out: join(dir, 'mixed'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    installs: { base: '/b' },
  });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /--scenario=mixed --mix=E1:50,E4-state:40,E4-list:10 --distribution=uniform/,
  );
});

test('large data sets are primed before every run; small ones are not; the report says what each started from', () => {
  const dir = mkdtempSync(join(tmpdir(), 'v10-e2e-bench-'));
  const benchPath = fakeBench(dir);
  const options = {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    installs: { base: '/b' },
  };
  const big = runSuite('e1-pg-uniform', {
    ...options,
    dataset: '100k',
    out: join(dir, 'big'),
  });
  assert.equal(big.primed, true);
  const small = runSuite('e1-pg-uniform', {
    ...options,
    dataset: '1k',
    out: join(dir, 'small'),
  });
  assert.equal(small.primed, false);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8')
    .split('\n')
    .filter(Boolean);
  assert.ok(
    calls
      .filter(line => line.includes('/big/'))
      .every(line => line.includes('--prime')),
  );
  assert.ok(
    calls
      .filter(line => line.includes('/small/'))
      .every(line => !line.includes('--prime')),
  );
  const report = renderReport([big, small]);
  assert.match(
    report,
    /Primed: every run touched each of the 100000 accounts once before its warm-up/,
  );
  assert.match(report, /exceed the node's state cache \(4096 per context/);
  assert.match(report, /Not primed: 1000 accounts, below 100000\./);
});

test('p99.9 is reported only with a thousand tail samples', () => {
  const few = metrics({ results: { ok: 40000, latencyMs: { p999: 50 } } });
  assert.equal(few.p999, undefined);
  const many = metrics({ results: { ok: 1000000, latencyMs: { p999: 50 } } });
  assert.equal(many.p999, 50);
  const primed = metrics({
    results: {},
    meta: { primed: 'true', primeSeconds: '35' },
  });
  assert.deepEqual([primed.primed, primed.primeSeconds], [true, 35]);
});
