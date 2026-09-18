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
  AnalysisMetric,
  AnalysisViewConfig,
  DataViewDefinition,
  FieldDefinition,
  NumberFormat,
  RecordData,
} from '../model/index.js';
import { analysisScope } from './capability.js';
import { shapeChart, type ChartData } from './chart.js';

/** A column of the result table; groups come first, then metrics. */
export interface AnalysisColumnView {
  alias: string;
  label: string;
  role: 'group' | 'metric';
  width?: number;
  pinned?: 'left' | 'right';
  numberFormat?: NumberFormat;
}

export interface AnalysisView {
  columns: AnalysisColumnView[];
  rows: RecordData[];
  /** Present only when `table.totals` asked for it and its query succeeded. */
  totals?: RecordData;
  /** Shaped for the configured chart family; absent when it cannot be drawn. */
  chart?: ChartData;
}

/** Result columns, which are also the schema a returned row must satisfy. */
export function resultSchema(config: AnalysisViewConfig): string[] {
  return [
    ...config.groups.map(group => group.alias),
    ...config.metrics.map(metric => metric.alias),
  ];
}

/**
 * The field a column is computed from, when it has one.
 *
 * Every metric that reads a single field names it: `ANY` directly, and the
 * three expression-carrying kinds through a `FIELD` expression. Only `NUMERIC`
 * used to be looked up, so a `DISTINCT_COUNT` of customers or a p95 of latency
 * fell back to its alias and lost both its label and its number format.
 */
function sourceFieldOf(metric: AnalysisMetric): string | undefined {
  if (metric.type === 'ANY') return metric.field;
  if (
    metric.type === 'NUMERIC' ||
    metric.type === 'DISTINCT_COUNT' ||
    metric.type === 'PERCENTILE'
  )
    return metric.expression?.type === 'FIELD'
      ? metric.expression.field
      : undefined;
  return undefined;
}

/**
 * Turns aggregation rows into table columns plus whatever the chart family
 * needs. A DERIVED metric is an ordinary column: the backend computed it.
 */
export function projectAnalysis(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  result: readonly RecordData[],
  totals?: readonly RecordData[],
): AnalysisView {
  const declared = new Map(
    config.table.columns.map(column => [column.alias, column]),
  );
  const order =
    config.table.columns.length > 0
      ? config.table.columns.map(column => column.alias)
      : resultSchema(config);

  const roles = new Map<string, 'group' | 'metric'>([
    ...config.groups.map(
      group => [group.alias, 'group'] as [string, 'group' | 'metric'],
    ),
    ...config.metrics.map(
      metric => [metric.alias, 'metric'] as [string, 'group' | 'metric'],
    ),
  ]);
  const sourceField = new Map<string, string>([
    ...config.groups.map(group => [group.alias, group.field] as const),
    ...config.metrics.flatMap(metric => {
      const field = sourceFieldOf(metric);
      return field === undefined
        ? ([] as const)
        : ([[metric.alias, field]] as const);
    }),
  ]);
  // The analysis scope, not the raw field list: an element field is addressed
  // as `items.sku`, which no root field is named, so a grouping or metric over
  // one used to be labelled by its alias.
  const byName = scopeFields(definition, config);

  const columns = order.flatMap<AnalysisColumnView>(alias => {
    const role = roles.get(alias);
    if (!role) return [];
    const source = sourceField.get(alias);
    const field = source === undefined ? undefined : byName.get(source);
    const declaredColumn = declared.get(alias);
    return [
      {
        alias,
        label: field?.label ?? source ?? alias,
        role,
        width: declaredColumn?.width,
        pinned: declaredColumn?.pinned,
        numberFormat: field?.numberFormat,
      },
    ];
  });

  return {
    columns,
    rows: [...result],
    ...(totals && totals.length > 0 ? { totals: totals[0] } : {}),
    ...(config.layout === 'chart'
      ? { chart: shapeChart(config, result, totals?.[0]) }
      : {}),
  };
}

/**
 * Root fields plus the fields of every expanded element, by their paths.
 *
 * The capability is required, as it is in `compileAnalysis` and
 * `defaultAnalysisConfig`: an analysis view of a definition that offers none
 * is a programming error, and admission reports it long before a result
 * arrives here.
 */
function scopeFields(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
): ReadonlyMap<string, FieldDefinition> {
  const capability = definition.analysis;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no analysis capability`,
    );
  return analysisScope(definition, capability, config).fields;
}
