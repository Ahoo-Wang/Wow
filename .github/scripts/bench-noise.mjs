/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  EQUAL,
  NOISE_FLOOR,
  SIGNIFICANCE_METRICS,
  confidence,
  modeOf,
  refusedRound,
} from './bench-calibration.mjs';
import { labelOf } from './bench-common.mjs';

// The A/A noise of the paired suites (D224 note ②), pooled over their recent A/A runs.
//
// Each A/A suite run is one entry of test/benchmarks/noise-history.json, appended and never edited: where it ran
// (the run, the build, the runner's CPU model and the suite's CPU pinning, the date) and, per significance metric,
// its A/A interval and each equal-rate round pair's own ratio. A suite's threshold is derived from its last POOL
// entries, by the rule `verdict` applies (bench-calibration.mjs): the smallest t, at least NOISE_FLOOR, under which
// none of them would have been called a difference, i.e. the farthest any of their intervals reached from 1. A wide
// draw counts until POOL newer entries of the suite push it out; the history keeps it.
//
// test/benchmarks/noise.json is this module's output, the thresholds the workflow reads (NOISE); it is never edited
// by hand, and `--check` fails when it is not what the history gives.
//
//   node .github/scripts/bench-noise.mjs                  (writes noise.json from the history)
//   node .github/scripts/bench-noise.mjs --check          (fails when noise.json is not what the history gives)
//   node .github/scripts/bench-noise.mjs add <entries>    (appends a run's noise-history-entries.json, then writes)
//
// The rule not taken: the 95th percentile of the round pairs' |ratio − 1| over the pool. It uses every pair, but it
// is not what `verdict` compares (an interval against 1 ± t), so "within noise" would stop meaning that no recent A/A
// run of identical code would have been called this.

export const POOL = 5;
export const HISTORY = 'test/benchmarks/noise-history.json';
export const NOISE = 'test/benchmarks/noise.json';

const METRICS = SIGNIFICANCE_METRICS.map(([metric]) => metric);
const round4 = value =>
  Number.isFinite(value) ? Number(value.toFixed(4)) : undefined;

/**
 * Whether an A/A suite's noise is pooled: not when its second arm ran with changed properties (it measured the
 * change), and not on an unprimed suite, where the calibration probes measure a fresh node's start rather than the
 * suite (they warm up 10 s whatever `warm_up` says), so its rounds' rates are not the suite's.
 */
export const pooled = suite =>
  Boolean(suite.aa) && !suite.properties && suite.primed !== false;

/** The equal-rate round pairs of an A/A suite: each round's b over a, per metric, refused rounds left out. */
function pairs(suite) {
  const [a, b] = suite.arms.map(arm => arm.id);
  const runs = suite.runs.filter(
    run => modeOf(run) === EQUAL && run.status === 'ok' && !refusedRound(run),
  );
  const rounds = [...new Set(runs.map(run => run.round))].sort((x, y) => x - y);
  return rounds
    .map(round =>
      [a, b].map(arm =>
        runs.find(run => run.round === round && run.arm === arm),
      ),
    )
    .filter(([ra, rb]) => ra && rb);
}

/**
 * A run's entries: one per pooled A/A suite that measured an equal-rate pair, with where it ran and, per metric, the
 * A/A interval (lo, point, hi) and each round pair's ratio.
 */
export function noiseEntries(suites, { run, attempt } = {}) {
  return suites.filter(pooled).flatMap(suite => {
    const intervals = confidence(suite, EQUAL);
    if (!METRICS.some(metric => intervals[metric])) return [];
    const roundPairs = pairs(suite);
    const metrics = Object.fromEntries(
      METRICS.map(metric => {
        const interval = intervals[metric];
        return [
          metric,
          {
            ...(interval
              ? {
                  lo: round4(interval.lo),
                  point: round4(interval.point),
                  hi: round4(interval.hi),
                }
              : {}),
            pairs: roundPairs
              .map(([ra, rb]) => rb.metrics[metric] / ra.metrics[metric])
              .filter(Number.isFinite)
              .map(round4),
          },
        ];
      }),
    );
    return [
      {
        suite: labelOf(suite),
        ...(run ? { run: String(run) } : {}),
        ...(attempt ? { attempt: Number(attempt) } : {}),
        commit: suite.refs?.headSha?.slice(0, 10),
        cpu: suite.runner?.cpu,
        cpus: suite.cpus,
        date: suite.startedAt,
        rounds: roundPairs.length,
        metrics,
      },
    ];
  });
}

/** How far an entry's interval reached from 1 in [metric]: 0 when it spans 1; a legacy entry's threshold as it was. */
export function reach(entry, metric) {
  if (entry.legacy) return entry.thresholds?.[metric] ?? 0;
  const m = entry.metrics?.[metric];
  if (!Number.isFinite(m?.lo) || !Number.isFinite(m?.hi)) return 0;
  return Math.max(0, m.lo - 1, 1 - m.hi);
}

/** A suite's pool: its last POOL entries, newest first; a legacy entry, which has no date, is the oldest. */
export function poolOf(entries, size = POOL) {
  const key = entry => entry.date ?? '';
  return [...entries]
    .sort(
      (x, y) =>
        key(y).localeCompare(key(x)) ||
        String(y.run ?? '').localeCompare(String(x.run ?? '')),
    )
    .slice(0, size);
}

/** A suite's thresholds from its pool: per metric the farthest reach from 1, at least NOISE_FLOOR. */
export function thresholdsOf(pool) {
  return Object.fromEntries(
    METRICS.map(metric => [
      metric,
      round4(Math.max(NOISE_FLOOR, ...pool.map(entry => reach(entry, metric)))),
    ]),
  );
}

/**
 * noise.json from the history: per suite its thresholds, and what its pool holds (how many entries, the CPU models
 * they ran on, whether a legacy entry is among them), so the report can say which models a threshold mixes.
 */
export function noiseFromHistory(history) {
  const suites = [...new Set(history.map(entry => entry.suite))].sort();
  return Object.fromEntries(
    suites.map(suite => {
      const pool = poolOf(history.filter(entry => entry.suite === suite));
      const cpus = [
        ...new Set(pool.filter(e => !e.legacy).map(e => e.cpu)),
      ].sort();
      return [
        suite,
        {
          ...thresholdsOf(pool),
          pool: {
            entries: pool.length,
            cpus,
            ...(pool.some(e => e.legacy) ? { legacy: true } : {}),
          },
        },
      ];
    }),
  );
}

/**
 * The thresholds a run judges its paired suites against: per suite the committed pool's, or, where the run carries
 * its own A/A of that suite, the wider of the pool's and its own draw's (as #6395 chose: one run's draw can widen
 * what this run calls noise, never narrow it).
 */
export function judgedAgainst(committed, entries) {
  const next = { ...committed };
  for (const entry of entries) {
    const own = thresholdsOf([entry]);
    const pool = committed[entry.suite] ?? {};
    next[entry.suite] = {
      ...pool,
      ...Object.fromEntries(
        METRICS.map(metric => [
          metric,
          Math.max(pool[metric] ?? 0, own[metric]),
        ]),
      ),
    };
  }
  return next;
}

/** History entries sorted by suite, then date, then run, so the file diffs line by line. */
export function sortedHistory(history) {
  return [...history].sort(
    (x, y) =>
      x.suite.localeCompare(y.suite) ||
      (x.date ?? '').localeCompare(y.date ?? '') ||
      String(x.run ?? '').localeCompare(String(y.run ?? '')),
  );
}

/** The history with [entries] added: an entry already there (its suite, run and attempt) is not added twice. */
export function added(history, entries) {
  const key = e =>
    `${e.suite}|${e.run ?? ''}|${e.attempt ?? ''}|${e.legacy ? 'legacy' : ''}`;
  const known = new Set(history.map(key));
  return sortedHistory([
    ...history,
    ...entries.filter(e => !known.has(key(e))),
  ]);
}

const json = value => `${JSON.stringify(value, null, 2)}\n`;
const read = path =>
  existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : [];

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const root = process.cwd();
  const historyPath = join(root, HISTORY);
  const noisePath = join(root, NOISE);
  const [command, argument] = process.argv.slice(2);
  let history = read(historyPath);
  if (command === 'add') {
    history = added(history, read(argument));
    writeFileSync(historyPath, json(history));
  }
  const expected = json(noiseFromHistory(history));
  if (command === '--check') {
    const actual = existsSync(noisePath) ? readFileSync(noisePath, 'utf8') : '';
    if (actual !== expected) {
      console.error(
        `${NOISE} is not what ${HISTORY} gives; run node .github/scripts/bench-noise.mjs`,
      );
      process.exitCode = 1;
    } else console.log(`${NOISE} is what ${HISTORY} gives`);
  } else {
    writeFileSync(noisePath, expected);
    console.log(`Wrote ${NOISE} from ${history.length} entries`);
  }
}
