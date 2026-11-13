/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { SUITES } from './bench.mjs';
import {
  RAW,
  calibrationText,
  ceilingText,
  rawAnomalies,
  rawArgs,
  rawTable,
  renderRaw,
  settingsText,
} from './bench-raw.mjs';

const CALIBRATED = {
  passed: true,
  read: true,
  checks: [
    {
      metric: 'walRecords',
      wowPerCommand: 9.5,
      rawPerCommand: 9.6,
      share: 0.1,
      absolute: null,
      passed: true,
    },
    {
      metric: 'hotShare',
      wowPerCommand: 0.92,
      rawPerCommand: 0.9,
      share: null,
      absolute: 0.1,
      passed: true,
    },
  ],
  apart: [
    {
      metric: 'walFpi',
      wowPerCommand: 0.01,
      rawPerCommand: 0.02,
      share: null,
      absolute: null,
    },
  ],
};

const STEP = {
  case: 'snapshot-4-async-ff100',
  unit: 'row',
  prices: 'P0: today',
  connections: 64,
  rate: null,
  seconds: 20,
  units: 52000,
  flushes: 13000,
  failures: 0,
  refused: 0,
  late: 0,
  read: true,
  pool: null,
  unitsPerSecond: 2600,
  p50FlushMs: 3.2,
  p99FlushMs: 11.5,
  storeCpuMsPerUnit: 0.0512,
  perUnit: { walBytes: 640.4, walSyncs: 0.01, walFpi: 0.02, commits: 0.25 },
  ratios: { hotShare: 0.75 },
};

const RUN = {
  name: 'head',
  status: 'ok',
  raw: {
    backend: 'postgres',
    calibration: CALIBRATED,
    logBatch: 10,
    snapshotBatch: 4,
    batches: [4, 10, 25],
    aggregates: 100000,
    store: { server_version: '17.6', synchronous_commit: 'on', fsync: 'on' },
    steps: [STEP],
  },
};

test('the raw suites are measure suites of the raw program on the head', () => {
  for (const [name, backend] of [
    ['raw-pg', 'postgres'],
    ['raw-mongo', 'mongo'],
  ]) {
    const suite = SUITES[name];
    assert.equal(suite.kind, 'measure');
    assert.equal(suite.measure, 'raw');
    assert.equal(suite.group, 'raw');
    assert.deepEqual(
      suite.arms.map(arm => [arm.install, arm.backend]),
      [['head', backend]],
    );
  }
});

test("a raw run passes the program's timing, the head's install and the server's CPUs", () => {
  assert.deepEqual(
    rawArgs(
      { backend: 'postgres', install: 'head' },
      { head: '/install/head' },
      { server: '0-2' },
      '/out/head',
    ),
    [
      'measure',
      '--program=raw',
      '--backend=postgres',
      '--install=/install/head',
      `--connections=${RAW.connections}`,
      `--warm-up=${RAW.warmUp}`,
      `--seconds=${RAW.seconds}`,
      '--server-cpus=0-2',
      '--out=/out/head',
    ],
  );
});

test('the calibration names each check against Wow, and an uncalibrated run is read for nothing', () => {
  assert.equal(
    calibrationText(RUN.raw),
    "Calibrated on Wow's ground, per command: walRecords 9.600 against Wow's 9.500 (1.011, within ±10%: yes); hotShare 0.900 against Wow's 0.920 (within ±0.10: yes). Apart: walFpi 0.020 against Wow's 0.010 (2.000).",
  );
  const outside = {
    ...CALIBRATED,
    passed: false,
    checks: [
      { ...CALIBRATED.checks[0], rawPerCommand: 12, passed: false },
      {
        metric: 'journalBytes',
        wowPerCommand: null,
        rawPerCommand: 900,
        share: 0.1,
        passed: false,
      },
    ],
  };
  assert.match(
    calibrationText({ calibration: outside }),
    /^\*\*Uncalibrated \(a check is outside its bound\): walRecords 12\.000 against Wow's 9\.500 \(1\.263, within ±10%: no\); journalBytes not read on both sides \(Wow none, clone 900\)\. .* No case of this run is read\.\*\*$/,
  );
  assert.match(
    calibrationText({
      calibration: { ...CALIBRATED, passed: false, read: false },
    }),
    /^\*\*Uncalibrated \(its step had a failed or refused write\)/,
  );
  assert.match(calibrationText({}), /recorded no calibration/);
});

test('the store and the batch shape a ceiling is quoted with', () => {
  assert.equal(
    settingsText(RUN.raw),
    'Store: server_version 17.6, synchronous_commit on, fsync on. Batches: 10 commands a flush, their snapshots 4 a statement or call; cases at 4, 10, 25 a statement or call; 100000 aggregates.',
  );
});

test("a PostgreSQL step's row is per unit, its HOT share beside it, a paced step's rate named", () => {
  const [head, , row, paced] = rawTable({
    raw: {
      ...RUN.raw,
      steps: [STEP, { ...STEP, case: 'p2-combined', rate: 1200 }],
    },
  });
  assert.match(head, /WAL bytes\/unit .* HOT share/);
  assert.equal(
    row,
    '| snapshot-4-async-ff100 | P0: today | no | row | 64 | closed loop | 2600 | 3.20 / 11.50 ms | - | 51.2 | 640 | 0.010 | 0.020 | 0.250 | 75% | yes |',
  );
  assert.match(
    paced,
    /^\| p2-combined \| .* \| 1200\/s \| 2600 \| 3\.20 \/ 11\.50 ms \| 0 \|/,
  );
});

test("a MongoDB step's row is mongod's CPU, the write latency, the journal and the oplog per update", () => {
  const step = {
    ...STEP,
    case: 'm1-10-state',
    prices: 'B: M1',
    unit: 'update',
    storeCpuMsPerUnit: null,
    perUnit: { journalBytes: 512.2, oplogBytes: 300.6, journalSyncs: 0.004 },
    ratios: { snapshotWriteMicros: 41.25 },
    pool: 64,
  };
  const [head, , row] = rawTable({
    raw: { backend: 'mongo', steps: [step] },
  });
  assert.match(head, /mongod CPU µs\/unit \| Write latency µs/);
  assert.equal(
    row,
    '| m1-10-state | B: M1 | no | update | 64 | 64 | 2600 | 3.20 / 11.50 ms | not recorded | 41.3 | 512 | 301 | 0.004 | yes |',
  );
});

test('a failed run, an uncalibrated one, a step not read and late paced flushes are anomalies; the summary leads each run with its calibration', () => {
  const suite = {
    name: 'raw-mongo',
    runner: {
      cpu: 'AMD EPYC 7763',
      cores: 4,
      jdk: 'Eclipse Adoptium 25.0.5+8-LTS',
    },
    failures: 1,
    runs: [
      {
        name: 'head',
        status: 'ok',
        raw: {
          ...RUN.raw,
          backend: 'mongo',
          calibration: { ...CALIBRATED, passed: false },
          steps: [
            { ...STEP, failures: 3, refused: 2, read: false },
            { ...STEP, case: 'p2-combined', rate: 1200, late: 7 },
          ],
        },
      },
      { name: 'other', status: 'failed' },
    ],
  };
  assert.deepEqual(
    rawAnomalies(suite).map(row => row.kind),
    ['uncalibrated', 'not read', 'late flushes', 'run failed'],
  );
  assert.equal(
    rawTable(suite.runs[0])[2].split(' | ').at(-1),
    'no: 3 failed, 2 refused |',
  );
  const summary = renderRaw(suite, SUITES['raw-mongo']);
  assert.match(summary, /^## raw-mongo@100k/);
  assert.match(summary, /Eclipse Adoptium 25\.0\.5\+8-LTS/);
  assert.match(summary, /### head\n\n\*\*Uncalibrated \(/);
  assert.match(summary, /`c-transaction` is a multi-document transaction/);
  assert.match(summary, /\*\*1 run\(s\) failed\*\*/);
});

test('the ceiling is the best read step of a shape Wow can give the store, and its absence is said', () => {
  assert.equal(
    ceilingText({
      ceiling: {
        case: 'wow-shape-512',
        connections: 1024,
        unitsPerSecond: 31250.4,
      },
    }),
    'Ceiling: 31250 commands/s, `wow-shape-512` at 1024 callers (the best read step of a shape Wow can give the store; the per-connection cases are shapes it cannot).',
  );
  assert.match(ceilingText({}), /^No ceiling/);
  const [, , row] = rawTable({
    raw: {
      ...RUN.raw,
      steps: [
        { ...STEP, case: 'wow-shape-512', unit: 'command', wowShape: true },
      ],
    },
  });
  assert.match(row, /^\| wow-shape-512 \| P0: today \| yes \| command \|/);
});
