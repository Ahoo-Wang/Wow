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
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  defaultRecordConfig,
  MemoryViewStore,
  recordProjection,
  ViewEngine,
  type AnalysisViewConfig,
  type DashboardFilters,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import {
  narrowDefinition,
  withCanonicalNames,
} from '../src/capabilities/index.js';
import { withCanonicalPanelFields } from '../src/dashboard/panelFieldNames.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { canonicalBoard } from '../src/runtime/dashboard/canonical.js';
import type { PanelView } from '../src/runtime/dashboard/children.js';
import {
  analysisConfig,
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import {
  describedField,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

/**
 * Orders whose `amount` the service renamed to `total`: it lists `total`,
 * with `amount` as an alias (#3519).
 */
function renamedDescriptor(): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    fields: base.fields.map(field =>
      field.path === 'amount'
        ? { ...describedField('total'), aliases: ['amount'] }
        : field,
    ),
  };
}

describe('a field the definition names by an alias', () => {
  it('is renamed to the path, with everything that names it, and noted', () => {
    const declared: DataViewDefinition = {
      ...ordersDefinition(),
      fieldGroups: [{ id: 'money', label: 'Money', fields: ['amount'] }],
    };
    const { definition, findings } = narrowDefinition(
      declared,
      renamedDescriptor(),
      builtinFieldKinds,
    );

    expect(definition.fields.map(field => field.name)).toEqual([
      'id',
      'warehouse',
      'status',
      'total',
    ]);
    expect(definition.fields[3].label).toBe('Amount');
    expect(definition.fieldGroups?.[0].fields).toEqual(['total']);
    expect(definition.analysis?.fields.map(entry => entry.field)).toEqual([
      'warehouse',
      'total',
    ]);
    expect(definition.views?.[0].config).toMatchObject({
      table: { columns: [{ field: 'id' }, { field: 'total' }] },
    });
    expect(definition.narrowing?.renamed).toEqual({ amount: 'total' });
    expect(findings).toEqual([
      {
        code: 'capability.field.alias',
        severity: 'note',
        path: ['fields', 3],
        params: { field: 'amount', path: 'total' },
      },
    ]);
    // A page asks for what the source answers by.
    expect(
      recordProjection(
        definition,
        withCanonicalNames(recordConfig(), { amount: 'total' }),
      ).include,
    ).toContain('total');
  });

  it('reads a config saved under the alias under the path', () => {
    const renamed = { amount: 'total' };
    const record = withCanonicalNames(
      recordConfig({
        filter: {
          op: 'and',
          children: [
            {
              op: 'or',
              children: [{ field: 'amount', operator: 'GT', value: 10 }],
            },
          ],
        },
        sort: [{ field: 'amount', direction: 'DESC' }],
        summaries: [{ field: 'amount', fn: 'SUM' }],
        card: { title: 'id', fields: ['amount'], image: 'amount' },
      }),
      renamed,
    );
    expect(record).toMatchObject({
      filter: {
        children: [
          { children: [{ field: 'total', operator: 'GT', value: 10 }] },
        ],
      },
      sort: [{ field: 'total', direction: 'DESC' }],
      summaries: [{ field: 'total', fn: 'SUM' }],
      table: { columns: [{ field: 'id' }, { field: 'total' }] },
      card: { title: 'id', fields: ['total'], image: 'total' },
    });

    const analysis = withCanonicalNames(
      analysisConfig({
        groups: [
          { alias: 'a', field: 'amount', type: 'HISTOGRAM', interval: 10 },
        ],
        metrics: [
          {
            alias: 'sum',
            type: 'NUMERIC',
            function: 'SUM',
            expression: {
              type: 'BINARY',
              operator: 'ADD',
              left: { type: 'FIELD', field: 'amount' },
              right: { type: 'CONSTANT', value: 1 },
            },
            filter: {
              op: 'and',
              children: [{ field: 'amount', operator: 'GT', value: 0 }],
            },
          },
          { alias: 'any', type: 'ANY', field: 'amount' },
        ],
      }),
      renamed,
    );
    expect(analysis).toMatchObject({
      groups: [{ field: 'total' }],
      metrics: [
        {
          expression: { left: { field: 'total' } },
          filter: { children: [{ field: 'total' }] },
        },
        { field: 'total' },
      ],
    });
    // Nothing to rename: the same config back.
    const plain = recordConfig({
      table: { columns: [{ field: 'id' }] },
      card: { title: 'id', fields: [] },
    });
    expect(withCanonicalNames(plain, renamed)).toBe(plain);
    expect(withCanonicalNames(plain, {})).toBe(plain);
  });

  it('opens a view saved under the alias under the path, not dirty, and asks by it', async () => {
    const saved: ViewInstance = {
      id: 'by-amount',
      definitionId: 'orders',
      title: 'By amount',
      scope: 'personal',
      revision: '1',
      config: recordConfig({ sort: [{ field: 'amount', direction: 'DESC' }] }),
    };
    const descriptor = renamedDescriptor();
    const paged = vi.fn(() => Promise.resolve({ total: 0, list: [] }));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () =>
        testSource({
          paged,
          describe: () => Promise.resolve(read(descriptor)),
        }),
    });

    const runtime = await engine.open('by-amount');
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.issues).toEqual([]);
    expect(state.dirty).toBe(false);
    expect(paged).toHaveBeenCalledWith(
      expect.objectContaining({
        sort: [
          { field: 'total', direction: 'DESC' },
          { field: 'id', direction: 'ASC' },
        ],
      }),
      undefined,
      expect.anything(),
    );
  });

  it('renames an open view when a new version starts naming a field by its path', async () => {
    const saved: ViewInstance = {
      id: 'by-amount',
      definitionId: 'orders',
      title: 'By amount',
      scope: 'personal',
      revision: '1',
      config: recordConfig({ sort: [{ field: 'amount', direction: 'DESC' }] }),
    };
    const describe = vi
      .fn()
      .mockResolvedValueOnce(read(ordersDescriptor()))
      .mockResolvedValueOnce(
        read({ ...renamedDescriptor(), version: 'sha256:orders-2' }),
      );
    const clock = testEnvironment();
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () => testSource({ describe }),
      environment: clock.environment,
    });
    const runtime = await engine.open('by-amount');
    await nextTask();

    clock.advance(5 * 60 * 1000);
    runtime.refresh();
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.draft).toMatchObject({
      sort: [{ field: 'total', direction: 'DESC' }],
    });
    expect(state.applied).toMatchObject({
      sort: [{ field: 'total', direction: 'DESC' }],
    });
    expect(state.saved?.config).toMatchObject({
      sort: [{ field: 'total', direction: 'DESC' }],
    });
    expect(state.dirty).toBe(false);
  });

  it('matches a search field and an element field by an alias too', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'elementMatch',
          elements: [{ name: 'code', label: 'Code', kind: 'string' }],
        },
        {
          name: 'q',
          label: 'Search',
          kind: 'search',
          searchFields: ['amount'],
        },
      ],
      analysis: {
        count: true,
        fields: [],
        elements: [
          {
            path: 'items',
            aggregations: [
              {
                field: 'code',
                groups: [AggregationGroupType.TERMS],
                functions: [AggregationFunction.SUM],
              },
            ],
          },
        ],
      },
    });
    const base = renamedDescriptor();
    const descriptor: QueryModelDescriptor = {
      ...base,
      fields: [
        ...base.fields,
        describedField('items'),
        {
          ...describedField('items.sku', { scope: 'items' }),
          aliases: ['items.code'],
        },
      ],
      elements: [{ path: 'items', filter: true, aggregate: true }],
      record: {
        ...base.record,
        search: { modes: ['TERMS'] as never, fields: ['total'] },
      },
    };
    const { definition } = narrowDefinition(
      declared,
      descriptor,
      builtinFieldKinds,
    );

    const items = definition.fields.find(field => field.name === 'items');
    expect(items?.elements?.[0].name).toBe('sku');
    expect(
      definition.fields.find(field => field.name === 'q')?.searchFields,
    ).toEqual(['total']);
    expect(definition.analysis?.elements?.[0].aggregations[0].field).toBe(
      'sku',
    );
    expect(definition.narrowing?.renamed).toEqual({
      amount: 'total',
      'items.code': 'items.sku',
    });
  });
});

describe('a definition whose record defaults name a field by an alias', () => {
  it('starts a view under the path', () => {
    const declared = ordersDefinition({
      record: {
        rowKey: 'id',
        paging: 'paged',
        layouts: ['table', 'card'],
        defaults: {
          filter: {
            op: 'and',
            children: [{ field: 'amount', operator: 'GT', value: 0 }],
          },
          sort: [{ field: 'amount', direction: 'DESC' }],
          summaries: [{ field: 'amount', fn: 'SUM' }],
          table: { columns: [{ field: 'id' }, { field: 'amount' }] },
          card: { title: 'id', fields: ['amount'] },
        },
      },
    });
    const { definition } = narrowDefinition(
      declared,
      renamedDescriptor(),
      builtinFieldKinds,
    );

    expect(definition.record?.defaults).toEqual({
      filter: {
        op: 'and',
        children: [{ field: 'total', operator: 'GT', value: 0 }],
      },
      sort: [{ field: 'total', direction: 'DESC' }],
      summaries: [{ field: 'total', fn: 'SUM' }],
      table: { columns: [{ field: 'id' }, { field: 'total' }] },
      card: { title: 'id', fields: ['total'] },
    });
    expect(defaultRecordConfig(definition)).toMatchObject({
      sort: [{ field: 'total', direction: 'DESC' }],
      table: { columns: [{ field: 'id' }, { field: 'total' }] },
    });
  });

  it('keeps defaults that name no alias as they are', () => {
    const defaults = { pageSize: 50, sort: [] };
    const declared = ordersDefinition({
      record: {
        rowKey: 'id',
        paging: 'paged',
        layouts: ['table'],
        defaults,
      },
    });
    const { definition } = narrowDefinition(
      declared,
      renamedDescriptor(),
      builtinFieldKinds,
    );
    expect(definition.record?.defaults).toBe(defaults);
  });
});

describe('a board that names a panel field by an alias', () => {
  const renamed = { amount: 'total' };

  /** A board saved beside a list of orders, opened over renamed orders. */
  async function openBoard(
    board: DashboardViewConfig,
    filters?: DashboardFilters,
  ): Promise<DashboardViewRuntime> {
    const list: ViewInstance = {
      id: 'orders-list',
      definitionId: 'orders',
      title: 'Orders',
      scope: 'shared',
      revision: 'r1',
      config: recordConfig({ sort: [{ field: 'amount', direction: 'DESC' }] }),
    };
    const descriptor = renamedDescriptor();
    const store = new MemoryViewStore({ instances: [list] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () =>
        testSource({ describe: () => Promise.resolve(read(descriptor)) }),
    });
    const saved = await store.create(
      {
        definitionId: 'overview',
        title: 'Board',
        scope: 'shared',
        config: board,
      },
      { requestId: 'board' },
    );
    const runtime = await engine.open(saved.id, filters ? { filters } : {});
    await nextTask();
    if (!(runtime instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');
    return runtime;
  }

  it('reads every name it gives the field under the path', () => {
    const panel = {
      id: 'a',
      kind: 'view',
      instanceId: 'orders-list',
      layout: { x: 0, y: 0, w: 8, h: 4 },
      bindings: [
        { globalField: 'min', panelField: 'amount' },
        { globalField: 'region', panelField: 'warehouse', auto: true },
        { globalField: 'broken' },
        'not a binding',
      ],
      click: {
        kind: 'url',
        url: '/orders?amount={{ amount }}&w={{warehouse}}',
      },
    } as unknown as DashboardPanel;
    const toBoard = {
      ...panel,
      id: 'b',
      click: {
        kind: 'dashboard',
        instanceId: 'other',
        values: {
          size: { dimension: 'amount' },
          region: { filter: 'region' },
          odd: 'not a source',
        },
      },
    } as unknown as DashboardPanel;
    const owned = {
      id: 'c',
      kind: 'view',
      owned: { definitionId: 'orders', config: analysisConfig() },
      layout: { x: 8, y: 0, w: 8, h: 4 },
      bindings: [],
    } as unknown as DashboardPanel;
    const heading = {
      id: 'h',
      kind: 'heading',
      content: 'Orders',
      layout: { x: 0, y: 4, w: 24, h: 1 },
    } as DashboardPanel;
    const config = dashboardConfig({
      panels: [heading, panel, toBoard, owned],
    });
    const ownedView = vi.fn((view: ReturnType<typeof analysisConfig>) => ({
      ...view,
      title: 'renamed',
    }));

    const next = withCanonicalPanelFields(config, () => renamed, ownedView);

    expect(next.panels[0]).toBe(heading);
    expect(next.panels[1]).toMatchObject({
      bindings: [
        { globalField: 'min', panelField: 'total' },
        { globalField: 'region', panelField: 'warehouse', auto: true },
        { globalField: 'broken' },
        'not a binding',
      ],
      click: { url: '/orders?amount={{ total }}&w={{warehouse}}' },
    });
    expect(next.panels[2]).toMatchObject({
      click: {
        values: {
          size: { dimension: 'total' },
          region: { filter: 'region' },
          odd: 'not a source',
        },
      },
    });
    expect(ownedView).toHaveBeenCalledWith(analysisConfig(), renamed);
    expect(next.panels[3]).toMatchObject({
      owned: { config: { title: 'renamed' } },
    });
    // The config itself back when nothing is renamed, or not known yet.
    const plain = dashboardConfig({ panels: [heading, toBoard] });
    expect(withCanonicalPanelFields(plain, () => ({}), ownedView)).toBe(plain);
    expect(withCanonicalPanelFields(config, () => null, ownedView)).toBe(
      config,
    );
    expect(
      withCanonicalPanelFields(
        dashboardConfig({ panels: [heading] }),
        () => renamed,
        ownedView,
      ).panels[0],
    ).toBe(heading);
  });

  it('opens clean, narrows its panels through the path and is saved under it', async () => {
    const board = dashboardConfig({
      fields: [{ name: 'min', label: 'Min', kind: 'number' }],
      panels: [
        {
          id: 'list',
          kind: 'view',
          instanceId: 'orders-list',
          bindings: [{ globalField: 'min', panelField: 'amount' }],
          layout: { x: 0, y: 0, w: 12, h: 4 },
        },
        {
          id: 'owned',
          kind: 'view',
          owned: {
            definitionId: 'orders',
            config: analysisConfig({
              metrics: [
                {
                  alias: 'sum',
                  type: 'NUMERIC',
                  function: 'SUM',
                  expression: { type: 'FIELD', field: 'amount' },
                },
              ],
            }),
          },
          bindings: [{ globalField: 'min', panelField: 'amount' }],
          layout: { x: 12, y: 0, w: 12, h: 4 },
        },
      ],
    });
    const runtime = await openBoard(board, { values: { min: [10] } });

    const state = runtime.getSnapshot();
    expect(state.issues).toEqual([]);
    expect(state.dirty).toBe(false);
    for (const config of [state.draft, state.applied, state.saved?.config])
      expect(config).toMatchObject({
        panels: [
          { bindings: [{ globalField: 'min', panelField: 'total' }] },
          {
            bindings: [{ globalField: 'min', panelField: 'total' }],
            owned: {
              config: {
                metrics: [{ expression: { type: 'FIELD', field: 'total' } }],
              },
            },
          },
        ],
      });

    for (const panel of runtime.getSnapshot().panels)
      expect(panel.runtime?.scopeFilter).toMatchObject({
        children: [
          { children: [{ field: 'total', operator: 'IN', value: [10] }] },
        ],
      });
  });

  it('leaves a view it owns that is no config as it is, for admission', () => {
    const broken = { kind: 'analysis' } as unknown as AnalysisViewConfig;
    const config = dashboardConfig({
      panels: [
        {
          id: 'owned',
          kind: 'view',
          owned: { definitionId: 'orders', config: broken },
          bindings: [{ globalField: 'min', panelField: 'amount' }],
          layout: { x: 0, y: 0, w: 12, h: 4 },
        },
      ],
    });
    const read = canonicalBoard(
      () =>
        ({ definition: { narrowing: { renamed } } }) as unknown as PanelView,
    );
    expect(read(config).panels[0]).toMatchObject({
      owned: { config: broken },
      bindings: [{ panelField: 'total' }],
    });
  });
});
