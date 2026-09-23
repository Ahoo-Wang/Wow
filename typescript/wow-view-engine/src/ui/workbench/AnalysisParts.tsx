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

import { useEffect, useRef, useState } from 'react';
import type { AnalysisViewConfig, FieldOption } from '../../model/index.js';
import type { ViewRuntime } from '../../runtime/index.js';
import {
  useAnalysisEditor,
  useSearchBox,
  useAnalysisResult,
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
import { formatNumber } from '../display.js';
import { resultSlots } from '../variants.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { NO_PARTS, type RenderParts } from './parts.js';
import { SearchBox } from './SearchBox.js';

export interface AnalysisPartsProps {
  workbench: WorkbenchController;
  /** The open analysis view, or null while what is open is not one. */
  runtime: ViewRuntime<AnalysisViewConfig> | null;
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language the surface reads in. The surface is inside the shell this
   * part is handed to, so the counts this part words itself — 「1,204 组」
   * — take it from here rather than from the provider.
   */
  locale?: string;
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
  locale,
  optionsFor,
  features,
  children,
}: AnalysisPartsProps) {
  const messages = useViewMessages(wording, locale);
  const shown = featuresOf(features);
  const { filter, state } = workbench;
  const analysis = useAnalysisEditor(runtime);
  const searchBox = useSearchBox(runtime);

  const result = useAnalysisResult(runtime, analysis, workbench);
  const { view, chart, chartData, fits, picked } = result;
  const layout = analysis.layout;

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
  // The group the user pressed, on the chart or in the table, and the menu
  // over it (D20 追问). What the menu offers is the controller's; which row
  // was pressed, and where, is the screen's.
  const [pick, setPick] = useState<Pick | null>(null);
  const followUp = pick ? result.followUp(pick.row) : null;
  const onPick = result.pickable
    ? (row: Pick['row'], anchor: Pick['anchor']) => setPick({ row, anchor })
    : undefined;
  const close = () => setPick(null);

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
    // The caption is this kind's furniture in the frame (`resultSlots`).
    resultSlots: RESULT_SLOTS,
    // The tray folds under the title bar's "Analysis" button, exactly where
    // the record view's "Filter" folds (D20): a saved view opens folded,
    // a new one opens out.
    search: shown.search && searchBox && <SearchBox search={searchBox} />,
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
          onPick={result.choose}
          onOptions={() => setPanel('options')}
          onBack={() => setPanel(null)}
        />
      ) : level === 'options' && view ? (
        <ChartOptions
          headingRef={heading}
          picked={picked}
          chart={chart}
          groups={result.ran?.groups ?? []}
          metrics={result.ran?.metrics ?? []}
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
      <>
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
          {result.pickable && (
            <DrillMenu
              pick={followUp ? pick : null}
              onClose={close}
              followUp={followUp}
            />
          )}
          {/* Last in the block, where nothing about it can be reached by a
              pointer or a tab: it draws nothing and is read, not seen. */}
          {announcement}
        </div>
        {/* The frame's last row, as the record view's pagination is: how
            many rows are on screen and how long they took. It holds the
            report up from the bottom edge — without it a short table or a
            chart ended wherever it ended, and the space under it read as
            the report having dropped off (the user's 2026-09-23 review). */}
        <AnalysisCaption
          rows={view.rows.length}
          elapsedMs={state?.result?.elapsedMs ?? null}
        />
      </>
    ),
  });
}

/** What the analysis result hands the frame to dress (`ResultBlock.slots`). */
const RESULT_SLOTS = resultSlots('caption');

/**
 * The analysis result's footer: 「正在显示 12 组，耗时 0.38 秒」.
 *
 * The time reads as a person reads a stopwatch: two decimals under a
 * second, where the difference between 0.04 and 0.38 is the difference a
 * reader notices, and one above it.
 */
function AnalysisCaption({
  rows,
  elapsedMs,
}: {
  rows: number;
  elapsedMs: number | null;
}) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const seconds = (elapsedMs ?? 0) / 1000;
  return (
    <div
      data-slot="analysis-caption"
      // On the right, where the record view's pager is: the left of the
      // row is where a reader starts the report, and this is its footnote
      // (the user's 2026-09-23 review).
      className="text-muted-foreground text-right text-sm tabular-nums"
    >
      {messages.label('label.analysis.caption', {
        count: formatNumber(rows, undefined, locale),
        seconds: formatNumber(
          seconds,
          { maximumFractionDigits: seconds < 1 ? 2 : 1 },
          locale,
        ),
      })}
    </div>
  );
}
