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
  drillGroups,
  fitChartSlots,
  fitCharts,
  focusOn,
  groupFor,
  groupableFields,
  measureColumns,
  momentColumns,
  asksForWhole,
  projectAnalysis,
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
import {
  drillGap,
  drillRecordConditions,
  drillSpan,
  drilledFields,
} from '../analysis/drill.js';
import { foldsSplit } from '../analysis/splitOther.js';
import { describeFilter, type FilterSummaryItem } from '../filter/index.js';
import type {
  AnalysisViewConfig,
  ChartSpec,
  ChartType,
  FilterNode,
  RecordData,
} from '../model/index.js';
import { hasAsked, type ViewRuntime } from '../runtime/index.js';
import type { AnalysisEditorController } from './useAnalysisEditor.js';
import type { WorkbenchController } from './useWorkbench.js';

/** Stable identity for "nothing asked yet", so the memos below stay quiet. */
const NO_COLUMNS: readonly AnalysisColumnView[] = [];

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
   *
   * With `gap`, offered and greyed with the reason it cannot run (D38): a
   * group counted over elements of elements, which no condition over the
   * records reaches (`drillGap`) — the menu says so rather than leave the
   * reader to wonder where 「查看这些记录」 went — and `run` does nothing.
   */
  | {
      kind: 'records';
      subject: string;
      run(title: string): void;
      gap?: 'nested-elements';
    }
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
  | { kind: 'focus'; subject: string; run(title: string): void }
  /**
   * Set a board's filter to the stretch of time the menu is about (D33
   * Q52): offered on a dashboard panel only, over a span, for each date
   * filter wired to the field it spans. The panel it was set from keeps
   * every group, as a press that sets a filter leaves it (D23 Q18). `filter`
   * is that filter's label, for the item to name it by.
   */
  | { kind: 'filter'; filter: string; run(): void };

/** One dimension of the group pressed, for the menu to name it by. */
export interface FollowUpGroup {
  /**
   * The conditions that select it, as the applied bar names them — a date
   * bucket's range as its period, a number band's two comparisons as the
   * one segment they bound — so the menu, the name of a view opened from it
   * and that view's applied bar say one sentence.
   */
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
  /**
   * The question the result answers or will answer: `ran` once rows are on
   * screen; before any are, the config on its way or the one that failed.
   * Undefined while nothing has been asked (`hasAsked`). What the rows are
   * *shaped* by — the reading, the chart types that fit, the slots a chart
   * can name — is known from the moment the question is sent, so the
   * toolbar and the visualization panel work while the first answer is on
   * its way and after it failed; only what needs the rows waits for them.
   */
  question: AnalysisViewConfig | undefined;
  /**
   * Every column of that question, as the result names them — the rows' own
   * schema, or the same projection over no rows while none have landed —
   * and the ones the table draws, in its order (`AnalysisView.columns`).
   * Both empty while nothing has been asked. The toolbar reads the first
   * out; a skeleton standing in for the table draws a bar per the second.
   */
  columns: readonly AnalysisColumnView[];
  tableColumns: readonly AnalysisColumnView[];
  /** The chart drawn over those rows, as the draft says to draw it. */
  chart: ChartSpec;
  /** The rows shaped for `chart`, or undefined while there is nothing to draw. */
  chartData: ChartData | undefined;
  /**
   * Which chart types the question's shape can draw, its rows included once
   * there are any (`fitCharts`); before anything was asked, the draft's
   * shape and the stages its chart names.
   */
  fits: Record<ChartType, ChartFit>;
  /** What the visualization panel shows as chosen. */
  picked: Picked;
  /**
   * Draw the rows as this type, or as the table. Over a question — rows on
   * screen, the first answer on its way, or a query that failed — it
   * redraws and never runs; with none asked — a saved chart refused, so
   * nothing ran — it fits the type to the draft's shape and runs (`repair`).
   */
  choose(next: Picked): void;
  /**
   * Whether a group of this result can be followed up at all. Over one
   * level of expanded elements its records are asked of one element (D38);
   * over elements of elements the menu opens and says why it cannot; over
   * an array the definition lets no condition match into, not at all.
   */
  pickable: boolean;
  /**
   * The menu over one pressed group, or null when no condition can say it.
   * With `through`, over a span instead (D33 Q52): every bucket of a time
   * axis from `row`'s to `through`'s, read as one range (`drillSpan`) — a
   * brush along the axis, or a second row picked with Shift in the table.
   */
  followUp(row: RecordData, through?: RecordData): FollowUp | null;
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
  // Before the first answer, the question itself. A query that was sent was
  // admitted, so the applied config is one the kernel can project; a config
  // refused before it ran was never asked, and stays undefined.
  const state = workbench.state;
  const applied = state?.applied;
  const asked =
    !ran && hasAsked(state) && applied?.kind === 'analysis'
      ? applied
      : undefined;
  const question = ran ?? asked;
  const definition = runtime?.definition;
  const shape = useMemo<Pick<AnalysisView, 'columns' | 'schema'> | null>(
    () =>
      view ??
      (asked && definition
        ? // Over no rows and as a table: the columns are all that is
          // wanted, and a chart shaped from nothing is work for no one.
          projectAnalysis(definition, { ...asked, layout: 'table' }, [])
        : null),
    [view, asked, definition],
  );
  const columns = shape ? (shape.schema ?? shape.columns) : NO_COLUMNS;
  const tableColumns = shape?.columns ?? NO_COLUMNS;

  const drafted = shapeKey(analysis.aliases.groups, analysis.aliases.metrics);
  const shapeAsked = question
    ? shapeKey(
        question.groups.map(group => group.alias),
        question.metrics.map(metric => metric.alias),
      )
    : null;
  // The moments of the question, read off its projection: a column that
  // reads as a date is one (`momentMetrics`), and no mark measures it.
  const moments = useMemo(() => momentColumns(columns), [columns]);
  // And what each measures (`metricMeasure`), so a combo picked here puts a
  // count and an amount on two axes as the editor would.
  const measures = useMemo(() => measureColumns(columns), [columns]);
  const chart = useMemo(
    () =>
      question && shapeAsked !== drafted
        ? fitChartSlots(
            analysis.chart,
            question.groups,
            question.metrics,
            moments,
            measures,
          )
        : analysis.chart,
    [analysis.chart, question, shapeAsked, drafted, moments, measures],
  );
  // What the picker offers. Over rows, the shape that ran and the rows
  // themselves: a funnel over one dimension takes its stages from them the
  // moment it is picked (`withStagesFrom`), so only they say whether it has
  // the two it needs. Before anything ran — a saved view whose chart is
  // refused runs nothing — the draft's shape, and the stages its chart
  // already names: picking from there is how such a view is repaired.
  const fits = useMemo(
    () =>
      question
        ? fitCharts({
            groups: question.groups,
            metrics: question.metrics,
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
      question,
      view,
      moments,
      analysis.groups,
      analysis.metrics,
      analysis.moments,
      analysis.chart,
    ],
  );
  // A chart the question's shape cannot draw — every metric a moment,
  // nothing to measure — is its table: the rows are there, and an empty
  // frame would say there were none. Before anything was asked there is no
  // shape to judge.
  const drawable = !question || fits[chart.type]?.available !== false;
  // Shaped as the runtime shaped it (`projectAnalysis`): day buckets stepped
  // in the engine's zone rather than the browser's, and a trend card's last
  // period the last one over when the question was asked — which is when
  // its answer arrived, less the time it took.
  const timeZone = runtime?.environment.timeZone;
  const askedAt = result ? result.receivedAt - result.elapsedMs : undefined;
  const chartData = useMemo(
    () =>
      view && ran && drawable
        ? shapeChart(
            { ...ran, chart, layout: 'chart' },
            view.rows,
            view.overall,
            {
              ...(timeZone === undefined ? {} : { timeZone }),
              ...(askedAt === undefined ? {} : { now: new Date(askedAt) }),
              cutShort: view.truncated || view.atLimit !== undefined,
              ...(view.splitWhole ? { splitWhole: view.splitWhole } : {}),
              ...(view.approximate ? { approximate: view.approximate } : {}),
            },
          )
        : undefined,
    [view, ran, chart, drawable, timeZone, askedAt],
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
  // So does a split picked past the palette over rows that ran without its
  // axis's whole: its rest is folded into 「其他」 against that (D33 Q56).
  const wantsSplitWhole =
    view !== null &&
    ran !== undefined &&
    analysis.layout === 'chart' &&
    drawable &&
    !foldsSplit(ran, view.rows) &&
    foldsSplit({ ...ran, chart }, view.rows);
  useEffect(() => {
    if ((wantsWhole || wantsSplitWhole) && !pending) submit();
  }, [wantsWhole, wantsSplitWhole, pending, submit]);

  const choose = (next: Picked) => {
    // Nothing asked — a saved chart refused, so nothing ran — is a repair.
    // A question on its way or one that failed is not: its shape is known,
    // and a pick redraws over it as it would over rows.
    if (!question) {
      repair(analysis, next);
      return;
    }
    if (next === 'table') {
      analysis.setLayout('table');
      return;
    }
    analysis.setLayout('chart');
    // Fitted to the question's shape, so a type picked while the first
    // answer is on its way — or after it failed — is the chart the rows
    // land in; and a funnel of a group's values given the order they came
    // in, once there are rows to take it from.
    const fitted = fitChartSlots(
      switchChartType(chart, next),
      question.groups,
      question.metrics,
      moments,
      measures,
    );
    analysis.updateChart(view ? withStagesFrom(fitted, view.rows) : fitted);
  };

  // Over expanded elements a group names an element, and the records behind
  // it are the ones with such an element (D38): one level can say that, a
  // deeper chain cannot and says so, an array no condition matches into
  // offers nothing.
  const expanded = (ran?.elements?.length ?? 0) > 0;
  const gap = ran && drillGap(ran);
  const pickable =
    ran !== undefined &&
    runtime !== null &&
    (!expanded ||
      gap !== undefined ||
      drillRecordConditions(ran, runtime.fields, runtime.kinds, []) !== null);

  const followUp = (row: RecordData, through?: RecordData): FollowUp | null => {
    if (!pickable || !ran || !runtime) return null;
    const context = { timeZone: runtime.environment.timeZone };
    const drilled = through
      ? drillSpan(ran, runtime.fields, runtime.kinds, row, through, context)
      : drillGroups(ran, runtime.fields, runtime.kinds, row, context);
    if (!drilled) return null;
    const conditions =
      drillRecordConditions(ran, runtime.fields, runtime.kinds, drilled) ?? [];
    const actions: FollowUpAction[] = [];
    // Each view opened is named by its subject and the group, and called
    // by its subject alone once the reader takes the group off it.
    if (workbench.canDrill) {
      const records = runtime.definition.title;
      actions.push(
        gap
          ? { kind: 'records', subject: records, gap, run: () => {} }
          : {
              kind: 'records',
              subject: records,
              run: title => workbench.drill(conditions, title, records),
            },
      );
    }
    // The group named as the menu heads it, by the fields its conditions
    // name: the root's, or the counted element's.
    const named = [...(drilledFields(ran, runtime.fields)?.values() ?? [])];
    const heading = drilled.map(entry => ({
      conditions: describeFilter(
        named,
        { op: 'and', children: entry.conditions },
        runtime.kinds,
      ),
    }));
    // Over elements only the records: 「只看这一组」 and 「按…拆开」 would
    // narrow the range the elements are counted under, which is the
    // expansion's gate and not the view's conditions — not offered yet.
    if (expanded)
      return actions.length > 0 ? { groups: heading, actions } : null;
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
            workbench.follow(
              { ...drawn, ...patch },
              title,
              conditions,
              subject,
            );
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
          subject,
        ),
    });
    return { groups: heading, actions };
  };

  return {
    view,
    ran,
    question,
    columns,
    tableColumns,
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
 * The table is always a way out, and needs nothing else: under the table
 * the chart is not judged (D20, `validateAnalysis`), so the refused chart
 * stays as it was saved and the table runs. The run is left to Apply while
 * the draft holds another edit, as a chart that asks for the whole is
 * (`wantsWhole`): running would apply that too, which is not this gesture's
 * to do.
 */
function repair(analysis: AnalysisEditorController, next: Picked): void {
  if (next === 'table') analysis.setLayout('table');
  else {
    analysis.setLayout('chart');
    analysis.setChartType(next);
  }
  if (!analysis.pending) analysis.submit();
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
