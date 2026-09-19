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

import type { FilterTree } from '../model/index.js';
import type { DashboardRuntime, ViewEngine } from '../runtime/index.js';
import {
  useAnalysisEditor,
  useDashboard,
  useOpenView,
  useRecordTable,
  useViewRuntime,
} from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Skeleton } from './components/skeleton.js';
import { AnalysisChart } from './AnalysisChart.js';
import { AnalysisTable } from './AnalysisTable.js';
import { DashboardGrid } from './DashboardGrid.js';
import { RecordCards } from './RecordCards.js';
import { RecordTable } from './RecordTable.js';
import { useViewMessages } from './MessagesProvider.js';
import { ViewSurface } from './ViewSurface.js';
import { WarningNotice } from './WarningNotice.js';

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
  className?: string;
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
  className,
}: EmbeddedViewProps) {
  // The condition goes in with the config, not after it: `useOpenView` hands
  // it to `engine.open`, so the opening query is already scoped and an
  // inadmissible condition is reported instead of being quietly dropped.
  const opened = useOpenView(engine, instanceId, scopeFilter);
  const messages = useViewMessages();
  const runtime = opened.runtime;

  return (
    <ViewSurface theme={theme} className={className}>
      {opened.error && (
        <Alert variant="destructive">
          <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
          <AlertDescription>{messages.issue(opened.error)}</AlertDescription>
        </Alert>
      )}
      {/*
        A refused narrowing leaves the wider result running, which is the one
        outcome this must never show in silence: the page asked for one
        customer's shipments and would otherwise quietly list everyone's.
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
      {runtime && <EmbeddedBody runtime={runtime} />}
    </ViewSurface>
  );
}

type OpenedRuntime = NonNullable<ReturnType<typeof useOpenView>['runtime']>;

/**
 * Dispatch by kind. It is a component rather than a branch inside the one
 * above because each kind's controller is a hook, and a hook cannot be called
 * conditionally.
 */
function EmbeddedBody({ runtime }: { runtime: OpenedRuntime }) {
  const state = useViewRuntime(runtime);
  const messages = useViewMessages();

  // A config the definition no longer admits opens but never executes, so
  // without this a record sits at an empty frame and an analysis at a
  // skeleton that never resolves: a view waiting to be fixed, dressed up as
  // one with nothing to show. The workbenches say so; so does this.
  const errors = (state?.issues ?? []).filter(
    found => found.severity === 'error',
  );
  // A warning blocks nothing, so the result still shows, with the warning
  // above it: an embed hides the editor, and this is the one place a reader
  // learns the view is not quite what its author saved. A dashboard's
  // panel-scoped findings are the panels' to show, each in its own frame.
  const warnings = (state?.issues ?? []).filter(
    found => runtime.kind !== 'dashboard' || found.path[0] !== 'panels',
  );
  // An error takes the result's place; it does not take the warnings' — a
  // config can carry both, and the workbench says both.
  if (errors.length > 0)
    return (
      <>
        <Alert variant="destructive">
          <AlertTitle>{messages.label('label.view.needs-fixing')}</AlertTitle>
          <AlertDescription>{messages.issues(errors)}</AlertDescription>
        </Alert>
        <WarningNotice issues={warnings} />
      </>
    );

  return (
    <>
      <WarningNotice issues={warnings} />
      {runtime.kind === 'record' ? (
        <EmbeddedRecord runtime={runtime} />
      ) : runtime.kind === 'analysis' ? (
        <EmbeddedAnalysis runtime={runtime} />
      ) : (
        <EmbeddedDashboard runtime={runtime} />
      )}
    </>
  );
}

function Failed({ runtime }: { runtime: OpenedRuntime }) {
  const state = useViewRuntime(runtime);
  const messages = useViewMessages();
  const error = state?.query.error;
  if (state?.query.status !== 'error' || !error) return null;
  return (
    <Alert variant="destructive">
      <AlertTitle>{messages.label('label.query.failed')}</AlertTitle>
      <AlertDescription>{messages.issue(error)}</AlertDescription>
    </Alert>
  );
}

function EmbeddedRecord({
  runtime,
}: {
  runtime: Extract<OpenedRuntime, { kind: 'record' }>;
}) {
  const table = useRecordTable(runtime);

  if (table.status === 'error') return <Failed runtime={runtime} />;
  if (table.loading && table.rows.length === 0)
    return <Skeleton className="h-24 w-full" />;
  return table.layout === 'card' ? (
    <RecordCards table={table} />
  ) : (
    <RecordTable table={table} />
  );
}

function EmbeddedAnalysis({ runtime }: { runtime: OpenedRuntime }) {
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const data = state?.result?.data;
  const view = data?.kind === 'analysis' ? data.view : null;

  if (state?.query.status === 'error') return <Failed runtime={runtime} />;
  if (!view) return <Skeleton className="h-24 w-full" />;
  return view.chart ? (
    <AnalysisChart data={view.chart} spec={analysis.chart} />
  ) : (
    <AnalysisTable view={view} />
  );
}

function EmbeddedDashboard({ runtime }: { runtime: DashboardRuntime }) {
  const dashboard = useDashboard(runtime);
  return <DashboardGrid dashboard={dashboard} />;
}
