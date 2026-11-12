/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import {
  anomalyTable,
  bench,
  labelOf,
  num,
  readJson,
} from './bench-common.mjs';

// The raw-driver ceiling (bench.sh measure --program=raw, RawStoreBench): the store alone doing the durable writes Wow
// asks of it per command, in Wow's shape and in the shapes that price a candidate, over a range of connections. Its
// suites run in bench-measure-suites.mjs's `measure` kind; bench-measure.mjs dispatches here by RAW_PROGRAMS. It imports
// bench-common.mjs alone.

export const RAW_PROGRAMS = ['raw'];

/**
 * A raw run's settings: each case over `connections`, `warmUp` seconds unmeasured and `seconds` measured. The batch
 * shapes are the program's own defaults for each store (the L1 counts' shape of Wow's flushes).
 */
export const RAW = {
  connections: '16,64,256',
  warmUp: 5,
  seconds: 20,
};

/** The raw suites on the head's PostgreSQL and MongoDB arms. */
export function rawSuites(HEAD_PG, HEAD_MONGO) {
  return {
    'raw-pg': {
      group: 'raw',
      kind: 'measure',
      measure: 'raw',
      arms: [HEAD_PG],
      answers:
        "What PostgreSQL alone does with the writes Wow asks of it, and with the shapes that price a candidate: Wow's tables cloned and written with rows of Wow's own commits through the driver alone. Today's shape (the calibration: its WAL per command against Wow's own), the append alone, the snapshot upsert alone at 4, 10 and 25 rows a statement, synchronous and not, at fillfactor 100 and 80 (P0, P1), the append and its snapshots in one synchronous transaction against today's split at one rate (P2), and a point read; per case, units a second, the store's CPU, WAL bytes, syncs and commits per unit, and the HOT share of the snapshot updates.",
    },
    'raw-mongo': {
      group: 'raw',
      kind: 'measure',
      measure: 'raw',
      arms: [HEAD_MONGO],
      answers:
        "What MongoDB alone does with the writes Wow asks of it, as raw-pg: today's shape (the calibration), the log's insertMany alone, the snapshot update in each shape its store cost is priced in (M0 today's pipeline, M1 a modifier $set, M2 with $setOnInsert, M3 replaceOne) at 4, 10 and 25 a bulkWrite with the domain's state and a 4 KB one, and the log's insert and its snapshots in one multi-document transaction beside the same calls back to back; per update, mongod's CPU, its write latency (latencyStats), journal and oplog bytes.",
    },
  };
}

/** The arguments of a raw run of [arm] into [dir]: bench.sh measure's, the program's settings, the server's CPUs. */
export function rawArgs(arm, installs, cpuset, dir) {
  return [
    'measure',
    '--program=raw',
    `--backend=${arm.backend}`,
    ...(installs[arm.install] ? [`--install=${installs[arm.install]}`] : []),
    `--connections=${RAW.connections}`,
    `--warm-up=${RAW.warmUp}`,
    `--seconds=${RAW.seconds}`,
    ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
    `--out=${dir}`,
  ];
}

/** Runs a raw suite into [suite]: one run per arm, its raw.json read. */
export function runRaw(
  definition,
  suite,
  { benchPath, out, cpuset, arms, installs, save },
) {
  for (const arm of arms) {
    const dir = join(out, arm.id);
    const run = { name: arm.id, arm: arm.id, status: 'failed' };
    const status = bench(
      benchPath,
      rawArgs(arm, installs, cpuset, dir),
      arm.props,
    );
    const file = join(dir, 'raw.json');
    if (status === 0 && existsSync(file)) {
      run.status = 'ok';
      run.raw = readJson(file);
    } else if (status === 3) {
      // A build older than RawStoreBench: nothing to measure, not a failure.
      run.status = 'unavailable';
    } else {
      suite.failures++;
    }
    suite.runs.push(run);
    save();
  }
}

const perUnit = (step, key, digits = 2, scale = 1) =>
  Number.isFinite(step.perUnit?.[key])
    ? num(step.perUnit[key] * scale, digits)
    : '-';

/** A raw run's calibration in words: Wow's bytes per command against the clone's, and whether it passed. */
export function calibrationText(raw) {
  const c = raw?.calibration;
  if (!c)
    return '**Uncalibrated: the run recorded no calibration. No case of it is read.**';
  if (!c.passed) {
    const measured = Number.isFinite(c.ratio)
      ? `the clone's ${c.metric} per command is ${num(c.rawPerCommand, 0)} against Wow's ${num(c.wowPerCommand, 0)} (${num(c.ratio, 3)}, outside ±${num(c.tolerance * 100, 0)}%)`
      : `${c.metric} per command could not be read on both sides (Wow ${c.wowPerCommand ?? 'none'}, clone ${c.rawPerCommand ?? 'none'})`;
    return `**Uncalibrated: ${measured}. No case of this run is read.**`;
  }
  return `Calibrated: the clone's ${c.metric} per command is ${num(c.rawPerCommand, 0)} against Wow's own ${num(c.wowPerCommand, 0)} (${num(c.ratio, 3)}, within ±${num(c.tolerance * 100, 0)}%).`;
}

/** The store's version and durability settings, and the run's batch shape, a ceiling is quoted with. */
export function settingsText(raw) {
  const store = Object.entries(raw?.store ?? {})
    .map(([key, value]) => `${key} ${value}`)
    .join(', ');
  return `Store: ${store || 'not recorded'}. Batches: ${raw?.logBatch ?? '?'} commands a flush, their snapshots ${raw?.snapshotBatch ?? '?'} a statement or call; cases at ${(raw?.batches ?? []).join(', ')} a statement or call; ${raw?.aggregates ?? '?'} aggregates.`;
}

/** A raw run's steps as a table: per case and connections, what it did and what each unit cost. */
export function rawTable(run) {
  const mongo = run.raw?.backend === 'mongo';
  const head = mongo
    ? '| Case | Prices | Unit | Connections | Units/s | Flush p50 / p99 | mongod CPU µs/unit | Write latency µs | Journal bytes/unit | Oplog bytes/unit | Journal syncs/unit | Failures |'
    : '| Case | Prices | Unit | Connections | Rate | Units/s | Flush p50 / p99 | Store CPU µs/unit | WAL bytes/unit | WAL syncs/unit | Full-page images/unit | Commits/unit | HOT share | Failures |';
  return [
    head,
    `| ${head
      .split('|')
      .slice(1, -1)
      .map(() => '---')
      .join(' | ')} |`,
    ...(run.raw?.steps ?? []).map(step => {
      const cpu = Number.isFinite(step.storeCpuMsPerUnit)
        ? num(step.storeCpuMsPerUnit * 1000, 1)
        : 'not recorded';
      const flush = `${num(step.p50FlushMs, 2)} / ${num(step.p99FlushMs, 2)} ms`;
      const cells = mongo
        ? [
            step.case,
            step.prices,
            step.unit,
            step.connections,
            num(step.unitsPerSecond, 0),
            flush,
            cpu,
            Number.isFinite(step.ratios?.snapshotWriteMicros)
              ? num(step.ratios.snapshotWriteMicros, 1)
              : '-',
            perUnit(step, 'journalBytes', 0),
            perUnit(step, 'oplogBytes', 0),
            perUnit(step, 'journalSyncs', 3),
            step.failures,
          ]
        : [
            step.case,
            step.prices,
            step.unit,
            step.connections,
            Number.isFinite(step.rate)
              ? `${num(step.rate, 0)}/s`
              : 'closed loop',
            num(step.unitsPerSecond, 0),
            flush,
            cpu,
            perUnit(step, 'walBytes', 0),
            perUnit(step, 'walSyncs', 3),
            perUnit(step, 'walFpi', 3),
            perUnit(step, 'commits', 3),
            Number.isFinite(step.ratios?.hotShare)
              ? `${num(step.ratios.hotShare * 100, 0)}%`
              : '-',
            step.failures,
          ];
      return `| ${cells.join(' | ')} |`;
    }),
  ];
}

/** A raw suite's anomalies: a run that failed, an uncalibrated run, and a step with failed flushes. */
export function rawAnomalies(suite) {
  const rows = [];
  const row = (run, context, kind, detail) =>
    rows.push({ suite: labelOf(suite), run: run.name, context, kind, detail });
  for (const run of suite.runs) {
    if (run.status === 'failed')
      row(run, 'raw', 'run failed', 'bench.sh failed; see the job log');
    if (run.status === 'ok' && !run.raw?.calibration?.passed)
      row(run, 'calibration', 'uncalibrated', 'no case of this run is read');
    for (const step of run.raw?.steps ?? []) {
      if (step.failures > 0) {
        row(
          run,
          `${step.case} at ${step.connections}`,
          'failed flushes',
          `${step.failures} of ${step.flushes + step.failures}`,
        );
      }
    }
  }
  return rows;
}

/** The Markdown summary of a raw suite, [definition] its SUITES entry: each run's calibration first. */
export function renderRaw(suite, definition) {
  const lines = [
    `## ${labelOf(suite)}`,
    '',
    definition?.answers ?? '',
    '',
    `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores, ${suite.runner?.jdk ?? 'JDK not recorded'}. ${RAW.warmUp} s unmeasured and ${RAW.seconds} s measured a step.`,
    '',
  ];
  for (const run of suite.runs.filter(it => it.status === 'ok')) {
    lines.push(
      `### ${run.name}`,
      '',
      calibrationText(run.raw),
      '',
      settingsText(run.raw),
      '',
    );
    if (run.raw?.backend === 'mongo') {
      lines.push(
        "`c-transaction` is a multi-document transaction: read it beside `c-back-to-back`, the same calls without one, so the transaction's own cost is not taken for the shape's.",
        '',
      );
    }
    lines.push(...rawTable(run), '');
  }
  const found = anomalyTable(rawAnomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}
