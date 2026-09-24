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
import type {
  AnalysisSort,
  AnalysisViewConfig,
  FieldOption,
} from '../../model/index.js';
import type { ViewRuntime } from '../../runtime/index.js';
import { CUT_SHORT_CODES } from '../../runtime/execute.js';
import { resultIssues } from '../../runtime/source.js';
import {
  useAnalysisEditor,
  useSearchBox,
  useAnalysisResult,
  type WorkbenchController,
} from '../../react/index.js';
import { AnalysisChart } from '../AnalysisChart.js';
import { AnalysisTable } from '../AnalysisTable.js';
import { AnalysisToolbar } from '../analysis/AnalysisToolbar.js';
import { Tray } from '../analysis/Tray.js';
import {
  useVisualizationFocus,
  type VisualizationLevel,
  visualizationPanel,
} from '../analysis/VisualizationPanel.js';
import { analysisIssueNamer } from '../analysis/issueNames.js';
import { Button } from '../components/button.js';
import { DrillMenu, type Pick as Pressed } from '../analysis/DrillMenu.js';
import { useHeaderSort } from '../analysis/headerSort.js';
import { AnalysisEmpty } from '../analysis/EmptyResult.js';
import {
  AnalysisSkeleton,
  CaptionSkeleton,
} from '../analysis/SkeletonResult.js';
import { wayOutOf } from '../record/emptyWayOut.js';
import { useAnnouncer } from '../Announcer.js';
import { NoteStrip, WarningStrip } from '../StatusStrip.js';
import { featuresOf, type WorkbenchFeatures } from '../features.js';
import type { ViewMessages } from '../messages.js';
import { useViewMessages } from '../MessagesProvider.js';
import { formatNumber } from '../display.js';
import { resultSlots } from '../variants.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { NO_PARTS, type RenderParts } from './parts.js';
import { SearchBox } from './SearchBox.js';

/**
 * What the analysis parts read of the workbench around them: the data
 * workbench's own controller, or — for an analysis made inside a dashboard
 * (D22 C) — the few members a dialog holding its own view supplies.
 */
export type AnalysisHost = Pick<
  WorkbenchController,
  | 'filter'
  | 'state'
  | 'autoRun'
  | 'setAutoRun'
  | 'canDrill'
  | 'drill'
  | 'follow'
>;

export interface AnalysisPartsProps {
  workbench: AnalysisHost;
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
  /**
   * Whether a press on a group offers the follow-ups (D20 追问). On in a
   * workbench, which opens what they ask beside the view; off where there is
   * no beside — the dashboard's dialog making a new analysis.
   */
  followUps?: boolean;
  /**
   * What the visualization panel's way back is called. In the workbench it
   * stands where the view list stood, and its way back says so; where there
   * is no list — the dashboard's dialog — the host names it.
   */
  visualizationBack?: string;
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
  followUps = true,
  visualizationBack,
  children,
}: AnalysisPartsProps) {
  const messages = useViewMessages(wording, locale);
  const shown = featuresOf(features);
  const { filter, state } = workbench;
  const analysis = useAnalysisEditor(runtime);
  const searchBox = useSearchBox(runtime);
  // The status line says every dimension, metric and field a finding names
  // as the screen does, never by a program's key (`analysisIssueNamer`).
  const nameIssue = analysisIssueNamer(analysis, messages);

  const result = useAnalysisResult(runtime, analysis, workbench);
  const { view, question, chart, chartData } = result;
  const layout = analysis.layout;
  // The headers order the groups by the column pressed, and run (the
  // record table's header does the same). A sort needs a dimension — Wow
  // refuses one over an ungrouped aggregation, which is one row anyway.
  const headerSort = useHeaderSort(analysis, result.ran?.sort ?? NO_SORT);
  const sortable = (result.ran?.groups.length ?? 0) > 0;

  // The visualization panel (D20 屏 I／J): open from the result's toolbar,
  // it takes the sidebar column, first as the chart types, then as the
  // chosen type's options; both fit the shape that ran.
  const [panel, setPanel] = useState<VisualizationLevel | null>(null);
  // A host that switched the panel off has no panel, whatever this state
  // says: the one way in is the toolbar's button, which is gone with it, and
  // an absent feature is absent rather than merely unreachable.
  const level = shown.visualization ? panel : null;
  // The keyboard follows the level (A1, `useVisualizationFocus`). Once
  // anything was asked the button is there to go back to: the toolbar
  // stands from the moment a question is sent — an empty result and a
  // failed one keep it. It used to go with the rows, so the focus kept a
  // second landing (the result block) for a result that came back empty
  // under an open panel; that state no longer exists. The one other way in,
  // the status line's chart options over a chart refused before it ran, has
  // no result block to land on either: nothing was asked.
  const focus = useVisualizationFocus(level);
  // The group the user pressed, on the chart or in the table, and the menu
  // over it (D20 追问). What the menu offers is the controller's; which row
  // was pressed, and where, is the screen's.
  const [pick, setPick] = useState<Pressed | null>(null);
  const followUp = pick ? result.followUp(pick.row) : null;
  const onPick =
    result.pickable && followUps
      ? (
          row: Pressed['row'],
          anchor: Pressed['anchor'],
          origin?: HTMLElement,
        ) => setPick({ row, anchor, ...(origin ? { origin } : {}) })
      : undefined;
  const close = () => setPick(null);

  // The one live region of this surface: a query that lands is a change of
  // the numbers on screen, and a reader who cannot see them has to be told
  // — as the record view's rows are (`record/queryAnnouncement.ts`).
  const { say, region: announcement } = useAnnouncer('analysis-announcement');
  const querying = state?.query.status === 'loading';
  // Without a dimension the answer is one row that is the whole range: no
  // groups to count, so neither the footer nor the announcement counts
  // any — 「正在显示 1 组」 was a count of something that was never grouped.
  const whole = result.ran?.groups.length === 0;
  const sentence = querying
    ? messages.label('label.status.querying')
    : view
      ? whole
        ? messages.label('label.analysis.answered')
        : messages.label('label.status.groups', { count: view.rows.length })
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

  // The first answer on its way: a question was sent and nothing is on
  // screen yet. A refresh with rows on screen is not this — those rows stay
  // until the new ones replace them; a skeleton over them would throw away
  // what the reader was reading for a moment of grey.
  const firstLoad = querying && !view;

  // What the empty result offers, and the press that takes it: the record
  // view's rule and press (`wayOutOf`), under the analysis's own sentences.
  // Asking something else opens the tray, which is where the range is.
  const empty = wayOutOf({
    state,
    hasConditions: filter.applied.length > 0,
    filter,
    runtime,
    openEditor: () => setFold({ id: runtimeId, open: true }),
  });

  // Whether every finding the strip shows is about the chart.
  const chartOnly =
    filter.unmarked.length > 0 &&
    filter.unmarked.every(found => found.path[0] === 'chart');

  // The result's own sentences about the groups below its last row, drawn
  // over the rows (below) and left out of the status line (`besideResult`).
  const cutShort = resultIssues(state?.result?.data).filter(found =>
    CUT_SHORT_CODES.includes(found.code),
  );

  if (!runtime) return children(NO_PARTS);
  return children({
    besideResult: CUT_SHORT_CODES,
    nameIssue,
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
    // The way out of a config that will not run: wherever the finding is
    // about (F11). A chart's are the visualization panel's — its options,
    // where the stages and slots are set — and the tray holds none of them:
    // sending a funnel short of stages to 「打开分析」 opened the one place
    // that could not fix it (the 2026-09-23 audit). With the panel switched
    // off by the host there is no way to it, so no button.
    errorAction: chartOnly
      ? shown.visualization && (
          <Button
            variant="outline"
            size="xs"
            onClick={() => setPanel(question ? 'options' : 'picker')}
          >
            {messages.label('label.analysis.open-chart-options')}
          </Button>
        )
      : // Only while the tray is folded away: it is the tray this opens, and
        // over an open tray 「打开分析」 pointed at the very thing under it —
        // the finding is marked there already (2026-09-23 audit).
        (editorOpen: boolean) =>
          !editorOpen && (
            <Button
              variant="outline"
              size="xs"
              onClick={() => setFold({ id: runtimeId, open: true })}
            >
              {messages.label('label.analysis.open-editor')}
            </Button>
          ),
    // From the moment a question is sent, whatever comes of it: the reading
    // is the question's (`useAnalysisResult().columns`), and the switches
    // beside it redraw and never run, so they work while the first answer
    // is on its way, after it failed and over a range that matched no
    // group. It used to wait for rows — which put it on screen as they
    // landed and pushed the result down, and took 表格／图表 and 可视化 away
    // from exactly the reader who had nothing else to act on.
    toolbar: question && (
      <AnalysisToolbar
        analysis={analysis}
        columns={result.columns}
        visualizing={level !== null}
        visualizeRef={focus.visualizeRef}
        {...(shown.visualization
          ? {
              onVisualize: (open: boolean) => setPanel(open ? 'picker' : null),
            }
          : {})}
      />
    ),
    panel: visualizationPanel({
      level,
      focus,
      result,
      totals: analysis.totals,
      onTotals: on => {
        analysis.setTotals(on);
        analysis.submit();
      },
      onChange: next => analysis.updateChart(next),
      onLevel: setPanel,
      onBack: () => setPanel(null),
      ...(visualizationBack === undefined
        ? {}
        : { backLabel: visualizationBack }),
    }),
    // Dismissed as a drawer on a narrow screen: the panel's own way back.
    onPanelClose: () => setPanel(null),
    result: question && (
      <>
        <div
          data-slot="analysis-result"
          // While the draft is about to run on its own (改了就跑), and for as
          // long as the answer to a new question is on its way, the rows on
          // screen answer the last question: kept, faded rather than
          // cleared, because a blank in between reads as a failure — and a
          // slow query has to look like one still working (2026-09-23 audit).
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
          {/* Where the rows are cut short, the sentence that says so, just
              above them: it is about these rows, and in the status line it
              sat over the title bar's edge of the view, a screen away from
              the table it described (2026-09-23 audit). A ranking — sorted by
              a metric and cut at N on purpose — says nothing
              (`cutShortIssues`). */}
          {view && view.rows.length > 0 && cutShort.length > 0 && (
            <div data-slot="analysis-cut-short" className="pb-2">
              <WarningStrip issues={cutShort} />
              <NoteStrip issues={cutShort} />
            </div>
          )}
          {!view ? (
            // Before the first answer, its shape; after a first query that
            // failed, nothing — the failure is the query strip's to say, under
            // the toolbar and with its retry, exactly as it is for the record
            // view, whose rows draw nothing in that state either.
            firstLoad && (
              <AnalysisSkeleton layout={layout} columns={result.tableColumns} />
            )
          ) : view.rows.length === 0 ? (
            <AnalysisEmpty wayOut={empty.wayOut} onAction={empty.take} />
          ) : layout === 'chart' && chartData ? (
            <AnalysisChart
              data={chartData}
              spec={chart}
              columns={view.schema ?? view.columns}
              onPick={onPick}
              cutShort={view.truncated || view.atLimit !== undefined}
              menuOpen={followUp !== null && pick !== null}
            />
          ) : (
            <AnalysisTable
              view={view}
              onPick={onPick}
              {...(sortable ? { sorting: headerSort } : {})}
            />
          )}
          {result.pickable && followUps && (
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
        {view ? (
          <AnalysisCaption
            rows={whole ? null : view.rows.length}
            elapsedMs={state?.result?.elapsedMs ?? null}
          />
        ) : (
          // In its place and at its height while the first answer is on its
          // way, so the rows landing change what is in the frame and not
          // where anything is. A first query that failed has nothing to
          // count, and says nothing here — as the record view's pager does.
          firstLoad && <CaptionSkeleton />
        )}
      </>
    ),
  });
}

/** What the analysis result hands the frame to dress (`ResultBlock.slots`). */
const RESULT_SLOTS = resultSlots('caption');

/** No sort has run yet: the order before any result. */
const NO_SORT: readonly AnalysisSort[] = [];

/** The smallest time the footer says; anything under it is 「<0.01 秒」. */
const MIN_SHOWN_SECONDS = 0.01;

/**
 * The analysis result's footer: 「正在显示 12 组，耗时 0.38 秒」 — or, with no
 * dimension, only 「耗时 0.38 秒」. An ungrouped answer is one row that is
 * every record in the range; 「1 组」 counted a grouping nobody asked for,
 * and 「1 行」 would count what the reader can see at a glance. What is left
 * worth saying is how long it took.
 *
 * The time reads as a person reads a stopwatch: two decimals under a
 * second, where the difference between 0.04 and 0.38 is the difference a
 * reader notices, and one above it.
 */
function AnalysisCaption({
  rows,
  elapsedMs,
}: {
  /** The groups on screen; `null` when nothing was grouped. */
  rows: number | null;
  elapsedMs: number | null;
}) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const seconds = (elapsedMs ?? 0) / 1000;
  // Under a hundredth it rounds to 「0 秒」, which reads as nothing having
  // been asked (the 2026-09-23 audit, P2-9): it is said as below the
  // smallest step the footer shows.
  const time =
    seconds < MIN_SHOWN_SECONDS
      ? `<${formatNumber(MIN_SHOWN_SECONDS, undefined, locale)}`
      : formatNumber(
          seconds,
          { maximumFractionDigits: seconds < 1 ? 2 : 1 },
          locale,
        );
  return (
    <div
      data-slot="analysis-caption"
      // On the right, where the record view's pager is: the left of the
      // row is where a reader starts the report, and this is its footnote
      // (the user's 2026-09-23 review).
      className="text-muted-foreground text-right text-sm tabular-nums"
    >
      {rows === null
        ? messages.label('label.analysis.caption-whole', { seconds: time })
        : messages.label(
            rows === 1
              ? 'label.analysis.caption-one'
              : 'label.analysis.caption',
            {
              count: formatNumber(rows, undefined, locale),
              seconds: time,
            },
          )}
    </div>
  );
}
