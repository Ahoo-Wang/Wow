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
        "What PostgreSQL alone does with the writes Wow asks of it, and with the shapes that price a candidate: Wow's tables cloned and written with rows of Wow's own commits through the driver alone. Today's shape, first on Wow's ground (its rows, rate and batch shape) as the calibration (WAL records, bytes and the HOT share per command against Wow's own, full-page images and commits apart), the append alone, the snapshot upsert alone at 4, 10 and 25 rows a statement, synchronous and not, at fillfactor 100 and 80 (P0, P1), the append and its snapshots in one synchronous transaction against today's split at one rate (P2), and a point read; per case, units a second, the store's CPU, WAL bytes, syncs and commits per unit, and the HOT share of the snapshot updates.",
    },
    'raw-mongo': {
      group: 'raw',
      kind: 'measure',
      measure: 'raw',
      arms: [HEAD_MONGO],
      answers:
        "What MongoDB alone does with the writes Wow asks of it, as raw-pg: today's shape, first on Wow's ground as the calibration (inserts, updates, journal and oplog bytes per command against Wow's own, its node's checkpoint and lease updates left out), the log's insertMany alone, the snapshot update in each shape its store cost is priced in (M0 today's pipeline, M1 a modifier $set, M2 with $setOnInsert, M3 replaceOne) at 4, 10 and 25 a bulkWrite with the domain's state and a 4 KB one, and the log's insert and its snapshots in one multi-document transaction beside the same calls back to back, each step on a pool of its connections; per update, mongod's CPU, its write latency (latencyStats), journal and oplog bytes.",
    },
  };
}

/**
 * A raw run's CPUs, its store's as its paired suites give it, so a ceiling is of the same store as Wow's figure (a
 * store's throughput does not scale with its cores, so no per-core figure would stand in): on MongoDB mongod on one CPU
 * of its own (the paired layout's `store`) and the program on the others but the generator's; on PostgreSQL the
 * program and the store sharing the paired layout's server CPUs. [cpuset] is the measure layout (one generator CPU,
 * the last); below four CPUs, as it is.
 */
export function rawCpus(cpuset, backend) {
  const total = Number(cpuset.load) + 1;
  if (!(total >= 4) || !cpuset.server) return cpuset;
  if (backend === 'mongo') {
    const store = total - 3;
    const others = [...Array(total - 1).keys()].filter(cpu => cpu !== store);
    return { ...cpuset, store: `${store}`, server: others.join(',') };
  }
  return { ...cpuset, server: `0-${total - 3}` };
}

/** A raw run's CPUs in words (rawCpus): the program's, the store's, and the idle generator's. */
export function rawLayoutText(cpus) {
  if (!cpus?.server) return 'CPUs: unpinned.';
  const store = cpus.store
    ? `the store on ${cpus.store} alone`
    : 'the store with the program';
  return `CPUs: the program on ${cpus.server}, ${store}${cpus.load ? `, ${cpus.load} idle (the generator's)` : ''}.`;
}

/** The arguments of a raw run of [arm] into [dir]: bench.sh measure's, the program's settings, its CPUs (rawCpus). */
export function rawArgs(arm, installs, measured, dir) {
  const cpuset = rawCpus(measured, arm.backend);
  return [
    'measure',
    '--program=raw',
    `--backend=${arm.backend}`,
    ...(installs[arm.install] ? [`--install=${installs[arm.install]}`] : []),
    `--connections=${RAW.connections}`,
    `--warm-up=${RAW.warmUp}`,
    `--seconds=${RAW.seconds}`,
    ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
    ...(cpuset.store ? [`--store-cpus=${cpuset.store}`] : []),
    `--out=${dir}`,
  ];
}

/** Runs a raw suite into [suite]: one run per arm, its raw.json read. */
export function runRaw(
  definition,
  suite,
  { benchPath, out, cpuset, arms, installs, save },
) {
  // The suite records the layout its runs had (rawCpus), not the measure layout it was given.
  const backends = [...new Set(arms.map(arm => arm.backend))];
  if (suite.cpus && backends.length === 1)
    suite.cpus = rawCpus(suite.cpus, backends[0]);
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

const ratioText = check =>
  Number.isFinite(check.absolute)
    ? `${num(check.rawPerCommand, 3)} against Wow's ${num(check.wowPerCommand, 3)} (within ±${num(check.absolute, 2)}: ${check.passed ? 'yes' : 'no'})`
    : Number.isFinite(check.rawPerCommand) &&
        Number.isFinite(check.wowPerCommand)
      ? `${num(check.rawPerCommand, 3)} against Wow's ${num(check.wowPerCommand, 3)} (${num(check.rawPerCommand / check.wowPerCommand, 3)}${Number.isFinite(check.share) ? `, within ±${num(check.share * 100, 0)}%: ${check.passed ? 'yes' : 'no'}` : ''})`
      : `not read on both sides (Wow ${check.wowPerCommand ?? 'none'}, clone ${check.rawPerCommand ?? 'none'})`;

/**
 * A raw run's calibration in words: each check of the clone's counters per command on Wow's ground against Wow's own,
 * then those reported apart, and whether it passed.
 */
export function calibrationText(raw) {
  const c = raw?.calibration;
  if (!c || !Array.isArray(c.checks))
    return '**Uncalibrated: the run recorded no calibration. No case of it is read.**';
  const checks = c.checks
    .map(check => `${check.metric} ${ratioText(check)}`)
    .join('; ');
  const apart = (c.apart ?? [])
    .map(check => `${check.metric} ${ratioText(check)}`)
    .join('; ');
  const tail = `${apart ? ` Apart: ${apart}.` : ''}${nodeTimeText(c)}`;
  if (!c.passed) {
    const why =
      c.read === false
        ? 'its step had a failed or refused write'
        : 'a check is outside its bound';
    return `**Uncalibrated (${why}): ${checks || 'no check'}.${tail} No case of this run is read.**`;
  }
  return `Calibrated on Wow's ground, per command: ${checks}.${tail}`;
}

/**
 * Why the model's own shape at Wow's rate is not Wow's, from the calibration's apart metrics: Wow's node time around
 * each append call, which the model has none of; empty unless both sides' call and round trip were read.
 */
export function nodeTimeText(calibration) {
  const of = metric =>
    (calibration?.apart ?? []).find(check => check.metric === metric) ?? {};
  const [call, trip, appends] = [
    'appendCallMs',
    'appendRoundTripMs',
    'uncapped appendsPerCommand',
  ].map(of);
  const values = [call, trip, appends].flatMap(check => [
    check.wowPerCommand,
    check.rawPerCommand,
  ]);
  if (!values.every(Number.isFinite)) return '';
  return (
    ` The model's own shape differs from Wow's by Wow's node time around each append call: Wow's call ${num(call.wowPerCommand, 2)} ms against its store round trip ${num(trip.wowPerCommand, 2)} ms (${num(call.wowPerCommand - trip.wowPerCommand, 2)} ms around it), the model's ${num(call.rawPerCommand, 2)} against ${num(trip.rawPerCommand, 2)} ms,` +
    ` so at Wow's rate Wow's appends carry ${num(1 / appends.wowPerCommand, 1)} commands and the model's ${num(1 / appends.rawPerCommand, 1)}.`
  );
}

/**
 * The ceilings a figure of Wow's is compared with, each the best candidate step of Wow's own shape (two appenders
 * merging the lanes' batches to 512 streams, two snapshot writers of up to 256 rows): committed, a command answered at
 * its append with its snapshots saved behind and kept up with; snapshot, answered once its snapshot is written too.
 * What they leave out is said beside them.
 */
export function ceilingText(raw) {
  const ceilings = raw?.ceilings ?? {};
  const named = [
    [
      'committed',
      'answered at the append, its snapshots saved behind and kept up with',
    ],
    ['snapshot', 'answered once its snapshot is written'],
  ]
    .filter(([kind]) => ceilings[kind])
    .map(
      ([kind, what]) =>
        `${kind} ${num(ceilings[kind].unitsPerSecond, 0)} commands/s (\`${ceilings[kind].case}\` at ${ceilings[kind].connections} callers; ${what})`,
    );
  if (named.length === 0)
    return 'No ceiling: the run is uncalibrated, or no step of the shape Wow gives the store was a candidate (read, unpaced, no snapshot failed, a committed one with its snapshots kept up).';
  return `Ceilings: ${named.join('; ')}. Each is the store alone doing Wow's appends and snapshots in Wow's own shape; it leaves out the node's checkpoints and leases, the tails' reads and Kafka, which Wow's figure pays for too. The per-connection and what-if cases are shapes Wow does not give the store.`;
}

/** The store's version and durability settings, and the run's batch shape, a ceiling is quoted with. */
export function settingsText(raw) {
  const store = Object.entries(raw?.store ?? {})
    .map(([key, value]) => `${key} ${value}`)
    .join(', ');
  return `Store: ${store || 'not recorded'}. Batches: ${raw?.logBatch ?? '?'} commands a flush, their snapshots ${raw?.snapshotBatch ?? '?'} a statement or call; cases at ${(raw?.batches ?? []).join(', ')} a statement or call; ${raw?.aggregates ?? '?'} aggregates.`;
}

/** Whether a step is read: every write it asked for taken and none failed, else what was not. */
const readText = step =>
  step.read
    ? 'yes'
    : `no: ${step.failures ?? '?'} failed, ${step.refused ?? '?'} refused`;

/** A raw run's steps as a table: per case and connections, what it did and what each unit cost. */
export function rawTable(run) {
  const mongo = run.raw?.backend === 'mongo';
  const head = mongo
    ? "| Case | Prices | Wow's shape (ceiling) | Unit | Connections | Pool | Units/s | Flush p50 / p99 | mongod CPU µs/unit | Write latency µs | Journal bytes/unit | Oplog bytes/unit | Journal syncs/unit | Read |"
    : "| Case | Prices | Wow's shape (ceiling) | Unit | Connections | Rate | Units/s | Flush p50 / p99 | Late | Store CPU µs/unit | WAL bytes/unit | WAL syncs/unit | Full-page images/unit | Commits/unit | HOT share | Read |";
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
            step.ceiling ?? 'no',
            step.unit,
            step.connections,
            step.pool ?? 'default',
            num(step.unitsPerSecond, 0),
            flush,
            cpu,
            Number.isFinite(step.ratios?.snapshotWriteMicros)
              ? num(step.ratios.snapshotWriteMicros, 1)
              : '-',
            perUnit(step, 'journalBytes', 0),
            perUnit(step, 'oplogBytes', 0),
            perUnit(step, 'journalSyncs', 3),
            readText(step),
          ]
        : [
            step.case,
            step.prices,
            step.ceiling ?? 'no',
            step.unit,
            step.connections,
            Number.isFinite(step.rate)
              ? `${num(step.rate, 0)}/s`
              : 'closed loop',
            num(step.unitsPerSecond, 0),
            flush,
            Number.isFinite(step.rate) ? step.late : '-',
            cpu,
            perUnit(step, 'walBytes', 0),
            perUnit(step, 'walSyncs', 3),
            perUnit(step, 'walFpi', 3),
            perUnit(step, 'commits', 3),
            Number.isFinite(step.ratios?.hotShare)
              ? `${num(step.ratios.hotShare * 100, 0)}%`
              : '-',
            readText(step),
          ];
      return `| ${cells.join(' | ')} |`;
    }),
  ];
}

/**
 * A raw suite's anomalies: a run that failed, an uncalibrated run, a step not read (a failed or refused write), and a
 * paced step whose flushes started late.
 */
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
      const context = `${step.case} at ${step.connections}`;
      if (!step.read) {
        row(
          run,
          context,
          'not read',
          `${step.failures} failed flushes of ${step.flushes + step.failures}, ${step.refused} units refused`,
        );
      }
      if (Number.isFinite(step.rate) && step.late > 0) {
        row(
          run,
          context,
          'late flushes',
          `${step.late} of ${step.flushes} started over 1 ms after their time`,
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
    `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores, ${suite.runner?.jdk ?? 'JDK not recorded'}. ${rawLayoutText(suite.cpus)} ${RAW.warmUp} s unmeasured and ${RAW.seconds} s measured a step.`,
    '',
  ];
  for (const run of suite.runs.filter(it => it.status === 'ok')) {
    lines.push(
      `### ${run.name}`,
      '',
      calibrationText(run.raw),
      '',
      ceilingText(run.raw),
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
