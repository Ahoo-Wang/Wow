/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  anomalyTable,
  bench,
  labelOf,
  ms,
  num,
  propsText,
  quantile,
  readJson,
  readJsonIfThere,
  roundOrder,
  spread,
  withProps,
} from './bench-common.mjs';
import {
  renderSink,
  runSink,
  sinkAnomalies,
  SINK_PROGRAMS,
} from './bench-sink.mjs';
import {
  indexAnomalies,
  INDEX_PROGRAMS,
  renderIndex,
  runIndex,
} from './bench-index.mjs';

// The `measure` kind of bench.mjs's suites, run through bench.sh's `measure` command: its programs' settings, runs,
// results, tables and anomalies. It imports bench-common.mjs, bench-sink.mjs and bench-index.mjs, never bench.mjs,
// which imports it.

// kind `measure`, measure `walk` (bench.sh measure --program=walk, AccessWalkBench): the access walk of the data set's
// accounts of `streams` streams each under `rate` E1 deposits a second, open loop, over `warmUp` and `measure` seconds;
// the shares of the share suites, and the aggregate counts the walk's measured rate extrapolates to.
export const WALK = {
  streams: 10,
  rate: 200,
  warmUp: 10,
  measure: 40,
  shares: [0.05, 0.1, 0.25],
  extrapolate: [100000, 1000000, 10000000],
  // A p99 this much above the highest run with nothing to walk is no harm: the larger of the two.
  noHarmMs: 1,
  noHarmShare: 0.1,
};

/**
 * A run of a walk suite: [arm] (the build and its properties), whether the second node has anything to [walk], the
 * deposits a second, and more of bench.sh's options. The node that seeds the history walks what it seeded at the
 * baseline's pace, or, on the head's build, with the whole share, so that the measured walk starts from it at once.
 */
function walkRun(arm, id, props, walk, more = {}) {
  return {
    arm: withProps(arm, id, props),
    walk,
    rate: WALK.rate,
    ...(arm.install === 'head' ? { seed: { 'wow.access.walk-share': 1 } } : {}),
    ...more,
  };
}

/**
 * The runs that choose the walk's default share: four runs of the baseline's build with nothing to walk among them
 * (main's A/A band), the baseline's walk at its default pace, and the head's walk at each of WALK.shares.
 */
export function walkShareRuns(head, base) {
  const shared = WALK.shares.map(share =>
    walkRun(
      head,
      `share-${Math.round(share * 100)}`,
      { 'wow.access.walk-share': share },
      true,
    ),
  );
  return [
    walkRun(base, 'none-1', {}, false),
    walkRun(base, 'base', {}, true),
    shared[0],
    walkRun(base, 'none-2', {}, false),
    shared[1],
    shared[2],
    walkRun(base, 'none-3', {}, false),
    walkRun(base, 'none-4', {}, false),
  ];
}

/**
 * The runs that judge the head's walk at its defaults, ABBA among four runs of the baseline's build with nothing to
 * walk (main's A/A band), every deposit into an account that keeps its attributes, so the walk is the only stamping:
 * the baseline's walk and the head's, twice each. Then, reported beside the verdict: the head's depositing into the
 * accounts it stamps first (`targets: first`), the same aggregates at once; both builds' depositing into accounts not
 * stamped (`targets: all`), each of which the `wow.access` subscription also stamps at its first deposit, as after a
 * change of the access schema; and the head's walk without deposits.
 */
export function walkRuns(head, base) {
  return [
    walkRun(base, 'none-1', {}, false),
    walkRun(base, 'base-1', {}, true),
    walkRun(head, 'head-1', {}, true),
    walkRun(base, 'none-2', {}, false),
    walkRun(head, 'head-2', {}, true),
    walkRun(base, 'base-2', {}, true),
    walkRun(base, 'none-3', {}, false),
    walkRun(base, 'none-4', {}, false),
    walkRun(head, 'head-same', {}, true, { targets: 'first' }),
    walkRun(head, 'head-follower', {}, true, { targets: 'all' }),
    walkRun(base, 'base-follower', {}, true, { targets: 'all' }),
    walkRun(head, 'head-idle', {}, true, { rate: 0 }),
  ];
}

/** The distinct arms of [runs], by id. */
export const armsOfRuns = runs => [
  ...new Map(runs.map(run => [run.arm.id, run.arm])).values(),
];

// kind `measure`: rounds of one run of each arm, ABBA, through bench.sh's `measure` command, each sending every query
// `warmUpRuns` times untimed, then `runs` times timed; a difference within the A/A band (the spread of an arm's two
// runs) is no result (D392 ③).
export const MEASURE = { runs: 200, warmUpRuns: 20, rounds: 2 };

/**
 * A `measure` run's results from bench.sh's --out [dir] (measure.json and the files it names): per query, each timed
 * answer's milliseconds and HTTP status, the answer's size (rows, the count, groups), and ClickHouse's query log of
 * the timed queries; undefined when measure.json is missing.
 */
export function readMeasure(dir) {
  const manifest = readJsonIfThere(join(dir, 'measure.json'));
  if (!manifest) return undefined;
  const shapes = {};
  for (const shape of manifest.shapes) {
    const lines = existsSync(join(dir, `${shape}.times`))
      ? readFileSync(join(dir, `${shape}.times`), 'utf8')
          .split('\n')
          .filter(Boolean)
      : [];
    const timed = lines.map(line => {
      const [code, seconds] = line.trim().split(/\s+/);
      return { code: Number(code), ms: Number(seconds) * 1000 };
    });
    const ok = timed.filter(it => it.code === 200).map(it => it.ms);
    const answer = readJsonIfThere(join(dir, `${shape}.json`));
    const log = existsSync(join(dir, `${shape}.log.jsonl`))
      ? readFileSync(join(dir, `${shape}.log.jsonl`), 'utf8')
          .split('\n')
          .filter(Boolean)
          .map(line => JSON.parse(line))
      : [];
    const median = values => quantile(values, 0.5);
    shapes[shape] = {
      timed: timed.length,
      failed: timed.length - ok.length,
      p50: quantile(ok, 0.5),
      p95: quantile(ok, 0.95),
      p99: quantile(ok, 0.99),
      answer: Array.isArray(answer)
        ? answer.length
        : (answer?.count ?? answer?.list?.length ?? null),
      clickhouse: {
        queries: log.length,
        durationMs: median(log.map(it => Number(it.query_duration_ms))),
        readRows: median(log.map(it => Number(it.read_rows))),
        readBytes: median(log.map(it => Number(it.read_bytes))),
        memory: median(log.map(it => Number(it.memory_usage))),
      },
    };
  }
  return { runs: manifest.runs, warmUpRuns: manifest.warmUpRuns, shapes };
}

/** [seconds] as a duration of seconds, minutes, hours or days. */
const duration = seconds =>
  !Number.isFinite(seconds)
    ? '-'
    : seconds < 120
      ? `${num(seconds, 0)} s`
      : seconds < 7200
        ? `${num(seconds / 60, 0)} min`
        : seconds < 172800
          ? `${num(seconds / 3600, 1)} h`
          : `${num(seconds / 86400, 1)} d`;

/**
 * A walk suite's table: each run's walk (its share, how many it stamped over the measured window and at what rate, and
 * how long that rate takes to stamp each of WALK.extrapolate's counts), the E1 deposits' latencies under it, and what
 * the store's sessions waited on and the pool's peak over the window; then the verdict. Main's A/A band is the p99s of
 * the baseline's runs with nothing to walk; a run does no harm when its p99 is less than WALK.noHarmMs or
 * WALK.noHarmShare above the band's top, whichever is larger. A share suite
 * names the largest share within the band; the other suites judge the head's runs at their defaults.
 */
export function walkTables(suite) {
  const ok = suite.runs.filter(run => run.status === 'ok');
  const band = ok
    .filter(run => run.measure.walks === false)
    .map(run => run.measure.e1?.latencyMs?.p99)
    .filter(Number.isFinite);
  const top = band.length > 1 ? Math.max(...band) : undefined;
  // What is no harm: at most the larger of WALK.noHarmMs and WALK.noHarmShare above the band's top.
  const allowed =
    top === undefined
      ? undefined
      : top + Math.max(WALK.noHarmMs, top * WALK.noHarmShare);
  const p99Of = run => run.measure.e1?.latencyMs?.p99;
  const within = run =>
    allowed !== undefined &&
    Number.isFinite(p99Of(run)) &&
    p99Of(run) <= allowed;
  const rateOf = walk =>
    walk?.windowPerSecond > 0
      ? walk.windowPerSecond
      : walk?.aggregatesPerSecond;
  const counts = WALK.extrapolate.map(count =>
    count >= 1000000 ? `${count / 1000000}M` : `${count / 1000}k`,
  );
  const lines = [
    `| Run | Build | Share | Walked in the window | Aggregates/s | ${counts.join(' | ')} | E1/s | p50 | p99 | p99.9 | Failed | Within main's A/A band | Waits (samples) | Pool peak | CPU (JVM, store cores) |`,
    `| --- | --- | --- | --- | --- | ${counts.map(() => '---').join(' | ')} | --- | --- | --- | --- | --- | --- | --- | --- | --- |`,
  ];
  const arms = new Map(suite.arms.map(arm => [arm.id, arm]));
  for (const run of suite.runs) {
    const install = arms.get(run.arm)?.install ?? '';
    if (run.status !== 'ok') {
      lines.push(
        `| ${run.name} | ${install} | **${run.status}** |${' |'.repeat(counts.length + 13)}`,
      );
      continue;
    }
    const m = run.measure;
    const walk = m.walks ? m.walk : undefined;
    const latency = m.e1?.latencyMs ?? {};
    const failed = Object.values(m.e1?.failed ?? {}).reduce(
      (sum, n) => sum + n,
      0,
    );
    const rate = rateOf(walk);
    const share = !m.walks
      ? 'nothing to walk'
      : m.walkShare
        ? `${num(Number(m.walkShare) * 100, 0)}%`
        : install === 'base'
          ? 'none (walk-rate)'
          : 'default';
    const shareText = `${share}${{ first: ', same aggregates', all: ', deposits follow unstamped' }[m.targets] ?? ''}${m.rate === 0 ? ', no deposits' : ''}`;
    const walked = !walk
      ? ''
      : walk.completed
        ? `completed: ${num(walk.walked ?? 0, 0)} in ${num(walk.seconds, 1)} s`
        : `${num(walk.windowWalked ?? 0, 0)} in ${num(walk.windowSeconds, 1)} s`;
    const verdict =
      !m.e1 || !m.walks
        ? ''
        : top === undefined
          ? 'no band'
          : within(run)
            ? 'yes'
            : `**no** (${ms(p99Of(run))} > ${ms(allowed)}, ×${num(p99Of(run) / top, 1)})`;
    const waits = Object.entries(m.waits?.events ?? {})
      .slice(0, 3)
      .map(([wait, count]) => `${wait} ${count}`)
      .join(' · ');
    const pool = m.waits?.poolMax
      ? `${m.waits.poolMax.active ?? '-'} in use, ${m.waits.poolMax.pending ?? '-'} waiting of ${m.waits.poolMax.total ?? '-'}`
      : '';
    const gc = Number.isFinite(m.cpu?.gcMillis)
      ? ` (GC ${num(m.cpu.gcMillis, 0)} ms)`
      : '';
    const cpu = Number.isFinite(m.cpu?.jvmCores)
      ? `${num(m.cpu.jvmCores, 2)}, ${num(m.cpu.storeCores, 2)}${gc}`
      : '';
    const times = WALK.extrapolate.map(count =>
      walk && rate > 0 ? duration(count / rate) : '',
    );
    lines.push(
      `| ${run.name} | ${install} | ${shareText} | ${walked} | ${walk ? num(rate, 0) : ''} | ${times.join(' | ')} | ${m.rate || ''} | ${m.e1 ? ms(latency.p50) : ''} | ${m.e1 ? ms(p99Of(run)) : ''} | ${m.e1 ? ms(latency.p999) : ''} | ${m.e1 ? failed : ''} | ${verdict} | ${waits ? `${waits} (${m.waits.samples})` : ''} | ${pool} | ${cpu} |`,
    );
  }
  lines.push('');
  if (top === undefined) {
    lines.push('No verdict: the runs with nothing to walk did not both pass.');
    return lines;
  }
  lines.push(
    `Main's A/A band: E1 p99 ${band.map(ms).join(', ')} with nothing to walk. A p99 less than ${WALK.noHarmMs} ms or ${num(WALK.noHarmShare * 100, 0)}% above its top, whichever is larger, is no harm: at most ${ms(allowed)}.`,
  );
  const live = ok.filter(
    run =>
      run.measure.walks &&
      run.measure.e1 &&
      arms.get(run.arm)?.install === 'head',
  );
  if (suite.name.startsWith('access-walk-share')) {
    const passing = live
      .filter(run => run.measure.targets === 'stamped' && within(run))
      .map(run => Number(run.measure.walkShare))
      .filter(Number.isFinite);
    lines.push(
      passing.length > 0
        ? `Default share: ${num(Math.max(...passing) * 100, 0)}%, the largest within the band.`
        : '**No share is within the band.**',
    );
  } else {
    const judged = live.filter(
      run => run.measure.targets === 'stamped' && run.measure.rate > 0,
    );
    const worst = Math.max(...judged.map(p99Of).filter(Number.isFinite));
    lines.push(
      judged.length > 0 && judged.every(within)
        ? `Verdict: the head's walk keeps E1 p99 within main's A/A band (${judged.map(run => ms(p99Of(run))).join(', ')}).`
        : judged.length > 0
          ? `Verdict: **outside** main's A/A band, by ${ms(worst - allowed)} at worst (${judged.map(run => ms(p99Of(run))).join(', ')} against at most ${ms(allowed)}).`
          : 'No verdict: no run of the head walked under deposits.',
    );
  }
  return lines;
}

/**
 * A `measure` suite's tables: per query, each arm's p50, p95 and p99 (the median of its runs'), its A/A band (the
 * spread of its runs' p50s), the second arm's p50 against the first's, and ClickHouse's work for one query.
 */
export function measureTables(suite) {
  const [a, b] = suite.arms.map(arm => arm.id);
  const ok = suite.runs.filter(run => run.status === 'ok');
  const shapes = [
    ...new Set(ok.flatMap(run => Object.keys(run.measure.shapes))),
  ];
  const of = (arm, shape, key) =>
    ok
      .filter(run => run.arm === arm)
      .map(run => run.measure.shapes[shape]?.[key])
      .filter(Number.isFinite);
  const med = values => quantile(values, 0.5);
  const lines = [
    `Each query sent ${suite.warmUpRuns ?? '?'} times untimed, then ${suite.measureRuns ?? '?'} times timed (curl's total time), a run of each arm a round, ABBA.`,
    '',
    `| Query | Arm | p50 | p95 | p99 | A/A band | Failed | Answer |`,
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const shape of shapes) {
    for (const arm of [a, b]) {
      const p50s = of(arm, shape, 'p50');
      const answer = ok.find(run => run.arm === arm)?.measure.shapes[shape]
        ?.answer;
      lines.push(
        `| ${shape} | ${arm} | ${ms(med(p50s))} | ${ms(med(of(arm, shape, 'p95')))} | ${ms(med(of(arm, shape, 'p99')))} | ${p50s.length > 1 ? `${num(spread(p50s) * 100)}%` : '-'} | ${of(arm, shape, 'failed').reduce((x, y) => x + y, 0)} | ${answer ?? '-'} |`,
      );
    }
  }
  lines.push(
    '',
    `| Query | ${b} p50 / ${a} p50 | Within the A/A band | ClickHouse ${a}: ms, rows, bytes, memory | ClickHouse ${b}: ms, rows, bytes, memory |`,
    '| --- | --- | --- | --- | --- |',
  );
  for (const shape of shapes) {
    const first = of(a, shape, 'p50');
    const second = of(b, shape, 'p50');
    const ratio = med(second) / med(first);
    const band = Math.max(spread(first), spread(second));
    const clickhouse = arm => {
      const work = ok
        .filter(run => run.arm === arm)
        .map(run => run.measure.shapes[shape]?.clickhouse)
        .filter(Boolean);
      const m = key => med(work.map(it => it[key]).filter(Number.isFinite));
      return `${num(m('durationMs'), 0)} ms, ${num(m('readRows'), 0)}, ${num(m('readBytes'), 0)}, ${num(m('memory'), 0)}`;
    };
    lines.push(
      `| ${shape} | ${Number.isFinite(ratio) ? `×${num(ratio, 2)}` : '-'} | ${Number.isFinite(ratio) ? (Math.abs(ratio - 1) <= band ? 'yes: no conclusion' : 'no') : '-'} | ${clickhouse(a)} | ${clickhouse(b)} |`,
    );
  }
  return lines;
}

/** The `program` of a `measure` suite run through bench.sh's --program: its `measure` but `dataset-access`, the default. */
export const programOf = definition =>
  definition.measure && definition.measure !== 'dataset-access'
    ? { program: definition.measure }
    : {};

/** The anomalies of a `measure` suite, as [anomalies] lists them. */
export function measureAnomalies(suite) {
  if (SINK_PROGRAMS.includes(suite.program)) return sinkAnomalies(suite);
  if (INDEX_PROGRAMS.includes(suite.program)) return indexAnomalies(suite);
  const rows = [];
  if (suite.kind === 'measure' && suite.program === 'walk') {
    // A run that failed, and deposits that failed: that run's numbers do not hold.
    for (const run of suite.runs) {
      const row = (kind, detail) =>
        rows.push({
          suite: labelOf(suite),
          run: run.name,
          context: 'walk',
          kind,
          detail,
        });
      if (run.status === 'failed')
        row('run failed', 'bench.sh failed; see the job log');
      if (run.status !== 'ok') continue;
      const failed = Object.entries(run.measure.e1?.failed ?? {});
      if (failed.length > 0)
        row(
          'deposits failed',
          failed.map(([kind, count]) => `${kind} ${count}`).join(', '),
        );
      const walk = run.measure.walks ? run.measure.walk : undefined;
      if (walk && !walk.completed && !(walk.windowWalked > 0))
        row('walk stalled', 'the walk stamped none over the measured window');
    }
    return rows;
  }
  if (suite.kind === 'measure') {
    // A query answered other than 200: its times are not the query's, which the tables leave out.
    for (const run of suite.runs.filter(it => it.status === 'ok')) {
      for (const [shape, measured] of Object.entries(run.measure.shapes)) {
        if (measured.failed > 0) {
          rows.push({
            suite: labelOf(suite),
            run: run.name,
            context: shape,
            kind: 'failed queries',
            detail: `${measured.failed} of ${measured.timed} answered other than 200`,
          });
        }
      }
    }
    return rows;
  }
}

/** The Markdown summary of a `measure` suite, [definition] its SUITES entry, as [renderSuite] writes it. */
export function renderMeasure(suite, definition) {
  if (SINK_PROGRAMS.includes(suite.program))
    return renderSink(suite, definition);
  if (INDEX_PROGRAMS.includes(suite.program))
    return renderIndex(suite, definition);
  if (suite.kind === 'measure' && suite.program === 'walk') {
    const lines = [
      `## ${labelOf(suite)}`,
      '',
      definition?.answers ?? '',
      '',
      `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores. ${num(suite.accounts ?? 0, 0)} accounts of ${WALK.streams} streams; E1 at ${WALK.rate}/s, open loop, warm-up ${WALK.warmUp} s, measured ${WALK.measure} s; the runs in this order, on one runner.`,
      '',
      ...walkTables(suite),
      '',
    ];
    const found = anomalyTable(measureAnomalies(suite));
    if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
    if (suite.failures > 0)
      lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
    return `${lines.join('\n')}\n`;
  }
  if (suite.kind === 'measure') {
    const lines = [
      `## ${labelOf(suite)}`,
      '',
      definition?.answers ?? '',
      '',
      `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores.`,
      '',
      ...suite.arms.map(
        arm =>
          `- \`${arm.id}\`: ${arm.props ? propsText(arm.props) : 'as built'}`,
      ),
      '',
      ...measureTables(suite),
      '',
    ];
    const found = anomalyTable(measureAnomalies(suite));
    if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
    if (suite.failures > 0)
      lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
    return `${lines.join('\n')}\n`;
  }
}

/** Runs a `measure` suite into [suite], as [runSuite] runs it: [context] holds the run's settings and its save. */
export function runMeasure(
  definition,
  suite,
  { benchPath, installs, out, data, cpuset, rounds, arms, save },
) {
  if (SINK_PROGRAMS.includes(definition.measure)) {
    runSink(definition, suite, { benchPath, out, cpuset, arms, save });
  } else if (INDEX_PROGRAMS.includes(definition.measure)) {
    runIndex(definition, suite, { benchPath, out, cpuset, arms, save });
  } else if (definition.measure === 'walk') {
    for (const spec of definition.runs) {
      const runName = spec.arm.id;
      const run = { name: runName, arm: spec.arm.id, status: 'failed' };
      if (spec.arm.install === 'base' && !installs.base) {
        // No baseline given: the head's runs alone.
        run.status = 'unavailable';
        suite.runs.push(run);
        save();
        continue;
      }
      const dir = join(out, runName);
      const status = bench(
        benchPath,
        [
          'measure',
          '--program=walk',
          `--backend=${spec.arm.backend}`,
          ...(installs[spec.arm.install]
            ? [`--install=${installs[spec.arm.install]}`]
            : []),
          `--aggregates=${data.accounts}`,
          `--streams=${WALK.streams}`,
          `--walk=${spec.walk ? 'yes' : 'none'}`,
          ...(spec.targets ? [`--targets=${spec.targets}`] : []),
          `--rate=${spec.rate}`,
          `--warm-up=${WALK.warmUp}`,
          `--measure=${WALK.measure}`,
          ...Object.entries(spec.seed ?? {}).map(
            ([key, value]) => `--seed.${key}=${value}`,
          ),
          ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
          `--out=${dir}`,
        ],
        spec.arm.props,
      );
      const file = join(dir, 'measure.json');
      if (status === 0 && existsSync(file)) {
        run.status = 'ok';
        run.measure = readJson(file);
      } else if (status === 3) {
        // A build older than AccessWalkBench: nothing to compare, not a failure.
        run.status = 'unavailable';
      } else {
        suite.failures++;
      }
      suite.runs.push(run);
      save();
    }
  } else {
    suite.measureRuns = MEASURE.runs;
    suite.warmUpRuns = MEASURE.warmUpRuns;
    for (let round = 0; round < Math.max(rounds, MEASURE.rounds); round++) {
      for (const arm of roundOrder(arms, round)) {
        const runName = `${round + 1}-${arm.id}`;
        const dir = join(out, runName);
        const status = bench(
          benchPath,
          [
            'measure',
            `--backend=${arm.backend}`,
            `--runs=${MEASURE.runs}`,
            `--warm-up-runs=${MEASURE.warmUpRuns}`,
            ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
            `--out=${dir}`,
          ],
          arm.props,
        );
        const run = { name: runName, arm: arm.id, round, status: 'failed' };
        const measured = status === 0 ? readMeasure(dir) : undefined;
        if (measured) {
          run.status = 'ok';
          run.measure = measured;
        } else {
          suite.failures++;
        }
        suite.runs.push(run);
        save();
      }
    }
  }
}
