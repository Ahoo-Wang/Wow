/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  collect,
  compare,
  decideStop,
  earlyDecision,
  MAX_JOBS,
  MAX_PARALLEL,
  parseBenchmarkList,
  parseBenchmarkParams,
  planMatrix,
  readResults,
  renderReport,
  resolveInputs,
  stopOf,
  summarize,
  touchedPatterns,
  tQuantile,
  WARN_JOBS,
} from './benchmark-ab.mjs';

const COMPONENT = 'me.ahoo.wow.benchmark.component';

// One fork of a JMH JSON record, as `-rf json -prof gc` writes it.
function record({
  benchmark = `${COMPONENT}.CommandIdComponentBenchmark.generateGlobalId`,
  mode = 'thrpt',
  threads = 1,
  params,
  scores,
  alloc = 48,
  gcTime = [1, 0, 1],
}) {
  return {
    benchmark,
    mode,
    threads,
    forks: 1,
    ...(params ? { params } : {}),
    primaryMetric: { scoreUnit: 'ops/s', rawData: [scores] },
    secondaryMetrics: {
      'gc.alloc.rate.norm': { rawData: [scores.map(() => alloc)] },
      'gc.time': { rawData: [gcTime] },
    },
  };
}

test('the t quantile matches the values JMH uses for its 99.9% error', () => {
  for (const [df, expected] of [
    [1, 636.619],
    [2, 31.599],
    [9, 4.781],
    [29, 3.659],
    [1000, 3.3],
  ])
    assert.ok(
      Math.abs(tQuantile(0.9995, df) - expected) < 0.005,
      `df=${df}: ${tQuantile(0.9995, df)}`,
    );
});

test('summarize returns the mean and the confidence half-width', () => {
  const { n, mean, error } = summarize([10, 12, 14]);
  assert.equal(n, 3);
  assert.equal(mean, 12);
  // sd = 2, t(0.9995, 2) = 31.599: 31.599 * 2 / sqrt(3)
  assert.ok(Math.abs(error - 36.487) < 0.01, String(error));
  assert.ok(Number.isNaN(summarize([5]).error));
});

test('dispatch inputs become JMH arguments', () => {
  const settings = resolveInputs({
    include: 'CommandIdComponentBenchmark, EventDispatch.*\n',
    profile: 'gate',
    params: 'processors=1,4; bus=in-memory',
    threads: '1 4',
  });
  assert.equal(settings.run, true);
  assert.deepEqual(settings.patterns, [
    'CommandIdComponentBenchmark',
    'EventDispatch.*',
  ]);
  assert.equal(settings.rounds, 8);
  assert.deepEqual(settings.jmhArgs, [
    '-wi',
    '3',
    '-w',
    '3s',
    '-i',
    '5',
    '-r',
    '3s',
  ]);
  assert.deepEqual(settings.params, ['processors=1,4', 'bus=in-memory']);
  assert.deepEqual(settings.threads, ['1', '4']);
  // `auto` splits gate runs per @Param combination, quick runs per class.
  assert.equal(settings.split, 'params');
  const quick = resolveInputs({ include: 'X' });
  assert.deepEqual(quick.threads, ['default']);
  assert.equal(quick.rounds, 3);
  assert.equal(quick.split, 'class');
  assert.deepEqual(quick.params, []);
  assert.equal(
    resolveInputs({ include: 'X', profile: 'gate', split: 'method' }).split,
    'method',
  );
  assert.equal(
    resolveInputs({ include: 'X', split: 'params' }).split,
    'params',
  );
});

test('inputs that would smuggle JMH options or bad values are refused', () => {
  assert.throws(() => resolveInputs({ include: '-foe' }), /cannot start/);
  assert.throws(
    () => resolveInputs({ include: 'X', exclude: '-jvmArgs' }),
    /cannot start/,
  );
  assert.throws(() => resolveInputs({ include: 'X', params: 'oops' }), /name=/);
  assert.throws(
    () => resolveInputs({ include: 'X', params: '-jvmArgs=x' }),
    /name=/,
  );
  assert.throws(() => resolveInputs({ include: 'X', threads: '0' }), /Threads/);
  assert.throws(() => resolveInputs({ include: 'X', profile: 'x' }), /profile/);
  assert.throws(() => resolveInputs({ include: 'X', split: 'x' }), /split/);
  assert.throws(
    () => resolveInputs({ include: 'X', threshold: '-1' }),
    /Threshold/,
  );
});

test('the label run compares the benchmark classes a pull request changed', () => {
  const paths = [
    'wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/component/CommandIdComponentBenchmark.kt',
    'wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandGatewayScenario.kt',
    'wow-core/src/main/kotlin/me/ahoo/wow/Wow.kt',
  ];
  assert.deepEqual(touchedPatterns(paths), [
    '^me\\.ahoo\\.wow\\.benchmark\\.component\\.CommandIdComponentBenchmark\\.',
  ]);
  assert.equal(resolveInputs({ changedPaths: paths.slice(2) }).run, false);
});

test('the benchmark list is read from `-l` output', () => {
  assert.deepEqual(
    parseBenchmarkList(
      `Benchmarks: \n${COMPONENT}.A.one\n${COMPONENT}.A.two\n\n`,
    ),
    [`${COMPONENT}.A.one`, `${COMPONENT}.A.two`],
  );
});

test('the matrix has one job per class, or per method', () => {
  const base = [
    `${COMPONENT}.A.one`,
    `${COMPONENT}.A.two`,
    `${COMPONENT}.B.one`,
    'me.ahoo.wow.benchmark.query.B.one',
    'me.ahoo.wow.benchmark.infrastructure.mongo.MongoX.append',
  ];
  const head = [...base.slice(1), `${COMPONENT}.C.added`];
  const plan = planMatrix(base, head);
  assert.deepEqual(plan.matrix.include, [
    {
      id: 'A',
      label: 'A',
      regex: '^me\\.ahoo\\.wow\\.benchmark\\.component\\.A\\.',
      params: '[]',
    },
    {
      id: 'me.ahoo.wow.benchmark.component.B',
      label: 'me.ahoo.wow.benchmark.component.B',
      regex: '^me\\.ahoo\\.wow\\.benchmark\\.component\\.B\\.',
      params: '[]',
    },
    {
      id: 'C',
      label: 'C',
      regex: '^me\\.ahoo\\.wow\\.benchmark\\.component\\.C\\.',
      params: '[]',
    },
    {
      id: 'me.ahoo.wow.benchmark.query.B',
      label: 'me.ahoo.wow.benchmark.query.B',
      regex: '^me\\.ahoo\\.wow\\.benchmark\\.query\\.B\\.',
      params: '[]',
    },
  ]);
  assert.deepEqual(plan.skipped, [
    'me.ahoo.wow.benchmark.infrastructure.mongo.MongoX.append',
  ]);
  assert.deepEqual(plan.onlyBase, [`${COMPONENT}.A.one`]);
  assert.deepEqual(plan.onlyHead, [`${COMPONENT}.C.added`]);
  const methods = planMatrix(base.slice(0, 2), base.slice(0, 2), 'method');
  assert.deepEqual(
    methods.matrix.include.map(job => [job.id, job.regex]),
    [
      ['A.one', '^me\\.ahoo\\.wow\\.benchmark\\.component\\.A\\.one$'],
      ['A.two', '^me\\.ahoo\\.wow\\.benchmark\\.component\\.A\\.two$'],
    ],
  );
});

const DISPATCH = `${COMPONENT}.EventDispatchComponentBenchmark.dispatchToProcessors`;
const LIST_WITH_PARAMS = `Benchmarks:
${DISPATCH}
  param "processors" = {1, 8}
  param "metrics" = {off, on}
  param "bus" = {in-memory, local-first}
${COMPONENT}.CommandIdComponentBenchmark.generateGlobalId
${COMPONENT}.Shapes.draw
  param "kind" = {}
  param "label" = {a,b, c}
`;

test('`-lp` output gives each benchmark its @Param values', () => {
  assert.deepEqual(parseBenchmarkList(LIST_WITH_PARAMS), [
    DISPATCH,
    `${COMPONENT}.CommandIdComponentBenchmark.generateGlobalId`,
    `${COMPONENT}.Shapes.draw`,
  ]);
  assert.deepEqual(parseBenchmarkParams(LIST_WITH_PARAMS), {
    [DISPATCH]: {
      processors: ['1', '8'],
      metrics: ['off', 'on'],
      bus: ['in-memory', 'local-first'],
    },
    [`${COMPONENT}.CommandIdComponentBenchmark.generateGlobalId`]: {},
    [`${COMPONENT}.Shapes.draw`]: { kind: [], label: ['a,b', 'c'] },
  });
});

test('split=params plans one job per method and @Param combination', () => {
  const names = parseBenchmarkList(LIST_WITH_PARAMS);
  const params = parseBenchmarkParams(LIST_WITH_PARAMS);
  const plan = planMatrix(names, names, 'params', {
    baseParams: params,
    headParams: {
      ...params,
      // A value only head lists still gets its own job.
      [DISPATCH]: { ...params[DISPATCH], processors: ['1', '8', '16'] },
    },
    overrides: ['label=x', 'processors=1,8'],
  });
  const include = plan.matrix.include;
  // 3 × 2 × 2 dispatch combinations, generateGlobalId and Shapes.draw.
  assert.equal(include.length, 14);
  assert.equal(plan.maxParallel, MAX_PARALLEL);
  assert.deepEqual(plan.warnings, []);
  const first = include.find(job => job.label.startsWith('EventDispatch'));
  assert.deepEqual(first, {
    id: 'EventDispatchComponentBenchmark.dispatchToProcessors_bus=in-memory_metrics=off_processors=1',
    label:
      'EventDispatchComponentBenchmark.dispatchToProcessors [bus=in-memory, metrics=off, processors=1]',
    regex: `^${DISPATCH.replaceAll('.', '\\.')}$`,
    // The pin replaces the processors override; the label override is kept.
    params: JSON.stringify([
      'bus=in-memory',
      'metrics=off',
      'processors=1',
      'label=x',
    ]),
  });
  assert.ok(include.some(job => job.label.endsWith('processors=16]')));
  // No params: one job; an empty value list or a value with a comma cannot
  // be pinned, so those params run inside the job with the overrides.
  assert.deepEqual(
    include
      .filter(job => !job.label.startsWith('EventDispatch'))
      .map(job => [job.label, job.params]),
    [
      [
        'CommandIdComponentBenchmark.generateGlobalId',
        JSON.stringify(['label=x', 'processors=1,8']),
      ],
      ['Shapes.draw', JSON.stringify(['label=x', 'processors=1,8'])],
    ],
  );
  assert.equal(new Set(include.map(job => job.id)).size, include.length);
  // Class and method splits keep the overrides as they are.
  assert.deepEqual(
    planMatrix(names, names, 'class', {
      overrides: ['bus=x'],
    }).matrix.include.map(job => job.params),
    [
      JSON.stringify(['bus=x']),
      JSON.stringify(['bus=x']),
      JSON.stringify(['bus=x']),
    ],
  );
});

test('a plan over the sane job count warns, and one over the matrix limit fails', () => {
  const params = count => ({
    [DISPATCH]: { n: Array.from({ length: count }, (_, index) => `${index}`) },
  });
  const plan = count =>
    planMatrix([DISPATCH], [DISPATCH], 'params', {
      baseParams: params(count),
      headParams: params(count),
    });
  assert.deepEqual(plan(WARN_JOBS).warnings, []);
  assert.match(
    plan(WARN_JOBS + 1).warnings[0],
    new RegExp(
      `^${WARN_JOBS + 1} jobs is more than ${WARN_JOBS}: at ${MAX_PARALLEL} at a time they run in 4 waves`,
    ),
  );
  assert.throws(() => plan(MAX_JOBS + 1), /matrix limit of 256/);
});

test('forks are pooled per side, benchmark, threads and params', () => {
  const { rows } = collect([
    { name: 'base-tdefault-r1.json', records: [record({ scores: [1, 2] })] },
    { name: 'base-tdefault-r2.json', records: [record({ scores: [3] })] },
    { name: 'head-tdefault-r1.json', records: [record({ scores: [4] })] },
    {
      name: 'head-t4-r1.json',
      records: [record({ scores: [5], threads: 4, params: { b: '2', a: 1 } })],
    },
    { name: 'list.txt.json', records: [record({ scores: [9] })] },
  ]);
  assert.equal(rows.length, 2);
  assert.deepEqual(rows[0].base.score, [1, 2, 3]);
  assert.deepEqual(rows[0].head.score, [4]);
  assert.equal(rows[0].base.forks, 2);
  assert.equal(rows[0].head.forks, 1);
  assert.equal(rows[1].params, 'a=1, b=2');
  assert.equal(rows[1].threads, 4);
});

test('overlapping error bars or a small Δ are noise; the rest are flagged', () => {
  const row = (base, head, mode = 'thrpt') => ({
    mode,
    base: { score: base, alloc: [48], gcTime: [1, 1] },
    head: { score: head, alloc: [32], gcTime: [0, 1] },
  });
  const steady = [100, 101, 99, 100, 101, 99, 100, 100];
  const faster = steady.map(value => value * 1.1);
  const slower = steady.map(value => value * 0.9);
  const noisy = [60, 140, 80, 120, 100, 100, 70, 130];
  assert.equal(compare(row(steady, faster), 3).verdict, 'faster');
  assert.equal(compare(row(steady, slower), 3).verdict, 'slower');
  // Time modes: a higher score is slower.
  assert.equal(compare(row(steady, faster, 'avgt'), 3).verdict, 'slower');
  // Error bars overlap.
  assert.equal(
    compare(
      row(
        steady,
        noisy.map(v => v * 1.1),
      ),
      3,
    ).verdict,
    'noise',
  );
  // Separated, but under the threshold.
  assert.equal(compare(row(steady, faster), 20).verdict, 'noise');
  const result = compare(row(steady, faster), 3);
  assert.ok(Math.abs(result.delta - 10) < 1e-9);
  assert.deepEqual(result.alloc, [48, 32]);
  assert.deepEqual(result.gcTime, [1, 0.5]);
});

// A row after `forks` rounds of 5-iteration forks per side.
const forked = (base, head, forks = 2, mode = 'thrpt') => ({
  benchmark: DISPATCH,
  mode,
  threads: 1,
  params: '',
  base: { forks, score: base, alloc: [], gcTime: [] },
  head: { forks, score: head, alloc: [], gcTime: [] },
});
const STEADY = [100, 100.4, 99.6, 100.2, 99.8, 100.1, 99.9, 100.3, 99.7, 100];
const NOISY = [88, 112, 95, 105, 91, 109, 97, 103, 90, 110];

test('early stop: clearly separated intervals beyond the threshold stop the job', () => {
  const row = forked(
    STEADY,
    STEADY.map(value => value * 0.9),
  );
  assert.deepEqual(earlyDecision(row, 3, 8), {
    decided: true,
    reason: 'separated',
  });
  // The report's verdict on the same data agrees.
  assert.equal(compare(row, 3).verdict, 'slower');
  // Separated but under the threshold is not a stop for a flag...
  assert.notEqual(earlyDecision(row, 20, 8).reason, 'separated');
});

test('early stop: intervals that bound |Δ| under the threshold stop as noise', () => {
  const row = forked(STEADY, [...STEADY].reverse());
  assert.deepEqual(earlyDecision(row, 3, 8), {
    decided: true,
    reason: 'noise',
  });
  assert.equal(compare(row, 3).verdict, 'noise');
  // ...and a 0% threshold can never be bounded.
  assert.deepEqual(earlyDecision(row, 0, 8), { decided: false });
});

test('early stop: wide or overlapping intervals continue', () => {
  // Overlapping and too wide to bound the change.
  assert.deepEqual(earlyDecision(forked(STEADY, NOISY), 3, 8), {
    decided: false,
  });
  // A 2.5% change: the intervals neither separate at the early-look
  // confidence nor bound |Δ| under 3%.
  assert.deepEqual(
    earlyDecision(
      forked(
        STEADY,
        STEADY.map(value => value * 1.025),
      ),
      3,
      8,
    ),
    { decided: false },
  );
  // One iteration per side has no interval yet.
  assert.deepEqual(earlyDecision(forked([100], [100], 1), 3, 8), {
    decided: false,
  });
});

test('early looks use a stricter interval than the report, never a looser one', () => {
  // Separated at 99.9% (the report flags it) but not once the 0.1% is split
  // over the six early looks of a gate job: the job keeps running.
  const base = [100, 101, 99, 100.5, 99.5, 100, 101, 99, 100.5, 99.5];
  const row = forked(
    base,
    base.map(value => value * 1.026),
  );
  assert.equal(compare(row, 2).verdict, 'faster');
  assert.deepEqual(earlyDecision(row, 2, 8), { decided: false });
  // A quick job (3 rounds) has one early look, at 99.9% itself.
  assert.equal(earlyDecision(row, 2, 3).reason, 'separated');
  assert.ok(summarize(base, 0.001 / 6).error > summarize(base).error);
});

test('a job stops only once every row is decided', () => {
  const separated = forked(
    STEADY,
    STEADY.map(value => value * 1.1),
  );
  const noise = forked(STEADY, [...STEADY].reverse());
  const undecided = forked(STEADY, NOISY);
  const onlyBase = forked(STEADY, []);
  assert.equal(decideStop([separated, noise, onlyBase], 3, 8).stop, true);
  const pending = decideStop([separated, undecided], 3, 8);
  assert.equal(pending.stop, false);
  assert.equal(pending.undecided, 1);
  assert.equal(decideStop([], 3, 8).stop, false);
  // The report shows the forks used and why the job stopped there.
  assert.deepEqual(stopOf(separated, 3, 8), { forks: 2, reason: 'separated' });
  assert.deepEqual(stopOf(noise, 3, 8), { forks: 2, reason: 'noise' });
  assert.deepEqual(stopOf(forked(STEADY, NOISY, 8), 3, 8), {
    forks: 8,
    reason: 'max forks',
  });
  assert.deepEqual(stopOf(undecided, 3, 8), { forks: 2, reason: 'incomplete' });
});

test('`decide` prints stop or continue for a thread directory', () => {
  const dir = mkdtempSync(join(tmpdir(), 'benchmark-ab-decide-'));
  const fork = (side, round, scores) =>
    writeFileSync(
      join(dir, `${side}-r${round}.json`),
      JSON.stringify([record({ scores })]),
    );
  const decide = () =>
    execFileSync(
      process.execPath,
      [
        fileURLToPath(new URL('./benchmark-ab.mjs', import.meta.url)),
        'decide',
        dir,
      ],
      {
        env: { ...process.env, THRESHOLD: '3', ROUNDS: '8' },
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      },
    ).trim();
  fork('base', 1, STEADY.slice(0, 5));
  fork('head', 1, NOISY.slice(0, 5));
  fork('base', 2, STEADY.slice(5));
  fork('head', 2, NOISY.slice(5));
  assert.equal(decide(), 'continue: 1 of 1 rows undecided');
  fork('head', 1, STEADY.slice(0, 5).reverse());
  fork('head', 2, STEADY.slice(5).reverse());
  assert.equal(decide(), 'stop: all 1 rows decided');
});

test('the report reads a results directory and renders the table', () => {
  const dir = mkdtempSync(join(tmpdir(), 'benchmark-ab-'));
  const job = join(dir, 'ab-result-CommandIdComponentBenchmark');
  mkdirSync(job);
  const forks = {
    base: [
      [100, 101, 99],
      [100, 100, 101],
    ],
    head: [
      [100, 99, 101],
      [101, 100, 100],
    ],
  };
  for (const side of ['base', 'head'])
    forks[side].forEach((scores, index) =>
      writeFileSync(
        join(job, `${side}-tdefault-r${index + 1}.json`),
        JSON.stringify([
          record({ scores }),
          record({
            benchmark: `${COMPONENT}.CommandIdComponentBenchmark.createAggregateId`,
            scores: side === 'base' ? scores : scores.map(v => v * 2),
          }),
        ]),
      ),
    );
  writeFileSync(
    join(job, `base-tdefault-r9.json`),
    JSON.stringify([
      record({
        benchmark: `${COMPONENT}.CommandIdComponentBenchmark.removed`,
        scores: [5, 6],
        params: { size: 'a|b' },
      }),
    ]),
  );
  writeFileSync(
    join(job, 'meta.json'),
    JSON.stringify({ label: 'CommandIdComponentBenchmark', seconds: 125 }),
  );
  const markdown = renderReport(readResults(dir), {
    profile: 'quick',
    baseRef: 'main',
    baseSha: '0123456789abcdef',
    headRef: 'ci/x',
    headSha: 'fedcba9876543210',
    rounds: '2',
    threshold: 3,
    runUrl: 'https://github.com/Ahoo-Wang/Wow/actions/runs/1',
    runResult: 'failure',
  });
  assert.match(markdown, /Base `main` \(0123456789\) against head `ci\/x`/);
  assert.match(markdown, /Some benchmark jobs did not succeed \(failure\)/);
  assert.match(
    markdown,
    /\*\*0 slower, 1 faster, 1 within noise\*\*, 1 on one side only\./,
  );
  assert.match(
    markdown,
    /\| CommandIdComponentBenchmark\.generateGlobalId \| - \| 100\.2 ± [\d.]+ ops\/s \| 100\.2 ± [\d.]+ ops\/s \| \+0\.0% \| noise \| 2 \| max forks \| 48 → 48 \| 0\.7 → 0\.7 \|/,
  );
  assert.match(
    markdown,
    /\| CommandIdComponentBenchmark\.createAggregateId \| - \| .+ \| \+100\.0% \| \*\*faster\*\* \| 2 \| max forks \|/,
  );
  assert.match(
    markdown,
    /\| CommandIdComponentBenchmark\.removed \| size=a\\\|b \| 5\.500 ± .+ \| - \| - \| only in base \| 1 \| - \|/,
  );
  assert.match(
    markdown,
    /longest 2m 05s \(CommandIdComponentBenchmark\), 1 job,/,
  );
  assert.match(
    renderReport(
      { rows: [], jobs: [] },
      {
        ...{ baseRef: 'a', headRef: 'b' },
        baseSha: 'x',
        headSha: 'y',
        threshold: 3,
      },
    ),
    /No JMH results were produced/,
  );
});
