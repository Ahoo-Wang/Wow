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

import { describe, expect, it, vi } from 'vitest';
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  isViewCommandError,
  MemoryViewStore,
  searchFieldOf,
  ViewEngine,
  type DataViewDefinition,
  type Issue,
  type RuntimeLimits,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DESCRIPTOR_MAX_AGE_MS } from '../src/capabilities/index.js';
import {
  analysisConfig,
  dashboardConfig,
  deferred,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';
import {
  describedField,
  notModified,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

/** The compensation console's 「搜索错误」 on the orders definition (G15). */
function searchable(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      { name: 'state.error.errorMsg', label: 'Error', kind: 'string' },
      {
        name: 'keyword',
        label: 'Search errors',
        kind: 'search',
        searchFields: ['state.error.errorMsg'],
        searchMode: 'PHRASE',
      },
    ],
  });
}

interface Harness {
  engine: ViewEngine;
  source: ViewSource;
  describe: ReturnType<typeof vi.fn>;
  issues: Issue[];
  clock: ReturnType<typeof testEnvironment>;
}

function harness(
  options: {
    describe?: ViewSource['describe'] | null;
    definitions?: DataViewDefinition[];
    instances?: ViewInstance[];
    limits?: Partial<RuntimeLimits>;
    descriptor?: QueryModelDescriptor;
  } = {},
): Harness {
  const descriptor = options.descriptor ?? ordersDescriptor();
  const describe = vi.fn(
    options.describe === null
      ? undefined
      : (options.describe ?? (() => Promise.resolve(read(descriptor)))),
  );
  const source = testSource(
    options.describe === null ? {} : { describe: describe as never },
  );
  const issues: Issue[] = [];
  const clock = testEnvironment();
  const engine = new ViewEngine({
    definitions: [
      ...(options.definitions ?? [ordersDefinition()]),
      overviewDefinition(),
    ],
    store: new MemoryViewStore({ instances: options.instances ?? [] }),
    resolveSource: () => source,
    environment: clock.environment,
    ...(options.limits ? { limits: options.limits } : {}),
    onIssue: found => issues.push(found),
  });
  return { engine, source, describe, issues, clock };
}

describe('a source without a descriptor', () => {
  it('runs every view on the definition as declared and the limits as given', async () => {
    const declared = ordersDefinition();
    const { engine, issues } = harness({
      describe: null,
      definitions: [declared],
    });

    const runtime = await engine.open('system:orders:all');

    expect(runtime.definition).toBe(declared);
    expect(runtime.limits).toBe(engine.limits);
    expect(issues).toEqual([]);
  });
});

describe('a source with a descriptor', () => {
  it('reads it before the first query, which goes out narrowed', async () => {
    const answer = deferred<ReturnType<typeof read>>();
    const base = ordersDescriptor();
    const { engine, source } = harness({
      definitions: [searchable()],
      describe: () => answer.promise,
    });

    const opening = engine.open('system:orders:all');
    await nextTask();
    expect(source.paged).not.toHaveBeenCalled();

    // A MongoDB store without a text index: no full-text search (G15).
    answer.resolve(read({ ...base, record: { ...base.record } }));
    const runtime = await opening;

    expect(source.paged).toHaveBeenCalledTimes(1);
    expect(searchFieldOf(runtime.fields)).toBeNull();
    expect(runtime.definition).not.toBe(engine.definitions.get('orders'));
  });

  it('keeps the search box where the source searches as the definition does', async () => {
    const base = ordersDescriptor();
    const { engine } = harness({
      definitions: [searchable()],
      descriptor: {
        ...base,
        fields: [...base.fields, describedField('state.error.errorMsg')],
        record: {
          ...base.record,
          search: {
            modes: ['TERMS', 'PHRASE'] as never,
            fields: ['state.error.errorMsg'],
          },
        },
      },
    });

    const runtime = await engine.open('system:orders:all');

    expect(searchFieldOf(runtime.fields)?.name).toBe('keyword');
  });

  it('reads one source once, however many views and panels run on it', async () => {
    const { engine, describe } = harness({
      instances: [
        pending,
        {
          id: 'board',
          definitionId: 'overview',
          title: 'Board',
          scope: 'shared',
          revision: 'r1',
          config: dashboardConfig({
            panels: [
              panel({ id: 'a' }),
              panel({ id: 'b', layout: { x: 6, y: 0, w: 6, h: 4 } }),
            ],
          }),
        },
      ],
    });

    await Promise.all([
      engine.open('system:orders:all'),
      engine.open('pending'),
      engine.open('board'),
    ]);

    expect(describe).toHaveBeenCalledTimes(1);
  });

  it('reports what narrowing found once per definition and version', async () => {
    const { engine, issues } = harness({
      descriptor: {
        ...ordersDescriptor(),
        fields: ordersDescriptor().fields.map(field =>
          field.path === 'status'
            ? describedField('status', { filter: { operators: [] } })
            : field,
        ),
      },
    });

    await engine.open('system:orders:all');
    await engine.open('system:orders:all');

    expect(issues).toEqual([
      {
        code: 'capability.field.unfilterable',
        severity: 'warning',
        path: ['fields', 2],
        params: { field: 'status', definition: 'orders' },
      },
    ]);
  });

  it('takes the source budgets from the descriptor, lowered only by the host', async () => {
    const base = ordersDescriptor();
    const descriptor = {
      ...base,
      limits: { ...base.limits, maxPageSize: 500 },
    };

    const raised = harness({ descriptor });
    const lowered = harness({ descriptor, limits: { maxPageSize: 50 } });

    expect(
      (await raised.engine.open('system:orders:all')).limits.maxPageSize,
    ).toBe(500);
    expect(
      (await lowered.engine.open('system:orders:all')).limits.maxPageSize,
    ).toBe(50);
  });

  it('admits an analysis limit the server admits without the definition declaring one', async () => {
    const base = ordersDescriptor();
    const big: ViewInstance = {
      id: 'big',
      definitionId: 'orders',
      title: 'Big',
      scope: 'personal',
      revision: 'r1',
      config: analysisConfig({ limit: 2_000 }),
    };
    const described = harness({
      instances: [big],
      descriptor: {
        ...base,
        limits: {
          ...base.limits,
          aggregation: { ...base.limits.aggregation, maxLimit: 5_000 },
        },
      },
    });
    const bare = harness({ instances: [big], describe: null });

    const runtime = await described.engine.open('big');
    await nextTask();

    expect(runtime.getSnapshot().issues).toEqual([]);
    expect(described.source.aggregate).toHaveBeenCalled();
    // Without one, the engine's own default still bounds it (D42).
    const plain = await bare.engine.open('big');
    expect(plain.getSnapshot().issues.map(found => found.code)).toContain(
      'analysis.limit.out-of-range',
    );
  });

  it('holds a saved view that uses what the source no longer admits, and sends nothing', async () => {
    const sorted: ViewInstance = {
      id: 'sorted',
      definitionId: 'orders',
      title: 'By amount',
      scope: 'personal',
      revision: 'r1',
      config: recordConfig({ sort: [{ field: 'amount', direction: 'DESC' }] }),
    };
    const { engine, source } = harness({
      instances: [sorted],
      descriptor: {
        ...ordersDescriptor(),
        fields: ordersDescriptor().fields.map(field =>
          field.path === 'amount'
            ? describedField('amount', {
                sort: { paged: false, cursor: false },
              })
            : field,
        ),
      },
    });

    const runtime = await engine.open('sorted');

    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'record.sort.not-sortable',
    );
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('refuses to open a definition the descriptor contradicts', async () => {
    const base = ordersDescriptor();
    const { engine, issues } = harness({
      descriptor: {
        ...base,
        record: { ...base.record, paging: ['CURSOR'] as never },
      },
    });

    const error = await engine
      .open('system:orders:all')
      .catch((caught: unknown) => caught);

    expect(isViewCommandError(error) && error.issue).toMatchObject({
      code: 'view.definition.invalid',
      params: { id: 'orders', issues: 1 },
    });
    expect(issues.map(found => found.code)).toEqual([
      'capability.record.paging',
    ]);
  });

  it('runs on the definition as declared when the descriptor cannot be read, and notes it', async () => {
    const declared = ordersDefinition();
    const { engine, issues, source } = harness({
      definitions: [declared],
      describe: () => Promise.reject(new Error('404')),
    });

    const runtime = await engine.open('system:orders:all');

    expect(runtime.definition).toBe(declared);
    expect(source.paged).toHaveBeenCalledTimes(1);
    expect(issues).toEqual([
      {
        code: 'capability.descriptor.unavailable',
        severity: 'note',
        path: [],
        params: { source: 'orders' },
      },
    ]);
  });

  it('makes a view from nothing on what is held, and narrows it when the descriptor arrives', async () => {
    const { engine, describe } = harness({ definitions: [searchable()] });
    const input = {
      title: 'New',
      scope: 'personal' as const,
      config: recordConfig(),
    };

    const cold = engine.create('orders', input);
    // Made at once, before anything was read: the search is still there.
    expect(searchFieldOf(cold.fields)?.name).toBe('keyword');
    await nextTask();
    const warm = engine.create('orders', input);

    expect(describe).toHaveBeenCalledTimes(1);
    // The first read narrows the view already open, and the next is made
    // narrowed.
    expect(searchFieldOf(cold.fields)).toBeNull();
    expect(searchFieldOf(warm.fields)).toBeNull();
  });
});

describe('checking a descriptor again', () => {
  it('on a refresh, once it is older than the maximum age, with the version held', async () => {
    const { engine, describe, clock } = harness();
    const runtime = await engine.open('system:orders:all');
    describe.mockResolvedValue(notModified('sha256:orders-1'));

    runtime.refresh();
    expect(describe).toHaveBeenCalledTimes(1);

    clock.advance(DESCRIPTOR_MAX_AGE_MS);
    runtime.refresh();
    expect(describe).toHaveBeenCalledTimes(2);
    expect(describe).toHaveBeenLastCalledWith('sha256:orders-1');
  });

  it('when the page comes back, once it is older than the maximum age', async () => {
    const { engine, describe, clock } = harness();
    await engine.open('system:orders:all');
    describe.mockResolvedValue(notModified('sha256:orders-1'));

    clock.setVisible(false);
    clock.setVisible(true);
    expect(describe).toHaveBeenCalledTimes(1);

    clock.advance(DESCRIPTOR_MAX_AGE_MS);
    clock.setVisible(false);
    expect(describe).toHaveBeenCalledTimes(1);
    clock.setVisible(true);
    expect(describe).toHaveBeenCalledTimes(2);

    // A disposed engine listens no more.
    engine.dispose();
    clock.advance(DESCRIPTOR_MAX_AGE_MS);
    clock.setVisible(true);
    expect(describe).toHaveBeenCalledTimes(2);
  });

  it('narrows the views open on a new version at once, keeping a result they still admit', async () => {
    const { engine, describe, clock, issues, source } = harness();
    const open = await engine.open('system:orders:all');
    await nextTask();
    const next = {
      ...ordersDescriptor(),
      version: 'sha256:orders-2',
      fields: ordersDescriptor().fields.map(field =>
        field.path === 'amount'
          ? describedField('amount', { sort: { paged: false, cursor: false } })
          : field,
      ),
    };
    const answer = deferred<ReturnType<typeof read>>();
    describe.mockReturnValue(answer.promise);
    const asked = vi.mocked(source.paged).mock.calls.length;

    clock.advance(DESCRIPTOR_MAX_AGE_MS);
    // Answered at once from what is held; the check runs behind it.
    const before = await engine.open('system:orders:all');
    expect(before.fields.find(f => f.name === 'amount')?.sortable).toBe(true);
    answer.resolve(read(next));
    await nextTask();

    // Both views run on the new version now, and their results stay: the
    // config sorts by nothing the source took away.
    for (const view of [open, before]) {
      expect(view.fields.find(f => f.name === 'amount')?.sortable).toBe(false);
      expect(view.getSnapshot().result).not.toBeNull();
    }
    expect(vi.mocked(source.paged).mock.calls.length).toBe(asked + 1);
    expect(issues.map(found => found.code)).toEqual([
      'capability.field.unsortable',
    ]);
  });
});
