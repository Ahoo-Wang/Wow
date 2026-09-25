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

import { FilterOperator, type FilterExpression } from '../filter/index.js';
import type { FieldSort } from '../sort.js';
import { requireValidExpressionTrees } from './expressionTrees.js';
import { effectiveSort } from './sort.js';
import {
  AGGREGATION_LIMITS,
  AggregationGroupType,
  AggregationMetricType,
  DerivedExpressionType,
  HavingExpressionType,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type DerivedAggregationMetric,
  type DerivedExpression,
  type HavingExpression,
} from './types.js';

// Internal: the rules `aggregation.query()` checks across the parts of a
// query. index.ts does not re-export this file.

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
 * Walks the arithmetic of every metric that carries an expression, as one
 * budget, and of each expression group on its own, as Wow does.
 */
function validateExpressions(
  metrics: readonly AggregationMetric[],
  groupBy: readonly AggregationGroup[],
): void {
  requireValidExpressionTrees(
    metrics.flatMap(metric =>
      metric.type === AggregationMetricType.NUMERIC ||
      metric.type === AggregationMetricType.DISTINCT_COUNT ||
      metric.type === AggregationMetricType.PERCENTILE
        ? [metric.expression]
        : [],
    ),
  );
  groupBy.forEach(group => {
    if (
      group.type !== AggregationGroupType.TERMS &&
      group.type !== AggregationGroupType.HISTOGRAM
    )
      return;
    if ((group.field === undefined) === (group.expression === undefined)) {
      throw new TypeError(
        `${group.type} group requires exactly one of field and expression.`,
      );
    }
    if (group.expression) requireValidExpressionTrees([group.expression]);
    if (
      group.type === AggregationGroupType.TERMS &&
      group.missingKey !== undefined &&
      group.expression
    ) {
      throw new TypeError('terms missingKey requires a field input.');
    }
  });
}

/** The metric types whose value is not a number: derived and having refuse them. */
const NON_NUMERIC: ReadonlySet<string> = new Set([
  AggregationMetricType.ANY,
  AggregationMetricType.FIRST,
  AggregationMetricType.LAST,
]);

/**
 * Admits the derived metrics against what was declared before each of them.
 *
 * Derived arithmetic runs after the aggregation, over values the query has
 * already produced, so a reference may only name a metric declared earlier —
 * that is what makes the list evaluable in one pass — and never an `ANY`,
 * `FIRST` or `LAST` metric, whose value is a field's value rather than a
 * number.
 */
function validateDerivedMetrics(metrics: readonly AggregationMetric[]): void {
  // Each earlier alias, with its type when that is not a number.
  const declared = new Map<string, string | undefined>();
  let nodes = 0;
  metrics.forEach(metric => {
    if (metric.type === AggregationMetricType.DERIVED) {
      nodes = validateDerivedExpression(metric, declared, nodes);
    }
    declared.set(
      metric.alias,
      NON_NUMERIC.has(metric.type) ? metric.type : undefined,
    );
  });
}

function validateDerivedExpression(
  metric: DerivedAggregationMetric,
  declared: ReadonlyMap<string, string | undefined>,
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
        const nonNumeric = declared.get(reference);
        if (nonNumeric !== undefined) {
          throw new TypeError(
            `derived metric [${metric.alias}] cannot reference ${nonNumeric} metric [${reference}].`,
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
  edgeAliases: ReadonlySet<string>,
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
  if (edgeAliases.has(metric)) {
    throw new TypeError(
      `having condition [${metric}] cannot reference FIRST or LAST metric.`,
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
  const edgeAliases = new Set(
    metrics
      .filter(
        metric =>
          metric.type === AggregationMetricType.FIRST ||
          metric.type === AggregationMetricType.LAST,
      )
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
        requireValidHavingMetric(
          expression.metric,
          metricAliases,
          anyAliases,
          edgeAliases,
        );
        if (!Number.isFinite(expression.value)) {
          throw new TypeError(
            `having condition [${expression.metric}] value must be finite.`,
          );
        }
        break;
      case HavingExpressionType.BETWEEN:
        requireValidHavingMetric(
          expression.metric,
          metricAliases,
          anyAliases,
          edgeAliases,
        );
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
        requireValidHavingMetric(
          expression.metric,
          metricAliases,
          anyAliases,
          edgeAliases,
        );
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
        requireValidHavingMetric(
          expression.metric,
          metricAliases,
          anyAliases,
          edgeAliases,
        );
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

/**
 * Admits a whole aggregation query against the rules Wow enforces on arrival;
 * {@link aggregation.query} is its public face.
 */
export function admitAggregationQuery<
  ROOT_FIELDS extends string = string,
  AGGREGATION_FIELDS extends string = ROOT_FIELDS,
>(
  query: AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS>,
): AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS> {
  const elements = query.elements ?? [];
  const groupBy = query.groupBy ?? [];
  const metrics = query.metrics;
  const sort = query.sort ?? [];

  requireAtMost('elements', 'paths', elements, AGGREGATION_LIMITS.MAX_ELEMENTS);
  requireAtMost(
    'groupBy',
    'dimensions',
    groupBy,
    AGGREGATION_LIMITS.MAX_GROUPS,
  );
  if (!Array.isArray(metrics) || metrics.length === 0) {
    throw new TypeError('metrics must not be empty.');
  }
  requireAtMost('metrics', 'entries', metrics, AGGREGATION_LIMITS.MAX_METRICS);
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

  validateExpressions(metrics, groupBy);
  validateDerivedMetrics(metrics);
  validateHaving(query.having, groupBy, metrics);
  metrics.forEach(metric => {
    if ('filter' in metric && metric.filter)
      validateMetricFilter(metric.filter);
  });

  // A dense DATE_HISTOGRAM fills in the buckets its range implies, and a
  // dense DATE_PART every key of its part's domain, rather than only the ones
  // with rows; a second dimension would multiply that filling out across
  // every one of its own keys.
  groupBy.forEach(group => {
    if (
      (group.type === AggregationGroupType.DATE_HISTOGRAM ||
        group.type === AggregationGroupType.DATE_PART) &&
      group.dense === true &&
      groupBy.length !== 1
    ) {
      throw new TypeError(
        `dense requires ${group.type} to be the only groupBy.`,
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
}
