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
 * What a result becomes on screen: the columns and rows `projectAnalysis`
 * hands a table, the display metadata each of them carries, and the headline
 * a metric card shows above its sparkline.
 */

import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  projectAnalysis,
  resultSchema,
} from '../src/index.js';
import {
  analysisCapability as capability,
  analysisContext as context,
  analysisDefinition as definition,
  analysisKernelConfig as config,
} from './fixtures/analysis.js';

describe('display metadata', () => {
  const warehouses = [{ value: 'SH', label: 'Shanghai' }];
  const withEnum = () =>
    definition({
      fields: definition().fields.map(field =>
        field.name === 'warehouse'
          ? { ...field, kind: 'enum', options: warehouses }
          : field,
      ),
    });

  // Left to the backend, a day ran midnight to midnight UTC: mid-morning to
  // mid-morning in Shanghai, and no longer the day "today" means.
  it('cuts a date histogram in the engine zone unless the group names one', () => {
    const day = {
      type: 'DATE_HISTOGRAM',
      field: 'createdAt',
      alias: 'day',
      unit: 'DAY',
    } as const;
    const shanghai = { ...context, timeZone: 'Asia/Shanghai' };

    const unnamed = compileAnalysis(
      definition(),
      config({ groups: [day] }),
      builtinFieldKinds,
      shanghai,
    );
    const named = compileAnalysis(
      definition(),
      config({ groups: [{ ...day, timeZone: 'UTC' }] }),
      builtinFieldKinds,
      shanghai,
    );

    expect(unnamed.groupBy?.[0]).toMatchObject({ timeZone: 'Asia/Shanghai' });
    expect(named.groupBy?.[0]).toMatchObject({ timeZone: 'UTC' });
  });

  // The table may show only the count while the chart still groups by the
  // warehouse, so what a chart names its categories by cannot be the table's.
  it('describes every alias for the chart, the ones the table hides too', () => {
    const view = projectAnalysis(
      withEnum(),
      config({ table: { columns: [{ alias: 'orders' }] } }),
      [],
    );

    expect(view.columns.map(column => column.alias)).toEqual(['orders']);
    expect(view.schema?.map(column => column.alias)).toEqual(['wh', 'orders']);
    expect(view.schema?.[0]).toMatchObject({
      kind: 'enum',
      options: warehouses,
    });
  });

  it('tells each column how its values show', () => {
    const view = projectAnalysis(
      withEnum(),
      config({
        layout: 'table',
        groups: [
          { type: 'TERMS', field: 'warehouse', alias: 'wh' },
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'day',
            unit: 'DAY',
            timeZone: 'UTC',
          },
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'month',
            unit: 'MONTH',
          },
        ],
        metrics: [
          { type: 'COUNT', alias: 'orders' },
          { type: 'ANY', alias: 'some', field: 'warehouse' },
          {
            type: 'NUMERIC',
            alias: 'latest',
            function: 'MAX',
            expression: { type: 'FIELD', field: 'createdAt' },
          },
        ],
      }),
      [],
    );
    const column = (alias: string) =>
      view.columns.find(found => found.alias === alias);

    expect(column('wh')).toMatchObject({
      kind: 'enum',
      cell: 'enum',
      options: warehouses,
    });
    expect(column('day')).toMatchObject({
      kind: 'datetime',
      dateUnit: 'DAY',
      timeZone: 'UTC',
    });
    // Cut in the engine's zone, which is the zone it is shown in anyway.
    expect(column('month')).toMatchObject({ dateUnit: 'MONTH' });
    expect(column('month')?.timeZone).toBeUndefined();
    // ANY returns one of the field's values; any other metric is a number,
    // whatever it was computed from.
    expect(column('some')).toMatchObject({ kind: 'enum', options: warehouses });
    expect(column('latest')?.kind).toBeUndefined();
    expect(column('orders')?.kind).toBeUndefined();
  });
});

describe('projectAnalysis', () => {
  const rows = [
    { wh: 'SH', orders: 30 },
    { wh: 'BJ', orders: 10 },
  ];

  it('lists group columns before metric columns', () => {
    expect(resultSchema(config())).toEqual(['wh', 'orders']);
    const view = projectAnalysis(
      definition(),
      config({ layout: 'table' }),
      rows,
    );
    expect(view.columns).toEqual([
      {
        alias: 'wh',
        label: 'Warehouse',
        role: 'group',
        width: undefined,
        pinned: undefined,
        numberFormat: undefined,
        // A group column holds the field's values, so it says how they show.
        kind: 'string',
        cell: 'string',
      },
      {
        alias: 'orders',
        label: 'orders',
        role: 'metric',
        width: undefined,
        pinned: undefined,
        numberFormat: undefined,
      },
    ]);
    expect(view.rows).toEqual(rows);
    expect(view.chart).toBeUndefined();
  });

  it('honours declared table columns and reports totals separately', () => {
    const view = projectAnalysis(
      definition(),
      config({
        layout: 'table',
        table: { columns: [{ alias: 'orders', width: 90 }], totals: true },
      }),
      rows,
      [{ orders: 40 }],
    );
    expect(view.columns.map(column => column.alias)).toEqual(['orders']);
    expect(view.columns[0].width).toBe(90);
    expect(view.totals).toEqual({ orders: 40 });
  });

  it('labels a numeric metric with its source field', () => {
    const view = projectAnalysis(
      definition(),
      config({
        layout: 'table',
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'total',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: { x: 'wh', series: [{ metric: 'total' }] },
        },
      }),
      [],
    );
    expect(view.columns[1]).toMatchObject({
      label: 'Amount',
      numberFormat: { style: 'currency', currency: 'CNY' },
    });
  });

  it('refuses a definition without the analysis capability', () => {
    expect(() =>
      projectAnalysis(
        definition({ analysis: undefined }),
        config({ layout: 'table' }),
        rows,
      ),
    ).toThrow(/no analysis capability/);
  });

  it('labels every metric that reads a field, and element fields too', () => {
    // Only NUMERIC used to be looked up, and only among the root fields, so a
    // p95 read as `p95` and a grouping of `items.sku` read as its alias.
    const withElements = definition({
      fields: [
        ...definition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
        },
      ],
      analysis: {
        ...capability,
        elements: [
          {
            path: 'items',
            aggregations: [
              {
                field: 'sku',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
            ],
          },
        ],
      },
    });
    const view = projectAnalysis(
      withElements,
      config({
        layout: 'table',
        elements: [{ path: 'items' }],
        groups: [{ type: 'TERMS', field: 'items.sku', alias: 'sku' }],
        metrics: [
          {
            type: 'PERCENTILE',
            alias: 'p95',
            expression: { type: 'FIELD', field: 'amount' },
            percentile: 95,
          },
          {
            type: 'DISTINCT_COUNT',
            alias: 'buyers',
            expression: { type: 'FIELD', field: 'amount' },
          },
          { type: 'ANY', alias: 'sample', field: 'amount' },
        ],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: { x: 'sku', series: [{ metric: 'p95' }] },
        },
      }),
      [],
    );
    expect(view.columns.map(column => column.label)).toEqual([
      'SKU',
      'Amount',
      'Amount',
      'Amount',
    ]);
    expect(view.columns[1].numberFormat).toEqual({
      style: 'currency',
      currency: 'CNY',
    });
  });
});

describe('metric card headline', () => {
  it('hands the totals row to the chart', () => {
    // The totals query is the ungrouped aggregation, which is the headline a
    // trend card shows; the grouped rows only draw the sparkline.
    const view = projectAnalysis(
      definition(),
      config({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'month',
            unit: 'MONTH',
          },
        ],
        table: { columns: [], totals: true },
        chart: {
          type: 'metric',
          metric: { metric: 'orders', trend: { x: 'month' } },
        },
      }),
      [
        { month: '2026-08', orders: 40 },
        { month: '2026-09', orders: 20 },
      ],
      [{ orders: 55 }],
    );
    expect(view.totals).toEqual({ orders: 55 });
    expect(view.chart).toMatchObject({ type: 'metric', value: 55 });
  });
});
