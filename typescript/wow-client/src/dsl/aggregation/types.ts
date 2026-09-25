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
} from '../filter/index.js';
import type { FieldSort } from '../sort.js';

/** The kind of an {@link AggregationGroup}, sent as its `type`. */
export enum AggregationGroupType {
  /** One group per distinct value; {@link aggregation.terms}. */
  TERMS = 'TERMS',
  /** Number buckets of equal width; {@link aggregation.histogram}. */
  HISTOGRAM = 'HISTOGRAM',
  /** Calendar buckets of a time field; {@link aggregation.dateHistogram}. */
  DATE_HISTOGRAM = 'DATE_HISTOGRAM',
  /**
   * One calendar part of a time field, such as the weekday or the hour;
   * {@link aggregation.datePart}. Wow 9.2 and later.
   */
  DATE_PART = 'DATE_PART',
}

/**
 * The kind of an {@link AggregationMetric}, sent as its `type`. Wow 8.12
 * knows `COUNT` and `NUMERIC`, Wow 9.0 adds `ANY`, and the other three need
 * Wow 9.1.
 */
export enum AggregationMetricType {
  /** The number of rows; {@link aggregation.count}. */
  COUNT = 'COUNT',
  /**
   * An {@link AggregationFunction} of an expression; {@link aggregation.sum}
   * and its siblings.
   */
  NUMERIC = 'NUMERIC',
  /** Any one value of a field; {@link aggregation.any}. Wow 9.0 and later. */
  ANY = 'ANY',
  /**
   * The number of distinct values; {@link aggregation.distinctCount}. Wow 9.1
   * and later.
   */
  DISTINCT_COUNT = 'DISTINCT_COUNT',
  /**
   * A percentile of an expression; {@link aggregation.percentile}. Wow 9.1
   * and later.
   */
  PERCENTILE = 'PERCENTILE',
  /**
   * Arithmetic over other metrics; {@link aggregation.derived}. Wow 9.1 and
   * later.
   */
  DERIVED = 'DERIVED',
}

/** The kind of an {@link AggregationExpression} node, sent as its `type`. */
export enum AggregationExpressionType {
  /** The value of a field; {@link aggregation.field}. */
  FIELD = 'FIELD',
  /** A number; {@link aggregation.constant}. */
  CONSTANT = 'CONSTANT',
  /** Two operands and an {@link AggregationExpressionOperator}. */
  BINARY = 'BINARY',
}

/**
 * The arithmetic of a `BINARY` node, in an {@link AggregationExpression} or a
 * {@link DerivedExpression}.
 */
export enum AggregationExpressionOperator {
  /** `left + right` */
  ADD = 'ADD',
  /** `left - right` */
  SUBTRACT = 'SUBTRACT',
  /** `left * right` */
  MULTIPLY = 'MULTIPLY',
  /** `left / right` */
  DIVIDE = 'DIVIDE',
}

/**
 * The calendar unit of a `DATE_HISTOGRAM` bucket, cut in the group's
 * `timeZone`.
 */
export enum AggregationDateUnit {
  YEAR = 'YEAR',
  QUARTER = 'QUARTER',
  MONTH = 'MONTH',
  WEEK = 'WEEK',
  DAY = 'DAY',
  HOUR = 'HOUR',
  MINUTE = 'MINUTE',
  SECOND = 'SECOND',
}

/**
 * The calendar part a `DATE_PART` group takes of each instant, read in the
 * group's `timeZone`. Its keys are integers from a fixed domain, so records
 * from different days, weeks or years fall into one bucket.
 */
export enum AggregationDatePart {
  /** The ISO weekday: 1 is Monday, 7 is Sunday. */
  DAY_OF_WEEK = 'DAY_OF_WEEK',
  /**
   * The day of the month, 1 to 31; days 29 to 31 exist only in the months
   * that have them.
   */
  DAY_OF_MONTH = 'DAY_OF_MONTH',
  /** The hour on the wall clock of the group's `timeZone`, 0 to 23. */
  HOUR_OF_DAY = 'HOUR_OF_DAY',
  /** The month of the year, 1 (January) to 12. */
  MONTH_OF_YEAR = 'MONTH_OF_YEAR',
}

/**
 * What a `NUMERIC` metric computes over its expression; each has a builder,
 * {@link aggregation.sum} through {@link aggregation.variance}.
 */
export enum AggregationFunction {
  /** The sum */
  SUM = 'SUM',
  /** The average */
  AVG = 'AVG',
  /** The least value */
  MIN = 'MIN',
  /** The greatest value */
  MAX = 'MAX',
  /** The standard deviation */
  STDDEV = 'STDDEV',
  /** The variance */
  VARIANCE = 'VARIANCE',
}

/**
 * An array field whose elements the query aggregates over instead of the
 * root documents; build it with {@link aggregation.element}. A query takes at
 * most `AGGREGATION_LIMITS.MAX_ELEMENTS` of them.
 */
export interface AggregationElement {
  /** The array field, a query field path. */
  path: QueryField;
  /** Keeps only the elements that match; no root filters. */
  filter?: ElementFilterExpression;
}

/** What every {@link AggregationGroup} has. */
interface AggregationGroupBase<FIELDS extends string = string> {
  /** The field the rows are grouped by. */
  field: QueryField<FIELDS>;
  /** The name of the group's column in the result rows. */
  alias: string;
}

/** One group per distinct value of a field; {@link aggregation.terms}. */
export interface TermsAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.TERMS;
  /** The key the rows without a value are grouped under. Wow 9.1 and later. */
  missingKey?: string;
}

/**
 * Buckets of equal width over a number field; {@link aggregation.histogram}.
 */
export interface HistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.HISTOGRAM;
  /** The width of each bucket, greater than 0. */
  interval: number;
}

/**
 * Calendar buckets over a time field in epoch milliseconds;
 * {@link aggregation.dateHistogram}.
 */
export interface DateHistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_HISTOGRAM;
  /** The calendar unit of each bucket. */
  unit: AggregationDateUnit;
  /** The IANA zone the buckets are cut in; `UTC` when absent. */
  timeZone?: string;
  /**
   * Whether the server fills in the empty buckets of the range. Wow 9.1 and
   * later, and only when this is the query's only group.
   */
  dense?: boolean;
}

/**
 * One calendar part of a time field in epoch milliseconds, such as the
 * weekday or the hour; {@link aggregation.datePart}. Its keys in the result
 * rows are integers from the part's domain, such as 1 (Monday) to 7 (Sunday)
 * for `DAY_OF_WEEK`. Wow 9.2 and later.
 */
export interface DatePartAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_PART;
  /** The calendar part each row is grouped by. */
  part: AggregationDatePart;
  /** The IANA zone the part is read in; `UTC` when absent. */
  timeZone?: string;
  /**
   * Whether the server returns every key of the part's domain, filling a
   * key with no records with each metric's empty value. Only when this is
   * the query's only group.
   */
  dense?: boolean;
}

/**
 * One dimension of an {@link AggregationQuery}'s `groupBy`: each result row
 * is one combination of the groups' keys.
 */
export type AggregationGroup<FIELDS extends string = string> =
  | TermsAggregationGroup<FIELDS>
  | HistogramAggregationGroup<FIELDS>
  | DateHistogramAggregationGroup<FIELDS>
  | DatePartAggregationGroup<FIELDS>;

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
 * Per-row arithmetic over fields and numbers, which a metric aggregates. A
 * tree at most `AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH` deep and
 * `MAX_EXPRESSION_NODES` large, counted over the whole query.
 */
export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>;

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

/** The kind of a {@link DerivedExpression} node, sent as its `type`. */
export enum DerivedExpressionType {
  /** The value of another metric of the row, by its alias. */
  METRIC_REF = 'METRIC_REF',
  /** A number. */
  CONSTANT = 'CONSTANT',
  /** Two operands and an {@link AggregationExpressionOperator}. */
  BINARY = 'BINARY',
}

/** Post-aggregation arithmetic; references must name earlier non-ANY metrics. */
export type DerivedExpression =
  | { type: DerivedExpressionType.METRIC_REF; metric: string }
  | { type: DerivedExpressionType.CONSTANT; value: number }
  | {
      type: DerivedExpressionType.BINARY;
      operator: AggregationExpressionOperator;
      left: DerivedExpression;
      right: DerivedExpression;
    };

/**
 * A metric computed from metrics declared before it in the same query, after
 * aggregation; {@link aggregation.derived}. Wow 9.1 and later.
 */
export interface DerivedAggregationMetric {
  type: AggregationMetricType.DERIVED;
  /** The arithmetic over the other metrics. */
  expression: DerivedExpression;
  /** The name of the metric's column in the result rows. */
  alias: string;
}

/** The kind of a {@link HavingExpression} node, sent as its `type`. */
export enum HavingExpressionType {
  /** A metric compared with a number; `aggregation.having.eq` … `lte`. */
  CONDITION = 'CONDITION',
  /** A metric within bounds, inclusive; `aggregation.having.between`. */
  BETWEEN = 'BETWEEN',
  /** A metric equal to one of the numbers; `aggregation.having.isIn`. */
  IN = 'IN',
  /**
   * A metric without a value, or with one when `negated`;
   * `aggregation.having.isNull`, `isNotNull`.
   */
  IS_NULL = 'IS_NULL',
  /** All operands hold; `aggregation.having.and`. */
  AND = 'AND',
  /** At least one operand holds; `aggregation.having.or`. */
  OR = 'OR',
}

/** The comparison of a `CONDITION` in a {@link HavingExpression}. */
export enum ComparisonOperator {
  /** `=` */
  EQ = 'EQ',
  /** `≠` */
  NE = 'NE',
  /** `>` */
  GT = 'GT',
  /** `≥` */
  GTE = 'GTE',
  /** `<` */
  LT = 'LT',
  /** `≤` */
  LTE = 'LTE',
}

/** Filters grouped results by non-ANY metric aliases, before sorting/limit. */
export type HavingExpression =
  | {
      type: HavingExpressionType.CONDITION;
      metric: string;
      operator: ComparisonOperator;
      value: number;
    }
  | {
      type: HavingExpressionType.BETWEEN;
      metric: string;
      lower: number;
      upper: number;
    }
  | {
      type: HavingExpressionType.IN;
      metric: string;
      values: [number, ...number[]];
    }
  | { type: HavingExpressionType.IS_NULL; metric: string; negated?: boolean }
  | {
      type: HavingExpressionType.AND | HavingExpressionType.OR;
      operands: [HavingExpression, ...HavingExpression[]];
    };

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
  | DerivedAggregationMetric;

/**
 * An aggregation request: which documents, grouped how, computing which
 * metrics. `QueryApi.aggregate` and `aggregateStream` send it, and each
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
 * The options every metric builder takes after its target and alias.
 */
export interface AggregationMetricOptions<FIELDS extends string = string> {
  /** Counts only the rows that match; sent as the metric's `filter`. */
  filter?: FilterExpression<FIELDS>;
}

/** The options of {@link aggregation.terms}. */
export interface TermsAggregationOptions {
  /** The key rows without a value are grouped under; must not be blank. */
  missingKey?: string;
}

/** The options of {@link aggregation.histogram}. */
export interface HistogramAggregationOptions {
  /** The width of each bucket: finite and greater than 0. */
  interval: number;
}

/** The options of {@link aggregation.dateHistogram}. */
export interface DateHistogramAggregationOptions {
  /** The calendar unit of each bucket. */
  unit: AggregationDateUnit;
  /** The zone the buckets are cut in, an IANA id. Defaults to `UTC`. */
  timeZone?: string;
  /**
   * Whether the server fills in the empty buckets of the range. Only when
   * this is the only group.
   */
  dense?: boolean;
}

/** The options of {@link aggregation.datePart}. */
export interface DatePartAggregationOptions {
  /** The calendar part each row is grouped by. */
  part: AggregationDatePart;
  /** The zone the part is read in, an IANA id. Defaults to `UTC`. */
  timeZone?: string;
  /**
   * Whether the server returns every key of the part's domain, the empty
   * ones filled in. Only when this is the only group.
   */
  dense?: boolean;
}

/** The options of {@link aggregation.percentile}. */
export interface PercentileAggregationOptions<
  FIELDS extends string = string,
> extends AggregationMetricOptions<FIELDS> {
  /** Which percentile: finite and within (0, 100). */
  percentile: number;
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
});
