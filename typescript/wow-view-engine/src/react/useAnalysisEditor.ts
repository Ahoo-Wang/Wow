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
import { approximateMetrics, datePartsOf } from '../model/index.js';
import type {
  FieldGroupDefinition,
  FilterTree,
  AnalysisDatePart,
  AnalysisDateUnit,
  AnalysisElement,
  AnalysisFunction,
  AnalysisGroup,
  AnalysisGroupType,
  AnalysisHavingExpression,
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
  limitBounds,
  withoutLevelsFrom,
  elementFilterFields,
  withLevel,
  fitChartSlots,
  switchChartType,
  havingRows,
  levelLabel,
  metricMeasures,
  momentMetrics,
  nextLevel,
  withElements,
  rangeSpan,
  recommendDateUnit,
  resultSpan,
  type AnalysisLimitBounds,
  type AnalysisScope,
} from '../analysis/index.js';
import { isFieldlessKind, isSingleStringField } from '../model/index.js';
import { questionEditing, type QuestionEditing } from './analysisEditing.js';
import type { FieldKindRegistry } from '../filter/index.js';
import type { OptionSource, ViewRuntime } from '../runtime/index.js';
import { answeringAnew, autoApplyDue } from '../runtime/autoApply.js';
import { comparePending, pendingBesides } from '../runtime/pending.js';
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
  /** The calendar parts a `DATE_PART` dimension on it may take (`datePartsOf`). */
  dateParts: AnalysisDatePart[];
  distinctCount: boolean;
  percentile: boolean;
  any: boolean;
  /** Whether a dimension on it may keep records missing the value as a group of their own. */
  missingKey: boolean;
  /**
   * `false` where a formula may not take it as an operand
   * (`expressionInput`); absent or `true` where it may.
   */
  expressionInput?: boolean;
  /**
   * How its values read (`cell ?? kind`): the earliest of a `datetime` is
   * worded 「最早」 where a number's smallest is 「最小」, and a date is no
   * operand of a formula. Absent when not known.
   */
  cell?: string;
}

export interface AnalysisEditorController extends QuestionEditing {
  /**
   * The expansion chain in force, outermost first (D20 屏 G): the arrays
   * the analysis counts inside. Empty when it counts records.
   */
  elements: AnalysisElement[];
  /** The next level the capability declares beyond the chain, or none. */
  expandable: { path: string; label: string } | null;
  /** Whether the capability declares a chain at all: the slot exists then. */
  expansible: boolean;
  /**
   * What is being counted: the innermost element, else the definition's
   * `recordNoun`; `null` where the definition names none, and a surface
   * says its own word for a record.
   */
  unit: string | null;
  /** The array a level expands, as its field is labelled. */
  elementLabel(index: number): string;
  /** The fields a level's own gate may name. */
  elementFields(index: number): FieldDefinition[];
  /** One level deeper, along the declared chain; re-scopes the question. */
  expand(path: string): void;
  /** Cuts the chain at `index`: that level and every level inside it leave. */
  collapse(index: number): void;
  /** A level's gate on the entries it lets through, or none. */
  setElementFilter(index: number, filter: FilterTree | undefined): void;
  groups: AnalysisGroup[];
  metrics: AnalysisMetric[];
  sort: AnalysisSort[];
  limit: number;
  /**
   * The range 「前 N 组」 may take and the N a blank stands for, from the one
   * `limitBounds` admission reads, so a field can say the bounds Apply is
   * refused by. `{ max: 0, fallback: 0 }` without an analysis capability.
   */
  limitBounds: AnalysisLimitBounds;
  layout: AnalysisViewConfig['layout'];
  chart: ChartSpec;
  totals: boolean;
  /** Issues about the analysis and its chart, not the whole view. */
  issues: Issue[];
  /** Aliases a chart or a sort may reference. */
  aliases: { groups: string[]; metrics: string[] };
  /**
   * The draft's metrics that are moments (`momentMetrics`) — the earliest or
   * the latest of a date — by alias: read in the table and on a card, never
   * measured by a mark, compared by 「只保留」 or calculated with.
   */
  moments: ReadonlySet<string>;
  /** What the definition allows, already expanded for configured elements. */
  fields: AnalysisFieldOption[];
  /** The picker groups the definition declares. */
  fieldGroups: readonly FieldGroupDefinition[];
  countable: boolean;
  /**
   * True while the rows on screen answer an older question than the one
   * being asked: the runtime is about to run the draft on its own
   * (`autoApplyDue`), or has sent it and the answer is on its way
   * (`answeringAnew`) — however slow it is. The result is drawn faded rather
   * than cleared, because a blank in between reads as a failure.
   */
  stale: boolean;
  /** Whether 「只保留」 exists here: the capability declares `having`. */
  havingAllowed: boolean;
  /** Whether a formula or a derived metric may be written: `expressions`. */
  expressionsAllowed: boolean;
  /**
   * The metric types 「只保留」 may compare (`havingMetrics`); `null` where
   * the capability names none, which is every type.
   */
  havingMetrics: readonly string[] | null;
  /** Whether the rows may be ordered by a metric (`metricSort`). */
  metricSortAllowed: boolean;
  /** Whether a date dimension may fill its empty buckets (`dense`). */
  denseAllowed: boolean;
  /**
   * The metric types the source estimates (`approximateMetrics`): a metric
   * of one is named with 「≈」 wherever it is mentioned.
   */
  approximate: readonly string[];
  /**
   * 「只保留」 as rows of one comparison each, or `null` when the stored
   * having is a shape the rows cannot say (`havingRows`).
   */
  having: ReturnType<typeof havingRows>;
  /**
   * The 「只保留」 the rows on screen ran under — the result's config, not
   * the draft — or `undefined` when nothing ran or it kept every group. The
   * result's reading says it (`AnalysisToolbar`): a saved view opens with
   * the tray folded, and groups missing from the table with nothing on
   * screen saying why read as groups with no data.
   */
  ranHaving: AnalysisHavingExpression | undefined;
  /**
   * The fields a metric's own condition may name (D20 屏 H): the scalar
   * fields of the analysis scope — never a search, an array or an element
   * match, which Wow refuses in metric position — as the range's editor
   * names them, so the condition is built of the same pills.
   */
  conditionFields: readonly FieldDefinition[];
  /** The kinds those fields are read by; absent without a runtime. */
  kinds: FieldKindRegistry | undefined;
  /** Candidates for a remote value editor, as the range's editor has them. */
  optionSource?(remote: string): OptionSource | null;
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
  setSort(sort: AnalysisSort[]): void;
  /**
   * Sets the sort and runs it now — unless something else in the draft is
   * waiting for Apply. A press on a result header asks to see *this* result
   * in another order; running would apply the other edits too (a range
   * condition not yet applied, a dimension added in the tray), which is not
   * that gesture's to do. Then the sort joins them, the pending dot shows,
   * and Apply runs them together — the rule the metric card's whole
   * (`useAnalysisResult`) keeps. Whether it ran is returned.
   */
  sortNow(sort: AnalysisSort[]): boolean;
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
const NO_MOMENTS: ReadonlySet<string> = new Set();

/**
 * Editing of an analysis draft: what to group by, what to measure, and how to
 * draw it.
 *
 * What may be grouped or measured comes from the definition's capability
 * rather than from the field list, because a backend that cannot sum a column
 * should not be offered the option.
 */
const EMPTY_GROUPS: readonly FieldGroupDefinition[] = [];
const NO_LIMIT_BOUNDS: AnalysisLimitBounds = { max: 0, fallback: 0 };

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
  // The chart fitted to a shape, with what its metrics are read against the
  // scope of that shape, because a change to the expansion chain moves the
  // fields a metric summarises: which are moments, and what each measures.
  const fitTo = useCallback(
    (
      chart: ChartSpec,
      shape: Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'elements'>,
    ) => {
      if (!definition || !capability)
        return fitChartSlots(chart, shape.groups, shape.metrics);
      const { fields } = analysisScope(definition, capability, shape);
      return fitChartSlots(
        chart,
        shape.groups,
        shape.metrics,
        momentMetrics(shape.metrics, fields),
        metricMeasures(shape.metrics, fields),
      );
    },
    [definition, capability],
  );
  const moments = useMemo(
    () =>
      config && scope
        ? momentMetrics(config.metrics, scope.fields)
        : NO_MOMENTS,
    [config, scope],
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
      ) =>
        | (Pick<AnalysisViewConfig, 'groups' | 'metrics'> &
            Partial<Pick<AnalysisViewConfig, 'elements'>>)
        | undefined,
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
          chart: fitTo(current.chart, { ...current, ...next }),
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
    [change, fitTo],
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
        dateParts: datePartsOf(aggregation),
        distinctCount: aggregation?.distinctCount === true,
        percentile: aggregation?.percentile === true,
        any: aggregation?.any === true,
        missingKey:
          aggregation?.missingKey !== false &&
          isSingleStringField(field, runtime?.kinds.get(field.kind)),
        expressionInput: aggregation?.expressionInput !== false,
        cell: field.cell ?? field.kind,
      };
    });
  }, [scope, runtime]);

  const dateUnitFor = useCallback(
    (field: AnalysisFieldOption): AnalysisDateUnit => {
      const applied = state?.applied;
      const result = state?.result;
      if (!runtime || !applied || applied.kind === 'dashboard')
        return field.dateUnits[0] ?? 'DAY';
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
  const elements = useMemo(() => config?.elements ?? [], [config?.elements]);
  const elementLabel = useCallback(
    (index: number): string =>
      scope && definition ? levelLabel(definition, scope, index) : '',
    [scope, definition],
  );
  const expandable = useMemo(
    () =>
      scope && definition
        ? (nextLevel(definition, scope, elements) ?? null)
        : null,
    [scope, definition, elements],
  );
  const rescope = useCallback(
    (next: AnalysisElement[]) =>
      reshape(current =>
        definition && capability
          ? withElements(current, next, definition, capability)
          : undefined,
      ),
    [reshape, definition, capability],
  );
  const conditionFields = useMemo<FieldDefinition[]>(() => {
    if (!scope || !runtime) return [];
    return [...scope.fields.values()].filter((field: FieldDefinition) => {
      const kind = runtime.kinds.get(field.kind);
      return (
        kind !== undefined &&
        kind.scalar !== false &&
        !isFieldlessKind(field.kind, kind) &&
        // The source may keep it out of a metric's condition.
        scope.aggregations.get(field.name)?.inMetricFilter !== false
      );
    });
  }, [scope, runtime]);

  const editing = useMemo(
    () => questionEditing({ reshape, edit, scope }),
    [reshape, edit, scope],
  );

  return {
    elements,
    expandable,
    expansible: (scope?.declaredChain.length ?? 0) > 0,
    unit:
      elements.length > 0
        ? elementLabel(elements.length - 1)
        : (definition?.recordNoun ?? null),
    elementLabel,
    elementFields: useCallback(
      (index: number) => (scope ? elementFilterFields(scope, index) : []),
      [scope],
    ),
    expand: useCallback(
      (path: string) => rescope(withLevel(elements, path)),
      [rescope, elements],
    ),
    collapse: useCallback(
      (index: number) => rescope(withoutLevelsFrom(elements, index)),
      [rescope, elements],
    ),
    setElementFilter: useCallback(
      (index: number, filter: FilterTree | undefined) =>
        change(current => ({
          elements: (current.elements ?? []).map((element, at) =>
            at !== index
              ? element
              : filter === undefined
                ? { path: element.path }
                : { ...element, filter },
          ),
        })),
      [change],
    ),
    groups,
    metrics,
    sort: config?.sort ?? [],
    limit: config?.limit ?? 0,
    limitBounds: useMemo(
      () =>
        capability && runtime
          ? limitBounds(capability, runtime.limits)
          : NO_LIMIT_BOUNDS,
      [capability, runtime],
    ),
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
    moments,
    fields,
    fieldGroups: definition?.fieldGroups ?? EMPTY_GROUPS,
    countable: capability?.count === true,
    stale: state ? autoApplyDue(state) || answeringAnew(state) : false,
    havingAllowed: capability?.having === true,
    expressionsAllowed: capability?.expressions === true,
    havingMetrics: capability?.havingMetrics ?? null,
    metricSortAllowed: capability?.metricSort !== false,
    denseAllowed: capability?.dense !== false,
    approximate: approximateMetrics(capability),
    having: havingRows(config?.having),
    ranHaving:
      state?.result?.config.kind === 'analysis'
        ? state.result.config.having
        : undefined,
    conditionFields,
    kinds: runtime?.kinds,
    ...(runtime ? { optionSource: runtime.optionSource.bind(runtime) } : {}),
    dateUnitFor,
    pending: state
      ? comparePending(state.draft, state.applied, state.issues).pending
      : false,

    ...editing,
    setSort: useCallback((sort: AnalysisSort[]) => edit({ sort }), [edit]),
    sortNow: useCallback(
      (sort: AnalysisSort[]) => {
        if (!runtime) return false;
        const { draft, applied, issues } = runtime.getSnapshot();
        if (draft.kind !== 'analysis' || applied.kind !== 'analysis')
          return false;
        // What waits besides the sort: a sort edited in the tray and not
        // yet run — which this one replaces — does not hold it back. The
        // record table's writes keep the same rule (`pendingBesides`).
        const others = pendingBesides({ draft, applied, issues }, { sort });
        runtime.edit({ sort });
        if (others) return false;
        runtime.apply();
        return true;
      },
      [runtime],
    ),
    setLimit: useCallback((limit: number) => edit({ limit }), [edit]),
    // A redraw, not a run: the result's rows are drawn as a table or as a
    // chart from the same answer (`ANALYSIS_PRESENTATION_MEMBERS`). Under
    // the table the chart is not judged (D20), so the one it saved may name
    // slots of an older shape; turning to it fits it to the shape in force,
    // which keeps every slot that still names something. A shape no chart
    // draws is drawn as the table, and the kernel says so (`chart.as-table`).
    setLayout: useCallback(
      (layout: AnalysisViewConfig['layout']) =>
        change(current =>
          layout === 'chart' && current.layout !== 'chart'
            ? { layout, chart: fitTo(current.chart, current) }
            : { layout },
        ),
      [change, fitTo],
    ),
    setChartType: useCallback(
      (type: ChartType) =>
        // The new type's family sub-object is built from the groups and
        // metrics in force, because a type without one is `chart.family.missing`
        // and draws nothing; every other family is carried over, so switching
        // type and back returns to the settings that family had.
        change(current => ({
          chart: fitTo(switchChartType(current.chart, type), current),
        })),
      [change, fitTo],
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
