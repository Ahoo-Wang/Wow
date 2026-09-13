/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { expect, it, vi } from 'vitest';
import {
  AggregationFunction as Fn,
  AggregationGroupType as Group,
  AggregationDateUnit as Unit,
  FilterOperator,
  SortDirection,
} from '@ahoo-wang/fetcher-wow';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { createFilterConfiguration } from '../src/filter/filterConfiguration.js';
import type {
  AnalysisViewConfig,
  AnalysisCompileContext,
} from '../src/analysis/analysisModel.js';
import type {
  ViewDefinition,
  AnalysisViewInstance,
} from '../src/contracts/viewModel.js';
const context: AnalysisCompileContext = {
  fields: [
    { field: 'state', label: 'State', type: 'string' },
    { field: 'amount', label: 'Amount', type: 'number' },
    { field: 'created', label: 'Created', type: 'datetime' },
  ],
  capability: {
    count: true,
    fields: [
      { field: 'state', groups: [Group.TERMS], functions: [] },
      { field: 'amount', groups: [Group.HISTOGRAM], functions: [Fn.SUM] },
      {
        field: 'created',
        groups: [Group.DATE_HISTOGRAM],
        functions: [],
        dateUnits: [Unit.MONTH],
      },
    ],
  },
  timeZone: 'Asia/Shanghai',
};
const config: AnalysisViewConfig = {
  filters: createFilterConfiguration({
    id: 'all',
    component: { name: 'builtin' },
    operator: FilterOperator.MATCH_ALL,
    props: {},
  }),
  dimensions: [],
  metrics: [
    {
      id: 'count',
      component: { name: 'count' },
      alias: 'orders',
      title: 'Orders',
      props: {},
    },
    {
      id: 'sum',
      component: { name: 'numeric' },
      field: 'amount',
      alias: 'total',
      title: 'Total',
      props: { function: Fn.SUM },
    },
  ],
  sort: [],
  limit: 100,
  presentation: { layout: 'table', columns: [] },
};

const definition: ViewDefinition = {
  id: 'orders',
  title: 'Orders',
  sourceId: 'orders',
  fields: context.fields,
  analysis: context.capability,
};
const instance: AnalysisViewInstance = {
  id: 'analysis',
  definitionId: 'orders',
  kind: 'analysis',
  title: 'Totals',
  scope: { type: 'personal' },
  revision: '1',
  config,
};
function setup(instances = [instance]) {
  const aggregate = vi.fn().mockResolvedValue([{ orders: 2, total: 30 }]);
  const save = vi.fn(async value => ({ ...value, revision: '2' }));
  const engine = new ViewEngine({
    definitionId: 'orders',
    definition,
    instances: { instances, defaultInstanceId: 'analysis' },
    host: {
      resolveSource: () => ({ aggregate }),
      instance: { save },
      permission: {
        getInstance: () => ({
          save: true,
          saveAsPersonal: true,
          saveAsShared: false,
        }),
      },
    },
  });
  return { engine, aggregate, save };
}
it('saves valid count/sum working configuration without running and restores the confirmed baseline', async () => {
  const { engine, aggregate, save } = setup();
  await engine.load();
  expect(aggregate).toHaveBeenCalledTimes(1);
  engine.analysis('analysis').edit(value => ({ ...value, limit: 50 }));
  await engine.save('analysis');
  expect(save.mock.calls[0][0].config.limit).toBe(50);
  expect(aggregate).toHaveBeenCalledTimes(1);
  engine.analysis('analysis').edit(value => ({ ...value, limit: 60 }));
  await engine.restore('analysis');
  expect(engine.getSnapshot().sessions.analysis.instance.config.limit).toBe(50);
  await engine.analysis('analysis').run();
  const session = engine.getSnapshot().sessions.analysis;
  expect(session.kind).toBe('analysis');
  if (session.kind === 'analysis')
    expect(session.result?.rows).toEqual([{ orders: 2, total: 30 }]);
  engine.dispose();
});
it('keeps invalid configuration repairable without blocking a healthy sibling', async () => {
  const broken = structuredClone(instance);
  broken.id = 'broken';
  broken.config.metrics[0].component.name = 'missing';
  const { engine } = setup([instance, broken]);
  await engine.load();
  await engine.selectInstance('broken');
  const bad = engine.getSnapshot().sessions.broken;
  expect(bad.kind).toBe('analysis');
  if (bad.kind === 'analysis') expect(bad.validation.length).toBeGreaterThan(0);
  await expect(engine.save('broken')).rejects.toThrow();
  await engine.selectInstance('analysis');
  await engine.analysis('analysis').run();
  expect(engine.getSnapshot().sessions.analysis.queryStatus).toBe('success');
  engine.dispose();
});
it('ignores late results after navigation and keeps submitted schema independent of edits', async () => {
  const other = { ...instance, id: 'other' };
  const { engine, aggregate } = setup([instance, other]);
  await engine.load();
  let resolve!: (rows: unknown[]) => void;
  aggregate.mockImplementationOnce(
    () =>
      new Promise(done => {
        resolve = done;
      }),
  );
  const pending = engine.analysis('analysis').run();
  await Promise.resolve();
  await Promise.resolve();
  engine.analysis('analysis').edit(value => ({ ...value, metrics: [] }));
  await engine.selectInstance('other');
  resolve([{ orders: 2, total: 30 }]);
  await pending;
  const session = engine.getSnapshot().sessions.analysis;
  if (session.kind === 'analysis')
    expect(session.result?.config.limit).toBe(100);
  engine.dispose();
});

it('uses the shared conflict decisions for analysis documents without running on adoption or overwrite', async () => {
  let remote = structuredClone(instance);
  const aggregate = vi.fn().mockResolvedValue([{ orders: 2, total: 30 }]);
  const save = vi.fn(async (value: AnalysisViewInstance) => {
    remote = { ...value, revision: 'r4' };
    return remote;
  });
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host: {
      resolveSource: () => ({ aggregate }),
      instance: { load: async () => remote, save: save as never },
      permission: {
        getInstance: () => ({
          save: true,
          saveAsPersonal: true,
          saveAsShared: false,
        }),
      },
    },
  });
  await engine.load();
  engine.setTitle('Local');
  remote = {
    ...remote,
    revision: 'r2',
    config: { ...remote.config, limit: 20 },
  };
  await engine.reloadInstance(instance.id);
  const review = engine.getSnapshot().sessions[instance.id].conflict!;
  expect(review).toBeDefined();
  await engine.useRemoteInstance(review, instance.id);
  expect(engine.getSnapshot().sessions[instance.id].baseline.revision).toBe(
    'r2',
  );
  expect(aggregate).toHaveBeenCalledOnce();
  engine.analysis(instance.id).edit(value => ({ ...value, limit: 50 }));
  remote = {
    ...remote,
    revision: 'r3',
    config: { ...remote.config, limit: 80 },
  };
  await engine.reloadInstance(instance.id);
  const commands = engine.analysis(instance.id);
  commands.edit(value => ({
    ...value,
    filters: {
      ...value.filters,
      root: { ...value.filters.root, id: 'new-filter' },
    },
  }));
  commands.setFilterValidity(false);
  const edited = engine.getSnapshot().sessions[instance.id];
  expect(edited.conflict?.filterDraft).toEqual(edited.instance.config.filters);
  expect(edited.conflict?.filterValid).toBe(false);
  commands.setFilterValidity(true);
  expect(engine.getSnapshot().sessions[instance.id].conflict?.filterValid).toBe(
    true,
  );
  await engine.overwriteInstance(
    engine.getSnapshot().sessions[instance.id].conflict!,
    instance.id,
  );
  expect(save.mock.calls[0][0].revision).toBe('r3');
  expect(save.mock.calls[0][0].config.limit).toBe(50);
  expect(aggregate).toHaveBeenCalledOnce();
  engine.dispose();
});

it('evicts results across record and analysis while retaining both working documents', async () => {
  const record = {
    id: 'records',
    definitionId: definition.id,
    title: 'Records',
    revision: 'r1',
    scope: { type: 'personal' as const },
    kind: 'record' as const,
    config: {
      filters: config.filters,
      sort: [],
      pagination: { mode: 'paged' as const, size: 10 },
      presentation: {
        layout: 'table' as const,
        table: {
          columns: [{ id: 'amount', kind: 'field' as const, field: 'amount' }],
        },
      },
    },
  };
  const aggregate = vi.fn().mockResolvedValue([{ orders: 2, total: 30 }]);
  const paged = vi
    .fn()
    .mockResolvedValue({ list: [{ id: 'one', amount: 30 }], total: 1 });
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      record: { rowKey: 'id', allowedLayouts: ['table'] },
    },
    instances: { instances: [record, instance], defaultInstanceId: record.id },
    limits: { maxRetainedResults: 1 },
    host: { resolveSource: () => ({ paged, aggregate }) },
  });
  await engine.load();
  engine.setTitle('Unsaved record', record.id);
  const original = engine.getSnapshot().sessions.records;
  if (original.kind === 'record') expect(original.result?.page).toBe(1);
  await engine.selectInstance(instance.id);
  const evicted = engine.getSnapshot().sessions.records;
  expect(evicted.result).toBeNull();
  expect(evicted.instance.title).toBe('Unsaved record');
  expect(evicted.dirty).toBe(true);
  if (evicted.kind === 'record') expect(evicted.rows).toEqual([]);
  await engine.selectInstance(record.id);
  expect(engine.getSnapshot().sessions.analysis.result).toBeNull();
  await engine.selectInstance(instance.id);
  expect(aggregate).toHaveBeenCalledOnce();
  engine.dispose();
});

it('deduplicates the same pending plan and lets changed configuration replace it', async () => {
  const { engine, aggregate } = setup();
  await engine.load();
  let finish!: (rows: unknown[]) => void;
  aggregate.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  const first = engine.analysis(instance.id).run();
  await vi.waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));
  await engine.analysis(instance.id).run();
  expect(aggregate).toHaveBeenCalledTimes(2);
  engine.analysis(instance.id).edit(value => ({ ...value, limit: 50 }));
  await engine.analysis(instance.id).run();
  finish([{ orders: 999, total: 999 }]);
  await first;
  const session = engine.getSnapshot().sessions[instance.id];
  if (session.kind === 'analysis') {
    expect(session.pendingQuery).toBeNull();
    expect(session.result?.config.limit).toBe(50);
    expect(session.result?.rows).toEqual([{ orders: 2, total: 30 }]);
  }
  engine.dispose();
});

it('preserves invalid analysis editor input across reloads without a JSON change', async () => {
  const aggregate = vi.fn().mockResolvedValue([{ orders: 2, total: 30 }]);
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host: {
      resolveSource: () => ({ aggregate }),
      instance: { load: async () => ({ ...instance, revision: '2' }) },
    },
  });
  try {
    await engine.load();
    engine.analysis(instance.id).setFilterValidity(false);
    await engine.reloadInstance(instance.id);
    expect(engine.getSnapshot().sessions[instance.id].filterValid).toBe(false);
    await expect(engine.analysis(instance.id).run()).rejects.toThrow();
    expect(aggregate).toHaveBeenCalledOnce();
  } finally {
    engine.dispose();
  }
});

it('does not clear an invalid extension draft when restoring configuration', async () => {
  const { engine, aggregate } = setup();
  try {
    await engine.load();
    const commands = engine.analysis(instance.id);
    commands.edit(value => ({ ...value, limit: 50 }));
    commands.setFilterValidity(false);
    commands.restore();
    expect(
      engine.getSnapshot().sessions[instance.id].instance.config.limit,
    ).toBe(100);
    expect(engine.getSnapshot().sessions[instance.id].filterValid).toBe(false);
    await expect(commands.run()).rejects.toThrow('筛选输入无效');
    expect(aggregate).toHaveBeenCalledOnce();
    commands.setFilterValidity(true);
    await commands.run();
    expect(aggregate).toHaveBeenCalledTimes(2);
  } finally {
    engine.dispose();
  }
});

it('retries a first analysis cancelled by navigation when revisiting its instance', async () => {
  let finish: ((rows: { orders: number; total: number }[]) => void) | undefined;
  const aggregate = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve;
        }),
    )
    .mockResolvedValue([{ orders: 2, total: 30 }]);
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: {
      instances: [instance, { ...instance, id: 'other' }],
      defaultInstanceId: instance.id,
    },
    host: { resolveSource: () => ({ aggregate }) },
  });
  try {
    const loading = engine.load();
    await vi.waitFor(() => expect(aggregate).toHaveBeenCalledOnce());
    await engine.selectInstance('other');
    await loading;
    expect(engine.getSnapshot().sessions[instance.id].result).toBeNull();
    await engine.selectInstance(instance.id);
    expect(aggregate).toHaveBeenCalledTimes(3);
    expect(engine.getSnapshot().sessions[instance.id].result).not.toBeNull();
  } finally {
    finish?.([]);
    engine.dispose();
  }
});

it('refreshes a clean analysis instance after an explicit reload', async () => {
  const aggregate = vi
    .fn()
    .mockResolvedValueOnce([{ orders: 2, total: 30 }])
    .mockResolvedValue([{ orders: 3, total: 40 }]);
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host: {
      resolveSource: () => ({ aggregate }),
      instance: { load: async () => ({ ...instance, revision: '2' }) },
    },
  });
  try {
    await engine.load();
    await engine.reloadInstance(instance.id);
    expect(aggregate).toHaveBeenCalledTimes(2);
    await vi.waitFor(() =>
      expect(engine.getSnapshot().sessions[instance.id].queryStatus).toBe(
        'success',
      ),
    );
    expect(
      engine.getSnapshot().sessions[instance.id].result?.rows[0].orders,
    ).toBe(3);
  } finally {
    engine.dispose();
  }
});

it('rejects a reentrant edit without changing title or configuration', async () => {
  const { engine } = setup();
  try {
    await engine.load();
    expect(() =>
      engine.analysis(instance.id).edit(value => {
        engine.setTitle('Intervening title');
        return { ...value, limit: 5 };
      }),
    ).toThrow('重入');
    expect(engine.getSnapshot().sessions[instance.id].instance.title).toBe(
      'Totals',
    );
    expect(
      engine.getSnapshot().sessions[instance.id].instance.config.limit,
    ).toBe(100);
  } finally {
    engine.dispose();
  }
});

it('reports invalid analysis without calling the data source', async () => {
  const aggregate = vi.fn(async () => [{ orders: 1, total: 2 }]);
  const diagnostic = vi.fn();
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host: { resolveSource: () => ({ aggregate }) },
    onDiagnostic: diagnostic,
  });
  try {
    await engine.load();
    engine.analysis(instance.id).edit(value => ({ ...value, metrics: [] }));
    await expect(engine.analysis(instance.id).run()).rejects.toThrow();
    expect(aggregate).toHaveBeenCalledOnce();
    expect(diagnostic).toHaveBeenLastCalledWith(
      expect.objectContaining({ phase: 'failed', errorCode: 'INVALID_CONFIG' }),
    );
  } finally {
    engine.dispose();
  }
});

it('cancels a run while resolving its source without invoking aggregate', async () => {
  const aggregate = vi.fn(async () => [{ orders: 1, total: 2 }]);
  let complete!: (value: { aggregate: typeof aggregate }) => void;
  let delayed = false;
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host: {
      resolveSource: () =>
        delayed
          ? new Promise(resolve => {
              complete = resolve;
            })
          : { aggregate },
    },
  });
  try {
    await engine.load();
    delayed = true;
    const running = engine.analysis(instance.id).run();
    await vi.waitFor(() => expect(complete).toBeTypeOf('function'));
    engine.dispose();
    complete({ aggregate });
    await running;
    expect(aggregate).toHaveBeenCalledOnce();
    expect(
      engine.getSnapshot().sessions[instance.id].result?.rows[0].orders,
    ).toBe(1);
  } finally {
    engine.dispose();
  }
});

it('validates query-producing sort changes before publishing and preserves invalid drafts', async () => {
  const grouped = {
    ...instance,
    config: {
      ...config,
      dimensions: [
        {
          id: 'state',
          alias: 'state',
          title: 'State',
          field: 'state',
          component: { name: 'terms' },
          props: {},
        },
      ],
    },
  };
  const aggregate = vi
    .fn()
    .mockResolvedValue([{ state: 'ready', orders: 2, total: 30 }]);
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      analysis: { ...definition.analysis!, limits: { maxSort: 1 } },
    },
    instances: { instances: [grouped], defaultInstanceId: grouped.id },
    host: { resolveSource: () => ({ aggregate }) },
  });
  try {
    await engine.load();
    const command = engine.analysis(grouped.id);
    const before = engine.getSnapshot().sessions[grouped.id].instance;
    await expect(
      command.setSort([{ alias: 'orders', direction: SortDirection.ASC }]),
    ).rejects.toThrow('有效排序数量超限');
    expect(engine.getSnapshot().sessions[grouped.id].instance).toBe(before);
    expect(aggregate).toHaveBeenCalledOnce();
    await command.setSort([{ alias: 'state', direction: SortDirection.DESC }]);
    expect(aggregate).toHaveBeenCalledTimes(2);
    command.setFilterValidity(false);
    const draft = engine.getSnapshot().sessions[grouped.id].instance;
    await command.setSort([]);
    expect(engine.getSnapshot().sessions[grouped.id].instance).toBe(draft);
    expect(aggregate).toHaveBeenCalledTimes(2);
  } finally {
    engine.dispose();
  }
});

it('reports admission before completion and isolates accepted B from newer draft C', async () => {
  let finish!: (rows: { orders: number; total: number }[]) => void;
  const { engine, aggregate } = setup();
  await engine.load();
  aggregate.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  const commands = engine.analysis(instance.id);
  commands.edit(value => ({ ...value, limit: 50 }));
  const execution = commands.start();
  expect(execution.accepted).toBe(true);
  expect(engine.getSnapshot().sessions[instance.id].queryStatus).toBe(
    'loading',
  );
  commands.edit(value => ({ ...value, limit: 25 }));
  await vi.waitFor(() => expect(finish).toBeDefined());
  finish([{ orders: 3, total: 60 }]);
  await execution.completion;
  const session = engine.getSnapshot().sessions[instance.id];
  expect(session.instance.config.limit).toBe(25);
  expect(session.result?.config.limit).toBe(50);
  const before = session.instance;
  await commands.setSort([]);
  expect(engine.getSnapshot().sessions[instance.id].instance).toBe(before);
  commands.setFilterValidity(false);
  expect(() => commands.start()).toThrow('筛选输入无效');
  engine.dispose();
});

it('does not implicitly retry a failed manual first run when revisiting the instance', async () => {
  const { engine, aggregate } = setup([
    { ...instance, config: { ...config, metrics: [] } },
    { ...instance, id: 'other' },
  ]);
  try {
    await engine.load();
    expect(aggregate).not.toHaveBeenCalled();
    engine.analysis('analysis').edit(() => config);
    aggregate.mockRejectedValueOnce(new Error('offline'));
    await expect(engine.analysis('analysis').run()).rejects.toThrow('offline');
    await engine.selectInstance('other');
    const count = aggregate.mock.calls.length;
    await engine.selectInstance('analysis');
    expect(aggregate).toHaveBeenCalledTimes(count);
    expect(engine.getSnapshot().sessions.analysis.queryStatus).toBe('error');
  } finally {
    engine.dispose();
  }
});

it('rejects clearSort from an editor replaced by remote conflict resolution', async () => {
  const remote = {
    ...instance,
    revision: '2',
    config: {
      ...config,
      sort: [{ alias: 'orders', direction: SortDirection.ASC }],
    },
  };
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host: {
      resolveSource: () => ({
        aggregate: async () => [{ orders: 2, total: 30 }],
      }),
      instance: { load: async () => remote },
    },
  });
  try {
    await engine.load();
    const old = engine.analysis(instance.id);
    old.setFilterValidity(false);
    await engine.reloadInstance(instance.id);
    await engine.useRemoteInstance(
      engine.getSnapshot().sessions.analysis.conflict!,
      instance.id,
    );
    expect(() => old.clearSort()).toThrow('编辑会话已重置');
    engine.setTitle('New local edit', instance.id);
    expect(() => old.restore()).toThrow('编辑会话已重置');
    expect(engine.getSnapshot().sessions.analysis.instance.title).toBe(
      'New local edit',
    );
    expect(engine.getSnapshot().sessions.analysis.instance.config.sort).toEqual(
      remote.config.sort,
    );
  } finally {
    engine.dispose();
  }
});

it('retries initial opening after a shared query budget refuses admission', async () => {
  let finish!: (rows: { orders: number; total: number }[]) => void;
  const aggregate = vi.fn().mockResolvedValue([{ orders: 2, total: 30 }]);
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    limits: { maxConcurrentQueries: 1 },
    instances: {
      instances: [
        instance,
        { ...instance, id: 'other' },
        { ...instance, id: 'busy' },
      ],
      defaultInstanceId: instance.id,
    },
    host: { resolveSource: () => ({ aggregate }) },
  });
  try {
    await engine.load();
    aggregate.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve;
        }),
    );
    const pending = engine.analysis('busy').run();
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'));
    await expect(engine.selectInstance('other')).rejects.toMatchObject({
      code: 'BUSY',
    });
    expect(engine.getSnapshot().sessions.other.queryStatus).toBe('error');
    expect(engine.getSnapshot().sessions.other.queryError).toContain('并发');
    finish([{ orders: 2, total: 30 }]);
    await pending;
    await engine.selectInstance(instance.id);
    await engine.selectInstance('other');
    expect(engine.getSnapshot().sessions.other.queryStatus).toBe('success');
  } finally {
    engine.dispose();
  }
});
