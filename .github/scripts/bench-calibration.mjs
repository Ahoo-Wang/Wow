/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { labelOf, num, spread } from './bench-common.mjs';

// A paired suite's calibration and its verdicts: the closed-loop search's ceiling, the open-loop probe that sets each
// arm's rate, the rounds' modes, the confidence of a ratio and what the report concludes from it. bench.mjs runs the
// suites and renders them; what a reader is told a ratio means is decided here.

/** The error kind of an operation the load generator did not send: its in-flight cap was full. */
export const OVERLOAD = 'generator-overload';

/**
 * The most operations outstanding per target, in both loops: the open loop's in-flight ceiling (the load generator's
 * InFlightCap.PER_TARGET, which it must equal) and the closed-loop search's top concurrency. With one ceiling a
 * saturation is never found at a concurrency the open loop cannot then hold (run 43327251175: the head saturated at
 * 2,048 clients, its calibrated rate then needed more than the open loop's 2,000 in flight, and the generator refused
 * 4.8% and 6.3% of two rounds' requests).
 */
export const IN_FLIGHT_PER_TARGET = 2048;

/** The share of the in-flight cap a probe may need (its rate × its p99) and still count as held. */
export const IN_FLIGHT_HEADROOM = 0.5;

export const DRIFT = 0.05;
// The closed loop reaches its best throughput only with deep queues, so `fraction` of it can be more than a minute
// of open loop sustains (the first runs on GitHub's 4-vCPU runners collapsed into timeouts at 80%). Each arm's
// rate is therefore calibrated in open loop first: a probe at the rate, and while it does not hold (probeHeld) the
// rate steps down by a quarter, at most 4 probes. The probe measures as long as a round warms up (30 s), so a queue
// that builds within a round's warm-up shows in it: a 20 s probe passed where the round then collapsed.
export const PROBE = { warmUp: 10, measure: 30, attempts: 4, step: 0.75 };

/** Whether an open-loop run at `rate` kept up: at most 1% failed and at least 97% of the rate completed. */
export function sustainable(m, rate) {
  const total = (m?.ok ?? 0) + (m?.failed ?? 0);
  return total > 0 && m.failed / total <= 0.01 && m.throughput >= 0.97 * rate;
}

/**
 * Whether an open-loop run at `rate` needed at most IN_FLIGHT_HEADROOM of its in-flight cap: by Little's law it holds
 * `rate × latency` outstanding, taken at its p99, so a round whose latency grows a little still fits under the cap.
 * True when the run recorded no cap or no p99.
 */
export function heldInFlight(m, rate) {
  if (!Number.isFinite(m?.inFlightCap) || !Number.isFinite(m?.p99)) return true;
  return (rate * m.p99) / 1000 <= IN_FLIGHT_HEADROOM * m.inFlightCap;
}

/** Whether a calibration probe at `rate` held: it kept up, within the in-flight headroom. */
export function probeHeld(m, rate) {
  return sustainable(m, rate) && heldInFlight(m, rate);
}

// bench.sh's default cap of 256 workers stopped the closed loop before V10 flattened, and so did 1024 on GitHub's
// runners (E1, E2 and E3), so the search goes to the open loop's ceiling (IN_FLIGHT_PER_TARGET, one target); the
// doubling stops on its own after two steps that gain less than 5%. A saturation found at the ceiling is a lower bound,
// marked `≥` (`capped`). Two arms whose saturations differ more than `outlier` times are probed again, the lower once:
// a search can stop early on a stalled step (large-mongo arm b found 202/s at concurrency 1 against arm a's 990/s at
// 128, 2026-10-28, run 40721764669, an A/A run of one build). The second search stands, and the first is kept beside
// it.
export const SATURATION = {
  warmUp: 3,
  step: 8,
  maxConcurrency: IN_FLIGHT_PER_TARGET,
  outlier: 3,
};

/** The arm of two saturations (id → `{throughput}`) to probe again: the lower, when they differ past the outlier. */
export function saturationOutlier(saturations, ids) {
  const [a, b] = ids.map(id => saturations[id]?.throughput);
  if (!(a > 0) || !(b > 0)) return undefined;
  if (Math.max(a, b) / Math.min(a, b) <= SATURATION.outlier) return undefined;
  return a < b ? ids[0] : ids[1];
}

/** What a row shows instead of a ratio that measured the load generator rather than the servers. */
export const GENERATOR_BOUND = 'invalid: generator-bound';

/** The share of its CPUs at which the load generator, not the servers, is what a run measured. */
export const GENERATOR_BUSY = 0.9;

/**
 * Whether a load generator that used `cores` of its `cpus` was the bottleneck: past GENERATOR_BUSY of them, what was
 * measured is the generator.
 */
export function generatorBound(cores, cpus) {
  return cpus > 0 && Number.isFinite(cores) && cores >= GENERATOR_BUSY * cpus;
}

/** Whether an arm's closed-loop saturation step was generator-bound. */
export function saturationGeneratorBound(suite, arm) {
  const s = suite.saturation?.[arm];
  return generatorBound(s?.generatorCores, s?.generatorCpus);
}

/** Whether either arm's saturation was generator-bound: the saturation ratio is then not the servers'. */
export function saturationRatioBound(suite) {
  return (suite.arms ?? []).some(arm =>
    saturationGeneratorBound(suite, arm.id),
  );
}

/**
 * Whether a run of either arm at the calibrated rates was generator-bound: its rates came from saturations or probes
 * the generator capped, so the calibrated pairs compare the generator, not the arms.
 */
export function calibratedGeneratorBound(suite) {
  return (suite.runs ?? []).some(
    run =>
      run.status === 'ok' &&
      modeOf(run) === CALIBRATED &&
      generatorBound(
        run.metrics?.loadGeneratorCores,
        run.metrics?.loadGeneratorCpus,
      ),
  );
}

/** The operations of a run the load generator did not send because its in-flight cap was full. */
export function refusedByGenerator(m) {
  return Object.entries(m?.errors ?? {})
    .filter(([kind]) => kind.endsWith(OVERLOAD))
    .reduce((sum, [, count]) => sum + count, 0);
}

/**
 * Whether the load generator refused more than 1% of a run's operations: the round measured the generator's ceiling,
 * not the node, so its seconds are left out of the confidence intervals.
 */
export function refusedRound(run) {
  const m = run.metrics;
  const total = (m?.ok ?? 0) + (m?.failed ?? 0);
  return total > 0 && refusedByGenerator(m) / total > 0.01;
}

// The rounds of a paired suite: `calibrated`, each arm at its own calibrated rate (throughput and cost compare;
// latencies are at unequal loads), then `equal`, both arms at the lower of the two calibrated rates (latencies
// compare).
export const CALIBRATED = 'calibrated';
export const EQUAL = 'equal';
export const modeOf = run => run.mode ?? CALIBRATED;

/** The lower of the arms' calibrated rates: the rate of the equal-rate rounds. */
export function equalRate(rates, arms) {
  const values = arms.map(arm => rates[arm.id]).filter(Number.isFinite);
  return values.length === arms.length ? Math.min(...values) : undefined;
}

/** Whether another round is due: two runs of one arm differ by more than DRIFT in throughput or p99. */
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

export const median = values => {
  const sorted = values.filter(Number.isFinite).sort((x, y) => x - y);
  if (sorted.length === 0) return undefined;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
};

// Significance (D224 note ②): a ratio counts only beyond the suite's noise. An A/A run (`aa`) puts the head against
// itself; the largest deviation of its pair ratios from 1 is the suite's threshold per metric (at least NOISE_FLOOR),
// which the report writes as noise.json, to commit as test/benchmarks/noise.json for later runs to read. The
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

/**
 * The bootstrap intervals of a paired suite's ratios in one mode, per significance metric. A round the load generator
 * refused (refusedRound) is left out: it measured the generator's ceiling.
 */
export function confidence(suite, mode) {
  if (suite.arms?.length !== 2) return {};
  const [a, b] = suite.arms.map(arm => arm.id);
  const runs = suite.runs.filter(
    run => modeOf(run) === mode && run.status === 'ok' && !refusedRound(run),
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
 * ratio (the head against itself at one rate, AA_ROUNDS rounds), at least NOISE_FLOOR. Null when the suite measured
 * no equal-rate pair: a failed suite has no noise, and the floor would make every later ratio significant.
 */
export function noiseOf(suite) {
  const intervals = confidence(suite, EQUAL);
  if (!SIGNIFICANCE_METRICS.some(([metric]) => intervals[metric])) return null;
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

/**
 * What each arm completed at its own calibrated rate: in an open loop the throughput is the offered rate whenever
 * the node keeps up, so the head/base ratio there is the ratio of their calibrations, not of what they did. Each arm
 * is judged against its own rate instead: the median completed share of its calibrated rounds (refused rounds left
 * out), whether that kept up (at least 97%), and the rate's share of its saturation.
 */
export function keptUp(suite, arm) {
  const rate = suite.rates?.[arm];
  const runs = (suite.runs ?? []).filter(
    run =>
      run.arm === arm &&
      run.status === 'ok' &&
      modeOf(run) === CALIBRATED &&
      !refusedRound(run),
  );
  const completed = median(runs.map(run => run.metrics.throughput / rate));
  if (!(rate > 0) || !Number.isFinite(completed)) return undefined;
  return {
    rate,
    completed,
    held: completed >= 0.97,
    share: suite.calibration?.[arm]?.share,
  };
}

/** The shares of their saturations the arms ran at, when they differ by more than 5 points, in words. */
export function sharesText(suite) {
  const shares = (suite.arms ?? []).map(arm => [
    arm.id,
    suite.calibration?.[arm.id]?.share,
  ]);
  if (shares.some(([, share]) => !Number.isFinite(share))) return undefined;
  const values = shares.map(([, share]) => share);
  if (Math.max(...values) - Math.min(...values) <= 0.05) return undefined;
  const parts = shares.map(
    ([id, share]) =>
      `${id} at ${percent(share)}${suite.calibration[id].sustainable ? '' : ' (no probe held)'}`,
  );
  return `${labelOf(suite)}: the arms ran at different shares of their saturations (${parts.join(', ')}), so their calibrated rounds compare different loads; the equal-rate rounds compare like with like.`;
}

const ratio = value => (Number.isFinite(value) ? value.toFixed(3) : '-');
const percent = value => `${Math.round(value * 100)}%`;

/** The saturation ratio of a paired suite as a measurement: closed loop, no interval. */
function saturationRow(suite, a, b) {
  const sa = suite.saturation?.[a];
  const sb = suite.saturation?.[b];
  const searches = suite.saturations
    ? `${Math.min(...[a, b].map(id => suite.saturation?.[id]?.samples ?? 0))} searches an arm (median), no interval`
    : 'one search an arm, no interval';
  if (saturationRatioBound(suite)) return ['-', GENERATOR_BOUND];
  if (!(sa?.throughput > 0) || !(sb?.throughput > 0)) return ['-', 'no data'];
  const capped = sa.capped || sb.capped;
  return [
    `${capped ? '~' : ''}${ratio(sb.throughput / sa.throughput)}`,
    capped
      ? `capped: an arm reached the ceiling of ${SATURATION.maxConcurrency} clients, a lower bound`
      : searches,
  ];
}

/** An arm's completed share of its calibrated rate, its share of its saturation, in a cell. */
const keptText = (id, kept) =>
  kept
    ? `${id} ${percent(kept.completed)} of ${num(kept.rate, 0)}/s${Number.isFinite(kept.share) ? ` (${percent(kept.share)} of saturation)` : ''}`
    : `${id} -`;

/**
 * The report's significance section over the paired suites (two arms, not A/A), with `noise` their A/A thresholds by
 * suite label: per suite, throughput as the closed-loop saturation ratio (a measurement), whether each arm kept up at
 * its own calibrated rate, and p50 and p99 at the equal rate against the noise. Only the latencies count as beyond
 * noise; the throughput at calibrated rates is not compared between the arms.
 */
export function significanceSection(suites, noise, noiseLabel) {
  const significant = suites.filter(
    s => s.kind === 'paired' && s.arms.length === 2 && !s.aa,
  );
  if (significant.length === 0) return [];
  const rows = [];
  const notes = [];
  let beyond = 0;
  let latencies = 0;
  let calibrated = 0;
  for (const suite of significant) {
    const thresholds = noise[noiseLabel(suite)];
    if (thresholds) calibrated++;
    const [a, b] = suite.arms.map(arm => arm.id);
    const [saturation, measured] = saturationRow(suite, a, b);
    rows.push(
      `| ${labelOf(suite)} | ${b}/${a} | saturation | closed loop | ${saturation} | - | ${measured} |`,
    );
    const kept = [a, b].map(id => keptUp(suite, id));
    const fell = [a, b].filter((id, at) => kept[at] && !kept[at].held);
    const keptVerdict = calibratedGeneratorBound(suite)
      ? GENERATOR_BOUND
      : kept.some(k => !k)
        ? 'no data'
        : fell.length > 0
          ? `**${fell.join(' and ')} fell behind**`
          : 'both kept up';
    rows.push(
      `| ${labelOf(suite)} | ${b}, ${a} | kept up | ${CALIBRATED} | ${keptText(b, kept[1])} · ${keptText(a, kept[0])} | - | ${keptVerdict} |`,
    );
    const shares = sharesText(suite);
    if (shares) notes.push(shares);
    for (const metric of ['p50', 'p99']) {
      const interval = confidence(suite, EQUAL)[metric];
      const result = verdict(interval, thresholds?.[metric]);
      latencies++;
      if (result === 'higher' || result === 'lower') beyond++;
      rows.push(
        `| ${labelOf(suite)} | ${b}/${a} | ${metric} | ${EQUAL} | ${interval ? `${ratio(interval.point)} [${ratio(interval.lo)}–${ratio(interval.hi)}]` : '-'} | ` +
          `${thresholds?.[metric] ? `±${num(thresholds[metric] * 100)}%` : '-'} | ${result === 'higher' || result === 'lower' ? `**${result}**` : result} |`,
      );
    }
  }
  return [
    '## Significance',
    '',
    `Beyond noise: ${beyond} of ${latencies} latency ratios; ${calibrated} of ${significant.length} suites have an A/A noise threshold (test/benchmarks/noise.json). A latency ratio counts when its 95% bootstrap interval (over the measured seconds of the equal-rate rounds, rounds the load generator refused left out) clears the suite's threshold; without one it is \`uncalibrated\`. Throughput is the closed-loop saturation ratio, a measurement without an interval; at the calibrated rates each arm is judged against its own rate (in an open loop the throughput is the offered rate), never against the other arm.`,
    '',
    '| Suite | Arms | Metric | Rounds | Ratio [95% CI] or share | Noise | Verdict |',
    '| --- | --- | --- | --- | --- | --- | --- |',
    ...rows,
    '',
    ...(notes.length > 0 ? [...notes.map(note => `- ${note}`), ''] : []),
  ];
}

/**
 * The anomalies of a run's signals that make it no capacity figure, `{ kind, detail }` each: a tail that read the
 * store after a gap or an overflow, and a store call given up at its timeout (a snapshot load past
 * `wow.snapshot.load-timeout`, its state replayed from the log; `storeFellBack` keyed `capability/reason`).
 */
export function signalAnomalies(signals) {
  const checks = [
    [
      'tail read the store',
      signals?.tailRead,
      key => key === 'gap' || key === 'overflow',
    ],
    [
      'store call given up',
      signals?.storeFellBack,
      key => key.endsWith('/timed-out'),
    ],
  ];
  return checks.flatMap(([kind, counts, flagged]) => {
    const hit = Object.entries(counts ?? {}).filter(
      ([key, n]) => flagged(key) && n > 0,
    );
    return hit.length === 0
      ? []
      : [{ kind, detail: hit.map(([key, n]) => `${key}=${n}`).join(', ') }];
  });
}
