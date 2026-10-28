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

import {
  AggregationMetricType,
  AggregationFunction,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  pageSummaries,
  projectSummaries,
  validateRecord,
  type DataViewDefinition,
  type Issue,
  type RecordViewConfig,
} from '../src/index.js';

function definition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return {
    id: 'orders',
    title: 'Orders',
    kind: 'data',
    source: 'orders',
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        summary: [AggregationFunction.SUM, AggregationFunction.AVG],
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
      { name: 'warehouse', label: 'Warehouse', kind: 'string', cell: 'status' },
      {
        name: 'createdAt',
        label: 'Created',
        kind: 'datetime',
        sortable: true,
        // A date carries its earliest and its latest, and no maths.
        summary: [AggregationFunction.MIN, AggregationFunction.MAX],
      },
    ],
    record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
    ...overrides,
  };
}

function config(overrides: Partial<RecordViewConfig> = {}): RecordViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'record',
    sort: [],
    pageSize: 20,
    layout: 'table',
    table: { columns: [{ field: 'id' }, { field: 'amount' }] },
    card: { title: 'id', fields: ['amount'] },
    ...overrides,
  };
}

const codes = (issues: Issue[]) =>
  issues.filter(i => i.severity === 'error').map(i => i.code);

describe('projectSummaries', () => {
  const withSummaries = config({
    summaries: [
      { field: 'amount', fn: AggregationFunction.SUM },
      { field: 'amount', fn: AggregationFunction.AVG },
      { field: 'id', fn: AggregationMetricType.COUNT },
    ],
  });

  it('computes page totals from the rows on screen', () => {
    const row = projectSummaries(definition(), withSummaries, {
      scope: 'page',
      rows: [{ amount: 10 }, { amount: 30 }, { amount: null }],
    });
    expect(row.scope).toBe('page');
    expect(row.cells.map(cell => cell.value)).toEqual([40, 20, 3]);
    expect(row.cells[0].label).toBe('Amount');
    expect(row.cells[0].numberFormat).toEqual({
      style: 'currency',
      currency: 'CNY',
    });
  });

  it('reads range totals back by alias', () => {
    const row = projectSummaries(definition(), withSummaries, {
      scope: 'total',
      result: [{ amount_sum: 400, amount_avg: 20, id_count: 20 }],
    });
    expect(row.scope).toBe('total');
    expect(row.cells.map(cell => cell.value)).toEqual([400, 20, 20]);
  });

  it('reports a missing number rather than inventing one', () => {
    expect(
      projectSummaries(definition(), withSummaries, {
        scope: 'total',
        result: [],
      }).cells.map(cell => cell.value),
    ).toEqual([null, null, null]);

    expect(
      projectSummaries(definition(), withSummaries, {
        scope: 'page',
        rows: [{ amount: 'x' }],
      }).cells.map(cell => cell.value),
    ).toEqual([null, null, 1]);
  });

  it('handles min and max and a field the definition lost', () => {
    const row = projectSummaries(
      definition(),
      config({
        summaries: [
          { field: 'amount', fn: AggregationFunction.MIN },
          { field: 'amount', fn: AggregationFunction.MAX },
          { field: 'gone', fn: AggregationFunction.SUM },
        ],
      }),
      { scope: 'page', rows: [{ amount: 5 }, { amount: 9 }] },
    );
    expect(row.cells.map(cell => cell.value)).toEqual([5, 9, null]);
    expect(row.cells[2].label).toBe('gone');
  });

  it('returns an empty row when the config asks for no summary', () => {
    expect(
      projectSummaries(definition(), config(), { scope: 'page', rows: [] })
        .cells,
    ).toEqual([]);
  });
});

/**
 * A date column's earliest and latest.
 *
 * They are two of that column's own cells rather than numbers about it, so
 * the kernel keeps the value the record holds and says how it reads — the
 * footer then formats it the way the column formats that cell. Everything
 * here is about that pair: what the comparison runs on, what comes back, and
 * what the two scopes have to agree about.
 */
describe('a date column summarised', () => {
  const dates = config({
    summaries: [
      { field: 'createdAt', fn: AggregationFunction.MIN },
      { field: 'createdAt', fn: AggregationFunction.MAX },
    ],
  });

  it('picks the earliest and the latest of the rows on screen', () => {
    const row = projectSummaries(definition(), dates, {
      scope: 'page',
      rows: [
        { createdAt: '2026-09-16T01:05:00.000Z' },
        { createdAt: '2026-09-15T02:10:00.000Z' },
        { createdAt: '2026-09-17T08:45:00.000Z' },
        { createdAt: null },
      ],
    });
    // The record's own value, not the instant it was compared as: the
    // column formats it, and a reformatted value is a second reading.
    expect(row.cells.map(cell => cell.value)).toEqual([
      '2026-09-15T02:10:00.000Z',
      '2026-09-17T08:45:00.000Z',
    ]);
    // And it says how it reads, which is how the footer knows not to run it
    // through a number format.
    expect(row.cells.map(cell => cell.cell)).toEqual(['datetime', 'datetime']);
  });

  /**
   * Text compares as text, which is the whole reason the kind's reader is
   * asked. `'2026-09-10'` sorts before `'2026-09-10T00:00:00+08:00'` as a
   * string and after it as an instant — the offset puts that one eight hours
   * earlier — and a string of epoch milliseconds is an instant `Date.parse`
   * cannot read at all.
   */
  it('orders by the instant the value names, whatever shape it came in', () => {
    const shaped = definition({
      fields: [
        ...definition().fields,
        {
          name: 'shipDate',
          label: 'Shipped',
          kind: 'date',
          summary: [AggregationFunction.MIN],
        },
      ],
    });
    const row = projectSummaries(
      shaped,
      config({
        summaries: [
          { field: 'shipDate', fn: AggregationFunction.MIN },
          { field: 'createdAt', fn: AggregationFunction.MAX },
        ],
      }),
      {
        scope: 'page',
        rows: [
          { shipDate: '2026-09-10', createdAt: 1789723315014 },
          { shipDate: '2026-09-10T00:00:00+08:00', createdAt: '1789723315015' },
          { shipDate: 'not a day', createdAt: 'not an instant' },
        ],
      },
    );
    expect(row.cells.map(cell => cell.value)).toEqual([
      '2026-09-10T00:00:00+08:00',
      '1789723315015',
    ]);
    expect(row.cells[0].cell).toBe('date');
  });

  it('reads the aggregated instant back as the source keeps it', () => {
    const iso = projectSummaries(definition(), dates, {
      scope: 'total',
      result: [
        {
          createdAt_min: '2026-09-15T02:10:00.000Z',
          createdAt_max: 1789723315014,
        },
      ],
    });
    // One source answers MIN on a date with an ISO instant and another with
    // epoch milliseconds; both are moments, and neither is rewritten here.
    expect(iso.cells.map(cell => cell.value)).toEqual([
      '2026-09-15T02:10:00.000Z',
      1789723315014,
    ]);
  });

  it('reports a value that is no moment as missing rather than showing it', () => {
    const row = projectSummaries(definition(), dates, {
      scope: 'total',
      result: [{ createdAt_min: 'whenever', createdAt_max: true }],
    });
    expect(row.cells.map(cell => cell.value)).toEqual([null, null]);
  });

  /**
   * The two scopes read one column one way. `pageSummaries` recomputes the
   * page from the cells the executed config named, so a date reduced to an
   * instant there and to a date here is exactly the drift the two functions
   * sit in one file to prevent.
   */
  it('reduces the page from the executed cells the same way', () => {
    const executed = projectSummaries(definition(), dates, {
      scope: 'total',
      result: [{ createdAt_min: 1789723315014 }],
    });
    const page = pageSummaries(executed.cells, [
      { key: 'A', data: { createdAt: '2026-09-16T01:05:00.000Z' } },
      { key: 'B', data: { createdAt: '2026-09-15T02:10:00.000Z' } },
    ]);
    expect(page.scope).toBe('page');
    // Both cells come back from the rows on screen — including the latest,
    // which the aggregation never answered.
    expect(page.cells.map(cell => cell.value)).toEqual([
      '2026-09-15T02:10:00.000Z',
      '2026-09-16T01:05:00.000Z',
    ]);
    expect(page.cells[0].cell).toBe('datetime');
  });

  it('counts rows under a date column rather than dating the count', () => {
    const row = projectSummaries(
      definition(),
      config({
        summaries: [{ field: 'createdAt', fn: AggregationMetricType.COUNT }],
      }),
      { scope: 'page', rows: [{ createdAt: '2026-09-16' }, {}] },
    );
    // A count is a number of rows wherever it is configured, so it names no
    // reading and is not formatted as the column's cells are.
    expect(row.cells[0].value).toBe(2);
    expect(row.cells[0].cell).toBeUndefined();
  });

  it('admits the earliest and the latest, and refuses the maths', () => {
    expect(
      codes(validateRecord(definition(), dates, builtinFieldKinds)),
    ).toEqual([]);
    // Declared or not, a sum of instants answers nothing and an average of
    // them is a moment nothing happened at.
    const declared = definition({
      fields: definition().fields.map(field =>
        field.name === 'createdAt'
          ? {
              ...field,
              summary: [
                AggregationFunction.MIN,
                AggregationFunction.SUM,
                AggregationFunction.AVG,
              ],
            }
          : field,
      ),
    });
    expect(
      codes(
        validateRecord(
          declared,
          config({
            summaries: [
              { field: 'createdAt', fn: AggregationFunction.SUM },
              { field: 'createdAt', fn: AggregationFunction.AVG },
              { field: 'createdAt', fn: AggregationFunction.MIN },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.summary.unsupported', 'record.summary.unsupported']);
  });

  /**
   * A number holding an instant reads as a date under `cell: 'date'`
   * (`FieldCellId`), and the column's summary has to read as that column
   * does: its earliest, formatted as its cells are, rather than its
   * smallest thirteen-digit number.
   */
  it('follows the reading a field borrowed, not only its kind', () => {
    const borrowed = definition({
      fields: [
        ...definition().fields,
        {
          name: 'closedAt',
          label: 'Closed',
          kind: 'number',
          cell: 'date',
          summary: [AggregationFunction.MIN, AggregationFunction.SUM],
        },
      ],
    });
    const asked = config({
      summaries: [{ field: 'closedAt', fn: AggregationFunction.MIN }],
    });
    const row = projectSummaries(borrowed, asked, {
      scope: 'page',
      rows: [{ closedAt: 1789723315014 }, { closedAt: 1689723315014 }],
    });
    expect(row.cells[0].value).toBe(1689723315014);
    expect(row.cells[0].cell).toBe('date');
    expect(codes(validateRecord(borrowed, asked, builtinFieldKinds))).toEqual(
      [],
    );
    expect(
      codes(
        validateRecord(
          borrowed,
          config({
            summaries: [{ field: 'closedAt', fn: AggregationFunction.SUM }],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.summary.unsupported']);
  });
});
