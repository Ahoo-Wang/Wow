/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  confidence,
  heldInFlight,
  IN_FLIGHT_PER_TARGET,
  keptUp,
  PROBE,
  probeHeld,
  refusedRound,
  SATURATION,
  sharesText,
  significanceSection,
} from './bench-calibration.mjs';

test('both loops share one ceiling: the search stops where the open loop caps its operations in flight', () => {
  assert.equal(SATURATION.maxConcurrency, IN_FLIGHT_PER_TARGET);
  const openLoop = readFileSync(
    new URL(
      '../../test/benchmarks/load/src/main/kotlin/me/ahoo/wow/benchmark/load/OpenLoop.kt',
      import.meta.url,
    ),
    'utf8',
  );
  const perTarget = Number(
    openLoop.match(/const val PER_TARGET: Int = ([\d_]+)/)[1].replace(/_/g, ''),
  );
  assert.equal(perTarget, IN_FLIGHT_PER_TARGET);
  // A probe measures as long as a round warms up, so a queue a round builds in that time shows in the probe.
  assert.ok(PROBE.measure >= 30);
});

test("a probe holds when it kept up and its rate × p99 needed at most half the cap (Little's law)", () => {
  const kept = { ok: 25_000, failed: 0, throughput: 2_585, inFlightCap: 2048 };
  // 2,585/s × 0.39 s = 1,008 in flight, under half of 2,048.
  assert.equal(heldInFlight({ ...kept, p99: 390 }, 2_585), true);
  assert.equal(probeHeld({ ...kept, p99: 390 }, 2_585), true);
  // At 0.5 s it needs 1,293: the round would reach the cap as its latency grows.
  assert.equal(heldInFlight({ ...kept, p99: 500 }, 2_585), false);
  assert.equal(probeHeld({ ...kept, p99: 500 }, 2_585), false);
  // A run that recorded no cap or no p99 is not held back by it; one that fell behind is never held.
  assert.equal(heldInFlight({ p99: 900 }, 2_585), true);
  assert.equal(
    probeHeld({ ...kept, throughput: 2_000, p99: 100 }, 2_585),
    false,
  );
});

const run = (arm, mode, round, metrics) => ({
  name: `${mode}-${arm}-${round}`,
  arm,
  mode,
  round,
  status: 'ok',
  metrics: { ok: 1000, failed: 0, errors: {}, ...metrics },
});
const seconds = (value, n = 10) => Array.from({ length: n }, () => value);

test('a round the generator refused is left out of the intervals and of what an arm kept up', () => {
  const refused = run('head', 'equal', 1, {
    failed: 50,
    errors: { 'E1:generator-overload': 50 },
    p99Series: seconds(900),
    okSeries: seconds(50),
    throughput: 50,
  });
  assert.equal(refusedRound(refused), true);
  assert.equal(
    refusedRound(
      run('head', 'equal', 1, {
        failed: 5,
        errors: { 'E1:generator-overload': 5 },
      }),
    ),
    false,
  );
  const suite = {
    arms: [{ id: 'base' }, { id: 'head' }],
    rates: { base: 100, head: 100 },
    runs: [
      run('base', 'equal', 0, {
        p99Series: seconds(100),
        okSeries: seconds(100),
      }),
      run('head', 'equal', 0, {
        p99Series: seconds(100),
        okSeries: seconds(100),
      }),
      refused,
    ],
  };
  const intervals = confidence(suite, 'equal');
  assert.equal(intervals.p99.lo, 1);
  assert.equal(intervals.p99.hi, 1);
  assert.equal(intervals.throughput.point, 1);
});

test('each arm is judged against its own calibrated rate, and different shares are said in words', () => {
  const suite = {
    name: 'e1-pg-uniform',
    dataset: '100k',
    arms: [{ id: 'base' }, { id: 'head' }],
    rates: { base: 1_498, head: 1_165 },
    calibration: {
      base: { sustainable: true, share: 0.8 },
      head: { sustainable: true, share: 0.6 },
    },
    runs: [
      run('base', 'calibrated', 0, { throughput: 1_490 }),
      run('base', 'calibrated', 1, { throughput: 1_400 }),
      run('head', 'calibrated', 0, { throughput: 1_165 }),
      run('head', 'calibrated', 1, { throughput: 1_160 }),
    ],
  };
  const base = keptUp(suite, 'base');
  assert.equal(base.held, false);
  assert.equal(Math.round(base.completed * 1000), 965);
  assert.equal(keptUp(suite, 'head').held, true);
  assert.equal(keptUp(suite, 'head').share, 0.6);
  assert.equal(keptUp({ ...suite, runs: [] }, 'head'), undefined);
  assert.equal(
    sharesText(suite),
    'e1-pg-uniform@100k: the arms ran at different shares of their saturations (base at 80%, head at 60%), so their calibrated rounds compare different loads; the equal-rate rounds compare like with like.',
  );
  assert.equal(
    sharesText({
      ...suite,
      calibration: {
        base: { sustainable: true, share: 0.8 },
        head: { sustainable: false, share: 0.78 },
      },
    }),
    undefined,
  );
});

test('the significance section reads throughput from the saturations and counts only latencies beyond noise', () => {
  const suite = {
    name: 'e1-pg-uniform',
    dataset: '100k',
    kind: 'paired',
    arms: [{ id: 'base' }, { id: 'head' }],
    rates: { base: 1_498, head: 1_165 },
    saturation: {
      base: { status: 'ok', throughput: 1_872 },
      head: { status: 'ok', throughput: 1_942 },
    },
    calibration: {
      base: { sustainable: true, share: 0.8 },
      head: { sustainable: true, share: 0.6 },
    },
    runs: [
      run('base', 'calibrated', 0, { throughput: 1_498 }),
      run('head', 'calibrated', 0, { throughput: 1_165 }),
      run('base', 'equal', 0, {
        p50Series: seconds(20),
        p99Series: seconds(200),
        okSeries: seconds(1_165),
      }),
      run('head', 'equal', 0, {
        p50Series: seconds(20),
        p99Series: seconds(200),
        okSeries: seconds(1_165),
      }),
    ],
  };
  const lines = significanceSection([suite], {}, s => `${s.name}@${s.dataset}`);
  const text = lines.join('\n');
  assert.match(text, /^## Significance/);
  assert.match(
    text,
    /Beyond noise: 0 of 2 latency ratios; 0 of 1 suites have an A\/A noise threshold/,
  );
  assert.match(
    text,
    /\| e1-pg-uniform@100k \| head\/base \| saturation \| closed loop \| 1\.037 \| - \| one search an arm, no interval \|/,
  );
  assert.match(
    text,
    /\| e1-pg-uniform@100k \| head, base \| kept up \| calibrated \| head 100% of 1165\/s \(60% of saturation\) · base 100% of 1498\/s \(80% of saturation\) \| - \| both kept up \|/,
  );
  assert.match(
    text,
    /\| e1-pg-uniform@100k \| head\/base \| p99 \| equal \| 1\.000 \[1\.000–1\.000\] \| - \| uncalibrated \|/,
  );
  assert.doesNotMatch(text, /\| throughput \|/);
  assert.match(text, /- e1-pg-uniform@100k: the arms ran at different shares/);
  // A capped search is a lower bound, not a ratio to read as one.
  const capped = significanceSection(
    [
      {
        ...suite,
        saturation: {
          ...suite.saturation,
          head: { status: 'ok', throughput: 3_000, capped: true },
        },
      },
    ],
    {},
    s => s.name,
  ).join('\n');
  assert.match(
    capped,
    /\| ~1\.603 \| - \| capped: an arm reached the ceiling of 2048 clients, a lower bound \|/,
  );
  assert.deepEqual(
    significanceSection([{ ...suite, aa: true }], {}, s => s.name),
    [],
  );
});
