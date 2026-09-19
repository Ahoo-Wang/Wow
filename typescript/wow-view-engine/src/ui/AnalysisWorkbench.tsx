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

import { useState } from 'react';
import type { AnalysisView } from '../analysis/index.js';
import type { FieldOption } from '../model/index.js';
import type { ViewEngine } from '../runtime/index.js';
import {
  kindMismatch,
  useAnalysisEditor,
  useFilterEditor,
  useOpenView,
  useSaveCommands,
  useViewList,
  useViewManager,
  useViewRuntime,
} from '../react/index.js';
import { AnalysisChart } from './AnalysisChart.js';
import { AnalysisEditor } from './AnalysisEditor.js';
import { AnalysisTable } from './AnalysisTable.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Separator } from './components/separator.js';
import { Skeleton } from './components/skeleton.js';
import { AppliedBar } from './AppliedBar.js';
import { FilterPanel } from './FilterPanel.js';
import { useLeaveGuard } from './LeaveGuard.js';
import {
  ErrorStrip,
  QueryStrip,
  unmarkedErrors,
  WarningStrip,
} from './StatusStrip.js';
import { ViewHeader } from './ViewHeader.js';
import { ViewList } from './ViewList.js';
import { useReleaseDeleted } from './useReleaseDeleted.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { ViewSurface } from './ViewSurface.js';

export interface AnalysisWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  instanceId?: string | null;
  theme?: 'light' | 'dark';
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  optionsFor?(remote: string): FieldOption[] | undefined;
}

/**
 * The default Analysis workbench: the view list, the conditions, what to
 * aggregate, and the result as a table or a chart.
 *
 * Which of the two is showing is part of the saved config, and both keep
 * their own settings, so switching back and forth loses nothing.
 */
export function AnalysisWorkbench({
  engine,
  definitionId,
  instanceId = null,
  theme,
  messages: wording,
  locale,
  optionsFor,
}: AnalysisWorkbenchProps) {
  // Only the analysis views: the sidebar offers what this page can open, and
  // the effective default is resolved among those alone.
  const list = useViewList(engine, definitionId, { kind: 'analysis' });
  // One boolean governs the sidebar, so collapsing it later is a change in
  // one place rather than in the layout of every part beside it.
  const [sidebarOpen] = useState(true);
  const [chosen, setChosen] = useState<string | null>(instanceId);
  const openId = chosen ?? list.defaultInstanceId;

  const opened = useOpenView(engine, openId);
  // A host may still name a view of another kind. It opened, and it is not
  // this page's to draw, so it is reported the way every unopenable view is
  // rather than left as a header over nothing.
  const wrongKind = kindMismatch(opened.runtime, 'analysis');
  const runtime = wrongKind ? null : opened.runtime;
  const unopenable = opened.error ?? wrongKind;
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const filter = useFilterEditor(runtime);
  const commands = useSaveCommands(engine, runtime);
  const manager = useViewManager(engine, definitionId, list);
  const messages = useViewMessages(wording);
  const leave = useLeaveGuard(
    state ? { dirty: state.dirty, write: state.write } : null,
    // The dialog is rendered out here, outside the surface that carries the
    // wording, so it is handed the wording directly; and leaving settles the
    // outcome first, because the runtime it belongs to is about to go.
    { messages: wording, onLeave: () => commands.abandon() },
  );
  useReleaseDeleted(openId, chosen, opened, setChosen);

  const data = state?.result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;
  // The chart the result was shaped by, not the draft being edited: until
  // Run, the draft's aliases may name other columns than the ones the
  // result's categories came from, and a category is named through its column.
  const shaped = state?.result?.config;
  const chart = shaped?.kind === 'analysis' ? shaped.chart : analysis.chart;
  const issues = state?.issues ?? [];

  return (
    <ViewSurface
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      className="gap-0 md:flex-row"
    >
      {sidebarOpen && (
        <>
          <aside
            data-slot="view-sidebar"
            className="flex w-56 shrink-0 flex-col gap-2 p-3"
          >
            <ViewList
              list={list}
              title={engine.definitions.get(definitionId)?.title}
              currentId={state?.saved?.id ?? null}
              // Opening another view releases this one's runtime and the draft
              // goes with it, so the switch is asked about before it happens.
              onOpen={id => leave.request(() => setChosen(id))}
              // Only when something on the list can actually be managed: a
              // reader with no write permission at all would otherwise get a
              // button whose only lesson is that it leads to a dialog of
              // read-only rows.
              manager={manager.can.anything ? manager : undefined}
              openDirtyId={state?.dirty ? (state.saved?.id ?? null) : null}
            />
          </aside>

          <Separator orientation="vertical" className="hidden md:block" />
        </>
      )}

      <main className="flex min-w-0 flex-1 flex-col gap-3 p-3">
        {unopenable && (
          <Alert variant="destructive">
            <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
            <AlertDescription>{messages.issue(unopenable)}</AlertDescription>
          </Alert>
        )}

        {opened.loading && <Skeleton className="h-8 w-full" />}

        {runtime && (
          <>
            <ViewHeader
              state={state}
              kind="analysis"
              commands={commands}
              onSaved={saved => {
                setChosen(saved.id);
                list.reload();
              }}
              onRenamed={instance => {
                // Pin the view before the reload: a workbench riding on the
                // default would otherwise close its runtime and lose the draft.
                setChosen(instance.id);
                list.reload();
              }}
              onDeleted={() => {
                // The engine let the runtime go with the instance. Reload so
                // the list drops it and the default moves on; the open id
                // follows the new default, or empties with the list.
                setChosen(null);
                list.reload();
              }}
              onRecovered={() => list.reload()}
            />

            {/* Not frozen while a query runs: editing never re-queries, and
                a refresh that lands mid-edit must not take the inputs away. */}
            <FilterPanel filter={filter} optionsFor={optionsFor} />
            <AnalysisEditor analysis={analysis} />

            <ErrorStrip issues={unmarkedErrors(issues, filter.tree)} />
            {/* Warnings block nothing — the result below is real — so they
                sit under the errors and never replace it. */}
            <WarningStrip issues={issues} />
            <QueryStrip
              error={state?.query.status === 'error' ? state.query.error : null}
              stale={state?.result != null}
              onRetry={() => runtime.refresh()}
            />

            <AppliedBar filter={filter} hasResult={state?.result != null} />

            {view &&
              (analysis.layout === 'chart' && view.chart ? (
                <AnalysisChart
                  data={view.chart}
                  spec={chart}
                  columns={view.schema ?? view.columns}
                />
              ) : (
                <AnalysisTable view={view} />
              ))}
          </>
        )}
      </main>
      {leave.dialog}
    </ViewSurface>
  );
}
