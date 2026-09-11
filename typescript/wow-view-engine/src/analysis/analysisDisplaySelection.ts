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

import type { DeepReadonly } from '../lib/types.js';
import type { AnalysisPlan, AnalysisRow } from './analysisModel.js';
import type { AnalysisPresentation } from './analysisPresentation.js';
import { ANALYSIS_VISUALIZATIONS } from './analysisVisualizations.js';
import { projectAnalysis } from './analysisProjection.js';

export type VisualizationType = Exclude<
  AnalysisPresentation['layout'],
  'table'
>;
export type VisualizationResult = DeepReadonly<{
  plan: AnalysisPlan;
  rows: readonly AnalysisRow[];
}>;
export type VisualizationMapping = Pick<
  AnalysisPresentation,
  'x' | 'series' | 'metrics'
>;

/** The renderer remains the single authority for field and row compatibility. */
function projectMapping(
  type: AnalysisPresentation['layout'],
  mapping: DeepReadonly<VisualizationMapping>,
  result: VisualizationResult,
) {
  return projectAnalysis(result.plan, result.rows, {
    layout: type,
    columns: [],
    x: mapping.x,
    series: mapping.series,
    metrics: mapping.metrics ? [...mapping.metrics] : undefined,
  });
}

export function validateMapping(
  type: AnalysisPresentation['layout'],
  mapping: DeepReadonly<VisualizationMapping>,
  result: VisualizationResult,
): string[] {
  return projectMapping(type, mapping, result).issues;
}

/** A single measure witnesses availability: adding measures cannot repair an invalid mapping. */
export function resolveMappings(
  type: AnalysisPresentation['layout'],
  result: VisualizationResult,
) {
  const dimensions = result.plan.schema.filter(
    column => column.role === 'dimension',
  );
  const metrics = result.plan.schema.filter(
    column =>
      column.role === 'metric' &&
      column.valueType === 'number' &&
      column.aggregation !== 'ANY',
  );
  const candidates: VisualizationMapping[] = [];
  const reasons = new Set<string>();
  const summaries = new Set<string>();
  // Supported plots consume every grouping dimension; none may be silently discarded.
  // With >2 dimensions or >500 rows, one renderer check suffices to prove incompatibility.
  const axes =
    type === 'metric' || !dimensions.length
      ? [undefined]
      : dimensions.length > 2 || result.rows.length > 500
        ? [dimensions[0]]
        : dimensions;
  const measures =
    result.rows.length > 500 || dimensions.length > 2
      ? metrics.slice(0, 1)
      : metrics;
  for (const x of axes) {
    for (const metric of measures) {
      const mapping: VisualizationMapping = {
        ...(x
          ? {
              x: x.alias,
              ...(dimensions.length === 2
                ? { series: dimensions.find(column => column !== x)!.alias }
                : {}),
            }
          : {}),
        metrics: [metric.alias],
      };
      const { issues, issueSummary } = projectMapping(type, mapping, result);
      if (!issues.length) candidates.push(mapping);
      else {
        issues.forEach(issue => reasons.add(issue));
        summaries.add(issueSummary ?? issues[0]);
      }
    }
  }
  if (!candidates.length && !reasons.size)
    validateMapping(type, {}, result).forEach(issue => reasons.add(issue));
  return {
    candidates,
    reasons: candidates.length ? [] : [...reasons],
    summaries: candidates.length
      ? []
      : summaries.size
        ? [...summaries]
        : [...reasons],
  };
}

export function inferCapabilities(result: VisualizationResult) {
  const capabilities = ANALYSIS_VISUALIZATIONS.filter(
    chart => chart.value !== 'table',
  ).map(chart => ({
    type: chart.value as VisualizationType,
    label: chart.label,
    ...resolveMappings(chart.value, result),
  }));
  const available = (type: VisualizationType) =>
    capabilities.some(item => item.type === type && item.candidates.length);
  const temporal = result.plan.schema.some(
    column => column.role === 'dimension' && column.valueType === 'datetime',
  );
  const preferred = available('metric')
    ? 'metric'
    : temporal && available('line')
      ? 'line'
      : available('bar')
        ? 'bar'
        : undefined;
  return capabilities.map(item => ({
    ...item,
    status: !item.candidates.length
      ? ('unavailable' as const)
      : item.type === preferred
        ? ('recommended' as const)
        : ('available' as const),
  }));
}

export function initialDisplayMapping(
  value: DeepReadonly<AnalysisPresentation>,
  plan: DeepReadonly<AnalysisPlan>,
  layout: AnalysisPresentation['layout'],
  rows: VisualizationResult['rows'] = [],
): AnalysisPresentation {
  const chart = ANALYSIS_VISUALIZATIONS.find(item => item.value === layout);
  if (!chart)
    return {
      ...value,
      layout,
      columns: Array.isArray(value.columns)
        ? value.columns.map(column => ({ ...column }))
        : [],
      metrics: value.metrics ? [...value.metrics] : undefined,
    };
  const { candidates } = resolveMappings(layout, { plan, rows });
  const unique = (values: (string | undefined)[]) => {
    const aliases = [
      ...new Set(
        values.filter((value): value is string => value !== undefined),
      ),
    ];
    return aliases.length === 1 ? aliases[0] : '';
  };
  const x = value.x ?? unique(candidates.map(candidate => candidate.x));
  const compatible = x
    ? candidates.filter(candidate => candidate.x === x)
    : candidates;
  const metrics = unique(
    compatible.flatMap(candidate => candidate.metrics ?? []),
  );
  return {
    ...value,
    columns: Array.isArray(value.columns)
      ? value.columns.map(column => ({ ...column }))
      : [],
    layout,
    ...(chart.axes
      ? {
          x,
          ...(plan.schema.filter(column => column.role === 'dimension').length >
          1
            ? {
                series:
                  value.series ??
                  (x
                    ? unique(compatible.map(candidate => candidate.series))
                    : ''),
              }
            : {}),
        }
      : {}),
    metrics: value.metrics ? [...value.metrics] : metrics ? [metrics] : [],
  };
}
