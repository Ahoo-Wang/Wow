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

import type { FilterExpression, QueryField } from '../filter/index.js';
import type { DerivedAggregationMetric } from './derived.js';
import { aggregationAlias, aggregationField } from './aliases.js';
import type { AggregationExpression } from './types.js';

/**
 * The kind of an {@link AggregationMetric}, sent as its `type`. Wow 8.12
 * knows `COUNT` and `NUMERIC`, Wow 9.0 adds `ANY`, and the other three need
 * Wow 9.1.
 */
export enum AggregationMetricType {
  /** The number of rows; {@link aggregation.count}. */
  COUNT = 'count',
  /**
   * An {@link AggregationFunction} of an expression; {@link aggregation.sum}
   * and its siblings.
   */
  NUMERIC = 'numeric',
  /** Any one value of a field; {@link aggregation.any}. Wow 9.0 and later. */
  ANY = 'any',
  /**
   * The number of distinct values; {@link aggregation.distinctCount}. Wow 9.1
   * and later.
   */
  DISTINCT_COUNT = 'distinct-count',
  /**
   * A percentile of an expression; {@link aggregation.percentile}. Wow 9.1
   * and later.
   */
  PERCENTILE = 'percentile',
  /**
   * Arithmetic over other metrics; {@link aggregation.derived}. Wow 9.1 and
   * later.
   */
  DERIVED = 'derived',
  /**
   * A field's value on the group's earliest record; {@link aggregation.first}.
   * Wow 9.2 and later.
   */
  FIRST = 'first',
  /**
   * A field's value on the group's latest record; {@link aggregation.last}.
   * Wow 9.2 and later.
   */
  LAST = 'last',
}

/**
 * What a `NUMERIC` metric computes over its expression; each has a builder,
 * {@link aggregation.sum} through {@link aggregation.variance}.
 */
export enum AggregationFunction {
  /** The sum */
  SUM = 'sum',
  /** The average */
  AVG = 'avg',
  /** The least value */
  MIN = 'min',
  /** The greatest value */
  MAX = 'max',
  /** The standard deviation */
  STDDEV = 'stddev',
  /** The variance */
  VARIANCE = 'variance',
}

/** The number of rows in each group; {@link aggregation.count}. */
export interface CountAggregationMetric<FIELDS extends string = string> {
  /** Counts only the rows that match. Wow 9.1 and later. */
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.COUNT;
  /** The name of the metric's column in the result rows. */
  alias: string;
}

/**
 * An {@link AggregationFunction} of an expression; {@link aggregation.sum},
 * `avg`, `min`, `max`, `stddev`, `variance`.
 */
export interface NumericAggregationMetric<FIELDS extends string = string> {
  /** Aggregates only the rows that match. Wow 9.1 and later. */
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.NUMERIC;
  /** What is computed. */
  function: AggregationFunction;
  /** The per-row value it is computed over. */
  expression: AggregationExpression<FIELDS>;
  /** The name of the metric's column in the result rows. */
  alias: string;
}

/**
 * Any one value of a field within each group, of any type;
 * {@link aggregation.any}. Wow 9.0 and later. Neither a derived metric nor
 * `having` may refer to it.
 */
export interface AnyAggregationMetric<FIELDS extends string = string> {
  /** Aggregates only the rows that match. Wow 9.1 and later. */
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.ANY;
  /** The field whose value is taken. */
  field: QueryField<FIELDS>;
  /** The name of the metric's column in the result rows. */
  alias: string;
}

/**
 * A field's value on the earliest (`FIRST`) or latest (`LAST`) record of
 * each group, ordered by `orderBy`; {@link aggregation.first} and
 * {@link aggregation.last}. Wow 9.2 and later.
 *
 * The value comes back in the field's own type, not as a number, and is
 * `null` when no record of the group has both a value and an `orderBy`
 * position. Without `orderBy` the server orders by the model's event time,
 * the field the descriptor names as `analysis.firstLastOrderBy`. Records
 * tied on `orderBy` may yield any one of their values. Like `ANY`, neither a
 * derived metric nor `having` may refer to it.
 */
export interface EdgeAggregationMetric<FIELDS extends string = string> {
  type: AggregationMetricType.FIRST | AggregationMetricType.LAST;
  /** The single-valued field whose value is taken. */
  field: QueryField<FIELDS>;
  /**
   * The single-valued, sortable field that orders the records; the model's
   * event time when absent.
   */
  orderBy?: QueryField<FIELDS>;
  /** The name of the metric's column in the result rows. */
  alias: string;
  /** Aggregates only the rows that match. */
  filter?: FilterExpression<FIELDS>;
}

/**
 * The number of distinct values of an expression;
 * {@link aggregation.distinctCount}. Wow 9.1 and later.
 */
export interface DistinctCountAggregationMetric<
  FIELDS extends string = string,
> {
  type: AggregationMetricType.DISTINCT_COUNT;
  /** The per-row value whose distinct values are counted. */
  expression: AggregationExpression<FIELDS>;
  /** The name of the metric's column in the result rows. */
  alias: string;
  /** Aggregates only the rows that match. */
  filter?: FilterExpression<FIELDS>;
}

/**
 * A percentile of an expression; {@link aggregation.percentile}. Wow 9.1 and
 * later.
 */
export interface PercentileAggregationMetric<FIELDS extends string = string> {
  type: AggregationMetricType.PERCENTILE;
  /** The per-row value the percentile is taken over. */
  expression: AggregationExpression<FIELDS>;
  /** Which percentile, within (0, 100). */
  percentile: number;
  /** The name of the metric's column in the result rows. */
  alias: string;
  /** Aggregates only the rows that match. */
  filter?: FilterExpression<FIELDS>;
}

/**
 * One column an {@link AggregationQuery} computes for each group; a query
 * takes 1 to `AGGREGATION_LIMITS.MAX_METRICS` of them.
 */
export type AggregationMetric<FIELDS extends string = string> =
  | CountAggregationMetric<FIELDS>
  | NumericAggregationMetric<FIELDS>
  | AnyAggregationMetric<FIELDS>
  | DistinctCountAggregationMetric<FIELDS>
  | PercentileAggregationMetric<FIELDS>
  | DerivedAggregationMetric
  | EdgeAggregationMetric<FIELDS>;

/**
 * The options every metric builder takes after its target and alias.
 */
export interface AggregationMetricOptions<FIELDS extends string = string> {
  /** Counts only the rows that match; sent as the metric's `filter`. */
  filter?: FilterExpression<FIELDS>;
}

/** The options of {@link aggregation.first} and {@link aggregation.last}. */
export interface EdgeAggregationOptions<
  FIELDS extends string = string,
> extends AggregationMetricOptions<FIELDS> {
  /**
   * The single-valued, sortable field that orders the records. Defaults to
   * the model's event time, `analysis.firstLastOrderBy` in the descriptor.
   */
  orderBy?: FIELDS;
}

/** The options of {@link aggregation.percentile}. */
export interface PercentileAggregationOptions<
  FIELDS extends string = string,
> extends AggregationMetricOptions<FIELDS> {
  /** Which percentile: finite and within (0, 100). */
  percentile: number;
}

export function numeric<FIELDS extends string>(
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

/** Builds a `FIRST` or `LAST` metric; {@link aggregation.first}. */
export function edge<FIELDS extends string>(
  type: AggregationMetricType.FIRST | AggregationMetricType.LAST,
  field: FIELDS,
  alias: string,
  { orderBy, filter: predicate }: EdgeAggregationOptions<FIELDS>,
): EdgeAggregationMetric<FIELDS> {
  return {
    type,
    field: aggregationField(field),
    alias: aggregationAlias(alias),
    ...(orderBy === undefined ? {} : { orderBy: aggregationField(orderBy) }),
    ...(predicate === undefined ? {} : { filter: predicate }),
  };
}

/** The builders of the metrics of an aggregation query: any, first, last, count, the numeric functions, distinct count and percentile. */
export const metricBuilders = {
  /**
   * Any one value of a field within each group, of any type.
   * `{ type: 'any', field, alias, filter? }`.
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
   * The value of `field` on each group's earliest record by `orderBy`.
   * `{ type: 'first', field, alias, orderBy?, filter? }`.
   *
   * The value comes back in the field's own type, or `null` when no record
   * of the group has both a value and an `orderBy` position. Without
   * `orderBy` the server orders by the model's event time, which the
   * descriptor names as `analysis.firstLastOrderBy`. Neither a derived
   * metric nor `having` may refer to it. Wow 9.2 and later.
   *
   * @throws TypeError when `field`, `orderBy` or `alias` is invalid.
   * @example
   * ```typescript
   * aggregation.first('state.price', 'open', { orderBy: 'createTime' });
   * ```
   */
  first<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options: EdgeAggregationOptions<FIELDS> = {},
  ): EdgeAggregationMetric<FIELDS> {
    return edge(AggregationMetricType.FIRST, field, alias, options);
  },
  /**
   * The value of `field` on each group's latest record by `orderBy`.
   * `{ type: 'last', field, alias, orderBy?, filter? }`. As
   * {@link metricBuilders.first | `aggregation.first`}, from the other end.
   *
   * @throws TypeError when `field`, `orderBy` or `alias` is invalid.
   * @example
   * ```typescript
   * aggregation.last('state.price', 'close', { orderBy: 'createTime' });
   * ```
   */
  last<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options: EdgeAggregationOptions<FIELDS> = {},
  ): EdgeAggregationMetric<FIELDS> {
    return edge(AggregationMetricType.LAST, field, alias, options);
  },
  /**
   * The number of rows in each group. `{ type: 'count', alias, filter? }`.
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
   * The sum of an expression. `{ type: 'numeric', function: 'sum',
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
  /** The average of an expression; see {@link metricBuilders.sum | `aggregation.sum`}. */
  avg: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.AVG, expression, alias, options),
  /** The least value of an expression; see {@link metricBuilders.sum | `aggregation.sum`}. */
  min: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.MIN, expression, alias, options),
  /** The greatest value of an expression; see {@link metricBuilders.sum | `aggregation.sum`}. */
  max: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.MAX, expression, alias, options),
  /** The standard deviation of an expression; see {@link metricBuilders.sum | `aggregation.sum`}. */
  stddev: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.STDDEV, expression, alias, options),
  /** The variance of an expression; see {@link metricBuilders.sum | `aggregation.sum`}. */
  variance: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => numeric(AggregationFunction.VARIANCE, expression, alias, options),
  /**
   * The number of distinct values of an expression.
   * `{ type: 'distinct-count', expression, alias, filter? }`.
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
   * `{ type: 'percentile', expression, percentile, alias, filter? }`.
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
};
