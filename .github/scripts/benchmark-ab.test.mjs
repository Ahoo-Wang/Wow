/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  collect,
  compare,
  parseBenchmarkList,
  planMatrix,
  readResults,
  renderReport,
  resolveInputs,
  summarize,
  touchedPatterns,
  tQuantile,
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
    '-p',
    'processors=1,4',
    '-p',
    'bus=in-memory',
  ]);
  assert.deepEqual(settings.threads, ['1', '4']);
  assert.deepEqual(resolveInputs({ include: 'X' }).threads, ['default']);
  assert.equal(resolveInputs({ include: 'X' }).rounds, 3);
});

test('inputs that would smuggle JMH options or bad values are refused', () => {
  assert.throws(() => resolveInputs({ include: '-foe' }), /cannot start/);
  assert.throws(
    () => resolveInputs({ include: 'X', exclude: '-jvmArgs' }),
    /cannot start/,
  );
  assert.throws(() => resolveInputs({ include: 'X', params: 'oops' }), /name=/);
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
    },
    {
      id: 'me.ahoo.wow.benchmark.component.B',
      label: 'me.ahoo.wow.benchmark.component.B',
      regex: '^me\\.ahoo\\.wow\\.benchmark\\.component\\.B\\.',
    },
    {
      id: 'C',
      label: 'C',
      regex: '^me\\.ahoo\\.wow\\.benchmark\\.component\\.C\\.',
    },
    {
      id: 'me.ahoo.wow.benchmark.query.B',
      label: 'me.ahoo.wow.benchmark.query.B',
      regex: '^me\\.ahoo\\.wow\\.benchmark\\.query\\.B\\.',
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
    /\| CommandIdComponentBenchmark\.generateGlobalId \| - \| 100\.2 ± [\d.]+ ops\/s \| 100\.2 ± [\d.]+ ops\/s \| \+0\.0% \| noise \| 48 → 48 \| 0\.7 → 0\.7 \|/,
  );
  assert.match(
    markdown,
    /\| CommandIdComponentBenchmark\.createAggregateId \| - \| .+ \| \+100\.0% \| \*\*faster\*\* \|/,
  );
  assert.match(
    markdown,
    /\| CommandIdComponentBenchmark\.removed \| size=a\\\|b \| 5\.500 ± .+ \| - \| - \| only in base \|/,
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
