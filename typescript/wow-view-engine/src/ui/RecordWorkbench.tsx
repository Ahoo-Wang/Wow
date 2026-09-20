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

import type { ReactNode } from 'react';
import type { FieldOption } from '../model/index.js';
import type { RecordRow } from '../record/index.js';
import {
  resultIssues,
  type RecordViewRuntime,
  type ViewEngine,
} from '../runtime/index.js';
import {
  useRecordTable,
  useWorkbench,
  type RecordActionSlots,
} from '../react/index.js';
import { FilterPanel } from './FilterPanel.js';
import { FilterModes, filterModeLabel } from './filter/FilterModes.js';
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
   * The sidebar this workbench opens on. It is view state and nothing else —
   * never saved, never asked about by the leave guard — so a host sets where
   * it starts and the shell owns it from there.
   */
  defaultSidebarOpen?: boolean;
  /** Told whenever the sidebar opens or closes, for a host that mirrors it. */
  onSidebarOpenChange?(open: boolean): void;
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
  defaultSidebarOpen,
  onSidebarOpenChange,
  actions,
}: RecordWorkbenchProps) {
  // The host's wording, resolved here rather than read off the provider:
  // `ViewSurface` is inside `WorkbenchShell`, so this component is above the
  // context and would otherwise name the editor in English on a translated
  // page.
  const messages = useViewMessages(wording);
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
      defaultSidebarOpen={defaultSidebarOpen}
      onSidebarOpenChange={onSidebarOpenChange}
      className="gap-2"
      // What the config says, plus what this result says about itself: a
      // summary row that had to fall back to the page is a fact about the
      // numbers below, and it outlives the next keystroke because it rides
      // with them rather than with the draft's admission.
      warnings={[
        ...(state?.issues ?? []),
        ...resultIssues(state?.result?.data),
      ]}
      actions={
        record && actions?.global?.({ runtime: record, refresh: table.refresh })
      }
      editorLabel={messages.label('label.filter.panel')}
      editorModeLabel={filterModeLabel(filter, messages)}
      editorModes={<FilterModes filter={filter} />}
      editorPending={filter.pendingCount}
      editor={
        /* Not frozen while a query runs: typing never re-queries, and a
           refresh that lands mid-edit must not take the input away. */
        runtime && (
          <FilterPanel filter={filter} optionsFor={optionsFor} modes={false} />
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
              rowKey={record.definition.record?.rowKey}
              // The settings show the action column only when there is one:
              // a host that hands over no row slot has no column to place.
              hasRowActions={row !== undefined}
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
