/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  anomalies,
  aaSpread,
  anomaliesOf,
  AXON_INSTALL,
  axonInstall,
  CLUSTER,
  drifted,
  measuredWindow,
  mergeSignals,
  planMatrix,
  mergeSaturations,
  OWN,
  parseJob,
  peakRatio,
  bootstrapRatio,
  calibratedGeneratorBound,
  confidence,
  correctnessOf,
  cpuSplit,
  GENERATOR_BOUND,
  generatorWithKafka,
  HANDOVER_SCRIPT,
  HOST_PROCESSES,
  hostProcesses,
  javaHome,
  javaRelease,
  generatorBound,
  cpuLayout,
  layoutOf,
  saturationRatioBound,
  halves,
  noiseFile,
  noiseLabel,
  noiseOf,
  propertiesOf,
  verdict,
  metrics,
  measureTable,
  pairs,
  ratios,
  readSuites,
  renderReport,
  renderSuite,
  resolveInputs,
  roundOrder,
  lastSignals,
  signalsWithin,
  BUSY,
  busyOf,
  queryLimitsWithin,
  queryLimitsText,
  ratioVerdict,
  SATURATION,
  saturationOutlier,
  saturationSamples,
  runSettings,
  runSuite,
  SAME,
  sameRateBasis,
  SESSIONS,
  spread,
  SUITES,
  suitesOf,
  sustainableStep,
  sweepNext,
  sweepOf,
  jobId,
  partsOf,
  usesBase,
  fineMore,
  fineOf,
  fineSteps,
  sweepPlanOf,
  usesBus,
} from './bench.mjs';

// This file runs under Node's test runner alone (`pnpm test:ci-scripts`), whose NODE_TEST_CONTEXT makes
// hostProcesses refuse: no test here finds or signals a process of the host (X-66).
assert.ok(
  process.env.NODE_TEST_CONTEXT,
  'Run with node --test: outside the test runner the hand-over could signal a process of the host (X-66).',
);

const result = (throughput, p99, cores = 1) => ({
  results: {
    throughput,
    ok: throughput * 10,
    failed: 0,
    latencyMs: { p50: p99 / 4, p95: p99 / 2, p99, p999: p99 * 2 },
    worstSecondP99Ms: p99 * 1.5,
  },
  resources: {
    processes: { 'node-1': { averageCores: cores } },
    containers: { 'wow-e2e-bench-mongo': { cpuPercentAverage: 50 } },
  },
});

test('resolveInputs checks the inputs and expands all', () => {
  const all = resolveInputs({
    suites: 'all',
    warmUp: '20',
    measure: '40',
    repeats: '2',
    fraction: '0.8',
  });
  // `all` is every suite.
  assert.deepEqual([...all.suites].sort(), Object.keys(SUITES).sort());
  assert.equal(all.fraction, 0.8);
  assert.equal(all.p99LimitMs, 200);
  assert.deepEqual(
    resolveInputs({
      suites: 'head e3-pg',
      warmUp: 1,
      measure: 5,
      repeats: 1,
      fraction: 1,
      p99LimitMs: '500',
    }),
    {
      suites: [...suitesOf('head'), 'e3-pg'],
      warmUp: 1,
      measure: 5,
      repeats: 1,
      fraction: 1,
      p99LimitMs: 500,
      tier: 'custom',
      datasets: ['100k'],
      aa: false,
      profile: false,
    },
  );
  // No suites named: the tier's list, L2 by default.
  const byTier = tier =>
    resolveInputs({ tier, warmUp: 30, measure: 60, repeats: 2, fraction: 0.8 });
  assert.deepEqual(byTier(undefined).suites, [
    'e1-mongo-uniform',
    'e1-pg-uniform',
    'e3-mongo',
    'e3-pg',
  ]);
  assert.equal(byTier(undefined).tier, 'L2');
  assert.deepEqual(byTier('L4').suites.slice(0, 2), [
    'sustainable-mongo',
    'sustainable-pg',
  ]);
  assert.throws(() => byTier('L9'), /Unknown tier/);
  // The data sets: L3 runs its grid at 1k and 100k; `dataset` replaces the tier's.
  assert.deepEqual(byTier('L3').datasets, ['1k', '100k']);
  assert.deepEqual(byTier('L2').datasets, ['100k']);
  assert.deepEqual(
    resolveInputs({
      dataset: '1M, 1k',
      aa: 'true',
      warmUp: 30,
      measure: 60,
      repeats: 2,
      fraction: 0.8,
    }),
    {
      suites: ['e1-mongo-uniform', 'e1-pg-uniform', 'e3-mongo', 'e3-pg'],
      warmUp: 30,
      measure: 60,
      repeats: 2,
      fraction: 0.8,
      p99LimitMs: 200,
      tier: 'L2',
      datasets: ['1M', '1k'],
      aa: true,
      profile: false,
    },
  );
  assert.throws(
    () =>
      resolveInputs({
        dataset: '10k',
        warmUp: 30,
        measure: 60,
        repeats: 2,
        fraction: 0.8,
      }),
    /Unknown data set/,
  );
  assert.deepEqual(parseJob('e1-mongo-uniform.100k'), {
    suite: 'e1-mongo-uniform',
    dataset: '100k',
  });
  assert.deepEqual(
    resolveInputs({
      suites: 'e3-pg, e1-mongo-zipf e3-pg',
      warmUp: 0,
      measure: 5,
      repeats: 1,
      fraction: '1',
    }).suites,
    ['e3-pg', 'e1-mongo-zipf'],
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'e9',
        warmUp: 1,
        measure: 5,
        repeats: 1,
        fraction: 1,
      }),
    /Unknown suite/,
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'all',
        warmUp: 1,
        measure: 5,
        repeats: 9,
        fraction: 1,
      }),
    /repeats/,
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'all',
        warmUp: 1,
        measure: 5,
        repeats: 1,
        fraction: '1.5',
      }),
    /fraction/,
  );
  assert.throws(
    () =>
      resolveInputs({
        suites: 'all',
        warmUp: 'x',
        measure: 5,
        repeats: 1,
        fraction: 1,
      }),
    /warm_up/,
  );
});

test('metrics, ratios, spread and drift', () => {
  const m = metrics(result(100, 20, 1.5));
  assert.equal(m.serverCores, 1.5);
  assert.equal(m.containerCores, 0.5);
  assert.equal(m.cpuMsPerOp, 20);
  assert.deepEqual(m.containers, { mongo: 50 });
  const r = ratios(m, metrics(result(120, 10, 1.5)));
  assert.equal(r.throughput, 1.2);
  assert.equal(r.p99, 0.5);
  assert.equal(spread([100, 104]), 0.040000000000000036);
  assert.equal(spread([100]), 0);
  const arms = [{ id: 'a' }];
  const run = (throughput, p99) => ({
    arm: 'a',
    status: 'ok',
    metrics: { throughput, p99 },
  });
  assert.equal(drifted([run(100, 10), run(103, 10.4)], arms), false);
  assert.equal(drifted([run(100, 10), run(100, 11)], arms), true);
  assert.deepEqual(roundOrder(['a', 'b'], 0), ['a', 'b']);
  assert.deepEqual(roundOrder(['a', 'b'], 1), ['b', 'a']);
});

test('allocation per operation is reported when every server was read, and a head allocating more is flagged', () => {
  const allocating = (throughput, bytes) => {
    const r = result(throughput, 20);
    r.resources.processes['node-1'].allocatedBytes = bytes;
    return r;
  };
  // 100/s × 10 s = 1000 operations.
  assert.equal(metrics(allocating(100, 4_000_000)).allocatedBytesPerOp, 4000);
  assert.equal(metrics(result(100, 20)).allocatedBytesPerOp, undefined);
  const twoNodes = allocating(100, 4_000_000);
  twoNodes.resources.processes['node-2'] = {
    averageCores: 1,
    allocatedBytes: null,
  };
  assert.equal(metrics(twoNodes).allocatedBytesPerOp, undefined);
  const suite = head => ({
    name: 'e1-mongo-uniform',
    kind: 'paired',
    arms: [{ id: 'base' }, { id: 'head' }],
    runs: [0, 1].flatMap(round => [
      {
        name: `r${round + 1}-base`,
        arm: 'base',
        round,
        status: 'ok',
        metrics: metrics(allocating(100, 4_000_000)),
      },
      {
        name: `r${round + 1}-head`,
        arm: 'head',
        round,
        status: 'ok',
        metrics: metrics(allocating(100, head)),
      },
    ]),
  });
  const flagged = anomalies(suite(4_400_000)).filter(
    row => row.kind === 'more allocation',
  );
  assert.equal(flagged.length, 1);
  assert.match(flagged[0].detail, /×1\.10 .*calibrated pairs/);
  assert.equal(
    anomalies(suite(4_100_000)).filter(row => row.kind === 'more allocation')
      .length,
    0,
  );
  // With equal-rate rounds, those decide: at unequal rates bytes per operation compare the rates.
  const atEqualRates = suite(4_400_000);
  atEqualRates.runs.push(
    {
      name: 'e1-base',
      arm: 'base',
      round: 0,
      mode: 'equal',
      status: 'ok',
      metrics: metrics(allocating(100, 4_000_000)),
    },
    {
      name: 'e1-head',
      arm: 'head',
      round: 0,
      mode: 'equal',
      status: 'ok',
      metrics: metrics(allocating(100, 4_040_000)),
    },
  );
  assert.equal(
    anomalies(atEqualRates).filter(row => row.kind === 'more allocation')
      .length,
    0,
  );
});

// A stand-in for bench.sh: writes what the real one writes, a saturation of 150/s, and
// throughput = rate; the first v10 run is slower by 10%, so the drift check adds a round.
function fakeBench(dir) {
  const path = join(dir, 'bench.sh');
  writeFileSync(
    path,
    `#!/usr/bin/env node
const fs = require('node:fs');
const [command, ...args] = process.argv.slice(2);
const opt = (name) => args.find((a) => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
const out = opt('out');
fs.mkdirSync(out, { recursive: true });
fs.appendFileSync(${JSON.stringify(join(dir, 'calls.log'))}, [command, ...args, 'props=' + (process.env.SPRING_APPLICATION_JSON ?? '')].join(' ') + '\\n');
if (process.env.SPRING_APPLICATION_JSON?.includes('bench.signals.enabled')) {
  fs.writeFileSync(out + '/node-1.log', 'x bench-signals {"t":1}\\nx WARN bench-signals {"t":2,"tailRead":{"gap":3}}\\n');
}
// The head is the build without --install, the baseline the one with it.
const head = !opt('install');
if (command === 'saturate') {
  // FAKE_STALLED_SATURATION names a label whose search stalls at concurrency 1.
  const stalled = opt('label') === process.env.FAKE_STALLED_SATURATION;
  fs.writeFileSync(out + '/saturation.json', JSON.stringify(stalled ? { saturationThroughput: 40, saturationConcurrency: 1 } : { saturationThroughput: 150, saturationConcurrency: 16 }));
} else {
  console.log('[run] fake: measure');
  // A cluster run lingers a moment, so the hand-over helper sees the measured phase start.
  if (opt('nodes')) require('node:child_process').execSync('sleep 3');
  const rate = Number(opt('rate'));
  const slow = head && /r1-(head|b)$/.test(opt('label')) ? 1.1 : 1;
  // The head keeps up with at most 100/s in open loop, so its probe at 120/s fails and the next, at 90/s, holds.
  const failed = head && rate > 100 ? rate * 10 : 0;
  fs.writeFileSync(out + '/result.json', JSON.stringify({
    load: { rate }, results: { perSecond: [{ ok: failed ? 0 : rate }], throughput: failed ? 0 : rate, ok: failed ? 0 : rate * 10, failed, errors: failed ? { 'http-504-wow.wait.timeout': failed } : {}, latencyMs: { p50: 2, p95: 5, p99: 10 * slow, p999: 20 }, worstSecondP99Ms: 15 },
    resources: { processes: { 'node-1': { averageCores: 1 } }, containers: { 'wow-e2e-bench-mongo': { cpuPercentAverage: 40 }, 'wow-e2e-bench-kafka': { cpuPercentAverage: 20 } } },
  }));
}
`,
  );
  chmodSync(path, 0o755);
  return path;
}

// A JDK's home with the release file of `version` (`21.0.9+10-LTS`), under `dir`.
function fakeJdk(dir, version) {
  const home = join(dir, `jdk-${version}`);
  mkdirSync(home, { recursive: true });
  writeFileSync(
    join(home, 'release'),
    `IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="${version.split('+')[0]}"\nJAVA_RUNTIME_VERSION="${version}"\n`,
  );
  return home;
}

test("javaHome finds a JDK by its major version, setup-java's variables first", () => {
  assert.equal(
    javaHome(21, { JAVA_HOME_21_X64: '/x64', JAVA_HOME_21: '/plain' }),
    '/x64',
  );
  assert.equal(javaHome(25, { JAVA_HOME_25_ARM64: '/arm' }), '/arm');
  assert.equal(javaHome(25, { JAVA_HOME_25: '/local' }), '/local');
  // JAVA_HOME alone names no version.
  assert.throws(
    () => javaHome(21, { JAVA_HOME: '/jdk' }),
    /No JDK 21: set JAVA_HOME_21/,
  );
});

test('javaRelease names a JDK by its release file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  assert.equal(
    javaRelease(fakeJdk(dir, '25.0.1+8-LTS')),
    'Eclipse Adoptium 25.0.1+8-LTS',
  );
  const bare = join(dir, 'bare');
  mkdirSync(bare);
  writeFileSync(join(bare, 'release'), 'JAVA_VERSION="21.0.9"\n');
  assert.equal(javaRelease(bare), '21.0.9');
  assert.equal(javaRelease(join(dir, 'none')), undefined);
  assert.equal(javaRelease(undefined), undefined);
});

test("the jdk suites pair the head's server on JDK 21 with it on 25", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const env = {
    JAVA_HOME_21_X64: fakeJdk(dir, '21.0.9+10-LTS'),
    JAVA_HOME_25_X64: fakeJdk(dir, '25.0.1+8-LTS'),
  };
  assert.deepEqual(suitesOf('jdk'), ['jdk-e1-pg', 'jdk-e3-pg', 'jdk-e1-mongo']);
  const suite = runSuite('jdk-e1-pg', {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    env,
    out: join(dir, 'results', 'jdk-e1-pg'),
  });
  assert.deepEqual(
    suite.arms.map(arm => [arm.id, arm.install, arm.jdk]),
    [
      ['jdk21', 'head', 'Eclipse Adoptium 21.0.9+10-LTS'],
      ['jdk25', 'head', 'Eclipse Adoptium 25.0.1+8-LTS'],
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  // Both arms run the head's build, each call naming its server's JDK.
  for (const java of [env.JAVA_HOME_21_X64, env.JAVA_HOME_25_X64]) {
    assert.ok(calls.some(line => line.includes(`--server-java=${java} `)));
  }
  assert.ok(calls.every(line => line.includes('--server-java=')));
  assert.ok(calls.every(line => !line.includes('--install=')));
  assert.match(
    renderSuite(suite),
    /- `jdk21`: the server on JDK Eclipse Adoptium 21\.0\.9\+10-LTS\n- `jdk25`: the server on JDK Eclipse Adoptium 25\.0\.1\+8-LTS/,
  );
  // Its A/A thresholds are its ab twin's.
  assert.equal(noiseLabel(suite), 'e1-pg-uniform@100k');
  assert.equal(noiseLabel({ name: 'e3-pg', dataset: '1k' }), 'e3-pg@1k');
});

test('the access suites pair the head with teller callers, without and with a policy of the account', () => {
  const paired = suitesOf('access').filter(
    name => SUITES[name].kind === 'paired',
  );
  assert.deepEqual(paired, [
    'access-e1-pg',
    'access-e3-pg',
    'access-e1-mongo',
    'access-e3-mongo',
  ]);
  for (const name of paired) {
    const arms = SUITES[name].arms;
    assert.deepEqual(
      arms.map(arm => arm.install),
      ['head', 'head'],
    );
    assert.deepEqual(
      arms.map(arm => arm.props),
      [{ 'benchmark.access': 'claims' }, { 'benchmark.access': 'policy' }],
    );
  }
});

test('the jdk-saturation suites take closed-loop samples alone, ABBA, and judge them by their A/A spread', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const env = {
    JAVA_HOME_21_X64: fakeJdk(dir, '21.0.9+10-LTS'),
    JAVA_HOME_25_X64: fakeJdk(dir, '25.0.1+8-LTS'),
  };
  assert.deepEqual(suitesOf('jdk-saturation'), [
    'jdk-e1-pg-saturation',
    'jdk-e3-pg-saturation',
  ]);
  const suite = runSuite('jdk-e1-pg-saturation', {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
    env,
    out: join(dir, 'results', 'jdk-e1-pg-saturation'),
  });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  // Searches alone, four an arm in turn, each from empty stores: no probe, no round.
  assert.ok(calls.every(line => line.startsWith('saturate ')));
  assert.ok(calls.every(line => !line.includes('--reuse')));
  assert.deepEqual(
    calls.map(line => line.match(/--label=\S+-saturation-(\S+)/)[1]),
    [
      'jdk21-1',
      'jdk25-1',
      'jdk25-2',
      'jdk21-2',
      'jdk21-3',
      'jdk25-3',
      'jdk25-4',
      'jdk21-4',
    ],
  );
  assert.deepEqual(suite.runs, []);
  assert.deepEqual(Object.keys(suite.saturation), ['jdk21', 'jdk25']);
  assert.deepEqual(suite.saturation.jdk21, {
    status: 'ok',
    throughput: 150,
    samples: 4,
    capped: false,
  });
  assert.equal(
    saturationSamples(suite).verdict,
    'level (within the A/A spread)',
  );
  // The verdict: a range beyond the larger arm's spread names the higher arm.
  const sampled = (first, second) => ({
    arms: suite.arms,
    saturations: {
      jdk21: first.map(throughput => ({ status: 'ok', throughput })),
      jdk25: second.map(throughput => ({ status: 'ok', throughput })),
    },
  });
  const lower = saturationSamples(
    sampled([3900, 3800, 3950, 3850], [3300, 3400, 3350, 3380]),
  );
  assert.equal(lower.verdict, 'jdk21 higher');
  assert.equal(Math.round(lower.median * 1000), 868);
  assert.equal(Math.round(lower.noise * 1000), 39);
  assert.equal(
    saturationSamples(sampled([3900, 3300], [3400, 3800])).verdict,
    'level (within the A/A spread)',
  );
  assert.equal(
    saturationSamples(sampled([3900], [3400])).verdict,
    'no A/A (one session an arm)',
  );
  assert.match(
    renderSuite(suite),
    /\| jdk-e1-pg-saturation@100k \| jdk25\/jdk21 \| 150\.0\/s, 150\.0\/s, 150\.0\/s, 150\.0\/s \| 150\.0\/s, 150\.0\/s, 150\.0\/s, 150\.0\/s \| 1\.000 \| 1\.000–1\.000 \| 0\.0% \| level/,
  );
  assert.match(
    renderSuite(suite),
    /measured 5 s; closed-loop searches alone, 4 an arm in turn \(ABBA\)/,
  );
  assert.doesNotMatch(renderSuite(suite), /\| Run \| Arm \| Rate/);
  const report = renderReport([suite], {});
  assert.match(report, /## Saturation samples \(closed loop\)/);
});

test('runSuite pairs the arms, adds a round on drift, and the report combines the suites', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const options = {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
    installs: { base: '/builds/base/benchmark-server' },
    refs: { baseRef: 'base', baseSha: 'b1', headRef: 'v10', headSha: 'h1' },
  };
  const e1 = runSuite('e1-mongo-uniform', {
    ...options,
    out: join(dir, 'results', 'e1-mongo-uniform'),
  });
  assert.equal(e1.failures, 0);
  assert.deepEqual(e1.rates, { base: 120, head: 90 });
  assert.deepEqual(
    e1.probes.map(probe => probe.name),
    ['probe1-base', 'probe1-head', 'probe2-head'],
  );
  assert.deepEqual(e1.calibration.head, { sustainable: true, share: 0.6 });
  assert.equal(e1.extraRound, true);
  assert.deepEqual(
    e1.runs.map(run => run.name),
    [
      'r1-base',
      'r1-head',
      'r2-head',
      'r2-base',
      'r3-base',
      'r3-head',
      'e1-base',
      'e1-head',
      'e2-head',
      'e2-base',
    ],
  );
  assert.equal(pairs(e1).length, 3);
  assert.equal(pairs(e1)[0].ratios.throughput, 0.75);
  // The equal-rate rounds: both arms at the lower calibrated rate, 90/s.
  assert.equal(e1.equalRate, 90);
  assert.deepEqual(
    e1.runs.filter(run => run.mode === 'equal').map(run => run.rate),
    [90, 90, 90, 90],
  );
  assert.equal(pairs(e1, 'equal').length, 2);
  assert.equal(pairs(e1, 'equal')[0].ratios.throughput, 1);

  const diag = runSuite('e3-mongo-diag', {
    ...options,
    out: join(dir, 'results', 'e3-mongo-diag'),
  });
  assert.equal(diag.runs.length, SUITES['e3-mongo-diag'].runs.length);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  // Every V10 run turns the signals on, its own properties beside them; the baseline runs its own build.
  assert.match(
    calls,
    /--scenario=E3.*props=\{"bench\.signals\.enabled":true,"wow\.log\.max-poll-interval":"5s"\}/,
  );
  assert.match(
    calls,
    /saturate --backend=mongo --install=\/builds\/base\/benchmark-server --scenario=E1 --distribution=uniform --accounts=100000 --seed-concurrency=256 --prime --warm-up=3 --step=8/,
  );

  const suites = readSuites(join(dir, 'results'));
  assert.deepEqual(
    suites.map(s => s.name),
    ['e1-mongo-uniform', 'e3-mongo-diag'],
  );
  const report = renderReport(suites, {
    ...suites[0].refs,
    skipped: ['e3-pg (no V10 baseline)'],
  });
  assert.match(report, /Head: `v10` h1; V10 baseline: `base` b1; harness/);
  assert.match(report, /\*\*Skipped:\*\* e3-pg \(no V10 baseline\)/);
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| head\/base \| calibrated \(unequal load\) \| 1\.000 \| 0\.750 \(0\.750–0\.750, n=3\)/,
  );
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| head\/base \| equal rate 90\.0\/s \| 1\.000 \| 1\.000 \(1\.000–1\.000, n=2\)/,
  );
  assert.match(report, /## E3 on MongoDB: diagnostics/);
  assert.match(report, /wow\.log\.max-poll-interval=5s/);
  // The fake node logs say the tails read the store after a gap: an anomaly on every V10 run.
  assert.match(report, /## Anomalies/);
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| r1-head \| steady \| \*\*tail read the store\*\* \| gap=3 \|/,
  );
  assert.match(
    readFileSync(
      join(dir, 'results', 'e1-mongo-uniform', 'summary.md'),
      'utf8',
    ),
    /\| r1-base \| base \| 120\.0\/s \| 120\.0\/s/,
  );
});

test('cpuSplit gives the load generator the last CPUs and the servers the others', () => {
  assert.deepEqual(cpuSplit(4), { load: '3', server: '0-2' });
  assert.deepEqual(cpuSplit(4, 2), { load: '2-3', server: '0-1' });
  assert.deepEqual(cpuSplit(8, 2), { load: '6-7', server: '0-5' });
  // Below four CPUs, or a split that would leave the servers less than two, nothing is pinned.
  assert.deepEqual(cpuSplit(2), {});
  assert.deepEqual(cpuSplit(4, 3), {});
  assert.deepEqual(cpuSplit(Number.NaN), {});
  for (const definition of Object.values(SUITES))
    assert.equal(definition.loadCpus, undefined);
});

test('cpuLayout gives a paired MongoDB suite a CPU each, a paired PostgreSQL suite and the Axon comparison a two-CPU generator, Kafka beside it when an arm uses it', () => {
  // The Axon comparison (kind `sessions`) is laid out as a paired PostgreSQL suite.
  const twoArmed = it => ['paired', 'sessions'].includes(it.kind);
  const mongo = Object.entries(SUITES).filter(
    ([, it]) => twoArmed(it) && it.arms.every(arm => arm.backend === 'mongo'),
  );
  const pg = Object.entries(SUITES).filter(
    ([, it]) => twoArmed(it) && it.arms.some(arm => arm.backend !== 'mongo'),
  );
  assert.ok(mongo.length > 0 && pg.length > 0);
  assert.ok(pg.some(([name]) => name === 'axon-pg'));
  for (const [name, definition] of mongo) {
    assert.deepEqual(
      cpuLayout(definition, 4),
      { load: '3', bus: '2', store: '1', server: '0' },
      name,
    );
    assert.deepEqual(
      cpuLayout(definition, 8),
      { load: '7', bus: '6', store: '5', server: '0-4' },
      name,
    );
  }
  for (const [name, definition] of pg)
    assert.deepEqual(
      cpuLayout(definition, 4),
      // Kafka, which neither server of the Axon comparison uses, does not run there.
      { load: '2-3', server: '0-1' },
      name,
    );
  // A sessions suite whose arm uses Kafka has it on the generator's CPUs.
  assert.deepEqual(
    cpuLayout(
      {
        kind: 'sessions',
        arms: [
          { id: 'wow', backend: 'postgres', install: 'head' },
          SUITES['axon-pg'].arms[1],
        ],
      },
      4,
    ),
    { load: '2-3', server: '0-1', bus: '2-3' },
  );
  for (const [name, definition] of Object.entries(SUITES).filter(
    ([, it]) => !twoArmed(it),
  ))
    assert.deepEqual(
      cpuLayout(definition, 4),
      { load: '3', server: '0-2' },
      name,
    );
  assert.deepEqual(cpuLayout(SUITES['e1-mongo-uniform'], 2), {});
  assert.equal(
    layoutOf(cpuLayout(SUITES['e1-mongo-uniform'], 4)),
    'generator 3 · node 0 · store 1 · Kafka 2',
  );
  assert.equal(
    layoutOf(cpuLayout(SUITES['e1-pg-uniform'], 4)),
    'generator 2-3 · node 0-1',
  );
  assert.equal(layoutOf(undefined), 'unpinned');
});

test('runSuite pins a paired MongoDB suite with the store and Kafka on CPUs of their own', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  runSuite('e1-mongo-uniform', {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    cpus: { total: 4 },
    installs: { base: '/builds/base/benchmark-server' },
    refs: { baseRef: 'base', baseSha: 'b1', headRef: 'v10', headSha: 'h1' },
    out: join(dir, 'results', 'e1-mongo-uniform'),
  });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /--load-cpus=3 --server-cpus=0 --store-cpus=1 --bus-cpus=2/,
  );
  assert.doesNotMatch(calls, /--load-cpus=2-3/);
  const suite = JSON.parse(
    readFileSync(
      join(dir, 'results', 'e1-mongo-uniform', 'suite.json'),
      'utf8',
    ),
  );
  assert.deepEqual(suite.cpus, {
    load: '3',
    bus: '2',
    store: '1',
    server: '0',
  });
});

test('a ratio the load generator capped is shown as invalid, not as a ratio', () => {
  assert.equal(generatorBound(0.97, 1), true);
  assert.equal(generatorBound(0.8, 1), false);
  assert.equal(generatorBound(1.85, 2), true);
  assert.equal(generatorBound(undefined, 1), false);
  assert.equal(generatorBound(0.97, undefined), false);

  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  runSuite('e1-mongo-uniform', {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
    installs: { base: '/builds/base/benchmark-server' },
    refs: { baseRef: 'base', baseSha: 'b1', headRef: 'v10', headSha: 'h1' },
    out: join(dir, 'results', 'e1-mongo-uniform'),
  });
  const [suite] = readSuites(join(dir, 'results'));
  assert.equal(saturationRatioBound(suite), false);
  assert.equal(calibratedGeneratorBound(suite), false);
  const clean = renderReport([suite], suite.refs);
  assert.doesNotMatch(clean, new RegExp(GENERATOR_BOUND));

  // The head's saturation step used 0.98 of its one core, and so did a calibrated run of the baseline.
  suite.saturation.head = {
    ...suite.saturation.head,
    generatorCores: 0.98,
    generatorCpus: 1,
  };
  const run = suite.runs.find(r => r.arm === 'base' && r.mode !== 'equal');
  run.metrics = {
    ...run.metrics,
    loadGeneratorCores: 0.97,
    loadGeneratorCpus: 1,
  };
  assert.equal(saturationRatioBound(suite), true);
  assert.equal(calibratedGeneratorBound(suite), true);
  const report = renderReport([suite], suite.refs);
  // The saturation ratio and every calibrated ratio; the equal-rate rows still compare.
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| head\/base \| calibrated \(unequal load\) \| invalid: generator-bound \| invalid: generator-bound \|/,
  );
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| head\/base \| equal rate 90\.0\/s \| invalid: generator-bound \| 1\.000/,
  );
  assert.match(report, /invalid: generator-bound, 0\.98 of 1 cores/);
  assert.match(
    report,
    /\| e1-mongo-uniform@100k \| head\/base \| throughput \| calibrated \| .* \| invalid: generator-bound \|/,
  );
});

test('planMatrix skips the suites whose commits are missing and lists the builds', () => {
  const suites = ['e1-mongo-uniform', 'cliff-e3-pg'];
  assert.deepEqual(planMatrix({ suites, headSha: 'h', baseSha: '' }), {
    suites: ['cliff-e3-pg'],
    jobs: ['cliff-e3-pg.100k'],
    skipped: [
      'e1-mongo-uniform (no V10 baseline: set base_ref; no v10.* release tag has the benchmark yet)',
    ],
    builds: [{ side: 'head', sha: 'h' }],
  });
  assert.deepEqual(
    planMatrix({ suites, headSha: 'h', baseSha: 'b' }).builds.map(
      build => build.side,
    ),
    ['head', 'base'],
  );
});

test('a capacity suite bisects for the highest rate within the error and p99 limits', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const options = {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
  };
  const suite = runSuite('sustainable-pg', {
    ...options,
    out: join(dir, 'pg'),
  });
  // Saturation 150/s; the head keeps up with at most 100/s: 75 holds, 112.5 fails, 93.75 holds, 103.1 fails,
  // 98.4 holds, and the bracket is then within 5% of the top.
  assert.deepEqual(Object.keys(suite.capacity), ['E1', 'E3']);
  assert.equal(suite.capacity.E1.rate, 98.4375);
  assert.equal(suite.capacity.E1.p99, 10);
  assert.deepEqual(
    suite.probes.filter(p => p.scenario === 'E1').map(p => [p.rate, p.held]),
    [
      [75, true],
      [112.5, false],
      [93.75, true],
      [103.125, false],
      [98.4375, true],
    ],
  );
  const strict = runSuite('sustainable-pg', {
    ...options,
    p99LimitMs: 5,
    out: join(dir, 'strict'),
  });
  assert.equal(strict.capacity.E1.rate, undefined);
  const report = renderReport([suite]);
  assert.match(report, /## Sustainable rates/);
  assert.match(report, /\| E1 \| \*\*98\.4\/s\*\* \| 2\.0 ms \| 10\.0 ms \|/);
});

// The hand-over helper's process functions for a test: node 4242 serves every port, its command line is `true`,
// and every call is recorded in `log`; nothing reaches a process of the host (X-66).
const fakeProcesses = log => `
node_pid() { echo "node_pid $1" >> '${log}'; echo 4242; }
node_argv() { echo "node_argv $1" >> '${log}'; ARGV=(true); }
node_cwd() { echo "node_cwd $1" >> '${log}'; pwd; }
signal() { echo "signal $1 $2" >> '${log}'; [ "$1" != 0 ]; }
`;

test('a cluster suite runs three nodes steady and with a hand-over', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const processes = join(dir, 'processes.log');
  const suite = runSuite('e5-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    cluster: { ...CLUSTER, stopAfter: 0, down: 0 },
    processes: fakeProcesses(processes),
  });
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.scenario, run.handover ?? false]),
    [
      ['e1-steady', 'E1', false],
      ['e1-handover', 'E1', true],
      ['e3-steady', 'E3', false],
      ['e3-handover', 'E3', true],
    ],
  );
  assert.equal(suite.failures, 0);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /run --backend=postgres --scenario=E1 --distribution=uniform --accounts=100000 --seed-concurrency=256 --prime --nodes=3 --rate=150\.0 --warm-up=15 --measure=90/,
  );
  // The hand-over helper ran beside bench.sh: it stopped node 2 through the process functions, and restarted it.
  const handover = readFileSync(
    join(dir, 'out', 'e1-handover', 'handover.log'),
    'utf8',
  );
  assert.match(handover, /stopping the node on port 18081 \(pid 4242\)/);
  assert.match(handover, /restarted \(pid \d+\)/);
  const signalled = readFileSync(processes, 'utf8');
  assert.ok(
    signalled.startsWith(
      'node_pid 18081\nnode_argv 4242\nnode_cwd 4242\nsignal TERM 4242\nsignal 0 4242\n',
    ),
    signalled,
  );
  // The restarted node is stopped through them too, once bench.sh is done.
  const restarted = readFileSync(
    join(dir, 'out', 'e1-handover', 'node-restarted.pid'),
    'utf8',
  ).trim();
  assert.ok(signalled.includes(`\nsignal TERM ${restarted}\n`), signalled);
  assert.match(renderReport([suite]), /## E5 cluster: steady and hand-over/);
});

test("the host's processes are never a test's: hostProcesses refuses them under the test runner", () => {
  assert.equal(hostProcesses({}), HOST_PROCESSES);
  assert.throws(
    () => hostProcesses({ NODE_TEST_CONTEXT: 'child-v8' }),
    /must not find or signal the host's processes/,
  );
  // A cluster suite given none refuses before it runs anything.
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  assert.throws(
    () =>
      runSuite('e5-pg', {
        benchPath,
        out: join(dir, 'out'),
        warmUp: 1,
        measure: 5,
        repeats: 1,
        fraction: 0.8,
      }),
    /must not find or signal the host's processes/,
  );
  assert.throws(() => readFileSync(join(dir, 'calls.log')), /ENOENT/);
  // The real functions are the only place that finds or signals a process; the helper calls them.
  assert.match(HOST_PROCESSES, /pgrep -f -- "--server.port=\$1"/);
  assert.doesNotMatch(HANDOVER_SCRIPT, /pgrep|\/proc|kill -TERM|kill -0/);
});

test('anomalies flag pauses, store fallbacks, error spikes, dips and long tails', () => {
  const run = (m, signals) => ({
    name: 'r',
    status: 'ok',
    metrics: {
      ok: 1000,
      failed: 0,
      weakSeconds: 0,
      p50: 5,
      p99: 20,
      errors: {},
      ...m,
    },
    signals,
  });
  assert.deepEqual(anomaliesOf(run({})), []);
  assert.deepEqual(
    anomaliesOf(run({ failed: 20, errors: { 'http-408': 20 } })).map(
      a => a.kind,
    ),
    ['error spike'],
  );
  assert.deepEqual(
    anomaliesOf(run({ weakSeconds: 2, minSecondOk: 10 })).map(a => a.kind),
    ['throughput dip'],
  );
  assert.deepEqual(
    anomaliesOf(run({ p50: 5, p99: 60 })).map(a => a.kind),
    ['p99 > 10× p50'],
  );
  // A 30 ms p99 over a 2 ms median is below the floor.
  assert.deepEqual(anomaliesOf(run({ p50: 2, p99: 30 })), []);
  // An overloaded run says what bound its operations in flight, as its result recorded it.
  const result = {
    load: {
      rate: 900,
      inFlightCap: 2000,
      inFlightBoundBy: 'per-target ceiling',
    },
    results: { ok: 1000, failed: 40, errors: { 'E1:generator-overload': 40 } },
  };
  const capped = metrics(result);
  assert.equal(capped.inFlightCap, 2000);
  assert.equal(capped.inFlightBoundBy, 'per-target ceiling');
  assert.match(
    anomaliesOf(
      run({
        failed: 40,
        errors: capped.errors,
        inFlightCap: 2000,
        inFlightBoundBy: 'per-target ceiling',
      }),
    ).find(a => a.kind === 'error spike').detail,
    /generator-overload: 2000 in flight at most \(per-target ceiling\)$/,
  );
  // Other errors say nothing of the cap.
  assert.doesNotMatch(
    anomaliesOf(
      run({ failed: 20, errors: { 'http-408': 20 }, inFlightCap: 2000 }),
    )[0].detail,
    /in flight/,
  );
  const paused = run(
    { failed: 500 },
    {
      partitionPaused: { 'TransferSaga.onPrepared': 2 },
      tailRead: { idle: 9, overflow: 1 },
    },
  );
  assert.deepEqual(
    anomaliesOf(paused).map(a => a.kind),
    ['partition paused', 'tail read the store', 'error spike'],
  );
  // Past the knee by design, errors are expected; the signals still count.
  assert.deepEqual(
    anomaliesOf(paused, { overload: true }).map(a => a.kind),
    ['partition paused', 'tail read the store'],
  );
  const suite = {
    name: 'cliff',
    kind: 'steps',
    runs: [{ ...paused, sustained: false }],
    probes: [],
  };
  assert.deepEqual(
    anomalies(suite).map(a => a.context),
    ['overload (cliff step)', 'overload (cliff step)'],
  );
  assert.deepEqual(
    mergeSignals([
      { t: 3, tailRead: { gap: 1 } },
      undefined,
      { t: 5, tailRead: { gap: 2, idle: 4 } },
    ]),
    { t: 5, tailRead: { gap: 3, idle: 4 } },
  );
});

test('a failed bench.sh step is recorded and counted', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = join(dir, 'bench.sh');
  writeFileSync(benchPath, '#!/bin/sh\nexit 3\n');
  chmodSync(benchPath, 0o755);
  const suite = runSuite('e3-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
  });
  assert.equal(suite.failures, 2);
  assert.equal(suite.saturation.base.status, 'failed');
  assert.equal(suite.saturation.head.status, 'failed');
  assert.equal(suite.runs.length, 0);
  assert.match(renderReport([suite]), /Failed steps:\*\* e3-pg \(2\)/);
});

test('runSettings: dispatch inputs, or the defaults with the run file over them on a push', () => {
  assert.equal(
    runSettings({ event: 'workflow_dispatch', inputs: { repeats: '3' } })
      .repeats,
    '3',
  );
  assert.equal(runSettings({ event: 'push' }).base_ref, '');
  assert.equal(
    runSettings({
      event: 'push',
      config: { base_ref: 'v10.0.0-beta.1', p99_limit_ms: 500 },
    }).p99_limit_ms,
    '500',
  );
  const pushed = runSettings({
    event: 'push',
    inputs: { repeats: '' },
    config: { suites: ['e3-pg', 'e1-mongo-zipf'], repeats: 3 },
  });
  assert.equal(pushed.suites, 'e3-pg,e1-mongo-zipf');
  assert.equal(pushed.repeats, '3');
  assert.equal(pushed.measure, '40');
  assert.throws(
    () => runSettings({ event: 'push', config: { head_ref: 'x' } }),
    /Unknown key/,
  );
  // An object (the server properties) passes as JSON, and resolveInputs reads it back.
  const withProperties = runSettings({
    event: 'push',
    config: { properties: { 'wow.state-cache.capacity': 131072 } },
  });
  assert.equal(
    withProperties.properties,
    '{"wow.state-cache.capacity":131072}',
  );
  assert.deepEqual(propertiesOf(withProperties.properties), {
    'wow.state-cache.capacity': 131072,
  });
  assert.equal(propertiesOf(''), undefined);
  assert.throws(() => propertiesOf('[1]'), /JSON object/);
  assert.throws(() => propertiesOf('{x'), /JSON object/);
});

test('lastSignals reads the last signals line', () => {
  assert.deepEqual(
    lastSignals(
      'a\n2026 WARN BenchSignals bench-signals {"t":1}\nb bench-signals {"t":2,"refused":{"wow.command.busy":4}}\nc',
    ),
    { t: 2, refused: { 'wow.command.busy': 4 } },
  );
  assert.equal(lastSignals('nothing'), undefined);
});

test('signalsWithin counts what grew in the measured phase only', () => {
  const log = [
    '2026-10-22T10:14:44.000Z  WARN 1 --- [x] BenchSignals : bench-signals {"t":1,"signalDropped":{"failed":259},"tailRead":{"idle":900}}',
    '2026-10-22T10:22:54.000Z  WARN 1 --- [x] BenchSignals : bench-signals {"t":2,"signalDropped":{"failed":259},"tailRead":{"idle":910}}',
    'noise without a time bench-signals {"t":9,"refused":{"wow.command.busy":99}}',
    '2026-10-22T10:23:30.000Z  WARN 1 --- [x] BenchSignals : bench-signals {"t":3,"signalDropped":{"failed":259},"tailRead":{"idle":915}}',
    '2026-10-22T10:24:40.000Z  WARN 1 --- [x] BenchSignals : bench-signals {"t":4,"signalDropped":{"failed":300},"tailRead":{"idle":999}}',
  ].join('\n');
  const from = Date.parse('2026-10-22T10:23:00Z');
  const to = Date.parse('2026-10-22T10:24:00Z');
  assert.deepEqual(signalsWithin(log, from, to), {
    t: 3,
    tailRead: { idle: 5 },
  });
  // A node started in the window (restarted in a hand-over) counts from zero.
  assert.deepEqual(
    signalsWithin(
      '2026-10-22T10:23:10Z INFO bench-signals {"t":1,"forwardFailed":{"local":4}}',
      from,
      to,
    ),
    { t: 1, forwardFailed: { local: 4 } },
  );
  assert.equal(signalsWithin(log, 0, from - 3_600_000), undefined);
  assert.equal(signalsWithin('nothing', from, to), undefined);
});

test("measuredWindow is the result's measured phase, with a second's grace", () => {
  assert.deepEqual(
    measuredWindow({
      startedAt: '2026-10-22T10:22:39.907Z',
      load: { warmUpSeconds: 15, measureSeconds: 90 },
    }),
    {
      from: Date.parse('2026-10-22T10:22:54.907Z'),
      to: Date.parse('2026-10-22T10:24:25.907Z'),
    },
  );
  assert.equal(
    measuredWindow({ load: { warmUpSeconds: 15, measureSeconds: 90 } }),
    undefined,
  );
  assert.equal(measuredWindow(undefined), undefined);
});

test('a cliff suite steps up until two steps in a row are not kept up', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const suite = runSuite('cliff-e3-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
  });
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.sustained]),
    [
      ['s1-50', true],
      ['s2-100', true],
      ['s3-200', false],
      ['s4-300', false],
    ],
  );
  assert.deepEqual(suite.runs[0].signals, { t: 2, tailRead: { gap: 3 } });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /--scenario=E3 .*--rate=50\.0 --warm-up=10 --measure=60 .*props=\{"bench\.signals\.enabled":true\}/,
  );
  const report = renderReport([suite]);
  assert.match(report, /## E3 cliff steps/);
  assert.match(report, /\| s3-200 \| 200\.0\/s \| 0\.0\/s \| \*\*no\*\* \|/);
  assert.match(report, /tailRead gap=3/);
});

test('a saturation found at the concurrency cap is marked as a lower bound', () => {
  const suite = {
    name: 'e3-mongo',
    kind: 'paired',
    arms: [{ id: 'base' }, { id: 'head' }],
    saturation: {
      base: { status: 'ok', throughput: 200, concurrency: 128 },
      head: { status: 'ok', throughput: 500, concurrency: 4096, capped: true },
    },
    rates: {},
    runs: [],
    failures: 0,
  };
  const report = renderReport([suite]);
  assert.match(report, /\| e3-mongo@100k \| head \| ≥ 500\.0\/s \| 4096 \|/);
});

test('A/A runs give the noise thresholds; bootstrap intervals decide what clears them', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const suite = runSuite('e1-mongo-uniform', {
    benchPath,
    out: join(dir, 'aa'),
    warmUp: 1,
    measure: 5,
    repeats: 2,
    fraction: 0.8,
    aa: true,
    dataset: '1k',
    cpus: { load: '3', server: '0-2' },
  });
  // The head against itself, no baseline build; pinned CPUs and the 1k data set on every call.
  assert.equal(suite.aa, true);
  assert.deepEqual(
    suite.arms.map(arm => [arm.id, arm.install]),
    [
      ['a', 'head'],
      ['b', 'head'],
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.doesNotMatch(calls, /--install=/);
  assert.match(
    calls,
    /--accounts=1000 --seed-concurrency=32 --load-cpus=3 --server-cpus=0-2/,
  );
  // At 1k the runs are not primed; an A/A run takes at least 4 equal-rate rounds and no drift round.
  assert.equal(suite.primed, false);
  assert.doesNotMatch(calls, /--prime/);
  assert.equal(suite.runs.filter(run => run.mode === 'equal').length, 8);
  assert.equal(suite.extraRound, undefined);
  const noise = noiseFile([suite], { 'e3-pg@100k': { p99: 0.1 } });
  assert.deepEqual(Object.keys(noise), ['e3-pg@100k', 'e1-mongo-uniform@1k']);
  // The thresholds are the equal-rate intervals' half-widths, at least 2%: identical arms there give the floor.
  assert.deepEqual(noise['e1-mongo-uniform@1k'], {
    throughput: 0.02,
    p50: 0.02,
    p99: 0.02,
  });
  const report = renderReport([suite], { noise: {} });
  assert.match(report, /## A\/A noise/);
  assert.match(report, /e1-mongo-uniform@1k \(A\/A: head against head\)/);

  // With properties the A/A's second arm runs changed: a paired probe, not noise.
  const probe = runSuite('e1-mongo-uniform', {
    benchPath,
    out: join(dir, 'probe'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.5,
    aa: true,
    dataset: '1k',
    properties: { 'wow.state-cache.capacity': 131072 },
  });
  assert.deepEqual(
    probe.arms.map(arm => arm.props),
    [undefined, { 'wow.state-cache.capacity': 131072 }],
  );
  assert.deepEqual(probe.properties, { 'wow.state-cache.capacity': 131072 });
  assert.deepEqual(Object.keys(noiseFile([probe])), []);

  // An A/A suite that measured no equal-rate pair (its saturation failed) has no noise: it keeps what it had.
  const failed = {
    ...suite,
    runs: suite.runs.filter(run => run.mode !== 'equal'),
  };
  assert.equal(noiseOf(failed), null);
  assert.deepEqual(
    noiseFile([failed], { 'e1-mongo-uniform@1k': { p99: 0.3 } }),
    { 'e1-mongo-uniform@1k': { p99: 0.3 } },
  );
  assert.match(
    renderReport([failed], { noise: {} }),
    /\| e1-mongo-uniform@1k \| no data \| no data \| no data \|/,
  );

  // Bootstrap: identical samples give a ratio of 1 with a tight interval; a 20% shift clears a 5% threshold.
  const a = Array.from({ length: 40 }, (_, i) => 100 + (i % 5));
  const same = bootstrapRatio(a, a, 'mean');
  assert.ok(same.lo <= 1 && same.hi >= 1 && same.hi - same.lo < 0.03);
  const faster = bootstrapRatio(
    a,
    a.map(v => v * 1.2),
    'mean',
  );
  assert.ok(faster.lo > 1.15);
  assert.equal(verdict(faster, 0.05), 'higher');
  assert.equal(verdict(same, 0.05), 'within noise');
  assert.equal(verdict(faster, undefined), 'uncalibrated');
  assert.equal(verdict(undefined, 0.05), 'no data');
  assert.equal(
    confidence({ arms: [{ id: 'x' }], runs: [] }, 'equal').p99,
    undefined,
  );
});

test('stability compares the halves; a busy load generator and an unstable run are flagged', () => {
  // p99 is compared only with a thousand tail samples a half (a hundred thousand operations): not here.
  assert.deepEqual(
    halves([100, 100, 100, 100], [10, 10, 10, 10], [2, 2, 2, 2]),
    {
      throughputChange: 0,
      p50Ratio: 1,
      p99Ratio: undefined,
    },
  );
  assert.deepEqual(halves([100, 100, 80, 80], [10, 10, 20, 20], [2, 2, 3, 3]), {
    throughputChange: -0.19999999999999996,
    p50Ratio: 1.5,
    p99Ratio: undefined,
  });
  assert.deepEqual(
    halves([60000, 60000, 60000, 60000], [10, 10, 20, 20]).p99Ratio,
    2,
  );
  assert.equal(halves([1, 2], [1, 2]), undefined);
  const run = m => ({
    name: 'r',
    status: 'ok',
    metrics: {
      ok: 1000,
      failed: 0,
      weakSeconds: 0,
      p50: 5,
      p99: 20,
      errors: {},
      ...m,
    },
  });
  assert.deepEqual(
    anomaliesOf(
      run({ stability: { throughputChange: -0.2, p99Ratio: 1 } }),
    ).map(a => a.kind),
    ['unstable'],
  );
  assert.deepEqual(
    anomaliesOf(run({ stability: { throughputChange: 0.01, p99Ratio: 1.2 } })),
    [],
  );
  // The suite's A/A noise replaces the defaults: a 20% p50 drift is within ±25%, beyond an A/A ±10%.
  const drift = run({ stability: { throughputChange: 0, p50Ratio: 1.2 } });
  assert.deepEqual(anomaliesOf(drift), []);
  assert.match(
    anomaliesOf(drift, { noise: { throughput: 0.02, p50: 0.1, p99: 0.5 } })[0]
      .detail,
    /beyond the A\/A noise: p50 \+20\.0% \(±10\.0%\)/,
  );
  assert.deepEqual(
    anomaliesOf(run({ loadGeneratorCores: 0.95, loadGeneratorCpus: 1 })).map(
      a => a.kind,
    ),
    ['load generator saturated'],
  );
  const m = metrics({
    load: { rate: 100 },
    results: {
      throughput: 100,
      perSecond: [
        { ok: 100, p50Ms: 1, p99Ms: 5 },
        { ok: 100, p50Ms: 1, p99Ms: 5 },
      ],
    },
    resources: {
      processes: { 'node-1': { averageCores: 0.5 } },
      containers: { 'wow-e2e-bench-mongo': { cpuPercentAverage: 120 } },
      loadGenerator: { averageCores: 0.3, availableProcessors: 1 },
    },
  });
  assert.deepEqual(m.bottleneck, { side: 'mongo', cores: 1.2 });
  assert.deepEqual(m.p99Series, [5, 5]);
});

test('a mixed suite sends its mix and shows each scenario', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  runSuite('mixed-pg', {
    benchPath,
    out: join(dir, 'mixed'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    installs: { base: '/b' },
  });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /--scenario=mixed --mix=E1:50,E4-state:40,E4-list:10 --distribution=uniform/,
  );
});

test('the walk suites measure the walk uncapped per build, then live E1 at each rate against two runs without a walk', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = join(dir, 'bench.sh');
  // Each run writes its measure.json: the walk at the configured rate, the deposits' p99 by run.
  writeFileSync(
    benchPath,
    `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
const opt = name => args.find(a => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
fs.appendFileSync(${JSON.stringify(join(dir, 'calls.log'))}, args.join(' ') + ' ' + (process.env.SPRING_APPLICATION_JSON ?? '') + '\\n');
const rate = JSON.parse(process.env.SPRING_APPLICATION_JSON ?? '{}')['wow.access.walk-rate'];
const walks = opt('walk') !== 'none';
const p99 = { 500: 20, 1000: 24, 2000: 40 }[rate] ?? 22;
fs.mkdirSync(opt('out'), { recursive: true });
fs.writeFileSync(opt('out') + '/measure.json', JSON.stringify({
  program: 'walk', walks, rate: Number(opt('rate')), walkRate: rate === undefined ? null : String(rate),
  walk: walks ? { completed: true, walked: 1000, seconds: 2, aggregatesPerSecond: 500 } : null,
  e1: Number(opt('rate')) > 0 ? { ops: 100, failed: {}, latencyMs: { p50: 5, p99: walks ? p99 : (opt('out').endsWith('none-1') ? 21 : 25), p999: 50 } } : null,
}));
`,
  );
  chmodSync(benchPath, 0o755);
  const suite = runSuite('access-walk-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    dataset: '1k',
    installs: {},
  });
  assert.equal(suite.failures, 0);
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    [
      ['base-uncapped', 'unavailable'],
      ['head-uncapped', 'ok'],
      ['none-1', 'ok'],
      ['walk-500', 'ok'],
      ['walk-1000', 'ok'],
      ['walk-2000', 'ok'],
      ['none-2', 'ok'],
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /^measure --backend=postgres --program=walk --aggregates=1000 --streams=10 --walk=yes --rate=0 /m,
  );
  // A run with nothing to walk sets no walk rate.
  assert.match(calls, /--walk=none --rate=200 .*\/none-1 $/m);
  const table = measureTable(suite).join('\n');
  // The band is the higher of the two runs without a walk, 25 ms: 2,000/s leaves it.
  assert.match(
    table,
    /\| access-walk-pg@1k \| head-uncapped \| uncapped \| 2\.0 s \| 500 \|/,
  );
  assert.match(table, /\| walk-1000 \| 1000\/s \| .* \| yes \|/);
  assert.match(
    table,
    /\| walk-2000 \| 2000\/s \| .* \| \*\*no\*\* \(40\.0 ms > 25\.0 ms\) \|/,
  );
  assert.deepEqual(pairs(suite), []);
  assert.doesNotMatch(renderReport([suite]), /NaN/);
});

test('component suites run each scenario per arm and tolerate a baseline without ComponentBench', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = join(dir, 'bench.sh');
  // The baseline build (with --install) has no ComponentBench: exit 3; the head writes its counts.
  writeFileSync(
    benchPath,
    `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
const opt = name => args.find(a => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
if (opt('install')) process.exit(3);
fs.mkdirSync(opt('out'), { recursive: true });
fs.writeFileSync(opt('out') + '/component.json', JSON.stringify({ scenario: opt('scenario'), throughput: 1000, storeRoundTripsPerOp: 4.5, storeRoundTripsByCommand: { insert: 1 }, allocatedBytesPerOp: 250000, latencyMs: { p50: 10, p99: 30 } }));
`,
  );
  chmodSync(benchPath, 0o755);
  const suite = runSuite('component-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    dataset: '1k',
    installs: { base: '/b' },
  });
  assert.equal(suite.failures, 0);
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    [
      ['e1-base', 'unavailable'],
      ['e1-head', 'ok'],
      ['e3-head', 'ok'],
      ['e3-base', 'unavailable'],
      ['e1-large-base', 'unavailable'],
      ['e1-large-head', 'ok'],
    ],
  );
  const report = renderReport([suite]);
  assert.match(report, /## L1 component counts/);
  assert.match(report, /\| component-pg@1k \| E1 \| head \| 4\.50 \|/);
  // Its runs have no rounds: no pairs table, which printed a NaN row (roadmap §6 performance item 6).
  assert.deepEqual(pairs(suite), []);
  assert.doesNotMatch(report, /Pairs at the calibrated rates/);
  assert.doesNotMatch(report, /NaN/);
});

test('a cold suite runs at a million accounts whatever the job says', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const suite = runSuite('cold-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    dataset: '100k',
  });
  assert.equal(suite.dataset, '1M');
  // Cold on purpose: a million accounts, never primed.
  assert.equal(suite.primed, false);
  assert.doesNotMatch(readFileSync(join(dir, 'calls.log'), 'utf8'), /--prime/);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.match(
    calls,
    /--scenario=E4-state .*--accounts=1000000 --seed-concurrency=512/,
  );
  assert.deepEqual(
    suite.runs.map(run => run.name),
    ['d1-head-e1-200', 'd2-head-e4-state-500'],
  );
});

test('profile adds one recorded run per arm after the timed rounds', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const suite = runSuite('e3-pg', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    profile: true,
    installs: { base: '/b' },
  });
  assert.deepEqual(
    suite.profiles.map(run => [run.name, run.rate]),
    [
      ['profile-base', 90],
      ['profile-head', 90],
    ],
  );
  // The timed rounds carry no recording; the profile runs do.
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').split('\n');
  assert.ok(
    calls
      .filter(line => line.includes('--jfr'))
      .every(line => /profile-(base|head)/.test(line)),
  );
  assert.equal(calls.filter(line => line.includes('--jfr')).length, 2);
  assert.deepEqual(
    suite.runs.filter(run => run.name.startsWith('profile')),
    [],
  );
});

test('large data sets are primed before every run; small ones are not; the report says what each started from', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  const options = {
    benchPath,
    warmUp: 1,
    measure: 5,
    repeats: 1,
    fraction: 0.8,
    installs: { base: '/b' },
  };
  const big = runSuite('e1-pg-uniform', {
    ...options,
    dataset: '100k',
    out: join(dir, 'big'),
  });
  assert.equal(big.primed, true);
  const small = runSuite('e1-pg-uniform', {
    ...options,
    dataset: '1k',
    out: join(dir, 'small'),
  });
  assert.equal(small.primed, false);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8')
    .split('\n')
    .filter(Boolean);
  assert.ok(
    calls
      .filter(line => line.includes('/big/'))
      .every(line => line.includes('--prime')),
  );
  assert.ok(
    calls
      .filter(line => line.includes('/small/'))
      .every(line => !line.includes('--prime')),
  );
  const report = renderReport([big, small]);
  assert.match(
    report,
    /Primed: every run touched each of the 100000 accounts once before its warm-up/,
  );
  // 100k accounts fit the state cache a 1 GB heap holds by bytes (B.O-4): no evictions to warn of.
  assert.doesNotMatch(report, /exceed the node's state cache/);
  assert.match(report, /Not primed: 1000 accounts, below 100000\./);
});

test('p99.9 is reported only with a thousand tail samples', () => {
  const few = metrics({ results: { ok: 40000, latencyMs: { p999: 50 } } });
  assert.equal(few.p999, undefined);
  const many = metrics({ results: { ok: 1000000, latencyMs: { p999: 50 } } });
  assert.equal(many.p999, 50);
  const primed = metrics({
    results: {},
    meta: { primed: 'true', primeSeconds: '35', settleSeconds: '12' },
  });
  assert.deepEqual(
    [primed.primed, primed.primeSeconds, primed.settleSeconds],
    [true, 35, 12],
  );
});

test('the bulkhead refusals are counted on their own, beside the query bound that refused them', () => {
  const errors = {
    [`E4-list:read-http-429-${BUSY}`]: 30,
    [`E4-state:read-http-429-${BUSY}`]: 2,
    'E1:http-409-wow.command.busy': 5,
  };
  assert.equal(busyOf(errors), 32);
  assert.equal(busyOf({}), 0);
  assert.equal(metrics({ results: { ok: 900, failed: 37, errors } }).busy, 32);

  // The per-second lines of a node: the bound before the window, lowered twice within it, and once after.
  const at = second =>
    `2026-10-28T20:00:${String(second).padStart(2, '0')}.000Z`;
  const line = (second, limit, decreases, pressed) =>
    `${at(second)}  WARN 1 --- [x] BenchQueryLimits : bench-query-limits {"t":${second},` +
    `"mongo":{"limit":${limit},"ceiling":50,"running":3,"queued":0,"adaptive":true,"floor":1,"pressure":${pressed},` +
    `"decreases":${decreases},"appends":10,"waitingForConnection":0},` +
    `"postgres":{"limit":8,"ceiling":8,"running":0,"queued":0}}`;
  const log = [
    line(1, 25, 4, false),
    line(2, 12, 5, true),
    line(3, 12, 5, true),
    line(4, 6, 6, true),
    line(5, 7, 6, false),
    line(9, 3, 7, true),
  ].join('\n');
  const limits = queryLimitsWithin(log, Date.parse(at(2)), Date.parse(at(5)));
  assert.deepEqual(limits, {
    mongo: { min: 6, max: 12, ceiling: 50, lowered: 2, pressedSeconds: 3 },
    postgres: { min: 8, max: 8, ceiling: 8 },
  });
  assert.equal(
    queryLimitsText(limits),
    'mongo 6–12 of 50, lowered 2, pressed 3 s · postgres 8 of 8',
  );
  assert.equal(
    queryLimitsWithin(log, Date.parse(at(6)), Date.parse(at(8))),
    undefined,
  );
  assert.equal(queryLimitsText(undefined), '-');

  // An error spike names them as the bulkhead's, with the bound; other errors keep their kinds.
  const spike = anomaliesOf({
    name: 'r',
    status: 'ok',
    metrics: {
      ok: 900,
      failed: 37,
      weakSeconds: 0,
      p50: 5,
      p99: 20,
      errors,
      busy: busyOf(errors),
    },
    queryLimits: {
      mongo: { min: 25, max: 25, ceiling: 50, lowered: 0, pressedSeconds: 0 },
    },
  }).find(a => a.kind === 'error spike');
  assert.match(
    spike.detail,
    /; 32 wow\.query\.busy \(bulkhead\), bound mongo 25 of 50, lowered 0, pressed 0 s$/,
  );
});

test('two saturations more than 3× apart get the lower probed again, the first kept beside it', () => {
  assert.equal(SATURATION.outlier, 3);
  const ids = ['a', 'b'];
  assert.equal(
    saturationOutlier({ a: { throughput: 990 }, b: { throughput: 202 } }, ids),
    'b',
  );
  assert.equal(
    saturationOutlier({ a: { throughput: 100 }, b: { throughput: 300 } }, ids),
    undefined,
  );
  assert.equal(
    saturationOutlier({ a: { throughput: 990 }, b: {} }, ids),
    undefined,
  );

  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeBench(dir);
  process.env.FAKE_STALLED_SATURATION = 'e1-mongo-uniform-saturation-b';
  try {
    const suite = runSuite('e1-mongo-uniform', {
      benchPath,
      warmUp: 1,
      measure: 5,
      repeats: 1,
      fraction: 0.5,
      aa: true,
      dataset: '1k',
      out: join(dir, 'aa'),
    });
    const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
    assert.match(calls, /--label=e1-mongo-uniform-saturation-b-again/);
    assert.deepEqual(suite.saturation.b.first, {
      status: 'ok',
      throughput: 40,
      concurrency: 1,
      capped: false,
      generatorCores: undefined,
      generatorCpus: undefined,
    });
    assert.equal(suite.saturation.b.throughput, 150);
    assert.match(
      renderReport([suite], { noise: {} }),
      /150\.0\/s \(probed again; first 40\.0\/s at concurrency 1\)/,
    );
  } finally {
    delete process.env.FAKE_STALLED_SATURATION;
  }
});

// A stand-in for bench.sh's sessions: `up` and `down`, and the open-loop runs and sweeps run on the session
// (`--reuse`). The Axon server (the build with --install) costs more per operation, answers slower and levels off at
// 64 clients, failing some requests at 4096; Wow doubles a step up to 4096 and levels off at 16384. A step's p99 is a
// tenth of its clients in ms. `up` writes the node's startup and the prime; `inspect` finds stored every event the
// session's runs and sweeps sent (FAKE_LOST_CONFIRMATION names an `inspect` whose store lost one). FAKE_FAIL_UP names the
// `up` that fails.
function fakeSessionBench(dir) {
  const path = join(dir, 'bench.sh');
  writeFileSync(
    path,
    `#!/usr/bin/env node
const fs = require('node:fs');
const [command, ...args] = process.argv.slice(2);
const opt = name => args.find(a => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
fs.appendFileSync(${JSON.stringify(join(dir, 'calls.log'))}, [command, ...args, 'props=' + (process.env.SPRING_APPLICATION_JSON ?? '')].join(' ') + '\\n');
if (command === 'down') process.exit(0);
const out = opt('out');
fs.mkdirSync(out, { recursive: true });
const axon = Boolean(opt('install'));
if (command === 'up') {
  if (process.env.FAKE_FAIL_UP && out.endsWith(process.env.FAKE_FAIL_UP)) process.exit(1);
  fs.writeFileSync(out + '/startup.json', JSON.stringify({ nodes: [{ node: 1, readyMs: axon ? 9000 : 6000, rssKb: axon ? 400000 : 300000 }] }));
  fs.writeFileSync(out + '/prime.json', JSON.stringify({ accounts: 100000, seconds: 50, failed: 0 }));
  process.exit(0);
}
if (command === 'inspect') {
  const path = require('node:path');
  const root = path.dirname(out);
  const session = path.basename(out).replace(/-inspect$/, '');
  // up's check sent one deposit and one transfer, and the prime a deposit to each account.
  let deposits = 100001;
  let transfers = 1;
  for (const name of fs.readdirSync(root).filter(name => name.startsWith(session + '-'))) {
    const result = path.join(root, name, 'result.json');
    const saturation = path.join(root, name, 'saturation.json');
    const sent = fs.existsSync(result)
      ? JSON.parse(fs.readFileSync(result, 'utf8')).results.sent.ok
      : fs.existsSync(saturation)
        ? JSON.parse(fs.readFileSync(saturation, 'utf8')).steps.reduce((sum, step) => sum + step.sentOk, 0)
        : 0;
    if (/-e3/.test(name)) transfers += sent;
    else deposits += sent;
  }
  const lost = process.env.FAKE_LOST_CONFIRMATION && out.endsWith(process.env.FAKE_LOST_CONFIRMATION) ? 1 : 0;
  const events = { AccountOpened: 100000, Deposited: deposits, AmountLocked: transfers, Prepared: transfers, AmountEntered: transfers, Confirmed: transfers - lost };
  const tables = [
    { name: 'log', kind: 'events', rows: 1, totalBytes: 50000000, indexBytes: 10000000 },
    { name: 'snapshot', kind: 'snapshots', rows: 1, totalBytes: 20000000, indexBytes: 1000000 },
    { name: 'token', kind: 'other', rows: 1, totalBytes: 1000000, indexBytes: 0 },
  ];
  fs.writeFileSync(out + '/inspect.json', JSON.stringify({ rssKb: [axon ? 500000 : 450000], store: { tables, records: deposits, snapshots: 100000, events } }));
  process.exit(0);
}
if (command === 'saturate') {
  const from = Number(opt('start-concurrency') ?? 1);
  const to = Number(opt('max-concurrency'));
  const given = opt('concurrencies')?.split(',').map(Number);
  const steps = (given ?? [1, 4, 16, 64, 256, 1024, 4096, 16384].filter(c => c >= from && c <= to)).map(concurrency => {
    const index = Math.round(Math.log(concurrency) / Math.log(4));
    const throughput = axon ? Math.min(100 * 2 ** index, 800) : concurrency >= 16384 ? 13000 : 200 * 2 ** index;
    return {
      concurrency,
      seconds: Number(opt('step')),
      throughput,
      ok: throughput * 30,
      errors: axon && concurrency === 4096 ? { 'http-504-timeout': 7 } : {},
      // The warm-up's 10 s too.
      sentOk: throughput * 40,
      sentFailed: axon && concurrency === 4096 ? 7 : 0,
      p50Ms: 1,
      p99Ms: concurrency / 10,
      generatorCores: 0.5,
    };
  });
  const best = steps.reduce((a, b) => (b.throughput > a.throughput ? b : a));
  fs.writeFileSync(out + '/saturation.json', JSON.stringify({ saturationThroughput: best.throughput, saturationConcurrency: best.concurrency, generatorCores: 0.5, availableProcessors: 2, steps }));
} else {
  const rate = Number(opt('rate'));
  fs.writeFileSync(out + '/result.json', JSON.stringify({
    load: { rate },
    meta: { primed: 'true', primeSeconds: '90', settleSeconds: '4' },
    results: { perSecond: [{ ok: rate }], throughput: rate, ok: rate * 40, failed: 0, sent: { ok: rate * 60, failed: 0 }, errors: {}, latencyMs: { p50: axon ? 2 : 1, p95: 3, p99: axon ? 20 : 5, p999: 30 }, worstSecondP99Ms: 25 },
    resources: {
      seconds: 40,
      processes: { 'node-1': { averageCores: axon ? 0.6 : 0.5, rssMaxMb: axon ? 700 : 600, gc: { delta: { GCT: axon ? 0.8 : 0.4 } } } },
      containers: { 'wow-e2e-bench-postgres': { cpuPercentAverage: axon ? 40 : 25 }, 'wow-e2e-bench-kafka': { cpuPercentAverage: 1 } },
      stores: { postgres: { delta: { xact_commit: rate * 40 * (axon ? 2 : 1.5), wal_bytes: rate * 40 * (axon ? 600 : 400) } } },
    },
  }));
}
`,
  );
  chmodSync(path, 0o755);
  return path;
}

test('the Axon comparison runs one session per arm ABBA: one rate for both, the sweeps, then each at its own share', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  const out = join(dir, 'results', 'axon-pg');
  const suite = runSuite('axon-pg', {
    benchPath,
    out,
    warmUp: 30,
    measure: 40,
    repeats: 2,
    fraction: 0.8,
    cpus: { total: 4 },
    installs: { axon: '/builds/axon-server' },
  });
  assert.equal(suite.failures, 0);
  assert.deepEqual(
    suite.sessions.map(session => [session.name, session.status]),
    [
      ['1-wow', 'ok'],
      ['1-axon', 'ok'],
      ['2-axon', 'ok'],
      ['2-wow', 'ok'],
    ],
  );
  // The session's own timing, as compare.sh's, whatever the run's settings.
  assert.equal(suite.warmUp, SESSIONS.warmUp);
  assert.equal(suite.share, 0.5);
  // No Kafka: neither server uses one.
  assert.deepEqual(suite.cpus, { load: '2-3', server: '0-1' });
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  // Per session: up, the same-rate runs, the sweeps (each located with short steps, Wow's past the cap a step at a
  // time, then measured at full length around its peak and sustainable rate), the own-share runs, the store's
  // inspection, down.
  assert.deepEqual(
    calls.slice(0, 13).map(call => call.split(' ')[0]),
    [
      'up',
      'run',
      'run',
      'saturate',
      'saturate',
      'saturate',
      'saturate',
      'saturate',
      'saturate',
      'run',
      'run',
      'inspect',
      'down',
    ],
  );
  // The inspection names the session's servers again, and the SQL of the arm's store.
  assert.ok(
    calls.includes(
      `inspect --backend=postgres --server-args=--wow.transport=none --no-bus --reuse --accounts=100000 --sql=${join(dir, '..', '..', '..', 'test/benchmarks/server/inspect.sql')} --out=${join(out, '1-wow-inspect')} props=`,
    ),
  );
  assert.ok(
    calls.some(call =>
      call.startsWith(
        `inspect --backend=postgres --install=/builds/axon-server --no-bus --reuse --accounts=100000 --sql=${join(dir, '..', '..', '..', 'test/benchmarks/axon-server/inspect.sql')} `,
      ),
    ),
  );
  // Every session's store holds what its runs sent.
  assert.deepEqual(
    suite.sessions.map(session => session.correctness.comparable),
    [true, true, true, true],
  );
  assert.deepEqual(
    suite.sessions[0].correctness.checks.map(check => [check.name, check.ok]),
    [
      ['accounts opened', true],
      ['deposits stored', true],
      ['transfers stored', true],
      ['transfer amounts locked', true],
      ['transfers entered or refused', true],
      ['entries confirmed', true],
      ['refusals unlocked', true],
    ],
  );
  assert.equal(calls.filter(call => call === 'down props=').length, 4);
  // Wow without a bus and Axon's own build; each session seeds and primes as compare.sh does, with no properties.
  assert.ok(
    calls.includes(
      `up --backend=postgres --server-args=--wow.transport=none --no-bus --mix=E1,E3 --accounts=100000 --seed-concurrency=128 --prime --load-cpus=2-3 --server-cpus=0-1 --out=${join(out, '1-wow-up')} props=`,
    ),
  );
  assert.ok(
    calls.includes(
      `run --backend=postgres --install=/builds/axon-server --no-bus --reuse --scenario=E3 --distribution=uniform --accounts=100000 --load-cpus=2-3 --server-cpus=0-1 --rate=300.0 --warm-up=20 --measure=40 --label=axon-pg-1-axon-e3 --out=${join(out, '1-axon-e3')} props=`,
    ),
  );
  // Short steps locate the peak.
  assert.ok(
    calls.includes(
      `saturate --backend=postgres --server-args=--wow.transport=none --no-bus --reuse --scenario=E1 --distribution=uniform --accounts=100000 --load-cpus=2-3 --server-cpus=0-1 --max-concurrency=4096 --sweep --growth=4 --step=8 --warm-up=3 --label=axon-pg-2-wow-e1-sweep-locate --out=${join(out, '2-wow-e1-sweep-locate')} props=`,
    ),
  );
  // Still rising at 4096, Wow's goes on alone at 16384, where it levels off.
  assert.ok(
    calls.includes(
      `saturate --backend=postgres --server-args=--wow.transport=none --no-bus --reuse --scenario=E1 --distribution=uniform --accounts=100000 --load-cpus=2-3 --server-cpus=0-1 --start-concurrency=16384 --max-concurrency=16384 --sweep --growth=4 --step=8 --warm-up=3 --label=axon-pg-1-wow-e1-sweep-locate-16384 --out=${join(out, '1-wow-e1-sweep-locate-16384')} props=`,
    ),
  );
  assert.ok(!calls.some(call => /--start-concurrency=65536/.test(call)));
  // Full-length steps then measure the peak, 16384, beside its better neighbour, 4096, and the sustainable rate,
  // 1024 (p99 102.4 ms), beside the step above it; a failing one ends after 10 s.
  assert.ok(
    calls.includes(
      `saturate --backend=postgres --server-args=--wow.transport=none --no-bus --reuse --scenario=E1 --distribution=uniform --accounts=100000 --load-cpus=2-3 --server-cpus=0-1 --concurrencies=1024,4096,16384 --step=30 --warm-up=10 --fail-after=10 --label=axon-pg-2-wow-e1-sweep --out=${join(out, '2-wow-e1-sweep')} props=`,
    ),
  );
  // Axon's peak, 64, and its sustainable rate, the same step, each beside 256.
  assert.ok(
    calls.some(call =>
      call.startsWith(
        `saturate --backend=postgres --install=/builds/axon-server --no-bus --reuse --scenario=E1 --distribution=uniform --accounts=100000 --load-cpus=2-3 --server-cpus=0-1 --concurrencies=64,256 --step=30 --warm-up=10 --fail-after=10 `,
      ),
    ),
  );
  // Axon's own share: half of the 800/s its sweep held at 64 clients.
  assert.ok(
    calls.includes(
      `run --backend=postgres --install=/builds/axon-server --no-bus --reuse --scenario=E1 --distribution=uniform --accounts=100000 --load-cpus=2-3 --server-cpus=0-1 --rate=400.0 --warm-up=20 --measure=40 --label=axon-pg-1-axon-e1-own --out=${join(out, '1-axon-e1-own')} props=`,
    ),
  );
  // A run on the session seeds and primes nothing.
  assert.ok(
    calls
      .filter(call => call.includes('--reuse'))
      .every(call => !/--prime|--seed-concurrency/.test(call)),
  );
  assert.deepEqual(
    suite.sweeps.map(run => run.name),
    [
      '1-wow-e1-sweep',
      '1-wow-e3-sweep',
      '1-axon-e1-sweep',
      '1-axon-e3-sweep',
      '2-axon-e1-sweep',
      '2-axon-e3-sweep',
      '2-wow-e1-sweep',
      '2-wow-e3-sweep',
    ],
  );
  const [wow, , axon] = suite.sweeps;
  assert.deepEqual(wow.locatedPast, ['1-wow-e1-sweep-locate-16384']);
  assert.equal(wow.extended, undefined);
  assert.equal(wow.end, 'levelled');
  // The full-length steps are the sweep's; the short ones that located them are kept beside them.
  assert.deepEqual(
    wow.steps.map(step => step.concurrency),
    [1024, 4096, 16384],
  );
  assert.deepEqual(
    wow.located.map(step => step.concurrency),
    [1, 4, 16, 64, 256, 1024, 4096, 16384],
  );
  assert.equal(wow.top, 16384);
  assert.deepEqual(
    [wow.peak.throughput, wow.peak.concurrency, wow.peak.lowerBound],
    [13000, 16384, true],
  );
  assert.deepEqual(wow.sustainable, {
    throughput: 6400,
    concurrency: 1024,
    p99: 102.4,
    lowerBound: false,
  });
  assert.equal(axon.extended, undefined);
  assert.deepEqual(
    [axon.peak.throughput, axon.peak.concurrency, axon.peak.lowerBound],
    [800, 64, false],
  );
  // Wow's peak is at its top step, a lower bound, and so is the ratio from it; the sustainable rates are not.
  assert.deepEqual(peakRatio(suite, 'E1'), {
    lo: 16.25,
    hi: 16.25,
    bound: '≥',
  });
  assert.deepEqual(peakRatio(suite, 'E1', 'sustainable'), {
    lo: 8,
    hi: 8,
    bound: '',
  });
  assert.deepEqual(
    suite.runs.slice(0, 4).map(run => [run.name, run.view, run.rate]),
    [
      ['1-wow-e1', SAME, 1000],
      ['1-wow-e3', SAME, 300],
      ['1-wow-e1-own', OWN, 3200],
      ['1-wow-e3-own', OWN, 3200],
    ],
  );
  assert.equal(suite.runs[0].metrics.commitsPerOp, 1.5);
  // The runs on a session are a session's, not pairs.
  assert.deepEqual(pairs(suite), []);

  const report = renderReport(readSuites(join(dir, 'results')));
  assert.match(report, /## The industry comparison/);
  assert.match(
    report,
    /\| E1 \| ≥ 13000\/s at 16384 \(top step\); ≥ 13000\/s at 16384 \(top step\) \| 800\/s at 64; 800\/s at 64 \| ≥ 16\.25–16\.25 \| 0\.0% \| wow higher \|/,
  );
  assert.match(
    report,
    /\| E1 \| 6400\/s at 1024 \(p99 102\.4 ms\); 6400\/s at 1024 \(p99 102\.4 ms\) \| 800\/s at 64 \(p99 6\.4 ms\); 800\/s at 64 \(p99 6\.4 ms\) \| 8\.00–8\.00 \| 0\.0% \| wow higher \|/,
  );
  // The same-rate runs, then the own-share runs.
  assert.match(
    report,
    /\*\*Same rate\*\*[\s\S]*\| E1 \| Rate \| 1000\/s, 1000\/s \| 1000\/s, 1000\/s \|[\s\S]*\*\*Own share\*\*[\s\S]*\| E1 \| Rate \| 3200\/s, 3200\/s \| 400\/s, 400\/s \|/,
  );
  assert.match(report, /\| E1 \| Kept up \| yes, yes \| yes, yes \|/);
  assert.match(
    report,
    /\| E1 \| Node CPU ms\/op \| 0\.500, 0\.500 \| 0\.600, 0\.600 \|/,
  );
  assert.match(
    report,
    /\| E1 \| Node CPU ms\/op \| 0\.156, 0\.156 \| 1\.500, 1\.500 \|/,
  );
  assert.match(
    report,
    /\| E3 \| PostgreSQL CPU ms\/op \| 0\.833, 0\.833 \| 1\.333, 1\.333 \|/,
  );
  assert.match(
    report,
    /\| E1 \| PostgreSQL transactions\/op \| 1\.50, 1\.50 \| 2\.00, 2\.00 \|/,
  );
  assert.match(report, /\| axon-pg@100k \| .* \| generator 2-3 · node 0-1 \|/);
  assert.match(
    report,
    /\| E1 \| PostgreSQL WAL bytes\/op \| 400 B, 400 B \| 600 B, 600 B \|/,
  );
  assert.match(
    report,
    /\| E1 \| Node GC time \| 1\.0%, 1\.0% \| 2\.0%, 2\.0% \|/,
  );
  assert.match(
    report,
    /\| E1 \| Node RSS max \| 600 MB, 600 MB \| 700 MB, 700 MB \|/,
  );
  // The generator at the peaks, alone: no Kafka ran.
  assert.match(
    report,
    /The load generator used at most 0\.50 of its 2 CPUs at a peak; no Kafka ran, as neither server uses one\./,
  );
  assert.match(report, /\| 1-wow \| 100000 \(100000\) \| \d+ \(\d+–\d+\) \|/);
  assert.match(
    report,
    /\| 1-wow \| \d+ \| \d+ \| 47\.7 MB \| \d+ \| 100000 \| 19\.1 MB \| 200 \| 1\.0 MB \|/,
  );
  assert.match(report, /\| 1-wow \| 6\.0 s \| 293\.0 MB \| 439\.5 MB \|/);
  assert.match(report, /\| 1-axon \| 9\.0 s \| 390\.6 MB \| 488\.3 MB \|/);
  assert.doesNotMatch(report, /not comparable|store does not match/);
  // What each server does for a command, once, at the head of the comparison.
  assert.match(
    report,
    /What each server does for a command:\n\n\| \| Wow \| Axon \|/,
  );
  assert.match(
    report,
    /\| Idempotency \| Each command stored with its request id/,
  );
  assert.equal(report.match(/\| Metrics and tracing \|/g).length, 1);
  assert.match(
    report,
    /\| HTTP \| Spring Boot 4\.1\.2, WebFlux on Reactor Netty \| Spring Boot 3\.5\.16/,
  );
  // Each same rate beside the slower arm's sustainable rate: the fake Axon holds 800/s, under E1's 1,000/s.
  assert.deepEqual(
    sameRateBasis(suite).map(basis => [
      basis.scenario,
      basis.lowest.arm,
      basis.lowest.throughput,
      basis.above,
    ]),
    [
      ['E1', 'axon', 800, true],
      ['E3', 'axon', 800, false],
    ],
  );
  assert.match(
    report,
    /- E1 at 1000\/s: 125% of the lowest sustainable rate a sweep found here \(axon, 800\/s\), \*\*above it\*\*\./,
  );
  assert.match(
    report,
    /- E3 at 300\/s: 38% of the lowest sustainable rate a sweep found here \(axon, 800\/s\)\./,
  );
  assert.doesNotMatch(report, /Pairs at the calibrated rates|NaN/);
  const summary = readFileSync(join(out, 'summary.md'), 'utf8');
  assert.match(
    summary,
    /Primed: each session touched each of the 100000 accounts once after seeding, untimed \(90–90 s\); every run on it first waited until the nodes were quiet \(4–4 s\)\./,
  );
  assert.match(
    summary,
    /\| 1-axon-e1-sweep \| axon \| 800\/s at 64 \| levelled off \| 800\/s, p99 6\.4 ms \| 800\/s, p99 25\.6 ms \| - \| - \| - \|/,
  );
  assert.match(
    summary,
    /\| 1-wow-e1-sweep \| wow \| ≥ 13000\/s at 16384 \(top step\) \| levelled off \| - \| - \| 6400\/s, p99 102\.4 ms \| 12800\/s, p99 409\.6 ms \| 13000\/s, p99 1638\.4 ms \|/,
  );
  // The short steps that located them, apart.
  assert.match(
    summary,
    /The short steps that located them \(3 s warm-up, 8 s measured\), not part of the results:\n\n\| Sweep \| Arm \| 1 clients \|/,
  );
  assert.match(
    summary,
    /\| 1-axon-e1-sweep \| axon \| 100\/s, p99 0\.1 ms \| .* \| 800\/s, 7 errors \| - \|/,
  );
  assert.match(
    summary,
    /sweeps of 30 s steps after 10 s, .*, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s;/,
  );
});

test('sweepNext goes on past the top step while it still rises, and says why it stops', () => {
  const step = (concurrency, throughput, extra = {}) => ({
    concurrency,
    throughput,
    ok: throughput * 30,
    errors: 0,
    p99: 10,
    generatorCores: 0.5,
    ...extra,
  });
  assert.deepEqual(sweepNext([step(1024, 1000), step(4096, 1200)], 2), {
    next: 16384,
  });
  // Within the closed loop's 5% of the step before: levelled off.
  assert.deepEqual(sweepNext([step(1024, 1000), step(4096, 1040)], 2), {
    end: 'levelled',
  });
  assert.deepEqual(sweepNext([step(1024, 1000), step(4096, 900)], 2), {
    end: 'peaked',
  });
  // 400 of 36,400 failed: more than 1%.
  assert.deepEqual(
    sweepNext([step(1024, 1000), step(4096, 1200, { errors: 400 })], 2),
    { end: 'errors' },
  );
  // The load generator used 1.9 of its 2 cores: what the step measured is the generator.
  assert.deepEqual(
    sweepNext([step(1024, 1000), step(4096, 1200, { generatorCores: 1.9 })], 2),
    { end: 'generator' },
  );
  assert.deepEqual(sweepNext([step(4096, 1000), step(16384, 1200)], 2), {
    end: 'limit',
  });
  assert.deepEqual(sweepNext([], 2), { end: 'failed' });
});

test('fineSteps measures at full length the located peak beside its better neighbour, and the sustainable step beside the one above', () => {
  const steps = (throughputs, p99s, errors = {}) =>
    [1, 4, 16, 64, 256, 1024, 4096].map((concurrency, index) => ({
      concurrency,
      throughput: throughputs[index],
      p99: p99s[index],
      errors: errors[concurrency] ?? 0,
    }));
  // A flat top: the peak at 64 beside 256, which held at the same rate; the sustainable rate, the same step.
  assert.deepEqual(
    fineSteps(
      steps(
        [100, 200, 400, 800, 800, 800, 800],
        [0.1, 0.4, 1.6, 6.4, 25.6, 102.4, 409.6],
        { 4096: 7 },
      ),
      200,
    ),
    [64, 256],
  );
  // Axon's E3 at 2 segments: the peak at the top step beside 1024, the sustainable rate far below it, at 16 beside 64.
  assert.deepEqual(
    fineSteps(
      steps(
        [260, 600, 618, 650, 680, 700, 709],
        [5, 20, 53, 300, 900, 2000, 6000],
      ),
      200,
    ),
    [16, 64, 1024, 4096],
  );
  // Held nowhere: the lowest step for the sustainable rate.
  assert.deepEqual(
    fineSteps(
      steps(
        [100, 200, 150, 140, 130, 120, 110],
        [300, 400, 900, 900, 900, 900, 900],
      ),
      200,
    ).slice(0, 3),
    [1, 4, 16],
  );
});

test('fineMore takes the sustainable rate down or up the located steps, and the peak past the located top', () => {
  const step = (concurrency, throughput, p99, errors = 0) => ({
    concurrency,
    throughput,
    p99,
    errors,
    ok: throughput * 30,
    generatorCores: 0.5,
  });
  const located = [1, 4, 16, 64, 256, 1024, 4096].map(c => step(c, 100, 10));
  // The peak below the top and the sustainable rate measured: nothing more.
  assert.deepEqual(
    fineMore(located, [step(64, 800, 6.4), step(256, 800, 25.6)], 200, 2),
    {},
  );
  // No full-length step held: the one below the lowest.
  assert.deepEqual(
    fineMore(
      located,
      [
        step(16, 618, 250),
        step(64, 650, 300),
        step(1024, 700, 2000),
        step(4096, 709, 6000),
      ],
      200,
      2,
    ),
    { next: 4 },
  );
  // Once 4 held, the peak at the top levelled off at full length: the sweep stops there.
  assert.deepEqual(
    fineMore(
      located,
      [
        step(4, 600, 20),
        step(16, 618, 250),
        step(64, 650, 300),
        step(1024, 700, 2000),
        step(4096, 709, 6000),
      ],
      200,
      2,
    ),
    { end: 'levelled' },
  );
  // The step above the sustainable one held at full length too: the one above it, below the peak's.
  assert.deepEqual(
    fineMore(
      located,
      [
        step(16, 618, 53),
        step(64, 650, 150),
        step(1024, 700, 2000),
        step(4096, 709, 6000),
      ],
      200,
      2,
    ),
    { next: 256 },
  );
  // Still rising at the located top at full length: on past it, a step at a time.
  assert.deepEqual(
    fineMore(
      located,
      [step(1024, 6400, 102.4), step(4096, 12800, 409.6)],
      200,
      2,
    ),
    { next: 16384 },
  );
  assert.deepEqual(
    fineMore(
      located,
      [
        step(1024, 6400, 102.4),
        step(4096, 12800, 409.6),
        step(16384, 13000, 1638.4),
      ],
      200,
      2,
    ),
    { end: 'levelled' },
  );
});

test('fineOf: a peak is at the top step only when no short or full-length step went past it', () => {
  const step = (concurrency, throughput) => ({
    concurrency,
    throughput,
    generatorCores: 0.5,
  });
  const located = sweepOf({
    steps: [1, 4, 16, 64, 256, 1024, 4096].map(concurrency => ({
      concurrency,
      throughput: 100,
      generatorCores: 0.5,
    })),
    saturationThroughput: 100,
    saturationConcurrency: 1,
    availableProcessors: 2,
  });
  const swept = full =>
    sweepOf({
      steps: full.map(([c, t]) => step(c, t)),
      saturationThroughput: Math.max(...full.map(([, t]) => t)),
      saturationConcurrency: full.reduce((a, b) => (b[1] > a[1] ? b : a))[0],
      generatorCores: 0.5,
      availableProcessors: 2,
    });
  // 256 is the highest full-length step, but the short steps went on to 4096.
  const below = fineOf(
    located,
    swept([
      [64, 800],
      [256, 820],
    ]),
  );
  assert.equal(below.top, 4096);
  assert.deepEqual([below.peak.top, below.peak.lowerBound], [false, false]);
  assert.deepEqual(
    below.steps.map(s => s.concurrency),
    [64, 256],
  );
  assert.equal(below.located.length, 7);
  const top = fineOf(
    located,
    swept([
      [1024, 6400],
      [4096, 12800],
    ]),
  );
  assert.deepEqual([top.peak.top, top.peak.lowerBound], [true, true]);
});

test('sustainableStep takes the most a step did within the p99 limit and with no failure', () => {
  const step = (concurrency, throughput, p99, errors = 0) => ({
    concurrency,
    throughput,
    p99,
    errors,
  });
  const steps = [
    step(16, 500, 50),
    step(64, 900, 150),
    step(256, 950, 400),
    step(1024, 1000, 100, 3),
  ];
  assert.deepEqual(sustainableStep(steps, 200), {
    throughput: 900,
    concurrency: 64,
    p99: 150,
    lowerBound: false,
  });
  assert.equal(sustainableStep(steps, 10), undefined);
  // At the sweep's top step it is a lower bound.
  assert.equal(
    sustainableStep([step(16, 500, 50), step(64, 900, 150)], 200).lowerBound,
    true,
  );
});

test('mergeSaturations joins a sweep and the steps past its cap, peak and all', () => {
  const merged = mergeSaturations([
    {
      availableProcessors: 2,
      steps: [
        { concurrency: 1024, throughput: 4000, generatorCores: 0.9 },
        { concurrency: 4096, throughput: 4400, generatorCores: 1 },
      ],
    },
    { steps: [{ concurrency: 16384, throughput: 4300, generatorCores: 1.2 }] },
  ]);
  assert.deepEqual(
    merged.steps.map(step => step.concurrency),
    [1024, 4096, 16384],
  );
  assert.deepEqual(
    [
      merged.saturationThroughput,
      merged.saturationConcurrency,
      merged.generatorCores,
      merged.availableProcessors,
    ],
    [4400, 4096, 1, 2],
  );
});

test('a session whose up fails is counted and skipped; the others run', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  process.env.FAKE_FAIL_UP = '2-axon-up';
  try {
    const suite = runSuite('axon-pg', {
      benchPath,
      out: join(dir, 'out'),
      warmUp: 30,
      measure: 40,
      repeats: 2,
      fraction: 0.8,
      installs: { axon: '/builds/axon-server' },
    });
    assert.equal(suite.failures, 1);
    assert.deepEqual(
      suite.sessions.map(session => session.status),
      ['ok', 'ok', 'failed', 'ok'],
    );
    assert.ok(suite.runs.every(run => !run.name.startsWith('2-axon')));
    // bench.sh up stops what it started when it fails: only the three sessions are taken down.
    const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
    assert.equal(calls.match(/^down /gm).length, 3);
    assert.match(
      renderReport([suite]),
      /Sessions, in order: 1-wow, 1-axon, 2-axon \(\*\*up failed\*\*\), 2-wow\./,
    );
  } finally {
    delete process.env.FAKE_FAIL_UP;
  }
});

test('sweepOf keeps the steps; a peak at the top step or at the generator limit is a lower bound', () => {
  const steps = [
    { concurrency: 1, throughput: 100, errors: {}, p50Ms: 1, p99Ms: 2 },
    {
      concurrency: 4,
      throughput: 300,
      errors: { 'http-429': 2, 'http-504': 1 },
      p50Ms: 2,
      p99Ms: 9,
    },
  ];
  const top = sweepOf({
    saturationThroughput: 300,
    saturationConcurrency: 4,
    generatorCores: 0.4,
    availableProcessors: 2,
    steps,
  });
  assert.equal(top.steps[1].errors, 3);
  assert.equal(top.steps[1].p99, 9);
  assert.equal(top.peak.top, true);
  assert.equal(top.peak.lowerBound, true);
  const knee = { saturationThroughput: 100, saturationConcurrency: 1, steps };
  assert.equal(sweepOf(knee).peak.lowerBound, false);
  const bound = sweepOf({
    ...knee,
    generatorCores: 1.9,
    availableProcessors: 2,
  });
  assert.equal(bound.peak.top, false);
  assert.equal(bound.peak.lowerBound, true);
});

test('peakRatio spans the sessions, and a lower-bound peak bounds the ratio', () => {
  const suite = (wow, axon) => ({
    arms: [{ id: 'wow' }, { id: 'axon' }],
    sweeps: [
      ...wow.map(peak => ({ arm: 'wow', scenario: 'E1', status: 'ok', peak })),
      ...axon.map(peak => ({
        arm: 'axon',
        scenario: 'E1',
        status: 'ok',
        peak,
      })),
      { arm: 'axon', scenario: 'E1', status: 'failed' },
    ],
  });
  const at = (throughput, lowerBound = false) => ({ throughput, lowerBound });
  assert.deepEqual(
    peakRatio(suite([at(3000), at(3300)], [at(1000), at(1100)]), 'E1'),
    { lo: 3000 / 1100, hi: 3.3, bound: '' },
  );
  assert.equal(peakRatio(suite([at(3000, true)], [at(1000)]), 'E1').bound, '≥');
  assert.equal(peakRatio(suite([at(3000)], [at(1000, true)]), 'E1').bound, '≤');
  assert.equal(
    peakRatio(suite([at(3000, true)], [at(1000, true)]), 'E1').bound,
    '~',
  );
  assert.equal(peakRatio(suite([at(3000)], []), 'E1'), undefined);
  assert.equal(peakRatio(suite([at(3000)], [at(1000)]), 'E3'), undefined);
});

test('the axon group and its build: the head alone, no baseline', () => {
  assert.deepEqual(
    resolveInputs({
      suites: 'axon',
      warmUp: 30,
      measure: 40,
      repeats: 2,
      fraction: 0.8,
    }).suites,
    ['axon-pg', 'axon-pg-contention', 'axon-pg-segments'],
  );
  assert.deepEqual(
    planMatrix({ suites: ['axon-pg'], headSha: 'h', baseSha: '' }),
    {
      suites: ['axon-pg'],
      // A job a sweep (partsOf).
      jobs: ['axon-pg.100k.e1', 'axon-pg.100k.e3'],
      skipped: [],
      builds: [{ side: 'head', sha: 'h' }],
    },
  );
});

test('a sessions suite with an arm of the baseline needs its build, and a baseline commit to build it', () => {
  assert.equal(usesBase('wow-pg-same-rate-ab'), true);
  assert.equal(usesBase('axon-pg'), false);
  assert.equal(usesBase('e1-pg-uniform'), false);
  const plan = planMatrix({
    suites: ['wow-pg-same-rate-ab', 'wow-pg-same-rate-ab-slow-disk'],
    headSha: 'h',
    baseSha: 'b',
  });
  assert.deepEqual(plan.builds, [
    { side: 'head', sha: 'h' },
    { side: 'base', sha: 'b' },
  ]);
  // Its two sweeps, a job each, and the slow-disk suite's one.
  assert.deepEqual(plan.jobs, [
    'wow-pg-same-rate-ab.100k.e1',
    'wow-pg-same-rate-ab.100k.e3',
    'wow-pg-same-rate-ab-slow-disk.100k',
  ]);
  // Each session starts the build of its arm, W A A W.
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite('wow-pg-same-rate-ab-slow-disk', {
    benchPath: fakeSessionBench(dir),
    out: join(dir, 'out'),
    warmUp: 30,
    measure: 40,
    repeats: 2,
    fraction: 0.8,
    installs: { head: '/builds/head', base: '/builds/base' },
  });
  assert.equal(suite.failures, 0);
  const ups = readFileSync(join(dir, 'calls.log'), 'utf8')
    .split('\n')
    .filter(call => call.startsWith('up '));
  assert.deepEqual(
    ups.map(call => call.match(/--install=(\S+)/)?.[1]),
    ['/builds/base', '/builds/head', '/builds/head', '/builds/base'],
  );
  assert.ok(ups.every(call => call.includes(' --store-write-iops=1200 ')));
  const unbased = planMatrix({ suites: ['wow-pg-same-rate-ab'], headSha: 'h' });
  assert.deepEqual(unbased.suites, []);
  assert.deepEqual(unbased.skipped, [
    "wow-pg-same-rate-ab (an arm runs the baseline's build: set base_ref)",
  ]);
});

test('partsOf splits a sessions suite of several sweeps into a part a sweep, its same-rate runs with it', () => {
  assert.deepEqual(partsOf(SUITES['axon-pg']), [
    {
      key: 'e1',
      label: 'E1',
      sweeps: [
        { scenario: 'E1', distribution: 'uniform', label: 'E1', key: 'e1' },
      ],
      rates: [{ scenario: 'E1', rate: 1000 }],
    },
    {
      key: 'e3',
      label: 'E3',
      sweeps: [
        { scenario: 'E3', distribution: 'uniform', label: 'E3', key: 'e3' },
      ],
      rates: [{ scenario: 'E3', rate: 300 }],
    },
  ]);
  // Two sweeps of E1: its same-rate runs, none here, would go with the first.
  assert.deepEqual(
    partsOf(SUITES['axon-pg-contention']).map(part => [
      part.key,
      part.label,
      part.rates,
    ]),
    [
      ['e1-zipf', 'E1 zipf', []],
      ['e3-zipf', 'E3 zipf', []],
      ['e1-hot', 'E1 hot', []],
    ],
  );
  // One sweep, or another kind: no parts.
  assert.equal(partsOf(SUITES['axon-pg-e3-pool']), undefined);
  assert.equal(partsOf(SUITES['e1-pg-uniform']), undefined);
  assert.equal(
    planMatrix({ suites: ['axon-pg-contention'], headSha: 'h' }).jobs.length,
    3,
  );
  assert.deepEqual(parseJob('axon-pg.100k.e1-zipf'), {
    suite: 'axon-pg',
    dataset: '100k',
    part: 'e1-zipf',
  });
  assert.deepEqual(parseJob('e1-pg-uniform.1M'), {
    suite: 'e1-pg-uniform',
    dataset: '1M',
  });
  assert.equal(jobId('axon-pg', '100k', 'e3'), 'axon-pg.100k.e3');
  assert.equal(jobId('axon-pg', '100k'), 'axon-pg.100k');
});

test('each part of a split suite runs its sweep in fresh sessions, ABBA; the report merges the parts into one suite', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  const results = join(dir, 'results');
  const run = part =>
    runSuite('axon-pg', {
      benchPath,
      out: join(results, `axon-pg.100k.${part}`),
      warmUp: 30,
      measure: 40,
      repeats: 2,
      fraction: 0.8,
      cpus: { total: 4 },
      installs: { axon: '/builds/axon-server' },
      part,
    });
  const e3 = run('e3');
  assert.equal(e3.failures, 0);
  assert.equal(e3.part, 'e3');
  assert.deepEqual(e3.fixedRates, [{ scenario: 'E3', rate: 300 }]);
  assert.deepEqual(
    e3.sessions.map(session => session.name),
    ['1-wow', '1-axon', '2-axon', '2-wow'],
  );
  // E3 alone: its rate, its sweep and its own share; `up` still checks every scenario of the suite.
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  assert.ok(calls.every(call => !/--scenario=E1 /.test(call)));
  assert.ok(
    calls
      .filter(call => call.startsWith('up '))
      .every(call => call.includes(' --mix=E1,E3 ')),
  );
  assert.deepEqual(
    e3.runs.map(r => r.name),
    [
      '1-wow-e3',
      '1-wow-e3-own',
      '1-axon-e3',
      '1-axon-e3-own',
      '2-axon-e3',
      '2-axon-e3-own',
      '2-wow-e3',
      '2-wow-e3-own',
    ],
  );
  assert.ok(e3.sessions.every(session => session.correctness.comparable));
  assert.throws(() => run('e2'), /axon-pg has no part e2/);
  run('e1');

  const [merged, ...others] = readSuites(results);
  assert.deepEqual(others, []);
  assert.equal(merged.part, undefined);
  assert.deepEqual(
    merged.parts.map(part => [part.part, part.label]),
    [
      ['e1', 'E1'],
      ['e3', 'E3'],
    ],
  );
  assert.deepEqual(
    merged.sweepPlan.map(plan => plan.key),
    ['e1', 'e3'],
  );
  assert.equal(merged.sweeps.length, 8);
  assert.equal(merged.sessions.length, 8);
  assert.equal(merged.sessions[0].name, '1-wow (E1)');
  assert.equal(merged.failures, 0);
  const report = renderReport([merged]);
  assert.match(
    report,
    /\| E1 \| ≥ 13000\/s at 16384 \(top step\); ≥ 13000\/s at 16384 \(top step\) \| 800\/s at 64; 800\/s at 64 \|/,
  );
  assert.match(report, /\| E3 \| ≥ 13000\/s at 16384 \(top step\);/);
  assert.match(
    report,
    /\| axon-pg@100k E1 \| .* \| generator 2-3 · node 0-1 \|/,
  );
  assert.match(
    report,
    /\| axon-pg@100k E3 \| .* \| generator 2-3 · node 0-1 \|/,
  );
  assert.match(
    renderSuite(merged),
    /Each sweep in fresh sessions on a runner of its own, the full ABBA: E1 on [^;]+, (\d+|\?) cores; E3 on .+, (\d+|\?) cores\./,
  );
  // A part's own summary is its runner's.
  assert.match(renderSuite(e3), /\. Runner: .+, (\d+|\?) cores\./);
  assert.match(report, /\| 2-wow \(E3\) \| 100000 \(100000\) \|/);
  assert.match(
    report,
    /- E3 at 300\/s: 38% of the lowest sustainable rate a sweep found here \(axon, 800\/s\)\./,
  );
  assert.doesNotMatch(report, /NaN|undefined/);
});

test("axonInstall builds the Axon server with the repository's wrapper, checked out first in a sparse checkout", () => {
  const repo = mkdtempSync(join(tmpdir(), 'bench-repo-'));
  const calls = [];
  const run = status => command => {
    calls.push(command.join(' '));
    return { status };
  };
  const gradlew =
    './gradlew --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -p test/benchmarks/axon-server test installDist';
  assert.equal(axonInstall(repo, run(0)), join(repo, AXON_INSTALL));
  assert.deepEqual(calls, ['git sparse-checkout add gradle', gradlew]);
  // With the wrapper checked out, only the build.
  mkdirSync(join(repo, 'gradle', 'wrapper'), { recursive: true });
  calls.length = 0;
  axonInstall(repo, run(0));
  assert.deepEqual(calls, [gradlew]);
  assert.throws(
    () => axonInstall(repo, run(1)),
    /Building the Axon server failed: \.\/gradlew .* \(1\)/,
  );
  // A distribution already built is taken as it is.
  mkdirSync(join(repo, AXON_INSTALL, 'bin'), { recursive: true });
  writeFileSync(join(repo, AXON_INSTALL, 'bin', 'axon-server'), '');
  calls.length = 0;
  assert.equal(axonInstall(repo, run(1)), join(repo, AXON_INSTALL));
  assert.deepEqual(calls, []);
});

test("the suite step runs the Axon comparison with the Axon server of the harness's repository", () => {
  const repo = mkdtempSync(join(tmpdir(), 'bench-repo-'));
  const harness = join(repo, 'test', 'benchmarks', 'harness');
  mkdirSync(harness, { recursive: true });
  const benchPath = fakeSessionBench(harness);
  mkdirSync(join(repo, AXON_INSTALL, 'bin'), { recursive: true });
  writeFileSync(join(repo, AXON_INSTALL, 'bin', 'axon-server'), '');
  const result = spawnSync(
    process.execPath,
    [new URL('./bench.mjs', import.meta.url).pathname, 'suite', 'axon-pg.100k'],
    {
      env: {
        ...process.env,
        BENCH: benchPath,
        OUT: join(repo, 'out'),
        WARM_UP: '30',
        MEASURE: '40',
        REPEATS: '1',
        FRACTION: '0.8',
        P99_LIMIT_MS: '200',
        CPUS: '',
        INSTALL_HEAD: '',
        INSTALL_BASE: '',
        INSTALL_AXON: '',
      },
      encoding: 'utf8',
    },
  );
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const calls = readFileSync(join(harness, 'calls.log'), 'utf8');
  assert.ok(
    calls.includes(
      `up --backend=postgres --install=${join(repo, AXON_INSTALL)} --no-bus --mix=E1,E3`,
    ),
    calls,
  );
  assert.ok(
    calls.includes('up --backend=postgres --server-args=--wow.transport=none'),
  );
});

test("the segments suite puts Axon's 16 segments against 2, set when each session starts", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  const suite = runSuite('axon-pg-segments', {
    benchPath,
    out: join(dir, 'out'),
    warmUp: 30,
    measure: 40,
    repeats: 2,
    fraction: 0.8,
    installs: { axon: '/builds/axon-server' },
  });
  assert.equal(suite.failures, 0);
  assert.deepEqual(
    suite.sessions.map(session => session.name),
    ['1-axon-16', '1-axon-2', '2-axon-2', '2-axon-16'],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  // Per session: up, E3 at one rate, the two sweeps (each located, then measured), the inspection, down; no
  // own-share runs.
  assert.deepEqual(
    calls.slice(0, 8).map(call => call.split(' ')[0]),
    [
      'up',
      'run',
      'saturate',
      'saturate',
      'saturate',
      'saturate',
      'inspect',
      'down',
    ],
  );
  const ups = calls.filter(call => call.startsWith('up '));
  assert.ok(ups[0].endsWith(' props={"benchmark.axon.segments":16}'));
  assert.ok(ups[1].endsWith(' props={"benchmark.axon.segments":2}'));
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.scenario, run.rate]),
    [
      ['1-axon-16-e3', 'E3', 300],
      ['1-axon-2-e3', 'E3', 300],
      ['2-axon-2-e3', 'E3', 300],
      ['2-axon-16-e3', 'E3', 300],
    ],
  );
  const report = renderReport([suite]);
  assert.match(report, /\| Sweep \| axon-16 \| axon-2 \| axon-16\/axon-2 \|/);
  assert.match(report, /\| E3 \| Rate \| 300\/s, 300\/s \| 300\/s, 300\/s \|/);
  assert.doesNotMatch(report, /\| E1 \| Rate|\*\*Own share\*\*/);
  // The same build at both settings: level.
  assert.match(
    report,
    /\| 1\.00–1\.00 \| 0\.0% \| level \(within the A\/A spread\) \|/,
  );
});

test('the contention suite sweeps with Zipf and hot keys, and nothing in open loop', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  const out = join(dir, 'out');
  const suite = runSuite('axon-pg-contention', {
    benchPath,
    out,
    warmUp: 30,
    measure: 40,
    repeats: 1,
    fraction: 0.8,
    installs: { axon: '/builds/axon-server' },
  });
  assert.equal(suite.failures, 0);
  assert.deepEqual(suite.runs, []);
  assert.deepEqual(
    suite.sweeps.map(run => [run.name, run.label]),
    [
      ['1-wow-e1-zipf-sweep', 'E1 zipf'],
      ['1-wow-e3-zipf-sweep', 'E3 zipf'],
      ['1-wow-e1-hot-sweep', 'E1 hot'],
      ['1-axon-e1-zipf-sweep', 'E1 zipf'],
      ['1-axon-e3-zipf-sweep', 'E3 zipf'],
      ['1-axon-e1-hot-sweep', 'E1 hot'],
    ],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8');
  assert.ok(
    calls.includes(
      `saturate --backend=postgres --install=/builds/axon-server --no-bus --reuse --scenario=E1 --distribution=hot --accounts=100000 --max-concurrency=4096 --sweep --growth=4 --step=8 --warm-up=3 --label=axon-pg-contention-1-axon-e1-hot-sweep-locate --out=${join(out, '1-axon-e1-hot-sweep-locate')} props=`,
    ),
  );
  assert.match(
    calls,
    /--start-concurrency=16384 .* --label=axon-pg-contention-1-wow-e1-zipf-sweep-locate-16384 /,
  );
  const report = renderReport([suite]);
  assert.match(
    report,
    /\| E1 zipf \| ≥ 13000\/s at 16384 \(top step\) \| 800\/s at 64 \| ≥ 16\.25–16\.25 \| - \| no A\/A \(one session an arm\) \|/,
  );
  assert.match(report, /\| E1 hot \| /);
  assert.match(report, /no Kafka ran, as neither server uses one/);
  assert.doesNotMatch(report, /\*\*Same rate\*\*|\*\*Own share\*\*|NaN/);
});

test("sweepPlanOf names each sweep by its scenario and, when they are not the suite's, its keys", () => {
  assert.deepEqual(sweepPlanOf(SUITES['axon-pg']), [
    { scenario: 'E1', distribution: 'uniform', label: 'E1', key: 'e1' },
    { scenario: 'E3', distribution: 'uniform', label: 'E3', key: 'e3' },
  ]);
  assert.deepEqual(
    sweepPlanOf(SUITES['axon-pg-contention']).map(plan => [
      plan.label,
      plan.key,
    ]),
    [
      ['E1 zipf', 'e1-zipf'],
      ['E3 zipf', 'e3-zipf'],
      ['E1 hot', 'e1-hot'],
    ],
  );
});

test("the A/A spread is each arm's sessions against each other, and a ratio within it is level", () => {
  const suite = (wow, axon) => ({
    arms: [{ id: 'wow' }, { id: 'axon' }],
    sweeps: [
      ...wow.map(throughput => ({
        arm: 'wow',
        label: 'E1',
        status: 'ok',
        peak: { throughput },
      })),
      ...axon.map(throughput => ({
        arm: 'axon',
        label: 'E1',
        status: 'ok',
        peak: { throughput },
      })),
    ],
  });
  assert.equal(
    aaSpread(suite([100, 110], [50, 51]), 'E1'),
    0.10000000000000009,
  );
  assert.equal(aaSpread(suite([100], [50, 51]), 'E1'), undefined);
  const ids = ['wow', 'axon'];
  const ratio = (lo, hi, bound = '') => ({ lo, hi, bound });
  assert.equal(ratioVerdict(ratio(1.2, 1.3), 0.1, ids), 'wow higher');
  assert.equal(ratioVerdict(ratio(0.7, 0.8), 0.1, ids), 'axon higher');
  assert.equal(
    ratioVerdict(ratio(0.95, 1.05), 0.1, ids),
    'level (within the A/A spread)',
  );
  assert.equal(ratioVerdict(ratio(1.2, 1.3, '≥'), 0.1, ids), 'wow higher');
  assert.equal(
    ratioVerdict(ratio(0.7, 0.8, '≥'), 0.1, ids),
    'undetermined (a lower bound)',
  );
  assert.equal(ratioVerdict(ratio(0.7, 0.8, '≤'), 0.1, ids), 'axon higher');
  assert.equal(
    ratioVerdict(ratio(1.2, 1.3, '≤'), 0.1, ids),
    'undetermined (an upper bound)',
  );
  assert.equal(
    ratioVerdict(ratio(1.2, 1.3, '~'), 0.1, ids),
    'undetermined (both lower bounds)',
  );
  assert.equal(
    ratioVerdict(ratio(1.2, 1.3), undefined, ids),
    'no A/A (one session an arm)',
  );
  assert.equal(ratioVerdict(undefined, 0.1, ids), '-');
});

test('generatorWithKafka adds Kafka on the generator CPUs to its cores at the peaks, flagged past 1.6', () => {
  const suite = (
    cores,
    kafkaPercent,
    arms = [{ id: 'wow', install: 'head' }],
  ) => ({
    arms,
    sweeps: [
      { status: 'ok', peak: { generatorCores: cores, generatorCpus: 2 } },
      { status: 'ok', peak: { generatorCores: 0.4, generatorCpus: 2 } },
    ],
    runs:
      kafkaPercent === undefined
        ? []
        : [{ status: 'ok', metrics: { containers: { kafka: kafkaPercent } } }],
  });
  assert.deepEqual(generatorWithKafka(suite(1.2, 45)), {
    generator: 1.2,
    cpus: 2,
    kafka: 0.45,
    total: 1.65,
    over: true,
  });
  assert.equal(generatorWithKafka(suite(1, 40)).over, false);
  assert.deepEqual(generatorWithKafka(suite(1, undefined)), {
    generator: 1,
    cpus: 2,
    kafka: undefined,
    total: undefined,
    over: false,
  });
  assert.equal(
    generatorWithKafka({ arms: [], sweeps: [], runs: [] }),
    undefined,
  );
  // Without a bus, the generator alone, flagged past the same line.
  const noBus = SUITES['axon-pg'].arms;
  assert.deepEqual(generatorWithKafka(suite(1.2, undefined, noBus)), {
    generator: 1.2,
    cpus: 2,
    bus: false,
    total: 1.2,
    over: false,
  });
  assert.equal(generatorWithKafka(suite(1.7, undefined, noBus)).over, true);
});

test('usesBus: Wow with a topic transport uses Kafka; Wow without one and Axon do not', () => {
  assert.equal(usesBus({ install: 'head' }), true);
  assert.equal(
    usesBus({ install: 'head', serverArgs: '--wow.transport=memory' }),
    true,
  );
  assert.equal(
    usesBus({ install: 'head', serverArgs: '--wow.transport=none' }),
    false,
  );
  assert.equal(
    usesBus({ install: 'head', serverArgs: '--a=b --wow.transport=none' }),
    false,
  );
  assert.equal(usesBus({ install: 'axon' }), false);
  assert.deepEqual(SUITES['axon-pg'].arms.map(usesBus), [false, false]);
  assert.ok(SUITES['e1-pg-uniform'].arms.every(usesBus));
});

test("metrics read PostgreSQL's WAL bytes, the servers' collection time and their largest RSS per operation", () => {
  const m = metrics({
    results: { throughput: 100, ok: 4000, failed: 0, latencyMs: {} },
    resources: {
      seconds: 40,
      processes: {
        'node-1': {
          averageCores: 1,
          rssMaxMb: 640,
          gc: { delta: { GCT: 0.8 } },
        },
      },
      stores: {
        postgres: { delta: { xact_commit: 8000, wal_bytes: 2_000_000 } },
      },
    },
  });
  assert.deepEqual(
    [m.commitsPerOp, m.walBytesPerOp, m.gcShare, m.rssMaxMb],
    [2, 500, 0.02, 640],
  );
  const bare = metrics({
    results: { throughput: 100, ok: 4000 },
    resources: {},
  });
  assert.deepEqual(
    [bare.walBytesPerOp, bare.gcShare, bare.rssMaxMb],
    [undefined, undefined, undefined],
  );
});

test("a session whose store lost an event makes its arm's numbers not comparable", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  process.env.FAKE_LOST_CONFIRMATION = '2-axon-2-inspect';
  try {
    const suite = runSuite('axon-pg-segments', {
      benchPath,
      out: join(dir, 'out'),
      warmUp: 30,
      measure: 40,
      repeats: 2,
      fraction: 0.8,
      installs: { axon: '/builds/axon-server' },
    });
    assert.deepEqual(
      suite.sessions.map(session => [
        session.name,
        session.correctness.comparable,
      ]),
      [
        ['1-axon-16', true],
        ['1-axon-2', true],
        ['2-axon-2', false],
        ['2-axon-16', true],
      ],
    );
    const report = renderReport([suite]);
    assert.match(report, /\| not comparable \(axon-2: the store check\) \|/);
    assert.match(
      report,
      /\| axon-pg-segments@100k \| 2-axon-2 \| store \| \*\*store does not match the runs\*\* \| entries confirmed: (\d+) stored, \d+ expected \|/,
    );
    assert.match(report, /\*\*Not comparable: axon-2\.\*\*/);
  } finally {
    delete process.env.FAKE_LOST_CONFIRMATION;
  }
});

test('correctnessOf counts what the runs sent against what the store holds', () => {
  const run = (scenario, sentOk, sentFailed = 0) => ({
    scenario,
    metrics: { sentOk, sentFailed },
  });
  const sweep = (scenario, ...steps) => ({
    scenario,
    status: 'ok',
    steps: steps.map(([sentOk, sentFailed]) => ({ sentOk, sentFailed })),
  });
  const runs = [run('E1', 1000, 5), run('E3', 300)];
  const sweeps = [sweep('E1', [40, 0], [160, 2]), sweep('E3', [20, 0])];
  // up's check sent one of each; the prime one deposit to each of 10 accounts, 1 of which failed.
  const events = {
    AccountOpened: 10,
    Deposited: 1 + 1000 + 200 + 9 + 3,
    AmountLocked: 321,
    Prepared: 321,
    AmountEntered: 320,
    EntryFailed: 1,
    Confirmed: 320,
    AmountUnlocked: 1,
  };
  const prime = { accounts: 10, failed: 1 };
  const ok = correctnessOf({ accounts: 10, events, prime, runs, sweeps });
  assert.equal(ok.comparable, true);
  assert.deepEqual(
    ok.checks.find(check => check.name === 'deposits stored'),
    { name: 'deposits stored', expected: '1210–1218', stored: 1213, ok: true },
  );
  // More stored than were sent: not comparable.
  const extra = correctnessOf({
    accounts: 10,
    events: { ...events, Deposited: 1219 },
    prime,
    runs,
    sweeps,
  });
  assert.equal(extra.comparable, false);
  // A transfer neither entered nor refused.
  const stuck = correctnessOf({
    accounts: 10,
    events: { ...events, AmountEntered: 319, Confirmed: 319 },
    prime,
    runs,
    sweeps,
  });
  assert.deepEqual(
    stuck.checks.filter(check => !check.ok).map(check => check.name),
    ['transfers entered or refused'],
  );
  // A run whose sends are not known (it failed) leaves its scenario's check unknown, which fails nothing.
  const unknown = correctnessOf({
    accounts: 10,
    events,
    prime,
    runs: [{ scenario: 'E1', status: 'failed' }, run('E3', 300)],
    sweeps,
  });
  assert.equal(
    unknown.checks.find(check => check.name === 'deposits stored').ok,
    undefined,
  );
  assert.equal(unknown.comparable, true);
});

test('the same-rate diagnostic runs each rate twice a session and nothing else; with profile, every session records', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  const out = join(dir, 'out');
  const suite = runSuite('axon-pg-same-rate', {
    benchPath,
    out,
    warmUp: 30,
    measure: 40,
    repeats: 2,
    fraction: 0.8,
    profile: true,
    installs: { axon: '/builds/axon-server' },
  });
  assert.equal(suite.failures, 0);
  assert.equal(suite.profiled, true);
  assert.deepEqual(suite.sweeps, []);
  assert.deepEqual(
    suite.runs
      .filter(run => run.arm === 'wow' && run.round === 0)
      .map(run => [run.name, run.scenario, run.rate]),
    [
      ['1-wow-e1', 'E1', 1000],
      ['1-wow-e3', 'E3', 300],
      ['1-wow-e1-2', 'E1', 1000],
      ['1-wow-e3-2', 'E3', 300],
    ],
  );
  assert.equal(suite.runs.length, 16);
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  assert.ok(!calls.some(call => call.startsWith('saturate ')));
  const ups = calls.filter(call => call.startsWith('up '));
  assert.equal(ups.length, 4);
  assert.ok(ups.every(call => / --jfr( |$)/.test(call)));
  assert.ok(suite.sessions.every(session => session.correctness.comparable));
  assert.match(
    renderSuite(suite),
    /Profiled: every session's servers ran with a flight recording/,
  );
  // The slow-disk variant caps the store's writes when each session starts.
  const slow = mkdtempSync(join(tmpdir(), 'bench-'));
  const capped = runSuite('axon-pg-same-rate-slow-disk', {
    benchPath: fakeSessionBench(slow),
    out: join(slow, 'out'),
    warmUp: 30,
    measure: 40,
    repeats: 1,
    fraction: 0.8,
    installs: { axon: '/builds/axon-server' },
  });
  assert.equal(capped.storeWriteIops, 1400);
  const cappedUps = readFileSync(join(slow, 'calls.log'), 'utf8')
    .split('\n')
    .filter(call => call.startsWith('up '));
  assert.equal(cappedUps.length, 2);
  assert.ok(
    cappedUps.every(call => call.includes(' --store-write-iops=1400 ')),
  );
  assert.match(
    renderSuite(capped),
    /The store's disk writes capped at 1400 a second/,
  );
  // Without profile, nothing records.
  const plain = mkdtempSync(join(tmpdir(), 'bench-'));
  const unprofiled = runSuite('axon-pg-same-rate', {
    benchPath: fakeSessionBench(plain),
    out: join(plain, 'out'),
    warmUp: 30,
    measure: 40,
    repeats: 1,
    fraction: 0.8,
    installs: { axon: '/builds/axon-server' },
  });
  assert.equal(unprofiled.profiled, undefined);
  assert.ok(
    readFileSync(join(plain, 'calls.log'), 'utf8')
      .split('\n')
      .every(call => !call.includes('--jfr')),
  );
});

test('a diagnostic suite dumps its sweeps, its second arm set by properties when the session starts', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const benchPath = fakeSessionBench(dir);
  const out = join(dir, 'out');
  const suite = runSuite('axon-pg-e3-pool', {
    benchPath,
    out,
    warmUp: 30,
    measure: 40,
    repeats: 1,
    fraction: 0.8,
    installs: { axon: '/builds/axon-server' },
  });
  assert.equal(suite.failures, 0);
  assert.deepEqual(suite.runs, []);
  assert.deepEqual(
    suite.sweeps.map(run => run.name),
    ['1-axon-e3-sweep', '1-axon-pool-32-e3-sweep'],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  // Each sweep located, then measured, both dumped.
  const sweeps = calls.filter(call => call.startsWith('saturate '));
  assert.equal(sweeps.length, 4);
  assert.ok(sweeps.every(call => call.includes(' --dump-every=15 ')));
  assert.ok(
    calls
      .find(
        call =>
          call.startsWith('up ') &&
          call.includes(join(out, '1-axon-pool-32-up')),
      )
      .endsWith(' props={"spring.datasource.hikari.maximum-pool-size":32}'),
  );
  assert.ok(suite.sessions.every(session => session.correctness.comparable));
  assert.deepEqual(
    resolveInputs({
      suites: 'axon-diag',
      warmUp: 30,
      measure: 40,
      repeats: 2,
      fraction: 0.8,
    }).suites,
    [
      'axon-pg-e3-pool',
      'axon-pg-e3-reactions',
      'axon-pg-same-rate',
      'axon-pg-same-rate-slow-disk',
      'wow-pg-same-rate-ab',
      'wow-pg-same-rate-ab-slow-disk',
    ],
  );
});
