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

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  drillConditions,
  fitChartSlots,
  fitCharts,
  focusOn,
  groupFor,
  groupableFields,
  shapeChart,
  splitBy,
  withStagesFrom,
  type AnalysisView,
  type Picked,
} from '../../analysis/index.js';
import { describeFilter } from '../../filter/index.js';
import type { AnalysisViewConfig, FieldOption } from '../../model/index.js';
import type { ViewRuntime } from '../../runtime/index.js';
import {
  useAnalysisEditor,
  type WorkbenchController,
} from '../../react/index.js';
import { AnalysisChart } from '../AnalysisChart.js';
import { AnalysisTable } from '../AnalysisTable.js';
import { AnalysisToolbar } from '../analysis/AnalysisToolbar.js';
import { ChartOptions } from '../analysis/ChartOptions.js';
import { ChartPicker } from '../analysis/ChartPicker.js';
import { Tray } from '../analysis/Tray.js';
import { Button } from '../components/button.js';
import { DrillMenu, type Pick } from '../analysis/DrillMenu.js';
import { AnalysisEmpty } from '../analysis/EmptyResult.js';
import { useAnnouncer } from '../Announcer.js';
import { featuresOf, type WorkbenchFeatures } from '../features.js';
import type { ViewMessages } from '../messages.js';
import { useViewMessages } from '../MessagesProvider.js';
import { NO_PARTS, type RenderParts } from './parts.js';

export interface AnalysisPartsProps {
  workbench: WorkbenchController;
  /** The open analysis view, or null while what is open is not one. */
  runtime: ViewRuntime<AnalysisViewConfig> | null;
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * Which of the workbench's own controls are on screen (D18 XI). One name
   * here is the analysis view's — `visualization`; the rest belong to the
   * record view and are passed through untouched.
   */
  features?: WorkbenchFeatures;
  children: RenderParts;
}

/**
 * What makes an analysis view an analysis view: the conditions, what to
 * aggregate, and the result as a table or a chart. Which of the two is
 * showing is part of the saved config, and both keep their own settings, so
 * switching back and forth loses nothing.
 *
 * It renders nothing of its own — it hands its parts to `children`, which
 * draws the shell around them, and hands back none while no analysis view
 * is open (`parts.ts`).
 */
export function AnalysisParts({
  workbench,
  runtime,
  messages: wording,
  optionsFor,
  features,
  children,
}: AnalysisPartsProps) {
  const messages = useViewMessages(wording);
  const shown = featuresOf(features);
  const { filter, state } = workbench;
  const analysis = useAnalysisEditor(runtime);

  const data = state?.result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;
  /**
   * The rows are the config that ran; how they are looked at is the draft.
   *
   * The result's rows and columns come from the config the result was
   * shaped by, and nothing else can name them. The layout and the chart are
   * presentation (D20, `ANALYSIS_PRESENTATION_MEMBERS`): they are drawn
   * from those rows as the draft says, so switching to a chart or picking
   * another type redraws without a run.
   *
   * The draft's chart is fitted to the shape that ran **only while the two
   * shapes differ** — a dimension added in the tray but not yet run is no
   * column of these rows, and a chart addressing it would draw nothing.
   * While the draft asks the question the rows answer, the chart is drawn
   * exactly as it is saved: `fitChartSlots` opens a narrowed slot back up
   * (one series becomes every metric when the second dimension goes), which
   * is right when the shape moved under the chart and wrong on every render
   * of a chart whose author narrowed it on purpose.
   */
  const applied = state?.result?.config;
  const shaped = applied?.kind === 'analysis' ? applied : undefined;
  const layout = analysis.layout;
  const drafted = aliasesOf(analysis.aliases.groups, analysis.aliases.metrics);
  const ran = shaped
    ? aliasesOf(
        shaped.groups.map(group => group.alias),
        shaped.metrics.map(metric => metric.alias),
      )
    : null;
  const chart = useMemo(
    () =>
      shaped && ran !== drafted
        ? fitChartSlots(analysis.chart, shaped.groups, shaped.metrics)
        : analysis.chart,
    [analysis.chart, shaped, ran, drafted],
  );
  const chartData = useMemo(
    () =>
      view && shaped
        ? shapeChart(
            { ...shaped, chart, layout: 'chart' },
            view.rows,
            view.totals,
          )
        : undefined,
    [view, shaped, chart],
  );

  // The visualization panel (D20 屏 I／J): open from the result's toolbar,
  // it takes the sidebar column, first as the chart types, then as the
  // chosen type's options; both fit the shape that ran.
  const [panel, setPanel] = useState<'picker' | 'options' | null>(null);
  // A host that switched the panel off has no panel, whatever this state
  // says: the one way in is the toolbar's button, which is gone with it, and
  // an absent feature is absent rather than merely unreachable.
  const level = shown.visualization ? panel : null;
  /**
   * The keyboard follows the level (A1).
   *
   * The panel takes the sidebar's column and replaces its own contents as
   * the level changes, so every one of those changes used to leave the
   * focus on an element that is no longer on the page — the press that
   * opened it, the gear, the way back — and a focus with nothing under it
   * falls to `<body>`. Each level therefore takes the keyboard to its own
   * heading, and closing the panel hands it back to the button that opened
   * it, which is where the user was.
   *
   * It is an effect keyed on the level rather than anything done inside the
   * press: the heading does not exist until React has drawn the level, and
   * a timer waiting for that would be a guess.
   */
  const heading = useRef<HTMLHeadingElement | null>(null);
  const visualizeRef = useRef<HTMLButtonElement | null>(null);
  // Whatever the panel is a panel *of*, remembered while it is open: with
  // the toolbar gone there is no button to go back to, and the result the
  // panel is about is the next best place — found through the panel's own
  // surface, because a page may hold more than one view (a dashboard does).
  const surface = useRef<HTMLElement | null>(null);
  const was = useRef(level);
  useEffect(() => {
    const before = was.current;
    if (before === level) return;
    was.current = level;
    if (level !== null) {
      surface.current =
        heading.current?.closest<HTMLElement>('[data-slot="view-surface"]') ??
        surface.current;
      heading.current?.focus();
      return;
    }
    if (before === null) return;
    const button = visualizeRef.current;
    if (button) {
      button.focus();
      return;
    }
    const block = surface.current?.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    );
    if (!block) return;
    // The section is not a control and owns no `tabindex` of its own; it is
    // given one for this landing only, the way a skip link's target is.
    block.setAttribute('tabindex', '-1');
    block.focus();
  }, [level]);
  const fits = useMemo(
    () =>
      fitCharts({
        groups: shaped?.groups ?? [],
        metrics: shaped?.metrics ?? [],
      }),
    [shaped],
  );
  const picked: Picked = layout === 'table' ? 'table' : chart.type;
  const choose = (next: Picked) => {
    if (next === 'table') {
      analysis.setLayout('table');
      return;
    }
    analysis.setLayout('chart');
    if (!shaped || !view) return;
    // Fitted to the rows on screen, and a funnel of a group's values given
    // the order they came in, so a type picked draws at once.
    analysis.updateChart(
      withStagesFrom(
        fitChartSlots({ ...chart, type: next }, shaped.groups, shaped.metrics),
        view.rows,
      ),
    );
  };

  // The group the user pressed, on the chart or in the table, and the menu
  // over it (D20 追问). Its conditions come from the config the result was
  // shaped by — the row is a row of that result — and are described by the
  // origin's fields, in the applied bar's words.
  const [pick, setPick] = useState<Pick | null>(null);
  const conditions =
    pick && shaped && runtime
      ? drillConditions(shaped, runtime.fields, runtime.kinds, pick.row, {
          timeZone: runtime.environment.timeZone,
        })
      : null;
  const described =
    conditions && runtime
      ? describeFilter(
          runtime.fields,
          { op: 'and', children: conditions },
          runtime.kinds,
        )
      : [];
  // Only a group a condition can say is worth a menu: an analysis over
  // expanded elements has rows no root condition selects, so its marks and
  // rows are not pressable at all.
  const pickable =
    shaped !== undefined &&
    !(shaped.elements && shaped.elements.length > 0) &&
    runtime !== null;
  const onPick = pickable
    ? (row: Pick['row'], anchor: Pick['anchor']) => setPick({ row, anchor })
    : undefined;
  // The dimensions the group can be split by: groupable fields the result
  // on screen is not already grouped by (`groupableFields`, the list the
  // tray adds a dimension from). Read off the config that shaped it
  // rather than off the draft, for the same reason the conditions are: the
  // group pressed is a group of that result, not of what is being edited.
  const splits = groupableFields(analysis.fields, shaped?.groups ?? []).map(
    option => ({ field: option.field, label: option.label }),
  );
  const close = () => setPick(null);
  const records = () => {
    if (conditions) workbench.drill(conditions);
    close();
  };
  const focus = () => {
    if (conditions && shaped && runtime) {
      runtime.edit(focusOn(shaped, conditions));
      runtime.apply();
    }
    close();
  };
  const split = (name: string) => {
    const field = runtime?.fields.find(entry => entry.name === name);
    const option = analysis.fields.find(entry => entry.field === name);
    if (conditions && shaped && runtime && field && option) {
      runtime.edit(
        splitBy(
          shaped,
          conditions,
          groupFor(field, option, runtime.kinds.get(field.kind)),
        ),
      );
      runtime.apply();
    }
    close();
  };

  // The one live region of this surface: a query that lands is a change of
  // the numbers on screen, and a reader who cannot see them has to be told
  // — as the record view's rows are (`record/queryAnnouncement.ts`).
  const { say, region: announcement } = useAnnouncer('analysis-announcement');
  const querying = state?.query.status === 'loading';
  const sentence = querying
    ? messages.label('label.status.querying')
    : view
      ? messages.label('label.status.groups', { count: view.rows.length })
      : null;
  const said = useRef<string | null>(null);
  useEffect(() => {
    if (sentence === null || sentence === said.current) return;
    said.current = sentence;
    say(sentence);
  }, [say, sentence]);

  // The tray's fold, held here only so a config that will not run can open
  // it from the status line (F11). Tagged with the runtime it was set for
  // and handed to the shell, which owns the fold otherwise.
  const runtimeId = runtime?.id ?? null;
  const [fold, setFold] = useState<{ id: string | null; open: boolean } | null>(
    null,
  );

  if (!runtime) return children(NO_PARTS);
  return children({
    // The tray folds under the title bar's "Analysis" button, exactly where
    // the record view's "Filter" folds (D20): a saved view opens folded,
    // a new one opens out.
    editorLabel: messages.label('label.analysis.editor'),
    editorOpen: fold?.id === runtimeId ? fold.open : undefined,
    onEditorOpenChange: open => setFold({ id: runtimeId, open }),
    // Edited, not run — whichever slot holds the change: the toggle wears
    // the dot while the tray is folded away.
    //
    // `filter.pendingCount` is already the count over the *whole* config
    // (`comparePending(draft, applied)`), dimensions and metrics included,
    // so nothing is added for the question's half: adding `analysis.pending`
    // on top said "3 not applied" for the one dimension that had been added,
    // and a count nobody can match to what they did is worse than no count.
    editorPending: filter.pendingCount,
    /* Not frozen while a query runs: editing never re-queries, and a refresh
       that lands mid-edit must not take the inputs away. */
    editor: (
      <Tray
        filter={filter}
        analysis={analysis}
        optionsFor={optionsFor}
        autoRun={{
          on: workbench.autoRun,
          // The write's outcome reaches the list's reload, not this switch.
          set: on => void workbench.setAutoRun(on),
        }}
      />
    ),
    // The way out of a config that will not run: the tray, which is where
    // the finding is about (F11).
    errorAction: (
      <Button
        variant="outline"
        size="xs"
        onClick={() => setFold({ id: runtimeId, open: true })}
      >
        {messages.label('label.analysis.open-editor')}
      </Button>
    ),
    toolbar: view && view.rows.length > 0 && (
      <AnalysisToolbar
        analysis={analysis}
        view={view}
        visualizing={level !== null}
        visualizeRef={visualizeRef}
        {...(shown.visualization
          ? {
              onVisualize: (open: boolean) => setPanel(open ? 'picker' : null),
            }
          : {})}
      />
    ),
    panel:
      level === 'picker' ? (
        <ChartPicker
          headingRef={heading}
          fits={fits}
          picked={picked}
          onPick={choose}
          onOptions={() => setPanel('options')}
          onBack={() => setPanel(null)}
        />
      ) : level === 'options' && view ? (
        <ChartOptions
          headingRef={heading}
          picked={picked}
          chart={chart}
          groups={shaped?.groups ?? []}
          metrics={shaped?.metrics ?? []}
          columns={view.schema ?? view.columns}
          rows={view.rows}
          totals={analysis.totals}
          onTotals={on => {
            analysis.setTotals(on);
            analysis.submit();
          }}
          onChange={next => analysis.updateChart(next)}
          onBack={() => setPanel('picker')}
        />
      ) : null,
    result: view && (
      <div
        data-slot="analysis-result"
        // While the draft is about to run on its own (改了就跑), the rows on
        // screen answer the last question: kept, faded rather than cleared,
        // because the next answer is moments away and a blank in between
        // reads as a failure.
        data-stale={analysis.stale || undefined}
        // A column of its own rather than `contents`: an element with
        // `display: contents` generates no box, and a property that paints
        // one — `opacity` here — is then computed and never rendered, so
        // the fade above would be a class nobody can see. A flex column
        // lays the three parts out exactly as the result block laid them
        // out when they were its own children (no gap, no margin
        // collapsing, `min-w-0` so a wide table still scrolls inside it).
        className="flex min-w-0 flex-col data-[stale]:opacity-60 data-[stale]:transition-opacity"
      >
        {/* A grouping nothing fell into is one sentence whichever layout is
            in force; a chart of no rows is a pair of empty axes, which reads
            as a drawing that failed rather than as a range that matched
            nothing. */}
        {view.rows.length === 0 ? (
          <AnalysisEmpty />
        ) : layout === 'chart' && chartData ? (
          <AnalysisChart
            data={chartData}
            spec={chart}
            columns={view.schema ?? view.columns}
            onPick={onPick}
          />
        ) : (
          <AnalysisTable view={view} onPick={onPick} />
        )}
        {pickable && (
          <DrillMenu
            pick={conditions ? pick : null}
            onClose={close}
            conditions={described}
            canDrill={workbench.canDrill}
            splits={splits}
            onRecords={records}
            onSplit={split}
            onFocus={focus}
          />
        )}
        {/* Last in the block, where nothing about it can be reached by a
            pointer or a tab: it draws nothing and is read, not seen. */}
        {announcement}
      </div>
    ),
  });
}

/**
 * One shape as the one string a chart addresses it by: a chart names groups
 * and metrics by alias and by nothing else, so two shapes that spell the
 * same aliases in the same order are one shape as far as its slots go.
 */
function aliasesOf(groups: readonly string[], metrics: readonly string[]) {
  return `${groups.join(' ')}${metrics.join(' ')}`;
}
