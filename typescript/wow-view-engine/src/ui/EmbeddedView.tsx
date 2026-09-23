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

import type { Ref, ReactNode } from 'react';
import type { FilterTree } from '../model/index.js';
import type { RecordRow } from '../record/index.js';
import {
  hasAsked,
  hasResult,
  resultIssues,
  type DashboardRuntime,
  type ViewEngine,
} from '../runtime/index.js';
import {
  useAnalysisEditor,
  useDashboard,
  useFilterEditor,
  useOpenView,
  useRecordTable,
  useViewRuntime,
} from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Skeleton } from './components/skeleton.js';
import { AnalysisChart } from './AnalysisChart.js';
import { AnalysisTable } from './AnalysisTable.js';
import { AppliedBar } from './AppliedBar.js';
import { chartIssueNamer } from './analysis/issueNames.js';
import { DashboardGrid, type PanelHeadingLevel } from './DashboardGrid.js';
import { RecordCards } from './RecordCards.js';
import { RecordTable } from './RecordTable.js';
import { ErrorStrip, QueryStrip, WarningStrip } from './StatusStrip.js';
import { useViewMessages } from './MessagesProvider.js';
import { RenderBoundary, type RenderFailureHandler } from './RenderBoundary.js';
import type { ViewMessages } from './messages.js';
import { ViewSurface } from './ViewSurface.js';

export interface EmbeddedViewProps {
  engine: ViewEngine;
  /** The saved view to show; a code-declared system view works too. */
  instanceId: string;
  /**
   * An outer condition ANDed onto the view's own, in the view's field names.
   * It is admitted like a user's own filter, so a host cannot widen a view
   * past what its definition allows, and it never reaches the saved config.
   */
  scopeFilter?: FilterTree | null;
  /** Follows the host page when left out. */
  theme?: 'light' | 'dark';
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  className?: string;
  /**
   * The surface this draws on, handed back.
   *
   * An embed is the result and nothing else — no title bar, no toolbar, no
   * save — so it grows no control of its own for filling the screen: a
   * button floating over somebody's order page is chrome that page did not
   * ask for and cannot place. What it owes a host that wants one is the
   * means, which is this: put the control where your own chrome is, and
   * point `useViewExpansion` at the element you get here.
   */
  ref?: Ref<HTMLDivElement>;
  /**
   * What the host offers on one row of a record view. An embed has no
   * toolbar and no save, but the rows it shows are still records somebody
   * may want to act on; the slot takes the row alone, because there is no
   * view to command here.
   */
  rowActions?(row: RecordRow): ReactNode;
  /**
   * Told when the embed fails to draw — a row action of the host's that
   * throws, for one. The embed shows a recoverable error state in place.
   */
  onRenderFailure?: RenderFailureHandler;
  /**
   * The heading level of a dashboard's panel titles. An embed has no title
   * of its own, so its panels sit one under whatever the host's page calls
   * the section it put them in — `2`, under a page's `h1`, unless the host
   * says otherwise. Only the host knows its outline.
   */
  headingLevel?: PanelHeadingLevel;
  /**
   * The host's route to the workbench, for 在工作台中打开 in a dashboard
   * panel's menu: the saved view the panel shows, and the board's condition
   * as the panel carries it, in that view's own field names. Without it the
   * item does not exist.
   */
  onOpenView?(instanceId: string, filter: FilterTree | null): void;
}

/**
 * One saved view inside a business page: the result, and nothing else.
 *
 * The workbench exists to let a user *change* how they observe; this exists to
 * let a page *show* what someone already decided. So there is no view list, no
 * condition editor and no save action — an order detail page embedding "recent
 * shipments for this customer" wants the rows, not a second application.
 *
 * Everything it drops is chrome. Admission, paging, auto-refresh and the
 * request budget are the runtime's, identical to the workbench's, because both
 * are compositions over the same controllers.
 */
export function EmbeddedView({
  engine,
  instanceId,
  scopeFilter = null,
  theme,
  messages: wording,
  locale,
  className,
  ref,
  rowActions,
  onRenderFailure,
  headingLevel = 2,
  onOpenView,
}: EmbeddedViewProps) {
  // The condition goes in with the config, not after it: `useOpenView` hands
  // it to `engine.open`, so the opening query is already scoped rather than
  // going out wide and being narrowed a moment later. One the definition
  // refuses is reported as a refusal instead of being quietly dropped — and
  // instead of being read as a defect of the view, which is what it used to
  // be when it entered the first admission as part of the config.
  const opened = useOpenView(engine, instanceId, scopeFilter);
  const messages = useViewMessages(wording, locale);
  const runtime = opened.runtime;

  return (
    <ViewSurface
      ref={ref}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      className={className}
    >
      {opened.error && (
        <Alert variant="destructive">
          <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
          <AlertDescription>{messages.issue(opened.error)}</AlertDescription>
        </Alert>
      )}
      {/*
        A refused narrowing leaves the wider result running, which is the one
        outcome this must never show in silence: the page asked for one
        customer's shipments and would otherwise quietly list everyone's. It
        reads the same on the first open as on any later one — what was
        refused is the page's own condition, and the page is who can change
        it; the view below is whatever its author saved, and still worth
        showing (D17-5).
      */}
      {opened.scopeIssues.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>{messages.label('label.scope.refused')}</AlertTitle>
          <AlertDescription>
            {messages.issues(opened.scopeIssues)}
          </AlertDescription>
        </Alert>
      )}
      {opened.loading && <Skeleton className="h-24 w-full" />}
      {runtime && (
        <RenderBoundary
          name="result"
          resetKeys={[runtime.id]}
          onFailure={onRenderFailure}
        >
          <EmbeddedBody
            runtime={runtime}
            rowActions={rowActions}
            headingLevel={headingLevel}
            onOpenView={onOpenView}
          />
        </RenderBoundary>
      )}
    </ViewSurface>
  );
}

type OpenedRuntime = NonNullable<ReturnType<typeof useOpenView>['runtime']>;

/**
 * Dispatch by kind. It is a component rather than a branch inside the one
 * above because each kind's controller is a hook, and a hook cannot be called
 * conditionally.
 */
function EmbeddedBody({
  runtime,
  rowActions,
  headingLevel,
  onOpenView,
}: {
  runtime: OpenedRuntime;
  rowActions?(row: RecordRow): ReactNode;
  headingLevel: PanelHeadingLevel;
  onOpenView?(instanceId: string, filter: FilterTree | null): void;
}) {
  const state = useViewRuntime(runtime);
  const messages = useViewMessages();
  // A chart finding names its dimensions and metrics as their columns are
  // headed (`chartIssueNamer`); over a view of another kind the editor is
  // empty and names nothing.
  const nameIssue = chartIssueNamer(useAnalysisEditor(runtime), messages);

  // A config the definition no longer admits opens but never executes, so
  // without this a record sits at an empty frame and an analysis at a
  // skeleton that never resolves: a view waiting to be fixed, dressed up as
  // one with nothing to show. The workbenches say so; so does this.
  // A dashboard's panel-scoped findings are the panels' to show, each in its
  // own frame — its errors as much as its warnings. One panel's error stops
  // that panel and nothing else (the runtime runs the rest), so taking it
  // for the dashboard's own drew no grid at all around a board where every
  // other panel was fine (R3). The workbench has always drawn the grid.
  // "Too many panels" sits at `['panels']` itself and is no one panel's:
  // it stops the whole board, and stays the dashboard's to say.
  const issues = (state?.issues ?? [])
    .filter(
      found =>
        runtime.kind !== 'dashboard' ||
        found.path[0] !== 'panels' ||
        typeof found.path[1] !== 'number',
    )
    .map(nameIssue);
  const errors = issues.filter(found => found.severity === 'error');
  // A warning blocks nothing, so the result still shows, with the warning
  // above it: an embed hides the editor, and this is the one place a reader
  // learns the view is not quite what its author saved. What the result
  // says about itself is said here too, and for a stronger reason than in a
  // workbench: there is no editor, no toolbar and no scope bar, so a page
  // total wearing the word "total" or a pie drawn from a truncated grouping
  // would have nothing at all to correct it.
  const warnings = [...issues, ...resultIssues(state?.result?.data)];
  // An error takes the result's place; it does not take the warnings' — a
  // config can carry both, and the workbench says both. There is no
  // condition editor here, so no finding is marked anywhere else.
  if (errors.length > 0)
    return (
      <>
        <ErrorStrip issues={errors} />
        <WarningStrip issues={warnings} />
      </>
    );

  return (
    <>
      <WarningStrip issues={warnings} />
      {runtime.kind === 'record' ? (
        <EmbeddedRecord runtime={runtime} rowActions={rowActions} />
      ) : runtime.kind === 'analysis' ? (
        <EmbeddedAnalysis runtime={runtime} />
      ) : (
        <EmbeddedDashboard
          runtime={runtime}
          headingLevel={headingLevel}
          onOpenView={onOpenView}
        />
      )}
    </>
  );
}

/**
 * What the rows were fetched under. An embed shows it for the same reason it
 * shows warnings: nothing else here says what the view is asking, and a
 * scope the host narrowed it by is part of that question.
 *
 * Read-only, unlike the workbench's: there is no editor here, and the view's
 * own conditions are what its author saved. A ✕ would let a reader drop a
 * saved condition — on a page that embedded this view to show one customer's
 * shipments, that is the page quietly listing everyone's.
 */
function Applied({ runtime }: { runtime: OpenedRuntime }) {
  const state = useViewRuntime(runtime);
  const filter = useFilterEditor(runtime);
  return <AppliedBar filter={filter} asked={hasAsked(state)} readOnly />;
}

function Failed({ runtime }: { runtime: OpenedRuntime }) {
  const state = useViewRuntime(runtime);
  // No toolbar, so no retry: the embed re-runs on its own schedule, and a
  // button that is the page's only control would make it look like one.
  return (
    <QueryStrip
      error={state?.query.status === 'error' ? state.query.error : null}
      stale={hasResult(state)}
    />
  );
}

function EmbeddedRecord({
  runtime,
  rowActions,
}: {
  runtime: Extract<OpenedRuntime, { kind: 'record' }>;
  rowActions?(row: RecordRow): ReactNode;
}) {
  const table = useRecordTable(runtime);

  // A refresh that failed over rows that are still good says so *above* them
  // rather than instead of them, the way the workbenches do: the strip itself
  // promises "the last successful result", and taking the table away would
  // make that line describe an empty frame. Only a failure with nothing
  // behind it replaces the content.
  if (table.status === 'error' && !table.hasResult)
    return <Failed runtime={runtime} />;
  if (table.loading && table.rows.length === 0)
    return <Skeleton className="h-24 w-full" />;
  return (
    <>
      <Failed runtime={runtime} />
      <Applied runtime={runtime} />
      {table.layout === 'card' ? (
        <RecordCards table={table} rowActions={rowActions} />
      ) : (
        <RecordTable table={table} rowActions={rowActions} />
      )}
    </>
  );
}

function EmbeddedAnalysis({ runtime }: { runtime: OpenedRuntime }) {
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const data = state?.result?.data;
  const view = data?.kind === 'analysis' ? data.view : null;

  // As in the record embed: the chart stays while the strip reports the
  // refresh that failed over it.
  if (state?.query.status === 'error' && !view)
    return <Failed runtime={runtime} />;
  if (!view) return <Skeleton className="h-24 w-full" />;
  return (
    <>
      <Failed runtime={runtime} />
      <Applied runtime={runtime} />
      {view.chart ? (
        <AnalysisChart
          data={view.chart}
          spec={analysis.chart}
          columns={view.schema ?? view.columns}
          cutShort={view.truncated || view.atLimit !== undefined}
        />
      ) : (
        <AnalysisTable view={view} />
      )}
    </>
  );
}

function EmbeddedDashboard({
  runtime,
  headingLevel,
  onOpenView,
}: {
  runtime: DashboardRuntime;
  headingLevel: PanelHeadingLevel;
  onOpenView?(instanceId: string, filter: FilterTree | null): void;
}) {
  const dashboard = useDashboard(runtime);
  return (
    <DashboardGrid
      dashboard={dashboard}
      headingLevel={headingLevel}
      onOpenView={onOpenView}
    />
  );
}
