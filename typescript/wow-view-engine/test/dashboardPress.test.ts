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
} from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import {
  AUTO_APPLY_DELAY_MS,
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type BoardValueSource,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  preCDashboardConfig,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

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
  // The board a press opens (D23 Q17): an area, a period, and a stage
  // that starts at its default.
  {
    id: 'regional',
    definitionId: 'overview',
    title: 'Regional',
    scope: 'shared',
    revision: 'r1',
    config: dashboardConfig({
      fields: [
        { name: 'area', label: 'Area', kind: 'string' },
        { name: 'period', label: 'Period', kind: 'datetime' },
        { name: 'stage', label: 'Stage', kind: 'string', default: ['OPEN'] },
      ],
      panels: [],
    }),
  },
  // The same board saved before batch C: its stage is a board condition,
  // which reading it makes the filter's default (D23 Q16).
  {
    id: 'regional-old',
    definitionId: 'overview',
    title: 'Regional (old)',
    scope: 'shared',
    revision: 'r1',
    config: preCDashboardConfig(
      {
        op: 'and',
        children: [{ field: 'stage', operator: 'IN', value: ['OPEN'] }],
      },
      {
        fields: [
          { name: 'area', label: 'Area', kind: 'string' },
          { name: 'stage', label: 'Stage', kind: 'string' },
        ],
        panels: [],
      },
    ),
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
  await nextTask();
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
    store,
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
    expect(panel('chart').clickFinding).toBeNull();
    expect(panel('odd').click).toBeNull();
    expect(panel('odd').issues.map(found => found.code)).toContain(
      'dashboard.click.filter-unknown',
    );
    // The state carries why, with the click it set aside (A-11).
    expect(panel('odd').clickFinding?.code).toBe(
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

  /**
   * A-11: which click is in force is the panel state's to say. A press used
   * to read the click off the config and judge it again, so a URL admission
   * had warned of — a field the panel does not group by — still opened,
   * with the placeholder left empty, while the panel said it would not.
   */
  it('falls back to the menu, saying why, on a click the panel state set aside', async () => {
    const { runtime, panel } = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: { kind: 'url', url: '/customers/{{customer}}' },
        }),
      ),
    );
    expect(panel('chart').click).toBeNull();

    const fell = await runtime.destination('chart', { warehouse: 'CN' });
    expect(fell && 'fallback' in fell && fell.fallback.code).toBe(
      'dashboard.click.url-unknown-field',
    );
    // Nor does a press on a panel with no click set go anywhere.
    const plain = await harness(board(view('chart', 'by-warehouse')));
    expect(
      await plain.runtime.destination('chart', { warehouse: 'CN' }),
    ).toBeNull();
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
        definitionId: 'orders',
        instanceId: 'list',
        scopeFilter: null,
        filter: {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        },
        from: expect.objectContaining({ title: 'Board' }),
      },
    });
  });

  it('takes what the panel takes off the board (D26 Q30): the page’s hold as the scope, the reader’s value among its own', async () => {
    const { runtime, clock } = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: { kind: 'view', instanceId: 'list' },
        }),
      ),
    );
    runtime.holdFilters({ values: { region: ['CN'] } });
    const held = await runtime.destination('chart', { warehouse: 'CN' });
    expect(held && 'to' in held && held.to).toMatchObject({
      kind: 'view',
      scopeFilter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'IN', value: ['CN'] }],
      },
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    });

    runtime.holdFilters(null);
    runtime.setFilterValue('region', ['EU']);
    clock.advance(AUTO_APPLY_DELAY_MS);
    const own = await runtime.destination('chart', { warehouse: 'EU' });
    expect(own && 'to' in own && own.to).toMatchObject({
      kind: 'view',
      scopeFilter: null,
      filter: {
        op: 'and',
        children: [
          { field: 'warehouse', operator: 'IN', value: ['EU'] },
          { field: 'warehouse', operator: 'EQ', value: 'EU' },
        ],
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
    runtime.setBuilding(true);
    runtime.setPanelClick('chart', { kind: 'view', instanceId: boardId });
    runtime.apply();
    await nextTask();
    const board2 = await runtime.destination('chart', { warehouse: 'CN' });
    expect(board2 && 'refused' in board2 && board2.refused.code).toBe(
      'dashboard.click.destination-unsupported',
    );
    expect(await runtime.destination('nope', { warehouse: 'CN' })).toBeNull();
  });
});

describe('a press that opens another board (D23 Q17)', () => {
  const toRegional = (
    values: Record<string, BoardValueSource>,
  ): Partial<DashboardPanel> => ({
    click: { kind: 'dashboard', instanceId: 'regional', values },
  });

  it('opens it with each mapped filter set from the group, the rest at their defaults', async () => {
    const { runtime } = await harness(
      board(
        view(
          'chart',
          'by-warehouse',
          toRegional({ area: { dimension: 'warehouse' } }),
        ),
        view(
          'trend',
          'by-day',
          toRegional({ period: { dimension: 'createdAt' } }),
          8,
        ),
      ),
    );
    expect(await runtime.destination('chart', { warehouse: 'CN' })).toEqual({
      to: {
        kind: 'dashboard',
        definitionId: 'overview',
        instanceId: 'regional',
        filters: { values: { stage: ['OPEN'], area: ['CN'] } },
      },
    });
    const trend = await runtime.destination('trend', { createdAt: DAY });
    expect(trend && 'to' in trend && trend.to).toMatchObject({
      kind: 'dashboard',
      filters: {
        values: {
          period: { type: 'absolute', from: new Date(DAY).toISOString() },
        },
      },
    });
    // The records without a value leave the filter at its default.
    expect(await runtime.destination('chart', { warehouse: null })).toEqual({
      to: expect.objectContaining({
        filters: { values: { stage: ['OPEN'] } },
      }),
    });
    // Nothing of it is written into this board.
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('opens a board saved before batch C at the defaults its condition became', async () => {
    const { runtime } = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: {
            kind: 'dashboard',
            instanceId: 'regional-old',
            values: { area: { dimension: 'warehouse' } },
          },
        }),
      ),
    );
    const went = await runtime.destination('chart', { warehouse: 'CN' });
    expect(went && 'to' in went && went.to).toMatchObject({
      kind: 'dashboard',
      filters: { values: { area: ['CN'], stage: ['OPEN'] } },
    });
  });

  it('reads the board only when pressed or asked for, never as this board opens', async () => {
    const store = new MemoryViewStore({ instances: views });
    const get = vi.spyOn(store, 'get');
    const engine = new ViewEngine({
      definitions: [orders(), overviewDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const saved = await store.create(
      {
        definitionId: 'overview',
        title: 'Board',
        scope: 'shared',
        config: board(
          view(
            'chart',
            'by-warehouse',
            toRegional({ area: { dimension: 'warehouse' } }),
          ),
        ),
      },
      { requestId: 'board' },
    );
    const runtime = await engine.open(saved.id);
    await nextTask();
    if (!(runtime instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');
    expect(get.mock.calls.map(([id]) => id)).not.toContain('regional');

    const read = await runtime.destinationBoard('regional');
    expect(read?.title).toBe('Regional');
    expect(read?.config.columns).toBe(24);
    expect(get.mock.calls.filter(([id]) => id === 'regional')).toHaveLength(1);
    // Neither a view nor a board that is gone is a board to open.
    expect(await runtime.destinationBoard('list')).toBeNull();
    expect(await runtime.destinationBoard('nope')).toBeNull();
  });

  it('falls back to the follow-up menu on a mapping gone stale, and warns from then on', async () => {
    const { runtime, panel } = await harness(
      board(
        view(
          'chart',
          'by-warehouse',
          toRegional({ zone: { dimension: 'warehouse' } }),
        ),
      ),
    );
    expect(panel('chart').click).toMatchObject({ kind: 'dashboard' });
    const fell = await runtime.destination('chart', { warehouse: 'CN' });
    expect(fell && 'fallback' in fell && fell.fallback.code).toBe(
      'dashboard.click.board-filter-unknown',
    );
    await nextTask();
    expect(panel('chart').click).toBeNull();
    expect(panel('chart').issues.map(found => found.code)).toContain(
      'dashboard.click.board-filter-unknown',
    );
  });

  it('falls back when the board is gone, or the dimension is', async () => {
    const gone = await harness(
      board(
        view('chart', 'by-warehouse', {
          click: {
            kind: 'dashboard',
            instanceId: 'nope',
            values: { area: { dimension: 'warehouse' } },
          },
        }),
      ),
    );
    const fell = await gone.runtime.destination('chart', { warehouse: 'CN' });
    expect(fell && 'fallback' in fell && fell.fallback.code).toBe(
      'dashboard.click.board-gone',
    );

    // A dimension the panel no longer groups by is known without the board.
    const { panel } = await harness(
      board(
        view(
          'chart',
          'by-warehouse',
          toRegional({ area: { dimension: 'status' } }),
        ),
      ),
    );
    expect(panel('chart').click).toBeNull();
    expect(panel('chart').issues.map(found => found.code)).toContain(
      'dashboard.click.board-dimension-unknown',
    );
  });
});

describe('a mapping from one of this board’s filters (D23 Q17, 2026-09-23)', () => {
  const fromOwn = (
    values: Record<string, BoardValueSource>,
  ): Partial<DashboardPanel> => ({
    click: { kind: 'dashboard', instanceId: 'regional', values },
  });

  it('carries what this board’s filter holds at the press, not the group', async () => {
    const { runtime } = await harness(
      board(
        view(
          'chart',
          'by-warehouse',
          fromOwn({
            area: { filter: 'region' },
            period: { filter: 'created' },
          }),
        ),
      ),
    );
    const window = { type: 'absolute', from: '2026-09-01', to: '2026-09-30' };
    runtime.setFilterValue('region', ['EU']);
    runtime.setFilterValue('created', window);
    const went = await runtime.destination('chart', { warehouse: 'CN' });
    expect(went && 'to' in went && went.to).toMatchObject({
      kind: 'dashboard',
      filters: { values: { area: ['EU'], period: window, stage: ['OPEN'] } },
    });
  });

  it('sends nothing for a filter left blank, and the target keeps its default', async () => {
    const { runtime } = await harness(
      board(
        view('chart', 'by-warehouse', fromOwn({ stage: { filter: 'region' } })),
      ),
    );
    const went = await runtime.destination('chart', { warehouse: 'CN' });
    expect(went && 'to' in went && went.to).toMatchObject({
      filters: { values: { stage: ['OPEN'] } },
    });
  });

  it('sends nothing the target filter refuses: several values into one that takes one', async () => {
    const config = board(
      view('chart', 'by-warehouse', fromOwn({ area: { filter: 'region' } })),
    );
    const { runtime } = await harness({
      ...config,
      fields: config.fields.map(field =>
        field.name === 'region' ? { ...field, multiple: true } : field,
      ),
    });
    expect(runtime.setFilterValue('region', ['CN', 'EU'])).toEqual([]);
    const went = await runtime.destination('chart', { warehouse: 'CN' });
    expect(went && 'to' in went && went.to).toMatchObject({
      filters: { values: { stage: ['OPEN'] } },
    });
    expect(
      went && 'to' in went && went.to.kind === 'dashboard'
        ? went.to.filters.values.area
        : 'none',
    ).toBeUndefined();
  });

  it('warns of a filter this board no longer has, and a press falls back to the menu', async () => {
    const { runtime, panel } = await harness(
      board(
        view('chart', 'by-warehouse', fromOwn({ area: { filter: 'gone' } })),
      ),
    );
    expect(panel('chart').click).toBeNull();
    expect(panel('chart').issues.map(found => found.code)).toContain(
      'dashboard.click.board-source-unknown',
    );
    // Were the click pressed anyway, it falls back rather than open.
    const fell = await runtime.destination('chart', { warehouse: 'CN' });
    expect(fell && 'fallback' in fell && fell.fallback.code).toBe(
      'dashboard.click.board-source-unknown',
    );
  });
});
