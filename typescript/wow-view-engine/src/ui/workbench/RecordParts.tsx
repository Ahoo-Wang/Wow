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

import { useMemo, useState, type ReactNode } from 'react';
import type { FieldOption } from '../../model/index.js';
import type { RecordRow } from '../../record/index.js';
import type { RecordViewRuntime } from '../../runtime/index.js';
import type { RecordActions } from '../../runtime/actions.js';
import {
  useRecordDetail,
  useRecordTable,
  useSearchBox,
  type RecordActionSlots,
  type RecordDetailControl,
  type RecordDetailController,
  type RecordDetailSection,
  type RecordDetailSectionContext,
  type WorkbenchController,
} from '../../react/index.js';
import { SurfaceAnnouncer, useAnnouncer } from '../kit/Announcer.js';
import { Button } from '../components/button.js';
import { CardSettings } from '../record/CardSettings.js';
import { ColumnSettings } from '../columns/ColumnSettings.js';
import { SortSettings } from '../sort/SortSettings.js';
import { configRemedy } from './configRemedy.js';
import { useExportOffer, type ExportedFile } from '../record/exportOffer.js';
import { useQueryAnnouncement } from '../record/queryAnnouncement.js';
import { FilterPanel } from '../filter/FilterPanel.js';
import { FilterModes } from '../filter/FilterModes.js';
import { RecordCards } from '../record/RecordCards.js';
import { RecordPagination } from '../record/RecordPagination.js';
import { RecordTable, type RecordCell } from '../record/RecordTable.js';
import { RecordDetail } from '../record/RecordDetail.js';
import { wayOutOf } from '../record/emptyWayOut.js';
import { NO_RELEASE, type ReleasedPins } from '../record/pinCap.js';
import { ResultToolbar, type ResultToolbarProps } from './ResultToolbar.js';
import { useActionSurface } from '../actions/ActionSurface.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { LAYOUT_LABEL, recordIssueNamer } from '../record/issueNames.js';
import type { ViewMessages } from '../kit/messages.js';
import { featuresOf, type WorkbenchFeatures } from '../kit/features.js';
import { resultSlots } from '../kit/variants.js';
import {
  RenderSlot,
  type RenderFailureHandler,
} from '../kit/RenderBoundary.js';
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
   * The host's declared actions (`actions()`, host-integration.md 5): what
   * may be *done* to a record, which the workbench places — the row's
   * button and menu, the selection's bar, the detail — asks about, runs a
   * few at a time and reports on the line above the rows. What may be done
   * belongs to the application that mounted the workbench, not to the way
   * of looking somebody saved.
   */
  actions?: RecordActions;
  /**
   * The host's own markup, drawn after the declared actions — one over the
   * view, one over a selection, one per row: the escape hatch for what a
   * declaration cannot say, a link out, say.
   */
  slots?: RecordActionSlots;
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
   * A render function, as `slots.row` is: what the application shows about
   * a record is code, not something a saved view holds.
   */
  sections?(
    context: RecordDetailSectionContext,
  ): readonly RecordDetailSection[];
  /**
   * The record read the host's way, in place of the definition's groups and
   * of `sections`: a host whose records answer one question (why did this
   * fail, and will trying again help) lays that answer out itself. The
   * panel stays the engine's — opening, reading the whole record, saying it
   * is gone or refused, the row's commands, focus and the way back.
   */
  render?(context: RecordDetailSectionContext): ReactNode;
  /**
   * The record's name in the header, in place of its key, which then stands
   * above it. Asked with the row the detail holds; `undefined` keeps the key.
   */
  title?(row: RecordRow): string | undefined;
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
  slots,
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
  const { region: announcement, ...voice } = useAnnouncer(
    'record-announcement',
  );
  const leadQuery = useQueryAnnouncement(table, messages, voice, emptyTitle);

  const fields = record?.fields ?? [];
  // The record the detail holds, which the page may not: its actions are
  // drawn there, and its availability changes on its own as a row's does.
  const { key: detailKey, record: detailData } = detail;
  const detailRows = useMemo(
    () =>
      detailKey !== null && detailData
        ? [{ key: detailKey, data: detailData }]
        : undefined,
    [detailKey, detailData],
  );
  const surface = useActionSurface({
    actions,
    slots,
    table,
    runtime: record,
    ...(detailRows ? { also: detailRows } : {}),
    say: voice.say,
    // The outcome leads the refresh's own sentence rather than being said
    // over by it.
    sayOutcome: leadQuery,
    ...(wording ? { messages: wording } : {}),
    ...(locale ? { locale } : {}),
  });

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
  // 「添加条件」 on an empty result with nothing asked is a request for a
  // field: it opens the fold and the field list in it, rather than leaving
  // the keyboard on a button that has just been taken off the screen.
  const [pick, setPick] = useState<{ id: string | null; n: number } | null>(
    null,
  );

  // What the empty result offers, and the press that takes it (`wayOutOf`,
  // shared with the analysis's empty result).
  const shown = featuresOf(features);
  const hasConditions = filter.applied.length > 0;
  const { wayOut, take: emptyAction } = wayOutOf({
    state,
    hasConditions,
    filter,
    runtime: record,
    openEditor: () => {
      setFold({ id: runtimeId, open: true });
      // Without conditions the editor is opened for `add`: the question
      // to ask starts with a field.
      if (!hasConditions)
        setPick(last => ({ id: runtimeId, n: (last?.n ?? 0) + 1 }));
    },
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
    actions: slots?.global && (
      <RenderSlot
        render={() =>
          slots.global?.({ runtime: record, refresh: table.refresh })
        }
      />
    ),
    editorOpen,
    onEditorOpenChange: open => {
      setFold({ id: runtimeId, open });
      // Spent: the list it opened is the fold's, and a fold opened again by
      // hand is not a request for a field.
      if (!open) setPick(null);
    },
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
      <FilterPanel
        filter={filter}
        optionsFor={optionsFor}
        modes={false}
        pick={pick?.id === runtimeId ? pick.n : 0}
      />
    ),
    /* The way out of a config that will not run, for the part of it the
       first error is about (`configRemedy`; second review R1-P1-3). A
       panel is the same one the toolbar's button opens — offered here
       because the state this line is read in is exactly the state there
       is no toolbar in: nothing ran, so there is no result and no result
       block (F-14). A page size or a layout is one press instead: the
       pagination that offers sizes is not drawn either, and a layout the
       definition refuses has one answer, the layout it offers. */
    errorAction: (() => {
      const button = (label: string, onClick?: () => void) => (
        <Button variant="outline" size="xs" onClick={onClick}>
          {label}
        </Button>
      );
      switch (configRemedy(filter.unmarked)) {
        case 'page-size': {
          const size = table.pageSizeFix;
          return (
            size !== null &&
            button(messages.label('label.status.use-page-size', { size }), () =>
              table.setPageSize(size),
            )
          );
        }
        case 'layout': {
          const layout = table.layouts[0];
          return (
            layout !== undefined &&
            button(
              messages.label('label.status.use-layout', {
                layout: messages.label(LAYOUT_LABEL[layout]),
              }),
              () => table.setLayout(layout),
            )
          );
        }
        case 'sort':
          return (
            shown.sort && (
              <SortSettings
                table={table}
                fields={fields}
                {...(table.fieldGroups
                  ? { fieldGroups: table.fieldGroups }
                  : {})}
                trigger={button(messages.label('label.status.open-sort'))}
              />
            )
          );
        case 'card':
          return (
            shown.columns && (
              <CardSettings
                table={table}
                fields={fields}
                trigger={button(messages.label('label.status.open-card'))}
              />
            )
          );
        case 'columns':
          return (
            shown.columns && (
              <ColumnSettings
                table={table}
                fields={fields}
                fieldGroups={table.fieldGroups}
                rowKey={table.rowKey}
                trigger={button(messages.label('label.status.open-columns'))}
              />
            )
          );
        default:
          return null;
      }
    })(),
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
        bulkActions={surface.bulk}
        features={features}
        runtime={record}
        exports={shown.export}
        filter={filter}
        title={state?.title ?? ''}
        {...(onExported ? { onExported } : {})}
      />
    ),
    // The table's own sentences — a column's width, step by step — are
    // said in this surface's one voice rather than a second region.
    result: (
      <SurfaceAnnouncer say={voice.say}>
        {table.layout === 'card' ? (
          <RecordCards
            table={table}
            renderCell={renderCell}
            selectable={selectable}
            emptyTitle={emptyTitle}
            emptyDescription={emptyDescription}
            rowActions={surface.row}
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
            rowActions={surface.row}
            onOpen={detail.open}
            emptyWayOut={wayOut}
            onEmptyAction={onEmptyAction}
            onReleasedPins={setReleased}
            {...(state?.title ? { name: state.title } : {})}
          />
        )}

        <RecordDetail
          detail={detail}
          actions={surface.detail}
          sections={bindSections(
            detailOptions?.sections,
            detail,
            record,
            table.refresh,
          )}
          render={bindSections(
            detailOptions?.render,
            detail,
            record,
            table.refresh,
          )}
          title={detailOptions?.title}
          onRenderFailure={onRenderFailure}
        />

        {/* Under the rows rather than over them: a line that appears above
            the rows moves every row down under the pointer that pressed
            one (UX-8). */}
        {surface.status}

        <RecordPagination table={table} />

        {surface.dialog}

        {/* Last in the block, where nothing about it can be reached by a
            pointer or a tab: it draws nothing and is read, not seen. */}
        {announcement}
      </SurfaceAnnouncer>
    ),
    resultSlots: RESULT_SLOTS,
    resultWithoutQuery: table.filterRequired === true,
  });
}

/**
 * The host's part of the detail (its sections, or its whole reading) bound
 * to the open view, or nothing at all.
 */
function bindSections<T>(
  sections: ((context: RecordDetailSectionContext) => T) | undefined,
  detail: RecordDetailController,
  runtime: RecordViewRuntime,
  refresh: () => void,
): ((row: RecordRow) => T) | undefined {
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
