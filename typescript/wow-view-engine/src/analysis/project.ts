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
  AnalysisViewConfig,
  DataViewDefinition,
  NumberFormat,
  RecordData,
} from '../model/index.js';
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

function labelOf(definition: DataViewDefinition, field: string): string {
  return definition.fields.find(entry => entry.name === field)?.label ?? field;
}

function numberFormatOf(
  definition: DataViewDefinition,
  field: string,
): NumberFormat | undefined {
  return definition.fields.find(entry => entry.name === field)?.numberFormat;
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
    ...config.metrics.flatMap(metric =>
      metric.type === 'NUMERIC' && metric.expression.type === 'FIELD'
        ? ([[metric.alias, metric.expression.field]] as const)
        : ([] as const),
    ),
  ]);

  const columns = order.flatMap<AnalysisColumnView>(alias => {
    const role = roles.get(alias);
    if (!role) return [];
    const field = sourceField.get(alias);
    const declaredColumn = declared.get(alias);
    return [
      {
        alias,
        label: field ? labelOf(definition, field) : alias,
        role,
        width: declaredColumn?.width,
        pinned: declaredColumn?.pinned,
        numberFormat: field ? numberFormatOf(definition, field) : undefined,
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
