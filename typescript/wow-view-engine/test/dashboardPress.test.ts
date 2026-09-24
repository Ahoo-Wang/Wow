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

/**
 * A press on a panel's group in the runtime (D22 I): cross-filtering sets
 * the board's filter from the panel pressed — every other wired panel runs
 * under it, the panel pressed does not and marks the group, a second press
 * clears it, a value set from the bar is nobody's press — and a custom
 * destination works out where it goes, carrying the group.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it, vi } from 'vitest';
import {
  AUTO_APPLY_DELAY_MS,
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

function flush(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

const DAY = Date.UTC(2026, 8, 22);

function orders(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      fields: [
        ...(base.analysis?.fields ?? []),
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        },
      ],
    },
  };
}

const views: ViewInstance[] = [
  {
    id: 'by-warehouse',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig(),
  },
  {
    id: 'by-day',
    definitionId: 'orders',
    title: 'By day',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig({
      groups: [
        {
          alias: 'createdAt',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'DAY',
        },
      ],
      chart: {
        type: 'bar',
        cartesian: { x: 'createdAt', series: [{ metric: 'orders' }] },
      },
    }),
  },
  {
    id: 'list',
    definitionId: 'orders',
    title: 'Order list',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
];

function view(
  id: string,
  instanceId: string,
  extra: Partial<DashboardPanel> = {},
  x = 0,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId,
    bindings: [
      { globalField: 'region', panelField: 'warehouse' },
      { globalField: 'created', panelField: 'createdAt' },
    ],
    layout: { x, y: 0, w: 8, h: 4 },
    ...extra,
  } as DashboardPanel;
}

function board(...panels: DashboardPanel[]): DashboardViewConfig {
  return dashboardConfig({
    fields: [
      { name: 'region', label: 'Region', kind: 'string' },
      { name: 'created', label: 'Created', kind: 'datetime' },
    ],
    panels: panels.length
      ? panels
      : [
          view('chart', 'by-warehouse', {
            click: { kind: 'filter', filter: 'region' },
          }),
          view('trend', 'by-day', {
            click: { kind: 'filter', filter: 'created' },
          }),
          view('list', 'list', {}, 16),
        ],
  });
}

async function harness(config: DashboardViewConfig = board()) {
  const clock = testEnvironment();
  const source = testSource({
    aggregate: vi.fn(query =>
      Promise.resolve(
        query.groupBy?.[0]?.alias === 'createdAt'
          ? [{ createdAt: DAY, orders: 4 }]
          : [
              { warehouse: 'CN', orders: 2 },
              { warehouse: 'EU', orders: 1 },
            ],
      ),
    ),
  });
  const store = new MemoryViewStore({ instances: views });
  const engine = new ViewEngine({
    definitions: [orders(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: clock.environment,
  });
  const saved = await store.create(
    { definitionId: 'overview', title: 'Board', scope: 'shared', config },
    { requestId: 'board' },
  );
  const runtime = await engine.open(saved.id);
  await flush();
  if (!(runtime instanceof DashboardViewRuntime))
    throw new Error('expected a dashboard');
  const panel = (id: string) => {
    const found = runtime.getSnapshot().panels.find(entry => entry.id === id);
    if (!found) throw new Error(`no panel ${id}`);
    return found;
  };
  return {
    runtime,
    clock,
    boardId: saved.id,
    panel,
    scope: (id: string) =>
      JSON.stringify(panel(id).runtime?.scopeFilter ?? null),
  };
}

describe('cross-filtering (D22 I)', () => {
  it('sets the filter from the panel pressed: the others run under it, the panel pressed does not', async () => {
    const { runtime, clock, scope } = await harness();

    const outcome = runtime.crossFilter('chart', { warehouse: 'CN' });
    expect(outcome.kind).toBe('set');
    expect(runtime.getSnapshot().filters).toEqual({
      values: { region: ['CN'] },
      from: { region: 'chart' },
    });
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(scope('list')).toContain('"CN"');
    expect(scope('trend')).toContain('"CN"');
    // The panel pressed keeps every group, and marks the one pressed.
    expect(scope('chart')).not.toContain('"CN"');
    expect(runtime.pressed('chart', { warehouse: 'CN' })).toBe(true);
    expect(runtime.pressed('chart', { warehouse: 'EU' })).toBe(false);
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('clears on a second press of the same group, and moves on a press of another', async () => {
    const { runtime } = await harness();

    runtime.crossFilter('chart', { warehouse: 'CN' });
    expect(runtime.crossFilter('chart', { warehouse: 'EU' }).kind).toBe('set');
    expect(runtime.getSnapshot().filters.values.region).toEqual(['EU']);
    expect(runtime.crossFilter('chart', { warehouse: 'EU' }).kind).toBe(
      'cleared',
    );
    expect(runtime.getSnapshot().filters).toEqual({ values: {} });
  });

  it('takes a date bucket as the window it spans', async () => {
    const { runtime } = await harness();

    expect(runtime.crossFilter('trend', { createdAt: DAY }).kind).toBe('set');
    const held = runtime.getSnapshot().filters;
    expect(held.from).toEqual({ created: 'trend' });
    expect(held.values.created).toMatchObject({
      type: 'absolute',
      from: new Date(DAY).toISOString(),
    });
    expect(runtime.pressed('trend', { createdAt: DAY })).toBe(true);
  });

  it('forgets where the value came from once it is set from the bar', async () => {
    const { runtime, clock, scope } = await harness();

    runtime.crossFilter('chart', { warehouse: 'CN' });
    runtime.setFilterValue('region', ['CN']);
    expect(runtime.getSnapshot().filters.from).toBeUndefined();
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(scope('chart')).toContain('"CN"');
    expect(runtime.pressed('chart', { warehouse: 'CN' })).toBe(false);
  });

  it('changes nothing for the group of records without a value, or a panel that sets no filter', async () => {
    const { runtime } = await harness();

    expect(runtime.crossFilter('chart', { warehouse: null }).kind).toBe(
      'no-value',
    );
    expect(runtime.crossFilter('list', { warehouse: 'CN' }).kind).toBe('none');
    expect(runtime.crossFilter('nope', { warehouse: 'CN' }).kind).toBe('none');
    expect(runtime.getSnapshot().filters).toEqual({ values: {} });
  });

  it('holds each panel’s click in force, and none where admission warned', async () => {
    const { panel } = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: { kind: 'filter', filter: 'region' },
        }),
        view('odd', 'by-warehouse', {
          click: { kind: 'filter', filter: 'gone' },
        }),
      ),
    );
    expect(panel('chart').click).toEqual({ kind: 'filter', filter: 'region' });
    expect(panel('odd').click).toBeNull();
    expect(panel('odd').issues.map(found => found.code)).toContain(
      'dashboard.click.filter-unknown',
    );
  });
});

describe('a custom destination (D22 I)', () => {
  it('fills a page’s address with the group pressed', async () => {
    const { runtime } = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: { kind: 'url', url: '/warehouses/{{warehouse}}' },
        }),
      ),
    );
    expect(await runtime.destination('chart', { warehouse: 'CN' })).toEqual({
      to: { kind: 'url', url: '/warehouses/CN' },
    });
    // A press that sets no destination goes nowhere.
    expect(runtime.crossFilter('chart', { warehouse: 'CN' }).kind).toBe('none');
  });

  it('opens another view under the group, on the fields its data has too', async () => {
    const { runtime } = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: { kind: 'view', instanceId: 'list' },
        }),
      ),
    );
    expect(await runtime.destination('chart', { warehouse: 'CN' })).toEqual({
      to: {
        kind: 'view',
        instanceId: 'list',
        filter: {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        },
      },
    });
  });

  it('refuses a view that is gone, or a dashboard, and says why', async () => {
    const gone = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: { kind: 'view', instanceId: 'gone' },
        }),
      ),
    );
    const refused = await gone.runtime.destination('chart', {
      warehouse: 'CN',
    });
    expect(refused && 'refused' in refused && refused.refused.code).toBe(
      'dashboard.click.destination-unavailable',
    );

    const { runtime, boardId } = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: { kind: 'view', instanceId: 'board-self' },
        }),
      ),
    );
    runtime.setPanelClick('chart', { kind: 'view', instanceId: boardId });
    runtime.apply();
    await flush();
    const board2 = await runtime.destination('chart', { warehouse: 'CN' });
    expect(board2 && 'refused' in board2 && board2.refused.code).toBe(
      'dashboard.click.destination-unsupported',
    );
    expect(await runtime.destination('nope', { warehouse: 'CN' })).toBeNull();
  });
});
