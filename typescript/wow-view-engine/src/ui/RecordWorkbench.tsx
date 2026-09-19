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
  useRecordTable,
  useWorkbench,
  type FilterEditorController,
  type RecordActionSlots,
} from '../react/index.js';
import { EditorBand } from './EditorBand.js';
import { FilterPanel } from './FilterPanel.js';
import { RecordCards } from './RecordCards.js';
import { RecordPagination } from './RecordPagination.js';
import { RecordTable } from './RecordTable.js';
import { ResultToolbar } from './ResultToolbar.js';
import { RowActions } from './RowActions.js';
import { QueryStrip } from './StatusStrip.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { WorkbenchShell } from './WorkbenchShell.js';

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
 * `useWorkbench` finds, opens and leaves the view; `WorkbenchShell` draws the
 * frame. What is left here is what makes a record view a record view — the
 * condition band it edits in, and the toolbar, rows and paging it reads.
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
  const workbench = useWorkbench(engine, definitionId, {
    kind: 'record',
    instanceId,
  });
  const { filter, runtime, state } = workbench;
  const record = runtime?.kind === 'record' ? runtime : null;
  const table = useRecordTable(record);

  const fields = runtime?.fields ?? [];
  const hasResult = state?.result != null;
  const row = actions?.row;

  return (
    <WorkbenchShell
      workbench={workbench}
      kind="record"
      title={engine.definitions.get(definitionId)?.title}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      className="gap-2"
      actions={
        record && actions?.global?.({ runtime: record, refresh: table.refresh })
      }
      editor={
        /* Not frozen while a query runs: typing never re-queries, and a
           refresh that lands mid-edit must not take the input away. */
        runtime && (
          <ConditionBand
            key={runtime.id}
            startOpen={state?.saved === null}
            filter={filter}
          >
            <FilterPanel filter={filter} optionsFor={optionsFor} />
          </ConditionBand>
        )
      }
      strips={
        <QueryStrip
          error={table.error}
          stale={hasResult}
          onRetry={table.refresh}
        />
      }
      result={
        record && (
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
        )
      }
    />
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
