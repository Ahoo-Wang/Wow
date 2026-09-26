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
  aggregation,
  AggregationExpressionType,
  AggregationGroupType,
  AggregationMetricType,
  SortDirection,
  type AggregationDatePart,
  type AggregationDateUnit,
  type AggregationExpressionOperator,
  type AggregationFunction,
  type AggregationElement,
  type AggregationExpression,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type DerivedExpression,
  type DerivedExpressionDsl,
  type FieldSort,
  type FilterExpression,
  type HavingExpression,
} from '@ahoo-wang/wow-client';
import type {
  AnalysisDerivedExpression,
  AnalysisHavingExpression,
  AnalysisExpression,
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  DataViewDefinition,
  FilterTree,
  RuntimeLimits,
} from '../model/index.js';
import {
  compileFilter,
  type FieldKindRegistry,
  type FilterCompileContext,
} from '../filter/index.js';
import {
  analysisScope,
  elementFilterFields,
  innermostElement,
  relativeFields,
  relativeName,
  relativeTree,
  scopePrefix,
  type AnalysisScope,
} from './capability.js';
import { limitBounds } from './defaults.js';

/**
 * Compilation is a mapping, not a translation: the configuration is
 * isomorphic to the Wow aggregation protocol and differs only in storing enum
 * values as literals and filters as trees. Admission happened in
 * `validateAnalysis`, so anything invalid here is a programming error.
 */
export function compileAnalysis(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
  limits?: Pick<RuntimeLimits, 'maxAnalysisRows'>,
): AggregationQuery {
  const scope = scopeOf(definition, config);
  const query = baseQuery(scope, config, kinds, context);
  const inner = innerPrefix(scope);
  return {
    ...query,
    ...(config.groups.length > 0
      ? {
          groupBy: config.groups.map(group =>
            compileGroup(group, context.timeZone, inner),
          ),
        }
      : {}),
    ...(config.having ? { having: compileHaving(config.having) } : {}),
    ...(config.sort.length > 0 ? { sort: compileSort(config) } : {}),
    limit: analysisProbeLimit(definition, config, limits),
  };
}

/**
 * The row count the grouped query asks for: one more than the configured
 * limit (D20 Ⅷ).
 *
 * An aggregation answers at most `limit` rows and says nothing about what it
 * left out, so a table that came back exactly full used to be reported as
 * "may have been cut short" — a grouping of exactly that size and a larger
 * one cut down to it look the same. Asking for one row more turns the guess
 * into an answer: the probe row either comes back, and there are more groups,
 * or it does not, and there are not. `projectAnalysis` drops it again, so the
 * reader still sees the `limit` rows they asked for.
 *
 * The extra row never passes a ceiling — the capability's own `maxLimit`,
 * the runtime's `maxAnalysisRows` (what the server admits, D42) and Wow's
 * `AGGREGATION_LIMITS.MAX_LIMIT`, the least of which `limitBounds` reads —
 * because a query beyond any is refused rather than answered, and trading the whole result for a probe is
 * not a trade worth making. A configured limit already sitting on that
 * ceiling therefore gets no probe at all: there is no row left to ask for,
 * and that one case keeps the old "may have been cut short".
 *
 * An analysis with no grouping asks one question and gets one row, so there
 * is nothing a ceiling could cut and nothing to probe for.
 */
export function analysisProbeLimit(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  limits?: Pick<RuntimeLimits, 'maxAnalysisRows'>,
): number {
  const limit = config.limit;
  // The limit is read as the untrusted number it is: `validateAnalysis`
  // refuses anything but a positive integer, but this is exported and a host
  // may compile a config nothing admitted. Passing it through unchanged
  // leaves that refusal to Wow, where it already was.
  if (config.groups.length === 0 || !Number.isInteger(limit) || limit < 1)
    return limit;
  return Math.min(limit + 1, limitBounds(definition.analysis, limits).max);
}

/**
 * Whether a config asks for its whole — the ungrouped answer — beside its
 * groups: when its table draws a totals row, or when its chart is a metric
 * card over a trend read as the whole (`MetricTrend.headline: 'whole'`).
 * Read as its last period — the default — the card's headline is one of
 * the buckets, and the whole is not asked.
 *
 * Without it that card added up its buckets, and those are only the groups
 * that fit the limit: a card over the first twenty days of a month said the
 * month was smaller than it was (the 2026-09-23 audit; the user chose a
 * second query over a sum labelled as partial). It is asked only when
 * something draws it, so a bar chart or a plain table costs one query as
 * before, and a dashboard of them no more than it did.
 */
export function asksForWhole(config: AnalysisViewConfig): boolean {
  // Without a dimension the answer is already the whole — one row over
  // every record in the range — and asking again would be the same query.
  if (config.groups.length === 0) return false;
  if (config.table.totals) return true;
  return (
    config.chart.type === 'metric' &&
    config.chart.metric?.trend?.headline === 'whole'
  );
}

/**
 * Totals run their own ungrouped query. Deriving them from the grouped rows
 * would be wrong for AVG, DISTINCT_COUNT and percentile.
 */
export function compileAnalysisTotals(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): AggregationQuery | null {
  if (!asksForWhole(config)) return null;
  return baseQuery(scopeOf(definition, config), config, kinds, context);
}

function scopeOf(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
): AnalysisScope {
  const capability = definition.analysis;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no analysis capability`,
    );
  return analysisScope(definition, capability, config);
}

/**
 * The prefix a dimension, a metric or a metric filter loses on its way out.
 *
 * With `elements`, Wow reads those names relative to the innermost expanded
 * element — the counting unit — while the config spells them out from the
 * query-model root. Without `elements` the two spellings are the same one.
 */
function innerPrefix(scope: AnalysisScope): string {
  return scopePrefix(innermostElement(scope.elements)?.absolute ?? '');
}

function baseQuery(
  scope: AnalysisScope,
  config: AnalysisViewConfig,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): AggregationQuery {
  const inner = innerPrefix(scope);
  const innerFields = relativeFields([...scope.fields.values()], inner);

  // A metric's filter decides, per record, whether that record counts, and a
  // record here is one entry of the innermost element, so it is written in
  // that element's names. The root filter runs before any expansion and keeps
  // the absolute ones.
  const compileTree = (tree: FilterTree): FilterExpression =>
    compileFilter(innerFields, relativeTree(tree, inner), kinds, context);

  const metrics = config.metrics.map(metric =>
    compileMetric(metric, compileTree, inner),
  );

  return {
    filter: compileFilter(scope.rootFields, config.filter, kinds, context),
    ...(config.elements && config.elements.length > 0
      ? {
          elements: config.elements.map((element, index) =>
            compileElement(element, index, scope, kinds, context),
          ),
        }
      : {}),
    metrics: metrics as [AggregationMetric, ...AggregationMetric[]],
  };
}

/**
 * One level of the expansion chain. Its `path` is already relative to the
 * level above it, as Wow reads it; its gate filter is relative to the element
 * itself, so the fields and the tree both shed that element's prefix.
 */
function compileElement(
  element: NonNullable<AnalysisViewConfig['elements']>[number],
  index: number,
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): AggregationElement {
  if (!element.filter) return { path: element.path };
  const prefix = scopePrefix(scope.elements[index]?.absolute ?? '');
  return {
    path: element.path,
    filter: compileFilter(
      relativeFields(elementFilterFields(scope, index), prefix),
      relativeTree(element.filter, prefix),
      kinds,
      context,
    ) as never,
  };
}

/**
 * A date histogram is cut in the engine's zone unless the group names one.
 * That is the zone "today" is evaluated in and the one the keys are shown in;
 * left to the backend, a day ran midnight to midnight UTC, which for most of
 * the world starts and ends mid-afternoon or mid-morning.
 */
function compileGroup(
  group: AnalysisGroup,
  timeZone: string,
  prefix: string,
): AggregationGroup {
  const field = relativeName(group.field, prefix);
  switch (group.type) {
    case 'TERMS':
      return {
        type: AggregationGroupType.TERMS,
        field,
        alias: group.alias,
        ...(group.missingKey === undefined
          ? {}
          : { missingKey: group.missingKey }),
      };
    case 'HISTOGRAM':
      return {
        type: AggregationGroupType.HISTOGRAM,
        field,
        alias: group.alias,
        interval: group.interval,
      };
    case 'DATE_HISTOGRAM':
      return {
        type: AggregationGroupType.DATE_HISTOGRAM,
        field,
        alias: group.alias,
        unit: group.unit as AggregationDateUnit,
        timeZone: group.timeZone ?? timeZone,
        ...(group.dense === undefined ? {} : { dense: group.dense }),
      };
    case 'DATE_PART':
      // Read on the same clock a date histogram is cut by: the weekday of an
      // order placed at 23:30 in Shanghai is the Shanghai one.
      return {
        type: AggregationGroupType.DATE_PART,
        field,
        alias: group.alias,
        part: group.part as AggregationDatePart,
        timeZone: group.timeZone ?? timeZone,
        ...(group.dense === undefined ? {} : { dense: group.dense }),
      };
  }
}

function compileExpression(
  expression: AnalysisExpression,
  prefix: string,
): AggregationExpression {
  switch (expression.type) {
    case 'FIELD':
      return {
        type: AggregationExpressionType.FIELD,
        field: relativeName(expression.field, prefix),
      };
    case 'CONSTANT':
      return {
        type: AggregationExpressionType.CONSTANT,
        value: expression.value,
      };
    case 'BINARY':
      return {
        type: AggregationExpressionType.BINARY,
        operator: expression.operator as AggregationExpressionOperator,
        left: compileExpression(expression.left, prefix),
        right: compileExpression(expression.right, prefix),
      };
  }
}

/**
 * The derived arithmetic, built through wow-client's `DerivedExpressionDsl`
 * so each node is Wow's own type rather than a literal cast to it.
 */
function compileDerived(
  expression: AnalysisDerivedExpression,
  d: DerivedExpressionDsl,
): DerivedExpression {
  switch (expression.type) {
    case 'METRIC_REF':
      return d.ref(expression.metric);
    case 'CONSTANT':
      return d.constant(expression.value);
    case 'BINARY': {
      const left = compileDerived(expression.left, d);
      const right = compileDerived(expression.right, d);
      switch (expression.operator) {
        case 'ADD':
          return d.add(left, right);
        case 'SUBTRACT':
          return d.subtract(left, right);
        case 'MULTIPLY':
          return d.multiply(left, right);
        case 'DIVIDE':
          return d.divide(left, right);
      }
    }
  }
}

function compileMetric(
  metric: AnalysisMetric,
  compileTree: (tree: FilterTree) => FilterExpression,
  prefix: string,
): AggregationMetric {
  // A DERIVED metric carries no filter in the protocol, and validation lets a
  // stale one through on the promise that it changes nothing. Compiling it
  // anyway would break that promise: `compileFilter` throws on a field the
  // scope no longer has, and the query would carry a filter Wow never reads.
  const predicate =
    metric.type !== 'DERIVED' && metric.filter
      ? { filter: compileTree(metric.filter) }
      : {};

  switch (metric.type) {
    case 'COUNT':
      return {
        type: AggregationMetricType.COUNT,
        alias: metric.alias,
        ...predicate,
      };
    case 'NUMERIC':
      return {
        type: AggregationMetricType.NUMERIC,
        function: metric.function as AggregationFunction,
        expression: compileExpression(metric.expression, prefix),
        alias: metric.alias,
        ...predicate,
      };
    case 'ANY':
      return {
        type: AggregationMetricType.ANY,
        field: relativeName(metric.field, prefix),
        alias: metric.alias,
        ...predicate,
      };
    case 'FIRST':
    case 'LAST':
      // Without `orderBy` the source orders by the model's event time, which
      // is only there at the root; admission asks an element's to name one.
      return {
        type: AggregationMetricType[metric.type],
        field: relativeName(metric.field, prefix),
        ...(metric.orderBy === undefined
          ? {}
          : { orderBy: relativeName(metric.orderBy, prefix) }),
        alias: metric.alias,
        ...predicate,
      };
    case 'DISTINCT_COUNT':
      return {
        type: AggregationMetricType.DISTINCT_COUNT,
        expression: compileExpression(metric.expression, prefix),
        alias: metric.alias,
        ...predicate,
      };
    case 'PERCENTILE':
      return {
        type: AggregationMetricType.PERCENTILE,
        expression: compileExpression(metric.expression, prefix),
        percentile: metric.percentile,
        alias: metric.alias,
        ...predicate,
      };
    case 'DERIVED':
      return aggregation.derived(
        d => compileDerived(metric.expression, d),
        metric.alias,
      );
    default:
      // Admission refuses a type this version does not know, so reaching
      // here is a programming error. Falling off the switch instead would put
      // an `undefined` into `metrics` and let Wow report the hole.
      throw new Error(
        `Unknown analysis metric type ${String((metric as { type: unknown }).type)}`,
      );
  }
}

/**
 * The having, built through `aggregation.having`: the stored literals become
 * Wow's `HavingExpression` node by node rather than by a cast, and a number
 * Wow would refuse is refused here, with Wow's message.
 */
function compileHaving(having: AnalysisHavingExpression): HavingExpression {
  const build = aggregation.having;
  switch (having.type) {
    case 'CONDITION':
      return COMPARISONS[having.operator](having.metric, having.value);
    case 'BETWEEN':
      return build.between(having.metric, having.lower, having.upper);
    case 'IN':
      return build.isIn(having.metric, having.values);
    case 'IS_NULL':
      return having.negated
        ? build.isNotNull(having.metric)
        : build.isNull(having.metric);
    case 'AND':
      return build.and(having.operands.map(compileHaving));
    case 'OR':
      return build.or(having.operands.map(compileHaving));
  }
}

/** Each stored comparison, by the `aggregation.having` builder that makes it. */
const COMPARISONS: Record<
  Extract<AnalysisHavingExpression, { type: 'CONDITION' }>['operator'],
  (metric: string, value: number) => HavingExpression
> = {
  EQ: aggregation.having.eq,
  NE: aggregation.having.ne,
  GT: aggregation.having.gt,
  GTE: aggregation.having.gte,
  LT: aggregation.having.lt,
  LTE: aggregation.having.lte,
};

function compileSort(config: AnalysisViewConfig): FieldSort[] {
  return config.sort.map(sort => ({
    field: sort.alias,
    direction:
      sort.direction === 'DESC' ? SortDirection.DESC : SortDirection.ASC,
  }));
}
