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

import { queryField } from '../field.js';
import type { ElementFilterExpression } from '../filter/index.js';
import { requireElementScopedFilter } from '../filter/scope.js';
import { admitAggregationQuery } from './admit.js';
import { derivedExpressionDsl, type DerivedExpressionDsl } from './derived.js';
import { havingDsl } from './having.js';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  type AggregationElement,
  type AggregationExpression,
  type AggregationMetricOptions,
  type AggregationQuery,
  type AnyAggregationMetric,
  type BinaryAggregationExpression,
  type ConstantAggregationExpression,
  type CountAggregationMetric,
  type DateHistogramAggregationGroup,
  type DateHistogramAggregationOptions,
  type DatePartAggregationGroup,
  type DatePartAggregationOptions,
  type DerivedAggregationMetric,
  type DerivedExpression,
  type DistinctCountAggregationMetric,
  type FieldAggregationExpression,
  type HistogramAggregationGroup,
  type HistogramAggregationOptions,
  type NumericAggregationMetric,
  type PercentileAggregationMetric,
  type PercentileAggregationOptions,
  type TermsAggregationGroup,
  type TermsAggregationOptions,
} from './types.js';

function aggregationField<FIELDS extends string>(field: FIELDS): FIELDS {
  return queryField(field);
}

function aggregationAlias(alias: string): string {
  aggregationField(alias);
  if (alias.includes('.')) {
    throw new TypeError('aggregation alias must contain one segment.');
  }
  if (alias.startsWith('__wow')) {
    throw new TypeError(
      'aggregation alias must not use the reserved __wow prefix.',
    );
  }
  return alias;
}

function binary<FIELDS extends string>(
  operator: AggregationExpressionOperator,
  left: AggregationExpression<FIELDS>,
  right: AggregationExpression<FIELDS>,
): BinaryAggregationExpression<FIELDS> {
  return { type: AggregationExpressionType.BINARY, operator, left, right };
}

function numeric<FIELDS extends string>(
  fn: AggregationFunction,
  expression: AggregationExpression<FIELDS>,
  alias: string,
  { filter: predicate }: AggregationMetricOptions<FIELDS> = {},
): NumericAggregationMetric<FIELDS> {
  return {
    type: AggregationMetricType.NUMERIC,
    ...(predicate === undefined ? {} : { filter: predicate }),
    function: fn,
    expression,
    alias: aggregationAlias(alias),
  };
}

/**
 * Builders of an `AggregationQuery`: its elements, groups, metrics and the
 * arithmetic expressions metrics compute over. Each builder checks its own
 * part and throws a `TypeError` naming what is wrong; {@link aggregation.query}
 * checks the parts against each other.
 *
 * Every group and metric takes its target first, its alias second, and any
 * further options as a trailing object.
 */
export const aggregation = {
  /**
   * Aggregates over the elements of an array field instead of the root
   * documents. `{ path, filter? }`.
   *
   * @param path - The array field, a query field path.
   * @param predicate - Keeps only the elements that match; may not contain
   *   root filters (id, owner, tenant, space, deletion, search).
   * @throws TypeError when `path` is not a valid field path or `predicate`
   *   contains a root filter.
   * @example
   * ```typescript
   * aggregation.element('items', filter.gt('quantity', 0));
   * ```
   */
  element(
    path: string,
    predicate?: ElementFilterExpression,
  ): AggregationElement {
    const validPath = aggregationField(path);
    if (predicate === undefined) return { path: validPath };
    requireElementScopedFilter(predicate, 'Aggregation element filter');
    return { path: validPath, filter: predicate };
  },
  /**
   * The value of a field, as an operand. `{ type: 'FIELD', field }`.
   *
   * @throws TypeError when `field` is not a valid field path.
   * @example
   * ```typescript
   * aggregation.sum(aggregation.field('state.amount'), 'amount');
   * ```
   */
  field<FIELDS extends string>(
    field: FIELDS,
  ): FieldAggregationExpression<FIELDS> {
    return {
      type: AggregationExpressionType.FIELD,
      field: aggregationField(field),
    };
  },
  /**
   * A number, as an operand. `{ type: 'CONSTANT', value }`.
   *
   * @throws TypeError when `value` is not finite.
   * @example
   * ```typescript
   * aggregation.multiply(aggregation.field('price'), aggregation.constant(100));
   * ```
   */
  constant(value: number): ConstantAggregationExpression {
    if (!Number.isFinite(value)) {
      throw new TypeError('aggregation constant must be finite.');
    }
    return { type: AggregationExpressionType.CONSTANT, value };
  },
  /** `left + right`. `{ type: 'BINARY', operator: 'ADD', left, right }`. */
  add: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.ADD, left, right),
  /** `left - right`. `{ type: 'BINARY', operator: 'SUBTRACT', left, right }`. */
  subtract: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.SUBTRACT, left, right),
  /** `left * right`. `{ type: 'BINARY', operator: 'MULTIPLY', left, right }`. */
  multiply: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.MULTIPLY, left, right),
  /** `left / right`. `{ type: 'BINARY', operator: 'DIVIDE', left, right }`. */
  divide: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.DIVIDE, left, right),
  /**
   * Groups by each distinct value of a field.
   * `{ type: 'TERMS', field, alias, missingKey? }`.
   *
   * @param field - The field to group by.
   * @param alias - The name of the group column in the result rows.
   * @param options.missingKey - The key of the rows without a value.
   * @throws TypeError when `field` or `alias` is invalid, or `missingKey` is
   *   blank.
   * @example
   * ```typescript
   * aggregation.terms('state.status', 'status', { missingKey: 'NONE' });
   * ```
   */
  terms<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    { missingKey }: TermsAggregationOptions = {},
  ): TermsAggregationGroup<FIELDS> {
    if (
      missingKey !== undefined &&
      (typeof missingKey !== 'string' || !missingKey.trim())
    ) {
      throw new TypeError('terms missingKey must not be blank.');
    }
    return {
      type: AggregationGroupType.TERMS,
      ...(missingKey === undefined ? {} : { missingKey }),
      field: aggregationField(field),
      alias: aggregationAlias(alias),
    };
  },
  /**
   * Groups a number field into buckets of equal width.
   * `{ type: 'HISTOGRAM', field, interval, alias }`.
   *
   * @param field - The number field to bucket.
   * @param alias - The name of the bucket column in the result rows.
   * @param options.interval - The bucket width.
   * @throws TypeError when `interval` is not finite and greater than 0, or
   *   `field` or `alias` is invalid.
   * @example
   * ```typescript
   * aggregation.histogram('state.amount', 'amountBand', { interval: 100 });
   * ```
   */
  histogram<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    { interval }: HistogramAggregationOptions,
  ): HistogramAggregationGroup<FIELDS> {
    if (!Number.isFinite(interval) || interval <= 0) {
      throw new TypeError(
        'histogram interval must be finite and greater than 0.',
      );
    }
    return {
      type: AggregationGroupType.HISTOGRAM,
      field: aggregationField(field),
      interval,
      alias: aggregationAlias(alias),
    };
  },
  /**
   * Groups a time field into calendar buckets.
   * `{ type: 'DATE_HISTOGRAM', field, unit, alias, timeZone, dense? }`.
   *
   * @param field - The time field (epoch milliseconds) to bucket.
   * @param alias - The name of the bucket column in the result rows.
   * @param options.unit - The calendar unit of a bucket.
   * @param options.timeZone - The zone buckets are cut in. Defaults to `UTC`.
   * @param options.dense - Whether the empty buckets of the range are filled
   *   in.
   * @throws TypeError when `unit` is not an `AggregationDateUnit`,
   *   `timeZone` is blank, `dense` is not a boolean, or `field` or `alias` is
   *   invalid.
   * @example
   * ```typescript
   * aggregation.dateHistogram('createTime', 'day', {
   *   unit: AggregationDateUnit.DAY,
   *   timeZone: 'Asia/Shanghai',
   * });
   * ```
   */
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    { unit, timeZone = 'UTC', dense }: DateHistogramAggregationOptions,
  ): DateHistogramAggregationGroup<FIELDS> {
    if (dense !== undefined && typeof dense !== 'boolean') {
      throw new TypeError('date histogram dense must be boolean.');
    }
    if (!Object.values(AggregationDateUnit).includes(unit)) {
      throw new TypeError('date histogram unit is invalid.');
    }
    if (typeof timeZone !== 'string' || !timeZone.trim()) {
      throw new TypeError('date histogram timeZone cannot be blank.');
    }
    return {
      type: AggregationGroupType.DATE_HISTOGRAM,
      ...(dense === undefined ? {} : { dense }),
      field: aggregationField(field),
      unit,
      alias: aggregationAlias(alias),
      timeZone,
    };
  },
  /**
   * Groups a time field by one calendar part of each instant, such as the
   * weekday or the hour, so records from different days share a bucket.
   * `{ type: 'DATE_PART', field, part, alias, timeZone, dense? }`.
   *
   * The group's keys in the result rows are integers: `DAY_OF_WEEK` is the
   * ISO weekday, 1 (Monday) to 7 (Sunday); `DAY_OF_MONTH` 1 to 31;
   * `HOUR_OF_DAY` 0 to 23 on the wall clock of `timeZone`; `MONTH_OF_YEAR`
   * 1 to 12. With `dense`, every key of that domain comes back, those
   * without records carrying each metric's empty value; `dense` needs the
   * group to be the query's only one. Wow 9.2 and later.
   *
   * @param field - The time field (epoch milliseconds) to read the part of.
   * @param alias - The name of the part column in the result rows.
   * @param options.part - The calendar part to group by.
   * @param options.timeZone - The zone the part is read in. Defaults to
   *   `UTC`.
   * @param options.dense - Whether every key of the part's domain is
   *   returned.
   * @throws TypeError when `part` is not an `AggregationDatePart`,
   *   `timeZone` is blank, `dense` is not a boolean, or `field` or `alias` is
   *   invalid.
   * @example
   * ```typescript
   * aggregation.datePart('createTime', 'weekday', {
   *   part: AggregationDatePart.DAY_OF_WEEK,
   *   timeZone: 'Asia/Shanghai',
   * });
   * ```
   */
  datePart<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    { part, timeZone = 'UTC', dense }: DatePartAggregationOptions,
  ): DatePartAggregationGroup<FIELDS> {
    if (dense !== undefined && typeof dense !== 'boolean') {
      throw new TypeError('date part dense must be boolean.');
    }
    if (!Object.values(AggregationDatePart).includes(part)) {
      throw new TypeError('date part is invalid.');
    }
    if (typeof timeZone !== 'string' || !timeZone.trim()) {
      throw new TypeError('date part timeZone cannot be blank.');
    }
    return {
      type: AggregationGroupType.DATE_PART,
      ...(dense === undefined ? {} : { dense }),
      field: aggregationField(field),
      part,
      alias: aggregationAlias(alias),
      timeZone,
    };
  },
  /**
   * Any one value of a field within each group, of any type.
   * `{ type: 'ANY', field, alias, filter? }`.
   *
   * @throws TypeError when `field` or `alias` is invalid.
   * @example
   * ```typescript
   * aggregation.any('state.customerName', 'customer');
   * ```
   */
  any<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    { filter: predicate }: AggregationMetricOptions<FIELDS> = {},
  ): AnyAggregationMetric<FIELDS> {
    return {
      type: AggregationMetricType.ANY,
      ...(predicate === undefined ? {} : { filter: predicate }),
      field: aggregationField(field),
      alias: aggregationAlias(alias),
    };
  },
  /**
   * The number of rows in each group. `{ type: 'COUNT', alias, filter? }`.
   *
   * @param alias - The name of the metric column in the result rows.
   * @param options.filter - Counts only the rows that match.
   * @throws TypeError when `alias` is invalid.
   * @example
   * ```typescript
   * aggregation.count('paid', { filter: filter.eq('state.status', 'PAID') });
   * ```
   */
  count<FIELDS extends string = string>(
    alias: string,
    { filter: predicate }: AggregationMetricOptions<FIELDS> = {},
  ): CountAggregationMetric<FIELDS> {
    return {
      type: AggregationMetricType.COUNT,
      ...(predicate === undefined ? {} : { filter: predicate }),
      alias: aggregationAlias(alias),
    };
  },
  /**
   * The sum of an expression. `{ type: 'NUMERIC', function: 'SUM',
   * expression, alias, filter? }`.
   *
   * @example
   * ```typescript
   * aggregation.sum(aggregation.field('state.amount'), 'revenue');
   * ```
   */
  sum: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.SUM, expression, alias, options),
  /** The average of an expression; see {@link aggregation.sum}. */
  avg: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.AVG, expression, alias, options),
  /** The least value of an expression; see {@link aggregation.sum}. */
  min: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.MIN, expression, alias, options),
  /** The greatest value of an expression; see {@link aggregation.sum}. */
  max: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.MAX, expression, alias, options),
  /** The standard deviation of an expression; see {@link aggregation.sum}. */
  stddev: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.STDDEV, expression, alias, options),
  /** The variance of an expression; see {@link aggregation.sum}. */
  variance: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.VARIANCE, expression, alias, options),
  /**
   * The number of distinct values of an expression.
   * `{ type: 'DISTINCT_COUNT', expression, alias, filter? }`.
   *
   * @example
   * ```typescript
   * aggregation.distinctCount(aggregation.field('ownerId'), 'customers');
   * ```
   */
  distinctCount<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    { filter: predicate }: AggregationMetricOptions<FIELDS> = {},
  ): DistinctCountAggregationMetric<FIELDS> {
    return {
      type: AggregationMetricType.DISTINCT_COUNT,
      expression,
      alias: aggregationAlias(alias),
      ...(predicate === undefined ? {} : { filter: predicate }),
    };
  },
  /**
   * A percentile of an expression.
   * `{ type: 'PERCENTILE', expression, percentile, alias, filter? }`.
   *
   * @param options.percentile - Which percentile, within (0, 100).
   * @throws TypeError when `percentile` is not finite and within (0, 100), or
   *   `alias` is invalid.
   * @example
   * ```typescript
   * aggregation.percentile(aggregation.field('latency'), 'p95', { percentile: 95 });
   * ```
   */
  percentile<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    { percentile, filter: predicate }: PercentileAggregationOptions<FIELDS>,
  ): PercentileAggregationMetric<FIELDS> {
    if (!Number.isFinite(percentile) || percentile <= 0 || percentile >= 100) {
      throw new TypeError('percentile must be finite and within (0, 100).');
    }
    return {
      type: AggregationMetricType.PERCENTILE,
      expression,
      percentile,
      alias: aggregationAlias(alias),
      ...(predicate === undefined ? {} : { filter: predicate }),
    };
  },
  /**
   * A metric computed from other metrics of the same row, by their aliases.
   * `{ type: 'DERIVED', expression, alias }`. `aggregation.query()` checks
   * that it refers only to metrics declared before it.
   *
   * @param expression - The arithmetic: a callback that builds it from the
   *   {@link DerivedExpressionDsl} it is handed (`ref`, `constant`, `add`,
   *   `subtract`, `multiply`, `divide`), or an already-built tree.
   * @throws TypeError when `alias` is invalid, or a `constant` is not finite.
   * @example
   * ```typescript
   * aggregation.derived(
   *   d => d.divide(d.ref('revenue'), d.ref('orders')),
   *   'averageOrder',
   * );
   * ```
   */
  derived(
    expression:
      DerivedExpression | ((d: DerivedExpressionDsl) => DerivedExpression),
    alias: string,
  ): DerivedAggregationMetric {
    return {
      type: AggregationMetricType.DERIVED,
      expression:
        typeof expression === 'function'
          ? expression(derivedExpressionDsl)
          : expression,
      alias: aggregationAlias(alias),
    };
  },
  /**
   * The builders of the query's `having`: conditions on the metric columns of
   * the grouped rows (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `between`, `isIn`,
   * `isNull`, `isNotNull`) and their `and`/`or`. See `HavingDsl`.
   *
   * @example
   * ```typescript
   * aggregation.query({
   *   groupBy: [aggregation.terms('state.status', 'status')],
   *   metrics: [aggregation.count('orders')],
   *   having: aggregation.having.gt('orders', 10),
   * });
   * ```
   */
  having: havingDsl,

  /**
   * Admits a whole aggregation query against the rules Wow enforces on arrival.
   *
   * The factories above each check their own part; only the assembled query
   * can answer the questions that span parts — whether two aliases collide,
   * whether a sort names one of them, whether `having` has rows to filter.
   * Wow checks all of it in `AggregationQuery`'s constructor and answers a
   * query that fails with a 400, so the same rules run here against the object
   * about to be sent.
   */
  query<
    ROOT_FIELDS extends string = string,
    AGGREGATION_FIELDS extends string = ROOT_FIELDS,
  >(
    query: AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS>,
  ): AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS> {
    return admitAggregationQuery(query);
  },
};
