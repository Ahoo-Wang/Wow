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
 * The data and the builders of Wow's aggregation TCK, for the in-memory source's suites:
 * `SnapshotQueryBackendSpec` in `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/`,
 * which every backend (MongoDB, Elasticsearch) runs. Same data, same
 * queries, same expected answers; each case keeps the TCK test's name. The
 * lines' times are epoch milliseconds here, where MongoDB stores dates.
 */
import { expect } from 'vitest';
import type {
  AggregationDatePart,
  AggregationDateUnit,
  ComparisonOperator,
} from '@ahoo-wang/wow-client';
import {
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  DerivedExpressionType,
  FilterOperator,
  HavingExpressionType,
  type AggregationElement,
  type AggregationExpression,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type DerivedExpression,
  type FilterExpression,
  type HavingExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '../../src/index.js';
import { memorySource } from '../../src/testing/index.js';

export const ms = (iso: string) => Date.parse(iso);
export const HOUR = 3_600_000;

// ---- The TCK's states (aggregationStateA/B, aggregationAnyNullState, …) ----

export function line(
  productId: string,
  quantity: number,
  amount: number | null,
  createdAt: string,
  discounts: [string, number][],
  extra: RecordData = {},
): RecordData {
  return {
    productId,
    quantity,
    amount,
    createdAt: ms(createdAt),
    discounts: discounts.map(([type, value]) => ({ type, amount: value })),
    ...extra,
  };
}

export const snapshot = (
  aggregateId: string,
  version: number,
  orders: RecordData[],
): RecordData => ({ aggregateId, version, state: { orders } });

export const stateA = snapshot('aggregation-a', 1, [
  {
    status: 'PAID',
    lines: [
      line(
        'alpha',
        1,
        10,
        '2026-01-01T10:00:00Z',
        [
          ['LOYALTY', 1],
          ['PROMO', 2],
        ],
        { samples: [7], productName: 'Alpha' },
      ),
      line('beta', 2, 20, '2026-01-02T10:00:00Z', [['PROMO', 3]], {
        samples: [3, 4],
      }),
    ],
  },
  {
    status: 'CANCELLED',
    lines: [line('gamma', 3, null, '2026-02-01T10:00:00Z', [['LOYALTY', 4]])],
  },
]);

export const stateB = snapshot('aggregation-b', 2, [
  {
    status: 'PAID',
    lines: [
      line('alpha', 4, 30, '2026-01-03T10:00:00Z', [['PROMO', 5]], {
        productName: 'Alpha 2026',
      }),
      line('beta', 2, 20, '2026-01-02T18:00:00Z', [['LOYALTY', 6]]),
      line('delta', 5, 50, '2026-02-02T10:00:00Z', []),
    ],
  },
]);

export const anyNullState = snapshot('aggregation-any-null', 3, [
  {
    status: 'PAID',
    lines: [
      line('alpha', 1, 40, '2026-01-04T10:00:00Z', [], { productName: null }),
    ],
  },
]);

export const STATES = [stateA, stateB];

// ---- Builders, after the Kotlin DSL ----

export const f = (filter: Record<string, unknown>) =>
  filter as FilterExpression;
export const eq = (field: string, value: unknown) =>
  f({ op: FilterOperator.EQ, field, value });
export const isIn = (field: string, values: unknown[]) =>
  f({ op: FilterOperator.IN, field, values });
export const gte = (field: string, value: number) =>
  f({ op: FilterOperator.GTE, field, value });
export const gt = (field: string, value: number) =>
  f({ op: FilterOperator.GT, field, value });
export const lte = (field: string, value: number) =>
  f({ op: FilterOperator.LTE, field, value });

export const field = (name: string): AggregationExpression => ({
  type: AggregationExpressionType.FIELD,
  field: name,
});
export const constant = (value: number): AggregationExpression => ({
  type: AggregationExpressionType.CONSTANT,
  value,
});
export const binary = (
  operator: AggregationExpressionOperator,
  left: AggregationExpression,
  right: AggregationExpression,
): AggregationExpression => ({
  type: AggregationExpressionType.BINARY,
  operator,
  left,
  right,
});
export const { ADD, SUBTRACT, MULTIPLY, DIVIDE } =
  AggregationExpressionOperator;

export const count = (
  alias: string,
  filter?: FilterExpression,
): AggregationMetric =>
  ({ type: AggregationMetricType.COUNT, alias, filter }) as AggregationMetric;
export const numeric =
  (fn: AggregationFunction) =>
  (
    expression: string | AggregationExpression,
    alias: string,
    filter?: FilterExpression,
  ): AggregationMetric => ({
    type: AggregationMetricType.NUMERIC,
    function: fn,
    expression: typeof expression === 'string' ? field(expression) : expression,
    alias,
    ...(filter ? { filter } : {}),
  });
export const sum = numeric(AggregationFunction.SUM);
export const avg = numeric(AggregationFunction.AVG);
export const min = numeric(AggregationFunction.MIN);
export const max = numeric(AggregationFunction.MAX);
export const stddev = numeric(AggregationFunction.STDDEV);
export const variance = numeric(AggregationFunction.VARIANCE);
export const distinct = (
  expression: string | AggregationExpression,
  alias: string,
  filter?: FilterExpression,
): AggregationMetric => ({
  type: AggregationMetricType.DISTINCT_COUNT,
  expression: typeof expression === 'string' ? field(expression) : expression,
  alias,
  ...(filter ? { filter } : {}),
});
export const percentile = (
  expression: string | AggregationExpression,
  p: number,
  alias: string,
  filter?: FilterExpression,
): AggregationMetric => ({
  type: AggregationMetricType.PERCENTILE,
  expression: typeof expression === 'string' ? field(expression) : expression,
  percentile: p,
  alias,
  ...(filter ? { filter } : {}),
});
export const any = (name: string, alias: string): AggregationMetric => ({
  type: AggregationMetricType.ANY,
  field: name,
  alias,
});
export const edge = (
  type: AggregationMetricType.FIRST | AggregationMetricType.LAST,
  name: string,
  alias: string,
  filter?: FilterExpression,
): AggregationMetric => ({
  type,
  field: name,
  alias,
  orderBy: 'createdAt',
  ...(filter ? { filter } : {}),
});
export const ref = (metric: string): DerivedExpression => ({
  type: DerivedExpressionType.METRIC_REF,
  metric,
});
export const lit = (value: number): DerivedExpression => ({
  type: DerivedExpressionType.CONSTANT,
  value,
});
export const op = (
  operator: AggregationExpressionOperator,
  left: DerivedExpression,
  right: DerivedExpression,
): DerivedExpression => ({
  type: DerivedExpressionType.BINARY,
  operator,
  left,
  right,
});
export const derived = (
  alias: string,
  expression: DerivedExpression,
): AggregationMetric => ({
  type: AggregationMetricType.DERIVED,
  alias,
  expression,
});

export const terms = (name: string, alias: string, missingKey?: string) =>
  ({
    type: AggregationGroupType.TERMS,
    field: name,
    alias,
    ...(missingKey ? { missingKey } : {}),
  }) as AggregationGroup;
export const histogram = (name: string, interval: number, alias: string) =>
  ({
    type: AggregationGroupType.HISTOGRAM,
    field: name,
    interval,
    alias,
  }) as AggregationGroup;
export const dateHistogram = (
  unit: AggregationDateUnit,
  alias: string,
  options: { timeZone?: string; dense?: boolean } = {},
): AggregationGroup => ({
  type: AggregationGroupType.DATE_HISTOGRAM,
  field: 'createdAt',
  unit,
  alias,
  ...options,
});
export const datePart = (
  part: AggregationDatePart,
  alias: string,
  options: { timeZone?: string; dense?: boolean } = {},
): AggregationGroup => ({
  type: AggregationGroupType.DATE_PART,
  field: 'createdAt',
  part,
  alias,
  ...options,
});

export const condition = (
  metric: string,
  operator: ComparisonOperator,
  value: number,
): HavingExpression => ({
  type: HavingExpressionType.CONDITION,
  metric,
  operator,
  value,
});

/** `expand("state.orders") { … }` then `expand("lines") { … }`. */
export const lines = (
  orders?: FilterExpression,
  each?: FilterExpression,
): AggregationElement[] => [
  { path: 'state.orders', ...(orders ? { filter: gate(orders) } : {}) },
  { path: 'lines', ...(each ? { filter: gate(each) } : {}) },
];

/** A filter as an element's gate: the TCK's conditions name the element's fields. */
export const gate = (filter: FilterExpression) =>
  filter as AggregationElement['filter'];

export async function ask(
  query: Omit<AggregationQuery, 'metrics'> & { metrics: AggregationMetric[] },
  documents: RecordData[] = STATES,
): Promise<RecordData[]> {
  return memorySource(documents).aggregate(query as AggregationQuery);
}

export const pick = (rows: RecordData[], ...aliases: string[]) =>
  rows.map(row => aliases.map(alias => row[alias]));

export const withinRankBounds = (
  value: unknown,
  sorted: number[],
  p: number,
) => {
  const rank = ((sorted.length - 1) * p) / 100;
  expect(value).toBeGreaterThanOrEqual(sorted[Math.floor(rank)]);
  expect(value).toBeLessThanOrEqual(sorted[Math.ceil(rank)]);
};
