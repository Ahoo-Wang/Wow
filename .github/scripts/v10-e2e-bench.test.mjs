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
  drifted,
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
  assert.deepEqual(all.suites, Object.keys(SUITES));
  assert.equal(all.fraction, 0.8);
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
if (command === 'saturate') {
  fs.writeFileSync(out + '/saturation.json', JSON.stringify({ saturationThroughput: opt('version') === '9x' ? 100 : 150, saturationConcurrency: 16 }));
} else {
  const rate = Number(opt('rate'));
  const slow = opt('version') === 'v10' && /r1-/.test(opt('label')) ? 1.1 : 1;
  // V10 keeps up with at most 100/s in open loop, so its probe at 120/s fails and the next, at 90/s, holds.
  const failed = opt('version') === 'v10' && rate > 100 ? rate * 10 : 0;
  fs.writeFileSync(out + '/result.json', JSON.stringify({
    results: { throughput: failed ? 0 : rate, ok: failed ? 0 : rate * 10, failed, errors: failed ? { 'http-504-wow.wait.timeout': failed } : {}, latencyMs: { p50: 2, p95: 5, p99: 10 * slow, p999: 20 }, worstSecondP99Ms: 15 },
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
    refs: { baselineRef: 'v10-9x-baseline', headRef: 'v10' },
  };
  const e1 = runSuite('e1-mongo-uniform', {
    ...options,
    out: join(dir, 'results', 'e1-mongo-uniform'),
  });
  assert.equal(e1.failures, 0);
  assert.deepEqual(e1.rates, { '9x': 80, v10: 90 });
  assert.deepEqual(
    e1.probes.map(probe => probe.name),
    ['probe1-9x', 'probe1-v10', 'probe2-v10'],
  );
  assert.deepEqual(e1.calibration.v10, { sustainable: true, share: 0.6 });
  assert.equal(e1.extraRound, true);
  assert.deepEqual(
    e1.runs.map(run => run.name),
    [
      'r1-9x',
      'r1-v10',
      'r2-v10',
      'r2-9x',
      'r3-9x',
      'r3-v10',
      'e1-9x',
      'e1-v10',
      'e2-v10',
      'e2-9x',
    ],
  );
  assert.equal(pairs(e1).length, 3);
  assert.equal(pairs(e1)[0].ratios.throughput, 1.125);
  // The equal-rate rounds: both arms at the lower calibrated rate, 80/s.
  assert.equal(e1.equalRate, 80);
  assert.deepEqual(
    e1.runs.filter(run => run.mode === 'equal').map(run => run.rate),
    [80, 80, 80, 80],
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
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(calls, /props=\{"wow.log.append-queue.enabled":true\}/);
  assert.match(
    calls,
    /--scenario=E3.*props=\{"wow.log.handoff.gap-wait":"20ms"\}/,
  );
  assert.match(
    calls,
    /saturate --version=9x --backend=mongo --scenario=E1 --distribution=uniform --warm-up=3 --step=8/,
  );

  const suites = readSuites(join(dir, 'results'));
  assert.deepEqual(
    suites.map(s => s.name),
    ['e1-mongo-uniform', 'e3-mongo-diag', 'pg-append-queue'],
  );
  const report = renderReport(suites, suites[0].refs);
  assert.match(
    report,
    /\| e1-mongo-uniform \| v10\/9x \| calibrated \(unequal load\) \| 1\.500 \| 1\.125 \(1\.125–1\.125, n=3\)/,
  );
  assert.match(
    report,
    /\| e1-mongo-uniform \| v10\/9x \| equal rate 80\.0\/s \| 1\.500 \| 1\.000 \(1\.000–1\.000, n=2\)/,
  );
  assert.match(report, /## E3 on MongoDB: diagnostics/);
  assert.match(report, /wow\.log\.max-poll-interval=5s/);
  assert.match(report, /## PostgreSQL append queue/);
  assert.match(
    readFileSync(
      join(dir, 'results', 'e1-mongo-uniform', 'summary.md'),
      'utf8',
    ),
    /\| r1-9x \| 9x \| 80\.0\/s \| 80\.0\/s/,
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
  assert.equal(suite.failures, 1);
  assert.equal(suite.saturation['v10-pg'].status, 'failed');
  assert.equal(suite.runs.length, 0);
  assert.match(renderReport([suite]), /Failed steps:\*\* e3-pg \(1\)/);
});

test('runSettings: dispatch inputs, or the defaults with the run file over them on a push', () => {
  assert.equal(
    runSettings({ event: 'workflow_dispatch', inputs: { repeats: '3' } })
      .repeats,
    '3',
  );
  assert.equal(runSettings({ event: 'push' }).baseline_ref, 'v10-9x-baseline');
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
    arms: [{ id: '9x' }, { id: 'v10' }],
    saturation: {
      '9x': { status: 'ok', throughput: 200, concurrency: 128 },
      v10: { status: 'ok', throughput: 500, concurrency: 4096, capped: true },
    },
    rates: {},
    runs: [],
    failures: 0,
  };
  const report = renderReport([suite]);
  assert.match(report, /\| e3-mongo \| v10 \| ≥ 500\.0\/s \| 4096 \|/);
});
