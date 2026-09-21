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

import { useCallback, useMemo, useState, type ReactNode } from 'react';
import type { FieldOption, RecordData } from '../model/index.js';
import { serializeCsv, type RecordRow } from '../record/index.js';
import {
  resultIssues,
  type RecordViewRuntime,
  type ViewEngine,
} from '../runtime/index.js';
import {
  useRecordExport,
  useRecordTable,
  useWorkbench,
  type RecordActionSlots,
  type RecordExportScope,
} from '../react/index.js';
import { cellText, isoDay, type DisplayContext } from './display.js';
import { downloadFile, fileName } from './download.js';
import { FilterPanel } from './FilterPanel.js';
import { FilterModes, filterModeLabel } from './filter/FilterModes.js';
import { RecordCards } from './RecordCards.js';
import { RecordPagination } from './RecordPagination.js';
import { RecordTable } from './RecordTable.js';
import { ResultToolbar } from './ResultToolbar.js';
import { RowActions } from './RowActions.js';
import { QueryStrip } from './StatusStrip.js';
import { RefreshControl } from './RefreshControl.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { WorkbenchShell } from './WorkbenchShell.js';
import { RenderSlot, type RenderFailureHandler } from './RenderBoundary.js';

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
   * it starts and the shell owns it from there. Left out, a column narrower
   * than `md` opens folded and a wider one opens with the list beside it.
   */
  defaultSidebarOpen?: boolean;
  /** Told whenever the sidebar opens or closes, for a host that mirrors it. */
  onSidebarOpenChange?(open: boolean): void;
  /**
   * Whether this workbench offers to fill the screen. On by default, and the
   * fold itself belongs to the shell — a host only says whether the control
   * exists, because a page that is already one full-screen view of one thing
   * has nothing to gain from a second way to say so.
   */
  expandable?: boolean;
  /**
   * Told of a render failure one of the workbench's boundaries caught — the
   * host's action slots, the editor, the result, a panel. The part shows a
   * recoverable error state in place regardless; this is the host's copy.
   */
  onRenderFailure?: RenderFailureHandler;
  /**
   * The host's own business actions: one over the view, one over a selection,
   * one per row. They are render functions rather than names in a config —
   * what may be *done* to a record belongs to the application that mounted
   * the workbench, not to the way of looking somebody saved.
   */
  actions?: RecordActionSlots;
  /**
   * Told whenever an export has been handed to the browser — the file's name,
   * its contents and how many rows of which scope it holds. A host that
   * audits what leaves the application reads it; nothing here needs it, and
   * the download happens either way.
   */
  onExported?(file: ExportedFile): void;
}

/** One file an export produced, as it was handed over. */
export interface ExportedFile {
  name: string;
  text: string;
  scope: RecordExportScope;
  rows: number;
}

/** What a CSV is served as; the charset is what makes the BOM readable. */
const CSV_TYPE = 'text/csv;charset=utf-8';

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
  expandable,
  onRenderFailure,
  actions,
  onExported,
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

  // The condition fold, held here only so the empty result can open it.
  //
  // It is tagged with the runtime it was set for and handed straight back to
  // the shell, which is the shell's own rule — a fold belongs to one opening
  // of one view — so switching views falls back to the shell's default
  // exactly as it did when the shell alone held it.
  const runtimeId = runtime?.id ?? null;
  const [fold, setFold] = useState<{ id: string | null; open: boolean } | null>(
    null,
  );
  const editorOpen = fold?.id === runtimeId ? fold.open : undefined;

  // What the empty result offers. Under conditions it clears them and asks
  // again — `clear` alone would leave the rows on screen fetched under the
  // conditions the button just took away — and with none it opens the
  // editor, because the question to change is behind a fold that may not
  // even be on screen.
  const hasConditions = filter.applied.length > 0;
  const emptyAction = () => {
    if (!hasConditions) {
      setFold({ id: runtimeId, open: true });
      return;
    }
    filter.clear();
    filter.submit();
  };

  // The language and zone values read in. `useSurfaceDisplay` cannot answer
  // here — the surface is inside `WorkbenchShell`, below this component — so
  // the two halves are taken from where the shell itself takes them, and an
  // exported time is the time the cell above it showed.
  const timeZone = engine.environment.timeZone;
  const display = useMemo<DisplayContext>(
    () => ({
      ...(locale === undefined ? {} : { locale }),
      ...(timeZone === undefined ? {} : { timeZone }),
    }),
    [locale, timeZone],
  );
  const title = state?.title ?? '';
  const columns = table.columns;
  const now = engine.environment.now;
  /**
   * The rows as a file the browser takes.
   *
   * The columns are the ones the table is drawing, in the order it draws
   * them, and every value goes through the same reading the cell above it
   * does — an export that said `1789723315014` where the screen said a date
   * would be a second, quieter view of the data.
   */
  // Named before it exists, because the export window says what the file
  // will be called before there is a file (D14). `now()` is read on both
  // sides, so the name on offer is the name that is handed over.
  const exportName = fileName(title, isoDay(now(), display), 'csv');
  const deliver = useCallback(
    (rows: readonly RecordData[], scope: RecordExportScope) => {
      const text = serializeCsv(rows, columns, (value, column) =>
        cellText(value, column, messages, display),
      );
      const name = fileName(title, isoDay(now(), display), 'csv');
      downloadFile({ name, text, type: CSV_TYPE });
      onExported?.({ name, text, scope, rows: rows.length });
    },
    [columns, display, messages, now, onExported, title],
  );
  const exportControl = useRecordExport(record, table, { deliver });

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
      expandable={expandable}
      onRenderFailure={onRenderFailure}
      // What the config says, plus what this result says about itself: a
      // summary row that had to fall back to the page is a fact about the
      // numbers below, and it outlives the next keystroke because it rides
      // with them rather than with the draft's admission.
      warnings={[
        ...(state?.issues ?? []),
        ...resultIssues(state?.result?.data),
      ]}
      actions={
        // As an element rather than a call: the slot then renders inside the
        // shell's boundary for it, and a host action that throws takes the
        // slot and not the workbench.
        record &&
        actions?.global && (
          <RenderSlot
            render={() =>
              actions.global?.({ runtime: record, refresh: table.refresh })
            }
          />
        )
      }
      // Freshness is a framework control and lives in the title bar with
      // the other two (D12 Ⅰ), for every kind of view alike.
      freshness={
        <RefreshControl
          refresh={workbench.refresh}
          variant="outline"
          busy={table.loading}
        />
      }
      editorOpen={editorOpen}
      onEditorOpenChange={open => setFold({ id: runtimeId, open })}
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
      /* An export says nothing here: it has a window of its own, and that
         window is where it reports what it produced, what the ceiling cut
         short and what went wrong (D14). A cancel says nothing anywhere —
         it is the answer the user gave. */
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
              exporter={{
                control: exportControl,
                // The host's scope narrows the export exactly as it narrows
                // the rows, so the window names both kinds of condition.
                conditions: [
                  ...filter.applied,
                  ...filter.scoped,
                  ...filter.implied,
                ],
                fileName: exportName,
              }}
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
                hasConditions={hasConditions}
                onEmptyAction={emptyAction}
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
