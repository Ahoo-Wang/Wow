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

import { aggregation, filter } from '@ahoo-wang/fetcher-wow';
import { expect, it } from 'vitest';
import type { RecordColumn } from '../../src/contracts/viewModel.js';
import { formatRecordNumber } from '../../src/record/recordValueFormat.js';
import {
  calculateRecordSummary,
  createRecordSummaryQuery,
  readRecordSummaryResult,
} from '../../src/record/recordSummary.js';
import { columns, metrics, metricsFor } from './fixtures.js';

it('calculates and queries metrics without table presentation fields', () => {
  const metrics = [{ id: 'amount', field: 'amount', function: 'SUM' as const }];
  expect(
    calculateRecordSummary([{ amount: 10 }, { amount: 20 }], metrics),
  ).toEqual({ amount: { SUM: 30 } });
  const query = createRecordSummaryQuery(filter.matchAll(), metrics);
  expect(query.metrics).toEqual([
    aggregation.sum(aggregation.field('amount'), 'summary0'),
  ]);
  expect(readRecordSummaryResult([{ summary0: 30 }], metrics)).toEqual({
    amount: { SUM: 30 },
  });
  expect(() =>
    createRecordSummaryQuery(filter.matchAll(), [...metrics, ...metrics]),
  ).toThrow(/重复/);
  expect(() =>
    calculateRecordSummary([], [{ ...metrics[0], id: ' ' }]),
  ).toThrow(/有效/);
});

it('formats numbers by field without changing calculation precision', () => {
  const field = { field: 'amount', label: '金额', type: 'number' as const };
  expect(formatRecordNumber(2025.3333333333333, field)).toBe('2,025.33');
  expect(
    formatRecordNumber(2025.3333333333333, {
      ...field,
      numberFormat: { style: 'currency', currency: 'CNY' },
    }),
  ).toBe('¥2,025.33');
  expect(
    formatRecordNumber(2025.3333333333333, {
      ...field,
      numberFormat: { style: 'currency', currency: 'JPY' },
    }),
  ).toBe('JP¥2,025');
  expect(
    formatRecordNumber(2.5, {
      ...field,
      numberFormat: { maximumFractionDigits: 0 },
    }),
  ).toBe('3');
  expect(
    formatRecordNumber(0.12345, {
      ...field,
      numberFormat: { style: 'percent', maximumFractionDigits: 2 },
    }),
  ).toBe('12.35%');
  expect(
    formatRecordNumber(1234.5, {
      ...field,
      numberFormat: { locale: 'de-DE', minimumFractionDigits: 4 },
    }),
  ).toBe('1.234,5000');
  expect(formatRecordNumber(0, field)).toBe('0');
});

it('keeps multiple metrics on one column distinct with stable query aliases', () => {
  const multiple: RecordColumn[] = [
    {
      id: 'amount',
      kind: 'field',
      field: 'amount',
      summary: ['SUM', 'AVG', 'MIN', 'MAX'],
    },
  ];
  const multipleMetrics = metricsFor(multiple);
  const values = { amount: { SUM: 3, AVG: 1, MIN: -3, MAX: 6 } };
  expect(
    calculateRecordSummary(
      [{ amount: 0 }, { amount: 6 }, { amount: -3 }],
      multipleMetrics,
    ),
  ).toEqual(values);
  const query = createRecordSummaryQuery(filter.matchAll(), multipleMetrics);
  expect(query.metrics).toEqual([
    aggregation.avg(aggregation.field('amount'), 'summary0'),
    aggregation.max(aggregation.field('amount'), 'summary1'),
    aggregation.min(aggregation.field('amount'), 'summary2'),
    aggregation.sum(aggregation.field('amount'), 'summary3'),
  ]);
  expect(
    readRecordSummaryResult(
      [{ summary0: 1, summary1: 6, summary2: -3, summary3: 3 }],
      multipleMetrics,
    ),
  ).toEqual(values);
  expect(
    createRecordSummaryQuery(
      filter.matchAll(),
      metricsFor([
        {
          ...multiple[0],
          kind: 'field',
          field: 'amount',
          summary: ['MAX', 'MIN', 'AVG', 'SUM'],
        },
      ]),
    ),
  ).toEqual(query);
});

it('summarizes visible page records with nulls and real zero kept distinct', () => {
  expect(
    calculateRecordSummary(
      [{ amount: 0 }, { amount: 6 }, { amount: -3 }, { amount: null }, {}],
      metrics,
    ),
  ).toEqual({
    sum: { SUM: 3 },
    avg: { AVG: 1 },
    min: { MIN: -3 },
    max: { MAX: 6 },
  });
  expect(calculateRecordSummary([], metrics)).toEqual({
    sum: { SUM: null },
    avg: { AVG: null },
    min: { MIN: null },
    max: { MAX: null },
  });
  expect(calculateRecordSummary([{ amount: 0 }], metrics)).toMatchObject({
    sum: { SUM: 0 },
    avg: { AVG: 0 },
  });
  expect(() => calculateRecordSummary([{ amount: '12' }], metrics)).toThrow();
  expect(() =>
    calculateRecordSummary([{ amount: Infinity }], metrics),
  ).toThrow();
});

it('builds one ungrouped Wow query with stable safe aliases independent of column order and visibility', () => {
  const predicate = filter.gte('amount', 20);
  const query = createRecordSummaryQuery(predicate, metrics);
  expect(query).toEqual({
    filter: predicate,
    metrics: [
      aggregation.avg(aggregation.field('amount'), 'summary0'),
      aggregation.max(aggregation.field('amount'), 'summary1'),
      aggregation.min(aggregation.field('amount'), 'summary2'),
      aggregation.sum(aggregation.field('amount'), 'summary3'),
    ],
  });
  expect(
    createRecordSummaryQuery(
      predicate,
      metricsFor(
        [...columns]
          .reverse()
          .map(column => ({ ...column, visible: false, width: 90 })),
      ),
    ),
  ).toEqual(query);
});

it('validates the complete aggregate response instead of substituting page totals or zero', () => {
  expect(
    readRecordSummaryResult(
      [{ summary0: 1, summary1: 6, summary2: -3, summary3: 3 }],
      metrics,
    ),
  ).toEqual({
    avg: { AVG: 1 },
    max: { MAX: 6 },
    min: { MIN: -3 },
    sum: { SUM: 3 },
  });
  for (const value of [
    [],
    [{}],
    [{ summary0: Infinity }],
    [{ summary1: -1 }],
    [{ summary0: 1 }, { summary0: 2 }],
  ])
    expect(() => readRecordSummaryResult(value, metrics)).toThrow();
  expect(
    readRecordSummaryResult(
      [
        {
          summary0: null,
          summary1: null,
          summary2: null,
          summary3: null,
        },
      ],
      metrics,
    ),
  ).toEqual({
    avg: { AVG: null },
    max: { MAX: null },
    min: { MIN: null },
    sum: { SUM: null },
  });
});
