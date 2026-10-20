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
        status: 'ok',
        metrics: metrics(result(800, 10)),
      },
      {
        round: 0,
        arm: 'queue-on',
        status: 'ok',
        metrics: metrics(result(800, p99On)),
      },
    ],
  });
  assert.match(queueVerdict(suite(1200, 10)).verdict, /on/);
  assert.match(queueVerdict(suite(900, 10)).verdict, /off/);
  assert.match(queueVerdict(suite(1020, 10.2)).verdict, /no clear difference/);
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
if (command === 'saturate') {
  fs.writeFileSync(out + '/saturation.json', JSON.stringify({ saturationThroughput: opt('version') === '9x' ? 100 : 150, saturationConcurrency: 16 }));
} else {
  const rate = Number(opt('rate'));
  const slow = opt('version') === 'v10' && /r1-/.test(opt('label')) ? 1.1 : 1;
  fs.writeFileSync(out + '/result.json', JSON.stringify({
    results: { throughput: rate, ok: rate * 10, failed: 0, latencyMs: { p50: 2, p95: 5, p99: 10 * slow, p999: 20 }, worstSecondP99Ms: 15 },
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
  assert.deepEqual(e1.rates, { '9x': 80, v10: 120 });
  assert.equal(e1.extraRound, true);
  assert.deepEqual(
    e1.runs.map(run => run.name),
    ['r1-9x', 'r1-v10', 'r2-v10', 'r2-9x', 'r3-9x', 'r3-v10'],
  );
  assert.equal(pairs(e1).length, 3);
  assert.equal(pairs(e1)[0].ratios.throughput, 1.5);

  const queue = runSuite('pg-append-queue', {
    ...options,
    out: join(dir, 'results', 'pg-append-queue'),
  });
  assert.deepEqual(queue.rates, { 'queue-off': 120, 'queue-on': 120 });
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
    /\| e1-mongo-uniform \| v10\/9x \| 1\.500 \| 1\.500 \(1\.500–1\.500, n=3\)/,
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
