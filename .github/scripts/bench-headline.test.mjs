/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  M5MAX,
  NOT_RECORDED,
  REPORTS,
  REPORTS_DIR,
  SCHEMA,
  actionsSnapshot,
  axonComparison,
  headlineOf,
  jdkOf,
  m5maxSnapshot,
  writeHeadlines,
} from './bench-headline.mjs';

const ROOT = process.cwd();

/** A root with `files` (path under the reports' directory to content) written, for a reader to read. */
function rootWith(files) {
  const root = mkdtempSync(join(tmpdir(), 'bench-headline-'));
  for (const [path, content] of Object.entries(files)) {
    const file = join(root, REPORTS_DIR, path);
    mkdirSync(join(file, '..'), { recursive: true });
    writeFileSync(
      file,
      typeof content === 'string' ? content : JSON.stringify(content),
    );
  }
  return root;
}

const RUNNER = {
  cpu: 'AMD EPYC 7763 64-Core Processor',
  cores: 4,
  jdk: 'Eclipse Adoptium 25.0.5+8-LTS',
};
const REFS = { headSha: 'a7c372ad3ef808f479776a6183247c39f46189fc' };

test('an Actions capacity suite gives each scenario its sustainable rate, beside its saturation', () => {
  const root = rootWith({
    'actions-snapshot/l4/sustainable-pg.100k/suite.json': {
      kind: 'capacity',
      distribution: 'uniform',
      arms: [{ id: 'head', backend: 'postgres' }],
      refs: REFS,
      runner: RUNNER,
      saturation: { E1: { throughput: 4912.5 } },
      capacity: {
        E1: {
          rate: 2609.765625,
          p50: 4.575,
          p99: 160.767,
          cpuMsPerOp: 0.96685,
        },
      },
    },
  });
  const [figure] = actionsSnapshot(root, 'actions-snapshot');
  assert.deepEqual(figure, {
    id: 'sustainable.postgres.E1',
    kind: 'sustainable',
    store: 'postgres',
    nodes: 1,
    scenario: 'E1',
    keys: 'uniform',
    value: 2609.8,
    p50Ms: 4.6,
    p99Ms: 160.8,
    cpuMsPerOp: 0.97,
    saturation: 4912.5,
    commit: 'a7c372ad3e',
    cpu: RUNNER.cpu,
    jdk: '25.0.5+8',
    run: REPORTS['actions-snapshot'].runs.l4,
    source: 'actions-snapshot/l4/sustainable-pg.100k/suite.json',
  });
});

test("an L3 round of the head is a unit cost at its own rate, the node's, the store's and Kafka's CPU apart", () => {
  const root = rootWith({
    'actions-snapshot/l3/e1-pg-uniform.100k/suite.json': {
      kind: 'paired',
      scenario: 'E1',
      accounts: 100000,
      arms: [
        { id: 'base', backend: 'postgres' },
        { id: 'head', backend: 'postgres' },
      ],
      refs: REFS,
      runner: RUNNER,
      runs: [
        { name: 'r1-base', arm: 'base', status: 'ok', rate: 1000, metrics: {} },
        {
          name: 'r1-head',
          arm: 'head',
          status: 'ok',
          rate: 1000,
          metrics: {
            throughput: 1000,
            cpuMsPerOp: 1.5,
            serverCores: 0.7,
            containers: { postgres: 50, kafka: 30 },
            commitsPerOp: 0.65,
            walBytesPerOp: 1290.4,
            allocatedBytesPerOp: 99000,
            p50: 2,
            p99: 40,
          },
        },
      ],
    },
  });
  const figures = actionsSnapshot(root, 'actions-snapshot');
  assert.equal(figures.length, 1, 'the base arm is not quoted');
  assert.equal(figures[0].id, 'unit.postgres.e1-pg-uniform.100k.r1-head');
  assert.equal(figures[0].rate, 1000);
  assert.equal(figures[0].nodeCpuMsPerOp, 0.7);
  assert.equal(figures[0].storeCpuMsPerOp, 0.5);
  assert.equal(figures[0].busCpuMsPerOp, 0.3);
  assert.equal(figures[0].walBytesPerOp, 1290);
});

test("the M5 Max snapshot's sweeps are summarize.py's, each step kept, an attempt set aside left out", () => {
  const root = rootWith({
    'm5max-snapshot/environment.txt':
      'machine: Mac17,14\ncommit: d9b0781205aa5749bf7e3941dfcbb622c2eaac36\njdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)\n',
    'm5max-snapshot/summary.json': {
      measurements: {
        '1-S-postgres/e1-sweep': {
          kind: 'sweep',
          scenario: 'E1',
          keys: 'uniform',
          nodes: 1,
          backend: 'postgres',
          peak: {
            throughput: 37713.6,
            clients: 4096,
            lowerBound: true,
            single: false,
          },
          sustainable: {
            throughput: 23030.13,
            clients: 1024,
            p50Ms: 20.1,
            p99Ms: 74.8,
          },
          steps: [
            {
              clients: 8192,
              throughput: 21273,
              p50Ms: 9,
              p99Ms: 900,
              failedShare: 0.04,
              errors: { 'http-429-wow.command.busy': 26600 },
            },
          ],
          disturbed: [],
          void: [],
          setAside: false,
        },
        '1-S-postgres.void-1/e1-sweep': {
          kind: 'sweep',
          backend: 'postgres',
          setAside: true,
        },
      },
    },
  });
  const figures = m5maxSnapshot(root, 'm5max-snapshot');
  assert.equal(figures.length, 1);
  assert.equal(figures[0].id, 'sweep.postgres.1.e1-sweep');
  assert.equal(figures[0].lowerBound, true);
  assert.equal(figures[0].sustainable.value, 23030.1);
  assert.deepEqual(figures[0].steps[0], {
    clients: 8192,
    value: 21273,
    p50Ms: 9,
    p99Ms: 900,
    failedShare: 0.04,
    refused: 26600,
  });
  assert.equal(figures[0].commit, 'd9b0781205');
  assert.equal(figures[0].cpu, M5MAX);
  assert.equal(figures[0].jdk, '25.0.5+8');
});

test('a JDK is the version its run records, never an assumed one', () => {
  assert.equal(jdkOf('Eclipse Adoptium 25.0.5+8-LTS'), '25.0.5+8');
  assert.equal(
    jdkOf(
      'OpenJDK Runtime Environment Temurin-21.0.9+10 (build 21.0.9+10-LTS)',
    ),
    '21.0.9+10',
  );
  assert.equal(jdkOf('openjdk 25'), '25');
  assert.equal(jdkOf(undefined), NOT_RECORDED);
  assert.equal(jdkOf('unknown'), NOT_RECORDED);
});

test("an Axon comparison is Wow's range over Axon's with the A/A spread's verdict, and not comparable when a store was wrong", () => {
  const sweep = (arm, round, throughput) => ({
    name: `${round}-${arm}-e1-sweep`,
    arm,
    label: 'E1',
    status: 'ok',
    peak: { throughput, concurrency: 1024 },
    sustainable: { throughput: throughput / 2, concurrency: 64, p99: 100 },
  });
  const suite = correct => ({
    kind: 'sessions',
    distribution: 'uniform',
    arms: [{ id: 'wow' }, { id: 'axon' }],
    refs: { headSha: 'cc11b16aa893b58f3a450c9ba62eadde16741e45' },
    runner: { cpu: 'unknown' },
    sweeps: [
      sweep('wow', 1, 4000),
      sweep('wow', 2, 4100),
      sweep('axon', 1, 1000),
      sweep('axon', 2, 1010),
    ],
    sessions: [
      {
        name: '1-wow',
        arm: 'wow',
        status: 'ok',
        correctness: { comparable: correct },
        startup: [{ readyMs: 1800, rssKb: 471632 }],
        store: {
          tables: [
            { kind: 'events', totalBytes: 615000 },
            { kind: 'snapshots', totalBytes: 50200 },
          ],
          events: { Deposited: 1000 },
          snapshots: 100,
        },
      },
    ],
  });
  const root = rootWith({
    'm5max-axon/m5max-axon-peak-e1/suite.json': suite(true),
    'm5max-axon/m5max-axon-contention-e1-zipf/suite.json': suite(false),
  });
  const figures = axonComparison(root, 'm5max-axon');
  const peak = figures.find(f => f.id === 'axon.m5max-axon-peak-e1.e1.peak');
  assert.equal(peak.lo, 3.96);
  assert.equal(peak.hi, 4.1);
  assert.equal(peak.verdict, 'wow higher');
  assert.equal(peak.comparable, true);
  assert.equal(peak.cpu, M5MAX);
  assert.equal(
    figures.find(f => f.id === 'axon.m5max-axon-contention-e1-zipf.e1.peak')
      .comparable,
    false,
  );
  const footprint = figures.find(
    f => f.id === 'footprint.m5max-axon-peak-e1.wow',
  );
  assert.deepEqual(footprint.bytesPerEvent, [615, 615]);
  assert.deepEqual(footprint.bytesPerSnapshot, [502, 502]);
  assert.deepEqual(footprint.readyMs, [1800, 1800]);
  assert.deepEqual(footprint.rssMbAtReady, [461, 461]);
});

test('each run a report is said to come from is one its report names', () => {
  for (const [report, { runs = {} }] of Object.entries(REPORTS)) {
    const text = readFileSync(join(ROOT, REPORTS_DIR, `${report}.md`), 'utf8');
    for (const run of Object.values(runs)) {
      assert.ok(text.includes(run), `${report}.md names no run ${run}`);
    }
  }
});

test("every headline.json in the tree is what its report's data gives", () => {
  assert.deepEqual(writeHeadlines(ROOT, { check: true }), []);
  for (const report of Object.keys(REPORTS)) {
    const headline = headlineOf(ROOT, report);
    assert.equal(headline.schema, SCHEMA);
    for (const figure of headline.figures) {
      assert.ok(
        figure.commit && figure.cpu && figure.jdk && figure.source,
        `${report}: ${figure.id} lacks its provenance`,
      );
    }
  }
});
