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

import { useCallback, useMemo } from 'react';
import type {
  AnalysisDateUnit,
  AnalysisFunction,
  AnalysisGroup,
  AnalysisGroupType,
  AnalysisMetric,
  AnalysisSort,
  AnalysisViewConfig,
  ChartSpec,
  ChartType,
  FieldDefinition,
  Issue,
} from '../model/index.js';
import { CHART_FAMILY } from '../model/index.js';
import { analysisScope, type AnalysisScope } from '../analysis/index.js';
import type { ViewRuntime } from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';

/** One field and what the definition allows doing with it. */
export interface AnalysisFieldOption {
  field: string;
  label: string;
  /** Group types this field offers; empty when it cannot be grouped. */
  groups: AnalysisGroupType[];
  /** Aggregation functions it offers; empty when it cannot be measured. */
  functions: AnalysisFunction[];
  dateUnits: AnalysisDateUnit[];
  distinctCount: boolean;
  percentile: boolean;
  any: boolean;
}

export interface AnalysisEditorController {
  groups: AnalysisGroup[];
  metrics: AnalysisMetric[];
  sort: AnalysisSort[];
  limit: number;
  layout: AnalysisViewConfig['layout'];
  chart: ChartSpec;
  totals: boolean;
  /** Issues about the analysis and its chart, not the whole view. */
  issues: Issue[];
  /** Aliases a chart or a sort may reference. */
  aliases: { groups: string[]; metrics: string[] };
  /** What the definition allows, already expanded for configured elements. */
  fields: AnalysisFieldOption[];
  countable: boolean;

  addGroup(group: AnalysisGroup): void;
  updateGroup(index: number, patch: Partial<AnalysisGroup>): void;
  removeGroup(index: number): void;
  addMetric(metric: AnalysisMetric): void;
  updateMetric(index: number, patch: Partial<AnalysisMetric>): void;
  /** Refuses the last metric: an aggregation query needs at least one. */
  removeMetric(index: number): void;
  setSort(sort: AnalysisSort[]): void;
  setLimit(limit: number): void;
  /** Applies at once: the kernel shapes a chart only for the layout that ran. */
  setLayout(layout: AnalysisViewConfig['layout']): void;
  setChartType(type: ChartType): void;
  updateChart(patch: Partial<ChartSpec>): void;
  setTotals(totals: boolean): void;
  submit(): void;
}

const EMPTY_CHART: ChartSpec = { type: 'bar' };

/**
 * Editing of an analysis draft: what to group by, what to measure, and how to
 * draw it.
 *
 * What may be grouped or measured comes from the definition's capability
 * rather than from the field list, because a backend that cannot sum a column
 * should not be offered the option.
 */
export function useAnalysisEditor(
  runtime: ViewRuntime | null,
): AnalysisEditorController {
  const state = useViewRuntime(runtime);
  const draft = state?.draft;
  const config = draft && draft.kind === 'analysis' ? draft : undefined;

  const definition = runtime?.definition;
  const capability = definition?.analysis;
  const scope = useMemo<AnalysisScope | null>(
    () =>
      definition && capability
        ? analysisScope(definition, capability, config)
        : null,
    [definition, capability, config],
  );

  const edit = useCallback(
    (patch: Partial<AnalysisViewConfig>) => runtime?.edit(patch),
    [runtime],
  );

  /** Reads the live draft, so several edits in one event compose. */
  const change = useCallback(
    (update: (current: AnalysisViewConfig) => Partial<AnalysisViewConfig>) => {
      if (!runtime) return;
      const current = runtime.getSnapshot().draft;
      if (current.kind !== 'analysis') return;
      runtime.edit(update(current));
    },
    [runtime],
  );

  const fields = useMemo<AnalysisFieldOption[]>(() => {
    if (!scope) return [];
    return [...scope.fields.values()].map((field: FieldDefinition) => {
      const aggregation = scope.aggregations.get(field.name);
      return {
        field: field.name,
        label: field.label,
        groups: aggregation?.groups ?? [],
        functions: aggregation?.functions ?? [],
        dateUnits: aggregation?.dateUnits ?? [],
        distinctCount: aggregation?.distinctCount === true,
        percentile: aggregation?.percentile === true,
        any: aggregation?.any === true,
      };
    });
  }, [scope]);

  const groups = config?.groups ?? [];
  const metrics = config?.metrics ?? [];

  return {
    groups,
    metrics,
    sort: config?.sort ?? [],
    limit: config?.limit ?? 0,
    layout: config?.layout ?? 'table',
    chart: config?.chart ?? EMPTY_CHART,
    totals: config?.table.totals === true,
    issues: (state?.issues ?? []).filter(
      found =>
        found.code.startsWith('analysis.') || found.code.startsWith('chart.'),
    ),
    aliases: {
      groups: groups.map(group => group.alias),
      metrics: metrics.map(metric => metric.alias),
    },
    fields,
    countable: capability?.count === true,

    addGroup: useCallback(
      (group: AnalysisGroup) =>
        change(current => ({ groups: [...current.groups, group] })),
      [change],
    ),
    updateGroup: useCallback(
      (index: number, patch: Partial<AnalysisGroup>) =>
        change(current => ({
          groups: current.groups.map((group, at) =>
            at === index ? ({ ...group, ...patch } as AnalysisGroup) : group,
          ),
        })),
      [change],
    ),
    removeGroup: useCallback(
      (index: number) =>
        change(current => ({
          groups: current.groups.filter((_group, at) => at !== index),
        })),
      [change],
    ),

    addMetric: useCallback(
      (metric: AnalysisMetric) =>
        change(current => ({
          metrics: [
            ...current.metrics,
            metric,
          ] as AnalysisViewConfig['metrics'],
        })),
      [change],
    ),
    updateMetric: useCallback(
      (index: number, patch: Partial<AnalysisMetric>) =>
        change(current => ({
          metrics: current.metrics.map((metric, at) =>
            at === index ? ({ ...metric, ...patch } as AnalysisMetric) : metric,
          ) as AnalysisViewConfig['metrics'],
        })),
      [change],
    ),
    removeMetric: useCallback(
      (index: number) =>
        change(current => {
          // An aggregation query without a metric has nothing to return.
          if (current.metrics.length <= 1) return {};
          return {
            metrics: current.metrics.filter(
              (_metric, at) => at !== index,
            ) as AnalysisViewConfig['metrics'],
          };
        }),
      [change],
    ),

    setSort: useCallback((sort: AnalysisSort[]) => edit({ sort }), [edit]),
    setLimit: useCallback((limit: number) => edit({ limit }), [edit]),
    setLayout: useCallback(
      (layout: AnalysisViewConfig['layout']) => {
        if (!runtime) return;
        // `projectAnalysis` shapes a chart only when the config that ran asked
        // for one, so a layout switch is a new execution rather than a redraw.
        runtime.edit({ layout });
        runtime.apply();
      },
      [runtime],
    ),
    setChartType: useCallback(
      (type: ChartType) =>
        change(current => ({
          // Each family keeps its own sub-object, so switching type and back
          // returns to the settings that family had.
          chart: {
            ...current.chart,
            type,
            [CHART_FAMILY[type]]: current.chart[CHART_FAMILY[type]],
          },
        })),
      [change],
    ),
    updateChart: useCallback(
      (patch: Partial<ChartSpec>) =>
        change(current => ({ chart: { ...current.chart, ...patch } })),
      [change],
    ),
    setTotals: useCallback(
      (totals: boolean) =>
        change(current => ({ table: { ...current.table, totals } })),
      [change],
    ),
    submit: useCallback(() => runtime?.apply(), [runtime]),
  };
}
