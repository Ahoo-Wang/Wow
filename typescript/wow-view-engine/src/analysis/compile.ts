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
  elementScopeFields,
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
  const query = baseQuery(definition, config, kinds, context);
  return {
    ...query,
    ...(config.groups.length > 0
      ? { groupBy: config.groups.map(compileGroup) }
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
  return baseQuery(definition, config, kinds, context);
}

function baseQuery(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): AggregationQuery {
  const capability = definition.analysis;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no analysis capability`,
    );
  const scope = analysisScope(definition, capability, config);
  const fields = [...scope.fields.values()];

  const compileTree = (tree: FilterTree): FilterExpression =>
    compileFilter(fields, tree, kinds, context);

  const metrics = config.metrics.map(metric =>
    compileMetric(metric, compileTree),
  );

  return {
    filter: compileTree(config.filter),
    ...(config.elements && config.elements.length > 0
      ? {
          elements: config.elements.map(element =>
            compileElement(element, scope, kinds, context),
          ),
        }
      : {}),
    metrics: metrics as [AggregationMetric, ...AggregationMetric[]],
  };
}

function compileElement(
  element: NonNullable<AnalysisViewConfig['elements']>[number],
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): AggregationElement {
  if (!element.filter) return { path: element.path };
  return {
    path: element.path,
    filter: compileFilter(
      elementScopeFields(scope, element.path),
      element.filter,
      kinds,
      context,
    ) as never,
  };
}

function compileGroup(group: AnalysisGroup): AggregationGroup {
  switch (group.type) {
    case 'TERMS':
      return {
        type: AggregationGroupType.TERMS,
        field: group.field,
        alias: group.alias,
        ...(group.missingKey === undefined
          ? {}
          : { missingKey: group.missingKey }),
      };
    case 'HISTOGRAM':
      return {
        type: AggregationGroupType.HISTOGRAM,
        field: group.field,
        alias: group.alias,
        interval: group.interval,
      };
    case 'DATE_HISTOGRAM':
      return {
        type: AggregationGroupType.DATE_HISTOGRAM,
        field: group.field,
        alias: group.alias,
        unit: group.unit as AggregationDateUnit,
        ...(group.timeZone === undefined ? {} : { timeZone: group.timeZone }),
        ...(group.dense === undefined ? {} : { dense: group.dense }),
      };
  }
}

function compileExpression(
  expression: AnalysisExpression,
): AggregationExpression {
  switch (expression.type) {
    case 'FIELD':
      return {
        type: AggregationExpressionType.FIELD,
        field: expression.field,
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
        left: compileExpression(expression.left),
        right: compileExpression(expression.right),
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
        expression: compileExpression(metric.expression),
        alias: metric.alias,
        ...predicate,
      };
    case 'ANY':
      return {
        type: AggregationMetricType.ANY,
        field: metric.field,
        alias: metric.alias,
        ...predicate,
      };
    case 'DISTINCT_COUNT':
      return {
        type: AggregationMetricType.DISTINCT_COUNT,
        expression: compileExpression(metric.expression),
        alias: metric.alias,
        ...predicate,
      };
    case 'PERCENTILE':
      return {
        type: AggregationMetricType.PERCENTILE,
        expression: compileExpression(metric.expression),
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
