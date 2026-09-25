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
 * A derived metric reads the way the analyst says it does (D38): a refund
 * rate as a percent — the ratio itself, 0.259 as 25.9%, never ×100 with
 * 「（%）」 in its name — the average order value as money in the currency
 * its operands are in, or a number with so many decimals. The table, the
 * chart's labels and tooltip, the reading table and the exported file all
 * read the column's format, so saying it once on the metric says it
 * everywhere.
 */

import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  metricMeasures,
  projectAnalysis,
  validateAnalysis,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type DerivedFormat,
} from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { analysisFile } from '../src/ui/analysis/exportOffer.js';
import {
  defaultMessages,
  formatIssue,
  formatMessage,
} from '../src/ui/messages.js';
import type { MessageFormatters } from '../src/ui/MessagesProvider.js';
import { analysisConfig, ordersDefinition } from './fixtures.js';

afterEach(cleanup);

const messages: MessageFormatters = {
  label: (key, params) => formatMessage(defaultMessages, key, params),
  issue: found => formatIssue(defaultMessages, found),
  issues: found =>
    found.map(each => formatIssue(defaultMessages, each)).join(' '),
};

function definition(currency = 'CNY'): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: base.fields.map(field =>
      field.name === 'amount'
        ? { ...field, numberFormat: { style: 'currency', currency } }
        : field,
    ),
    analysis: {
      ...base.analysis!,
      expressions: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
}

const gmv: AnalysisMetric = {
  alias: 'gmv',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const orders: AnalysisMetric = { alias: 'orders', type: 'COUNT' };
const ratio = (format?: DerivedFormat): AnalysisMetric => ({
  alias: 'aov',
  type: 'DERIVED',
  label: 'AOV',
  expression: {
    type: 'BINARY',
    operator: 'DIVIDE',
    left: { type: 'METRIC_REF', metric: 'gmv' },
    right: { type: 'METRIC_REF', metric: 'orders' },
  },
  ...(format ? { format } : {}),
});

const config = (
  format?: DerivedFormat,
  extra: Partial<AnalysisViewConfig> = {},
) =>
  analysisConfig({
    metrics: [gmv, orders, ratio(format)],
    ...extra,
  });

const ROWS = [
  { warehouse: 'W-0', gmv: 1000, orders: 4, aov: 250 },
  { warehouse: 'W-1', gmv: 300, orders: 12, aov: 25 },
];

const columnOf = (format?: DerivedFormat, def = definition()) =>
  projectAnalysis(def, config(format), ROWS).columns.find(
    column => column.alias === 'aov',
  )!;

describe('a derived metric’s format', () => {
  it('is a plain number with two decimals when it says none', () => {
    expect(columnOf().numberFormat).toEqual({
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  });

  it('reads a ratio as a percent, one decimal unless it says more', () => {
    expect(columnOf({ style: 'percent' }).numberFormat).toEqual({
      style: 'percent',
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    });
    expect(columnOf({ style: 'percent', decimals: 2 }).numberFormat).toEqual(
      expect.objectContaining({ maximumFractionDigits: 2 }),
    );
  });

  it('reads money in the currency its operands are in', () => {
    expect(columnOf({ style: 'currency' }).numberFormat).toEqual({
      style: 'currency',
      currency: 'CNY',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    // One it names wins.
    expect(
      columnOf({ style: 'currency', currency: 'USD', decimals: 0 })
        .numberFormat,
    ).toEqual({
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    });
  });

  it('reads a number with the decimals it asks for', () => {
    expect(columnOf({ style: 'number', decimals: 0 }).numberFormat).toEqual({
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    });
  });

  it('puts two rates in percent on one scale, and money apart from counts', () => {
    const measures = metricMeasures(
      [
        gmv,
        orders,
        ratio({ style: 'percent' }),
        { ...ratio({ style: 'percent' }), alias: 'other' },
      ],
      new Map([
        ['amount', { numberFormat: { style: 'currency', currency: 'CNY' } }],
      ]),
    );
    expect(measures.get('aov')).toBe(measures.get('other'));
    expect(measures.get('aov')).not.toBe(measures.get('gmv'));
  });

  it('is written so in the table and the exported file', () => {
    const view = projectAnalysis(
      definition(),
      config({ style: 'percent' }, { table: { columns: [] } }),
      [{ warehouse: 'W-0', gmv: 1000, orders: 4, aov: 0.259 }],
    );
    const file = analysisFile(view, messages, { locale: 'en-US' });
    expect(file.rows[0]).toContain('25.9%');
  });

  it('is written so on the chart’s reading table', () => {
    const cfg = config(
      { style: 'currency' },
      {
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: { x: 'warehouse', series: [{ metric: 'aov' }] },
        },
      },
    );
    const view = projectAnalysis(definition(), cfg, ROWS);
    render(
      <ViewSurface locale="en-US">
        <AnalysisChart
          data={view.chart!}
          spec={cfg.chart}
          columns={view.columns}
        />
      </ViewSurface>,
    );
    const reading = document.querySelector(
      '[data-slot="chart-reading"]',
    )!.textContent!;
    expect(reading).toContain('CN¥250.00');
  });
});

describe('a derived metric’s format as admitted', () => {
  const codes = (format: unknown, def = definition()) =>
    validateAnalysis(def, config(format as DerivedFormat), builtinFieldKinds)
      .filter(found => found.path.includes('format'))
      .map(found => found.code);

  it('takes each of the three styles', () => {
    expect(codes({ style: 'number', decimals: 3 })).toEqual([]);
    expect(codes({ style: 'percent' })).toEqual([]);
    expect(codes({ style: 'currency' })).toEqual([]);
    expect(codes({ style: 'currency', currency: 'USD' })).toEqual([]);
  });

  it('refuses a shape it cannot read', () => {
    expect(codes({ style: 'bytes' })).toEqual([
      'analysis.derived.format-invalid',
    ]);
    expect(codes('percent')).toEqual(['analysis.derived.format-invalid']);
    expect(codes({ style: 'number', decimals: 1.5 })).toEqual([
      'analysis.derived.decimals',
    ]);
    expect(codes({ style: 'number', decimals: 7 })).toEqual([
      'analysis.derived.decimals',
    ]);
    expect(codes({ style: 'currency', currency: 'yuan' })).toEqual([
      'analysis.derived.currency-invalid',
    ]);
  });

  it('asks for the currency where the operands are in none', () => {
    const plain = ordersDefinition({
      analysis: { ...definition().analysis! },
    });
    expect(codes({ style: 'currency' }, plain)).toEqual([
      'analysis.derived.currency-unknown',
    ]);
    expect(codes({ style: 'currency', currency: 'CNY' }, plain)).toEqual([]);
  });

  it('is never sent to Wow', async () => {
    const { compileAnalysis } = await import('../src/index.js');
    const query = compileAnalysis(
      definition(),
      config({ style: 'percent' }),
      builtinFieldKinds,
      { now: new Date(0), timeZone: 'UTC' },
    );
    expect(JSON.stringify(query)).not.toContain('percent');
  });
});
