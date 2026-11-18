/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  HISTORY,
  NOISE,
  POOL,
  added,
  judgedAgainst,
  noiseFromHistory,
  poolOf,
  pooled,
  reach,
  thresholdsOf,
} from './bench-noise.mjs';

const ROOT = join(import.meta.dirname, '../..');

/** An entry of suite e3-pg@100k on [date] whose p50 interval is [lo, hi]. */
const entry = (date, lo, hi, extra = {}) => ({
  suite: 'e3-pg@100k',
  run: date.replaceAll('-', ''),
  attempt: 1,
  commit: 'e3a4e937aa',
  cpu: 'AMD EPYC 7763 64-Core Processor',
  cpus: { load: '3', bus: '2', store: '1', server: '0' },
  date: `${date}T00:00:00Z`,
  rounds: 4,
  metrics: {
    throughput: { lo: 0.99, point: 1, hi: 1.01, pairs: [1, 1, 1, 1] },
    p50: { lo, point: (lo + hi) / 2, hi, pairs: [1, 1, 1, 1] },
    p99: { lo: 0.9, point: 1, hi: 1.1, pairs: [1, 1, 1, 1] },
  },
  ...extra,
});

test('an entry reaches as far from 1 as its interval goes beyond it, and no farther than 0 when it spans 1', () => {
  assert.equal(reach(entry('2026-11-01', 0.8, 1.2), 'p50'), 0);
  assert.ok(Math.abs(reach(entry('2026-11-01', 1.3, 1.5), 'p50') - 0.3) < 1e-9);
  assert.ok(
    Math.abs(reach(entry('2026-11-01', 0.5, 0.75), 'p50') - 0.25) < 1e-9,
  );
  assert.equal(reach({ legacy: true, thresholds: { p50: 0.46 } }, 'p50'), 0.46);
});

test("a suite's threshold is the farthest reach of its last POOL entries, at least 2%: a wide draw ages out", () => {
  const wide = entry('2026-10-01', 1.3, 1.5);
  const narrow = ['2026-11-01', '2026-11-02', '2026-11-03', '2026-11-04'].map(
    d => entry(d, 0.9, 1.1),
  );
  assert.equal(POOL, 5);
  assert.deepEqual(thresholdsOf(poolOf([wide, ...narrow])), {
    throughput: 0.02,
    p50: 0.3,
    p99: 0.02,
  });
  // A fifth newer entry pushes the wide one out of the pool; the history keeps it.
  const pool = poolOf([wide, ...narrow, entry('2026-11-05', 0.95, 1.05)]);
  assert.equal(pool.length, POOL);
  assert.ok(!pool.includes(wide));
  assert.deepEqual(thresholdsOf(pool), {
    throughput: 0.02,
    p50: 0.02,
    p99: 0.02,
  });
});

test('a legacy threshold, which has no date, is the oldest of its pool and counts until POOL newer entries', () => {
  const legacy = {
    suite: 'e3-pg@100k',
    legacy: true,
    thresholds: { throughput: 0.02, p50: 0.37, p99: 0.4 },
  };
  const one = entry('2026-11-01', 0.9, 1.1);
  assert.deepEqual(thresholdsOf(poolOf([legacy, one])).p50, 0.37);
  const five = ['01', '02', '03', '04', '05'].map(d =>
    entry(`2026-11-${d}`, 0.9, 1.1),
  );
  assert.ok(!poolOf([legacy, ...five]).includes(legacy));
});

test("noise.json holds each suite's thresholds and what its pool mixes", () => {
  const noise = noiseFromHistory([
    entry('2026-11-01', 0.9, 1.1),
    entry('2026-11-02', 1.1, 1.2, { cpu: 'Intel(R) Xeon(R) 6975P-C' }),
  ]);
  assert.deepEqual(noise['e3-pg@100k'], {
    throughput: 0.02,
    p50: 0.1,
    p99: 0.02,
    pool: {
      entries: 2,
      cpus: ['AMD EPYC 7763 64-Core Processor', 'Intel(R) Xeon(R) 6975P-C'],
    },
  });
});

test('a run is judged against the wider of the pool and its own A/A draw, never a narrower one', () => {
  const committed = {
    'e3-pg@100k': {
      throughput: 0.02,
      p50: 0.3,
      p99: 0.02,
      pool: { entries: 5 },
    },
  };
  const judged = judgedAgainst(committed, [entry('2026-11-20', 0.5, 0.9)]);
  assert.deepEqual(judged['e3-pg@100k'], {
    throughput: 0.02,
    p50: 0.3,
    p99: 0.02,
    pool: { entries: 5 },
  });
  const wider = judgedAgainst(committed, [entry('2026-11-20', 1.4, 1.6)]);
  assert.ok(Math.abs(wider['e3-pg@100k'].p50 - 0.4) < 1e-9);
  // A suite the pool lacks takes its own draw.
  assert.deepEqual(
    Object.keys(judgedAgainst({}, [entry('2026-11-20', 0.9, 1.1)])),
    ['e3-pg@100k'],
  );
});

test('only the A/A of a primed suite with unchanged arms is pooled', () => {
  assert.equal(pooled({ aa: true, primed: true }), true);
  assert.equal(
    pooled({ aa: true, primed: true, properties: { 'wow.x': 1 } }),
    false,
    'it measured the change',
  );
  assert.equal(
    pooled({ aa: true, primed: false }),
    false,
    'its probes measured a fresh node',
  );
  assert.equal(pooled({ aa: false, primed: true }), false);
});

test('a run already in the history is not added twice, and the history is sorted by suite and date', () => {
  const a = entry('2026-11-02', 0.9, 1.1);
  const b = { ...entry('2026-11-01', 0.9, 1.1), suite: 'e1-pg-uniform@100k' };
  const history = added([a], [b, a]);
  assert.deepEqual(
    history.map(e => `${e.suite} ${e.date}`),
    [
      'e1-pg-uniform@100k 2026-11-01T00:00:00Z',
      'e3-pg@100k 2026-11-02T00:00:00Z',
    ],
  );
});

test('the committed noise.json is what the committed history gives', () => {
  const history = JSON.parse(readFileSync(join(ROOT, HISTORY), 'utf8'));
  const noise = readFileSync(join(ROOT, NOISE), 'utf8');
  assert.equal(
    noise,
    `${JSON.stringify(noiseFromHistory(history), null, 2)}\n`,
    'run node .github/scripts/bench-noise.mjs',
  );
  // The 1k suites are not calibrated until the probes stop measuring a fresh node's start.
  assert.ok(
    Object.keys(JSON.parse(noise)).every(suite => suite.endsWith('@100k')),
  );
});
