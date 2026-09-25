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
 * A record view over the example server's orders, through the engine's
 * runtime: each kind of condition, the sort, the pages, the totals row and
 * the export, every expectation added up from the seeded orders.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import {
  CSV_BOM,
  DEFAULT_RUNTIME_LIMITS,
  serializeCsv,
  type FilterNode,
  type RecordViewConfig,
  type RecordViewRuntime,
  type ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import {
  ORDERS,
  QueryFailed,
  freshTenant,
  nextResult,
  ordersEngine,
  paidOf,
  seedOrders,
  statusOf,
  sum,
  type SeededOrder,
} from './salesOrders';

const COLUMNS = [
  'aggregateId',
  'state.status',
  'state.address.province',
  'state.address.city',
  'state.totalAmount',
  'state.paidAmount',
];

function recordConfig(
  filter: FilterNode[] = [],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'state.totalAmount', direction: 'DESC' }],
    pageSize: 5,
    layout: 'table',
    summaries: [
      { field: 'state.totalAmount', fn: 'SUM' },
      { field: 'state.totalAmount', fn: 'AVG' },
      { field: 'state.totalAmount', fn: 'MAX' },
      { field: 'state.paidAmount', fn: 'SUM' },
    ],
    table: { columns: COLUMNS.map(field => ({ field })) },
    card: { title: 'aggregateId', fields: [] },
    ...overrides,
  };
}

let tenant: string;
let engine: ViewEngine;
let orders: SeededOrder[];

beforeAll(async () => {
  tenant = freshTenant('record');
  orders = await seedOrders(tenant);
  engine = ordersEngine(tenant).engine;
});

/** Opens an unsaved record view on `config` and waits for its first page. */
async function open(config: RecordViewConfig) {
  const runtime = engine.create(ORDERS, {
    title: 'Orders',
    scope: 'personal',
    config,
  }) as RecordViewRuntime;
  const state = await nextResult(runtime);
  return { runtime, state };
}

function view(state: Awaited<ReturnType<typeof open>>['state']) {
  const data = state.result?.data;
  if (data?.kind !== 'record') throw new Error('expected a record result');
  return data;
}

function ids(state: Awaited<ReturnType<typeof open>>['state']): string[] {
  return view(state).view.rows.map(row => String(row.key));
}

const byTotalDesc = (a: SeededOrder, b: SeededOrder) => b.total - a.total;

/** The ids of the seeded orders `keep` admits, largest total first. */
function expected(keep: (order: SeededOrder) => boolean): string[] {
  return orders
    .filter(keep)
    .sort(byTotalDesc)
    .map(order => order.id);
}

describe('record view against the example server', () => {
  it('pages the orders, largest total first, and counts them all', async () => {
    const { runtime, state } = await open(recordConfig());
    const all = expected(() => true);

    expect(ids(state)).toEqual(all.slice(0, 5));
    expect(view(state).view.paging).toMatchObject({
      mode: 'paged',
      index: 1,
      size: 5,
      total: orders.length,
      hasNext: true,
    });

    runtime.page({ index: 2 });
    const second = await nextResult(runtime, state.result);
    expect(ids(second)).toEqual(all.slice(5, 10));

    runtime.page({ index: 3 });
    const third = await nextResult(runtime, second.result);
    expect(ids(third)).toEqual(all.slice(10));
    expect(view(third).view.paging).toMatchObject({ hasNext: false });
    runtime.dispose();
  });

  it('adds the totals row up over every order, not the page', async () => {
    const { runtime, state } = await open(recordConfig());
    const totals = orders.map(order => order.total);

    const summaries = view(state).summaries;
    expect(summaries?.scope).toBe('total');
    expect(
      summaries?.cells.map(cell => [cell.field, cell.fn, cell.value]),
    ).toEqual([
      ['state.totalAmount', 'SUM', sum(totals)],
      ['state.totalAmount', 'AVG', sum(totals) / totals.length],
      ['state.totalAmount', 'MAX', Math.max(...totals)],
      ['state.paidAmount', 'SUM', sum(orders.map(paidOf))],
    ]);
    runtime.dispose();
  });

  it('sorts by several fields in turn', async () => {
    const { runtime, state } = await open(
      recordConfig([], {
        sort: [
          { field: 'state.address.province', direction: 'ASC' },
          { field: 'state.totalAmount', direction: 'ASC' },
        ],
        pageSize: 5,
      }),
    );
    const sorted = [...orders].sort(
      (a, b) =>
        (a.province < b.province ? -1 : a.province > b.province ? 1 : 0) ||
        a.total - b.total,
    );
    expect(ids(state)).toEqual(sorted.slice(0, 5).map(order => order.id));
    runtime.dispose();
  });

  it.each<[string, FilterNode, (order: SeededOrder) => boolean]>([
    [
      'a text condition',
      { field: 'state.address.city', operator: 'CONTAINS', value: 'zhou' },
      order => order.city.includes('zhou'),
    ],
    [
      'an enum condition',
      {
        field: 'state.address.province',
        operator: 'IN',
        value: ['Zhejiang', 'Anhui'],
      },
      order => ['Zhejiang', 'Anhui'].includes(order.province),
    ],
    [
      'an enum condition on the status the payments left',
      { field: 'state.status', operator: 'IN', value: ['PAID'] },
      order => statusOf(order) === 'PAID',
    ],
    [
      'a number range',
      { field: 'state.totalAmount', operator: 'BETWEEN', value: [50, 120] },
      order => order.total >= 50 && order.total <= 120,
    ],
  ])('narrows the orders by %s', async (_, condition, keep) => {
    const { runtime, state } = await open(recordConfig([condition]));
    const matching = expected(keep);

    expect(matching.length).toBeGreaterThan(0);
    expect(matching.length).toBeLessThan(orders.length);
    expect(ids(state)).toEqual(matching.slice(0, 5));
    expect(view(state).view.paging).toMatchObject({ total: matching.length });
    expect(view(state).summaries?.cells[0].value).toBe(
      sum(orders.filter(keep).map(order => order.total)),
    );
    runtime.dispose();
  });

  it('narrows the orders by a date range between two creation times', async () => {
    // Midway between neighbours, so no bound sits on an order's own moment.
    const byTime = [...orders].sort((a, b) => a.createdAt - b.createdAt);
    const between = (i: number) =>
      Math.floor((byTime[i].createdAt + byTime[i + 1].createdAt) / 2);
    const from = between(2);
    const to = between(8);
    const keep = (order: SeededOrder) =>
      order.createdAt >= from && order.createdAt <= to;

    const { runtime, state } = await open(
      recordConfig([
        {
          field: 'firstEventTime',
          operator: 'BETWEEN',
          value: {
            type: 'absolute',
            from: new Date(from).toISOString(),
            to: new Date(to).toISOString(),
          },
        },
      ]),
    );
    expect(view(state).view.paging).toMatchObject({
      total: orders.filter(keep).length,
    });
    expect(ids(state)).toEqual(expected(keep).slice(0, 5));
    runtime.dispose();
  });

  it('applies an edited condition, and reports a search the backend refuses', async () => {
    const { runtime, state } = await open(recordConfig());

    runtime.edit({
      filter: {
        op: 'and',
        children: [
          {
            field: 'state.address.province',
            operator: 'IN',
            value: ['Shanghai'],
          },
        ],
      },
    });
    runtime.apply();
    const narrowed = await nextResult(runtime, state.result);
    expect(ids(narrowed)).toEqual(
      expected(order => order.province === 'Shanghai'),
    );

    // The example stores snapshots in MongoDB, which has no full-text
    // capability: the server refuses the search, and the view says what it
    // said while the rows it had stay on screen.
    runtime.edit({
      filter: {
        op: 'and',
        children: [{ field: 'search', operator: 'SEARCH', value: 'Lane' }],
      },
    });
    runtime.apply();
    const refused = await nextResult(runtime, narrowed.result).then(
      () => null,
      (error: unknown) => error,
    );
    expect(refused).toBeInstanceOf(QueryFailed);
    expect((refused as QueryFailed).issue).toMatchObject({
      code: 'runtime.query.failed',
      severity: 'error',
      params: {
        reason: expect.stringMatching(
          /state\.address\.detail.*FULL_TEXT_TERMS/,
        ),
      },
    });
    expect(ids(runtime.getSnapshot())).toEqual(ids(narrowed));
    runtime.dispose();
  });

  it('exports every matching order, page by page, as the CSV the table reads', async () => {
    const keep = (order: SeededOrder) => order.province !== 'Shanghai';
    const { runtime, state } = await open(
      recordConfig([
        {
          field: 'state.address.province',
          operator: 'NOT_IN',
          value: ['Shanghai'],
        },
      ]),
    );
    const progress: number[] = [];
    const exported = await runtime.exportRows({
      onProgress: fetched => progress.push(fetched),
    });
    const matching = orders.filter(keep).sort(byTotalDesc);

    // Twelve orders at five a page: three round trips, none capped.
    expect(exported).toMatchObject({ total: matching.length, capped: false });
    expect(progress).toEqual([5, 10, 12]);
    expect(exported.rows.map(row => row.aggregateId)).toEqual(
      matching.map(order => order.id),
    );

    const columns = view(state).view.columns;
    const csv = serializeCsv(exported.rows, columns, value =>
      value === null || value === undefined ? '' : String(value),
    );
    const lines = [
      'Order,Status,Province,City,Total,Paid',
      ...matching.map(order =>
        [
          order.id,
          statusOf(order),
          order.province,
          order.city,
          order.total,
          paidOf(order),
        ].join(','),
      ),
    ];
    expect(csv).toBe(`${CSV_BOM}${lines.join('\r\n')}\r\n`);
    // The export ran beside the view: the page on screen is untouched.
    expect(runtime.getSnapshot().result).toBe(state.result);
    runtime.dispose();
  });
  it('exports under the engine’s default limits, which a Wow server admits', async () => {
    // An export pages at `maxPageSize`, and Wow's HTTP query guard refuses a
    // page of more than 100 rows (`HttpQueryGuard.maxPageSize`): a default
    // above it failed every export against a server left at its defaults.
    const defaults = ordersEngine(tenant, {}, DEFAULT_RUNTIME_LIMITS).engine;
    const runtime = defaults.create(ORDERS, {
      title: 'Orders',
      scope: 'personal',
      config: recordConfig(),
    }) as RecordViewRuntime;
    await nextResult(runtime);

    const exported = await runtime.exportRows();
    expect(exported).toMatchObject({ total: orders.length, capped: false });
    expect(exported.rows.map(row => row.aggregateId)).toEqual(
      expected(() => true),
    );
    runtime.dispose();
  });
});
