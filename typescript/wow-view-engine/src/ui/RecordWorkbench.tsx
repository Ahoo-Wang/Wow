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
import type {
  FieldOption,
  RecordData,
  RecordViewConfig,
} from '../model/index.js';
import { serializeCsv, type RecordRow } from '../record/index.js';
import {
  hasResult,
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
import { useAnnouncer } from './Announcer.js';
import { Button } from './components/button.js';
import { ColumnSettings } from './ColumnSettings.js';
import { cellText, isoDay, type DisplayContext } from './display.js';
import { downloadFile, fileName } from './download.js';
import { useQueryAnnouncement } from './record/queryAnnouncement.js';
import { FilterPanel } from './FilterPanel.js';
import { FilterModes, filterModeLabel } from './filter/FilterModes.js';
import { RecordCards } from './RecordCards.js';
import { RecordPagination } from './RecordPagination.js';
import { RecordTable, type RecordCell } from './RecordTable.js';
import { NO_RELEASE, type ReleasedPins } from './record/pinCap.js';
import { ResultToolbar } from './ResultToolbar.js';
import { RowActions } from './RowActions.js';
import { QueryStrip } from './StatusStrip.js';
import { RefreshControl } from './RefreshControl.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { featuresOf, type WorkbenchFeatures } from './features.js';
import { WorkbenchShell } from './WorkbenchShell.js';
import { RenderSlot, type RenderFailureHandler } from './RenderBoundary.js';

export interface RecordWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /**
   * Which view is open, as `value` is on an input: leaving it out lets the
   * workbench own it from the effective default on, and passing it — a
   * string, or `null` for that default — puts a host's route in charge, every
   * later change opening what it names. It goes through the leave guard, so a
   * pushed view never takes an unsaved draft away without asking
   * (`WorkbenchOptions.instanceId`).
   */
  instanceId?: string | null;
  /**
   * Told which view is open whenever that changes, in the same vocabulary
   * `instanceId` is written in — `null` is the effective default — so a host
   * can put it straight into a route and get the same view back from the
   * link.
   */
  onInstanceChange?(id: string | null): void;
  /**
   * What a new view starts from, for a host with a better first view than
   * the definition's default columns: `defaultRecordConfig` when left out.
   * The view still opens unsaved, under the catalogue's "New view", and the
   * first save asks for its name and audience.
   */
  template?: RecordViewConfig;
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
   * Which of the workbench's own controls are on screen (D18 XI): export,
   * the layout switch, column settings, sort settings, the view manager.
   * All on by default; one turned off is absent, not disabled.
   */
  features?: WorkbenchFeatures;
  /**
   * What the empty result's one button does. Left out, the workbench's own
   * answer: clear the conditions in force, or open the editor when there
   * are none. A function is the host's answer instead; `null` is no button
   * — the sentence alone.
   */
  emptyAction?: (() => void) | null;
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
   * Renders one cell of the table; the default reads it as the column says.
   *
   * It is the smallest thing a host can change and keep everything else —
   * a business object with one cell nobody else could draw should not cost
   * the whole workbench. Fall back to `cellValue` for the cells it has
   * nothing special to say about, and enum labels, the surface's zone and
   * the field's number format all keep working.
   */
  renderCell?(cell: RecordCell): ReactNode;
  /**
   * Whether rows can be picked. On by default; a workbench whose host offers
   * nothing to do with a selection turns it off rather than showing a column
   * of checkboxes that lead nowhere.
   */
  selectable?: boolean;
  /**
   * The empty result in the host's own words — "no orders are waiting" says
   * more than "no rows". The way out of it is the workbench's either way:
   * conditions in force are cleared, and with none the condition editor
   * opens.
   */
  emptyTitle?: string;
  emptyDescription?: string;
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
  instanceId,
  onInstanceChange,
  theme,
  messages: wording,
  locale,
  optionsFor,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable,
  onRenderFailure,
  actions,
  renderCell,
  selectable,
  emptyTitle,
  emptyDescription,
  onExported,
  template,
  features,
  emptyAction: hostEmptyAction,
}: RecordWorkbenchProps) {
  // The host's wording, resolved here rather than read off the provider:
  // `ViewSurface` is inside `WorkbenchShell`, so this component is above the
  // context and would otherwise name the editor in English on a translated
  // page.
  const messages = useViewMessages(wording);
  const workbench = useWorkbench(engine, definitionId, {
    kind: 'record',
    instanceId,
    onInstanceChange,
    newView: {
      title: messages.label('label.view.new-title'),
      ...(template ? { config: template } : {}),
    },
  });
  const { filter, runtime, state } = workbench;
  const record = runtime?.kind === 'record' ? runtime : null;
  const table = useRecordTable(record);

  // The one live region of this surface, and the queries it reads back.
  //
  // It lives with the result rather than in the shell: the result is what a
  // query changes, and the shell is shared with two other workbenches whose
  // answers are not rows. One per surface is the rule (`Announcer.tsx`) —
  // the column settings and the sort editor each carry one of their own, but
  // only while their popover is open, and neither is on screen at the same
  // time as a drag of the other.
  const { say: announce, region: announcement } = useAnnouncer(
    'record-announcement',
  );
  useQueryAnnouncement(table, messages, announce, emptyTitle);

  const fields = runtime?.fields ?? [];
  const row = actions?.row;

  // The condition fold, held here only so the empty result can open it.
  //
  // It is tagged with the runtime it was set for and handed straight back to
  // the shell, which is the shell's own rule — a fold belongs to one opening
  // of one view — so switching views falls back to the shell's default
  // exactly as it did when the shell alone held it.
  const runtimeId = runtime?.id ?? null;
  // What the table's cap (D17-4) is not drawing right now, so the column
  // settings can say so beside the switch that still says "pinned".
  const [released, setReleased] = useState<ReleasedPins>(NO_RELEASE);
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
  const shown = featuresOf(features);
  const emptyAction = () => {
    if (!hasConditions) {
      setFold({ id: runtimeId, open: true });
      return;
    }
    filter.clear();
    filter.submit();
  };
  // The host's word over the workbench's: a function replaces the answer,
  // `null` takes the button away and leaves the sentence.
  const onEmptyAction =
    hostEmptyAction === null ? undefined : (hostEmptyAction ?? emptyAction);

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
  // will be called before there is a file (D14). The clock is read when the
  // window asks — once, as it opens — rather than on every render and again
  // at delivery: a name holds a day in it, and an export that ran across
  // midnight used to be handed over under a name nobody was shown.
  const nameFile = useCallback(
    () => fileName(title, isoDay(now(), display), 'csv'),
    [display, now, title],
  );
  const deliver = useCallback(
    (rows: readonly RecordData[], scope: RecordExportScope, name: string) => {
      const text = serializeCsv(rows, columns, (value, column) =>
        cellText(value, column, messages, display),
      );
      downloadFile({ name, text, type: CSV_TYPE });
      onExported?.({ name, text, scope, rows: rows.length });
    },
    [columns, display, messages, onExported],
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
      manage={shown.manage}
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
      /* Only where it will draw: the shell reads the slot to decide whether
         there is a result block at all, and an element that renders null
         still counts as something in it (`filled`). */
      strips={
        table.error != null && (
          <QueryStrip
            error={table.error}
            stale={hasResult(state)}
            onRetry={table.refresh}
          />
        )
      }
      /* The way out of a config that will not run. It is the same panel
         the toolbar's button opens — and it is offered here because the
         state this line is read in is exactly the state there is no
         toolbar in: nothing ran, so there is no result and no result
         block (F-14). Offered under both layouts, because what the
         finding is about is the view's columns and the card settings do
         not hold them. */
      errorAction={
        record &&
        shown.columns && (
          <ColumnSettings
            table={table}
            fields={fields}
            {...(record.definition.fieldGroups
              ? { fieldGroups: record.definition.fieldGroups }
              : {})}
            {...(record.definition.record?.rowKey === undefined
              ? {}
              : { rowKey: record.definition.record.rowKey })}
            trigger={
              <Button variant="outline" size="xs">
                {messages.label('label.status.open-columns')}
              </Button>
            }
          />
        )
      }
      toolbar={
        record && (
          <ResultToolbar
            table={table}
            fields={fields}
            fieldGroups={record.definition.fieldGroups}
            rowKey={record.definition.record?.rowKey}
            // Cards draw no pins, so nothing is let go under them.
            released={table.layout === 'table' ? released : NO_RELEASE}
            bulkActions={actions?.bulk}
            features={features}
            exporter={
              shown.export
                ? {
                    control: exportControl,
                    // The host's scope narrows the export exactly as it
                    // narrows the rows, so the window names both kinds of
                    // condition.
                    conditions: [
                      ...filter.applied,
                      ...filter.scoped,
                      ...filter.implied,
                    ],
                    nameFile,
                  }
                : undefined
            }
            runtime={record}
          />
        )
      }
      result={
        record && (
          <>
            {table.layout === 'card' ? (
              <RecordCards
                table={table}
                renderCell={renderCell}
                selectable={selectable}
                emptyTitle={emptyTitle}
                emptyDescription={emptyDescription}
                rowActions={bindRow(row, record, table.refresh)}
                hasConditions={hasConditions}
                onEmptyAction={onEmptyAction}
              />
            ) : (
              <RecordTable
                table={table}
                renderCell={renderCell}
                selectable={selectable}
                emptyTitle={emptyTitle}
                emptyDescription={emptyDescription}
                rowActions={bindRow(row, record, table.refresh)}
                hasConditions={hasConditions}
                onEmptyAction={onEmptyAction}
                onReleasedPins={setReleased}
              />
            )}

            <RecordPagination table={table} />

            {/* Last in the block, where nothing about it can be reached by
                a pointer or a tab: it draws nothing and is read, not seen. */}
            {announcement}
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
