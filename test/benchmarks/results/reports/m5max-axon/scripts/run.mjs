/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// The comparison with Axon Framework 4 on the Apple M5 Max (D392), through the sessions suites of
// .github/scripts/bench.mjs, the ones its Actions report runs, with this machine's settings:
// - no CPU sets (macOS has no taskset); every process sees the 18 cores, the stores OrbStack's VM;
// - the sweeps double the clients a step (`growth` 2, so a step sits between the last clean step and the first
//   failed one, D392 ②), up to 12,288 clients, the most the client's 16,384 ephemeral ports to the node's one port
//   allow with 4,096 kept for the connections still closing (D392 ①);
// - the same rate is half of the lower arm's peak measured here, in the peak suite's sessions (D392 ①).
// The suites, in order, each sweep in sessions of its own (a part, as on Actions), W A A W (two rounds, the arms in
// turn, a fresh session each):
// - m5max-axon-peak: E1 and E3 with uniform keys to their peaks, then each arm at half of its own sustainable rate;
// - m5max-axon-same-rate: E1 and E3 at one rate for both;
// - m5max-axon-pool-32: E1 and E3 to their peaks with 32 connections in both pools, against Spring Boot's 10;
// - m5max-axon-contention: E1 and E3 with Zipf keys and E1 on hot keys to their peaks (bench.mjs's axon-pg-contention).
//
//   node run.mjs all <out>       every suite into <out>/<suite>/, rates.json, then report.md (run-all.sh runs it)
//   node run.mjs report <out>    report.md again from the suites' suite.json
//
// WOW_REPO names the checkout measured, whose test/benchmarks builds the suites run.
import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const repo = process.env.WOW_REPO;
if (!repo) throw new Error('Set WOW_REPO to the checkout measured.');
const bench = await import(
  pathToFileURL(join(repo, '.github/scripts/bench.mjs')).href
);
const { SUITES, SESSIONS, runSuite, readSuites, renderReport } = bench;

SESSIONS.sweep.growth = 2;
SESSIONS.sweep.limit = 12288;

const SHARE = 0.5;
const POOL_32 = { 'spring.datasource.hikari.maximum-pool-size': 32 };
const [WOW, AXON] = SUITES['axon-pg'].arms;
const pooled = arm => ({
  ...arm,
  id: `${arm.id}-pool-32`,
  props: { ...arm.props, ...POOL_32 },
});
SUITES['m5max-axon-peak'] = {
  ...SUITES['axon-pg'],
  rates: [],
  answers:
    'The industry comparison on the M5 Max: Wow with no bus (wow.transport=none) against Axon Framework 4 on one PostgreSQL, one node each, W A A W: closed-loop sweeps of E1 and E3 to their peaks, then each at half of its own sustainable rate.',
};
SUITES['m5max-axon-same-rate'] = {
  ...SUITES['axon-pg'],
  sweeps: [],
  own: false,
  answers:
    'The same work for both on the M5 Max: E1 and E3 at half of the lower arm’s peak, W A A W, for their latency and cost per command.',
};
SUITES['m5max-axon-pool-32'] = {
  ...SUITES['axon-pg'],
  arms: [pooled(WOW), pooled(AXON)],
  rates: [],
  own: false,
  answers:
    'Both pools at 32 connections instead of Spring Boot’s 10, on the M5 Max: E1 and E3 to their peaks, W A A W (Axon’s 16 processor threads share 10 connections by default).',
};
SUITES['m5max-axon-contention'] = {
  ...SUITES['axon-pg-contention'],
  answers:
    'Contention on the M5 Max: E1 and E3 with Zipf keys and E1 on hot keys to their peaks, Wow with no bus against Axon Framework 4, W A A W.',
};
const NAMES = [
  'm5max-axon-peak',
  'm5max-axon-same-rate',
  'm5max-axon-pool-32',
  'm5max-axon-contention',
];

const command = process.argv[2];
const out = process.argv[3];
if (!['all', 'report'].includes(command) || !out) {
  throw new Error('usage: run.mjs all|report <out>');
}

const git = (...args) =>
  spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).stdout.trim();
const refs = {
  headRef: 'main',
  headSha: git('rev-parse', 'HEAD'),
  harness: 'local Apple M5 Max',
};

/** A step's share of failed commands is at most 1% (D392 ②): only such a step is a peak. */
const clean = step => step.errors <= 0.01 * (step.ok + step.errors);
const peakOf = run =>
  Math.max(0, ...run.steps.filter(clean).map(step => step.throughput));

/**
 * The same rate of each scenario: half of the lower arm's peak in `suite`, each arm's peak the mean of its sessions',
 * rounded to 50/s; a scenario some arm has no peak for is left out.
 */
function sameRates(suite) {
  return ['E1', 'E3'].flatMap(scenario => {
    const peaks = suite.arms.map(arm => {
      const runs = suite.sweeps.filter(
        run =>
          run.status === 'ok' &&
          run.arm === arm.id &&
          run.scenario === scenario &&
          run.distribution === 'uniform',
      );
      const values = runs.map(peakOf).filter(value => value > 0);
      return values.length > 0
        ? values.reduce((a, b) => a + b, 0) / values.length
        : 0;
    });
    const lower = Math.min(...peaks);
    return lower > 0
      ? [
          {
            scenario,
            rate: Math.max(50, Math.round((SHARE * lower) / 50) * 50),
            peaks,
          },
        ]
      : [];
  });
}

/** One part of a suite (one sweep, bench.mjs's partsOf), or the whole suite when it has no parts, into its directory. */
const runPart = (name, part) => {
  const label = part ? `${name}-${part}` : name;
  console.log(`[run.mjs] ${new Date().toISOString()} suite ${label}`);
  const suite = runSuite(name, {
    benchPath: join(repo, 'test/benchmarks/harness/bench.sh'),
    out: join(out, label),
    repeats: 2,
    installs: {
      head: join(repo, 'test/benchmarks/server/build/install/benchmark-server'),
      axon: join(repo, bench.AXON_INSTALL),
    },
    refs,
    ...(part ? { part } : {}),
  });
  console.log(
    `[run.mjs] ${new Date().toISOString()} suite ${label} done, ${suite.failures} failures`,
  );
  return suite;
};

/**
 * A suite as Actions runs it (D392 ⑩): each sweep a part of its own, W A A W in fresh sessions, so that no sweep runs
 * behind the backlog an earlier one left (a tracking processor or a subscription still reading a sweep's events); the
 * parts' suites, merged by readSuites for the report.
 */
const run = name => {
  const parts = bench.partsOf(SUITES[name]);
  return parts
    ? parts.map(part => runPart(name, part.key))
    : [runPart(name)];
};

if (command === 'all') {
  const peaks = run('m5max-axon-peak');
  const rates = sameRates({
    arms: peaks[0].arms,
    sweeps: peaks.flatMap(suite => suite.sweeps ?? []),
  });
  writeFileSync(
    join(out, 'rates.json'),
    `${JSON.stringify({ share: SHARE, arms: peaks[0].arms.map(arm => arm.id), rates }, null, 2)}\n`,
  );
  if (rates.length > 0) {
    SUITES['m5max-axon-same-rate'].rates = rates.map(
      ({ scenario, rate }) => ({ scenario, rate }),
    );
    run('m5max-axon-same-rate');
  } else {
    console.log('[run.mjs] no peak to set the same rate from: skipped');
  }
  run('m5max-axon-pool-32');
  run('m5max-axon-contention');
}

const suites = readSuites(out).filter(suite => NAMES.includes(suite.name));
if (existsSync(out) && suites.length > 0) {
  writeFileSync(
    join(out, 'report.md'),
    renderReport(suites, { ...refs, skipped: [], noise: {} }),
  );
  console.log(`[run.mjs] report.md of ${suites.length} suites`);
}
