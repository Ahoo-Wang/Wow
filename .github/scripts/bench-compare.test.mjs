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
  cachedStates,
  median,
  readSessions,
  renderTables,
  runMetrics,
  sweepMetrics,
} from './bench-compare.mjs';

function result({
  ok = 1000,
  cpuSeconds = 0.5,
  allocatedBytes = 102_400_000,
  fetched = 3000,
} = {}) {
  return {
    results: {
      ok,
      failed: 0,
      throughput: ok / 60,
      latencyMs: { p50: 1.5, p99: 9 },
    },
    resources: {
      seconds: 60,
      processes: { 'node-1': { cpuSeconds, allocatedBytes } },
      containers: { 'wow-e2e-bench-postgres': { cpuPercentAverage: 50 } },
      stores: {
        postgres: {
          before: { tup_fetched: 0, xact_commit: 0 },
          after: { tup_fetched: fetched, xact_commit: ok },
        },
      },
    },
  };
}

test('a run gives its costs and its store counters per operation', () => {
  const metrics = runMetrics(result());
  assert.equal(metrics['node-1 CPU ms'], 0.5);
  assert.equal(metrics['node-1 KB allocated'], 100);
  assert.equal(metrics['postgres CPU ms'], 30);
  assert.equal(metrics['postgres tup_fetched'], 3);
  assert.equal(metrics['postgres xact_commit'], 1);
});

test('a sweep whose peak is its top step is a lower bound', () => {
  const steps = [
    { concurrency: 1, throughput: 100 },
    { concurrency: 4, throughput: 300 },
  ];
  assert.deepEqual(
    sweepMetrics({
      saturationThroughput: 300,
      saturationConcurrency: 4,
      steps,
    }),
    {
      peak: 300,
      'peak at': 4,
      'peak is the top step': 1,
    },
  );
});

test('a heap histogram gives the cached states', () => {
  const histogram = [
    '   6:         95786        4597728  me.ahoo.wow.kernel.CachedState',
    '   8:         95786        3831440  me.ahoo.wow.kernel.RecentRequests',
  ].join('\n');
  assert.equal(cachedStates(histogram), 95786);
  assert.equal(cachedStates('nothing'), undefined);
});

test('the median of the rounds is the middle, or the mean of the two middles', () => {
  assert.equal(median([3, 1, 2]), 2);
  assert.equal(median([4, 1, 2, 3]), 2.5);
  assert.equal(median([]), undefined);
});

test('the tables give every session by arm and round, and each arm against the first', () => {
  const root = mkdtempSync(join(tmpdir(), 'bench-compare-'));
  const sessions = {
    '1-A-postgres': 0.6,
    '2-B-postgres': 0.45,
    '3-B-postgres': 0.55,
    '4-A-postgres': 0.4,
  };
  for (const [name, cpuSeconds] of Object.entries(sessions)) {
    mkdirSync(join(root, name, 'e1'), { recursive: true });
    writeFileSync(
      join(root, name, 'e1', 'result.json'),
      JSON.stringify(result({ cpuSeconds })),
    );
    writeFileSync(
      join(root, name, 'e1.hist'),
      ' 1: 100 4800 me.ahoo.wow.kernel.CachedState\n',
    );
  }
  mkdirSync(join(root, 'not-a-session'));
  const read = readSessions(root);
  assert.equal(read.length, 4);
  const tables = renderTables(read);
  assert.match(tables, /### postgres, e1/);
  assert.match(
    tables,
    /\| \| A \(1\) \| A \(4\) \| B \(2\) \| B \(3\) \| B\/A \|/,
  );
  // Medians: A 0.5 ms, B 0.5 ms.
  assert.match(
    tables,
    /\| node-1 CPU ms \| 0\.600 \| 0\.400 \| 0\.450 \| 0\.550 \| 1\.000 \|/,
  );
  assert.match(tables, /\| failed \| 0 \| 0 \| 0 \| 0 \| {2}\|/);
  assert.match(
    tables,
    /\| states cached \| 100 \| 100 \| 100 \| 100 \| 1\.000 \|/,
  );
});
