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
import type { FieldOption } from '../../model/index.js';
import type { RecordRow } from '../../record/index.js';
import type { RecordViewRuntime } from '../../runtime/index.js';
import {
  useRecordDetail,
  useRecordTable,
  useSearchBox,
  type BulkCommand,
  type RecordActionSlots,
  type RecordDetailControl,
  type RecordDetailController,
  type RecordDetailSection,
  type RecordDetailSectionContext,
  type WorkbenchController,
} from '../../react/index.js';
import { useAnnouncer } from '../Announcer.js';
import { Button } from '../components/button.js';
import { ColumnSettings } from '../ColumnSettings.js';
import { useExportOffer, type ExportedFile } from '../record/exportOffer.js';
import { useQueryAnnouncement } from '../record/queryAnnouncement.js';
import { FilterPanel } from '../FilterPanel.js';
import { FilterModes } from '../filter/FilterModes.js';
import { RecordCards } from '../RecordCards.js';
import { RecordPagination } from '../RecordPagination.js';
import { RecordTable, type RecordCell } from '../RecordTable.js';
import { RecordDetail } from '../record/RecordDetail.js';
import { wayOutOf } from '../record/emptyWayOut.js';
import { NO_RELEASE, type ReleasedPins } from '../record/pinCap.js';
import { ResultToolbar, type ResultToolbarProps } from '../ResultToolbar.js';
import { BulkStatus } from '../BulkStatus.js';
import { useViewMessages } from '../MessagesProvider.js';
import { recordIssueNamer } from '../record/issueNames.js';
import type { ViewMessages } from '../messages.js';
import { featuresOf, type WorkbenchFeatures } from '../features.js';
import { resultSlots } from '../variants.js';
import { RenderSlot, type RenderFailureHandler } from '../RenderBoundary.js';
import { NO_PARTS, type RenderParts } from './parts.js';
import { SearchBox } from './SearchBox.js';

/**
 * What a host may say about the record views a workbench draws: what may be
 * done to a record, how a cell is read, whether rows can be picked, and what
 * an empty result says. None of it is about the frame, and none of it means
 * anything to an analysis view, so it is one object rather than a spread of
 * props over the workbench.
 */
export interface RecordViewProps {
  /**
   * The host's own business actions: one over the view, one over a selection,
   * one per row. They are render functions rather than names in a config —
   * what may be *done* to a record belongs to the application that mounted
   * the workbench, not to the way of looking somebody saved.
   */
  actions?: RecordActionSlots;
  /**
   * The host's bulk command (`useBulkCommand`), whose progress and outcome
   * the workbench says above the rows, where a failed query is said. The
   * command outlives the selection it ran over — the toolbar's bulk slot
   * goes with the selection — so its line belongs to the result, not to
   * the slot that started it.
   */
  bulk?: BulkCommand;
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
   * What the empty result's one button does. Left out, the workbench's own
   * answer: clear the conditions in force, or open the editor when there
   * are none. A function is the host's answer instead; `null` is no button
   * — the sentence alone.
   */
  emptyAction?: (() => void) | null;
  /**
   * Told whenever an export has been handed to the browser — the file's name,
   * its contents and how many rows of which scope it holds. A host that
   * audits what leaves the application reads it; nothing here needs it, and
   * the download happens either way.
   */
  onExported?(file: ExportedFile): void;
  /**
   * The record detail: which record is open, for a host that keeps it in
   * its address, and the host's own sections in it. Left out, the detail is
   * the workbench's own — a row opens it, its close closes it — and holds
   * the definition's field groups alone.
   */
  detail?: RecordDetailOptions;
}

/**
 * What a host says about the record detail (G2): who holds which record is
 * open (`RecordDetailControl`, as `instanceId` and `onInstanceChange` hold
 * the open view), and what the host adds to it.
 */
export interface RecordDetailOptions extends RecordDetailControl {
  /**
   * The host's sections for the record open, asked each time the detail
   * draws a record — the context says which, and whether it is whole yet.
   * A render function, as `actions.row` is: what the application shows about
   * a record is code, not something a saved view holds.
   */
  sections?(
    context: RecordDetailSectionContext,
  ): readonly RecordDetailSection[];
}

export type { ExportedFile } from '../record/exportOffer.js';

export interface RecordPartsProps extends RecordViewProps {
  workbench: WorkbenchController;
  /** The open record view, or null while what is open is not one. */
  runtime: RecordViewRuntime | null;
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  locale?: string;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * Which of the workbench's own controls are on screen (D18 XI): export,
   * the layout switch, column settings, sort settings. All on by default;
   * one turned off is absent, not disabled.
   */
  features?: WorkbenchFeatures;
  /** Told of a render failure in a host's section of the record detail. */
  onRenderFailure?: RenderFailureHandler;
  children: RenderParts;
}

/**
 * What this kind puts inside the result frame, and how the frame dresses
 * each of them: the pagination row is its caption, a query that matched
 * nothing stands in air of its own, and the cards keep the frame's padding
 * where the table runs to its edge.
 *
 * It is stated here rather than in `ResultBlock` because these are a
 * *record* view's parts: the frame is shared by three kinds, and one shared
 * frame that names one kind's slots is the `if` D18-1 means to do without.
 */
const RESULT_SLOTS = resultSlots('caption', 'empty', 'cards', 'bulk');

/**
 * What makes a record view a record view: the condition band it edits in,
 * and the toolbar, rows and paging it reads. It renders nothing of its own —
 * it hands its parts to `children`, which draws the shell around them, and
 * hands back none while no record view is open (`parts.ts`).
 */
export function RecordParts({
  workbench,
  runtime: record,
  messages: wording,
  locale,
  optionsFor,
  features,
  actions,
  bulk,
  renderCell,
  selectable,
  emptyTitle,
  emptyDescription,
  emptyAction: hostEmptyAction,
  onExported,
  detail: detailOptions,
  onRenderFailure,
  children,
}: RecordPartsProps) {
  // The host's wording, resolved here rather than read off the provider:
  // `ViewSurface` is inside the shell this part is handed to, so this
  // component is above the context and would otherwise name the editor in
  // English on a translated page.
  const messages = useViewMessages(wording, locale);
  const { filter, state } = workbench;
  const table = useRecordTable(record);
  const detail = useRecordDetail(record, {
    open: detailOptions?.open,
    onOpenChange: detailOptions?.onOpenChange,
  });

  // The one live region of this surface, and the queries it reads back.
  //
  // It lives with the result rather than in the shell: the result is what a
  // query changes, and the shell is shared with two other kinds whose
  // answers are not rows. One per surface is the rule (`Announcer.tsx`) —
  // the column settings and the sort editor each carry one of their own, but
  // only while their popover is open, and neither is on screen at the same
  // time as a drag of the other.
  const { say: announce, region: announcement } = useAnnouncer(
    'record-announcement',
  );
  useQueryAnnouncement(table, messages, announce, emptyTitle);

  const fields = record?.fields ?? [];
  const row = actions?.row;

  // The condition fold, held here only so the empty result can open it.
  //
  // It is tagged with the runtime it was set for and handed straight back to
  // the shell, which is the shell's own rule — a fold belongs to one opening
  // of one view — so switching views falls back to the shell's default
  // exactly as it did when the shell alone held it.
  const runtimeId = record?.id ?? null;
  // What the table's cap (D17-4) is not drawing right now, so the column
  // settings can say so beside the switch that still says "pinned".
  const [released, setReleased] = useState<ReleasedPins>(NO_RELEASE);
  const [fold, setFold] = useState<{ id: string | null; open: boolean } | null>(
    null,
  );
  const editorOpen = fold?.id === runtimeId ? fold.open : undefined;

  // What the empty result offers, and the press that takes it (`wayOutOf`,
  // shared with the analysis's empty result).
  const shown = featuresOf(features);
  const { wayOut, take: emptyAction } = wayOutOf({
    state,
    hasConditions: filter.applied.length > 0,
    filter,
    runtime: record,
    openEditor: () => setFold({ id: runtimeId, open: true }),
  });
  // The host's word over the workbench's: a function replaces the answer,
  // `null` takes the button away and leaves the sentence.
  const onEmptyAction =
    hostEmptyAction === null ? undefined : (hostEmptyAction ?? emptyAction);

  const searchBox = useSearchBox(record);

  if (!record) return children(NO_PARTS);
  return children({
    // As an element rather than a call: the slot then renders inside the
    // shell's boundary for it, and a host action that throws takes the slot
    // and not the workbench.
    actions: actions?.global && (
      <RenderSlot
        render={() =>
          actions.global?.({ runtime: record, refresh: table.refresh })
        }
      />
    ),
    editorOpen,
    onEditorOpenChange: open => setFold({ id: runtimeId, open }),
    search: shown.search && searchBox && <SearchBox search={searchBox} />,
    // The status line names every field, summary and operator a finding
    // names as the screen does, never by its path (`recordIssueNamer`).
    nameIssue: recordIssueNamer(record.definition.fields, messages),
    editorLabel: messages.label('label.filter.panel'),
    editorModes: <FilterModes filter={filter} />,
    editorPending: filter.pendingCount,
    /* Not frozen while a query runs: typing never re-queries, and a refresh
       that lands mid-edit must not take the input away. */
    editor: (
      <FilterPanel filter={filter} optionsFor={optionsFor} modes={false} />
    ),
    /* The way out of a config that will not run. It is the same panel the
       toolbar's button opens — and it is offered here because the state
       this line is read in is exactly the state there is no toolbar in:
       nothing ran, so there is no result and no result block (F-14).
       Offered under both layouts, because what the finding is about is the
       view's columns and the card settings do not hold them. */
    errorAction: shown.columns && (
      <ColumnSettings
        table={table}
        fields={fields}
        fieldGroups={table.fieldGroups}
        rowKey={table.rowKey}
        trigger={
          <Button variant="outline" size="xs">
            {messages.label('label.status.open-columns')}
          </Button>
        }
      />
    ),
    /* An export says nothing here: it has a window of its own, and that
       window is where it reports what it produced, what the ceiling cut
       short and what went wrong (D14). A cancel says nothing anywhere — it
       is the answer the user gave. */
    toolbar: (
      <RecordToolbar
        table={table}
        fields={fields}
        fieldGroups={table.fieldGroups}
        rowKey={table.rowKey}
        // Cards draw no pins, so nothing is let go under them.
        released={table.layout === 'table' ? released : NO_RELEASE}
        bulkActions={actions?.bulk}
        features={features}
        runtime={record}
        exports={shown.export}
        filter={filter}
        title={state?.title ?? ''}
        {...(onExported ? { onExported } : {})}
      />
    ),
    result: (
      <>
        {bulk && <BulkStatus command={bulk} />}
        {table.layout === 'card' ? (
          <RecordCards
            table={table}
            renderCell={renderCell}
            selectable={selectable}
            emptyTitle={emptyTitle}
            emptyDescription={emptyDescription}
            rowActions={bindRow(row, record, table.refresh)}
            onOpen={detail.open}
            emptyWayOut={wayOut}
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
            onOpen={detail.open}
            emptyWayOut={wayOut}
            onEmptyAction={onEmptyAction}
            onReleasedPins={setReleased}
          />
        )}

        <RecordDetail
          detail={detail}
          actions={bindRow(row, record, table.refresh)}
          sections={bindSections(
            detailOptions?.sections,
            detail,
            record,
            table.refresh,
          )}
          onRenderFailure={onRenderFailure}
        />

        <RecordPagination table={table} />

        {/* Last in the block, where nothing about it can be reached by a
            pointer or a tab: it draws nothing and is read, not seen. */}
        {announcement}
      </>
    ),
    resultSlots: RESULT_SLOTS,
  });
}

/**
 * The host's row slot bound to the open view, or nothing at all.
 *
 * The table is handed a function of the row alone — it knows a result, not a
 * runtime — so the binding happens here, where both are in hand. The wrapper
 * is the table's and the cards' to add (`RowActions`), once, the same way an
 * embed's actions get it; binding it here as well drew two nested
 * `row-actions` around every row's buttons. The slot still runs inside the
 * result's render boundary, so a host's action that throws takes the rows
 * with it and nothing else.
 */
function bindRow(
  row: RecordActionSlots['row'],
  runtime: RecordViewRuntime,
  refresh: () => void,
): ((row: RecordRow) => ReactNode) | undefined {
  return row ? item => row({ row: item, runtime, refresh }) : undefined;
}

/** The host's detail sections bound to the open view, or nothing at all. */
function bindSections(
  sections: RecordDetailOptions['sections'],
  detail: RecordDetailController,
  runtime: RecordViewRuntime,
  refresh: () => void,
): ((row: RecordRow) => readonly RecordDetailSection[]) | undefined {
  return sections
    ? row => sections({ row, complete: detail.complete, runtime, refresh })
    : undefined;
}

/**
 * The result toolbar with the export offered on it (`useExportOffer`).
 *
 * A component of its own rather than a call in `RecordParts`, because the
 * offer reads the wording, the language and the zone off the surface it is
 * drawn on, and `RecordParts` is above that surface — the toolbar, handed to
 * the shell as a slot, is inside it.
 */
function RecordToolbar({
  exports,
  filter,
  title,
  onExported,
  ...props
}: Omit<ResultToolbarProps, 'exporter'> & {
  /** Whether the host offers exports (`WorkbenchFeatures.export`). */
  exports: boolean;
  filter: WorkbenchController['filter'];
  /** The view's title, which names the file. */
  title: string;
  onExported?(file: ExportedFile): void;
}) {
  const exporter = useExportOffer({
    runtime: props.runtime,
    table: props.table,
    filter,
    title,
    ...(onExported ? { onExported } : {}),
  });
  return <ResultToolbar {...props} {...(exports ? { exporter } : {})} />;
}
