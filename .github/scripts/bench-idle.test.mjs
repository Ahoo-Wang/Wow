/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// bench.sh idle's summary (bench-idle.mjs): the store's reads a second while the nodes idle, the log head reads among
// them, and the CPU the nodes add to the store, from the raw inputs a session writes.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  describeMachine,
  environmentName,
  machineOf,
  meanOf,
  mongoReads,
  parseSession,
  postgresReads,
  render,
  summarize,
  table,
} from './bench-idle.mjs';

const session = backend => `backend=${backend}
nodes=1
accounts=100
serverArgs=--wow.transport=none
quietSeconds=45
windowSeconds=60
commit=abc1234
machine.chip=Apple M4 Pro
machine.cores=14
machine.performanceCores=10
machine.efficiencyCores=4
machine.memoryBytes=25769803776
machine.os=macOS 27.2.1
machine.dockerCpus=14
machine.dockerMemoryBytes=12598894592
meta=backend=${backend};nodes=1;serverJvm=openjdk version "25.0.5"`;

test('a session keeps its meta whole, with its own = and ;', () => {
  const parsed = parseSession(session('mongo'));
  assert.equal(parsed.backend, 'mongo');
  assert.equal(
    parsed.meta,
    'backend=mongo;nodes=1;serverJvm=openjdk version "25.0.5"',
  );
});

test('the CPU is the mean of its samples, and none gives null', () => {
  assert.deepEqual(meanOf('10.5\n11.5\n\n12\n'), {
    mean: 11.333333333333334,
    samples: 3,
  });
  assert.equal(meanOf(''), null);
});

test("MongoDB's reads: the opcounters a second, Long or number, and the head aggregations on a log's counters", () => {
  const reads = mongoReads(
    {
      millis: 60_000,
      before: { command: { high: 0, low: 1000, unsigned: false }, query: 10 },
      after: { command: { high: 0, low: 5200, unsigned: false }, query: 250 },
    },
    {
      millis: 20_000,
      operations: [
        {
          op: 'command',
          ns: 'transfer.wow_log__transfer__partition',
          command: 'aggregate',
          count: 1400,
        },
        { op: 'query', ns: 'transfer.wow_lease', command: 'find', count: 26 },
        {
          op: 'command',
          ns: 'transfer.wow_failure_ledger',
          command: 'aggregate',
          count: 22,
        },
      ],
    },
  );
  assert.deepEqual(reads.opcountersPerSecond, { command: 70, query: 4 });
  assert.equal(reads.logHeadReadsPerSecond, 70);
  assert.deepEqual(reads.operations[1], {
    operation: 'query find transfer.wow_lease',
    perSecond: 1.3,
  });
});

test("PostgreSQL's statements a second, the head lookups apart, the extension's own left out", () => {
  const reads = postgresReads(
    [
      '4347\tSELECT sequence FROM "wow_log__transfer__partition" WHERE partition = $1',
      '80\tSELECT subscription_group, node FROM wow_lease_member WHERE subscription_group = $1',
      '1\tselect pg_stat_statements_reset()',
      '',
    ].join('\n'),
    60_000,
  );
  assert.equal(reads.logHeadReadsPerSecond, 72.45);
  assert.equal(reads.statementsPerSecond, 73.78);
  assert.equal(reads.operations.length, 2);
});

test('idle.json gives what the nodes add to the store, and idle.txt says it', () => {
  const idle = summarize({
    session: session('postgres'),
    cpuWithNodes: '2.7\n2.7\n',
    cpuWithoutNodes: '1.5\n1.5\n',
    statements:
      '600\tSELECT sequence FROM "wow_log__transfer__partition" WHERE partition = $1\n',
    statementsMillis: 60_000,
  });
  assert.equal(idle.schema, 'wow-idle/1');
  assert.equal(idle.commit, 'abc1234');
  assert.deepEqual(idle.storeCpu, {
    withNodes: 2.7,
    withoutNodes: 1.5,
    added: 1.2,
    samples: { withNodes: 2, withoutNodes: 2 },
  });
  assert.equal(idle.logHeadReadsPerSecond, 10);
  const text = render(idle);
  assert.match(text, /log head reads: 10\/s/);
  assert.match(text, /1\.2 added by the nodes/);
});

test('a report is named for the machine it ran on, not for a constant', () => {
  assert.equal(environmentName('Apple M4 Pro'), 'm4pro');
  assert.equal(environmentName('Apple M5 Max'), 'm5max');
  assert.equal(
    environmentName('Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz', true),
    'actions',
  );
  assert.equal(
    environmentName('AMD EPYC 7763 64-Core Processor'),
    'epyc776364coreprocessor',
  );
  assert.equal(environmentName(''), 'local');
});

test('the machine is stated as the session read it, and a session without one says so', () => {
  const machine = machineOf(parseSession(session('mongo')));
  assert.equal(
    describeMachine(machine),
    'Apple M4 Pro, 14 cores (10 performance, 4 efficiency), 24 GB; Docker 14 CPUs, 12 GB',
  );
  assert.equal(machineOf({}), null);
  assert.equal(describeMachine(null), 'machine not recorded');
  const idle = summarize({
    session: session('postgres'),
    cpuWithNodes: '2\n',
    cpuWithoutNodes: '1\n',
    statements: '',
    statementsMillis: 60_000,
  });
  assert.equal(idle.environment, 'm4pro');
  assert.match(render(idle), /machine: Apple M4 Pro, 14 cores/);
});

test("a report's table: one row per session, the head reads apart from the other reads", () => {
  const idle = summarize({
    session: session('postgres'),
    cpuWithNodes: '2.7\n',
    cpuWithoutNodes: '1.5\n',
    statements:
      '600\tSELECT sequence FROM "wow_log__transfer__partition" WHERE partition = $1\n60\tSELECT 1 FROM wow_lease\n',
    statementsMillis: 60_000,
  });
  const lines = table([['postgres-1-node', idle]])
    .trim()
    .split('\n');
  assert.equal(lines.length, 3);
  assert.equal(
    lines[2],
    '| postgres-1-node | `--wow.transport=none` | 10 | 2.7 % | 1.5 % | 1.2 | 1 |',
  );
});

test("idle.sh reads the machine it runs on: its chip, cores and memory, and Docker's", () => {
  const idle = join(
    import.meta.dirname,
    '../../test/benchmarks/harness/idle.sh',
  );
  // Docker stubbed: the machine's own figures are read, whatever this machine is.
  const run = spawnSync(
    'bash',
    ['-c', `docker() { echo 8; }; . ${JSON.stringify(idle)}; idle_machine`],
    {
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'true' },
    },
  );
  assert.equal(run.status, 0, run.stderr);
  const machine = machineOf(parseSession(run.stdout));
  assert.ok(machine.chip.length > 0, run.stdout);
  assert.ok(machine.cores > 0 && machine.memoryBytes > 0, run.stdout);
  assert.deepEqual(machine.docker, { cpus: 8, memoryBytes: 8 });
  assert.equal(parseSession(run.stdout)['machine.actions'], 'true');
});
