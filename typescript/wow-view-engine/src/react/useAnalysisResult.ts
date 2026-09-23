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

import { useEffect, useMemo } from 'react';
import {
  CHART_PICKER_ORDER,
  drillGroups,
  fitChartSlots,
  fitCharts,
  focusOn,
  groupFor,
  groupableFields,
  momentColumns,
  asksForWhole,
  shapeChart,
  splitBy,
  switchChartType,
  withStagesFrom,
  type AnalysisColumnView,
  type AnalysisView,
  type ChartData,
  type ChartFit,
  type Picked,
} from '../analysis/index.js';
import { describeFilter, type FilterSummaryItem } from '../filter/index.js';
import type {
  AnalysisViewConfig,
  ChartSpec,
  ChartType,
  FilterNode,
  RecordData,
} from '../model/index.js';
import type { ViewRuntime } from '../runtime/index.js';
import type { AnalysisEditorController } from './useAnalysisEditor.js';
import type { WorkbenchController } from './useWorkbench.js';

/** A dimension a group can be split by: a field the result is not grouped on. */
export interface SplitOption {
  field: string;
  label: string;
}

/**
 * One thing the follow-up menu offers on a group (D20 追问). The menu draws
 * each kind its own way — an icon and a word are a host's to choose — and
 * runs it; which kinds are offered, in what order, and what running one
 * does are decided here, so a new follow-up is a new member of this union
 * and one more entry in `followUp`, not another pair of props on the menu.
 */
export type FollowUpAction =
  /**
   * Open the records behind the group, in a record view of their own. The
   * view is named `title`: wording, so the menu says it — of `subject`, the
   * definition's name for its records, and the group.
   */
  | { kind: 'records'; subject: string; run(title: string): void }
  /**
   * Ask the same question of the group by another dimension, as a view of
   * its own beside this one (`WorkbenchController.follow`), as `focus`
   * does. Named `title`, of `subject` — this view's name — and the group.
   */
  | {
      kind: 'split';
      subject: string;
      options: readonly SplitOption[];
      run(field: string, title: string): void;
    }
  /**
   * Ask the same question of the group alone, as a view of its own beside
   * this one (`WorkbenchController.follow`), so the way back is this result
   * as it stands. Named `title`, of `subject` — this view's name — and the
   * group.
   */
  | { kind: 'focus'; subject: string; run(title: string): void };

/** One dimension of the group pressed, for the menu to name it by. */
export interface FollowUpGroup {
  /**
   * The dimension's column in the result — its header and how its values
   * read, a date bucket's width among them — or undefined where the result
   * drew none.
   */
  column: AnalysisColumnView | undefined;
  /** The row's value in that column: a key, a bucket's start. */
  value: unknown;
  /** The conditions that select it, as the applied bar names them. */
  conditions: readonly FilterSummaryItem[];
}

/** What the menu over one pressed group shows. */
export interface FollowUp {
  /** The group, one dimension each, in the order the result is grouped. */
  groups: readonly FollowUpGroup[];
  actions: readonly FollowUpAction[];
}

export interface AnalysisResultController {
  /** The rows on screen, or null while there are none of an analysis. */
  view: AnalysisView | null;
  /** The config those rows ran on: what a chart and a follow-up address. */
  ran: AnalysisViewConfig | undefined;
  /** The chart drawn over those rows, as the draft says to draw it. */
  chart: ChartSpec;
  /** The rows shaped for `chart`, or undefined while there is nothing to draw. */
  chartData: ChartData | undefined;
  /**
   * Which chart types the shape that ran can draw, its rows included
   * (`fitCharts`); before anything ran, the draft's shape and the stages its
   * chart names.
   */
  fits: Record<ChartType, ChartFit>;
  /** What the visualization panel shows as chosen. */
  picked: Picked;
  /**
   * Draw the rows as this type, or as the table. Over rows it redraws and
   * never runs; with none — a saved chart refused, so nothing ran — it fits
   * the type to the draft's shape and runs (`repair`).
   */
  choose(next: Picked): void;
  /**
   * Whether a group of this result can be followed up at all: an analysis
   * over expanded elements has rows no root condition selects.
   */
  pickable: boolean;
  /** The menu over one pressed group, or null when no condition can say it. */
  followUp(row: RecordData): FollowUp | null;
}

/**
 * The analysis result as a host draws it (phase-2 review, developer #9):
 * which rows, which chart over them, what the picker offers, and what a
 * press on one group can do next. Everything here reads a kernel and writes
 * the runtime; the component that draws it keeps only what is about the
 * screen — which panel is open, where the keyboard is, which mark was
 * pressed and where. `/ui` consumes this and nothing below it, which is
 * the contract its entry states.
 *
 * **The rows are the config that ran; how they are looked at is the
 * draft.** A result's rows and columns come from the config it was shaped
 * by, and nothing else can name them. The layout and the chart are
 * presentation (D20, `ANALYSIS_PRESENTATION_MEMBERS`): drawn from those
 * rows as the draft says, so switching to a chart or picking another type
 * redraws without a run. The draft's chart is fitted to the shape that ran
 * **only while the two shapes differ** — a dimension added in the tray but
 * not yet run is no column of these rows — because `fitChartSlots` opens a
 * narrowed slot back up, which is right when the shape moved under the
 * chart and wrong on every render of a chart narrowed on purpose.
 *
 * A follow-up is read off the same config the rows ran on, not the draft:
 * the group pressed is a group of that result, not of what is being edited.
 */
export function useAnalysisResult(
  runtime: ViewRuntime<AnalysisViewConfig> | null,
  analysis: AnalysisEditorController,
  workbench: Pick<
    WorkbenchController,
    'state' | 'canDrill' | 'drill' | 'follow'
  >,
): AnalysisResultController {
  const result = workbench.state?.result;
  const data = result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;
  const ran = result?.config.kind === 'analysis' ? result.config : undefined;

  const drafted = shapeKey(analysis.aliases.groups, analysis.aliases.metrics);
  const shapeRan = ran
    ? shapeKey(
        ran.groups.map(group => group.alias),
        ran.metrics.map(metric => metric.alias),
      )
    : null;
  // The moments of the config that ran, read off its projection: a column
  // that reads as a date is one (`momentMetrics`), and no mark measures it.
  const moments = useMemo(
    () => momentColumns(view?.schema ?? view?.columns ?? []),
    [view],
  );
  const chart = useMemo(
    () =>
      ran && shapeRan !== drafted
        ? fitChartSlots(analysis.chart, ran.groups, ran.metrics, moments)
        : analysis.chart,
    [analysis.chart, ran, shapeRan, drafted, moments],
  );
  // What the picker offers. Over rows, the shape that ran and the rows
  // themselves: a funnel over one dimension takes its stages from them the
  // moment it is picked (`withStagesFrom`), so only they say whether it has
  // the two it needs. Before anything ran — a saved view whose chart is
  // refused runs nothing — the draft's shape, and the stages its chart
  // already names: picking from there is how such a view is repaired.
  const fits = useMemo(
    () =>
      ran
        ? fitCharts({
            groups: ran.groups,
            metrics: ran.metrics,
            moments,
            ...(view ? { rows: view.rows } : {}),
          })
        : fitCharts({
            groups: analysis.groups,
            metrics: analysis.metrics,
            moments: analysis.moments,
            chart: analysis.chart,
          }),
    [
      ran,
      view,
      moments,
      analysis.groups,
      analysis.metrics,
      analysis.moments,
      analysis.chart,
    ],
  );
  // A chart the shape that ran cannot draw — every metric a moment, nothing
  // to measure — is its table: the rows are there, and an empty frame would
  // say there were none. Before anything ran there is no shape to judge.
  const drawable = !ran || fits[chart.type]?.available !== false;
  const chartData = useMemo(
    () =>
      view && ran && drawable
        ? shapeChart(
            { ...ran, chart, layout: 'chart' },
            view.rows,
            view.overall,
          )
        : undefined,
    [view, ran, chart, drawable],
  );
  const picked: Picked =
    analysis.layout === 'table' || !drawable ? 'table' : chart.type;

  // A chart that headlines the whole — a metric card over a trend — is one
  // the rows cannot draw alone: the buckets are only the groups that fit the
  // limit (`asksForWhole`). Picked over rows that ran without the whole, it
  // runs once, as the totals switch does. Not while the draft holds another
  // edit waiting for Apply — running would apply that too, which is not this
  // gesture's to do; the card adds up its buckets until then — and never
  // again for a config that asked and did not get it, which is a failed
  // query and not a missing question.
  const { submit, pending } = analysis;
  const wantsWhole =
    ran !== undefined &&
    analysis.layout === 'chart' &&
    drawable &&
    !asksForWhole(ran) &&
    asksForWhole({ ...ran, chart });
  useEffect(() => {
    if (wantsWhole && !pending) submit();
  }, [wantsWhole, pending, submit]);

  const choose = (next: Picked) => {
    if (!ran || !view) {
      repair(analysis, next, fits);
      return;
    }
    if (next === 'table') {
      analysis.setLayout('table');
      return;
    }
    analysis.setLayout('chart');
    // Fitted to the rows on screen, and a funnel of a group's values given
    // the order they came in, so a type picked draws at once.
    analysis.updateChart(
      withStagesFrom(
        fitChartSlots(
          switchChartType(chart, next),
          ran.groups,
          ran.metrics,
          moments,
        ),
        view.rows,
      ),
    );
  };

  const pickable =
    ran !== undefined &&
    !(ran.elements && ran.elements.length > 0) &&
    runtime !== null;

  const followUp = (row: RecordData): FollowUp | null => {
    if (!pickable || !ran || !runtime) return null;
    const drilled = drillGroups(ran, runtime.fields, runtime.kinds, row, {
      timeZone: runtime.environment.timeZone,
    });
    if (!drilled) return null;
    const conditions = drilled.flatMap(entry => entry.conditions);
    const actions: FollowUpAction[] = [];
    if (workbench.canDrill)
      actions.push({
        kind: 'records',
        subject: runtime.definition.title,
        run: title => workbench.drill(conditions, title),
      });
    // Groupable fields the result is not already grouped by — the list the
    // tray adds a dimension from (`groupableFields`) — read off the config
    // that ran, for the reason the conditions are.
    const options: SplitOption[] = groupableFields(
      analysis.fields,
      ran.groups,
    ).map(option => ({ field: option.field, label: option.label }));
    // Both open beside this view: the question that ran, drawn as the screen
    // draws it — the layout and the chart are the draft's, and nothing else
    // the draft holds is applied by a gesture that did not ask for it.
    const drawn: AnalysisViewConfig = {
      ...ran,
      layout: analysis.layout,
      chart,
    };
    const subject = workbench.state?.title ?? '';
    if (options.length > 0)
      actions.push({
        kind: 'split',
        subject,
        options,
        run: (name, title) => {
          const patch = split(
            runtime,
            analysis,
            drawn,
            conditions,
            name,
            moments,
          );
          if (patch)
            workbench.follow({ ...drawn, ...patch }, title, conditions);
        },
      });
    actions.push({
      kind: 'focus',
      subject,
      run: title =>
        workbench.follow(
          { ...drawn, ...focusOn(ran, conditions) },
          title,
          conditions,
        ),
    });
    const columns = view?.schema ?? view?.columns ?? [];
    return {
      groups: drilled.map(entry => ({
        column: columns.find(column => column.alias === entry.group.alias),
        value: entry.value,
        conditions: describeFilter(
          runtime.fields,
          { op: 'and', children: entry.conditions },
          runtime.kinds,
        ),
      })),
      actions,
    };
  };

  return {
    view,
    ran,
    chart,
    chartData,
    fits,
    picked,
    choose,
    pickable,
    followUp,
  };
}

/**
 * A pick with no rows on screen. Nothing has run — most often because the
 * saved chart is refused (a funnel of one stage, or of averages), and a
 * refused config never runs — so there is nothing to redraw: the pick is
 * fitted to the shape of the draft (`setChartType`), which makes the config
 * one that validates, and then it runs.
 *
 * The table is always a way out, but the chart is validated under a table
 * too, so a refused chart would keep the table from running as well: it is
 * replaced by the type the shape reads best as, or else the first it can
 * draw. The run is left to Apply while the draft holds another edit, as a
 * chart that asks for the whole is (`wantsWhole`): running would apply that
 * too, which is not this gesture's to do.
 */
function repair(
  analysis: AnalysisEditorController,
  next: Picked,
  fits: Record<ChartType, ChartFit>,
): void {
  if (next === 'table') {
    analysis.setLayout('table');
    const refused = analysis.issues.some(
      found => found.severity === 'error' && found.path[0] === 'chart',
    );
    const fallback = refused ? drawableType(fits) : undefined;
    if (fallback) analysis.setChartType(fallback);
  } else {
    analysis.setLayout('chart');
    analysis.setChartType(next);
  }
  if (!analysis.pending) analysis.submit();
}

/** The type a shape reads best as, else the first it can draw at all. */
function drawableType(
  fits: Record<ChartType, ChartFit>,
): ChartType | undefined {
  const types = CHART_PICKER_ORDER.filter(type => fits[type].available);
  return types.find(type => fits[type].recommended) ?? types[0];
}

/**
 * The patch that asks `config` of the group by the field named, or null
 * where the field is not one to split by. The chart's slots follow the new
 * shape; the metrics do not change, so neither do the moments among them.
 */
function split(
  runtime: ViewRuntime<AnalysisViewConfig>,
  analysis: AnalysisEditorController,
  config: AnalysisViewConfig,
  conditions: readonly FilterNode[],
  name: string,
  moments: ReadonlySet<string>,
): ReturnType<typeof splitBy> | null {
  const field = runtime.fields.find(entry => entry.name === name);
  const option = analysis.fields.find(entry => entry.field === name);
  if (!field || !option) return null;
  return splitBy(
    config,
    conditions,
    groupFor(field, option, runtime.kinds.get(field.kind)),
    moments,
  );
}

/**
 * One shape as the one string a chart addresses it by: a chart names groups
 * and metrics by alias and by nothing else, so two shapes that spell the
 * same aliases in the same order are one shape as far as its slots go. The
 * separators are control characters no alias can hold, written as escapes:
 * a raw one in the source makes every text tool read the file as binary.
 */
function shapeKey(groups: readonly string[], metrics: readonly string[]) {
  return `${groups.join('\u0000')}\u0001${metrics.join('\u0000')}`;
}
