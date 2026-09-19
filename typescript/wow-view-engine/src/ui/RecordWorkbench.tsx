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

import { useState, type ReactNode } from 'react';
import type { FieldOption } from '../model/index.js';
import type { RecordRow } from '../record/index.js';
import type { RecordViewRuntime, ViewEngine } from '../runtime/index.js';
import {
  kindMismatch,
  useFilterEditor,
  useOpenView,
  useRecordTable,
  useSaveCommands,
  useViewList,
  useViewManager,
  useViewRuntime,
  type FilterEditorController,
  type RecordActionSlots,
} from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Separator } from './components/separator.js';
import { Skeleton } from './components/skeleton.js';
import { AppliedBar } from './AppliedBar.js';
import { EditorBand } from './EditorBand.js';
import { FilterPanel } from './FilterPanel.js';
import { useLeaveGuard } from './LeaveGuard.js';
import { RecordCards } from './RecordCards.js';
import { RecordPagination } from './RecordPagination.js';
import { RecordTable } from './RecordTable.js';
import { ResultToolbar } from './ResultToolbar.js';
import { RowActions } from './RowActions.js';
import {
  ErrorStrip,
  QueryStrip,
  unmarkedErrors,
  WarningStrip,
} from './StatusStrip.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { ViewHeader } from './ViewHeader.js';
import { ViewSurface } from './ViewSurface.js';
import { ViewList } from './ViewList.js';
import { useReleaseDeleted } from './useReleaseDeleted.js';

export interface RecordWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /** Opens this view first; the user's effective default when left out. */
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
  /**
   * The host's own business actions: one over the view, one over a selection,
   * one per row. They are render functions rather than names in a config —
   * what may be *done* to a record belongs to the application that mounted
   * the workbench, not to the way of looking somebody saved.
   */
  actions?: RecordActionSlots;
}

/**
 * The default Record workbench: the view list, the conditions, the result and
 * the save commands.
 *
 * The result is the point of it, so the page is ordered by how close each
 * part stands to the rows: which view this is, the editor folded out of the
 * way, anything the view has to say in a line, what the rows on screen were
 * fetched under, then the rows and their paging.
 *
 * It is one composition of the controllers in `/react`, not a privileged one.
 * An application that wants different markup builds its own from the same
 * hooks and loses nothing.
 */
export function RecordWorkbench({
  engine,
  definitionId,
  instanceId = null,
  theme,
  messages: wording,
  locale,
  optionsFor,
  actions,
}: RecordWorkbenchProps) {
  // Only the record views: the sidebar offers what this page can open, and
  // the effective default is resolved among those alone.
  const list = useViewList(engine, definitionId, { kind: 'record' });
  // One boolean governs the sidebar, so collapsing it later is a change in
  // one place rather than in the layout of every part beside it.
  const [sidebarOpen] = useState(true);
  const [chosen, setChosen] = useState<string | null>(instanceId);
  const openId = chosen ?? list.defaultInstanceId;

  const opened = useOpenView(engine, openId);
  // A host may still name a view of another kind. It opened, and it is not
  // this page's to draw, so it is reported the way every unopenable view is
  // rather than left as a header over nothing.
  const wrongKind = kindMismatch(opened.runtime, 'record');
  const runtime = wrongKind ? null : opened.runtime;
  const unopenable = opened.error ?? wrongKind;
  const state = useViewRuntime(runtime);
  const record = runtime?.kind === 'record' ? runtime : null;
  const table = useRecordTable(record);
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

  const fields = runtime?.fields ?? [];
  const issues = state?.issues ?? [];
  const hasResult = state?.result != null;
  const row = actions?.row;

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

      <main className="flex min-w-0 flex-1 flex-col gap-2 p-3">
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
              kind="record"
              commands={commands}
              actions={
                record &&
                actions?.global?.({ runtime: record, refresh: table.refresh })
              }
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

            {/* Not frozen while a query runs: typing never re-queries, and
                a refresh that lands mid-edit must not take the input away. */}
            <ConditionBand
              key={runtime.id}
              startOpen={state?.saved === null}
              filter={filter}
            >
              <FilterPanel filter={filter} optionsFor={optionsFor} />
            </ConditionBand>

            <ErrorStrip issues={unmarkedErrors(issues, filter.tree)} />
            {/* Warnings block nothing — the rows below are the real ones —
                so they sit under the errors and never replace the result. */}
            <WarningStrip issues={issues} />
            <QueryStrip
              error={table.error}
              stale={hasResult}
              onRetry={table.refresh}
            />

            <AppliedBar filter={filter} hasResult={hasResult} />

            {record && (
              <>
                <ResultToolbar
                  table={table}
                  fields={fields}
                  fieldGroups={record.definition.fieldGroups}
                  bulkActions={actions?.bulk}
                  runtime={record}
                />

                {table.layout === 'card' ? (
                  <RecordCards
                    table={table}
                    rowActions={bindRow(row, record, table.refresh)}
                  />
                ) : (
                  <RecordTable
                    table={table}
                    rowActions={bindRow(row, record, table.refresh)}
                  />
                )}

                <RecordPagination table={table} />
              </>
            )}
          </>
        )}
      </main>
      {leave.dialog}
    </ViewSurface>
  );
}

/**
 * The host's row slot bound to the open view, or nothing at all.
 *
 * The table is handed a function of the row alone — it knows a result, not a
 * runtime — so the binding happens here, where both are in hand, and the
 * host's buttons get their wrapper once rather than from every host.
 */
function bindRow(
  row: RecordActionSlots['row'],
  runtime: RecordViewRuntime,
  refresh: () => void,
): ((row: RecordRow) => ReactNode) | undefined {
  return row
    ? item => <RowActions>{row({ row: item, runtime, refresh })}</RowActions>
    : undefined;
}

/**
 * The fold the conditions live in, opened by what the view is rather than by
 * what the user last did to another one.
 *
 * It is its own component so a `key` can reset it: the open state belongs to
 * one opening of one view, and switching views has to start it over. A saved
 * view opens folded — its author already decided, and the rows are the point
 * — while a view that was never saved opens out, because there is nothing to
 * look at until it has been told what to ask for.
 */
function ConditionBand({
  startOpen,
  filter,
  children,
}: {
  startOpen: boolean;
  filter: FilterEditorController;
  children: ReactNode;
}) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(startOpen);
  return (
    <EditorBand
      open={open}
      onOpenChange={setOpen}
      label={messages.label('label.filter.panel')}
      pending={filter.pendingCount}
    >
      {children}
    </EditorBand>
  );
}
