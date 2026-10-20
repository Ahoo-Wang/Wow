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
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// The steps of v10-e2e-bench.yml, the timed end-to-end benchmark of decision
// D224 (wow-benchmarks/e2e/README.md) on GitHub's runners:
//
//   node .github/scripts/v10-e2e-bench.mjs plan            (plan job)
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

// The arms: a version on a backend, and the server properties it runs with
// (handed to the servers as SPRING_APPLICATION_JSON, which bench.sh's servers
// inherit).
const NINE_X = { id: '9x', version: '9x', backend: 'mongo' };
const V10_MONGO = { id: 'v10', version: 'v10', backend: 'mongo' };
const V10_PG = { id: 'v10-pg', version: 'v10', backend: 'postgres' };
const withProps = (arm, id, props) => ({ ...arm, id, props });

// The E3 latency suspects on MongoDB (H1 to H5 of the E3 analysis): a rate
// sweep, then one key toggled at a time at the middle rate.
const DIAG_RATE = 20;

// kind `paired`: saturation of each arm, then rounds of one run per arm.
// The ratio is arms[1] / arms[0]. `sharedRate`: both arms run at `fraction`
// of the first arm's saturation (the same offered load), not each its own.
// kind `fixed`: the listed runs once each, at fixed rates, no saturation.
export const SUITES = {
  'e1-mongo-uniform': {
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [NINE_X, V10_MONGO],
    answers:
      'E1 write, uniform keys: V10 on MongoDB against 9.x on MongoDB, throughput, latency and CPU at the same share of each saturation.',
  },
  'e1-mongo-zipf': {
    kind: 'paired',
    scenario: 'E1',
    distribution: 'zipf',
    arms: [NINE_X, V10_MONGO],
    answers:
      'E1 write, Zipf keys (hot accounts, lane and cache contention): V10 on MongoDB against 9.x.',
  },
  'e1-pg-uniform': {
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [V10_PG],
    answers:
      'E1 write on V10 with PostgreSQL, reported on its own (D224: never compared with 9.x); run-to-run spread only.',
  },
  'e2-mongo': {
    kind: 'paired',
    scenario: 'E2',
    distribution: 'uniform',
    arms: [NINE_X, V10_MONGO],
    answers: 'E2 read-your-write: V10 on MongoDB against 9.x.',
  },
  'e3-mongo': {
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [NINE_X, V10_MONGO],
    answers:
      "E3 saga chain (completion: the target's Entry stored): V10 on MongoDB against 9.x.",
  },
  'e3-pg': {
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [V10_PG],
    answers: 'E3 saga chain on V10 with PostgreSQL, reported on its own.',
  },
  'e3-mongo-diag': {
    kind: 'fixed',
    scenario: 'E3',
    distribution: 'uniform',
    runs: [
      { arm: V10_MONGO, rate: 5 },
      { arm: V10_MONGO, rate: DIAG_RATE },
      { arm: V10_MONGO, rate: 40 },
      {
        arm: withProps(V10_MONGO, 'v10-queue-off', {
          'wow.log.append-queue.enabled': false,
        }),
        rate: DIAG_RATE,
      },
      {
        arm: withProps(V10_MONGO, 'v10-gap-wait-20ms', {
          'wow.log.handoff.gap-wait': '20ms',
        }),
        rate: DIAG_RATE,
      },
      {
        arm: withProps(V10_MONGO, 'v10-max-poll-5s', {
          'wow.log.max-poll-interval': '5s',
        }),
        rate: DIAG_RATE,
      },
      { arm: NINE_X, rate: DIAG_RATE },
    ],
    answers:
      'Why V10 E3 on MongoDB is slow: a rate sweep (a median falling at 5/s means load, a flat one a fixed delay), then the append queue off, a 20 ms handoff gap wait and a 5 s longest poll, one at a time at 20/s; 9.x at 20/s as the reference.',
  },
  'pg-append-queue': {
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    sharedRate: true,
    arms: [
      withProps(V10_PG, 'queue-off', { 'wow.log.append-queue.enabled': false }),
      withProps(V10_PG, 'queue-on', { 'wow.log.append-queue.enabled': true }),
    ],
    answers:
      "Whether PostgreSQL's append queue should be on by default (D248): saturation with it off and on, then both at the same offered load.",
  },
  'cliff-e3-pg': cliff(
    V10_PG,
    'E3 on V10 with PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).',
  ),
  'cliff-e3-mongo': cliff(
    V10_MONGO,
    'E3 on V10 with MongoDB in fixed open-loop steps, as cliff-e3-pg.',
  ),
  'cliff-e3-9x': cliff(
    NINE_X,
    'E3 on 9.x with MongoDB in the same steps, the reference (no signals: 9.x has no such observer).',
  ),
};

// kind `steps` (the cliff suites): one arm in open loop at rising fixed rates, each step on fresh containers, a
// minute measured, until two steps in a row are not sustained. A V10 arm counts its overload and fallback signals
// (BenchSignals in :e2e-bench-v10, `bench.signals.enabled`) and logs them, which the step reads from the server log.
export const CLIFF = {
  rates: [50, 100, 200, 300, 400, 500],
  warmUp: 10,
  measure: 60,
};

function cliff(arm, answers) {
  const signals =
    arm.version === 'v10' ? { 'bench.signals.enabled': true } : undefined;
  return {
    kind: 'steps',
    scenario: 'E3',
    distribution: 'uniform',
    arm: signals ? withProps(arm, arm.id, signals) : arm,
    answers,
  };
}

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
  suites: 'all',
  warm_up: '20',
  measure: '40',
  repeats: '2',
  fraction: '0.8',
  baseline_ref: 'v10-9x-baseline',
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

/** The dispatch inputs, checked; `suites` is `all` or names separated by commas or spaces. */
export function resolveInputs({ suites, warmUp, measure, repeats, fraction }) {
  const names =
    !suites || suites.trim() === 'all'
      ? Object.keys(SUITES)
      : [...new Set(suites.split(/[\s,]+/).filter(Boolean))];
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
  };
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
  return {
    throughput,
    ok: results.ok ?? 0,
    failed: results.failed ?? 0,
    errors: results.errors ?? {},
    p50: latency.p50,
    p95: latency.p95,
    p99: latency.p99,
    p999: latency.p999,
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

function runTable(runs) {
  const lines = [
    '| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | CPU ms/op | Errors |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of runs) {
    if (run.status !== 'ok') {
      lines.push(
        `| ${run.name} | ${run.arm} | ${num(run.rate)}/s | **${run.status}** | | | | | | | | | | | |`,
      );
      continue;
    }
    const m = run.metrics;
    const containers = Object.entries(m.containers)
      .map(([name, percent]) => `${name} ${percent.toFixed(0)}%`)
      .join(' · ');
    lines.push(
      `| ${run.name} | ${run.arm} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${m.ok} | ${m.failed} | ` +
        `${ms(m.p50)} | ${ms(m.p95)} | ${ms(m.p99)} | ${ms(m.p999)} | ${ms(m.worstSecondP99)} | ` +
        `${num(m.serverCores, 2)} cores | ${containers || '-'} | ${num(m.cpuMsPerOp, 2)} | ${errorsText(m.errors)} |`,
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

/** The Markdown summary of one suite. */
export function renderSuite(suite) {
  const definition = SUITES[suite.name];
  const lines = [
    `## ${suite.name}`,
    '',
    definition?.answers ?? '',
    '',
    `${suite.scenario}, ${suite.distribution} keys; warm-up ${suite.warmUp} s, measured ${suite.measure} s` +
      (suite.kind === 'paired'
        ? `, ${suite.fraction * 100}% of saturation, ${suite.repeats} round(s)`
        : '') +
      (suite.extraRound
        ? ' + 1 (two runs of one arm differed by more than 5%)'
        : '') +
      `. Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores.`,
    '',
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
  lines.push(...runTable(suite.runs), '');
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

/** Runs bench.sh with `args`, the arm's properties in SPRING_APPLICATION_JSON; true when it succeeded. */
function bench(benchPath, args, props) {
  const env = { ...process.env };
  delete env.SPRING_APPLICATION_JSON;
  if (props && Object.keys(props).length > 0)
    env.SPRING_APPLICATION_JSON = JSON.stringify(props);
  console.log(
    `::group::bench.sh ${args.join(' ')}${props ? ` (${propsText(props)})` : ''}`,
  );
  const result = spawnSync(benchPath, args, { stdio: 'inherit', env });
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
    refs = {},
  } = options;
  mkdirSync(out, { recursive: true });
  const arms =
    definition.kind === 'paired'
      ? definition.arms
      : definition.kind === 'steps'
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
  const common = arm => [
    `--version=${arm.version}`,
    `--backend=${arm.backend}`,
    `--scenario=${definition.scenario}`,
    `--distribution=${definition.distribution}`,
  ];
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
        ...common(arm),
        `--rate=${rate.toFixed(1)}`,
        `--warm-up=${phase.warmUp}`,
        `--measure=${phase.measure}`,
        `--label=${name}-${runName}`,
        `--out=${dir}`,
      ],
      arm.props,
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
    };
    if (ok && existsSync(file)) {
      run.status = 'ok';
      run.metrics = metrics(readJson(file));
      const log = join(dir, 'node-1.log');
      if (existsSync(log)) {
        const signals = lastSignals(readFileSync(log, 'utf8'));
        if (signals) run.signals = signals;
      }
    } else {
      suite.failures++;
    }
    phase.into.push(run);
    save();
    return run;
  };

  if (definition.kind === 'paired') {
    for (const arm of arms) {
      const dir = join(out, `saturation-${arm.id}`);
      const ok = bench(
        benchPath,
        [
          'saturate',
          ...common(arm),
          `--warm-up=${SATURATION.warmUp}`,
          `--step=${SATURATION.step}`,
          `--max-concurrency=${SATURATION.maxConcurrency}`,
          `--label=${name}-saturation-${arm.id}`,
          `--out=${dir}`,
        ],
        arm.props,
      );
      const file = join(dir, 'saturation.json');
      const saturation = ok && existsSync(file) ? readJson(file) : undefined;
      if (saturation?.saturationThroughput > 0) {
        suite.saturation[arm.id] = {
          status: 'ok',
          throughput: saturation.saturationThroughput,
          concurrency: saturation.saturationConcurrency,
          capped: saturation.saturationConcurrency >= SATURATION.maxConcurrency,
        };
      } else {
        suite.saturation[arm.id] = { status: 'failed' };
        suite.failures++;
      }
      save();
    }
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
      for (let index = 0; index < repeats; index++)
        round(index, EQUAL, () => equal, 'e');
    } else if (definition.sharedRate && suite.rates[arms[0].id]) {
      suite.equalRate = suite.rates[arms[0].id];
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

/** The combined report of every suite. */
export function renderReport(suites, meta = {}) {
  const lines = [
    '# V10 end-to-end benchmark (D224) on GitHub Actions',
    '',
    `Baseline (9.x): \`${meta.baselineRef ?? '?'}\` ${meta.baselineSha ?? ''}; head (V10): \`${meta.headRef ?? '?'}\` ${meta.headSha ?? ''}; ` +
      `harness and load generator from ${meta.harness ?? '?'}.`,
    '',
    "Each suite ran on one runner of its own, so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair: median (min–max, n pairs). Lower is better except throughput.",
    '',
  ];
  if (suites.length === 0) {
    lines.push('No suite left results; see the jobs.', '');
    return `${lines.join('\n')}\n`;
  }
  lines.push(
    '## Runners',
    '',
    '| Suite | CPU | Cores | Memory |',
    '| --- | --- | --- | --- |',
  );
  for (const suite of suites) {
    lines.push(
      `| ${suite.name} | ${suite.runner?.cpu ?? '?'} | ${suite.runner?.cores ?? '?'} | ${suite.runner?.memoryGb ?? '?'} GB |`,
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
        `| ${suite.name} | ${arm.id} | ${saturationText(s)} | ${s.concurrency ?? '-'} | ${num(suite.rates?.[arm.id])}/s | ${shareText(suite, arm.id)} |`,
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
          `| ${suite.name} | ${b}/${a} | ${label} | ${saturation} | ` +
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
        `| ${suite.name} | ${runs.length} | ${cell('throughput', v => `${num(v)}/s`)} | ${cell('p50', ms)} | ${cell('p99', ms)} | ` +
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
      lines.push('', `### ${suite.name}`, '', ...cliffTable(suite));
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
  try {
    if (command === 'plan') {
      const config =
        env.CONFIG && existsSync(env.CONFIG) ? readJson(env.CONFIG) : undefined;
      const settings = runSettings({
        event: env.EVENT,
        config,
        inputs: {
          suites: env.SUITES,
          warm_up: env.WARM_UP,
          measure: env.MEASURE,
          repeats: env.REPEATS,
          fraction: env.FRACTION,
          baseline_ref: env.BASELINE_REF,
        },
      });
      if (config && env.EVENT === 'push')
        console.log(`bench-run.json: ${JSON.stringify(config)}`);
      const inputs = resolveInputs({
        suites: settings.suites,
        warmUp: settings.warm_up,
        measure: settings.measure,
        repeats: settings.repeats,
        fraction: settings.fraction,
      });
      output({
        'baseline-ref': settings.baseline_ref,
        suites: JSON.stringify(inputs.suites),
        'warm-up': inputs.warmUp,
        measure: inputs.measure,
        repeats: inputs.repeats,
        fraction: inputs.fraction,
      });
    } else if (command === 'suite') {
      const inputs = resolveInputs({
        suites: process.argv[3],
        warmUp: env.WARM_UP,
        measure: env.MEASURE,
        repeats: env.REPEATS,
        fraction: env.FRACTION,
      });
      const suite = runSuite(inputs.suites[0], {
        benchPath: env.BENCH,
        out: env.OUT,
        ...inputs,
        refs: {
          baselineRef: env.BASELINE_REF,
          baselineSha: env.BASELINE_SHA,
          headRef: env.HEAD_REF,
          headSha: env.HEAD_SHA,
          harness: env.HARNESS,
        },
      });
      process.exitCode = suite.failures > 0 ? 1 : 0;
    } else if (command === 'report') {
      const suites = readSuites(process.argv[3]);
      const meta = suites[0]?.refs ?? {};
      const report = renderReport(suites, meta);
      writeFileSync(process.argv[4] ?? 'report.md', report);
      if (env.GITHUB_STEP_SUMMARY)
        appendFileSync(env.GITHUB_STEP_SUMMARY, report);
    } else {
      throw new Error(
        `usage: v10-e2e-bench.mjs plan | suite <name> | report <dir> [report.md]`,
      );
    }
  } catch (error) {
    console.log(`::error::${error.message}`);
    process.exitCode = 1;
  }
}
