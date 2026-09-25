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
 * A dashboard over the example server's orders, through the engine's
 * runtime: the board's filters wired into its panels, its fixed scope, and a
 * press on one panel filtering the others — each panel's answer added up
 * from the seeded orders.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import type {
  AnalysisViewConfig,
  DashboardField,
  DashboardPanel,
  DashboardRuntime,
  DashboardViewConfig,
  DataViewConfig,
  FilterTree,
  MemoryViewStore,
  RecordViewConfig,
  ViewEngine,
  ViewRuntime,
  ViewRuntimeState,
} from '@ahoo-wang/wow-view-engine';
import {
  BOARD,
  ORDERS,
  freshTenant,
  nextResult,
  ordersEngine,
  seedOrders,
  statusOf,
  sum,
  type SeededOrder,
} from './salesOrders';

const PROVINCES = ['Anhui', 'Jiangsu', 'Shanghai', 'Zhejiang'];

const BINDINGS = [
  { globalField: 'province', panelField: 'state.address.province' },
  { globalField: 'created', panelField: 'firstEventTime' },
  { globalField: 'amount', panelField: 'state.totalAmount' },
  { globalField: 'status', panelField: 'state.status' },
];

function analysis(
  config: Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'chart'>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    ...config,
  };
}

const LIST: RecordViewConfig = {
  kind: 'record',
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
  refresh: { interval: null },
  sort: [{ field: 'state.totalAmount', direction: 'DESC' }],
  pageSize: 5,
  layout: 'table',
  table: {
    columns: ['aggregateId', 'state.address.province', 'state.totalAmount'].map(
      field => ({ field }),
    ),
  },
  card: { title: 'aggregateId', fields: [] },
};

const AMOUNT = {
  type: 'NUMERIC',
  alias: 'amount',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'state.totalAmount' },
} as const;

function board(listId: string, fixed?: FilterTree): DashboardViewConfig {
  const panels: DashboardPanel[] = [
    {
      id: 'by-province',
      kind: 'view',
      owned: {
        definitionId: ORDERS,
        config: analysis({
          groups: [
            {
              type: 'TERMS',
              field: 'state.address.province',
              alias: 'province',
            },
          ],
          metrics: [{ type: 'COUNT', alias: 'orders' }, AMOUNT],
          chart: {
            type: 'bar',
            cartesian: { x: 'province', series: [{ metric: 'amount' }] },
          },
        }),
      },
      bindings: BINDINGS,
      click: { kind: 'filter', filter: 'province' },
      layout: { x: 0, y: 0, w: 12, h: 6 },
    },
    {
      id: 'total',
      kind: 'view',
      owned: {
        definitionId: ORDERS,
        config: analysis({
          groups: [],
          metrics: [AMOUNT, { type: 'COUNT', alias: 'orders' }],
          chart: { type: 'metric', metric: { metric: 'amount' } },
        }),
      },
      bindings: BINDINGS,
      layout: { x: 12, y: 0, w: 6, h: 3 },
    },
    {
      id: 'list',
      kind: 'view',
      instanceId: listId,
      bindings: BINDINGS,
      layout: { x: 0, y: 6, w: 24, h: 8 },
    },
  ];
  return {
    kind: 'dashboard',
    refresh: { interval: null },
    columns: 24,
    fixed: fixed ?? { op: 'and', children: [] },
    tabs: [],
    fields: [
      {
        name: 'province',
        label: 'Province',
        kind: 'enum',
        multiple: true,
        options: PROVINCES.map(value => ({ value, label: value })),
      },
      { name: 'created', label: 'Created', kind: 'datetime' },
      { name: 'amount', label: 'Total', kind: 'number', multiple: true },
      {
        name: 'status',
        label: 'Status',
        kind: 'enum',
        options: ['CREATED', 'PAID'].map(value => ({ value, label: value })),
      },
    ],
    panels,
  };
}

let tenant: string;
let orders: SeededOrder[];
let engine: ViewEngine;
let store: MemoryViewStore;

beforeAll(async () => {
  tenant = freshTenant('board');
  orders = await seedOrders(tenant);
  ({ engine, store } = ordersEngine(tenant));
});

/** Saves the board and its list, opens it, and waits for every panel. */
async function openBoard(fixed?: FilterTree) {
  const list = await store.create(
    { definitionId: ORDERS, title: 'Orders', scope: 'shared', config: LIST },
    { requestId: `list-${Math.random()}` },
  );
  const saved = await store.create(
    {
      definitionId: BOARD,
      title: 'Sales',
      scope: 'shared',
      config: board(list.id, fixed),
    },
    { requestId: `board-${Math.random()}` },
  );
  const runtime = (await engine.open(saved.id)) as DashboardRuntime;
  expect(runtime.kind).toBe('dashboard');
  const panels = ['by-province', 'total', 'list'] as const;
  for (const id of panels) {
    const found = runtime.getSnapshot().panels.find(panel => panel.id === id);
    expect(found?.issues).toEqual([]);
    await nextResult(child(runtime, id));
  }
  return runtime;
}

function child(
  runtime: DashboardRuntime,
  id: string,
): ViewRuntime<DataViewConfig> {
  const found = runtime.panelRuntime(id);
  if (!found) throw new Error(`panel ${id} has no runtime`);
  return found;
}

type Results = Record<string, ViewRuntimeState<DataViewConfig>['result']>;

function results(runtime: DashboardRuntime, ids: readonly string[]): Results {
  return Object.fromEntries(
    ids.map(id => [id, child(runtime, id).getSnapshot().result]),
  );
}

/** Waits for each panel named in `before` to answer again. */
async function rerun(runtime: DashboardRuntime, before: Results) {
  for (const [id, result] of Object.entries(before))
    await nextResult(child(runtime, id), result);
}

/** What the three panels show, read the way a reader reads them. */
function shown(runtime: DashboardRuntime) {
  const data = (id: string) => child(runtime, id).getSnapshot().result?.data;
  const byProvince = data('by-province');
  const total = data('total');
  const list = data('list');
  if (
    byProvince?.kind !== 'analysis' ||
    total?.kind !== 'analysis' ||
    list?.kind !== 'record'
  )
    throw new Error('unexpected panel results');
  return {
    byProvince: Object.fromEntries(
      byProvince.view.rows.map(row => [row.province, row.amount]),
    ),
    total: {
      amount: total.view.rows[0]?.amount,
      orders: total.view.rows[0]?.orders,
    },
    list: {
      ids: list.view.rows.map(row => String(row.key)),
      total:
        list.view.paging.mode === 'paged' ? list.view.paging.total : undefined,
    },
  };
}

/** What the three panels should show over the orders `keep` admits. */
function expected(
  keep: (order: SeededOrder) => boolean,
  pressed?: (order: SeededOrder) => boolean,
) {
  const matching = orders.filter(keep);
  const unpressed = orders.filter(pressed ?? keep);
  const byProvince: Record<string, number> = {};
  for (const order of unpressed)
    byProvince[order.province] =
      (byProvince[order.province] ?? 0) + order.total;
  return {
    byProvince,
    total: {
      amount: sum(matching.map(order => order.total)),
      orders: matching.length,
    },
    list: {
      ids: [...matching]
        .sort((a, b) => b.total - a.total)
        .slice(0, 5)
        .map(order => order.id),
      total: matching.length,
    },
  };
}

const PANELS = ['by-province', 'total', 'list'] as const;

const DAY = 86_400_000;

/** The first moment of `at`'s day, in UTC. */
const dayOf = (at: number) => Math.floor(at / DAY) * DAY;

/** The day the last seeded order was made. */
const seeded = () => dayOf(Math.max(...orders.map(order => order.createdAt)));

/** The seeded orders made on `day`. */
const onDay = (day: number) =>
  orders.filter(order => dayOf(order.createdAt) === day);

describe('dashboard against the example server', () => {
  it('runs every panel over every order until a filter is set', async () => {
    const runtime = await openBoard();
    expect(shown(runtime)).toEqual(expected(() => true));
    runtime.dispose();
  });

  it('narrows every wired panel by the board’s filters', async () => {
    const runtime = await openBoard();

    let before = results(runtime, PANELS);
    expect(runtime.setFilterValue('province', ['Jiangsu', 'Zhejiang'])).toEqual(
      [],
    );
    await rerun(runtime, before);
    const inProvinces = (order: SeededOrder) =>
      ['Jiangsu', 'Zhejiang'].includes(order.province);
    expect(shown(runtime)).toEqual(expected(inProvinces));

    // A date window and a list of amounts on top: every filter ANDed.
    const byTime = [...orders].sort((a, b) => a.createdAt - b.createdAt);
    const from = byTime[3].createdAt;
    const to = byTime[10].createdAt;
    const amounts = [30, 60, 70, 80, 90, 120, 150];
    before = results(runtime, PANELS);
    expect(
      runtime.setFilters({
        values: {
          province: ['Jiangsu', 'Zhejiang'],
          created: {
            type: 'absolute',
            from: new Date(from).toISOString(),
            to: new Date(to).toISOString(),
          },
          amount: amounts,
        },
      }),
    ).toEqual([]);
    await rerun(runtime, before);
    expect(shown(runtime)).toEqual(
      expected(
        order =>
          inProvinces(order) &&
          order.createdAt >= from &&
          order.createdAt <= to &&
          amounts.includes(order.total),
      ),
    );

    before = results(runtime, PANELS);
    runtime.clearFilters();
    await rerun(runtime, before);
    expect(shown(runtime)).toEqual(expected(() => true));
    runtime.dispose();
  });

  it('filters the other panels by a press, and keeps every group on the one pressed', async () => {
    const runtime = await openBoard();

    const before = results(runtime, ['total', 'list']);
    const pressedBefore = child(runtime, 'by-province').getSnapshot().result;
    expect(
      runtime.crossFilter('by-province', { province: 'Zhejiang' }).kind,
    ).toBe('set');
    await rerun(runtime, before);

    expect(runtime.getSnapshot().filters).toEqual({
      values: { province: ['Zhejiang'] },
      from: { province: 'by-province' },
    });
    expect(shown(runtime)).toEqual(
      expected(
        order => order.province === 'Zhejiang',
        () => true,
      ),
    );
    // The panel pressed did not run again: it still shows every province,
    // and marks the one pressed.
    expect(child(runtime, 'by-province').getSnapshot().result).toBe(
      pressedBefore,
    );
    expect(runtime.pressed('by-province', { province: 'Zhejiang' })).toBe(true);

    // The same group pressed again lets the filter go.
    const narrowed = results(runtime, ['total', 'list']);
    expect(
      runtime.crossFilter('by-province', { province: 'Zhejiang' }).kind,
    ).toBe('cleared');
    await rerun(runtime, narrowed);
    expect(shown(runtime)).toEqual(expected(() => true));
    runtime.dispose();
  });

  it('holds every panel to the board’s fixed scope', async () => {
    const runtime = await openBoard({
      op: 'and',
      children: [{ field: 'status', operator: 'IN', value: ['CREATED'] }],
    });
    // Written in the board's field names and mapped through each panel's
    // bindings, like a filter — but no reader's command lets it go.
    const unpaid = (order: SeededOrder) => statusOf(order) === 'CREATED';
    expect(shown(runtime)).toEqual(expected(unpaid));

    const before = results(runtime, PANELS);
    runtime.setFilterValue('province', ['Jiangsu']);
    await rerun(runtime, before);
    expect(shown(runtime)).toEqual(
      expected(order => unpaid(order) && order.province === 'Jiangsu'),
    );
    runtime.dispose();
  });

  /**
   * A daily board on a clock a day after the orders were made, so its
   * date filter's default 「昨日」 is their day: a trend card with 「近 7 天」
   * of its own, and a total that is no trend card.
   */
  async function openDaily() {
    const clocked = ordersEngine(tenant, {
      now: () => new Date(seeded() + DAY + 12 * 3_600_000),
    });

    const date: DashboardField = {
      name: 'date',
      label: 'Date',
      kind: 'datetime',
      required: true,
      default: { type: 'preset', preset: 'yesterday' },
    };
    const byDay = [
      {
        type: 'DATE_HISTOGRAM',
        field: 'firstEventTime',
        alias: 'day',
        unit: 'DAY',
        timeZone: 'UTC',
      },
    ] satisfies AnalysisViewConfig['groups'];
    const owned = (config: AnalysisViewConfig) => ({
      definitionId: ORDERS,
      config,
    });
    const saved = await clocked.store.create(
      {
        definitionId: BOARD,
        title: 'Daily',
        scope: 'shared',
        config: {
          ...board('unused'),
          fields: [date],
          panels: [
            {
              // 「近 7 天」 of its own, read as of the day the board holds.
              id: 'trend',
              kind: 'view',
              owned: owned({
                ...analysis({
                  groups: byDay,
                  metrics: [{ type: 'COUNT', alias: 'orders' }],
                  chart: {
                    type: 'metric',
                    metric: { metric: 'orders', trend: { x: 'day' } },
                  },
                }),
                filter: {
                  op: 'and',
                  children: [
                    {
                      field: 'firstEventTime',
                      operator: 'BETWEEN',
                      value: { type: 'relative', amount: 7, unit: 'day' },
                    },
                  ],
                },
                sort: [{ alias: 'day', direction: 'ASC' }],
              }),
              bindings: [{ globalField: 'date', panelField: 'firstEventTime' }],
              layout: { x: 0, y: 0, w: 6, h: 3 },
            },
            {
              // Not a trend card: narrowed to the day.
              id: 'total',
              kind: 'view',
              owned: owned(
                analysis({
                  groups: [],
                  metrics: [AMOUNT, { type: 'COUNT', alias: 'orders' }],
                  chart: { type: 'metric', metric: { metric: 'amount' } },
                }),
              ),
              bindings: [{ globalField: 'date', panelField: 'firstEventTime' }],
              layout: { x: 6, y: 0, w: 6, h: 3 },
            },
          ],
        },
      },
      { requestId: `daily-${Math.random()}` },
    );
    const runtime = (await clocked.engine.open(saved.id)) as DashboardRuntime;
    for (const id of ['trend', 'total']) await nextResult(child(runtime, id));
    const read = () => {
      const trend = child(runtime, 'trend').getSnapshot();
      const total = child(runtime, 'total').getSnapshot().result?.data;
      const data = trend.result?.data;
      if (data?.kind !== 'analysis' || total?.kind !== 'analysis')
        throw new Error('unexpected panel results');
      if (data.view.chart?.type !== 'metric')
        throw new Error('expected a metric card');
      return {
        card: data.view.chart,
        window: trend.applied.filter,
        total: total.view.rows[0],
      };
    };
    return { runtime, read };
  }

  /** The card's own 「近 7 天」, read as of `day`: seven whole days. */
  const sevenDaysTo = (day: number) => ({
    op: 'and',
    children: [
      {
        field: 'firstEventTime',
        operator: 'BETWEEN',
        value: {
          type: 'absolute',
          from: new Date(day - 6 * DAY).toISOString(),
          to: new Date(day + DAY - 1).toISOString(),
        },
      },
    ],
  });

  it('anchors a trend card to the one day the board’s date filter holds', async () => {
    const { runtime, read } = await openDaily();

    const { card, window, total } = read();
    expect(window).toEqual(sevenDaysTo(seeded()));
    expect(card.value).toBe(onDay(seeded()).length);
    expect(card.period).toMatchObject({ at: seeded(), unit: 'DAY' });
    expect(card.trend?.[card.trend.length - 1]).toEqual({
      x: seeded(),
      value: onDay(seeded()).length,
    });
    // Any other panel is narrowed to the day.
    expect(total).toMatchObject({
      orders: onDay(seeded()).length,
      amount: sum(onDay(seeded()).map(order => order.total)),
    });

    // 「前天」: the card's window follows the day, the total holds nothing.
    const before = results(runtime, ['trend', 'total']);
    expect(
      runtime.setFilterValue('date', {
        type: 'preset',
        preset: 'dayBeforeYesterday',
      }),
    ).toEqual([]);
    await rerun(runtime, before);
    const dayBefore = read();
    expect(dayBefore.window).toEqual(sevenDaysTo(seeded() - DAY));
    expect(dayBefore.total).toMatchObject({
      orders: onDay(seeded() - DAY).length,
    });
    runtime.dispose();
  });

  // Found by this suite: the trend is filled only between the buckets the
  // server answered, never out to the edges of the card's own window. With
  // orders on the anchored day alone, the card draws one point and has no
  // 「较前一日」, though the day before is a known 0 of a count (D39). Kept
  // as an expected failure until the engine fills to the window; see
  // wow-view-engine/docs/design/todo.md, 「连真 Wow 服务端的端到端」.
  it.fails(
    'compares the anchored day with the one before, over seven whole days',
    async () => {
      const { runtime, read } = await openDaily();
      const { card } = read();
      runtime.dispose();

      expect(card.period).toMatchObject({
        at: seeded(),
        previous: { at: seeded() - DAY, value: onDay(seeded() - DAY).length },
      });
      expect(card.trend?.map(point => [point.x, point.value])).toEqual(
        [6, 5, 4, 3, 2, 1, 0].map(back => [
          seeded() - back * DAY,
          onDay(seeded() - back * DAY).length,
        ]),
      );
    },
  );
});
