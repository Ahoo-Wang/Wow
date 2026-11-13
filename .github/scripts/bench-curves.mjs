/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import {
  GENERATOR_BOUND,
  generatorBound,
  median,
} from './bench-calibration.mjs';
import {
  anomalyTable,
  bench,
  labelOf,
  num,
  readJson,
  roundOrder,
  spread,
} from './bench-common.mjs';

// The closed-loop curve suites (group `curve`): head against base at fixed client counts, each arm swept SWEEPS
// times in turn (ABBA, each sweep on fresh stores, seeded and primed), every step 10 s warm-up and 30 s measured.
// A step's ratio is the median of the head's sweeps over the base's, and its band the A/A spread of each arm's own
// sweeps at that step (the larger arm's): a throughput claim rests on per-step ratios with bands, not on one search's
// peak, which two readings of a single search got wrong. bench.mjs dispatches here by a definition's `curve`.

/** The curve's steps and timing: the clients of each step, the sweeps an arm, a step's warm-up and measured seconds. */
export const CURVE = {
  concurrencies: [16, 32, 64, 128, 256, 512],
  sweeps: 4,
  warmUp: 10,
  step: 30,
  // A failing step ends once it measured this long (the load generator's stopsEarly), with its failure mix.
  failAfter: 10,
};

/** The curve suites on the head's and base's arms of each store, E1 and E3. */
export function curveSuites({ BASE_PG, HEAD_PG, BASE_MONGO, HEAD_MONGO }) {
  const suite = (scenario, arms, store) => ({
    group: 'curve',
    kind: 'paired',
    scenario,
    distribution: 'uniform',
    arms,
    curve: CURVE,
    answers: `${scenario} on ${store}, head against base, as a curve: throughput and p99 at ${CURVE.concurrencies.join(', ')} clients, each arm swept ${CURVE.sweeps} times in turn (ABBA), each step's ratio against the A/A band of the arms' own sweeps.`,
  });
  return {
    'e1-pg-curve': suite('E1', [BASE_PG, HEAD_PG], 'PostgreSQL'),
    'e1-mongo-curve': suite('E1', [BASE_MONGO, HEAD_MONGO], 'MongoDB'),
    'e3-pg-curve': suite('E3', [BASE_PG, HEAD_PG], 'PostgreSQL'),
    'e3-mongo-curve': suite('E3', [BASE_MONGO, HEAD_MONGO], 'MongoDB'),
  };
}

/** bench.sh's arguments of one sweep into [dir], after the suite's own ([common]). */
export function curveArgs(common, curve, label, dir) {
  return [
    'saturate',
    ...common,
    `--concurrencies=${curve.concurrencies.join(',')}`,
    `--warm-up=${curve.warmUp}`,
    `--step=${curve.step}`,
    `--fail-after=${curve.failAfter}`,
    `--label=${label}`,
    `--out=${dir}`,
  ];
}

/** A sweep's steps from its saturation.json: each step's clients, throughput, p50, p99, failures and generator cores. */
export function curveSteps(saturation) {
  return (saturation?.steps ?? []).map(step => ({
    clients: step.concurrency,
    throughput: step.throughput,
    p50Ms: step.p50Ms,
    p99Ms: step.p99Ms,
    ok: step.ok ?? 0,
    failed: Object.values(step.errors ?? {}).reduce((a, b) => a + b, 0),
    generatorCores: step.generatorCores,
  }));
}

/**
 * Runs a curve suite into [suite]: [definition]'s sweeps an arm in turn (ABBA), each through bench.sh's `saturate`
 * with the suite's arguments ([common]) and the arm's properties ([propsOf]).
 */
export function runCurve(
  definition,
  suite,
  { benchPath, out, name, arms, common, propsOf, save },
) {
  const curve = definition.curve;
  suite.curve = {
    ...curve,
    sweeps: Object.fromEntries(arms.map(arm => [arm.id, []])),
  };
  for (let index = 0; index < curve.sweeps; index++) {
    for (const arm of roundOrder(arms, index)) {
      const key = `${arm.id}-${index + 1}`;
      const dir = join(out, `curve-${key}`);
      const status = bench(
        benchPath,
        curveArgs(common(arm), curve, `${name}-curve-${key}`, dir),
        propsOf(arm),
      );
      const file = join(dir, 'saturation.json');
      const saturation =
        status === 0 && existsSync(file) ? readJson(file) : undefined;
      const sweep = saturation
        ? {
            status: 'ok',
            key,
            steps: curveSteps(saturation),
            generatorCpus: saturation.availableProcessors,
          }
        : { status: 'failed', key, steps: [] };
      if (sweep.status === 'failed') suite.failures++;
      suite.curve.sweeps[arm.id].push(sweep);
      save();
    }
  }
}

/** The share of a step's operations that failed. */
const failedShare = step =>
  step.ok + step.failed > 0 ? step.failed / (step.ok + step.failed) : 0;

/**
 * A curve suite's steps: per client count, each arm's sweeps' throughputs (a step that failed more than 1% is left out
 * of its arm's values, and counted), the head's median over the base's for throughput and p99, the band (the larger
 * arm's A/A spread of its own sweeps' throughputs at the step), the ratio's range (the lowest head over the highest
 * base to the highest over the lowest), and the verdict. A step any sweep of either arm ran generator-bound measured
 * the generator: invalid, with no ratio. Otherwise an arm is higher only when the range clears the band, level within
 * it; when one arm has two clean sweeps and the other fewer, the verdict names the arm that failed the step and in how
 * many sweeps; with neither, undetermined.
 */
export function curveRows(suite) {
  const [a, b] = suite.arms.map(arm => arm.id);
  const sweeps = suite.curve?.sweeps ?? {};
  return (suite.curve?.concurrencies ?? []).map(clients => {
    const at = id =>
      (sweeps[id] ?? []).flatMap(sweep => {
        const step = sweep.steps.find(found => found.clients === clients);
        return step ? [{ ...step, generatorCpus: sweep.generatorCpus }] : [];
      });
    const [base, head] = [at(a), at(b)];
    const [cleanBase, cleanHead] = [base, head].map(steps =>
      steps.filter(step => failedShare(step) <= 0.01),
    );
    const throughput = steps => steps.map(step => step.throughput);
    const p99 = steps => steps.map(step => step.p99Ms);
    const busiest = [...base, ...head].reduce(
      (most, step) =>
        (step.generatorCores ?? 0) > (most?.generatorCores ?? 0) ? step : most,
      undefined,
    );
    const bound = [...base, ...head].some(step =>
      generatorBound(step.generatorCores, step.generatorCpus),
    );
    const both = !bound && cleanBase.length > 0 && cleanHead.length > 0;
    const enough = cleanBase.length >= 2 && cleanHead.length >= 2;
    const band = enough
      ? Math.max(spread(throughput(cleanBase)), spread(throughput(cleanHead)))
      : undefined;
    const range = both
      ? {
          lo:
            Math.min(...throughput(cleanHead)) /
            Math.max(...throughput(cleanBase)),
          hi:
            Math.max(...throughput(cleanHead)) /
            Math.min(...throughput(cleanBase)),
        }
      : undefined;
    // An arm short of two clean sweeps where the other has them failed the step: over 1% failed, or never reached it.
    const failedIn = (id, clean) => (sweeps[id]?.length ?? 0) - clean.length;
    const failing = [
      [a, cleanBase, cleanHead],
      [b, cleanHead, cleanBase],
    ].find(
      ([id, own, other]) =>
        own.length < 2 && other.length >= 2 && failedIn(id, own) > 0,
    );
    const verdict = bound
      ? GENERATOR_BOUND
      : failing
        ? `${failing[0]} failed the step in ${failedIn(failing[0], failing[1])} of ${sweeps[failing[0]].length} sweeps`
        : !enough
          ? 'undetermined (fewer than two clean sweeps an arm)'
          : range.lo > 1 + band
            ? `${b} higher`
            : range.hi < 1 - band
              ? `${a} higher`
              : 'level (within the A/A band)';
    return {
      clients,
      base: throughput(cleanBase),
      head: throughput(cleanHead),
      ratio: both
        ? median(throughput(cleanHead)) / median(throughput(cleanBase))
        : undefined,
      range,
      band,
      verdict,
      p99Ratio: both
        ? median(p99(cleanHead)) / median(p99(cleanBase))
        : undefined,
      failed: base.length + head.length - cleanBase.length - cleanHead.length,
      generatorCores: busiest?.generatorCores ?? 0,
      generatorCpus: busiest?.generatorCpus,
    };
  });
}

const ratioText = value => (Number.isFinite(value) ? value.toFixed(3) : '-');
const perSecond = values =>
  values.map(value => `${num(value, 0)}/s`).join(', ') || '-';

/** A curve suite's table: one row a client count. */
export function curveTable(suite) {
  const [a, b] = suite.arms.map(arm => arm.id);
  return [
    `| Clients | ${a} throughput (sweeps) | ${b} throughput (sweeps) | ${b}/${a} (median) | Range | A/A band | Verdict | p99 ${b}/${a} | Steps over 1% failed | Generator cores (max, of its CPUs) |`,
    '| ---: | --- | --- | ---: | --- | ---: | --- | ---: | ---: | ---: |',
    ...curveRows(suite).map(
      row =>
        `| ${row.clients} | ${perSecond(row.base)} | ${perSecond(row.head)} | ${ratioText(row.ratio)} | ${row.range ? `${ratioText(row.range.lo)}–${ratioText(row.range.hi)}` : '-'} | ${Number.isFinite(row.band) ? `±${num(row.band * 100)}%` : '-'} | ${row.verdict} | ${ratioText(row.p99Ratio)} | ${row.failed} | ${num(row.generatorCores, 2)}${row.generatorCpus > 0 ? ` of ${row.generatorCpus}` : ''} |`,
    ),
  ];
}

/** A curve suite's anomalies: a failed sweep, a step that failed more than 1%, a step the load generator bound. */
export function curveAnomalies(suite) {
  const rows = [];
  const row = (run, context, kind, detail) =>
    rows.push({ suite: labelOf(suite), run, context, kind, detail });
  for (const [arm, sweeps] of Object.entries(suite.curve?.sweeps ?? {})) {
    for (const sweep of sweeps) {
      const run = `curve-${sweep.key}`;
      if (sweep.status !== 'ok') {
        row(run, arm, 'run failed', 'bench.sh failed; see the job log');
        continue;
      }
      for (const step of sweep.steps) {
        const context = `${step.clients} clients`;
        if (failedShare(step) > 0.01) {
          row(
            run,
            context,
            'error spike',
            `${step.failed} of ${step.ok + step.failed} failed: left out of the step's ratio`,
          );
        }
        if (generatorBound(step.generatorCores, sweep.generatorCpus)) {
          row(
            run,
            context,
            'load generator saturated',
            `${num(step.generatorCores, 2)} of ${sweep.generatorCpus} cores: the step measured the generator, so its row has no ratio`,
          );
        }
      }
    }
  }
  return rows;
}

/** The Markdown summary of a curve suite, [definition] its SUITES entry. */
export function renderCurve(suite, definition) {
  const sweeps = Object.values(suite.curve?.sweeps ?? {})[0]?.length ?? 0;
  const lines = [
    `## ${labelOf(suite)}`,
    '',
    definition?.answers ?? '',
    '',
    `${suite.scenario}, ${suite.distribution} keys over ${suite.accounts ?? '?'} accounts; ${sweeps} sweeps an arm in turn (ABBA), each on fresh stores; every step ${suite.curve?.warmUp} s warm-up and ${suite.curve?.step} s measured. Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores, ${suite.runner?.jdk ?? 'JDK not recorded'}.`,
    '',
    "A step's ratio counts as higher or lower only when its range clears the A/A band at that step. A closed loop's p99 understates a saturated server's (coordinated omission): it shows where the knee is, never a result.",
    '',
    ...curveTable(suite),
    '',
  ];
  const found = anomalyTable(curveAnomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} sweep(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}

/** The combined report's curve section: each curve suite's table. */
export function curveSection(suites) {
  const curves = suites.filter(suite => suite.curve);
  if (curves.length === 0) return [];
  return [
    '## Curves (closed loop, ABBA)',
    '',
    ...curves.flatMap(suite => [
      `### ${labelOf(suite)}`,
      '',
      ...curveTable(suite),
      '',
    ]),
  ];
}

// The closed-loop samples of the jdk-saturation suites, ABBA searches compared by their A/A spread as a curve's steps are.

/**
 * A paired suite's closed-loop samples (`saturations`), the second arm's against the first's: each arm's throughputs in
 * order, the ratio of their medians, and from the lowest second over the highest first to the highest over the lowest.
 * The A/A spread is the larger of the two arms' (spread); a range within it is level (ratioVerdict).
 */
export function saturationSamples(suite) {
  if (!suite.saturations || suite.arms.length !== 2) return undefined;
  const [a, b] = suite.arms.map(arm => arm.id);
  const values = id =>
    (suite.saturations[id] ?? [])
      .filter(sample => sample.status === 'ok')
      .map(sample => sample.throughput);
  const [first, second] = [values(a), values(b)];
  if (first.length === 0 || second.length === 0) return undefined;
  const noise =
    first.length >= 2 && second.length >= 2
      ? Math.max(spread(first), spread(second))
      : undefined;
  const range = {
    lo: Math.min(...second) / Math.max(...first),
    hi: Math.max(...second) / Math.min(...first),
    bound: '',
  };
  return {
    ids: [a, b],
    first,
    second,
    median: median(second) / median(first),
    range,
    noise,
    verdict: ratioVerdict(range, noise, [b, a]),
  };
}

/**
 * What a ratio of two arms' peaks (peakRatio) says beyond the A/A spread `noise`: one arm higher, level, or
 * undetermined. A lower-bound ratio (`≥`) can show the first arm higher only, an upper bound (`≤`) the second.
 */
export function ratioVerdict(ratio, noise, ids) {
  if (!ratio) return '-';
  if (!Number.isFinite(noise)) return 'no A/A (one session an arm)';
  const first = ratio.lo > 1 + noise;
  const second = ratio.hi < 1 - noise;
  if (ratio.bound === '~') return 'undetermined (both lower bounds)';
  if (ratio.bound === '≥')
    return first ? `${ids[0]} higher` : 'undetermined (a lower bound)';
  if (ratio.bound === '≤')
    return second ? `${ids[1]} higher` : 'undetermined (an upper bound)';
  if (first) return `${ids[0]} higher`;
  if (second) return `${ids[1]} higher`;
  return 'level (within the A/A spread)';
}

/** The jdk-saturation suites' samples table: each suite's two arms' searches, ratio, range, spread and verdict. */
export function samplesTable(suites) {
  return [
    '| Suite | b/a | a, in order | b, in order | Median ratio | Range | A/A spread | Verdict |',
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
    ...suites.flatMap(suite => {
      const samples = saturationSamples(suite);
      if (!samples) return [];
      const [a, b] = samples.ids;
      const list = values => values.map(value => `${num(value)}/s`).join(', ');
      return [
        `| ${labelOf(suite)} | ${b}/${a} | ${list(samples.first)} | ${list(samples.second)} | ${ratioText(samples.median)} | ` +
          `${ratioText(samples.range.lo)}–${ratioText(samples.range.hi)} | ${Number.isFinite(samples.noise) ? `${num(samples.noise * 100)}%` : '-'} | ${samples.verdict} |`,
      ];
    }),
  ];
}
