/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  DISTURBED_CORES,
  containerCoresOf,
  profileText,
  foreignOf,
  hostSamples,
  pipelineLines,
  pipelineOf,
  rowsOf,
  sessionsOf,
  stepEnds,
  tableOf,
} from './bench-abba.mjs';

const END = 1_800_000_000;

/** A node log line of the `bench-pipeline` line at epoch second [second], local time with its offset. */
const pipeline = (second, sections) =>
  `${new Date(second * 1000).toISOString().replace('Z', '+00:00')}  WARN 1 --- [x] [ bench-pipeline] m.a.w.b.t.BenchPipeline : bench-pipeline ${JSON.stringify({ t: 1, ...sections })}`;

/** A sweep's directory under [root]: one step at 1,024 clients ending at END, [throughput] and [errors] its result. */
function sweep(root, name, throughput, errors = {}, calls = 100) {
  const dir = join(root, name, 'sweep');
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    join(dir, 'saturation.json'),
    JSON.stringify({
      steps: [
        {
          concurrency: 1024,
          seconds: 30,
          throughput,
          ok: throughput * 30,
          errors,
          p50Ms: 40,
          p99Ms: 120,
        },
      ],
    }),
  );
  writeFileSync(
    join(dir, 'saturate.log'),
    `${END - 31} [saturate] warming\n${END} [saturate] E1: concurrency=1024 throughput=${throughput}/s p50=40ms errors={}\n`,
  );
  const lines = [0, 10, 20, 30].map(offset =>
    pipeline(END - 30 + offset, {
      appendDepth: { handedGroups: 4 },
      appendCalls: {
        calls: (calls * offset) / 10,
        streams: (calls * offset * 15) / 10,
      },
      appendClock: {
        calls: (calls * offset) / 10,
        call: (calls * offset * 3000) / 10,
        statement: (calls * offset * 2500) / 10,
      },
      windowMax: { 'wow.snapshot': offset * 100 },
    }),
  );
  writeFileSync(
    join(dir, 'node-1.log'),
    `${lines.join('\n')}\n${'x'.repeat(5)} bench-pipeline {"cut`,
  );
}

test("a sweep's steps end at their lines' stamps, and a node's pipeline lines are read as JSON, a cut one left out", () => {
  assert.deepEqual(
    stepEnds(
      `12 [saturate] E1: concurrency=4 throughput=1/s\n15 [saturate] E3: concurrency=16 x\n`,
    ),
    { 4: 12, 16: 15 },
  );
  const lines = pipelineLines(
    `${pipeline(END, { appendCalls: { calls: 3 } })}\nnoise\nbench-pipeline {"cut`,
  );
  assert.equal(lines.length, 1);
  assert.equal(lines[0].at, END * 1000);
  assert.equal(lines[0].sections.appendCalls.calls, 3);
});

test("the pipeline over a step is its window's calls, streams a call, clock, groups waiting and widest window", () => {
  const lines = [0, 30].map(
    offset =>
      pipelineLines(
        pipeline(END - 30 + offset, {
          appendDepth: { handedGroups: offset ? 6 : 2 },
          appendCalls: { calls: offset * 10, streams: offset * 150 },
          appendClock: {
            calls: offset * 10,
            call: offset * 30_000,
            statement: offset * 25_000,
            commanded: offset * 10,
            command: offset * 20_000,
          },
          snapshotClock: {
            saves: offset * 40,
            snapshots: offset * 40,
            writing: offset ? 2 : 1,
            calls: offset * 4,
            call: offset * 8_000,
            statement: offset * 6_000,
            commanded: offset * 4,
            command: offset * 5_000,
          },
          windowMax: { a: offset, b: 7 },
        }),
      )[0],
  );
  const of = pipelineOf(lines, END, 30);
  assert.equal(of.callsPerSecond, 10);
  assert.equal(of.streamsPerCall, 15);
  assert.equal(of.callMs, 3);
  assert.equal(of.statementMs, 2.5);
  // On MongoDB a clock's statement less the driver's commands: 2.5 ms less 2 ms an append, 1.5 less 1.25 a write.
  assert.equal(of.appendDriverMs, 0.5);
  assert.equal(of.snapshotsPerWrite, 10);
  assert.equal(of.writeCallMs, 2);
  assert.equal(of.writeStatementMs, 1.5);
  assert.equal(of.writeDriverMs, 0.25);
  assert.equal(of.leadersWriting, 1.5);
  assert.equal(of.groupsWaiting, 4);
  assert.equal(of.windowMax, 30);
  assert.deepEqual(pipelineOf(lines.slice(1), END, 30), {});
  assert.deepEqual(pipelineOf(lines, undefined, 30), {});
});

test('an ABBA root gives each step its arms a sweep, the ratio to the first, each own spread and the refused share', () => {
  const root = mkdtempSync(join(tmpdir(), 'bench-abba-'));
  sweep(root, '1-main-postgres', 10_000);
  sweep(root, '1-branch-postgres', 11_000, {
    'http-429-wow.command.busy': 3_300,
  });
  sweep(root, '2-branch-postgres', 11_400);
  sweep(root, '2-main-postgres', 10_200);
  mkdirSync(join(root, 'not-a-session'));
  const sessions = sessionsOf(root);
  assert.equal(sessions.length, 4);
  const rows = rowsOf(sessions, ['main', 'branch']);
  assert.deepEqual(
    rowsOf(sessions).map(row => row.arm),
    ['branch', 'main'],
  );
  assert.deepEqual(
    rows.map(row => [row.arm, row.throughputs]),
    [
      ['main', [10_000, 10_200]],
      ['branch', [11_000, 11_400]],
    ],
  );
  const [main, branch] = rows;
  assert.ok(Number.isNaN(main.ratio));
  assert.equal(branch.ratio, 11_200 / 10_100);
  assert.deepEqual(branch.range, [11_000 / 10_200, 11_400 / 10_000]);
  assert.equal(main.spread, 200 / 10_100);
  assert.equal(branch.refused, 3_300 / (330_000 + 3_300));
  assert.equal(main.refused, 0);
  assert.equal(main.streamsPerCall, 15);
  assert.equal(main.callMs, 3);
  assert.equal(main.statementMs, 2.5);
  assert.equal(main.windowMax, 3_000);
  const table = tableOf(rows);
  assert.match(table, /^## postgres/m);
  assert.match(
    table,
    /\| 1,024 \| branch \| 11,000, 11,400 \| 1\.109 \(1\.078–1\.140\) \| 3\.6% \| 0\.99% \|/,
  );
  assert.match(
    table,
    /\| 1,024 \| main \| 10,000, 10,200 \| - \| 2\.0% \| 0\.00% \| 40\.0 \| 120\.0 \| 10 \| 15\.0 \| 3\.00 \| 2\.50 \| - \| - \| - \| - \| - \| - \| 4\.0 \| 3,000 \| - \|/,
  );
});

test("a step's foreign cores are the host's mean over its window, and a busy one is marked disturbed", () => {
  const samples = hostSamples(
    `epoch\tforeignCores\ttop\n${END - 40}\t9\tx\n${END - 20}\t1\ty\n${END}\t3\tz\nnoise\n`,
  );
  assert.equal(samples.length, 3);
  assert.deepEqual(foreignOf(samples, END, 30), { mean: 2, worst: 3 });
  assert.ok(Number.isNaN(foreignOf(samples, END - 100, 30).mean));
  const root = mkdtempSync(join(tmpdir(), 'bench-abba-'));
  sweep(root, '1-main-postgres', 10_000);
  sweep(root, '1-branch-postgres', 10_100);
  const rows = rowsOf(sessionsOf(root), ['main', 'branch'], samples);
  assert.equal(rows[0].foreign, 2 * DISTURBED_CORES);
  assert.equal(rows[0].foreignWorst, 3);
  assert.match(tableOf(rows), /\| 2\.00, 3\.00 \(disturbed\) \|/);
});

test("a step's store and Kafka cores are their containers' CPU over its window, and the profile says what was pinned", () => {
  const samples = hostSamples(
    'epoch\tforeignCores\ttop\tcontainers\n' +
      `${END - 30}\t0.1\t\twow-e2e-bench-postgres=1000000,wow-e2e-bench-kafka=500000,\n` +
      `${END}\t0.2\t\twow-e2e-bench-postgres=28000000,wow-e2e-bench-kafka=3500000,\n`,
  );
  assert.equal(
    containerCoresOf(samples, 'wow-e2e-bench-postgres', END, 30),
    0.9,
  );
  assert.equal(containerCoresOf(samples, 'wow-e2e-bench-kafka', END, 30), 0.1);
  assert.ok(
    Number.isNaN(containerCoresOf(samples, 'wow-e2e-bench-mongo', END, 30)),
  );
  const root = mkdtempSync(join(tmpdir(), 'bench-abba-'));
  sweep(root, '1-main-postgres', 10_000);
  const rows = rowsOf(sessionsOf(root), ['main'], samples);
  assert.equal(rows[0].storeCores, 0.9);
  assert.match(tableOf(rows), /\| 0\.90 \| 0\.10 \| 0\.15, 0\.20 \|/);
  assert.equal(profileText(''), '');
  assert.match(
    tableOf(rows, 'constrained'),
    /^Profile: constrained\. The store and Kafka each ran on one CPU/,
  );
});
