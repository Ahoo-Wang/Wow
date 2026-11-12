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
 * A raw run's settings: each shape and variant over `connections`, `warmUp` seconds unmeasured and `seconds` measured,
 * `logBatch` commands a flush in one append and their snapshots `snapshotBatch` a statement (the L1 counts' shape: about
 * 0.10 append statements and 0.24 snapshot statements per command).
 */
export const RAW = {
  connections: '1,4,16,64,256',
  warmUp: 5,
  seconds: 20,
  logBatch: 10,
  snapshotBatch: 4,
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
        "What PostgreSQL alone does with the writes Wow asks of it per command, and with the shapes that price a candidate: Wow's tables cloned, written with rows of Wow's own commits through the driver alone; the append and the snapshots as separate autocommitted statements (today), the snapshots merged into one statement, both in one transaction, the append alone, and a point read, the snapshot table at fillfactor 100 and 80; per shape and connections, commands a second, the store's CPU, WAL bytes, syncs and commits per command.",
    },
    'raw-mongo': {
      group: 'raw',
      kind: 'measure',
      measure: 'raw',
      arms: [HEAD_MONGO],
      answers:
        "What MongoDB alone does with the writes Wow asks of it per command, as raw-pg: the log's insertMany and the snapshots' guarded upserts at write concern majority, the update as Wow's pipeline or a plain $set; per shape and connections, commands a second, the store's CPU, the journal's syncs and the operations per command.",
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
    `--log-batch=${RAW.logBatch}`,
    `--snapshot-batch=${RAW.snapshotBatch}`,
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

const perCommand = (step, key, digits = 2) =>
  Number.isFinite(step.perCommand?.[key])
    ? num(step.perCommand[key], digits)
    : '-';

/** A raw run's steps as a table: per shape, variant and connections, what it did and what each command cost. */
export function rawTable(run) {
  const mongo = run.raw?.backend === 'mongo';
  const head = mongo
    ? '| Shape | Variant | Connections | Commands/s | Flush p50 / p99 | Store CPU ms/command | Journal syncs/command | Journal sync ms/command | Inserts/command | Updates/command | Failures |'
    : '| Shape | Variant | Connections | Commands/s | Flush p50 / p99 | Store CPU ms/command | WAL bytes/command | WAL syncs/command | WAL sync ms/command | Commits/command | Failures |';
  return [
    head,
    '| --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |',
    ...(run.raw?.steps ?? []).map(step => {
      const cells = mongo
        ? [
            perCommand(step, 'journalSyncs', 3),
            perCommand(step, 'journalSyncMs', 3),
            perCommand(step, 'inserts', 3),
            perCommand(step, 'updates', 3),
          ]
        : [
            perCommand(step, 'walBytes', 0),
            perCommand(step, 'walSyncs', 3),
            perCommand(step, 'walSyncMs', 3),
            perCommand(step, 'commits', 3),
          ];
      return `| ${step.shape} | ${step.variant} | ${step.connections} | ${num(step.commandsPerSecond, 0)} | ${num(step.p50FlushMs, 2)} / ${num(step.p99FlushMs, 2)} ms | ${Number.isFinite(step.storeCpuMsPerCommand) ? num(step.storeCpuMsPerCommand, 3) : 'not recorded'} | ${cells.join(' | ')} | ${step.failures} |`;
    }),
  ];
}

/** A raw suite's anomalies: a run that failed, and a step with failed flushes, whose figures do not hold. */
export function rawAnomalies(suite) {
  const rows = [];
  for (const run of suite.runs) {
    if (run.status === 'failed') {
      rows.push({
        suite: labelOf(suite),
        run: run.name,
        context: 'raw',
        kind: 'run failed',
        detail: 'bench.sh failed; see the job log',
      });
    }
    for (const step of run.raw?.steps ?? []) {
      if (step.failures > 0) {
        rows.push({
          suite: labelOf(suite),
          run: run.name,
          context: `${step.shape} ${step.variant} at ${step.connections}`,
          kind: 'failed flushes',
          detail: `${step.failures} of ${step.flushes + step.failures}`,
        });
      }
    }
  }
  return rows;
}

/** The Markdown summary of a raw suite, [definition] its SUITES entry. */
export function renderRaw(suite, definition) {
  const lines = [
    `## ${labelOf(suite)}`,
    '',
    definition?.answers ?? '',
    '',
    `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores, ${suite.runner?.jdk ?? 'JDK not recorded'}. ${RAW.logBatch} commands a flush, their snapshots ${RAW.snapshotBatch} a statement; ${RAW.warmUp} s unmeasured and ${RAW.seconds} s measured a step. The \`today\` shape is the check: its WAL per command should be Wow's own.`,
    '',
  ];
  for (const run of suite.runs.filter(it => it.status === 'ok')) {
    lines.push(`### ${run.name}`, '', ...rawTable(run), '');
  }
  const found = anomalyTable(rawAnomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}
