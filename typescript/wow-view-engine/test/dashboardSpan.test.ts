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
 * A brushed stretch of a panel's time axis set into the board's date filter
 * (D33 batch C, Q52): the follow-up menu's 「设为〈筛选〉」, with the same
 * reading as a press that sets a filter — every other wired panel runs
 * under it, the panel brushed keeps every group and marks the buckets
 * inside the stretch (D23 Q18).
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import { AUTO_APPLY_DELAY_MS } from '../src/runtime/autoApply.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
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

const DAY_MS = 86_400_000;
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

/** A trend and a warehouse chart, both following the menu, and a list. */
function board(): DashboardViewConfig {
  return dashboardConfig({
    fields: [
      { name: 'region', label: 'Region', kind: 'string' },
      { name: 'created', label: 'Created', kind: 'datetime' },
    ],
    panels: [
      view('trend', 'by-day'),
      view('chart', 'by-warehouse', {}, 8),
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
          ? [0, 1, 2, 3, 4].map(n => ({
              createdAt: DAY + n * DAY_MS,
              orders: n,
            }))
          : [{ warehouse: 'CN', orders: 2 }],
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
    scope: (id: string) =>
      JSON.stringify(panel(id).runtime?.scopeFilter ?? null),
  };
}

const first = { createdAt: DAY + DAY_MS };
const last = { createdAt: DAY + 3 * DAY_MS };

describe('a brushed stretch set into a board filter (D33 Q52)', () => {
  it('offers the date filters wired through a date dimension of the rows', async () => {
    const { runtime } = await harness();
    expect(runtime.spanFilters('trend').map(filter => filter.name)).toEqual([
      'created',
    ]);
    // Grouped by warehouse alone: no stretch of time to set.
    expect(runtime.spanFilters('chart')).toEqual([]);
    // A record panel, or no panel at all.
    expect(runtime.spanFilters('list')).toEqual([]);
    expect(runtime.spanFilters('nope')).toEqual([]);
  });

  it('sets the stretch from the panel brushed: the others run under it, the panel brushed does not', async () => {
    const { runtime, clock, scope } = await harness();

    const outcome = runtime.pressSpan('trend', 'created', last, first);
    expect(outcome).toMatchObject({ kind: 'set', filter: { name: 'created' } });
    const held = runtime.getSnapshot().filters;
    expect(held.from).toEqual({ created: 'trend' });
    expect(held.values.created).toEqual({
      type: 'absolute',
      from: new Date(DAY + DAY_MS).toISOString(),
      to: new Date(DAY + 4 * DAY_MS - 1).toISOString(),
      timeZone: 'UTC',
    });
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(scope('list')).toContain(new Date(DAY + DAY_MS).toISOString());
    expect(scope('chart')).toContain(new Date(DAY + DAY_MS).toISOString());
    // The panel brushed keeps every day, and marks the ones inside.
    expect(scope('trend')).not.toContain('createdAt');
    expect(runtime.pressed('trend', { createdAt: DAY })).toBe(false);
    expect(runtime.pressed('trend', { createdAt: DAY + 2 * DAY_MS })).toBe(
      true,
    );
    expect(runtime.pressed('trend', last)).toBe(true);
    expect(runtime.pressed('trend', { createdAt: DAY + 4 * DAY_MS })).toBe(
      false,
    );
    // Only the panel it was brushed on marks it.
    expect(runtime.pressed('chart', { warehouse: 'CN' })).toBe(false);
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('keeps where the stretch came from when the board reads its filters again', async () => {
    const { runtime } = await harness();
    runtime.pressSpan('trend', 'created', first, last);
    const held = runtime.getSnapshot().filters;
    // As a host's address hands it back.
    expect(runtime.setFilters(held)).toEqual([]);
    expect(runtime.getSnapshot().filters.from).toEqual({ created: 'trend' });
    // A text filter the panel's click does not set is nobody's press.
    runtime.setFilters({
      values: { ...held.values, region: ['CN'] },
      from: { created: 'trend', region: 'trend' },
    });
    expect(runtime.getSnapshot().filters.from).toEqual({ created: 'trend' });
  });

  it('sets nothing it cannot say, and nothing the host holds', async () => {
    const { runtime } = await harness();
    // Not a filter the panel's stretch can set.
    expect(runtime.pressSpan('trend', 'region', first, last).kind).toBe('none');
    expect(runtime.pressSpan('chart', 'created', first, last).kind).toBe(
      'none',
    );
    // Ends that are no time.
    expect(
      runtime.pressSpan(
        'trend',
        'created',
        { createdAt: 'soon' },
        { createdAt: 'later' },
      ).kind,
    ).toBe('no-value');
    expect(runtime.getSnapshot().filters).toEqual({ values: {} });

    // A filter the page holds is no reader's to set.
    runtime.holdFilters({ values: { created: null } });
    expect(runtime.spanFilters('trend')).toEqual([]);
    expect(runtime.pressSpan('trend', 'created', first, last).kind).toBe(
      'none',
    );

    runtime.dispose();
    expect(runtime.spanFilters('trend')).toEqual([]);
    expect(runtime.pressSpan('trend', 'created', first, last).kind).toBe(
      'none',
    );
  });
});
