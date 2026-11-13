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
  renderSuite,
  runSuite,
  SUITES,
  suitesOf,
  usesBase,
} from './bench.mjs';
import { GENERATOR_BOUND } from './bench-calibration.mjs';
import {
  CURVE,
  curveAnomalies,
  curveArgs,
  curveRows,
  curveSection,
  curveTable,
} from './bench-curves.mjs';

// A stand-in for bench.sh's `saturate`: every step of the curve at a throughput of `clients` × the arm's factor
// (the head, built without --install, 1.2), its sweep number adding 1%, so each arm's sweeps spread a little.
function fakeCurveBench(dir) {
  const path = join(dir, 'bench.sh');
  writeFileSync(
    path,
    `#!/usr/bin/env node
const fs = require('node:fs');
const [command, ...args] = process.argv.slice(2);
const opt = (name) => args.find((a) => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
const out = opt('out');
fs.mkdirSync(out, { recursive: true });
fs.appendFileSync(${JSON.stringify(join(dir, 'calls.log'))}, [command, ...args].join(' ') + '\\n');
const factor = opt('install') ? 1 : 1.2;
const sweep = Number(opt('label').split('-').pop());
const steps = opt('concurrencies').split(',').map(Number).map((concurrency) => ({
  concurrency, throughput: concurrency * factor * (1 + sweep / 100), p50Ms: 1, p99Ms: 10 / factor, ok: 1000, errors: {}, generatorCores: 0.5,
}));
fs.writeFileSync(out + '/saturation.json', JSON.stringify({ availableProcessors: 2, steps }));
`,
  );
  chmodSync(path, 0o755);
  return path;
}

// A curve suite whose arms swept the given throughputs at one step of 64 clients.
const swept = (base, head, extra = {}) => ({
  name: 'e3-pg-curve',
  arms: [{ id: 'base' }, { id: 'head' }],
  curve: {
    concurrencies: [64],
    sweeps: {
      base: base.map((throughput, index) => ({
        status: 'ok',
        key: `base-${index + 1}`,
        steps: [
          { clients: 64, throughput, p99Ms: 10, ok: 1000, failed: 0, ...extra },
        ],
      })),
      head: head.map((throughput, index) => ({
        status: 'ok',
        key: `head-${index + 1}`,
        steps: [{ clients: 64, throughput, p99Ms: 5, ok: 1000, failed: 0 }],
      })),
    },
  },
});

test('the curve group holds E1 and E3 on both stores, head against base, each a paired suite with the curve', () => {
  assert.deepEqual(suitesOf('curve'), [
    'e1-pg-curve',
    'e1-mongo-curve',
    'e3-pg-curve',
    'e3-mongo-curve',
  ]);
  for (const name of suitesOf('curve')) {
    assert.equal(SUITES[name].kind, 'paired');
    assert.equal(SUITES[name].curve, CURVE);
    assert.deepEqual(
      SUITES[name].arms.map(arm => arm.id),
      ['base', 'head'],
    );
    assert.ok(usesBase(name));
  }
  assert.equal(SUITES['e1-mongo-curve'].scenario, 'E1');
  assert.equal(SUITES['e3-pg-curve'].arms[0].backend, 'postgres');
});

test("a sweep's arguments name the curve's steps, its timing and where it writes", () => {
  assert.deepEqual(
    curveArgs(['--scenario=E3'], CURVE, 'x-curve-head-1', '/o'),
    [
      'saturate',
      '--scenario=E3',
      '--concurrencies=16,32,64,128,256,512',
      '--warm-up=10',
      '--step=30',
      '--fail-after=10',
      '--label=x-curve-head-1',
      '--out=/o',
    ],
  );
});

test("a step's ratio is the medians', and an arm is higher only when the range clears the A/A band", () => {
  const [higher] = curveRows(swept([100, 101, 102, 103], [120, 121, 122, 123]));
  assert.equal(higher.ratio, 121.5 / 101.5);
  assert.ok(Math.abs(higher.range.lo - 120 / 103) < 1e-12);
  assert.ok(Math.abs(higher.range.hi - 123 / 100) < 1e-12);
  assert.equal(higher.verdict, 'head higher');
  assert.equal(higher.p99Ratio, 0.5);
  const [lower] = curveRows(swept([120, 121, 122, 123], [100, 101, 102, 103]));
  assert.equal(lower.verdict, 'base higher');
  // Overlapping sweeps: the range straddles 1 within the band.
  const [level] = curveRows(swept([100, 110, 105, 108], [104, 106, 109, 101]));
  assert.equal(level.verdict, 'level (within the A/A band)');
  const [one] = curveRows(swept([100], [120, 121]));
  assert.equal(
    one.verdict,
    'undetermined (fewer than two clean sweeps an arm)',
  );
  assert.equal(one.band, undefined);
});

test('a step that failed more than 1% is left out of its arm and counted; a generator-bound step is flagged', () => {
  const suite = swept([100, 101, 102], [120, 121, 122]);
  suite.curve.sweeps.base[0].steps[0].failed = 20;
  suite.curve.sweeps.head[1].generatorCpus = 2;
  suite.curve.sweeps.head[1].steps[0].generatorCores = 1.9;
  const [row] = curveRows(suite);
  assert.deepEqual(row.base, [101, 102]);
  assert.equal(row.failed, 1);
  assert.equal(row.generatorCores, 1.9);
  assert.equal(row.generatorCpus, 2);
  assert.deepEqual(
    curveAnomalies(suite).map(found => [found.run, found.kind]),
    [
      ['curve-base-1', 'error spike'],
      ['curve-head-2', 'load generator saturated'],
    ],
  );
});

test('a step any sweep of either arm ran generator-bound is invalid: no ratio, and its cores out of the CPUs', () => {
  const suite = swept([100, 101, 102], [120, 121, 122]);
  suite.curve.sweeps.base[2].generatorCpus = 4;
  suite.curve.sweeps.base[2].steps[0].generatorCores = 3.7;
  const [row] = curveRows(suite);
  assert.equal(row.verdict, GENERATOR_BOUND);
  assert.equal(row.ratio, undefined);
  assert.equal(row.range, undefined);
  assert.equal(row.p99Ratio, undefined);
  assert.match(
    curveTable(suite)[2],
    /^\| 64 \| .* \| - \| - \| .* \| invalid: generator-bound \| - \| 0 \| 3\.70 of 4 \|$/,
  );
  // Below 90% of its CPUs the generator is not what the step measured.
  suite.curve.sweeps.base[2].steps[0].generatorCores = 3.5;
  assert.equal(curveRows(suite)[0].verdict, 'head higher');
});

test('an arm that fails a step the other holds is named, with the sweeps it failed in', () => {
  const failing = swept([100, 101, 102, 103], [120, 121, 122, 123]);
  for (const sweep of failing.curve.sweeps.head.slice(0, 3))
    sweep.steps[0].failed = 50;
  assert.equal(
    curveRows(failing)[0].verdict,
    'head failed the step in 3 of 4 sweeps',
  );
  // A sweep that never reached the step counts as failing it.
  const short = swept([100, 101, 102], [120, 121, 122]);
  short.curve.sweeps.base[0].steps = [];
  short.curve.sweeps.base[1] = { status: 'failed', key: 'base-2', steps: [] };
  assert.equal(
    curveRows(short)[0].verdict,
    'base failed the step in 2 of 3 sweeps',
  );
  // Both short: nothing to name.
  assert.equal(
    curveRows(swept([100], [120]))[0].verdict,
    'undetermined (fewer than two clean sweeps an arm)',
  );
});

test('a curve suite sweeps each arm in turn, ABBA, and reports each step against its band', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite('e3-pg-curve', {
    benchPath: fakeCurveBench(dir),
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    dataset: '1k',
    installs: { base: '/b' },
  });
  assert.equal(suite.failures, 0);
  const labels = readFileSync(join(dir, 'calls.log'), 'utf8')
    .trim()
    .split('\n')
    .map(line => line.match(/--label=(\S+)/)[1]);
  assert.deepEqual(labels, [
    'e3-pg-curve-curve-base-1',
    'e3-pg-curve-curve-head-1',
    'e3-pg-curve-curve-head-2',
    'e3-pg-curve-curve-base-2',
    'e3-pg-curve-curve-base-3',
    'e3-pg-curve-curve-head-3',
    'e3-pg-curve-curve-head-4',
    'e3-pg-curve-curve-base-4',
  ]);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /^saturate .*--install=\/b .*--label=e3-pg-curve-curve-base-1 /m,
  );
  assert.doesNotMatch(
    calls,
    /--install=\S+ .*--label=e3-pg-curve-curve-head-1 /,
  );
  const rows = curveRows(suite);
  assert.deepEqual(
    rows.map(row => row.clients),
    CURVE.concurrencies,
  );
  for (const row of rows) assert.equal(row.verdict, 'head higher');
  assert.deepEqual(anomalies(suite), []);
  const summary = renderSuite(suite);
  assert.match(summary, /\| Clients \| base throughput \(sweeps\) \|/);
  assert.match(summary, /\| 64 \| /);
  assert.equal(curveSection([suite])[0], '## Curves (closed loop, ABBA)');
  assert.deepEqual(curveSection([{ name: 'x' }]), []);
});
