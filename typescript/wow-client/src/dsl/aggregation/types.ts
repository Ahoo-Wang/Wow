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
import type { FieldSort, SortDirection } from '../sort.js';

/** The kind of an {@link AggregationGroup}, sent as its `type`. */
export enum AggregationGroupType {
  /** One group per distinct value; {@link aggregation.terms}. */
  TERMS = 'terms',
  /** Number buckets of equal width; {@link aggregation.histogram}. */
  HISTOGRAM = 'histogram',
  /** Calendar buckets of a time field; {@link aggregation.dateHistogram}. */
  DATE_HISTOGRAM = 'date-histogram',
  /**
   * One calendar part of a time field, such as the weekday or the hour;
   * {@link aggregation.datePart}. Wow 9.2 and later.
   */
  DATE_PART = 'date-part',
}

/**
 * What an {@link AggregationGroup}'s field is relative to when the query
 * expands elements, sent as its `scope`. Wow 9.9 and later.
 */
export enum AggregationGroupScope {
  /** The innermost expanded element; the default. */
  ELEMENT = 'element',
  /** The root record, so the elements are grouped by a field of their root. */
  ROOT = 'root',
}

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
 * The calendar unit of a `DATE_HISTOGRAM` bucket, cut in the group's
 * `timeZone`.
 */
export enum AggregationDateUnit {
  YEAR = 'year',
  QUARTER = 'quarter',
  MONTH = 'month',
  WEEK = 'week',
  DAY = 'day',
  HOUR = 'hour',
  MINUTE = 'minute',
  SECOND = 'second',
}

/**
 * The calendar part a `DATE_PART` group takes of each instant, read in the
 * group's `timeZone`. Its keys are integers from a fixed domain, so records
 * from different days, weeks or years fall into one bucket.
 */
export enum AggregationDatePart {
  /** The ISO weekday: 1 is Monday, 7 is Sunday. */
  DAY_OF_WEEK = 'day-of-week',
  /**
   * The day of the month, 1 to 31; days 29 to 31 exist only in the months
   * that have them.
   */
  DAY_OF_MONTH = 'day-of-month',
  /** The hour on the wall clock of the group's `timeZone`, 0 to 23. */
  HOUR_OF_DAY = 'hour-of-day',
  /** The month of the year, 1 (January) to 12. */
  MONTH_OF_YEAR = 'month-of-year',
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

/** What every {@link AggregationGroup} has. */
interface AggregationGroupBase<FIELDS extends string = string> {
  /** The field the rows are grouped by. */
  field: QueryField<FIELDS>;
  /** The name of the group's column in the result rows. */
  alias: string;
  /**
   * What `field` is relative to when the query expands elements: the
   * innermost element (`ELEMENT`, the default) or the root record (`ROOT`).
   * A `ROOT` group names a field, never an expression. Wow 9.9 and later,
   * where `analysis.rootGroups` is `true`.
   */
  scope?: AggregationGroupScope;
}

/**
 * What a TERMS or HISTOGRAM group groups by: a field, or a computed
 * expression in its place (Wow 9.2 and later), never both. An expression
 * input is an expensive computation the server admits only where
 * `analysis.expressions` is `true`.
 */
export type AggregationGroupInput<FIELDS extends string = string> =
  | {
      /** The field the rows are grouped by. */
      field: QueryField<FIELDS>;
      expression?: undefined;
    }
  | {
      field?: undefined;
      /** The per-row value the rows are grouped by, such as a `DATE_DIFF`. */
      expression: AggregationExpression<FIELDS>;
    };

/**
 * One group per distinct value of a field, or of an expression;
 * {@link aggregation.terms}. `missingKey` needs a field.
 */
export type TermsAggregationGroup<FIELDS extends string = string> = {
  type: AggregationGroupType.TERMS;
  /** The name of the group's column in the result rows. */
  alias: string;
  /** The key the rows without a value are grouped under. Wow 9.1 and later. */
  missingKey?: string;
  /**
   * What `field` is relative to when the query expands elements: the
   * innermost element (`ELEMENT`, the default) or the root record (`ROOT`).
   * A `ROOT` group names a field, never an expression. Wow 9.9 and later,
   * where `analysis.rootGroups` is `true`.
   */
  scope?: AggregationGroupScope;
} & AggregationGroupInput<FIELDS>;

/**
 * Buckets of equal width over a number field, or over an expression;
 * {@link aggregation.histogram}.
 */
export type HistogramAggregationGroup<FIELDS extends string = string> = {
  type: AggregationGroupType.HISTOGRAM;
  /** The name of the group's column in the result rows. */
  alias: string;
  /** The width of each bucket, greater than 0. */
  interval: number;
  /**
   * What `field` is relative to when the query expands elements: the
   * innermost element (`ELEMENT`, the default) or the root record (`ROOT`).
   * A `ROOT` group names a field, never an expression. Wow 9.9 and later,
   * where `analysis.rootGroups` is `true`.
   */
  scope?: AggregationGroupScope;
} & AggregationGroupInput<FIELDS>;

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

/** The kind of a {@link DerivedExpression} node, sent as its `type`. */
export enum DerivedExpressionType {
  /** The value of another metric of the row, by its alias. */
  METRIC_REF = 'metric-ref',
  /** A number. */
  CONSTANT = 'constant',
  /** Two operands and an {@link AggregationExpressionOperator}. */
  BINARY = 'binary',
  /** A window: the metric summed over a partition of the groups. */
  TOTAL = 'total',
  /** A window: the metric accumulated along an order, or its moving average. */
  RUNNING = 'running',
  /** A window: the metric of an earlier period along a date group. */
  PREVIOUS = 'previous',
  /** A window: the dense rank of the metric within a partition. */
  RANK = 'rank',
}

/**
 * The window functions of derived expressions, as a storage declares them in
 * `analysis.windows` of its descriptor.
 */
export enum WindowFunction {
  TOTAL = 'total',
  RUNNING = 'running',
  PREVIOUS = 'previous',
  RANK = 'rank',
}

/** What a `RUNNING` window computes over its frame. */
export enum RunningFunction {
  SUM = 'sum',
  AVG = 'avg',
}

/**
 * A window over the groups of the aggregation. Wow computes it over every
 * group the filter selects, before `having`, `sort` and `limit`; `metric`
 * names a non-derived numeric metric declared before the derived one, and
 * `within`, `order` and `along` name groups of the query. Wow 9.9 and later,
 * where `analysis.windows` lists the function.
 */
export type DerivedWindow =
  | {
      type: DerivedExpressionType.TOTAL;
      /** A `COUNT` or `SUM` metric. */
      metric: string;
      /** The partition's group aliases; every group when absent. */
      within?: string[];
    }
  | {
      type: DerivedExpressionType.RUNNING;
      /** A `COUNT` or `SUM` metric for `SUM`; any numeric metric for `AVG`. */
      metric: string;
      /** A group alias or a non-derived numeric metric, with its direction. */
      order: FieldSort;
      within?: string[];
      /** The last this many groups; every group up to this one when absent. */
      frame?: number;
      /** `SUM` when absent. */
      function?: RunningFunction;
    }
  | {
      type: DerivedExpressionType.PREVIOUS;
      metric: string;
      /** The alias of a `DATE_HISTOGRAM` group. */
      along: string;
      /** How many steps back; 1 when absent. */
      shift?: number;
      /** Step by this calendar period instead of by one bucket of `along`. */
      period?: AggregationDateUnit;
    }
  | {
      type: DerivedExpressionType.RANK;
      metric: string;
      within?: string[];
      /** `DESC` when absent. */
      direction?: SortDirection;
    };

/**
 * Post-aggregation arithmetic; references must name earlier metrics that are
 * not `ANY`, `FIRST` or `LAST`.
 */
export type DerivedExpression =
  | { type: DerivedExpressionType.METRIC_REF; metric: string }
  | { type: DerivedExpressionType.CONSTANT; value: number }
  | {
      type: DerivedExpressionType.BINARY;
      operator: AggregationExpressionOperator;
      left: DerivedExpression;
      right: DerivedExpression;
    }
  | DerivedWindow;

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
  CONDITION = 'condition',
  /** A metric within bounds, inclusive; `aggregation.having.between`. */
  BETWEEN = 'between',
  /** A metric equal to one of the numbers; `aggregation.having.isIn`. */
  IN = 'in',
  /**
   * A metric without a value, or with one when `negated`;
   * `aggregation.having.isNull`, `isNotNull`.
   */
  IS_NULL = 'is-null',
  /** All operands hold; `aggregation.having.and`. */
  AND = 'and',
  /** At least one operand holds; `aggregation.having.or`. */
  OR = 'or',
}

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
 * Filters grouped results by metric aliases, before sorting/limit; never an
 * `ANY`, `FIRST` or `LAST` metric.
 */
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
  | DerivedAggregationMetric
  | EdgeAggregationMetric<FIELDS>;

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
 * The options every metric builder takes after its target and alias.
 */
export interface AggregationMetricOptions<FIELDS extends string = string> {
  /** Counts only the rows that match; sent as the metric's `filter`. */
  filter?: FilterExpression<FIELDS>;
}

/** The option every group builder takes. */
export interface AggregationGroupScopeOptions {
  /**
   * What the field is relative to when the query expands elements; the
   * innermost element when absent. `ROOT` needs a field input. Wow 9.9 and
   * later.
   */
  scope?: AggregationGroupScope;
}

/** The options of {@link aggregation.terms}. */
export interface TermsAggregationOptions extends AggregationGroupScopeOptions {
  /** The key rows without a value are grouped under; must not be blank. */
  missingKey?: string;
}

/** The options of {@link aggregation.histogram}. */
export interface HistogramAggregationOptions extends AggregationGroupScopeOptions {
  /** The width of each bucket: finite and greater than 0. */
  interval: number;
}

/** The options of {@link aggregation.dateHistogram}. */
export interface DateHistogramAggregationOptions extends AggregationGroupScopeOptions {
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
export interface DatePartAggregationOptions extends AggregationGroupScopeOptions {
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
