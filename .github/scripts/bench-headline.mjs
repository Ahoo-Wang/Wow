/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { aaSpread, peakRatio } from './bench.mjs';
import { ratioVerdict } from './bench-curves.mjs';

// The figures the documentation quotes (docs-performance.mjs), read from the reports' own data so that no figure
// reaches a page by hand: each report's `headline.json` beside its `.md`, written here from the raw results the
// report keeps (D391), with the build, the runner's CPU model and the file each figure comes from.
//
//   node .github/scripts/bench-headline.mjs           (writes every report's headline.json)
//   node .github/scripts/bench-headline.mjs --check   (fails when one is not what its report's data gives)
//
// docs-reference.mjs writes and checks them with the performance pages (`pnpm docs:reference`), on every pull
// request (ci.yml's documentation layout job), so a report's data and its headline never part.
//
// The Actions reports' views are bench.mjs's (`suite.json`: a capacity suite's `capacity`, a sessions suite's
// sweeps through peakRatio and aaSpread). The M5 Max snapshot's are its summarize.py's (`summary.json`, written with
// `--json` from the same values as its SUMMARY.md). A figure is never computed across two reports, so never across
// two builds.

export const SCHEMA = 'wow-headline/1';
export const REPORTS_DIR = 'test/benchmarks/results/reports';

/**
 * The reports the documentation quotes, each with its environment and the runs its directories hold. A run id is the
 * report's own (its Setup or Data section names it); headline.test.mjs checks that the report still says it.
 */
export const REPORTS = {
  'actions-snapshot': {
    environment: 'actions',
    runs: { l3: '42973046096', l4: '42973320597' },
    read: actionsSnapshot,
  },
  'm5max-snapshot': {
    environment: 'm5max',
    read: m5maxSnapshot,
  },
  'actions-axon': {
    environment: 'actions',
    runs: { '': '41681347500' },
    read: axonComparison,
  },
  'm5max-axon': {
    environment: 'm5max',
    read: axonComparison,
  },
};

/** The M5 Max's CPU, which its runs do not record (`runner.cpu` is `unknown` there): the reports' Setup tables say it. */
export const M5MAX = 'Apple M5 Max, 18 cores, 64 GB';

const STORES = { postgres: 'postgres', mongo: 'mongo' };

const readJson = path => JSON.parse(readFileSync(path, 'utf8'));

/** The suite directories under `dir`, each with its suite.json, by name. */
function suitesIn(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true })
    .filter(
      entry =>
        entry.isDirectory() && existsSync(join(dir, entry.name, 'suite.json')),
    )
    .map(entry => entry.name)
    .sort();
}

/** Where a figure comes from: the build, the CPU model, the run when there is one, and the file in the repository. */
function provenance(suite, source, run) {
  return {
    commit: suite.refs?.headSha?.slice(0, 10),
    cpu:
      suite.runner?.cpu && suite.runner.cpu !== 'unknown'
        ? suite.runner.cpu
        : M5MAX,
    jdk: jdkOf(suite.runner?.jdk),
    ...(run ? { run } : {}),
    source,
  };
}

/** What a page says of a figure whose run did not record its JDK: never an assumed one. */
export const NOT_RECORDED = 'not recorded';

/**
 * A JDK's version as a run records it (`Eclipse Adoptium 25.0.5+8-LTS`, `OpenJDK Runtime Environment
 * Temurin-25.0.5+8 (build 25.0.5+8-LTS)`): `25.0.5+8`, or its major version alone; NOT_RECORDED without one.
 */
export function jdkOf(text) {
  const version =
    /(\d+(?:\.\d+){1,2}\+\d+)/.exec(text ?? '')?.[1] ??
    /\b(\d{2})\b/.exec(text ?? '')?.[1];
  return version ?? NOT_RECORDED;
}

const round = (value, digits = 1) =>
  Number.isFinite(value) ? Number(value.toFixed(digits)) : undefined;

/** The keys of a figure, from a suite's name: `uniform` unless it names `zipf` or `hot`. */
const keysOf = name =>
  /-zipf\b/.test(name) ? 'zipf' : /-hot\b/.test(name) ? 'hot' : 'uniform';

/** The store of a suite: its first arm's backend. */
const storeOf = suite =>
  STORES[suite.arms?.[0]?.backend] ?? suite.arms?.[0]?.backend;

/**
 * The Actions snapshot (L3 and L4): per store and scenario, the L4 sustainable rate (a capacity suite's view) with its
 * closed-loop saturation; each L4 cliff step, cold-cache run and cluster run; and the unit costs of every L3 round
 * of the head (each at its own rate: Wow batches, so a cost belongs to the rate it was measured at).
 */
export function actionsSnapshot(root, report) {
  const figures = [];
  const dir = join(root, REPORTS_DIR, report);
  const l4 = join(dir, 'l4');
  for (const name of suitesIn(l4)) {
    const suite = readJson(join(l4, name, 'suite.json'));
    const from = provenance(
      suite,
      `${report}/l4/${name}/suite.json`,
      REPORTS[report].runs.l4,
    );
    const store = storeOf(suite);
    if (suite.kind === 'capacity') {
      for (const [scenario, c] of Object.entries(suite.capacity ?? {})) {
        figures.push({
          id: `sustainable.${store}.${scenario}`,
          kind: 'sustainable',
          store,
          nodes: 1,
          scenario,
          keys: suite.distribution,
          value: round(c.rate),
          p50Ms: round(c.p50),
          p99Ms: round(c.p99),
          // The node's, the store's and Kafka's CPU together (bench.mjs's cpuMsPerOp).
          cpuMsPerOp: round(c.cpuMsPerOp, 2),
          saturation: round(suite.saturation?.[scenario]?.throughput),
          ...from,
        });
      }
    } else if (suite.kind === 'steps') {
      for (const run of suite.runs.filter(r => r.status === 'ok')) {
        figures.push({
          id: `cliff.${store}.${suite.scenario}.${run.rate}`,
          kind: 'step',
          store,
          nodes: 1,
          scenario: suite.scenario,
          rate: run.rate,
          value: round(run.metrics.throughput),
          p50Ms: round(run.metrics.p50),
          p99Ms: round(run.metrics.p99),
          failed: run.metrics.failed,
          ...from,
        });
      }
    } else if (suite.kind === 'fixed' && suite.cold) {
      for (const run of suite.runs.filter(r => r.status === 'ok')) {
        figures.push({
          id: `cold.${store}.${run.scenario}`,
          kind: 'cold',
          store,
          nodes: 1,
          scenario: run.scenario,
          accounts: suite.accounts,
          rate: run.rate,
          value: round(run.metrics.throughput),
          p50Ms: round(run.metrics.p50),
          p95Ms: round(run.metrics.p95),
          p99Ms: round(run.metrics.p99),
          cpuMsPerOp: round(run.metrics.cpuMsPerOp, 2),
          allocatedBytesPerOp: round(run.metrics.allocatedBytesPerOp, 0),
          ...from,
        });
      }
    } else if (suite.kind === 'cluster') {
      // A hand-over run's CPU is not measured (the sampler fails once the stopped node is gone), so none is quoted.
      for (const run of suite.runs.filter(r => r.status === 'ok')) {
        figures.push({
          id: `cluster.${store}.${run.name}`,
          kind: 'cluster',
          store,
          nodes: 3,
          scenario: run.scenario,
          run: run.name,
          handover: Boolean(run.handover) || /handover/.test(run.name),
          rate: run.rate,
          value: round(run.metrics.throughput),
          failed: run.metrics.failed,
          p50Ms: round(run.metrics.p50),
          p99Ms: round(run.metrics.p99),
          worstSecondP99Ms: round(run.metrics.worstSecondP99),
          lowestSecond: run.metrics.minSecondOk,
          ...from,
        });
      }
    }
  }
  const l3 = join(dir, 'l3');
  for (const name of suitesIn(l3)) {
    const suite = readJson(join(l3, name, 'suite.json'));
    const from = provenance(
      suite,
      `${report}/l3/${name}/suite.json`,
      REPORTS[report].runs.l3,
    );
    const store = storeOf(suite);
    const scenario = suite.mix ? 'mixed' : suite.scenario;
    const runs = (suite.runs ?? []).filter(
      run => run.arm === 'head' && run.status === 'ok',
    );
    for (const run of runs) {
      const m = run.metrics;
      // bench.mjs's cpuMsPerOp is the node's and its containers' (the store and Kafka) together; each apart here.
      const perOp = cores =>
        Number.isFinite(cores) && m.throughput > 0
          ? (cores * 1000) / m.throughput
          : undefined;
      const containerCores = name =>
        Number.isFinite(m.containers?.[name])
          ? m.containers[name] / 100
          : undefined;
      figures.push({
        id: `unit.${store}.${name}.${run.name}`,
        kind: 'unit',
        store,
        nodes: 1,
        scenario,
        keys: keysOf(name),
        accounts: suite.accounts,
        rate: round(run.rate),
        value: round(m.cpuMsPerOp, 3),
        unit: 'cpuMsPerOp',
        throughput: round(m.throughput),
        p50Ms: round(m.p50),
        p99Ms: round(m.p99),
        nodeCpuMsPerOp: round(perOp(m.serverCores), 3),
        storeCpuMsPerOp: round(perOp(containerCores(store)), 3),
        busCpuMsPerOp: round(perOp(containerCores('kafka')), 3),
        commitsPerOp: round(m.commitsPerOp, 3),
        walBytesPerOp: round(m.walBytesPerOp, 0),
        allocatedBytesPerOp: round(m.allocatedBytesPerOp, 0),
        ...from,
      });
    }
  }
  return figures;
}

/**
 * The M5 Max snapshot: each sweep and fixed run of its sessions (`summary.json`, summarize.py's), with every step for
 * the curve. A measurement summarize.py set aside (a void attempt) is not quoted.
 */
export function m5maxSnapshot(root, report) {
  const path = join(root, REPORTS_DIR, report, 'summary.json');
  if (!existsSync(path)) return [];
  const summary = readJson(path);
  const environment = join(root, REPORTS_DIR, report, 'environment.txt');
  const commit = environmentCommit(environment);
  const jdk = jdkOf(environmentLine(environment, 'jdk'));
  const figures = [];
  for (const [label, m] of Object.entries(summary.measurements)) {
    if (m.setAside) continue;
    const from = { commit, cpu: M5MAX, jdk, source: `${report}/${label}` };
    const store = STORES[m.backend] ?? m.backend;
    const nodes = Number(m.nodes) || 1;
    if (m.kind === 'sweep') {
      figures.push({
        id: `sweep.${store}.${nodes}.${label.split('/').pop()}`,
        kind: 'sweep',
        store,
        nodes,
        scenario: m.scenario,
        keys: m.keys,
        value: round(m.peak?.throughput),
        clients: m.peak?.clients,
        lowerBound: Boolean(m.peak?.lowerBound),
        single: Boolean(m.peak?.single),
        sustainable: m.sustainable
          ? {
              value: round(m.sustainable.throughput),
              clients: m.sustainable.clients,
              p50Ms: round(m.sustainable.p50Ms),
              p99Ms: round(m.sustainable.p99Ms),
            }
          : null,
        steps: m.steps.map(step => ({
          clients: step.clients,
          value: round(step.throughput),
          p50Ms: round(step.p50Ms, 2),
          p99Ms: round(step.p99Ms),
          failedShare: round(step.failedShare, 4),
          refused: step.errors?.['http-429-wow.command.busy'] ?? 0,
        })),
        disturbed: m.disturbed,
        void: m.void,
        ...from,
      });
    }
  }
  return figures;
}

/** The commit `environment.txt` records (`commit=<sha>`), cut to ten characters. */
function environmentCommit(path) {
  return environmentLine(path, 'commit')?.slice(0, 10);
}

/** The value of `environment.txt`'s first line `<key>: <value>` (or `=`), the run's own record; undefined without one. */
function environmentLine(path, key) {
  if (!existsSync(path)) return undefined;
  const line = readFileSync(path, 'utf8')
    .split('\n')
    .find(text => new RegExp(`^${key}[=:]`).test(text.trim()));
  return line
    ?.trim()
    .slice(key.length + 1)
    .trim();
}

/**
 * An Axon comparison: per suite and sweep, Wow's peak and sustainable rate over Axon's, as the range bench.mjs gives
 * (peakRatio) with the larger arm's A/A spread (aaSpread) and the verdict a range within it is (level); and each
 * session's storage per event and per snapshot, its readiness and its RSS (bench.mjs's storageTable, startupTable).
 */
export function axonComparison(root, report) {
  const dir = join(root, REPORTS_DIR, report);
  const run = REPORTS[report].runs?.[''];
  const figures = [];
  for (const name of suitesIn(dir)) {
    const suite = readJson(join(dir, name, 'suite.json'));
    if (suite.kind !== 'sessions') continue;
    const [wow, axon] = suite.arms.map(arm => arm.id);
    if (!/wow/.test(wow) || !/axon/.test(axon)) continue;
    const from = provenance(suite, `${report}/${name}/suite.json`, run);
    // An arm with a session whose store does not hold what its runs asked of it does not compare (D392 ④).
    const comparable = !(suite.sessions ?? []).some(
      session => session.correctness?.comparable === false,
    );
    const labels = [
      ...new Set(
        (suite.sweeps ?? []).map(sweep => sweep.label ?? sweep.scenario),
      ),
    ];
    for (const label of labels) {
      for (const key of ['peak', 'sustainable']) {
        const ratio = peakRatio(suite, label, key);
        if (!ratio) continue;
        const band = aaSpread(suite, label, key);
        const arms = Object.fromEntries(
          [wow, axon].map(id => [
            id,
            (suite.sweeps ?? [])
              .filter(
                sweep =>
                  sweep.arm === id &&
                  (sweep.label ?? sweep.scenario) === label &&
                  sweep.status === 'ok',
              )
              .map(sweep => round(sweep[key]?.throughput))
              .filter(Number.isFinite),
          ]),
        );
        figures.push({
          id: `axon.${name}.${label.replace(/\s+/g, '-').toLowerCase()}.${key}`,
          kind: 'ratio',
          measure: key,
          suite: name,
          store: 'postgres',
          nodes: 1,
          scenario: label.split(' ')[0],
          keys: label.split(' ')[1] ?? suite.distribution,
          lo: round(ratio.lo, 2),
          hi: round(ratio.hi, 2),
          bound: ratio.bound,
          band: round(band, 3),
          verdict: ratioVerdict(ratio, band, ['wow', 'axon']),
          comparable,
          wow: arms[wow],
          axon: arms[axon],
          ...from,
        });
      }
    }
    for (const arm of [wow, axon]) {
      const sessions = (suite.sessions ?? []).filter(
        session => session.arm === arm && session.status === 'ok',
      );
      const stored = sessions.map(storageOf).filter(Boolean);
      const started = sessions
        .map(session => session.startup?.[0])
        .filter(Boolean);
      if (stored.length === 0 && started.length === 0) continue;
      figures.push({
        id: `footprint.${name}.${arm}`,
        kind: 'footprint',
        suite: name,
        arm: /axon/.test(arm) ? 'axon' : 'wow',
        store: 'postgres',
        bytesPerEvent: range(stored.map(s => s.bytesPerEvent)),
        bytesPerSnapshot: range(stored.map(s => s.bytesPerSnapshot)),
        readyMs: range(started.map(node => node.readyMs)),
        rssMbAtReady: range(started.map(node => node.rssKb / 1024)),
        ...from,
      });
    }
  }
  return figures;
}

/** A session's event and snapshot tables per event and per snapshot, as bench.mjs's storageTable divides them. */
function storageOf(session) {
  const store = session.store;
  if (!store?.tables) return undefined;
  const bytes = kind =>
    store.tables
      .filter(table => table.kind === kind)
      .reduce((total, table) => total + table.totalBytes, 0);
  const events = Object.values(store.events ?? {}).reduce((a, b) => a + b, 0);
  return {
    bytesPerEvent: events > 0 ? bytes('events') / events : undefined,
    bytesPerSnapshot:
      store.snapshots > 0 ? bytes('snapshots') / store.snapshots : undefined,
  };
}

/** The least and the most of `values`, rounded; undefined when none is a number. */
function range(values) {
  const numbers = values.filter(Number.isFinite);
  if (numbers.length === 0) return undefined;
  return [round(Math.min(...numbers), 0), round(Math.max(...numbers), 0)];
}

/** A report's headline: its name, its environment, and its figures, each with its build, CPU model and source. */
export function headlineOf(root, report) {
  const { environment, read } = REPORTS[report];
  return { schema: SCHEMA, report, environment, figures: read(root, report) };
}

/** Where a report's headline is, under the repository's root. */
export const headlinePath = report => `${REPORTS_DIR}/${report}/headline.json`;

/** A headline as its file holds it. */
export const headlineText = headline =>
  `${JSON.stringify(headline, null, 2)}\n`;

/** The reports present in `root`: those the documentation quotes whose directory the tree has. */
export const reportsIn = root =>
  Object.keys(REPORTS).filter(report =>
    existsSync(join(root, REPORTS_DIR, report)),
  );

/**
 * Each report's headline.json as its data gives it, by path. docs-reference.mjs writes them with the pages
 * (`pnpm docs:reference`) and fails when one is not what its data gives.
 */
export function headlineFiles(root) {
  return Object.fromEntries(
    reportsIn(root).map(report => [
      headlinePath(report),
      headlineText(headlineOf(root, report)),
    ]),
  );
}

/** Writes each report's headline.json; with `check`, writes nothing and returns the reports whose file differs. */
export function writeHeadlines(root, { check = false } = {}) {
  const stale = [];
  for (const [path, expected] of Object.entries(headlineFiles(root))) {
    const actual = existsSync(join(root, path))
      ? readFileSync(join(root, path), 'utf8')
      : undefined;
    if (actual === expected) continue;
    stale.push(path.split('/').at(-2));
    if (!check) writeFileSync(join(root, path), expected);
  }
  return stale;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const check = process.argv.includes('--check');
  const stale = writeHeadlines(process.cwd(), { check });
  if (check && stale.length > 0) {
    console.error(
      `headline.json is not what the report's data gives: ${stale.join(', ')}. Run node .github/scripts/bench-headline.mjs.`,
    );
    process.exitCode = 1;
  }
}
