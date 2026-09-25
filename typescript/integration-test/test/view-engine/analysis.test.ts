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
 * Analyses of the example server's orders through the engine's runtime:
 * grouping by value and by date, every kind of metric, the having, derived
 * metrics, the rows past 「前 N 组」 and the split's 「其他」, dense date
 * buckets and a period-to-date comparison — each number added up here from
 * the seeded orders and the creation times the server wrote.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import type {
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  ChartSpec,
  RecordData,
  ViewEngine,
  ViewRuntime,
} from '@ahoo-wang/wow-view-engine';
import {
  ORDERS,
  freshTenant,
  groupBy,
  nextResult,
  ordersEngine,
  seedOrders,
  sum,
  type OrderSeed,
  type SeededOrder,
} from './salesOrders';

const DAY_MS = 86_400_000;

const COUNT: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const AMOUNT: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'amount',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'state.totalAmount' },
};
const PROVINCE: AnalysisGroup = {
  type: 'TERMS',
  field: 'state.address.province',
  alias: 'province',
};

function barOf(config: Pick<AnalysisViewConfig, 'groups' | 'metrics'>) {
  return {
    type: 'bar',
    cartesian: {
      x: config.groups[0].alias,
      series: [{ metric: config.metrics[0].alias }],
    },
  } satisfies ChartSpec;
}

function analysisConfig(
  config: Partial<AnalysisViewConfig> &
    Pick<AnalysisViewConfig, 'groups' | 'metrics'>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    limit: 100,
    layout: 'table',
    table: { columns: [] },
    chart: config.groups.length
      ? barOf(config)
      : { type: 'metric', metric: { metric: config.metrics[0].alias } },
    ...config,
  };
}

let tenant: string;
let engine: ViewEngine;
let orders: SeededOrder[];

beforeAll(async () => {
  tenant = freshTenant('analysis');
  orders = await seedOrders(tenant);
  engine = ordersEngine(tenant).engine;
});

/** Runs an unsaved analysis on `config` and returns its projected view. */
async function run(config: AnalysisViewConfig, on: ViewEngine = engine) {
  const runtime = on.create(ORDERS, {
    title: 'Analysis',
    scope: 'personal',
    config,
  }) as ViewRuntime<AnalysisViewConfig>;
  try {
    const state = await nextResult(runtime);
    const data = state.result?.data;
    if (data?.kind !== 'analysis') throw new Error('expected an analysis');
    expect(state.issues).toEqual([]);
    return data.view;
  } finally {
    runtime.dispose();
  }
}

/** Rows keyed by one alias, each reduced to the aliases asked for. */
function byKey(
  rows: readonly RecordData[],
  key: string,
  aliases: readonly string[],
): Record<string, Record<string, unknown>> {
  return Object.fromEntries(
    rows.map(row => [
      String(row[key]),
      Object.fromEntries(aliases.map(alias => [alias, row[alias]])),
    ]),
  );
}

/** The nearest-rank percentile of `values`: a value the data holds. */
function nearestRank(values: readonly number[], percentile: number): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil((percentile / 100) * sorted.length) - 1];
}

describe('analysis against the example server', () => {
  it('groups by a field with every kind of metric, and totals the whole range', async () => {
    const view = await run(
      analysisConfig({
        groups: [PROVINCE],
        metrics: [
          COUNT,
          AMOUNT,
          {
            type: 'NUMERIC',
            alias: 'average',
            function: 'AVG',
            expression: { type: 'FIELD', field: 'state.totalAmount' },
          },
          {
            type: 'DISTINCT_COUNT',
            alias: 'cities',
            expression: { type: 'FIELD', field: 'state.address.city' },
          },
          {
            type: 'PERCENTILE',
            alias: 'median',
            percentile: 50,
            expression: { type: 'FIELD', field: 'state.totalAmount' },
          },
          {
            type: 'NUMERIC',
            alias: 'paid',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'state.paidAmount' },
          },
          {
            type: 'DERIVED',
            alias: 'unpaid',
            expression: {
              type: 'BINARY',
              operator: 'SUBTRACT',
              left: { type: 'METRIC_REF', metric: 'amount' },
              right: { type: 'METRIC_REF', metric: 'paid' },
            },
          },
        ],
        sort: [{ alias: 'amount', direction: 'DESC' }],
        table: { columns: [], totals: true },
      }),
    );

    const provinces = groupBy(orders, order => order.province);
    const aliases = ['orders', 'amount', 'average', 'cities', 'median'];
    const expected = Object.fromEntries(
      [...provinces].map(([province, group]) => {
        const totals = group.map(order => order.total);
        return [
          province,
          {
            orders: group.length,
            amount: sum(totals),
            average: sum(totals) / group.length,
            cities: new Set(group.map(order => order.city)).size,
            median: nearestRank(totals, 50),
            unpaid: sum(totals) - sum(group.map(paid)),
          },
        ];
      }),
    );
    expect(byKey(view.rows, 'province', [...aliases, 'unpaid'])).toEqual(
      expected,
    );
    // Largest amount first, as the sort asked.
    expect(view.rows.map(row => row.province)).toEqual(
      [...provinces.keys()].sort(
        (a, b) => expected[b].amount - expected[a].amount,
      ),
    );
    expect(view.truncated).toBe(false);

    const totals = orders.map(order => order.total);
    expect(view.totals).toMatchObject({
      orders: orders.length,
      amount: sum(totals),
      average: sum(totals) / orders.length,
      cities: new Set(orders.map(order => order.city)).size,
      median: nearestRank(totals, 50),
      unpaid: sum(totals) - sum(orders.map(paid)),
    });
  });

  it('keeps only the groups the having admits, and says the totals still count them', async () => {
    const provinces = groupBy(orders, order => order.province);
    const amounts = new Map(
      [...provinces].map(([province, group]) => [
        province,
        sum(group.map(order => order.total)),
      ]),
    );
    const view = await run(
      analysisConfig({
        groups: [PROVINCE],
        metrics: [COUNT, AMOUNT],
        having: {
          type: 'CONDITION',
          metric: 'amount',
          operator: 'GT',
          value: 150,
        },
        sort: [{ alias: 'amount', direction: 'DESC' }],
        table: { columns: [], totals: true },
      }),
    );

    expect(byKey(view.rows, 'province', ['amount'])).toEqual(
      Object.fromEntries(
        [...amounts]
          .filter(([, amount]) => amount > 150)
          .map(([province, amount]) => [province, { amount }]),
      ),
    );
    expect(view.narrowed).toBe(true);
    expect(view.totals).toMatchObject({
      orders: orders.length,
      amount: sum(orders.map(order => order.total)),
    });
  });

  it('counts the lines of the expanded items by product', async () => {
    const view = await run(
      analysisConfig({
        elements: [{ path: 'state.items' }],
        groups: [
          { type: 'TERMS', field: 'state.items.productId', alias: 'product' },
        ],
        metrics: [
          { type: 'COUNT', alias: 'lines' },
          {
            type: 'NUMERIC',
            alias: 'quantity',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'state.items.quantity' },
          },
        ],
        sort: [{ alias: 'quantity', direction: 'DESC' }],
      }),
    );

    const lines = orders.flatMap(order => order.items);
    const products = new Map<string, { lines: number; quantity: number }>();
    for (const line of lines) {
      const found = products.get(line.productId) ?? { lines: 0, quantity: 0 };
      products.set(line.productId, {
        lines: found.lines + 1,
        quantity: found.quantity + line.quantity,
      });
    }
    expect(byKey(view.rows, 'product', ['lines', 'quantity'])).toEqual(
      Object.fromEntries(products),
    );
  });

  it.each<[string, 'DAY' | 'WEEK' | 'MONTH', (at: number) => number]>([
    ['day', 'DAY', at => Math.floor(at / DAY_MS) * DAY_MS],
    [
      'week',
      'WEEK',
      at => {
        // Monday is the first day of Wow's week; 1970-01-01 was a Thursday.
        const day = Math.floor(at / DAY_MS);
        return (day - ((day + 3) % 7)) * DAY_MS;
      },
    ],
    [
      'month',
      'MONTH',
      at => {
        const date = new Date(at);
        return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1);
      },
    ],
  ])('buckets the orders by %s of their creation', async (_, unit, bucket) => {
    const view = await run(
      analysisConfig({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'firstEventTime',
            alias: 'created',
            unit,
            timeZone: 'UTC',
          },
        ],
        metrics: [COUNT, AMOUNT],
        sort: [{ alias: 'created', direction: 'ASC' }],
      }),
    );

    const buckets = groupBy(orders, order => bucket(order.createdAt));
    expect(view.rows.map(row => [row.created, row.orders, row.amount])).toEqual(
      [...buckets]
        .sort(([a], [b]) => a - b)
        .map(([at, group]) => [
          at,
          group.length,
          sum(group.map(order => order.total)),
        ]),
    );
  });

  it('shows the top groups, says more exist, and folds a crowded split into 「其他」', async () => {
    const provinces = groupBy(orders, order => order.province);
    const amountOf = (group: readonly SeededOrder[]) =>
      sum(group.map(order => order.total));

    // 「前 N 组」: the two largest provinces, and a probe row that says
    // there are more.
    const top = await run(
      analysisConfig({
        groups: [PROVINCE],
        metrics: [AMOUNT],
        sort: [{ alias: 'amount', direction: 'DESC' }],
        limit: 2,
        table: { columns: [], totals: true },
      }),
    );
    const ranked = [...provinces]
      .map(([province, group]) => [province, amountOf(group)] as const)
      .sort((a, b) => b[1] - a[1]);
    expect(top.rows.map(row => [row.province, row.amount])).toEqual(
      ranked.slice(0, 2),
    );
    expect(top.truncated).toBe(true);
    expect(top.totals).toMatchObject({ amount: amountOf(orders) });

    // Twelve cities split four provinces: seven largest cities drawn, the
    // rest of each province in one 「其他」, measured against a query of the
    // provinces alone.
    const split = await run(
      analysisConfig({
        groups: [
          PROVINCE,
          { type: 'TERMS', field: 'state.address.city', alias: 'city' },
        ],
        metrics: [AMOUNT],
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: {
            x: 'province',
            splitBy: 'city',
            series: [{ metric: 'amount' }],
          },
        },
      }),
    );
    const cities = [...groupBy(orders, order => order.city)]
      .map(([city, group]) => [city, amountOf(group)] as const)
      .sort((a, b) => b[1] - a[1]);
    const kept = new Set(cities.slice(0, 7).map(([city]) => city));
    const chart = split.chart;
    if (chart?.type !== 'cartesian') throw new Error('expected a bar chart');

    expect(
      chart.series
        .filter(series => !series.other)
        .map(series => series.value)
        .sort(),
    ).toEqual([...kept].sort());
    const other = chart.series.find(series => series.other);
    expect(other).toBeDefined();
    expect(
      Object.fromEntries(
        chart.points.map(point => [
          point.x,
          point.values[other?.key ?? ''] ?? null,
        ]),
      ),
    ).toEqual(
      Object.fromEntries(
        [...provinces].map(([province, group]) => [
          province,
          amountOf(group.filter(order => !kept.has(order.city))),
        ]),
      ),
    );

    // A pie keeps its largest slices and adds the rest up into one.
    const pie = await run(
      analysisConfig({
        groups: [{ type: 'TERMS', field: 'state.address.city', alias: 'city' }],
        metrics: [AMOUNT],
        layout: 'chart',
        chart: {
          type: 'pie',
          pie: { category: 'city', value: 'amount', maxSlices: 5 },
        },
      }),
    );
    if (pie.chart?.type !== 'pie') throw new Error('expected a pie');
    expect(
      pie.chart.slices.map(slice => [
        slice.category,
        slice.value,
        !!slice.other,
      ]),
    ).toEqual([
      ...cities.slice(0, 4).map(([city, amount]) => [city, amount, false]),
      [null, sum(cities.slice(4).map(([, amount]) => amount)), true],
    ]);
  });

  it('compares this month so far with the same stretch of last month', async () => {
    const metrics: AnalysisViewConfig['metrics'] = [
      {
        type: 'COUNT',
        alias: 'thisMonth',
        filter: {
          op: 'and',
          children: [
            {
              field: 'firstEventTime',
              operator: 'BETWEEN',
              value: { type: 'preset', preset: 'monthToDate' },
            },
          ],
        },
      },
      {
        type: 'COUNT',
        alias: 'lastMonth',
        filter: {
          op: 'and',
          children: [
            {
              field: 'firstEventTime',
              operator: 'BETWEEN',
              value: { type: 'preset', preset: 'lastMonthToDate' },
            },
          ],
        },
      },
    ];
    const config = analysisConfig({
      groups: [],
      metrics,
      layout: 'chart',
      chart: {
        type: 'metric',
        metric: {
          metric: 'thisMonth',
          compare: { metric: 'lastMonth', mode: 'delta' },
        },
      },
    });

    // Read on two clocks: now, when the orders are this month's, and a
    // month on, when they fall in 「上月同期」 — bar an order made on a day
    // the next month does not reach, which the counts below leave out too.
    const now = Date.now();
    const latest = Math.max(...orders.map(order => order.createdAt));
    const later = new Date(latest);
    later.setUTCDate(1);
    later.setUTCMonth(later.getUTCMonth() + 1);
    later.setUTCDate(
      Math.min(new Date(latest).getUTCDate(), daysInMonth(later)),
    );
    later.setUTCHours(23, 59, 59, 999);

    for (const at of [now, later.getTime()]) {
      const clocked = ordersEngine(tenant, { now: () => new Date(at) });
      const view = await run(config, clocked.engine);
      const thisMonth = count(monthToDate(at));
      const lastMonth = count(lastMonthToDate(at));
      if (view.chart?.type !== 'metric') throw new Error('expected a card');
      expect(view.chart).toMatchObject({
        value: thisMonth,
        compare: { value: lastMonth, delta: thisMonth - lastMonth },
      });
    }
  });
});

describe('dense date buckets against the example server', () => {
  // Two bursts of orders more than two seconds apart: bucketed by the
  // second, the seconds between them hold no order, and a dense histogram
  // answers them with a count of 0 rather than leaving them out.
  const BURSTS: readonly OrderSeed[] = [
    seed('d1', 3),
    seed('d2', 4),
    seed('d3', 5),
    seed('d4', 6),
  ];
  let dense: SeededOrder[];
  let denseEngine: ViewEngine;

  beforeAll(async () => {
    const denseTenant = freshTenant('dense');
    dense = await seedOrders(denseTenant, BURSTS, { d2: 2_100 });
    denseEngine = ordersEngine(denseTenant).engine;
  });

  it('fills every second between the first order and the last', async () => {
    const view = await run(
      analysisConfig({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'firstEventTime',
            alias: 'second',
            unit: 'SECOND',
            timeZone: 'UTC',
            dense: true,
          },
        ],
        metrics: [COUNT],
        sort: [{ alias: 'second', direction: 'ASC' }],
      }),
      denseEngine,
    );

    const seconds = groupBy(dense, order => Math.floor(order.createdAt / 1000));
    const first = Math.min(...seconds.keys());
    const last = Math.max(...seconds.keys());
    const expected: [number, number][] = [];
    for (let at = first; at <= last; at += 1)
      expected.push([at * 1000, seconds.get(at)?.length ?? 0]);

    expect(expected.some(([, orders]) => orders === 0)).toBe(true);
    expect(view.rows.map(row => [row.second, row.orders])).toEqual(expected);
  });
});

function seed(key: string, quantity: number): OrderSeed {
  return {
    key,
    province: 'Zhejiang',
    city: 'Hangzhou',
    detail: `Dense Road ${key}`,
    items: [{ productId: 'p-dense', quantity }],
  };
}

function paid(order: SeededOrder): number {
  if (order.paid === 'full') return order.total;
  return order.paid ?? 0;
}

function daysInMonth(date: Date): number {
  return new Date(
    Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0),
  ).getUTCDate();
}

/** `[from, to]` of 「本月至今」 at `at`, in UTC. */
function monthToDate(at: number): [number, number] {
  const date = new Date(at);
  return [Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1), at];
}

/**
 * `[from, to]` of 「上月同期（至今）」 at `at`, in UTC: last month's first
 * moment to the same moment of it, or its last day when it is shorter.
 */
function lastMonthToDate(at: number): [number, number] {
  const date = new Date(at);
  const start = new Date(
    Date.UTC(date.getUTCFullYear(), date.getUTCMonth() - 1, 1),
  );
  const end = new Date(start);
  end.setUTCDate(Math.min(date.getUTCDate(), daysInMonth(start)));
  end.setUTCHours(
    date.getUTCHours(),
    date.getUTCMinutes(),
    date.getUTCSeconds(),
    date.getUTCMilliseconds(),
  );
  return [start.getTime(), end.getTime()];
}

function count([from, to]: [number, number]): number {
  return orders.filter(
    order => order.createdAt >= from && order.createdAt <= to,
  ).length;
}
