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

import {
  filter,
  FilterOperator,
  requireElementScopedFilter,
  type ElementFilterExpression,
  type FilterExpression,
  type QueryField,
} from './filter.js';
import { asc, type FieldSort } from './sort.js';

export enum AggregationGroupType {
  TERMS = 'TERMS',
  HISTOGRAM = 'HISTOGRAM',
  DATE_HISTOGRAM = 'DATE_HISTOGRAM',
}

export enum AggregationMetricType {
  COUNT = 'COUNT',
  NUMERIC = 'NUMERIC',
  ANY = 'ANY',
  DISTINCT_COUNT = 'DISTINCT_COUNT',
  PERCENTILE = 'PERCENTILE',
  DERIVED = 'DERIVED',
}

export enum AggregationExpressionType {
  FIELD = 'FIELD',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
}

export enum AggregationExpressionOperator {
  ADD = 'ADD',
  SUBTRACT = 'SUBTRACT',
  MULTIPLY = 'MULTIPLY',
  DIVIDE = 'DIVIDE',
}

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

export enum AggregationFunction {
  SUM = 'SUM',
  AVG = 'AVG',
  MIN = 'MIN',
  MAX = 'MAX',
  STDDEV = 'STDDEV',
  VARIANCE = 'VARIANCE',
}

export interface AggregationElement {
  path: QueryField;
  filter?: ElementFilterExpression;
}

interface AggregationGroupBase<FIELDS extends string = string> {
  field: QueryField<FIELDS>;
  alias: string;
}

export interface TermsAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.TERMS;
  missingKey?: string;
}

export interface HistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.HISTOGRAM;
  interval: number;
}

export interface DateHistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_HISTOGRAM;
  unit: AggregationDateUnit;
  timeZone?: string;
  dense?: boolean;
}

export type AggregationGroup<FIELDS extends string = string> =
  | TermsAggregationGroup<FIELDS>
  | HistogramAggregationGroup<FIELDS>
  | DateHistogramAggregationGroup<FIELDS>;

export interface FieldAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.FIELD;
  field: QueryField<FIELDS>;
}

export interface ConstantAggregationExpression {
  type: AggregationExpressionType.CONSTANT;
  value: number;
}

export interface BinaryAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.BINARY;
  operator: AggregationExpressionOperator;
  left: AggregationExpression<FIELDS>;
  right: AggregationExpression<FIELDS>;
}

export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>;

export interface CountAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.COUNT;
  alias: string;
}

export interface NumericAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.NUMERIC;
  function: AggregationFunction;
  expression: AggregationExpression<FIELDS>;
  alias: string;
}

export interface AnyAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.ANY;
  field: QueryField<FIELDS>;
  alias: string;
}

export interface DistinctCountAggregationMetric<
  FIELDS extends string = string,
> {
  type: AggregationMetricType.DISTINCT_COUNT;
  expression: AggregationExpression<FIELDS>;
  alias: string;
  filter?: FilterExpression<FIELDS>;
}

export interface PercentileAggregationMetric<FIELDS extends string = string> {
  type: AggregationMetricType.PERCENTILE;
  expression: AggregationExpression<FIELDS>;
  percentile: number;
  alias: string;
  filter?: FilterExpression<FIELDS>;
}

export enum DerivedExpressionType {
  METRIC_REF = 'METRIC_REF',
  CONSTANT = 'CONSTANT',
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

export interface DerivedAggregationMetric {
  type: AggregationMetricType.DERIVED;
  expression: DerivedExpression;
  alias: string;
}

export enum HavingExpressionType {
  CONDITION = 'CONDITION',
  BETWEEN = 'BETWEEN',
  IN = 'IN',
  IS_NULL = 'IS_NULL',
  AND = 'AND',
  OR = 'OR',
}

export enum ComparisonOperator {
  EQ = 'EQ',
  NE = 'NE',
  GT = 'GT',
  GTE = 'GTE',
  LT = 'LT',
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

export type AggregationMetric<FIELDS extends string = string> =
  | CountAggregationMetric<FIELDS>
  | NumericAggregationMetric<FIELDS>
  | AnyAggregationMetric<FIELDS>
  | DistinctCountAggregationMetric<FIELDS>
  | PercentileAggregationMetric<FIELDS>
  | DerivedAggregationMetric;

export interface AggregationQuery<
  ROOT_FIELDS extends string = string,
  AGGREGATION_FIELDS extends string = ROOT_FIELDS,
> {
  filter?: FilterExpression<ROOT_FIELDS>;
  elements?: AggregationElement[];
  groupBy?: AggregationGroup<AGGREGATION_FIELDS>[];
  metrics: [
    AggregationMetric<AGGREGATION_FIELDS>,
    ...AggregationMetric<AGGREGATION_FIELDS>[],
  ];
  sort?: FieldSort[];
  limit?: number;
  having?: HavingExpression;
}

export interface HistogramAggregationOptions {
  interval: number;
  alias: string;
}

export interface DateHistogramAggregationOptions {
  unit: AggregationDateUnit;
  alias: string;
  timeZone?: string;
  dense?: boolean;
}

function aggregationField<FIELDS extends string>(field: FIELDS): FIELDS {
  return filter.exists(field).field;
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
  predicate?: FilterExpression<FIELDS>,
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
 * The sizes Wow's own `AggregationQuery` enforces by throwing.
 *
 * A query that breaks one of them is refused by the server, which says what
 * was wrong but not which metric or group said it. Checking here keeps the
 * complaint next to the call that built the query.
 */
export const AGGREGATION_LIMITS = Object.freeze({
  /** Rows returned when a query names no limit of its own. */
  DEFAULT_LIMIT: 100,
  MAX_LIMIT: 10_000,
  MAX_ELEMENTS: 5,
  MAX_GROUPS: 32,
  MAX_METRICS: 64,
  MAX_SORT_FIELDS: 32,
  MAX_EXPRESSION_DEPTH: 8,
  MAX_EXPRESSION_NODES: 256,
});

function requireAtMost(
  name: string,
  noun: string,
  items: readonly unknown[],
  max: number,
): void {
  if (items.length > max) {
    throw new TypeError(`${name} must contain at most ${max} ${noun}.`);
  }
}

/**
 * Refuses the filters a metric cannot carry, whatever the aggregate holds.
 *
 * A metric filter is a whole-value predicate on one record — MongoDB turns it
 * into a `$cond` guard and Elasticsearch into a filter aggregation — and
 * element matching and full text have no whole-value reading. Both backends
 * refuse them before touching a schema, so the shape alone settles it. What a
 * schema would add, refusing an array-valued field, stays on the server.
 */
function validateMetricFilter(filter: FilterExpression): void {
  switch (filter.op) {
    case FilterOperator.SEARCH:
      throw new TypeError(
        'Aggregation metric filters do not support search filters.',
      );
    case FilterOperator.ELEMENT_MATCH:
      throw new TypeError(
        'Aggregation metric filters do not support [ELEMENT_MATCH].',
      );
    case FilterOperator.AND:
    case FilterOperator.OR:
    case FilterOperator.NOR:
      filter.operands.forEach(validateMetricFilter);
      break;
  }
}

/**
 * Walks the arithmetic of every metric that carries an expression.
 *
 * Depth is per expression and the node count is one budget over all of them,
 * so a query cannot slip past by spreading a large tree across many metrics.
 */
function validateExpressions(metrics: readonly AggregationMetric[]): void {
  const pending: { expression: AggregationExpression; depth: number }[] = [];
  metrics.forEach(metric => {
    if (
      metric.type === AggregationMetricType.NUMERIC ||
      metric.type === AggregationMetricType.DISTINCT_COUNT ||
      metric.type === AggregationMetricType.PERCENTILE
    ) {
      pending.push({ expression: metric.expression, depth: 1 });
    }
  });

  let nodes = 0;
  while (pending.length > 0) {
    const { expression, depth } = pending.pop()!;
    if (depth > AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH) {
      throw new TypeError(
        `aggregation expression depth must be at most ${AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH}.`,
      );
    }
    nodes++;
    if (nodes > AGGREGATION_LIMITS.MAX_EXPRESSION_NODES) {
      throw new TypeError(
        `aggregation expressions must contain at most ${AGGREGATION_LIMITS.MAX_EXPRESSION_NODES} nodes.`,
      );
    }
    switch (expression.type) {
      case AggregationExpressionType.FIELD:
        break;
      // `aggregation.constant` refuses these, but a query rebuilt from a
      // stored config never passed through it, and JSON has no NaN: the value
      // would serialise to null and be refused on arrival instead.
      case AggregationExpressionType.CONSTANT:
        if (!Number.isFinite(expression.value)) {
          throw new TypeError('aggregation constant must be finite.');
        }
        break;
      case AggregationExpressionType.BINARY:
        pending.push({ expression: expression.left, depth: depth + 1 });
        pending.push({ expression: expression.right, depth: depth + 1 });
        break;
      default:
        throw new TypeError(
          `Unsupported aggregation expression: ${String((expression as { type: unknown }).type)}.`,
        );
    }
  }
}

/**
 * Admits the derived metrics against what was declared before each of them.
 *
 * Derived arithmetic runs after the aggregation, over values the query has
 * already produced, so a reference may only name a metric declared earlier —
 * that is what makes the list evaluable in one pass — and never an `ANY`
 * metric, whose value is a sample rather than a number.
 */
function validateDerivedMetrics(metrics: readonly AggregationMetric[]): void {
  const declared = new Map<string, boolean>();
  let nodes = 0;
  metrics.forEach(metric => {
    if (metric.type === AggregationMetricType.DERIVED) {
      nodes = validateDerivedExpression(metric, declared, nodes);
    }
    declared.set(metric.alias, metric.type === AggregationMetricType.ANY);
  });
}

function validateDerivedExpression(
  metric: DerivedAggregationMetric,
  declared: ReadonlyMap<string, boolean>,
  visitedNodes: number,
): number {
  const pending: { expression: DerivedExpression; depth: number }[] = [
    { expression: metric.expression, depth: 1 },
  ];
  let nodes = visitedNodes;
  while (pending.length > 0) {
    const { expression, depth } = pending.pop()!;
    if (depth > AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH) {
      throw new TypeError(
        `derived expression depth must be at most ${AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH}.`,
      );
    }
    nodes++;
    if (nodes > AGGREGATION_LIMITS.MAX_EXPRESSION_NODES) {
      throw new TypeError(
        `derived expressions must contain at most ${AGGREGATION_LIMITS.MAX_EXPRESSION_NODES} nodes.`,
      );
    }
    switch (expression.type) {
      case DerivedExpressionType.METRIC_REF: {
        const reference = expression.metric;
        if (!declared.has(reference)) {
          throw new TypeError(
            `derived metric [${metric.alias}] must reference a metric declared before it, but was [${reference}].`,
          );
        }
        if (declared.get(reference)) {
          throw new TypeError(
            `derived metric [${metric.alias}] cannot reference ANY metric [${reference}].`,
          );
        }
        break;
      }
      case DerivedExpressionType.CONSTANT:
        if (!Number.isFinite(expression.value)) {
          throw new TypeError('derived constant must be finite.');
        }
        break;
      case DerivedExpressionType.BINARY:
        pending.push({ expression: expression.left, depth: depth + 1 });
        pending.push({ expression: expression.right, depth: depth + 1 });
        break;
      default:
        throw new TypeError(
          `Unsupported derived expression: ${String((expression as { type: unknown }).type)}.`,
        );
    }
  }
  return nodes;
}

function requireValidHavingMetric(
  metric: string,
  metricAliases: ReadonlySet<string>,
  anyAliases: ReadonlySet<string>,
): void {
  if (!metricAliases.has(metric)) {
    throw new TypeError(
      `having condition [${metric}] must reference a declared metric alias.`,
    );
  }
  if (anyAliases.has(metric)) {
    throw new TypeError(
      `having condition [${metric}] cannot reference ANY metric.`,
    );
  }
}

/**
 * `having` filters the grouped rows, so it needs rows to filter: a query with
 * no `groupBy` produces one row and has nothing to select from.
 */
function validateHaving(
  having: HavingExpression | undefined,
  groupBy: readonly AggregationGroup[],
  metrics: readonly AggregationMetric[],
): void {
  if (having === undefined) return;
  if (groupBy.length === 0) {
    throw new TypeError('having requires at least one groupBy.');
  }
  const metricAliases = new Set(metrics.map(metric => metric.alias));
  const anyAliases = new Set(
    metrics
      .filter(metric => metric.type === AggregationMetricType.ANY)
      .map(metric => metric.alias),
  );

  const pending: { expression: HavingExpression; depth: number }[] = [
    { expression: having, depth: 1 },
  ];
  while (pending.length > 0) {
    const { expression, depth } = pending.pop()!;
    if (depth > AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH) {
      throw new TypeError(
        `having expression depth must be at most ${AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH}.`,
      );
    }
    switch (expression.type) {
      case HavingExpressionType.CONDITION:
        requireValidHavingMetric(expression.metric, metricAliases, anyAliases);
        if (!Number.isFinite(expression.value)) {
          throw new TypeError(
            `having condition [${expression.metric}] value must be finite.`,
          );
        }
        break;
      case HavingExpressionType.BETWEEN:
        requireValidHavingMetric(expression.metric, metricAliases, anyAliases);
        if (
          !Number.isFinite(expression.lower) ||
          !Number.isFinite(expression.upper)
        ) {
          throw new TypeError(
            `having between [${expression.metric}] bounds must be finite.`,
          );
        }
        if (expression.lower > expression.upper) {
          throw new TypeError(
            `having between [${expression.metric}] lower bound must not exceed upper bound.`,
          );
        }
        break;
      case HavingExpressionType.IN:
        requireValidHavingMetric(expression.metric, metricAliases, anyAliases);
        if (expression.values.length === 0) {
          throw new TypeError(
            `having in [${expression.metric}] values must not be empty.`,
          );
        }
        if (!expression.values.every(Number.isFinite)) {
          throw new TypeError(
            `having in [${expression.metric}] values must be finite.`,
          );
        }
        break;
      case HavingExpressionType.IS_NULL:
        requireValidHavingMetric(expression.metric, metricAliases, anyAliases);
        break;
      case HavingExpressionType.AND:
      case HavingExpressionType.OR:
        if (expression.operands.length === 0) {
          throw new TypeError(
            `having ${expression.type} operands must not be empty.`,
          );
        }
        expression.operands.forEach(operand =>
          pending.push({ expression: operand, depth: depth + 1 }),
        );
        break;
      default:
        throw new TypeError(
          `Unsupported having expression: ${String((expression as { type: unknown }).type)}.`,
        );
    }
  }
}

/**
 * The sort Wow actually applies: what the query asked for, then each remaining
 * group ascending, so a page of grouped rows has one stable order.
 */
export function effectiveSort<FIELDS extends string = string>(
  query: Pick<AggregationQuery<string, FIELDS>, 'groupBy' | 'sort'>,
): FieldSort[] {
  const sort = [...(query.sort ?? [])];
  const sorted = new Set(sort.map(entry => entry.field));
  return [
    ...sort,
    ...(query.groupBy ?? [])
      .map(group => group.alias)
      .filter(alias => !sorted.has(alias))
      .map(asc),
  ];
}

function validateSort(
  sort: readonly FieldSort[],
  groupBy: readonly AggregationGroup[],
  aliases: readonly string[],
): void {
  requireAtMost('sort', 'fields', sort, AGGREGATION_LIMITS.MAX_SORT_FIELDS);
  if (groupBy.length === 0 && sort.length > 0) {
    throw new TypeError('sort requires at least one groupBy.');
  }
  const fields = sort.map(entry => entry.field);
  if (new Set(fields).size !== fields.length) {
    throw new TypeError('sort fields must be unique.');
  }
  if (!fields.every(field => aliases.includes(field))) {
    throw new TypeError('sort fields must reference aggregation aliases.');
  }
  requireAtMost(
    'effective sort',
    'fields',
    effectiveSort({ groupBy: [...groupBy], sort: [...sort] }),
    AGGREGATION_LIMITS.MAX_SORT_FIELDS,
  );
}

export const aggregation = {
  element(
    path: string,
    predicate?: ElementFilterExpression,
  ): AggregationElement {
    const validPath = aggregationField(path);
    if (predicate === undefined) return { path: validPath };
    requireElementScopedFilter(predicate, 'Aggregation element filter');
    return { path: validPath, filter: predicate };
  },
  field<FIELDS extends string>(
    field: FIELDS,
  ): FieldAggregationExpression<FIELDS> {
    return {
      type: AggregationExpressionType.FIELD,
      field: aggregationField(field),
    };
  },
  constant(value: number): ConstantAggregationExpression {
    if (!Number.isFinite(value)) {
      throw new TypeError('aggregation constant must be finite.');
    }
    return { type: AggregationExpressionType.CONSTANT, value };
  },
  add: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.ADD, left, right),
  subtract: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.SUBTRACT, left, right),
  multiply: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.MULTIPLY, left, right),
  divide: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.DIVIDE, left, right),
  terms<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    missingKey?: string,
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
  histogram<FIELDS extends string>(
    field: FIELDS,
    { interval, alias }: HistogramAggregationOptions,
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
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    { unit, alias, timeZone = 'UTC', dense }: DateHistogramAggregationOptions,
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
  any<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ): AnyAggregationMetric<FIELDS> {
    return {
      type: AggregationMetricType.ANY,
      ...(predicate === undefined ? {} : { filter: predicate }),
      field: aggregationField(field),
      alias: aggregationAlias(alias),
    };
  },
  count<FIELDS extends string = string>(
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ): CountAggregationMetric<FIELDS> {
    return {
      type: AggregationMetricType.COUNT,
      ...(predicate === undefined ? {} : { filter: predicate }),
      alias: aggregationAlias(alias),
    };
  },
  sum: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => numeric(AggregationFunction.SUM, expression, alias, predicate),
  avg: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => numeric(AggregationFunction.AVG, expression, alias, predicate),
  min: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => numeric(AggregationFunction.MIN, expression, alias, predicate),
  max: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => numeric(AggregationFunction.MAX, expression, alias, predicate),
  stddev: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => numeric(AggregationFunction.STDDEV, expression, alias, predicate),
  variance: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => numeric(AggregationFunction.VARIANCE, expression, alias, predicate),
  distinctCount<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ): DistinctCountAggregationMetric<FIELDS> {
    return {
      type: AggregationMetricType.DISTINCT_COUNT,
      expression,
      alias: aggregationAlias(alias),
      ...(predicate === undefined ? {} : { filter: predicate }),
    };
  },
  percentile<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    percentile: number,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
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
  derived(
    expression: DerivedExpression,
    alias: string,
  ): DerivedAggregationMetric {
    return {
      type: AggregationMetricType.DERIVED,
      expression,
      alias: aggregationAlias(alias),
    };
  },

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
    const elements = query.elements ?? [];
    const groupBy = query.groupBy ?? [];
    const metrics = query.metrics;
    const sort = query.sort ?? [];

    requireAtMost(
      'elements',
      'paths',
      elements,
      AGGREGATION_LIMITS.MAX_ELEMENTS,
    );
    requireAtMost(
      'groupBy',
      'dimensions',
      groupBy,
      AGGREGATION_LIMITS.MAX_GROUPS,
    );
    if (!Array.isArray(metrics) || metrics.length === 0) {
      throw new TypeError('metrics must not be empty.');
    }
    requireAtMost(
      'metrics',
      'entries',
      metrics,
      AGGREGATION_LIMITS.MAX_METRICS,
    );
    if (query.limit !== undefined) {
      if (
        !Number.isInteger(query.limit) ||
        query.limit < 1 ||
        query.limit > AGGREGATION_LIMITS.MAX_LIMIT
      ) {
        throw new TypeError(
          `limit must be between 1 and ${AGGREGATION_LIMITS.MAX_LIMIT}.`,
        );
      }
    }

    validateExpressions(metrics);
    validateDerivedMetrics(metrics);
    validateHaving(query.having, groupBy, metrics);
    metrics.forEach(metric => {
      if ('filter' in metric && metric.filter)
        validateMetricFilter(metric.filter);
    });

    // A DATE_HISTOGRAM fills in the buckets its range implies rather than only
    // the ones with rows, and a second dimension would multiply that filling
    // out across every one of its own keys.
    groupBy.forEach(group => {
      if (
        group.type === AggregationGroupType.DATE_HISTOGRAM &&
        group.dense === true &&
        groupBy.length !== 1
      ) {
        throw new TypeError(
          'dense requires DATE_HISTOGRAM to be the only groupBy.',
        );
      }
    });

    const aliases = [
      ...groupBy.map(group => group.alias),
      ...metrics.map(metric => metric.alias),
    ];
    if (new Set(aliases).size !== aliases.length) {
      throw new TypeError('aggregation aliases must be unique.');
    }
    validateSort(sort, groupBy, aliases);

    return {
      ...query,
      metrics: [...metrics] as typeof query.metrics,
    };
  },
};
