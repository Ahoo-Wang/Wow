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
  AggregationExpressionType,
  AggregationGroupType,
  AggregationMetricType,
  DerivedExpressionType,
  SortDirection,
  type AggregationDateUnit,
  type AggregationExpressionOperator,
  type AggregationFunction,
  type AggregationElement,
  type AggregationExpression,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type DerivedExpression,
  type FieldSort,
  type FilterExpression,
  type HavingExpression,
} from '@ahoo-wang/fetcher-wow';
import type {
  AnalysisDerivedExpression,
  AnalysisExpression,
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  DataViewDefinition,
  FilterTree,
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
    limit: config.limit,
  };
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
  if (!config.table.totals) return null;
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

function compileDerived(
  expression: AnalysisDerivedExpression,
): DerivedExpression {
  switch (expression.type) {
    case 'METRIC_REF':
      return {
        type: DerivedExpressionType.METRIC_REF,
        metric: expression.metric,
      };
    case 'CONSTANT':
      return {
        type: DerivedExpressionType.CONSTANT,
        value: expression.value,
      };
    case 'BINARY':
      return {
        type: DerivedExpressionType.BINARY,
        operator: expression.operator as AggregationExpressionOperator,
        left: compileDerived(expression.left),
        right: compileDerived(expression.right),
      };
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
      return {
        type: AggregationMetricType.DERIVED,
        expression: compileDerived(metric.expression),
        alias: metric.alias,
      };
    default:
      // Admission refuses a type this version does not know, so reaching
      // here is a programming error. Falling off the switch instead would put
      // an `undefined` into `metrics` and let Wow report the hole.
      throw new Error(
        `Unknown analysis metric type ${String((metric as { type: unknown }).type)}`,
      );
  }
}

function compileHaving(
  having: NonNullable<AnalysisViewConfig['having']>,
): HavingExpression {
  return having as HavingExpression;
}

function compileSort(config: AnalysisViewConfig): FieldSort[] {
  return config.sort.map(sort => ({
    field: sort.alias,
    direction:
      sort.direction === 'DESC' ? SortDirection.DESC : SortDirection.ASC,
  }));
}
