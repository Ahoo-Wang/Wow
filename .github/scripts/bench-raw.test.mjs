/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { SUITES } from './bench.mjs';
import {
  RAW,
  calibrationText,
  nodeTimeText,
  ceilingText,
  rawAnomalies,
  rawArgs,
  rawCpus,
  rawLayoutText,
  rawTable,
  runRaw,
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

test('the ceilings are named for what each is compared with, and what they leave out is said', () => {
  const text = ceilingText({
    ceilings: {
      committed: {
        case: 'wow-shape-committed',
        connections: 1024,
        unitsPerSecond: 41250.4,
      },
      snapshot: {
        case: 'wow-shape-snapshot',
        connections: 4096,
        unitsPerSecond: 31250.4,
      },
    },
  });
  assert.match(
    text,
    /^Ceilings: committed 41250 commands\/s \(`wow-shape-committed` at 1024 callers; answered at the append, its snapshots saved behind and kept up with\); snapshot 31250 commands\/s \(`wow-shape-snapshot` at 4096 callers; answered once its snapshot is written\)\./,
  );
  assert.match(
    text,
    /leaves out the node's checkpoints and leases, the tails' reads and Kafka/,
  );
  assert.match(
    ceilingText({ ceilings: {} }),
    /^No ceiling: the run is uncalibrated/,
  );
  const [, , row] = rawTable({
    raw: {
      ...RUN.raw,
      steps: [
        {
          ...STEP,
          case: 'wow-shape-snapshot',
          unit: 'command',
          ceiling: 'snapshot',
        },
      ],
    },
  });
  assert.match(
    row,
    /^\| wow-shape-snapshot \| P0: today \| snapshot \| command \|/,
  );
});

test("a raw run's store is on the CPUs its paired suites give it: mongod alone, PostgreSQL with the program", () => {
  const measured = { load: '3', server: '0-2' };
  assert.deepEqual(rawCpus(measured, 'mongo'), {
    load: '3',
    store: '1',
    server: '0,2',
  });
  assert.deepEqual(rawCpus(measured, 'postgres'), { load: '3', server: '0-1' });
  assert.deepEqual(rawCpus({ load: '7', server: '0-6' }, 'mongo'), {
    load: '7',
    store: '5',
    server: '0,1,2,3,4,6',
  });
  assert.deepEqual(rawCpus({}, 'mongo'), {});
  assert.ok(
    rawArgs({ backend: 'mongo' }, {}, measured, '/out').includes(
      '--store-cpus=1',
    ),
  );
});

test("the calibration says Wow's node time around a call is why the model's own shape is not Wow's", () => {
  const apart = [
    { metric: 'appendCallMs', wowPerCommand: 3.5, rawPerCommand: 2.3 },
    { metric: 'appendRoundTripMs', wowPerCommand: 2.7, rawPerCommand: 2.25 },
    {
      metric: 'uncapped appendsPerCommand',
      wowPerCommand: 1 / 15,
      rawPerCommand: 1 / 9,
    },
  ];
  const text = nodeTimeText({ apart });
  assert.match(
    text,
    /Wow's call 3\.50 ms against its store round trip 2\.70 ms \(0\.80 ms around it\)/,
  );
  assert.match(text, /the model's 2\.30 against 2\.25 ms/);
  assert.match(
    text,
    /Wow's appends carry 15(\.0)? commands and the model's 9(\.0)?\./,
  );
  assert.equal(nodeTimeText({ apart: apart.slice(1) }), '');
  assert.match(
    calibrationText({ calibration: { checks: [], apart, passed: true } }),
    /node time around each append call/,
  );
});

test('a raw suite records and prints the CPUs its runs had, not the measure layout it was given', () => {
  const suite = { cpus: { load: '3', server: '0-2' }, runs: [], failures: 0 };
  runRaw(SUITES['raw-mongo'], suite, {
    benchPath: '/bin/false',
    out: mkdtempSync(join(tmpdir(), 'raw-')),
    cpuset: suite.cpus,
    arms: SUITES['raw-mongo'].arms,
    installs: {},
    save: () => {},
  });
  assert.deepEqual(suite.cpus, { load: '3', store: '1', server: '0,2' });
  assert.equal(
    rawLayoutText(suite.cpus),
    "CPUs: the program on 0,2, the store on 1 alone, 3 idle (the generator's).",
  );
  assert.equal(
    rawLayoutText({ load: '3', server: '0-1' }),
    "CPUs: the program on 0-1, the store with the program, 3 idle (the generator's).",
  );
  assert.equal(rawLayoutText(undefined), 'CPUs: unpinned.');
});
