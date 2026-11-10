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

import type {
  ElementFilterExpression,
  FilterExpression,
  QueryField,
  ExpressionFilter,
} from '../filter/index.js';
import type { FieldSort } from '../sort.js';
import type { AggregationGroup } from './groups.js';
import type { HavingExpression } from './having.js';
import type { AggregationMetric } from './metrics.js';

/** The kind of an {@link AggregationExpression} node, sent as its `type`. */
export enum AggregationExpressionType {
  /** The value of a field; {@link aggregation.field}. */
  FIELD = 'field',
  /** A number; {@link aggregation.constant}. */
  CONSTANT = 'constant',
  /** Two operands and an {@link AggregationExpressionOperator}. */
  BINARY = 'binary',
  /**
   * The time elapsed between two time fields; {@link aggregation.dateDiff}.
   * Wow 9.2 and later.
   */
  DATE_DIFF = 'date-diff',
}

/**
 * The unit a `DATE_DIFF` measures elapsed time in. Each has a fixed length:
 * a `DAY` is exactly 24 hours, and months and years, whose length depends on
 * a calendar and a time zone, are not offered.
 */
export enum DateDiffUnit {
  SECOND = 'second',
  MINUTE = 'minute',
  HOUR = 'hour',
  DAY = 'day',
}

/**
 * The arithmetic of a `BINARY` node, in an {@link AggregationExpression} or a
 * {@link DerivedExpression}.
 */
export enum AggregationExpressionOperator {
  /** `left + right` */
  ADD = 'add',
  /** `left - right` */
  SUBTRACT = 'subtract',
  /** `left * right` */
  MULTIPLY = 'multiply',
  /** `left / right` */
  DIVIDE = 'divide',
}

/**
 * An array field whose elements the query aggregates over instead of the
 * root documents; build it with {@link aggregation.element}. A query takes at
 * most `AGGREGATION_LIMITS.MAX_ELEMENTS` of them.
 */
export interface AggregationElement {
  /** The array field, a query field path. */
  path: QueryField;
  /**
   * Keeps only the elements that match; no root filters and no search, but
   * an `EXPRESSION` filter may compare an element's computed value.
   */
  filter?: ElementFilterExpression | ExpressionFilter;
}

/** The value of a field, as an operand; {@link aggregation.field}. */
export interface FieldAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.FIELD;
  /** The field, a query field path. */
  field: QueryField<FIELDS>;
}

/** A number, as an operand; {@link aggregation.constant}. */
export interface ConstantAggregationExpression {
  type: AggregationExpressionType.CONSTANT;
  /** A finite number. */
  value: number;
}

/**
 * `left operator right`; {@link aggregation.add}, {@link aggregation.subtract},
 * {@link aggregation.multiply}, {@link aggregation.divide}.
 */
export interface BinaryAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.BINARY;
  /** The arithmetic. */
  operator: AggregationExpressionOperator;
  /** The left operand. */
  left: AggregationExpression<FIELDS>;
  /** The right operand. */
  right: AggregationExpression<FIELDS>;
}

/**
 * The time from the instant in `from` to the instant in `to`, `to − from`,
 * in `unit`, as a signed decimal: negative when `to` is earlier, and no
 * value when either is absent. Both are single-valued time fields (their
 * `aggregate.groups` list `DATE_HISTOGRAM`), each read in its own encoding;
 * {@link aggregation.dateDiff}. Wow 9.2 and later.
 */
export interface DateDiffAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.DATE_DIFF;
  /** The earlier instant, when the difference is positive. */
  from: QueryField<FIELDS>;
  /** The later instant, when the difference is positive. */
  to: QueryField<FIELDS>;
  /** The unit the difference is measured in. */
  unit: DateDiffUnit;
}

/**
 * Per-row arithmetic over fields, time differences and numbers, which a
 * metric aggregates, a TERMS or HISTOGRAM group groups by, and an
 * `EXPRESSION` filter compares. A tree at most
 * `AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH` deep and `MAX_EXPRESSION_NODES`
 * large, counted over a query's metrics together and over each group or
 * filter on its own.
 */
export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>
  | DateDiffAggregationExpression<FIELDS>;

/** The comparison of a `CONDITION` in a {@link HavingExpression}. */
export enum ComparisonOperator {
  /** `=` */
  EQ = 'eq',
  /** `≠` */
  NE = 'ne',
  /** `>` */
  GT = 'gt',
  /** `≥` */
  GTE = 'gte',
  /** `<` */
  LT = 'lt',
  /** `≤` */
  LTE = 'lte',
}

/**
 * An aggregation request: which documents, grouped how, computing which
 * metrics. `QueryModelClient.aggregate` sends it, and each
 * result row holds the groups' and metrics' columns by alias. Assemble it
 * with {@link aggregation.query}, which checks the rules Wow enforces across
 * its parts before anything is sent.
 *
 * Wow 8.11 has no aggregation endpoint; Wow 8.12 added it with `COUNT` and
 * `NUMERIC` metrics, and Wow 9.0 added `ANY`. `having`, the `DISTINCT_COUNT`,
 * `PERCENTILE` and `DERIVED` metrics, metric filters, `missingKey` and `dense`
 * need Wow 9.1.
 *
 * @template ROOT_FIELDS - The fields `filter` may name
 * @template AGGREGATION_FIELDS - The fields groups and metrics may name
 */
export interface AggregationQuery<
  ROOT_FIELDS extends string = string,
  AGGREGATION_FIELDS extends string = ROOT_FIELDS,
> {
  /** Which documents are aggregated; all of them when absent. */
  filter?: FilterExpression<ROOT_FIELDS>;
  /** Array fields to aggregate the elements of, instead of the documents. */
  elements?: AggregationElement[];
  /** The dimensions of the result; one row in total when absent or empty. */
  groupBy?: AggregationGroup<AGGREGATION_FIELDS>[];
  /** The columns computed for each group. */
  metrics: [
    AggregationMetric<AGGREGATION_FIELDS>,
    ...AggregationMetric<AGGREGATION_FIELDS>[],
  ];
  /**
   * The order of the rows, by group or metric alias; needs a `groupBy`. Wow
   * then sorts by each remaining group ascending.
   */
  sort?: FieldSort[];
  /**
   * The most rows returned, 1 to `AGGREGATION_LIMITS.MAX_LIMIT`; the
   * server returns `DEFAULT_LIMIT` (100) when absent.
   */
  limit?: number;
  /**
   * Keeps only the grouped rows whose metrics match, before `sort` and
   * `limit`; built with {@link aggregation.having}. Needs a `groupBy`.
   */
  having?: HavingExpression;
}

/**
 * The sizes Wow's own `AggregationQuery` enforces by throwing.
 *
 * A query that breaks one of them is refused by the server, which says what
 * was wrong but not which metric or group said it. Checking here keeps the
 * complaint next to the call that built the query.
 */
export const AGGREGATION_LIMITS = Object.freeze({
  /** Rows returned when a query names no limit of its own. */
  DEFAULT_LIMIT: 100,
  /** The largest `limit`. */
  MAX_LIMIT: 10_000,
  /** The most `elements`. */
  MAX_ELEMENTS: 5,
  /** The most `groupBy` entries. */
  MAX_GROUPS: 32,
  /** The most `metrics`. */
  MAX_METRICS: 64,
  /** The most `sort` fields, including those Wow appends for the groups. */
  MAX_SORT_FIELDS: 32,
  /** The deepest expression tree. */
  MAX_EXPRESSION_DEPTH: 8,
  /** The most expression nodes in the whole query. */
  MAX_EXPRESSION_NODES: 256,
  /** The most windows in the whole query. */
  MAX_WINDOWS: 8,
});
