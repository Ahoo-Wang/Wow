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

import type { AnalysisResultColumn } from './analysisModel.js';
import type { AnalysisVisualizationType } from './analysisVisualizations.js';
import type { DeepReadonly } from '../lib/types.js';

export interface AnalysisPresentation {
  layout: AnalysisVisualizationType;
  columns: { alias: string; width?: number }[];
  x?: string;
  series?: string;
  metrics?: string[];
  orientation?: 'vertical' | 'horizontal';
  stacked?: boolean;
  donut?: boolean;
}

/** 类型保持的数组守卫：Array.isArray 的 any[] 谓词会把 readonly 数组退化为 any[]。 */
function isReadonlyArray(value: unknown): value is readonly unknown[] {
  return Array.isArray(value);
}

/** Resolve implicit selection before filtering selectable candidates; never hide current intent. */
export function resolveAnalysisMetricAliases(
  schema: DeepReadonly<readonly AnalysisResultColumn[]>,
  value: DeepReadonly<Pick<AnalysisPresentation, 'metrics'>>,
): string[] {
  return isReadonlyArray(value.metrics)
    ? [...value.metrics]
    : schema
        .filter(
          column =>
            column.role === 'metric' &&
            column.valueType === 'number' &&
            column.aggregation !== 'ANY',
        )
        .map(column => column.alias);
}

/** Retain display preferences; remove only references to outputs that no longer exist. */
export function pruneAnalysisPresentation(
  value: DeepReadonly<AnalysisPresentation>,
  dimensions: readonly {
    readonly alias: string;
    readonly label?: { readonly alias: string };
  }[],
  metrics: readonly { readonly alias: string }[],
): AnalysisPresentation {
  const outputs = [
    ...dimensions,
    ...metrics,
    ...dimensions.flatMap(d => (d.label ? [d.label] : [])),
  ];
  const x = dimensions.some(column => column.alias === value.x)
    ? value.x
    : undefined;
  const series =
    dimensions.some(column => column.alias === value.series) &&
    value.series !== (x ?? dimensions[0]?.alias)
      ? value.series
      : undefined;
  const selected = isReadonlyArray(value.metrics)
    ? value.metrics.filter(alias =>
        metrics.some(column => column.alias === alias),
      )
    : undefined;
  return {
    ...value,
    columns: isReadonlyArray(value.columns)
      ? value.columns
          .filter(
            column =>
              column && outputs.some(output => output.alias === column.alias),
          )
          .map(column => ({ ...column }))
      : [],
    x,
    series,
    // An explicit empty selection remains editable; deleting its last referenced output restores defaults.
    metrics:
      selected?.length || value.metrics?.length === 0 ? selected : undefined,
  };
}

/** Infer the only useful mapping for one or two grouped dimensions. */
export function resolveAnalysisAxes<T extends { readonly alias: string }>(
  dimensions: readonly T[],
  value: DeepReadonly<AnalysisPresentation>,
) {
  const x =
    value.x !== undefined
      ? dimensions.find(d => d.alias === value.x)
      : dimensions[0];
  const series =
    dimensions.find(d => d.alias === value.series) ??
    (value.series === undefined && !!x && dimensions.length === 2
      ? dimensions.find(d => d !== x)
      : undefined);
  return { x, series };
}
