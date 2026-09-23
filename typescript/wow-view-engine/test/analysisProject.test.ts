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
  metricFormat,
  metricFunctionOf,
  projectAnalysis,
  type AnalysisFunction,
  type AnalysisMetric,
  metricReferenceText,
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

  // The table's order is whatever it was dragged into; the chart and the
  // reading name things in the question's order, so they read the schema.
  it('describes every alias for the chart in the order the question asks', () => {
    const view = projectAnalysis(
      withEnum(),
      config({ table: { columns: [{ alias: 'orders' }] } }),
      [],
    );

    expect(view.columns.map(column => column.alias)).toEqual(['orders', 'wh']);
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
    // ANY returns one of the field's values, and so does MAX: the latest of
    // a datetime is a datetime, never thirteen digits of epoch milliseconds
    // (production review). A count is a number, whatever it counted.
    expect(column('some')).toMatchObject({ kind: 'enum', options: warehouses });
    expect(column('latest')).toMatchObject({
      kind: 'datetime',
      cell: 'datetime',
      fn: 'MAX',
    });
    expect(column('orders')?.kind).toBeUndefined();
  });

  // A number histogram's key is only the band's lower bound: the column
  // carries the interval, as a date histogram's carries its unit, so the
  // screen can say the band — and the field's format, to say it in.
  it('tells a number histogram column how wide its bands are', () => {
    const view = projectAnalysis(
      definition(),
      config({
        layout: 'table',
        groups: [
          { type: 'HISTOGRAM', field: 'amount', alias: 'band', interval: 500 },
          { type: 'TERMS', field: 'warehouse', alias: 'wh' },
        ],
        metrics: [{ type: 'COUNT', alias: 'orders' }],
      }),
      [],
    );
    const column = (alias: string) =>
      view.columns.find(found => found.alias === alias);

    expect(column('band')).toMatchObject({
      interval: 500,
      numberFormat: { style: 'currency', currency: 'CNY' },
    });
    expect(column('band')?.dateUnit).toBeUndefined();
    expect(column('wh')?.interval).toBeUndefined();
    expect(column('orders')?.interval).toBeUndefined();
  });
});

describe('projectAnalysis', () => {
  const rows = [
    { wh: 'SH', orders: 30 },
    { wh: 'BJ', orders: 10 },
  ];

  it('lists group columns before metric columns', () => {
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
        numberFormat: undefined,
        // A group column holds the field's values, so it says how they show.
        kind: 'string',
        cell: 'string',
      },
      {
        alias: 'orders',
        label: 'orders',
        role: 'metric',
        // Which summary it is, so the header can say it; a count of records
        // is a whole number in nobody's currency.
        fn: 'COUNT',
        width: undefined,
        numberFormat: { maximumFractionDigits: 0 },
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
    expect(view.columns.map(column => column.alias)).toEqual(['orders', 'wh']);
    expect(view.columns[0].width).toBe(90);
    expect(view.columns[1].width).toBeUndefined();
    expect(view.totals).toEqual({ orders: 40 });
  });

  // 2026-09-23 audit P0-1. The list is an override of order and width, not
  // an allow-list: what the tray added ran, so it is on screen — otherwise a
  // second dimension draws two 华东 rows with no column telling them apart.
  describe('table.columns orders and sizes, never hides', () => {
    const grown = (columns: { alias: string; width?: number }[]) =>
      projectAnalysis(
        definition(),
        config({
          layout: 'table',
          groups: [
            { type: 'TERMS', field: 'warehouse', alias: 'wh' },
            {
              type: 'DATE_HISTOGRAM',
              field: 'createdAt',
              alias: 'month',
              unit: 'MONTH',
            },
          ],
          metrics: [
            { type: 'COUNT', alias: 'orders' },
            {
              type: 'NUMERIC',
              alias: 'amount_1',
              function: 'SUM',
              expression: { type: 'FIELD', field: 'amount' },
            },
          ],
          table: { columns },
        }),
        [],
      );
    const aliases = (view: ReturnType<typeof grown>) =>
      view.columns.map(column => column.alias);

    it('appends a metric the list does not name', () => {
      expect(
        aliases(
          grown([{ alias: 'wh' }, { alias: 'month' }, { alias: 'orders' }]),
        ),
      ).toEqual(['wh', 'month', 'orders', 'amount_1']);
    });

    it('appends a dimension the list does not name', () => {
      expect(
        aliases(
          grown([{ alias: 'wh' }, { alias: 'orders' }, { alias: 'amount_1' }]),
        ),
      ).toEqual(['wh', 'orders', 'amount_1', 'month']);
    });

    // After the listed ones, as the question names them: dimensions first,
    // each set in the config's order — the order an empty list draws.
    it('appends groups before metrics, in the config order', () => {
      expect(aliases(grown([{ alias: 'orders' }]))).toEqual([
        'orders',
        'wh',
        'month',
        'amount_1',
      ]);
    });

    it('keeps the listed order and widths', () => {
      const view = grown([
        { alias: 'amount_1', width: 140 },
        { alias: 'month' },
        { alias: 'wh', width: 90 },
      ]);
      expect(aliases(view)).toEqual(['amount_1', 'month', 'wh', 'orders']);
      expect(view.columns.map(column => column.width)).toEqual([
        140,
        undefined,
        90,
        undefined,
      ]);
    });

    // Admission refuses both, but the projection is exported: a host may
    // hand it a config nothing admitted, and neither may cost a column or
    // draw one twice.
    it('skips a listed alias the result does not hold and draws a repeat once', () => {
      expect(
        aliases(
          grown([{ alias: 'gone' }, { alias: 'month' }, { alias: 'month' }]),
        ),
      ).toEqual(['month', 'wh', 'orders', 'amount_1']);
    });

    it('draws the config order when nothing is listed', () => {
      expect(aliases(grown([]))).toEqual(['wh', 'month', 'orders', 'amount_1']);
    });
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
    // p95 read as `p95` and a grouping of `items.sku` read as its alias. With
    // an expansion the counting unit is one item, so every dimension and
    // every metric reads that item's own fields.
    const withElements = definition({
      fields: [
        ...definition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' },
            {
              name: 'amount',
              label: 'Amount',
              kind: 'number',
              numberFormat: { style: 'currency', currency: 'CNY' },
            },
          ],
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
              {
                field: 'amount',
                groups: [],
                functions: [],
                distinctCount: true,
                percentile: true,
                any: true,
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
            expression: { type: 'FIELD', field: 'items.amount' },
            percentile: 95,
          },
          {
            type: 'DISTINCT_COUNT',
            alias: 'buyers',
            expression: { type: 'FIELD', field: 'items.amount' },
          },
          { type: 'ANY', alias: 'sample', field: 'items.amount' },
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

/**
 * A field's `numberFormat` describes one stored value; an aggregate of that
 * field is a different number and reads by what the aggregate *is* (K5).
 * These are the rules the table, the totals row, the axes, the tooltips and
 * the metric card all print through.
 */
describe('metricFormat', () => {
  const money = { style: 'currency', currency: 'CNY' } as const;
  const field = { numberFormat: money };
  const count: AnalysisMetric = { type: 'COUNT', alias: 'm' };
  const distinct: AnalysisMetric = {
    type: 'DISTINCT_COUNT',
    alias: 'm',
    expression: { type: 'FIELD', field: 'amount' },
  };
  const percentile: AnalysisMetric = {
    type: 'PERCENTILE',
    alias: 'm',
    percentile: 95,
    expression: { type: 'FIELD', field: 'amount' },
  };
  const any: AnalysisMetric = { type: 'ANY', alias: 'm', field: 'amount' };
  const derived: AnalysisMetric = {
    type: 'DERIVED',
    alias: 'm',
    expression: { type: 'METRIC_REF', metric: 'orders' },
  };
  const numeric = (fn: AnalysisFunction): AnalysisMetric => ({
    type: 'NUMERIC',
    alias: 'm',
    function: fn,
    expression: { type: 'FIELD', field: 'amount' },
  });
  const TWO = { minimumFractionDigits: 2, maximumFractionDigits: 2 };

  it('counts records as whole numbers, in no currency at all', () => {
    expect(metricFormat(count, field)).toEqual({ maximumFractionDigits: 0 });
    expect(metricFormat(distinct, field)).toEqual({
      maximumFractionDigits: 0,
    });
  });

  it('gives a computed number two decimals, its currency kept', () => {
    expect(metricFormat(numeric('AVG'), field)).toEqual({ ...money, ...TWO });
    // An integer column's average is not an integer, whatever the column is.
    expect(
      metricFormat(numeric('AVG'), {
        numberFormat: { maximumFractionDigits: 0 },
      }),
    ).toEqual(TWO);
    for (const fn of ['STDDEV', 'VARIANCE'] as const)
      expect(metricFormat(numeric(fn))).toEqual(TWO);
  });

  it('leaves a value of the field reading as the field does', () => {
    for (const fn of ['SUM', 'MIN', 'MAX'] as const)
      expect(metricFormat(numeric(fn), field)).toEqual(money);
    expect(metricFormat(percentile, field)).toEqual(money);
    expect(metricFormat(any, field)).toEqual(money);
    expect(metricFormat(numeric('SUM'))).toBeUndefined();
  });

  it('gives a derived metric a plain number: it belongs to no field', () => {
    expect(metricFormat(derived, field)).toEqual(TWO);
  });

  it('names which summary a metric is, for the header to say', () => {
    expect(metricFunctionOf(count)).toBe('COUNT');
    expect(metricFunctionOf(numeric('AVG'))).toBe('AVG');
    expect(metricFunctionOf(percentile)).toBe('PERCENTILE');
  });
});

describe('a derived column', () => {
  // A derived metric is titled as its author says it; a reference to the
  // record count is a token here, because the kernel holds no catalogue,
  // and `columnTitle` words it (test/display.test.ts「columnTitle」).
  it('marks the metrics it reads, each with its summary', () => {
    const base = config();
    const view = projectAnalysis(
      definition(),
      config({
        metrics: [
          { ...base.metrics[0] },
          {
            type: 'NUMERIC',
            alias: 'total',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
          {
            type: 'DERIVED',
            alias: 'avg',
            expression: {
              type: 'BINARY',
              operator: 'DIVIDE',
              left: { type: 'METRIC_REF', metric: 'total' },
              right: { type: 'METRIC_REF', metric: base.metrics[0].alias },
            },
          },
        ],
        table: { columns: [] },
      }),
      [],
    );
    const derived = view.schema?.find(column => column.alias === 'avg');
    // Each operand is marked with its summary for the UI to word, the
    // count included: the kernel holds no catalogue.
    expect(derived?.label).toBe(
      `${metricReferenceText('SUM', 'Amount')} ÷ ${metricReferenceText('COUNT', base.metrics[0].alias)}`,
    );
    expect(derived?.fn).toBe('DERIVED');
  });
});

describe('a named column', () => {
  // D20 显示名: the name the analyst gave is the whole title, so a header
  // does not append the summary to it; how the numbers read is unchanged.
  it('is titled by its name alone, with its summary kept for the numbers', () => {
    const base = config();
    const view = projectAnalysis(
      definition(),
      config({
        groups: [{ ...base.groups[0]!, label: '仓库' }],
        metrics: [
          { ...base.metrics[0], label: '单数' },
          {
            type: 'NUMERIC',
            alias: 'total',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        table: { columns: [] },
      }),
      [],
    );

    expect(view.schema?.slice(0, 2).map(column => column.label)).toEqual([
      '仓库',
      '单数',
    ]);
    expect(view.schema?.map(column => column.named)).toEqual([
      true,
      true,
      undefined,
    ]);
    expect(view.schema?.[1]).toMatchObject({ fn: 'COUNT' });
  });
});

/**
 * The probe row, read back (D20 Ⅷ). The query asked for one row more than the
 * limit, so a result longer than the limit is more groups existing — a fact,
 * not the old guess at a table that came back exactly full — and the extra
 * row is dropped rather than shown.
 */
describe('the probe row read back', () => {
  /** One aggregation row per warehouse, as many as the caller asks for. */
  const rows = (count: number) =>
    Array.from({ length: count }, (_unused, index) => ({
      wh: `W-${index}`,
      orders: count - index,
    }));

  const project = (limit: number, count: number, capped?: number) =>
    projectAnalysis(
      capped === undefined
        ? definition()
        : definition({
            analysis: { ...capability, limits: { maxLimit: capped } },
          }),
      config({ limit, layout: 'table' }),
      rows(count),
    );

  it('knows there are more groups when the probe row came back', () => {
    const view = project(2, 3);

    expect(view.truncated).toBe(true);
    // The reader asked for two groups and gets two: the third row was a
    // question, not a group.
    expect(view.rows).toHaveLength(2);
    expect(view.atLimit).toBeUndefined();
  });

  it('knows there are none when it did not', () => {
    const view = project(2, 2);

    expect(view.truncated).toBe(false);
    expect(view.rows).toHaveLength(2);
    expect(view.atLimit).toBeUndefined();
  });

  it('says nothing about an empty result', () => {
    const view = project(2, 0);

    expect(view.truncated).toBe(false);
    expect(view.atLimit).toBeUndefined();
  });

  /**
   * On the ceiling nothing could be asked for, so the old ambiguous signal is
   * all there is and it is still reported as a maybe.
   */
  it('falls back to "exactly full" where no probe was possible', () => {
    const view = project(2, 2, 2);

    expect(view.truncated).toBe(false);
    expect(view.atLimit).toBe(2);
    expect(view.rows).toHaveLength(2);
  });

  it('says nothing on the ceiling when the result came back short', () => {
    const view = project(3, 2, 3);

    expect(view.truncated).toBe(false);
    expect(view.atLimit).toBeUndefined();
  });

  /**
   * An analysis with no grouping asks one question and gets one row, so a
   * limit of one is met by every successful answer and cuts nothing short.
   */
  it('says nothing about an analysis that has no grouping to cut short', () => {
    const view = projectAnalysis(
      definition(),
      config({ groups: [], sort: [], limit: 1, layout: 'table' }),
      [{ orders: 6 }],
    );

    expect(view.rows).toHaveLength(1);
    expect(view.truncated).toBe(false);
    expect(view.atLimit).toBeUndefined();
  });

  /**
   * `validateAnalysis` refuses each of these, but the projection is exported
   * and a host may run it over a config nothing admitted. Without a usable
   * limit there is no probe row to tell from a group, so nothing is claimed
   * and nothing is dropped.
   */
  it.each([
    ['a limit of zero', 0],
    ['a fractional limit', 2.5],
    ['an infinite limit', Number.POSITIVE_INFINITY],
  ] as const)('says nothing under %s', (_name, limit) => {
    const view = project(limit, 3);

    expect(view.truncated).toBe(false);
    expect(view.atLimit).toBeUndefined();
    expect(view.rows).toHaveLength(3);
  });

  /**
   * The chart is shaped from the rows that survive the cut: a pie whose
   * slices included the probe row would show a share of a group the table
   * below it does not list.
   */
  it('keeps the probe row out of the chart as well', () => {
    const view = projectAnalysis(
      definition(),
      config({ limit: 2, layout: 'chart' }),
      rows(3),
    );

    expect(view.chart?.type).toBe('cartesian');
    expect(
      view.chart?.type === 'cartesian' ? view.chart.points : [],
    ).toHaveLength(2);
  });
});

/**
 * The whole, beside the groups. A metric card over a trend asks its
 * ungrouped question too (`asksForWhole`), and headlines that answer rather
 * than the buckets added up — which are only the groups that fit the limit
 * (the 2026-09-23 audit).
 */
describe('the whole beside the groups', () => {
  const day = {
    type: 'DATE_HISTOGRAM',
    field: 'createdAt',
    alias: 'day',
    unit: 'DAY',
  } as const;
  // Three days asked for, a fourth came back as the probe: the grouping
  // goes on past what is shown.
  const buckets = [
    { day: '2026-09-01', orders: 5 },
    { day: '2026-09-02', orders: 7 },
    { day: '2026-09-03', orders: 4 },
    { day: '2026-09-04', orders: 9 },
  ];
  const card = (table = { columns: [] as [] }) =>
    projectAnalysis(
      definition(),
      config({
        groups: [day],
        limit: 3,
        sort: [{ alias: 'day', direction: 'ASC' }],
        table,
        chart: {
          type: 'metric',
          metric: { metric: 'orders', trend: { x: 'day' } },
        },
      }),
      buckets,
      [{ orders: 120 }],
    );

  it('headlines the whole, not the buckets that fit the limit', () => {
    const view = card();

    expect(view.truncated).toBe(true);
    expect(view.chart).toMatchObject({ type: 'metric', value: 120 });
    expect(view.overall).toEqual({ orders: 120 });
  });

  it('draws no totals row unless the table asks for one', () => {
    expect(card().totals).toBeUndefined();
    expect(card({ columns: [], totals: true } as never).totals).toEqual({
      orders: 120,
    });
  });
});
