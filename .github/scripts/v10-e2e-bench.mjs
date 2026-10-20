/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { spawnSync } from 'node:child_process';
import {
  appendFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

// The steps of v10-e2e-bench.yml, the timed end-to-end benchmark of decision
// D224 (wow-benchmarks/e2e/README.md) on GitHub's runners:
//
//   node .github/scripts/v10-e2e-bench.mjs plan            (plan job: the settings)
//   node .github/scripts/v10-e2e-bench.mjs matrix          (plan job: the suites and builds that can run)
//   node .github/scripts/v10-e2e-bench.mjs suite <name>    (one matrix job per suite)
//   node .github/scripts/v10-e2e-bench.mjs report <dir>    (report job)
//
// `plan` validates the dispatch inputs and lists the suites to run. `suite`
// drives wow-benchmarks/e2e/harness/bench.sh on one runner: it finds each
// arm's saturation point (closed loop, short steps), then runs the arms
// alternately, open loop at `fraction` of their saturation, so that every
// comparison is a pair measured on the same machine minutes apart; the
// relative result survives the variance between runners, the absolute numbers
// do not. Rounds alternate which arm goes first (ABBA), so drift over the job
// cancels too. When two runs of one arm differ by more than DRIFT in
// throughput or p99, one more round runs. bench.sh starts every run on fresh
// containers and servers. `report` combines the suites' results.

// The arms: a build (`install`: `head`, the pushed or dispatched V10 ref; `base`, the V10 baseline ref; `9x`, the
// 9.x server from `nine_x_ref`) on a backend, and the server properties it runs with (handed to the servers as
// SPRING_APPLICATION_JSON, which bench.sh's servers inherit). Every V10 run also turns on the benchmark server's
// BenchSignals (SIGNALS), which count the node's overload and fallback signals for the anomaly checks.
const NINE_X = { id: '9x', version: '9x', backend: 'mongo', install: '9x' };
const v10 = (install, backend) => ({
  id: install,
  version: 'v10',
  backend,
  install,
});
const HEAD_MONGO = v10('head', 'mongo');
const BASE_MONGO = v10('base', 'mongo');
const HEAD_PG = v10('head', 'postgres');
const BASE_PG = v10('base', 'postgres');
const withProps = (arm, id, props) => ({ ...arm, id, props });
export const SIGNALS = { 'bench.signals.enabled': true };

// The mixed suites' operations: writes while point reads and lists run (the query bulkheads, the write priority).
const MIX = 'E1:50,E4-state:40,E4-list:10';

// The data set sizes: how many accounts a run seeds and draws its keys from. 1k fits every cache (hot); 100k, the
// default from L2 up, does not; 1M is cold. Seeding opens the accounts over HTTP, more at once for the larger sets.
export const DATASETS = {
  '1k': { accounts: 1000, seedConcurrency: 32 },
  '100k': { accounts: 100000, seedConcurrency: 256 },
  '1M': { accounts: 1000000, seedConcurrency: 512 },
};

// Priming (D224 note ⑤): from PRIME_FROM accounts up, every run touches each seeded account once before its warm-up
// (bench.sh --prime), untimed, so the measured window is not still filling the caches; at uniform keys and a few
// hundred operations a second, a warm-up alone touches only a small share of 100k aggregates. A suite marked `cold`
// is never primed. The node keeps STATE_CACHE aggregates per context (V10's default `wow.state-cache.capacity`):
// above that, the steady state a run reaches, primed or not, is a full cache with evictions, which the report says.
export const PRIME_FROM = 100000;
export const STATE_CACHE = 4096;

// The E3 latency suspects on MongoDB (H1 to H5 of the E3 analysis): a rate
// sweep, then one key toggled at a time at the middle rate.
const DIAG_RATE = 20;

// Suite groups: `ab` compares the V10 baseline with the head (they need a baseline ref, and are skipped without one),
// `head` measures the head alone, `9x` compares the head with 9.x (opt-in as `vs-9x`; removed at GA).
//
// kind `paired`: saturation of each arm, open-loop calibration, then rounds of one run per arm; the ratio is
// arms[1] / arms[0] (head/base, head/9x). `sharedRate`: both arms run at the first arm's calibrated rate.
// kind `fixed`: the listed runs once each, at fixed rates. kind `steps`: the cliff steps (CLIFF). kind `capacity`:
// the sustainable rate of each scenario by bisection (CAPACITY). kind `cluster`: three nodes, steady and with a node
// stopped and restarted mid-measurement (CLUSTER).
export const SUITES = {
  'e1-mongo-uniform': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      'E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.',
  },
  'e1-mongo-zipf': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'zipf',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      'E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.',
  },
  'e1-pg-uniform': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [BASE_PG, HEAD_PG],
    answers: 'E1 write on PostgreSQL: head against baseline.',
  },
  'e2-mongo': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E2',
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers: 'E2 read-your-write on MongoDB: head against baseline.',
  },
  'e3-mongo': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      "E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.",
  },
  'e3-pg': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [BASE_PG, HEAD_PG],
    answers: 'E3 saga chain on PostgreSQL: head against baseline.',
  },
  'mixed-mongo': {
    group: 'ab',
    kind: 'paired',
    scenario: 'mixed',
    mix: MIX,
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      'Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.',
  },
  'mixed-pg': {
    group: 'ab',
    kind: 'paired',
    scenario: 'mixed',
    mix: MIX,
    distribution: 'uniform',
    arms: [BASE_PG, HEAD_PG],
    answers: 'Reads and writes together on PostgreSQL, as mixed-mongo.',
  },
  'sustainable-mongo': {
    group: 'head',
    kind: 'capacity',
    distribution: 'uniform',
    arm: HEAD_MONGO,
    scenarios: ['E1', 'E2', 'E3'],
    answers:
      'Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.',
  },
  'sustainable-pg': {
    group: 'head',
    kind: 'capacity',
    distribution: 'uniform',
    arm: HEAD_PG,
    scenarios: ['E1', 'E3'],
    answers:
      'Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.',
  },
  'e5-mongo': {
    group: 'head',
    kind: 'cluster',
    distribution: 'uniform',
    arm: HEAD_MONGO,
    answers:
      'E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).',
  },
  'e5-pg': {
    group: 'head',
    kind: 'cluster',
    distribution: 'uniform',
    arm: HEAD_PG,
    answers: 'E5 cluster of three nodes on PostgreSQL, as e5-mongo.',
  },
  'e3-mongo-diag': {
    group: 'head',
    kind: 'fixed',
    scenario: 'E3',
    distribution: 'uniform',
    runs: [
      { arm: HEAD_MONGO, rate: 5 },
      { arm: HEAD_MONGO, rate: DIAG_RATE },
      { arm: HEAD_MONGO, rate: 40 },
      {
        arm: withProps(HEAD_MONGO, 'head-queue-off', {
          'wow.log.append-queue.enabled': false,
        }),
        rate: DIAG_RATE,
      },
      {
        arm: withProps(HEAD_MONGO, 'head-gap-wait-20ms', {
          'wow.log.handoff.gap-wait': '20ms',
        }),
        rate: DIAG_RATE,
      },
      {
        arm: withProps(HEAD_MONGO, 'head-max-poll-5s', {
          'wow.log.max-poll-interval': '5s',
        }),
        rate: DIAG_RATE,
      },
    ],
    answers:
      'E3 latency on MongoDB: a rate sweep (a median falling at 5/s means load, a flat one a fixed delay), then the append queue off, a 20 ms handoff gap wait and a 5 s longest poll, one at a time at 20/s.',
  },
  'pg-append-queue': {
    group: 'head',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    sharedRate: true,
    arms: [
      withProps(HEAD_PG, 'queue-off', {
        'wow.log.append-queue.enabled': false,
      }),
      withProps(HEAD_PG, 'queue-on', { 'wow.log.append-queue.enabled': true }),
    ],
    answers:
      "Whether PostgreSQL's append queue should be on by default (D248): saturation with it off and on, then both at the same offered load.",
  },
  'cliff-e3-pg': cliff(
    HEAD_PG,
    'E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).',
  ),
  'cliff-e3-mongo': cliff(
    HEAD_MONGO,
    'E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.',
  ),
  'vs-9x-e1': {
    group: '9x',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [NINE_X, HEAD_MONGO],
    answers: 'E1 write on MongoDB: the head against 9.x (D224; removed at GA).',
  },
  'vs-9x-e3': {
    group: '9x',
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [NINE_X, HEAD_MONGO],
    answers: 'E3 saga chain on MongoDB: the head against 9.x.',
  },
  'vs-9x-cliff-e3': {
    ...cliff(
      NINE_X,
      'E3 on 9.x in the cliff steps, the reference for cliff-e3-mongo (9.x has no signals).',
    ),
    group: '9x',
  },
};

/** The suites of a group. */
export const suitesOf = group =>
  Object.keys(SUITES).filter(name => SUITES[name].group === group);

// The tiers of D224's benchmark ladder that this workflow runs: run the smallest one that answers the question. L2, an
// end-to-end point (one scenario on one backend, head against baseline), is the default and never the whole grid;
// L3 is the end-to-end grid (before an rc or after a major merge); L4 the special runs: sustainable rates, overload
// cliffs and the three-node cluster. L0 (JMH) is outside this workflow. `suites` given by name replaces the tier's list.
// The data sets each tier runs its suites at (the `dataset` setting replaces them).
export const TIER_DATASETS = { L2: ['100k'], L3: ['1k', '100k'], L4: ['100k'] };

export const TIERS = {
  L2: ['e1-mongo-uniform', 'e1-pg-uniform', 'e3-mongo', 'e3-pg'],
  L3: suitesOf('ab'),
  L4: [
    'sustainable-mongo',
    'sustainable-pg',
    'cliff-e3-mongo',
    'cliff-e3-pg',
    'e5-mongo',
    'e5-pg',
  ],
};

// kind `steps` (the cliff suites): one arm in open loop at rising fixed rates, each step on fresh containers, a
// minute measured, until two steps in a row are not sustained.
export const CLIFF = {
  rates: [50, 100, 200, 300, 400, 500],
  warmUp: 10,
  measure: 60,
};

function cliff(arm, answers) {
  return {
    group: 'head',
    kind: 'steps',
    scenario: 'E3',
    distribution: 'uniform',
    arm,
    answers,
  };
}

// kind `capacity`: per scenario, the closed-loop saturation bounds a bisection over the open-loop rate; a probe holds
// when at most 1% fails, at least 97% of the rate completes and p99 is within `p99_limit_ms`. The bisection stops
// when the bracket is within 5% of its top or after `probes` probes; the capacity is the highest rate that held.
export const CAPACITY = { warmUp: 10, measure: 30, probes: 6, tolerance: 0.05 };

// kind `cluster`: three nodes (bench.sh --nodes=3), requests round-robin, each scenario at a fixed rate a minute and
// a half, once steady and once with node 2 stopped (SIGTERM, its partitions hand over) `stopAfter` seconds into the
// measured phase and restarted `down` seconds later with its own command line.
export const CLUSTER = {
  nodes: 3,
  warmUp: 15,
  measure: 90,
  stopAfter: 25,
  down: 20,
  nodePort: 18081,
  scenarios: [
    { scenario: 'E1', rate: 150 },
    { scenario: 'E3', rate: 50 },
  ],
};

/** The last `bench-signals {json}` line of a server log: the node's cumulative signal counts, or undefined. */
export function lastSignals(log) {
  const lines = log
    .split('\n')
    .filter(line => line.includes('bench-signals {'));
  const last = lines.at(-1);
  if (!last) return undefined;
  try {
    return JSON.parse(
      last.slice(last.indexOf('bench-signals {') + 'bench-signals '.length),
    );
  } catch {
    return undefined;
  }
}

/** The signals of several nodes' logs, summed per kind and key (a cluster's nodes, a restarted node's log). */
export function mergeSignals(all) {
  const present = all.filter(Boolean);
  if (present.length === 0) return undefined;
  const merged = { t: Math.max(...present.map(signals => signals.t ?? 0)) };
  for (const signals of present) {
    for (const [kind, counts] of Object.entries(signals)) {
      if (kind === 't') continue;
      merged[kind] ??= {};
      for (const [key, count] of Object.entries(counts)) {
        merged[kind][key] = (merged[kind][key] ?? 0) + count;
      }
    }
  }
  return merged;
}

// The anomaly checks of a run, so a run surfaces defects and not only numbers. `overload` marks a run that went past
// what the node keeps up with by design (a calibration or capacity probe, a cliff step that did not keep up), where
// errors are expected; signals are flagged there too, since a pause or a store fallback is worth a look either way.
export const ANOMALY = {
  errorPercent: 1,
  tailToMedian: 10,
  // Unstable: the halves of the measured phase differ by more than the suite's A/A noise (noise.json) in throughput,
  // p50 or, with enough tail samples, p99; these defaults stand in for a suite without a threshold.
  stability: { throughput: 0.05, p50: 0.25, p99: 0.5 },
  // The load generator used this share of the CPUs it had: the run measured the generator, not the servers.
  generatorBusy: 0.9,
  // p99 > 10× p50 is flagged only above this p99, so a 2 ms median with a 30 ms p99 does not count.
  minTailMs: 50,
};

/** The anomalies of one run: `{ kind, detail }` each; `noise` is its suite's A/A thresholds, when there are any. */
export function anomaliesOf(run, { overload = false, noise } = {}) {
  const found = [];
  const s = run.signals ?? {};
  const sum = counts => Object.values(counts ?? {}).reduce((a, b) => a + b, 0);
  if (sum(s.partitionPaused) > 0) {
    found.push({
      kind: 'partition paused',
      detail: Object.entries(s.partitionPaused)
        .map(([key, count]) => `${key} ×${count}`)
        .join(', '),
    });
  }
  const fallback = ['gap', 'overflow']
    .filter(reason => s.tailRead?.[reason] > 0)
    .map(reason => `${reason}=${s.tailRead[reason]}`);
  if (fallback.length > 0) {
    found.push({ kind: 'tail read the store', detail: fallback.join(', ') });
  }
  if (run.status !== 'ok') {
    found.push({
      kind: 'run failed',
      detail: 'bench.sh failed; see the job log',
    });
    return found;
  }
  if (overload) return found;
  const m = run.metrics;
  if (
    m.loadGeneratorCpus > 0 &&
    m.loadGeneratorCores >= ANOMALY.generatorBusy * m.loadGeneratorCpus
  ) {
    found.push({
      kind: 'load generator saturated',
      detail: `${num(m.loadGeneratorCores, 2)} of ${m.loadGeneratorCpus} cores: the run measured the generator`,
    });
  }
  const st = m.stability;
  if (st) {
    const limit = metric => noise?.[metric] ?? ANOMALY.stability[metric];
    const drifted = [
      ['throughput', st.throughputChange],
      ['p50', Number.isFinite(st.p50Ratio) ? st.p50Ratio - 1 : undefined],
      ['p99', Number.isFinite(st.p99Ratio) ? st.p99Ratio - 1 : undefined],
    ].filter(
      ([metric, change]) =>
        Number.isFinite(change) && Math.abs(change) > limit(metric),
    );
    if (drifted.length > 0) {
      found.push({
        kind: 'unstable',
        detail: `second half against first, beyond ${noise ? 'the A/A noise' : 'the default limits'}: ${drifted
          .map(
            ([metric, change]) =>
              `${metric} ${change >= 0 ? '+' : ''}${num(change * 100)}% (±${num(limit(metric) * 100)}%)`,
          )
          .join(', ')}`,
      });
    }
  }
  const total = m.ok + m.failed;
  if (total > 0 && (m.failed * 100) / total > ANOMALY.errorPercent) {
    found.push({
      kind: 'error spike',
      detail: `${m.failed} of ${total} failed: ${errorsText(m.errors)}`,
    });
  }
  if (m.weakSeconds > 0) {
    found.push({
      kind: 'throughput dip',
      detail: `${m.weakSeconds} s below half the rate (lowest ${m.minSecondOk}/s)`,
    });
  }
  if (
    m.p99 >= ANOMALY.minTailMs &&
    m.p50 > 0 &&
    m.p99 > ANOMALY.tailToMedian * m.p50
  ) {
    found.push({
      kind: 'p99 > 10× p50',
      detail: `p50 ${ms(m.p50)}, p99 ${ms(m.p99)}`,
    });
  }
  return found;
}

/** The anomalies of a suite: every run's and probe's, with where they were found; `noise` from noise.json. */
export function anomalies(suite, noise = {}) {
  const thresholds = noise[labelOf(suite)];
  const rows = [];
  for (const run of suite.runs ?? []) {
    const overload = suite.kind === 'steps' && run.sustained === false;
    const context = overload
      ? 'overload (cliff step)'
      : run.handover
        ? 'hand-over'
        : 'steady';
    for (const found of anomaliesOf(run, { overload, noise: thresholds })) {
      rows.push({ suite: labelOf(suite), run: run.name, context, ...found });
    }
  }
  for (const probe of suite.probes ?? []) {
    for (const found of anomaliesOf(probe, { overload: true })) {
      rows.push({
        suite: labelOf(suite),
        run: probe.name,
        context: 'overload (probe)',
        ...found,
      });
    }
  }
  return rows;
}

export const DRIFT = 0.05;
// The closed loop reaches its best throughput only with deep queues, so `fraction` of it can be more than a minute
// of open loop sustains (the first runs on GitHub's 4-vCPU runners collapsed into timeouts at 80%). Each arm's
// rate is therefore calibrated in open loop first: a short probe at the rate, and while it is not sustainable
// (more than 1% failed, or less than 97% of the rate completed) the rate steps down by a quarter, at most 4 probes.
export const PROBE = { warmUp: 10, measure: 20, attempts: 4, step: 0.75 };

/** Whether an open-loop run at `rate` kept up: at most 1% failed and at least 97% of the rate completed. */
export function sustainable(m, rate) {
  const total = (m?.ok ?? 0) + (m?.failed ?? 0);
  return total > 0 && m.failed / total <= 0.01 && m.throughput >= 0.97 * rate;
}

// bench.sh's default cap of 256 workers stopped the closed loop before V10 flattened, and so did 1024 on GitHub's
// runners (E1, E2 and E3), so the cap is 4096; the doubling stops on its own after two steps that gain less than 5%.
// A saturation found at the cap is a lower bound, marked `≥` (`capped`).
export const SATURATION = { warmUp: 3, step: 8, maxConcurrency: 4096 };

const saturationText = s =>
  s?.throughput
    ? `${s.capped ? '≥ ' : ''}${num(s.throughput)}/s`
    : `**${s?.status ?? 'missing'}**`;

const integer = (name, value, min, max) => {
  const text = String(value ?? '').trim();
  const number = Number(text);
  if (!/^\d+$/.test(text) || number < min || number > max) {
    throw new Error(
      `${name} must be an integer from ${min} to ${max}: ${value}`,
    );
  }
  return number;
};

// The run's settings when a push to `v10-bench-run/**` starts it: the defaults, over which
// wow-benchmarks/e2e/bench-run.json at the pushed commit may set any of these keys.
export const DEFAULTS = {
  tier: 'L2',
  suites: '',
  dataset: '',
  aa: 'false',
  warm_up: '30',
  measure: '40',
  repeats: '2',
  fraction: '0.8',
  p99_limit_ms: '200',
  base_ref: '',
  nine_x_ref: 'v10-9x-baseline',
};

/** The settings of a run: the dispatch inputs, or for a push the defaults with the run file's keys over them. */
export function runSettings({ event, inputs = {}, config }) {
  if (event !== 'push') {
    const given = Object.entries(inputs).filter(
      ([, value]) => value !== undefined && value !== '',
    );
    return { ...DEFAULTS, ...Object.fromEntries(given) };
  }
  const unknown = Object.keys(config ?? {}).filter(key => !(key in DEFAULTS));
  if (unknown.length > 0) {
    throw new Error(
      `Unknown key(s) in bench-run.json: ${unknown.join(', ')}; known: ${Object.keys(DEFAULTS).join(', ')}`,
    );
  }
  const settings = { ...DEFAULTS };
  for (const [key, value] of Object.entries(config ?? {})) {
    settings[key] = Array.isArray(value) ? value.join(',') : String(value);
  }
  return settings;
}

/**
 * The settings, checked. `suites` is `all` (every suite but the 9.x ones), names separated by commas or spaces, or
 * the group names `ab`, `head` and `vs-9x` among them.
 */
export function resolveInputs({
  tier = DEFAULTS.tier,
  suites,
  dataset,
  aa = DEFAULTS.aa,
  warmUp,
  measure,
  repeats,
  fraction,
  p99LimitMs = DEFAULTS.p99_limit_ms,
}) {
  const groups = {
    all: ['ab', 'head'],
    ab: ['ab'],
    head: ['head'],
    'vs-9x': ['9x'],
  };
  if (!suites?.trim() && !TIERS[tier]) {
    throw new Error(
      `Unknown tier: ${tier}; known: ${Object.keys(TIERS).join(', ')}`,
    );
  }
  const names = [
    ...new Set(
      (suites?.trim() ? suites : TIERS[tier].join(','))
        .split(/[\s,]+/)
        .filter(Boolean)
        .flatMap(name =>
          groups[name] ? groups[name].flatMap(suitesOf) : [name],
        ),
    ),
  ];
  const unknown = names.filter(name => !(name in SUITES));
  if (unknown.length > 0 || names.length === 0) {
    throw new Error(
      `Unknown suite(s): ${unknown.join(', ') || '(none)'}; known: ${Object.keys(SUITES).join(', ')}`,
    );
  }
  const share = Number(fraction);
  if (
    !/^\d*\.?\d+$/.test(String(fraction).trim()) ||
    !(share > 0 && share <= 1)
  ) {
    throw new Error(`fraction must be in (0, 1]: ${fraction}`);
  }
  return {
    suites: names,
    warmUp: integer('warm_up', warmUp, 0, 600),
    measure: integer('measure', measure, 5, 600),
    repeats: integer('repeats', repeats, 1, 5),
    fraction: share,
    p99LimitMs: integer('p99_limit_ms', p99LimitMs, 1, 60000),
    tier: suites?.trim() ? 'custom' : tier,
    datasets: datasetsOf(dataset, suites?.trim() ? undefined : tier),
    aa: String(aa) === 'true',
  };
}

/** The data sets: `dataset` (names separated by commas or spaces), else the tier's, else 100k. */
function datasetsOf(dataset, tier) {
  const names = dataset?.trim()
    ? [...new Set(dataset.split(/[\s,]+/).filter(Boolean))]
    : (TIER_DATASETS[tier] ?? ['100k']);
  const unknown = names.filter(name => !(name in DATASETS));
  if (unknown.length > 0) {
    throw new Error(
      `Unknown data set(s): ${unknown.join(', ')}; known: ${Object.keys(DATASETS).join(', ')}`,
    );
  }
  return names;
}

/** A suite's name with its data set, as the report shows it: `e1-mongo-uniform@100k`. */
export const labelOf = suite => `${suite.name}@${suite.dataset ?? '100k'}`;

/** A job of the matrix: a suite at a data set, `e1-mongo-uniform.100k`. */
export const jobId = (suite, dataset) => `${suite}.${dataset}`;
export function parseJob(id) {
  const dot = id.lastIndexOf('.');
  return dot < 0
    ? { suite: id, dataset: '100k' }
    : { suite: id.slice(0, dot), dataset: id.slice(dot + 1) };
}

/**
 * The jobs of a run: the suites that can run (those of group `ab` need a baseline commit, the `9x` ones a 9.x
 * commit), the ones skipped and why, and the builds they need, one per side.
 */
export function planMatrix({
  suites,
  datasets = ['100k'],
  aa = false,
  headSha,
  baseSha,
  nineXSha,
}) {
  const skipped = [];
  const runnable = suites.filter(name => {
    const group = SUITES[name].group;
    // An A/A run measures the head against itself: it needs no baseline.
    if (group === 'ab' && !baseSha && !aa) {
      skipped.push(
        `${name} (no V10 baseline: set base_ref; no v10.* release tag has the benchmark yet)`,
      );
      return false;
    }
    if (group === '9x' && !nineXSha) {
      skipped.push(`${name} (no 9.x commit)`);
      return false;
    }
    return true;
  });
  const groups = new Set(runnable.map(name => SUITES[name].group));
  const builds = [
    { side: 'head', sha: headSha, scope: 'v10', dir: 'bench-v10' },
    ...(groups.has('ab') && !aa
      ? [{ side: 'base', sha: baseSha, scope: 'v10', dir: 'bench-v10' }]
      : []),
    ...(groups.has('9x')
      ? [{ side: '9x', sha: nineXSha, scope: 'all', dir: 'bench-9x' }]
      : []),
  ];
  const jobs = runnable.flatMap(name =>
    datasets.map(dataset => jobId(name, dataset)),
  );
  return { suites: runnable, jobs, skipped, builds };
}

/** The spread of values: max / min - 1, 0 for fewer than two. */
export function spread(values) {
  const present = values.filter(value => Number.isFinite(value) && value > 0);
  if (present.length < 2) return 0;
  return Math.max(...present) / Math.min(...present) - 1;
}

/** The numbers of one run's result.json that the summaries use. */
export function metrics(result) {
  const results = result?.results ?? {};
  const latency = results.latencyMs ?? {};
  const resources = result?.resources ?? {};
  const serverCores = Object.values(resources.processes ?? {}).reduce(
    (sum, process) => sum + (process.averageCores ?? 0),
    0,
  );
  const containers = Object.fromEntries(
    Object.entries(resources.containers ?? {}).map(([name, container]) => [
      name.replace(/^wow-e2e-bench-/, ''),
      container.cpuPercentAverage ?? 0,
    ]),
  );
  const containerCores =
    Object.values(containers).reduce((sum, percent) => sum + percent, 0) / 100;
  const throughput = results.throughput ?? 0;
  const rate = result?.load?.rate;
  const perSecond = results.perSecond ?? [];
  const seconds = perSecond.map(second => second.ok ?? 0);
  const p99s = perSecond.map(second => second.p99Ms);
  const loadGenerator = resources.loadGenerator;
  // Who worked hardest: the servers, a container, or the load generator (in cores).
  const consumers = {
    server: serverCores,
    ...Object.fromEntries(
      Object.entries(resources.containers ?? {}).map(([name, container]) => [
        name.replace(/^wow-e2e-bench-/, ''),
        (container.cpuPercentAverage ?? 0) / 100,
      ]),
    ),
    ...(loadGenerator ? { 'load generator': loadGenerator.averageCores } : {}),
  };
  const bottleneck = Object.entries(consumers).sort((a, b) => b[1] - a[1])[0];
  return {
    rate,
    throughput,
    // The per-second series the bootstrap resamples, and the halves the stability check compares.
    okSeries: seconds,
    p50Series: perSecond.map(second => second.p50Ms),
    p99Series: p99s,
    stability: halves(
      seconds,
      p99s,
      perSecond.map(second => second.p50Ms),
    ),
    loadGeneratorCores: loadGenerator?.averageCores,
    loadGeneratorCpus: loadGenerator?.availableProcessors,
    bottleneck: bottleneck
      ? { side: bottleneck[0], cores: bottleneck[1] }
      : undefined,
    byScenario: results.byScenario
      ? Object.fromEntries(
          Object.entries(results.byScenario).map(([label, scenario]) => [
            label,
            {
              throughput: scenario.throughput,
              failed: scenario.failed,
              p50: scenario.latencyMs?.p50,
              p99: scenario.latencyMs?.p99,
            },
          ]),
        )
      : undefined,
    // The measured seconds that completed less than half the rate: a dip (a stall, a hand-over).
    weakSeconds: Number.isFinite(rate)
      ? seconds.filter(ok => ok < rate / 2).length
      : 0,
    minSecondOk: seconds.length > 0 ? Math.min(...seconds) : undefined,
    ok: results.ok ?? 0,
    failed: results.failed ?? 0,
    errors: results.errors ?? {},
    p50: latency.p50,
    p95: latency.p95,
    p99: latency.p99,
    // p99.9 only with at least P999_TAIL samples beyond it, else undefined (shown n/a).
    p999: (results.ok ?? 0) * 0.001 >= P999_TAIL ? latency.p999 : undefined,
    primed: result?.meta?.primed === 'true',
    primeSeconds: Number(result?.meta?.primeSeconds) || undefined,
    worstSecondP99: results.worstSecondP99Ms,
    serverCores,
    containers,
    containerCores,
    cpuMsPerOp:
      throughput > 0
        ? ((serverCores + containerCores) * 1000) / throughput
        : undefined,
  };
}

/**
 * The measured phase's first half against its second: the change in mean throughput, and the ratios of the halves'
 * median per-second p50 and p99. p99 is compared only when each half has at least P99_TAIL samples beyond it (a
 * hundred thousand operations a half): with fewer, half a run's tail is noise, and a stable system would trip it.
 */
export function halves(okSeries, p99Series, p50Series = []) {
  const half = Math.floor(okSeries.length / 2);
  if (half < 2) return undefined;
  const sum = values => values.reduce((a, b) => a + b, 0);
  const first = sum(okSeries.slice(0, half)) / half;
  const second = sum(okSeries.slice(half)) / (okSeries.length - half);
  const ratioOf = series => {
    const a = median(series.slice(0, half));
    const b = median(series.slice(half));
    return a > 0 ? b / a : undefined;
  };
  const tailed =
    Math.min(sum(okSeries.slice(0, half)), sum(okSeries.slice(half))) * 0.01 >=
    P99_TAIL;
  return {
    throughputChange: first > 0 ? second / first - 1 : undefined,
    p50Ratio:
      p50Series.length === okSeries.length ? ratioOf(p50Series) : undefined,
    p99Ratio: tailed ? ratioOf(p99Series) : undefined,
  };
}

/** The tail samples a p99 comparison of two halves needs: a thousand beyond it in each. */
export const P99_TAIL = 1000;

/** The tail samples p99.9 needs to be reported: a thousand beyond it, so a run of a million operations. */
export const P999_TAIL = 1000;

/** The metrics compared between the two arms of a pair, and whether higher is better. */
export const RATIO_METRICS = [
  ['throughput', 'Throughput', true],
  ['p50', 'p50', false],
  ['p95', 'p95', false],
  ['p99', 'p99', false],
  ['p999', 'p999', false],
  ['worstSecondP99', 'Worst-second p99', false],
  ['cpuMsPerOp', 'CPU ms/op', false],
];

/** b / a of every ratio metric. */
export function ratios(a, b) {
  return Object.fromEntries(
    RATIO_METRICS.map(([key]) => [
      key,
      Number.isFinite(a?.[key]) && Number.isFinite(b?.[key]) && a[key] > 0
        ? b[key] / a[key]
        : undefined,
    ]),
  );
}

/** Whether another round is due: two runs of one arm differ by more than DRIFT in throughput or p99. */
// The rounds of a paired suite: `calibrated`, each arm at its own calibrated rate (throughput and cost compare;
// latencies are at unequal loads), then `equal`, both arms at the lower of the two calibrated rates (latencies
// compare). A suite whose arms share one rate (`sharedRate`) has only equal rounds.
export const CALIBRATED = 'calibrated';
export const EQUAL = 'equal';
const modeOf = run => run.mode ?? CALIBRATED;

/** The lower of the arms' calibrated rates: the rate of the equal-rate rounds. */
export function equalRate(rates, arms) {
  const values = arms.map(arm => rates[arm.id]).filter(Number.isFinite);
  return values.length === arms.length ? Math.min(...values) : undefined;
}

export function drifted(runs, arms) {
  return arms.some(arm => {
    const own = runs.filter(
      run =>
        run.arm === arm.id && run.status === 'ok' && modeOf(run) === CALIBRATED,
    );
    return (
      spread(own.map(run => run.metrics.throughput)) > DRIFT ||
      spread(own.map(run => run.metrics.p99)) > DRIFT
    );
  });
}

/** The arms of round `round` (0-based) in their order: ABBA. */
export function roundOrder(arms, round) {
  return round % 2 === 0 ? arms : [...arms].reverse();
}

/** The pairs of a paired suite: per round, the two arms' runs and their ratios. */
export function pairs(suite, mode = CALIBRATED) {
  if (suite.arms.length < 2) return [];
  const [a, b] = suite.arms.map(arm => arm.id);
  const runs = suite.runs.filter(run => modeOf(run) === mode);
  const rounds = [...new Set(runs.map(run => run.round))].sort((x, y) => x - y);
  return rounds.flatMap(round => {
    const first = runs.find(run => run.round === round && run.arm === a);
    const second = runs.find(run => run.round === round && run.arm === b);
    if (first?.status !== 'ok' || second?.status !== 'ok') return [];
    return [{ round, ratios: ratios(first.metrics, second.metrics) }];
  });
}

const median = values => {
  const sorted = values.filter(Number.isFinite).sort((x, y) => x - y);
  if (sorted.length === 0) return undefined;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
};

// Significance (D224 note ②): a ratio counts only beyond the suite's noise. An A/A run (`aa`) puts the head against
// itself; the largest deviation of its pair ratios from 1 is the suite's threshold per metric (at least NOISE_FLOOR),
// which the report writes as noise.json, to commit as wow-benchmarks/e2e/noise.json for later runs to read. The
// confidence interval of a ratio comes from a bootstrap over the measured seconds of both arms' runs in that mode:
// the mean per-second throughput, the median per-second p50 and p99.
export const NOISE_FLOOR = 0.02;
export const AA_ROUNDS = 4;
export const BOOTSTRAP = { resamples: 1000, seed: 20261020 };
export const SIGNIFICANCE_METRICS = [
  ['throughput', 'okSeries', 'mean'],
  ['p50', 'p50Series', 'median'],
  ['p99', 'p99Series', 'median'],
];

/** A small seeded generator (mulberry32), so a report's intervals are the same every time it is rendered. */
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** The 95% bootstrap interval of statistic(b) / statistic(a) over the samples, with its point value. */
export function bootstrapRatio(a, b, statistic) {
  const xs = a.filter(Number.isFinite);
  const ys = b.filter(Number.isFinite);
  if (xs.length < 2 || ys.length < 2) return undefined;
  const of = values =>
    statistic === 'mean'
      ? values.reduce((sum, v) => sum + v, 0) / values.length
      : median(values);
  const next = random(BOOTSTRAP.seed);
  const draw = values =>
    Array.from(
      { length: values.length },
      () => values[Math.floor(next() * values.length)],
    );
  const ratios = [];
  for (let i = 0; i < BOOTSTRAP.resamples; i++) {
    const x = of(draw(xs));
    if (x > 0) ratios.push(of(draw(ys)) / x);
  }
  ratios.sort((p, q) => p - q);
  const at = q =>
    ratios[Math.min(ratios.length - 1, Math.floor(q * ratios.length))];
  const point = of(xs) > 0 ? of(ys) / of(xs) : undefined;
  return { point, lo: at(0.025), hi: at(0.975) };
}

/** The bootstrap intervals of a paired suite's ratios in one mode, per significance metric. */
export function confidence(suite, mode) {
  if (suite.arms?.length !== 2) return {};
  const [a, b] = suite.arms.map(arm => arm.id);
  const runs = suite.runs.filter(
    run => modeOf(run) === mode && run.status === 'ok',
  );
  const pool = (id, key) =>
    runs.filter(run => run.arm === id).flatMap(run => run.metrics[key] ?? []);
  // A latency's point estimate is the median of the rounds' own percentiles (each from the run's whole histogram);
  // its interval comes from the bootstrap over the measured seconds.
  const perRound = (id, metric) =>
    median(runs.filter(run => run.arm === id).map(run => run.metrics[metric]));
  return Object.fromEntries(
    SIGNIFICANCE_METRICS.map(([metric, key, statistic]) => {
      const interval = bootstrapRatio(pool(a, key), pool(b, key), statistic);
      if (!interval || metric === 'throughput') return [metric, interval];
      const pa = perRound(a, metric);
      const pb = perRound(b, metric);
      return [
        metric,
        { ...interval, point: pa > 0 ? pb / pa : interval.point },
      ];
    }),
  );
}

/**
 * An A/A suite's noise per significance metric: the half-width of the 95% bootstrap interval of its equal-rate
 * ratio (the head against itself at one rate, AA_ROUNDS rounds), at least NOISE_FLOOR.
 */
export function noiseOf(suite) {
  const intervals = confidence(suite, EQUAL);
  return Object.fromEntries(
    SIGNIFICANCE_METRICS.map(([metric]) => {
      const interval = intervals[metric];
      const half = interval ? (interval.hi - interval.lo) / 2 : NaN;
      return [metric, Math.max(NOISE_FLOOR, Number.isFinite(half) ? half : 0)];
    }),
  );
}

/** Whether a ratio's interval clears the noise threshold: `higher`, `lower`, `within noise` or `uncalibrated`. */
export function verdict(interval, threshold) {
  if (!interval) return 'no data';
  if (!Number.isFinite(threshold)) return 'uncalibrated';
  if (interval.lo > 1 + threshold) return 'higher';
  if (interval.hi < 1 - threshold) return 'lower';
  return 'within noise';
}

/** min, median and max of finite values, or undefined. */
export function range(values) {
  const finite = values.filter(Number.isFinite);
  if (finite.length === 0) return undefined;
  return {
    min: Math.min(...finite),
    median: median(finite),
    max: Math.max(...finite),
    n: finite.length,
  };
}

const ms = value => (Number.isFinite(value) ? `${value.toFixed(1)} ms` : '-');
const num = (value, digits = 1) =>
  Number.isFinite(value) ? value.toFixed(digits) : '-';
const ratio = value => (Number.isFinite(value) ? value.toFixed(3) : '-');
const ratioRange = r =>
  r ? `${ratio(r.median)} (${ratio(r.min)}–${ratio(r.max)}, n=${r.n})` : '-';
const propsText = props =>
  props && Object.keys(props).length > 0
    ? Object.entries(props)
        .map(([key, value]) => `${key}=${value}`)
        .join(', ')
    : 'defaults';

const errorsText = errors =>
  Object.entries(errors ?? {})
    .sort((a, b) => b[1] - a[1])
    .map(([kind, count]) => `${kind} ${count}`)
    .join(' · ') || '-';

/** An arm's open-loop rate as a share of its saturation, flagged when no probe sustained it. */
const shareText = (suite, id) => {
  const c = suite.calibration?.[id];
  if (!c) return '-';
  return `${(c.share * 100).toFixed(0)}%${c.sustainable ? '' : ' (**not sustained**)'}`;
};

const signalsText = signals =>
  signals
    ? Object.entries(signals)
        .filter(([kind]) => kind !== 't')
        .map(
          ([kind, counts]) =>
            `${kind} ${Object.entries(counts)
              .map(([key, count]) => `${key}=${count}`)
              .join(',')}`,
        )
        .join(' · ') || 'none'
    : '-';

/** The steps of a cliff suite: completions against the rate, errors and the node's signals. */
function cliffTable(suite) {
  const lines = [
    '| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (cumulative) |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of suite.runs) {
    const m = run.metrics;
    lines.push(
      run.status === 'ok'
        ? `| ${run.name} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${run.sustained ? 'yes' : '**no**'} | ${m.failed} | ${ms(m.p50)} | ${ms(m.p99)} | ${ms(m.worstSecondP99)} | ${errorsText(m.errors)} | ${signalsText(run.signals)} |`
        : `| ${run.name} | ${num(run.rate)}/s | **${run.status}** | | | | | | | |`,
    );
  }
  return lines;
}

/** The anomalies of suites as a table, or nothing. */
function anomalyTable(rows) {
  if (rows.length === 0) return [];
  return [
    '| Suite | Run | Context | Anomaly | Detail |',
    '| --- | --- | --- | --- | --- |',
    ...rows.map(
      row =>
        `| ${row.suite} | ${row.run} | ${row.context} | **${row.kind}** | ${row.detail} |`,
    ),
  ];
}

/** The sustainable rate of each scenario of a capacity suite. */
function capacityTable(suite) {
  const lines = [
    `| Scenario | Sustainable rate (≤1% errors, p99 ≤ ${suite.p99LimitMs} ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |`,
    '| --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const [scenario, c] of Object.entries(suite.capacity ?? {})) {
    lines.push(
      `| ${scenario} | ${c.rate ? `**${num(c.rate)}/s**` : '**none held**'} | ${ms(c.p50)} | ${ms(c.p99)} | ${num(c.cpuMsPerOp, 2)} | ${saturationText(suite.saturation?.[scenario])} | ${num(c.rate ?? 0)}–${num(c.upper)}/s |`,
    );
  }
  return lines;
}

/** The runs of a cluster suite: steady against hand-over. */
function clusterTable(suite) {
  const lines = [
    '| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes) |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of suite.runs) {
    const m = run.metrics;
    lines.push(
      run.status === 'ok'
        ? `| ${run.name} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${m.failed} | ${ms(m.p50)} | ${ms(m.p99)} | ${ms(m.worstSecondP99)} | ${m.weakSeconds} | ${m.minSecondOk ?? '-'}/s | ${errorsText(m.errors)} | ${signalsText(run.signals)} |`
        : `| ${run.name} | ${num(run.rate)}/s | **${run.status}** | | | | | | | | ${signalsText(run.signals)} |`,
    );
  }
  return lines;
}

function runTable(runs) {
  const lines = [
    '| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Errors |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of runs) {
    if (run.status !== 'ok') {
      lines.push(
        `| ${run.name} | ${run.arm} | ${num(run.rate)}/s | **${run.status}** | | | | | | | | | | | | | |`,
      );
      continue;
    }
    const m = run.metrics;
    const containers = Object.entries(m.containers)
      .map(([name, percent]) => `${name} ${percent.toFixed(0)}%`)
      .join(' · ');
    lines.push(
      `| ${run.name} | ${run.arm} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${m.ok} | ${m.failed} | ` +
        `${ms(m.p50)} | ${ms(m.p95)} | ${ms(m.p99)} | ${Number.isFinite(m.p999) ? ms(m.p999) : 'n/a'} | ${ms(m.worstSecondP99)} | ` +
        // A hand-over run's server CPU is not measured: the sampler's ps fails once the stopped node is gone.
        `${run.handover ? '-' : `${num(m.serverCores, 2)} cores`} | ${containers || '-'} | ` +
        `${Number.isFinite(m.loadGeneratorCores) ? `${num(m.loadGeneratorCores, 2)} of ${m.loadGeneratorCpus}` : '-'} | ` +
        `${m.bottleneck && !run.handover ? `${m.bottleneck.side} ${num(m.bottleneck.cores, 2)}` : '-'} | ` +
        `${run.handover ? '-' : num(m.cpuMsPerOp, 2)} | ${errorsText(m.errors)} |`,
    );
  }
  return lines;
}

function saturationTable(suite) {
  if (!suite.saturation || Object.keys(suite.saturation).length === 0)
    return [];
  const lines = [
    '| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |',
    '| --- | --- | --- | --- | --- |',
  ];
  for (const arm of suite.arms) {
    const s = suite.saturation[arm.id] ?? {};
    lines.push(
      `| ${arm.id} | ${saturationText(s)} | ${s.concurrency ?? '-'} | ${num(suite.rates?.[arm.id])}/s | ${shareText(suite, arm.id)} |`,
    );
  }
  return lines;
}

function pairTable(suite, mode) {
  const rows = pairs(suite, mode);
  if (rows.length === 0) return [];
  const [a, b] = suite.arms.map(arm => arm.id);
  return [
    `| Round | ${RATIO_METRICS.map(([, label]) => `${label} ${b}/${a}`).join(' | ')} |`,
    `| --- | ${RATIO_METRICS.map(() => '---').join(' | ')} |`,
    ...rows.map(
      row =>
        `| ${row.round + 1} | ${RATIO_METRICS.map(([key]) => ratio(row.ratios[key])).join(' | ')} |`,
    ),
  ];
}

/** What a suite's runs started from: primed or not, and whether its accounts fit the node's state cache. */
function primingLines(suite) {
  if (!suite.accounts || suite.kind === 'component') return [];
  const seconds = range(
    [...(suite.runs ?? []), ...(suite.probes ?? [])].map(
      run => run.metrics?.primeSeconds,
    ),
  );
  const lines = [
    suite.primed
      ? `Primed: every run touched each of the ${suite.accounts} accounts once before its warm-up, untimed${seconds ? ` (${num(seconds.min, 0)}–${num(seconds.max, 0)} s)` : ''}.`
      : suite.cold
        ? `Cold: not primed on purpose; the caches start empty and the keys are uniform over ${suite.accounts} accounts.`
        : `Not primed: ${suite.accounts} accounts, below ${PRIME_FROM}.`,
  ];
  if (suite.accounts > STATE_CACHE) {
    lines.push(
      `The accounts exceed the node's state cache (${STATE_CACHE} per context, \`wow.state-cache.capacity\`): the steady state is a full cache with evictions.`,
    );
  }
  return [...lines, ''];
}

/** The Markdown summary of one suite. */
export function renderSuite(suite) {
  const definition = SUITES[suite.name];
  const lines = [
    `## ${labelOf(suite)}${suite.aa ? ' (A/A: head against head)' : ''}`,
    '',
    definition?.answers ?? '',
    '',
    `${suite.scenario ?? 'scenarios below'}${definition?.mix ? ` (${definition.mix})` : ''}, ${suite.distribution} keys over ${suite.accounts ?? '?'} accounts; warm-up ${suite.warmUp} s, measured ${suite.measure} s` +
      (suite.kind === 'paired'
        ? `, ${suite.fraction * 100}% of saturation, ${suite.repeats} round(s)`
        : '') +
      (suite.extraRound
        ? ' + 1 (two runs of one arm differed by more than 5%)'
        : '') +
      `. Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores.`,
    '',
    ...primingLines(suite),
  ];
  const arms = suite.arms.filter(arm => arm.props);
  if (arms.length > 0) {
    lines.push(
      ...arms.map(arm => `- \`${arm.id}\`: ${propsText(arm.props)}`),
      '',
    );
  }
  const saturation = saturationTable(suite);
  if (saturation.length > 0) lines.push(...saturation, '');
  if (suite.probes?.length > 0) {
    lines.push(
      'Open-loop calibration probes (not part of the results):',
      '',
      ...runTable(suite.probes),
      '',
    );
  }
  if (suite.kind === 'steps') lines.push(...cliffTable(suite), '');
  if (suite.kind === 'capacity') lines.push(...capacityTable(suite), '');
  if (suite.kind === 'cluster') {
    lines.push(
      `Three nodes, requests round-robin, ${CLUSTER.measure} s measured; in the hand-over runs node 2 is stopped ${CLUSTER.stopAfter} s into the measured phase and restarted ${CLUSTER.down} s later.`,
      '',
      ...clusterTable(suite),
      '',
    );
  }
  lines.push(...runTable(suite.runs), '');
  const mixed = suite.runs.filter(run => run.metrics?.byScenario);
  if (mixed.length > 0) {
    const labels = Object.keys(mixed[0].metrics.byScenario);
    lines.push(
      'Each scenario of the mix (the writes under the reads):',
      '',
      `| Run | Arm | ${labels.map(label => `${label} p50 | ${label} p99 | ${label} failed`).join(' | ')} |`,
      `| --- | --- | ${labels.map(() => '--- | --- | ---').join(' | ')} |`,
      ...mixed.map(
        run =>
          `| ${run.name} | ${run.arm} | ${labels
            .map(label => {
              const x = run.metrics.byScenario[label] ?? {};
              return `${ms(x.p50)} | ${ms(x.p99)} | ${x.failed ?? '-'}`;
            })
            .join(' | ')} |`,
      ),
      '',
    );
  }
  const calibratedPairs = pairTable(suite, CALIBRATED);
  if (calibratedPairs.length > 0) {
    lines.push(
      'Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):',
      '',
      ...calibratedPairs,
      '',
    );
  }
  const equalPairs = pairTable(suite, EQUAL);
  if (equalPairs.length > 0) {
    lines.push(
      `Pairs at one rate for both arms${suite.equalRate ? `, ${num(suite.equalRate)}/s` : ''} (latencies compare; lower is better):`,
      '',
      ...equalPairs,
      '',
    );
  }
  const found = anomalyTable(anomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} step(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}

function runnerInfo() {
  const read = path => (existsSync(path) ? readFileSync(path, 'utf8') : '');
  const cpuinfo = read('/proc/cpuinfo');
  const meminfo = read('/proc/meminfo');
  const cpu = cpuinfo.match(/^model name\s*:\s*(.+)$/m)?.[1]?.trim();
  const cores = (cpuinfo.match(/^processor\s*:/gm) ?? []).length || undefined;
  const memKb = Number(meminfo.match(/^MemTotal:\s*(\d+)/m)?.[1]);
  return {
    cpu: cpu ?? process.env.RUNNER_CPU ?? 'unknown',
    cores,
    memoryGb: memKb ? Math.round(memKb / 1024 / 1024) : undefined,
    runner: process.env.RUNNER_NAME,
  };
}

// A cluster run with a hand-over: bench.sh runs with its output teed to a log, and a helper waits for the load
// generator's measured phase, stops the node on NODE_PORT (SIGTERM: a graceful stop, its partitions hand over),
// and after DOWN seconds starts it again with its own command line, working directory and environment. The
// restarted node is stopped once bench.sh is done (bench.sh only knows the pids it started). Linux only (/proc).
export const HANDOVER_SCRIPT = `
set -uo pipefail
bench=$1; shift
mkdir -p "$RUN_DIR"
log="$RUN_DIR/bench-output.log"; : > "$log"
handover() {
  for _ in $(seq 1 900); do grep -q ': measure$' "$log" && break; sleep 1; done
  grep -q ': measure$' "$log" || return 0
  sleep "$STOP_AFTER"
  pid=$(pgrep -f -- "--server.port=$NODE_PORT" | head -1)
  if [ -z "$pid" ]; then echo "[handover] no node on port $NODE_PORT"; return 0; fi
  mapfile -d '' argv < "/proc/$pid/cmdline"
  cwd=$(readlink "/proc/$pid/cwd")
  echo "[handover] $(date +%s.%N) stopping the node on port $NODE_PORT (pid $pid)"
  kill -TERM "$pid"
  for _ in $(seq 1 300); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
  echo "[handover] $(date +%s.%N) stopped; down for $DOWN s"
  sleep "$DOWN"
  (cd "$cwd" && exec "\${argv[@]}") >> "$RUN_DIR/node-restarted.log" 2>&1 &
  echo $! > "$RUN_DIR/node-restarted.pid"
  echo "[handover] $(date +%s.%N) restarted (pid $!)"
}
handover >> "$RUN_DIR/handover.log" 2>&1 &
helper=$!
"$bench" "$@" 2>&1 | tee -a "$log"
status=\${PIPESTATUS[0]}
kill "$helper" 2>/dev/null; wait "$helper" 2>/dev/null
if [ -f "$RUN_DIR/node-restarted.pid" ]; then
  restarted=$(cat "$RUN_DIR/node-restarted.pid")
  kill "$restarted" 2>/dev/null
  for _ in $(seq 1 150); do kill -0 "$restarted" 2>/dev/null || break; sleep 0.2; done
fi
exit "$status"
`;

/**
 * Runs bench.sh with `args`, the arm's properties in SPRING_APPLICATION_JSON; true when it succeeded. With `handover`
 * (`{ dir, stopAfter, down, nodePort }`) it runs through HANDOVER_SCRIPT.
 */
function bench(benchPath, args, props, handover) {
  const env = { ...process.env };
  delete env.SPRING_APPLICATION_JSON;
  if (props && Object.keys(props).length > 0)
    env.SPRING_APPLICATION_JSON = JSON.stringify(props);
  console.log(
    `::group::bench.sh ${args.join(' ')}${props ? ` (${propsText(props)})` : ''}${handover ? ' (hand-over)' : ''}`,
  );
  const result = handover
    ? spawnSync(
        'bash',
        ['-c', HANDOVER_SCRIPT, 'handover', benchPath, ...args],
        {
          stdio: 'inherit',
          env: {
            ...env,
            RUN_DIR: handover.dir,
            STOP_AFTER: String(handover.stopAfter),
            DOWN: String(handover.down),
            NODE_PORT: String(handover.nodePort),
          },
        },
      )
    : spawnSync(benchPath, args, { stdio: 'inherit', env });
  console.log('::endgroup::');
  if (result.status !== 0) {
    console.log(
      `::warning::bench.sh ${args[0]} failed (${result.status ?? result.signal}): ${args.join(' ')}`,
    );
  }
  return result.status === 0;
}

const readJson = path => JSON.parse(readFileSync(path, 'utf8'));

/** Drives one suite through bench.sh; writes suite.json and summary.md to `out`. */
export function runSuite(name, options) {
  const definition = SUITES[name];
  if (!definition) throw new Error(`Unknown suite: ${name}`);
  const {
    benchPath,
    out,
    warmUp,
    measure,
    repeats,
    fraction,
    p99LimitMs = Number(DEFAULTS.p99_limit_ms),
    installs = {},
    refs = {},
    cluster = CLUSTER,
    dataset = '100k',
    aa = false,
    cpus = {},
  } = options;
  mkdirSync(out, { recursive: true });
  const data = DATASETS[dataset];
  if (!data) throw new Error(`Unknown data set: ${dataset}`);
  // An A/A run puts the head against itself in a baseline suite, at least AA_ROUNDS rounds: the width of its
  // equal-rate ratios' intervals is the suite's noise.
  const aaRun = aa && definition.group === 'ab';
  const rounds = aaRun ? Math.max(repeats, AA_ROUNDS) : repeats;
  const primed = data.accounts >= PRIME_FROM && !definition.cold;
  const arms =
    definition.kind === 'paired'
      ? aaRun
        ? [
            { ...definition.arms[1], id: 'a' },
            { ...definition.arms[1], id: 'b' },
          ]
        : definition.arms
      : ['steps', 'capacity', 'cluster'].includes(definition.kind)
        ? [definition.arm]
        : [
            ...new Map(
              definition.runs.map(run => [run.arm.id, run.arm]),
            ).values(),
          ];
  const suite = {
    schema: 'wow-e2e-bench/suite/1',
    name,
    kind: definition.kind,
    scenario: definition.scenario,
    distribution: definition.distribution,
    warmUp,
    measure,
    repeats,
    fraction,
    p99LimitMs,
    dataset,
    accounts: data.accounts,
    primed,
    cold: definition.cold === true,
    aa: aaRun,
    arms,
    refs,
    runner: runnerInfo(),
    saturation: {},
    rates: {},
    runs: [],
    failures: 0,
    startedAt: new Date().toISOString(),
  };
  const save = () => {
    writeFileSync(
      join(out, 'suite.json'),
      `${JSON.stringify(suite, null, 2)}\n`,
    );
    writeFileSync(join(out, 'summary.md'), renderSuite(suite));
  };
  // The options every bench.sh call of an arm takes: its version, backend and build (`--install`, when the build is
  // not the one in the harness's own tree), the scenario and the keys.
  // A mixed suite names its scenarios in --mix (the --scenario only labels the run's directory).
  const common = (arm, scenario = definition.scenario) => [
    `--version=${arm.version}`,
    `--backend=${arm.backend}`,
    ...(installs[arm.install] ? [`--install=${installs[arm.install]}`] : []),
    `--scenario=${scenario}`,
    ...(definition.mix && scenario === definition.scenario
      ? [`--mix=${definition.mix}`]
      : []),
    `--distribution=${definition.distribution}`,
    `--accounts=${data.accounts}`,
    `--seed-concurrency=${data.seedConcurrency}`,
    ...(primed ? ['--prime'] : []),
    ...(cpus.load ? [`--load-cpus=${cpus.load}`] : []),
    ...(cpus.server ? [`--server-cpus=${cpus.server}`] : []),
  ];
  const propsOf = arm =>
    arm.version === 'v10' ? { ...SIGNALS, ...arm.props } : arm.props;
  const measureRun = (
    runName,
    arm,
    rate,
    round,
    phase = { warmUp, measure, into: suite.runs, mode: CALIBRATED },
  ) => {
    const dir = join(out, runName);
    const ok = bench(
      benchPath,
      [
        'run',
        ...common(arm, phase.scenario),
        ...(phase.extra ?? []),
        `--rate=${rate.toFixed(1)}`,
        `--warm-up=${phase.warmUp}`,
        `--measure=${phase.measure}`,
        `--label=${name}-${runName}`,
        `--out=${dir}`,
      ],
      propsOf(arm),
      phase.handover && { ...phase.handover, dir },
    );
    const file = join(dir, 'result.json');
    const run = {
      name: runName,
      arm: arm.id,
      round,
      rate,
      dir: runName,
      status: 'failed',
      ...(phase.mode === EQUAL ? { mode: EQUAL } : {}),
      ...(phase.scenario ? { scenario: phase.scenario } : {}),
      ...(phase.handover ? { handover: true } : {}),
    };
    if (ok && existsSync(file)) {
      run.status = 'ok';
      run.metrics = metrics(readJson(file));
    } else {
      suite.failures++;
    }
    // Every node's last signals line, a restarted node's included, summed (also for a failed run: its logs stay).
    if (existsSync(dir)) {
      const signals = mergeSignals(
        readdirSync(dir)
          .filter(file => /^node-.*\.log$/.test(file))
          .map(file => lastSignals(readFileSync(join(dir, file), 'utf8'))),
      );
      if (signals) run.signals = signals;
    }
    phase.into.push(run);
    save();
    return run;
  };

  // The closed-loop saturation of an arm in a scenario, recorded under `key`.
  const saturate = (arm, key, scenario) => {
    const dir = join(out, `saturation-${key}`);
    const ok = bench(
      benchPath,
      [
        'saturate',
        ...common(arm, scenario),
        `--warm-up=${SATURATION.warmUp}`,
        `--step=${SATURATION.step}`,
        `--max-concurrency=${SATURATION.maxConcurrency}`,
        `--label=${name}-saturation-${key}`,
        `--out=${dir}`,
      ],
      propsOf(arm),
    );
    const file = join(dir, 'saturation.json');
    const saturation = ok && existsSync(file) ? readJson(file) : undefined;
    if (saturation?.saturationThroughput > 0) {
      suite.saturation[key] = {
        status: 'ok',
        throughput: saturation.saturationThroughput,
        concurrency: saturation.saturationConcurrency,
        capped: saturation.saturationConcurrency >= SATURATION.maxConcurrency,
      };
    } else {
      suite.saturation[key] = { status: 'failed' };
      suite.failures++;
    }
    save();
    return suite.saturation[key];
  };

  if (definition.kind === 'paired') {
    for (const arm of arms) saturate(arm, arm.id);
    suite.probes = [];
    suite.calibration = {};
    const calibrated = definition.sharedRate ? arms.slice(0, 1) : arms;
    for (const arm of calibrated) {
      const saturation = suite.saturation[arm.id]?.throughput;
      if (!saturation) continue;
      let rate = saturation * fraction;
      let kept = false;
      for (let attempt = 1; attempt <= PROBE.attempts; attempt++) {
        const probe = measureRun(`probe${attempt}-${arm.id}`, arm, rate, 0, {
          warmUp: PROBE.warmUp,
          measure: PROBE.measure,
          into: suite.probes,
        });
        if (probe.status === 'ok' && sustainable(probe.metrics, rate)) {
          kept = true;
          break;
        }
        if (attempt < PROBE.attempts) rate *= PROBE.step;
      }
      suite.rates[arm.id] = rate;
      suite.calibration[arm.id] = {
        sustainable: kept,
        share: rate / saturation,
      };
      save();
    }
    if (definition.sharedRate && suite.rates[arms[0].id]) {
      for (const arm of arms) suite.rates[arm.id] = suite.rates[arms[0].id];
    }
    const runnable = arms.filter(arm => suite.rates[arm.id]);
    const firstMode = definition.sharedRate ? EQUAL : CALIBRATED;
    const round = (index, mode, rateOf, prefix) => {
      for (const arm of roundOrder(runnable, index)) {
        measureRun(`${prefix}${index + 1}-${arm.id}`, arm, rateOf(arm), index, {
          warmUp,
          measure,
          into: suite.runs,
          mode,
        });
      }
    };
    const own = arm => suite.rates[arm.id];
    if (runnable.length > 0) {
      for (let index = 0; index < repeats; index++)
        round(index, firstMode, own, 'r');
      if (
        repeats >= 2 &&
        !aaRun &&
        firstMode === CALIBRATED &&
        drifted(suite.runs, runnable)
      ) {
        suite.extraRound = true;
        round(repeats, firstMode, own, 'r');
      }
    }
    // The equal-rate rounds: both arms at the lower calibrated rate, so their latencies compare.
    const equal = equalRate(suite.rates, arms);
    if (!definition.sharedRate && arms.length === 2 && equal) {
      suite.equalRate = equal;
      save();
      for (let index = 0; index < rounds; index++)
        round(index, EQUAL, () => equal, 'e');
    } else if (definition.sharedRate && suite.rates[arms[0].id]) {
      suite.equalRate = suite.rates[arms[0].id];
    }
  } else if (definition.kind === 'capacity') {
    suite.probes = [];
    suite.capacity = {};
    for (const scenario of definition.scenarios) {
      const top = saturate(definition.arm, scenario, scenario)?.throughput;
      if (!top) continue;
      let lo = 0;
      let hi = top;
      let best;
      for (let probe = 1; probe <= CAPACITY.probes; probe++) {
        const rate = (lo + hi) / 2;
        const run = measureRun(
          `${scenario.toLowerCase()}-probe${probe}`,
          definition.arm,
          rate,
          probe - 1,
          {
            warmUp: CAPACITY.warmUp,
            measure: CAPACITY.measure,
            into: suite.probes,
            scenario,
          },
        );
        run.held =
          run.status === 'ok' &&
          sustainable(run.metrics, rate) &&
          run.metrics.p99 <= p99LimitMs;
        if (run.held) {
          lo = rate;
          best = run;
        } else {
          hi = rate;
        }
        save();
        if ((hi - lo) / top <= CAPACITY.tolerance) break;
      }
      suite.capacity[scenario] = {
        saturation: top,
        rate: best ? lo : undefined,
        run: best?.name,
        p50: best?.metrics.p50,
        p99: best?.metrics.p99,
        cpuMsPerOp: best?.metrics.cpuMsPerOp,
        upper: hi,
      };
      save();
    }
  } else if (definition.kind === 'cluster') {
    for (const { scenario, rate } of cluster.scenarios) {
      for (const handover of [false, true]) {
        measureRun(
          `${scenario.toLowerCase()}-${handover ? 'handover' : 'steady'}`,
          definition.arm,
          rate,
          0,
          {
            warmUp: cluster.warmUp,
            measure: cluster.measure,
            into: suite.runs,
            scenario,
            extra: [`--nodes=${cluster.nodes}`],
            handover: handover && {
              stopAfter: cluster.stopAfter,
              down: cluster.down,
              nodePort: cluster.nodePort,
            },
          },
        );
      }
    }
  } else if (definition.kind === 'steps') {
    let unsustained = 0;
    for (const [index, rate] of CLIFF.rates.entries()) {
      const run = measureRun(
        `s${index + 1}-${rate}`,
        definition.arm,
        rate,
        index,
        {
          warmUp: CLIFF.warmUp,
          measure: CLIFF.measure,
          into: suite.runs,
        },
      );
      run.sustained = run.status === 'ok' && sustainable(run.metrics, rate);
      save();
      unsustained = run.sustained ? 0 : unsustained + 1;
      if (unsustained >= 2) break;
    }
  } else {
    definition.runs.forEach((run, index) => {
      suite.rates[`${run.arm.id}@${run.rate}`] = run.rate;
      measureRun(
        `d${index + 1}-${run.arm.id}-${run.rate}`,
        run.arm,
        run.rate,
        index,
      );
    });
  }
  suite.finishedAt = new Date().toISOString();
  save();
  return suite;
}

/** Every suite.json under `dir` (the report job's downloaded artifacts). */
export function readSuites(dir) {
  const suites = [];
  const walk = (path, depth) => {
    if (depth > 3 || !existsSync(path)) return;
    for (const entry of readdirSync(path, { withFileTypes: true })) {
      const child = join(path, entry.name);
      if (entry.isDirectory()) walk(child, depth + 1);
      else if (entry.name === 'suite.json') suites.push(readJson(child));
    }
  };
  walk(dir, 0);
  const order = Object.keys(SUITES);
  return suites.sort((a, b) => order.indexOf(a.name) - order.indexOf(b.name));
}

/** The PostgreSQL append-queue verdict: saturation on/off and p99 at the same offered load. */
export function queueVerdict(suite) {
  const off = suite.saturation?.['queue-off']?.throughput;
  const on = suite.saturation?.['queue-on']?.throughput;
  const saturationRatio = off && on ? on / off : undefined;
  const p99 = range(pairs(suite, EQUAL).map(pair => pair.ratios.p99));
  let verdict = 'not decided: a step failed';
  if (Number.isFinite(saturationRatio) && p99) {
    if (saturationRatio <= 1 - DRIFT || p99.median >= 1 + 2 * DRIFT) {
      verdict =
        'suggests **off**: less throughput at saturation or a higher p99';
    } else if (
      (saturationRatio >= 1 + DRIFT && p99.max <= 1 + DRIFT) ||
      p99.median <= 1 - 2 * DRIFT
    ) {
      verdict =
        'suggests **on**: more throughput at saturation, or a p99 lower by 10% or more at the same load, and neither worse';
    } else {
      verdict = 'no clear difference within 5% (keep the current default, off)';
    }
  }
  return { saturationRatio, p99, verdict };
}

/** The noise thresholds of a run's A/A suites, keyed by `suite@dataset`, merged over `previous`. */
export function noiseFile(suites, previous = {}) {
  const next = { ...previous };
  for (const suite of suites.filter(s => s.aa))
    next[labelOf(suite)] = noiseOf(suite);
  return next;
}

/** The combined report of every suite. */
export function renderReport(suites, meta = {}) {
  const lines = [
    '# V10 end-to-end benchmark (D224) on GitHub Actions',
    '',
    `Head: \`${meta.headRef ?? '?'}\` ${meta.headSha ?? ''}; V10 baseline: ${meta.baseSha ? `\`${meta.baseRef}\` ${meta.baseSha}` : 'none'}; ` +
      `9.x: ${meta.nineXSha ? `\`${meta.nineXRef}\` ${meta.nineXSha}` : 'not run'}; harness and load generator from ${meta.harness ?? '?'}.`,
    '',
    `Tier: ${meta.tier ?? '?'}; coverage: ${suites.map(labelOf).join(', ') || 'none'}.`,
    '',
    "Each suite ran on one runner of its own, so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base, head/9x): median (min–max, n pairs). Lower is better except throughput.",
    '',
  ];
  if (meta.skipped?.length > 0) {
    lines.push(`**Skipped:** ${meta.skipped.join('; ')}.`, '');
  }
  if (suites.length === 0) {
    lines.push('No suite left results; see the jobs.', '');
    return `${lines.join('\n')}\n`;
  }
  const found = suites.flatMap(suite => anomalies(suite, meta.noise ?? {}));
  lines.push('## Anomalies', '');
  if (found.length === 0) {
    lines.push(
      'None: no partition paused, no tail read the store after a gap or an overflow, no error spike, dip or p99 above 10× p50.',
      '',
    );
  } else {
    lines.push(
      `Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than ${ANOMALY.errorPercent}% errors, a measured second below half the rate, p99 above ${ANOMALY.tailToMedian}× p50 (when p99 ≥ ${ANOMALY.minTailMs} ms). Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).`,
      '',
      ...anomalyTable(found),
      '',
    );
  }
  const noise = meta.noise ?? {};
  const significant = suites.filter(
    s => s.kind === 'paired' && s.arms.length === 2 && !s.aa,
  );
  if (significant.length > 0) {
    const rows = [];
    let beyond = 0;
    let calibrated = 0;
    for (const suite of significant) {
      const thresholds = noise[labelOf(suite)];
      if (thresholds) calibrated++;
      const [a, b] = suite.arms.map(arm => arm.id);
      // Throughput and CPU compare at the calibrated rates, latencies at the equal rate.
      for (const [metric, mode] of [
        ['throughput', CALIBRATED],
        ['p50', EQUAL],
        ['p99', EQUAL],
      ]) {
        const interval = confidence(suite, mode)[metric];
        const result = verdict(interval, thresholds?.[metric]);
        if (result === 'higher' || result === 'lower') beyond++;
        rows.push(
          `| ${labelOf(suite)} | ${b}/${a} | ${metric} | ${mode} | ${interval ? `${ratio(interval.point)} [${ratio(interval.lo)}–${ratio(interval.hi)}]` : '-'} | ` +
            `${thresholds?.[metric] ? `±${num(thresholds[metric] * 100)}%` : '-'} | ${result === 'higher' || result === 'lower' ? `**${result}**` : result} |`,
        );
      }
    }
    lines.push(
      '## Significance',
      '',
      `Beyond noise: ${beyond} of ${rows.length} ratios; ${calibrated} of ${significant.length} suites have an A/A noise threshold (wow-benchmarks/e2e/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is \`uncalibrated\`.`,
      '',
      '| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |',
      '| --- | --- | --- | --- | --- | --- | --- |',
      ...rows,
      '',
    );
  }
  const aaSuites = suites.filter(s => s.aa);
  if (aaSuites.length > 0) {
    lines.push(
      '## A/A noise',
      '',
      `The head against itself at one rate, ${AA_ROUNDS} rounds or more: each metric's threshold is the half-width of the 95% bootstrap interval of its ratio, at least 2%. The report writes these as noise.json; commit it as wow-benchmarks/e2e/noise.json to make them the thresholds of later runs.`,
      '',
      `| Suite | ${SIGNIFICANCE_METRICS.map(([metric]) => metric).join(' | ')} |`,
      `| --- | ${SIGNIFICANCE_METRICS.map(() => '---').join(' | ')} |`,
      ...aaSuites.map(suite => {
        const n = noiseOf(suite);
        return `| ${labelOf(suite)} | ${SIGNIFICANCE_METRICS.map(([metric]) => `±${num(n[metric] * 100)}%`).join(' | ')} |`;
      }),
      '',
    );
  }
  const capacities = suites.filter(s => s.kind === 'capacity');
  if (capacities.length > 0) {
    lines.push('## Sustainable rates (capacity guidance)', '');
    for (const suite of capacities) {
      lines.push(`### ${labelOf(suite)}`, '', ...capacityTable(suite), '');
    }
  }
  const clusters = suites.filter(s => s.kind === 'cluster');
  if (clusters.length > 0) {
    lines.push('## E5 cluster: steady and hand-over', '');
    for (const suite of clusters) {
      lines.push(`### ${labelOf(suite)}`, '', ...clusterTable(suite), '');
    }
  }
  lines.push(
    '## Runners',
    '',
    '| Suite | CPU | Cores | Memory |',
    '| --- | --- | --- | --- |',
  );
  for (const suite of suites) {
    lines.push(
      `| ${labelOf(suite)} | ${suite.runner?.cpu ?? '?'} | ${suite.runner?.cores ?? '?'} | ${suite.runner?.memoryGb ?? '?'} GB |`,
    );
  }
  lines.push(
    '',
    '## Saturation points (closed loop)',
    '',
    '| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |',
    '| --- | --- | --- | --- | --- | --- |',
  );
  for (const suite of suites.filter(s => s.kind === 'paired')) {
    for (const arm of suite.arms) {
      const s = suite.saturation?.[arm.id] ?? {};
      lines.push(
        `| ${labelOf(suite)} | ${arm.id} | ${saturationText(s)} | ${s.concurrency ?? '-'} | ${num(suite.rates?.[arm.id])}/s | ${shareText(suite, arm.id)} |`,
      );
    }
  }
  const compared = suites.filter(
    s => s.kind === 'paired' && s.arms.length === 2,
  );
  if (compared.length > 0) {
    lines.push(
      '',
      '## Paired ratios',
      '',
      '`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.',
      '',
      `| Suite | b/a | Rounds | Saturation | ${RATIO_METRICS.map(([, label]) => label).join(' | ')} |`,
      `| --- | --- | --- | --- | ${RATIO_METRICS.map(() => '---').join(' | ')} |`,
    );
    for (const suite of compared) {
      const [a, b] = suite.arms.map(arm => arm.id);
      const sa = suite.saturation?.[a];
      const sb = suite.saturation?.[b];
      const saturation =
        sa?.throughput && sb?.throughput
          ? `${sb.capped || sa.capped ? '~' : ''}${ratio(sb.throughput / sa.throughput)}`
          : '-';
      for (const [mode, label] of [
        [CALIBRATED, 'calibrated (unequal load)'],
        [
          EQUAL,
          `equal rate${suite.equalRate ? ` ${num(suite.equalRate)}/s` : ''}`,
        ],
      ]) {
        const rows = pairs(suite, mode);
        if (rows.length === 0) continue;
        lines.push(
          `| ${labelOf(suite)} | ${b}/${a} | ${label} | ${saturation} | ` +
            `${RATIO_METRICS.map(([key]) => ratioRange(range(rows.map(row => row.ratios[key])))).join(' | ')} |`,
        );
      }
    }
  }
  const solo = suites.filter(s => s.kind === 'paired' && s.arms.length === 1);
  if (solo.length > 0) {
    lines.push(
      '',
      '## Reported on their own (V10 on PostgreSQL)',
      '',
      '| Suite | Runs | Throughput | p50 | p99 | p999 | Worst-second p99 | CPU ms/op |',
      '| --- | --- | --- | --- | --- | --- | --- | --- |',
    );
    for (const suite of solo) {
      const runs = suite.runs
        .filter(run => run.status === 'ok')
        .map(run => run.metrics);
      const cell = (key, format) => {
        const r = range(runs.map(m => m[key]));
        return r
          ? `${format(r.median)} (${format(r.min)}–${format(r.max)})`
          : '-';
      };
      lines.push(
        `| ${labelOf(suite)} | ${runs.length} | ${cell('throughput', v => `${num(v)}/s`)} | ${cell('p50', ms)} | ${cell('p99', ms)} | ` +
          `${cell('p999', ms)} | ${cell('worstSecondP99', ms)} | ${cell('cpuMsPerOp', v => num(v, 2))} |`,
      );
    }
  }
  const diag = suites.find(s => s.name === 'e3-mongo-diag');
  if (diag) {
    const armOf = new Map(diag.arms.map(arm => [arm.id, arm]));
    lines.push(
      '',
      '## E3 on MongoDB: diagnostics',
      '',
      SUITES['e3-mongo-diag'].answers,
      '',
      '| Run | Version | Properties | Rate | Throughput | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | MongoDB CPU | Kafka CPU | Server CPU |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    );
    for (const run of diag.runs) {
      const arm = armOf.get(run.arm) ?? {};
      const m = run.metrics;
      lines.push(
        run.status === 'ok'
          ? `| ${run.name} | ${arm.version} | ${propsText(arm.props)} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${m.failed} | ${ms(m.p50)} | ${ms(m.p95)} | ` +
              `${ms(m.p99)} | ${ms(m.p999)} | ${ms(m.worstSecondP99)} | ${num(m.containers.mongo, 0)}% | ${num(m.containers.kafka, 0)}% | ${num(m.serverCores, 2)} cores |`
          : `| ${run.name} | ${arm.version} | ${propsText(arm.props)} | ${num(run.rate)}/s | **${run.status}** | | | | | | | | | |`,
      );
    }
  }
  const cliffs = suites.filter(s => s.kind === 'steps');
  if (cliffs.length > 0) {
    lines.push(
      '',
      '## E3 cliff steps',
      '',
      'Fixed open-loop steps, a minute each on fresh containers. "Kept up": at most 1% failed and at least 97% of the rate completed. V10 signals count from the server start (seeding included): `tailRead` reasons other than `start` mean the tails fell back to the store, `refused wow.command.busy` that the node shed load.',
    );
    for (const suite of cliffs)
      lines.push('', `### ${labelOf(suite)}`, '', ...cliffTable(suite));
  }
  const queue = suites.find(s => s.name === 'pg-append-queue');
  if (queue) {
    const v = queueVerdict(queue);
    lines.push(
      '',
      '## PostgreSQL append queue (D248)',
      '',
      `Saturation on/off: ${ratio(v.saturationRatio)}; p99 on/off at the same offered load: ${ratioRange(v.p99)}. Verdict: ${v.verdict}.`,
    );
  }
  const failed = suites.filter(s => s.failures > 0);
  if (failed.length > 0) {
    lines.push(
      '',
      `**Failed steps:** ${failed.map(s => `${s.name} (${s.failures})`).join(', ')}.`,
    );
  }
  lines.push(
    '',
    '---',
    '',
    ...suites.map(suite => renderSuite(suite).replace(/^## /m, '### ')),
  );
  return `${lines.join('\n')}\n`;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const command = process.argv[2];
  const env = process.env;
  const output = pairsToWrite => {
    const text = Object.entries(pairsToWrite)
      .map(([key, value]) => `${key}=${value}`)
      .join('\n');
    console.log(text);
    if (env.GITHUB_OUTPUT) appendFileSync(env.GITHUB_OUTPUT, `${text}\n`);
  };
  const settingsOf = () => {
    const config =
      env.CONFIG && existsSync(env.CONFIG) ? readJson(env.CONFIG) : undefined;
    if (config && env.EVENT === 'push')
      console.log(`bench-run.json: ${JSON.stringify(config)}`);
    return runSettings({
      event: env.EVENT,
      config,
      inputs: {
        tier: env.TIER,
        suites: env.SUITES,
        dataset: env.DATASET,
        aa: env.AA,
        warm_up: env.WARM_UP,
        measure: env.MEASURE,
        repeats: env.REPEATS,
        fraction: env.FRACTION,
        p99_limit_ms: env.P99_LIMIT_MS,
        base_ref: env.BASE_REF,
        nine_x_ref: env.NINE_X_REF,
      },
    });
  };
  try {
    if (command === 'plan') {
      // The settings, checked, and which commits the suites need resolved.
      const settings = settingsOf();
      const inputs = resolveInputs({
        tier: settings.tier,
        suites: settings.suites,
        dataset: settings.dataset,
        aa: settings.aa,
        warmUp: settings.warm_up,
        measure: settings.measure,
        repeats: settings.repeats,
        fraction: settings.fraction,
        p99LimitMs: settings.p99_limit_ms,
      });
      const groups = new Set(inputs.suites.map(name => SUITES[name].group));
      output({
        suites: JSON.stringify(inputs.suites),
        'warm-up': inputs.warmUp,
        measure: inputs.measure,
        repeats: inputs.repeats,
        fraction: inputs.fraction,
        'p99-limit-ms': inputs.p99LimitMs,
        tier: inputs.tier,
        datasets: JSON.stringify(inputs.datasets),
        aa: inputs.aa,
        'base-ref': settings.base_ref,
        'needs-base': groups.has('ab') && !inputs.aa,
        'nine-x-ref': groups.has('9x') ? settings.nine_x_ref : '',
      });
    } else if (command === 'matrix') {
      const plan = planMatrix({
        suites: JSON.parse(env.SUITES_JSON),
        datasets: JSON.parse(env.DATASETS_JSON ?? '["100k"]'),
        aa: env.AA === 'true',
        headSha: env.HEAD_SHA,
        baseSha: env.BASE_SHA,
        nineXSha: env.NINE_X_SHA,
      });
      for (const skipped of plan.skipped)
        console.log(`::warning::Skipped ${skipped}`);
      if (plan.suites.length === 0) throw new Error('No suite can run.');
      output({
        suites: JSON.stringify(plan.suites),
        jobs: JSON.stringify(plan.jobs),
        builds: JSON.stringify(plan.builds),
        skipped: JSON.stringify(plan.skipped),
      });
    } else if (command === 'suite') {
      const job = parseJob(process.argv[3]);
      const inputs = resolveInputs({
        suites: job.suite,
        dataset: job.dataset,
        aa: env.AA,
        warmUp: env.WARM_UP,
        measure: env.MEASURE,
        repeats: env.REPEATS,
        fraction: env.FRACTION,
        p99LimitMs: env.P99_LIMIT_MS,
      });
      const suite = runSuite(inputs.suites[0], {
        benchPath: env.BENCH,
        out: env.OUT,
        ...inputs,
        dataset: job.dataset,
        cpus: { load: env.LOAD_CPUS, server: env.SERVER_CPUS },
        installs: Object.fromEntries(
          [
            ['head', env.INSTALL_HEAD],
            ['base', env.INSTALL_BASE],
            ['9x', env.INSTALL_9X],
          ].filter(([, dir]) => dir),
        ),
        refs: {
          headRef: env.HEAD_REF,
          headSha: env.HEAD_SHA,
          baseRef: env.BASE_REF,
          baseSha: env.BASE_SHA,
          nineXRef: env.NINE_X_REF,
          nineXSha: env.NINE_X_SHA,
          harness: env.HARNESS,
          tier: env.TIER,
        },
      });
      process.exitCode = suite.failures > 0 ? 1 : 0;
    } else if (command === 'report') {
      const suites = readSuites(process.argv[3]);
      const previous =
        env.NOISE && existsSync(env.NOISE) ? readJson(env.NOISE) : {};
      const meta = {
        ...(suites[0]?.refs ?? {}),
        skipped: env.SKIPPED ? JSON.parse(env.SKIPPED) : [],
        noise: previous,
      };
      const report = renderReport(suites, meta);
      const file = process.argv[4] ?? 'report.md';
      writeFileSync(file, report);
      if (suites.some(suite => suite.aa)) {
        writeFileSync(
          join(dirname(file), 'noise.json'),
          `${JSON.stringify(noiseFile(suites, previous), null, 2)}\n`,
        );
      }
      if (env.GITHUB_STEP_SUMMARY)
        appendFileSync(env.GITHUB_STEP_SUMMARY, report);
    } else {
      throw new Error(
        `usage: v10-e2e-bench.mjs plan | matrix | suite <name> | report <dir> [report.md]`,
      );
    }
  } catch (error) {
    console.log(`::error::${error.message}`);
    process.exitCode = 1;
  }
}
