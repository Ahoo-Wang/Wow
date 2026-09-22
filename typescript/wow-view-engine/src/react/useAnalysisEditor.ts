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
  FieldGroupDefinition,
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
import {
  analysisScope,
  DEFAULT_MISSING_KEY,
  fitChartSlots,
  rangeSpan,
  recommendDateUnit,
  resultSpan,
  type AnalysisScope,
} from '../analysis/index.js';
import { isSingleStringField, without } from '../model/index.js';
import { comparePending, type ViewRuntime } from '../runtime/index.js';
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
  /** Whether a dimension on it may keep records missing the value as a group of their own. */
  missingKey: boolean;
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
  /** The picker groups the definition declares. */
  fieldGroups: readonly FieldGroupDefinition[];
  countable: boolean;
  /**
   * True while the draft says something the last Run did not (D17-6): the
   * groups, metrics, sort, limit, chart and totals all wait for Run, and a
   * Run that was refused leaves them waiting. The same reading as the filter
   * editor's `pending` — both buttons run the whole draft.
   */
  pending: boolean;

  /**
   * The granularity a new time dimension on this field starts at (K4): read
   * off the applied range's conditions on the field, failing that off the
   * buckets a result already has on it, else the field's first unit.
   */
  dateUnitFor(field: AnalysisFieldOption): AnalysisDateUnit;
  addGroup(group: AnalysisGroup): void;
  updateGroup(index: number, patch: Partial<AnalysisGroup>): void;
  removeGroup(index: number): void;
  /** Names a dimension on screen, or takes the name back with `undefined`. */
  renameGroup(index: number, label: string | undefined): void;
  /** Keeps records missing the value as a group of their own, or drops them. */
  setMissingBucket(index: number, on: boolean): void;
  /** Fills in the empty periods of a time dimension, or leaves them out. */
  setDense(index: number, on: boolean): void;
  addMetric(metric: AnalysisMetric): void;
  updateMetric(index: number, patch: Partial<AnalysisMetric>): void;
  /**
   * Puts a whole metric in a row's place. A change of summary is a change
   * of type — a sum becomes a distinct count — and a patch over the old
   * shape would leave its `function` or `expression` behind for admission
   * to trip over; the card builds the new metric and swaps it in.
   */
  replaceMetric(index: number, metric: AnalysisMetric): void;
  /** Refuses the last metric: an aggregation query needs at least one. */
  removeMetric(index: number): void;
  /** Names a metric on screen, or takes the name back with `undefined`. */
  renameMetric(index: number, label: string | undefined): void;
  setSort(sort: AnalysisSort[]): void;
  setLimit(limit: number): void;
  /** A redraw of the same rows, never a run; nor does it count as pending. */
  setLayout(layout: AnalysisViewConfig['layout']): void;
  setChartType(type: ChartType): void;
  updateChart(patch: Partial<ChartSpec>): void;
  setTotals(totals: boolean): void;
  submit(): void;
  /** Auto-refresh pauses between these two, so typing is never interrupted. */
  focus(): void;
  blur(): void;
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
const EMPTY_GROUPS: readonly FieldGroupDefinition[] = [];

export function useAnalysisEditor(
  runtime: ViewRuntime | null,
): AnalysisEditorController {
  const state = useViewRuntime(runtime);
  const draft = state?.draft;
  const config = draft && draft.kind === 'analysis' ? draft : undefined;

  // A dashboard runtime has no fields and no capabilities; the controller
  // then reports an empty scope rather than editing something that is not there.
  const owner = runtime?.definition;
  const definition = owner?.kind === 'data' ? owner : undefined;
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

  /**
   * A change to what is grouped or measured, with everything that names an
   * alias brought along.
   *
   * Groups and metrics are what the rest of the config points at: the chart
   * addresses its slots by alias, the sort orders by one, the table lists
   * them. Editing the lists alone left those three naming aliases that were
   * gone — the chart reported `chart.group.unconsumed` and vanished, and the
   * sort reported `analysis.sort.unknown-alias` — so the one edit carries all
   * four. A slot, an ordering or a column the user chose is kept wherever it
   * still names something; nothing else survives the alias it referred to.
   */
  const reshape = useCallback(
    (
      update: (
        current: AnalysisViewConfig,
      ) => Pick<AnalysisViewConfig, 'groups' | 'metrics'> | undefined,
    ) =>
      change(current => {
        const next = update(current);
        if (!next) return {};
        const aliases = new Set([
          ...next.groups.map(group => group.alias),
          ...next.metrics.map(metric => metric.alias),
        ]);
        return {
          ...next,
          chart: fitChartSlots(current.chart, next.groups, next.metrics),
          // Wow refuses a sort over an ungrouped aggregation, and it has one
          // row anyway, so losing the last group empties the ordering too.
          sort:
            next.groups.length === 0
              ? []
              : current.sort.filter(entry => aliases.has(entry.alias)),
          table: {
            ...current.table,
            columns: current.table.columns.filter(column =>
              aliases.has(column.alias),
            ),
          },
        };
      }),
    [change],
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
        missingKey: isSingleStringField(field, runtime?.kinds.get(field.kind)),
      };
    });
  }, [scope, runtime]);

  const dateUnitFor = useCallback(
    (field: AnalysisFieldOption): AnalysisDateUnit => {
      const applied = state?.applied;
      const result = state?.result;
      if (!runtime || !applied) return field.dateUnits[0] ?? 'DAY';
      const { timeZone } = runtime.environment;
      const span =
        rangeSpan(
          applied.filter,
          field.field,
          runtime.environment.now(),
          timeZone,
        ) ??
        (result?.config.kind === 'analysis' && result.data.kind === 'analysis'
          ? resultSpan(
              result.data.view.rows,
              result.config.groups,
              field.field,
              timeZone,
            )
          : null);
      return recommendDateUnit(span, field.dateUnits);
    },
    [runtime, state],
  );

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
    fieldGroups: definition?.fieldGroups ?? EMPTY_GROUPS,
    countable: capability?.count === true,
    dateUnitFor,
    pending: state
      ? comparePending(state.draft, state.applied, state.issues).pending
      : false,

    addGroup: useCallback(
      (group: AnalysisGroup) =>
        reshape(current => ({
          groups: [...current.groups, group],
          metrics: current.metrics,
        })),
      [reshape],
    ),
    updateGroup: useCallback(
      (index: number, patch: Partial<AnalysisGroup>) =>
        reshape(current => ({
          groups: current.groups.map((group, at) =>
            at === index ? ({ ...group, ...patch } as AnalysisGroup) : group,
          ),
          metrics: current.metrics,
        })),
      [reshape],
    ),
    removeGroup: useCallback(
      (index: number) =>
        reshape(current => ({
          groups: current.groups.filter((_group, at) => at !== index),
          metrics: current.metrics,
        })),
      [reshape],
    ),
    // The three settings that come and go rather than change: a name taken
    // back, a sentinel bucket dropped, a fill switched off leave no key
    // behind, so the config stays what a fresh one would be.
    renameGroup: useCallback(
      (index: number, label: string | undefined) =>
        reshape(current => ({
          groups: current.groups.map((group, at) =>
            at !== index
              ? group
              : label === undefined
                ? (without(group, 'label') as AnalysisGroup)
                : { ...group, label },
          ),
          metrics: current.metrics,
        })),
      [reshape],
    ),
    setMissingBucket: useCallback(
      (index: number, on: boolean) =>
        reshape(current => ({
          groups: current.groups.map((group, at) =>
            at !== index || group.type !== 'TERMS'
              ? group
              : on
                ? { ...group, missingKey: DEFAULT_MISSING_KEY }
                : (without(group, 'missingKey') as AnalysisGroup),
          ),
          metrics: current.metrics,
        })),
      [reshape],
    ),
    setDense: useCallback(
      (index: number, on: boolean) =>
        reshape(current => ({
          groups: current.groups.map((group, at) =>
            at !== index || group.type !== 'DATE_HISTOGRAM'
              ? group
              : on
                ? { ...group, dense: true }
                : (without(group, 'dense') as AnalysisGroup),
          ),
          metrics: current.metrics,
        })),
      [reshape],
    ),

    addMetric: useCallback(
      (metric: AnalysisMetric) =>
        reshape(current => ({
          groups: current.groups,
          metrics: [
            ...current.metrics,
            metric,
          ] as AnalysisViewConfig['metrics'],
        })),
      [reshape],
    ),
    updateMetric: useCallback(
      (index: number, patch: Partial<AnalysisMetric>) =>
        reshape(current => ({
          groups: current.groups,
          metrics: current.metrics.map((metric, at) =>
            at === index ? ({ ...metric, ...patch } as AnalysisMetric) : metric,
          ) as AnalysisViewConfig['metrics'],
        })),
      [reshape],
    ),
    replaceMetric: useCallback(
      (index: number, metric: AnalysisMetric) =>
        reshape(current => ({
          groups: current.groups,
          metrics: current.metrics.map((entry, at) =>
            at === index ? metric : entry,
          ) as AnalysisViewConfig['metrics'],
        })),
      [reshape],
    ),
    renameMetric: useCallback(
      (index: number, label: string | undefined) =>
        reshape(current => ({
          groups: current.groups,
          metrics: current.metrics.map((metric, at) =>
            at !== index
              ? metric
              : label === undefined
                ? (without(metric, 'label') as AnalysisMetric)
                : { ...metric, label },
          ) as AnalysisViewConfig['metrics'],
        })),
      [reshape],
    ),
    removeMetric: useCallback(
      (index: number) =>
        reshape(current =>
          // An aggregation query without a metric has nothing to return.
          current.metrics.length <= 1
            ? undefined
            : {
                groups: current.groups,
                metrics: current.metrics.filter(
                  (_metric, at) => at !== index,
                ) as AnalysisViewConfig['metrics'],
              },
        ),
      [reshape],
    ),

    setSort: useCallback((sort: AnalysisSort[]) => edit({ sort }), [edit]),
    setLimit: useCallback((limit: number) => edit({ limit }), [edit]),
    // A redraw, not a run: the result's rows are drawn as a table or as a
    // chart from the same answer (`ANALYSIS_PRESENTATION_MEMBERS`).
    setLayout: useCallback(
      (layout: AnalysisViewConfig['layout']) => edit({ layout }),
      [edit],
    ),
    setChartType: useCallback(
      (type: ChartType) =>
        // The new type's family sub-object is built from the groups and
        // metrics in force, because a type without one is `chart.family.missing`
        // and draws nothing; every other family is carried over, so switching
        // type and back returns to the settings that family had.
        change(current => ({
          chart: fitChartSlots(
            { ...current.chart, type },
            current.groups,
            current.metrics,
          ),
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
    focus: useCallback(() => runtime?.setEditing(true), [runtime]),
    blur: useCallback(() => runtime?.setEditing(false), [runtime]),
  };
}
