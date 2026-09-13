/* Copyright [2021-present] Ahoo Wang. Licensed under the Apache License, Version 2.0. */
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import assert from 'node:assert/strict';
const baselinePath = process.argv[2];
if (!baselinePath)
  throw new Error('Pass the baseline packages/view-engine/dist/index.js path');
const baseline = await import(pathToFileURL(resolve(baselinePath)).href);
const candidate = await import('../dist/index.js');
const filters = {
  mode: 'simple',
  root: {
    id: 'all',
    component: { name: 'builtin' },
    operator: 'MATCH_ALL',
    props: {},
  },
};
const definition = {
  id: 'orders',
  title: 'Orders',
  sourceId: 'orders',
  fields: [
    { field: 'id', label: 'ID', type: 'string' },
    { field: 'amount', label: 'Amount', type: 'number', sortable: true },
  ],
  record: { rowKey: 'id', allowedLayouts: ['table'] },
  analysis: { count: true, fields: [] },
};
const rows = Array.from({ length: 100 }, (_, i) => ({
  id: `order-${i}`,
  amount: i + 100,
}));
// Run the actual pre-dashboard engine against a current mixed-format host.
const oldRecord = {
  id: 'legacy',
  definitionId: definition.id,
  title: 'Legacy',
  revision: '1',
  kind: 'record',
  scope: { type: 'personal' },
  config: {
    filters,
    sort: [],
    pagination: { mode: 'paged', size: 10 },
    presentation: {
      layout: 'table',
      table: { columns: [{ id: 'amount', kind: 'field', field: 'amount' }] },
    },
  },
};
const store = new Map();
const hostOptions = {
  serviceKey: 'compatibility',
  scopeKey: 'user',
  store,
  definition: { ...definition, dashboard: true },
  instances: {
    instances: [
      oldRecord,
      {
        id: 'dashboard',
        definitionId: definition.id,
        title: 'Dashboard',
        revision: '1',
        kind: 'dashboard',
        scope: { type: 'personal' },
        config: { schemaVersion: 1, panels: [], filters: [] },
      },
    ],
    defaultInstanceId: 'dashboard',
  },
  resolveSource: () => ({
    paged: async () => ({ total: rows.length, list: rows.slice(0, 10) }),
  }),
};
const oldHost = new candidate.MemoryViewHost(hostOptions);
const modernHost = new candidate.MemoryViewHost({
  ...hostOptions,
  supportedFormats: { record: true, analysis: true, dashboard: 1 },
});
const oldEngine = new baseline.ViewEngine({
  definitionId: definition.id,
  host: oldHost,
});
await oldEngine.load();
assert.deepEqual(oldEngine.getSnapshot().instanceIds, ['legacy']);
assert.equal(oldEngine.getSnapshot().loadError, undefined);
await oldEngine.record('legacy').refresh();
assert.equal(oldEngine.getSnapshot().sessions.legacy.rows.length, 10);
assert.equal(
  (await modernHost.instance.list(definition.id)).defaultInstanceId,
  'dashboard',
);
oldEngine.dispose();
const samples = [];
for (const [version, { ViewEngine }] of [
  ['baseline', baseline],
  ['candidate', candidate],
]) {
  for (const kind of ['record', 'analysis']) {
    for (let run = 0; run < 7; run++) {
      const instance = {
        id: 'view',
        definitionId: definition.id,
        title: 'View',
        revision: '1',
        kind,
        scope: { type: 'personal' },
        config:
          kind === 'record'
            ? {
                filters,
                sort: [],
                pagination: { mode: 'paged', size: 100 },
                presentation: {
                  layout: 'table',
                  table: {
                    columns: [{ id: 'amount', kind: 'field', field: 'amount' }],
                  },
                },
              }
            : {
                filters,
                dimensions: [],
                metrics: [
                  {
                    id: 'n',
                    alias: 'n',
                    title: 'Count',
                    component: { name: 'count' },
                    props: {},
                  },
                ],
                sort: [],
                limit: 100,
                presentation: { layout: 'table', columns: [] },
              },
      };
      const start = performance.now();
      const engine = new ViewEngine({
        definitionId: definition.id,
        definition,
        instances: { instances: [instance], defaultInstanceId: instance.id },
        host: {
          resolveSource: () => ({
            paged: async () => ({ total: rows.length, list: rows }),
            aggregate: async () => [{ n: rows.length }],
          }),
        },
      });
      await engine.load();
      const firstAvailableMs = performance.now() - start;
      const processing = performance.now();
      if (kind === 'record')
        await engine
          .record(instance.id)
          .setSort([{ field: 'amount', direction: 'DESC' }]);
      else await engine.analysis(instance.id).run();
      const processingMs = performance.now() - processing;
      if (engine.getSnapshot().sessions[instance.id].queryStatus !== 'success')
        throw new Error('Benchmark did not produce a successful result');
      engine.dispose();
      samples.push({
        version,
        kind,
        run,
        warmup: run < 2,
        firstAvailableMs,
        processingMs,
      });
    }
  }
}
const median = values =>
  [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const comparisons = ['record', 'analysis'].flatMap(kind =>
  ['firstAvailableMs', 'processingMs'].map(metric => {
    const value = version =>
      median(
        samples
          .filter(
            sample =>
              sample.version === version &&
              sample.kind === kind &&
              !sample.warmup,
          )
          .map(sample => sample[metric]),
      );
    const before = value('baseline'),
      after = value('candidate');
    return {
      kind,
      metric,
      baselineMedian: before,
      candidateMedian: after,
      differenceMs: after - before,
      regression: after > before * 1.1 && after - before > 20,
    };
  }),
);
console.log(
  JSON.stringify(
    {
      node: process.version,
      generatedAt: new Date().toISOString(),
      scope:
        'Headless fixed-input local processing, no server latency; 2 warmups + 5 measured samples per kind/version.',
      legacyClientMixedHost:
        'passed: baseline engine loads and queries projected records while modern default stays dashboard',
      samples,
      comparisons,
    },
    null,
    2,
  ),
);
if (comparisons.some(comparison => comparison.regression)) process.exitCode = 1;
