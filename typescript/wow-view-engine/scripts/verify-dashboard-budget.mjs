/* Copyright [2021-present] Ahoo Wang. Licensed under the Apache License, Version 2.0. */
import { ViewEngine } from '../dist/index.js';
const encoder = new TextEncoder();
const results = [];
const root = {
  id: 'dashboards',
  title: 'Dashboards',
  fields: [],
  dashboard: true,
};
const child = {
  id: 'records',
  title: 'Records',
  sourceId: 'rows',
  fields: [{ field: 'tag', label: 'Tag', type: 'string' }],
  record: { rowKey: 'id', allowedLayouts: ['table'] },
};
const filters = value => ({
  mode: 'simple',
  root: {
    id: 'tag',
    component: { name: 'builtin' },
    operator: 'EQ',
    field: 'tag',
    props: { value },
  },
});
for (const count of [1, 6, 20]) {
  for (const repeated of [true, false]) {
    const records = new Map();
    const instances = Array.from({ length: count }, (_, d) => ({
      id: `dashboard-${d}`,
      definitionId: root.id,
      title: `Dashboard ${d}`,
      kind: 'dashboard',
      scope: { type: 'personal' },
      revision: '1',
      config: {
        schemaVersion: 1,
        filters: [],
        panels: Array.from({ length: 12 }, (_, p) => {
          const id = repeated ? 'record' : `record-${d}-${p}`;
          records.set(id, {
            id,
            definitionId: child.id,
            title: id,
            kind: 'record',
            scope: { type: 'personal' },
            revision: String(p + 1),
            config: {
              filters: filters('x'.repeat(261865)),
              sort: [],
              pagination: { mode: 'paged', size: 10 },
              presentation: {
                layout: 'table',
                table: {
                  columns: [{ id: 'tag', kind: 'field', field: 'tag' }],
                },
              },
            },
          });
          return {
            kind: 'view',
            id: `panel-${p}`,
            instanceId: id,
            layout: { x: 0, y: 0, w: 6, h: 18 },
          };
        }),
      },
    }));
    global.gc?.();
    const before = process.memoryUsage().heapUsed;
    const engine = new ViewEngine({
      definitionId: root.id,
      definition: root,
      instances: { instances, defaultInstanceId: null },
      host: {
        instance: { load: async id => records.get(id) },
        definition: { load: async () => child },
        resolveSource: () => ({ paged: async () => ({ total: 0, list: [] }) }),
      },
    });
    await engine.load();
    for (const instance of instances) {
      await engine.selectInstance(instance.id);
      const runtime = engine.dashboard(instance.id);
      await runtime.resume();
      const started = Date.now();
      while (
        Object.values(runtime.getSnapshot().panels).some(
          panel => panel.loading || !panel.position,
        )
      ) {
        if (Date.now() - started > 30000)
          throw new Error('Fixture did not become ready');
        await new Promise(resolve => setTimeout(resolve, 1));
      }
    }
    const metadata = new Set();
    let retainedReferences = 0;
    // Inspect retained runtime state rather than counting only visible panel snapshots.
    for (const runtime of Reflect.get(engine, 'dashboards').values()) {
      metadata.add(Reflect.get(runtime, 'config'));
      metadata.add(Reflect.get(runtime, 'applied'));
      for (const panel of Reflect.get(runtime, 'panels').values()) {
        if (panel.retained) {
          metadata.add(panel.retained);
          retainedReferences++;
        }
      }
    }
    const metadataJSONBytes = [...metadata].reduce(
      (sum, item) => sum + encoder.encode(JSON.stringify(item)).byteLength,
      0,
    );
    global.gc?.();
    const heapDeltaBytes = process.memoryUsage().heapUsed - before;
    engine.dispose();
    results.push({
      dashboards: count,
      panelsPerDashboard: 12,
      repeatedReferences: repeated,
      retainedReferences,
      childConfigBytes: encoder.encode(
        JSON.stringify(records.values().next().value.config),
      ).byteLength,
      metadataJSONBytes,
      heapDeltaBytes,
      remainingRuntimeCount: Reflect.get(engine, 'dashboards').size,
    });
  }
}
const worst = Math.max(...results.map(result => result.metadataJSONBytes));
console.log(
  JSON.stringify(
    {
      node: process.version,
      generatedAt: new Date().toISOString(),
      measurement:
        'Runtime retained config/reference JSON bytes; GC heap delta is diagnostic, not a leak proof.',
      results,
      candidateMetadataBudgetBytes: Math.ceil(worst / 1048576) * 1048576 * 2,
    },
    null,
    2,
  ),
);
