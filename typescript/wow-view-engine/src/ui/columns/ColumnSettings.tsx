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

import {
  useCallback,
  useId,
  useMemo,
  useState,
  type ReactElement,
} from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { Columns3Icon, SearchIcon } from 'lucide-react';
import type {
  FieldDefinition,
  FieldGroupDefinition,
} from '../../model/index.js';
import type { RecordTableController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { Empty, EmptyDescription, EmptyHeader } from '../components/empty.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from '../components/input-group.js';
import {
  Popover,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from '../components/popover.js';
import { PopoverContent, TooltipContent } from '../kit/popups.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { NO_RELEASE, type ReleasedPins } from '../record/pinCap.js';
import { pinAnnouncement } from './ColumnRow.js';
import { columnDragAccessibility, columnDrop } from './drag.js';
import { sortableList } from '../kit/dragPlugins.js';
import {
  columnSettingRows,
  movableIndex,
  regionOf,
  renderedCount,
  renderedIndex,
  reorderColumns,
  REGIONS,
  type ColumnSettingRow,
} from './rows.js';
import { matchingRows } from './sections.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { Region } from './ColumnRegion.js';
import {
  ToolbarItem,
  libraryPopover,
  type DetachedPopover,
  type LibraryPopoverHandle,
} from '../kit/toolbar.js';
import { useAnnouncer } from '../kit/Announcer.js';

/**
 * What of a record table the column settings read and set: the columns,
 * their pinning, visibility, widths and summaries.
 */
export type ColumnSettingsTable = Pick<
  RecordTableController,
  | 'columnFields'
  | 'summaryFields'
  | 'pinnedOf'
  | 'hiddenOf'
  | 'summaryOf'
  | 'columns'
  | 'setColumnOrder'
  | 'setPinned'
  | 'setColumnWidth'
  | 'setSummary'
  | 'setColumns'
>;

export interface ColumnSettingsProps {
  table: ColumnSettingsTable;
  /** The fields the definition offers, in its order. */
  fields: readonly FieldDefinition[];
  /**
   * The picker groups of the definition the fields come from, which the
   * scrolling area is listed under.
   */
  fieldGroups?: readonly FieldGroupDefinition[];
  /** The field holding each row's identity, when the definition declares one. */
  rowKey?: string;
  /** Pins the table's cap is not drawing right now (D17-4); see `ColumnRow`. */
  released?: ReleasedPins;
  /**
   * The control that opens it. Left out, the toolbar's bordered icon button
   * with the panel's name in its tooltip (D12 Ⅳ).
   *
   * The error strip passes a worded button instead, and it passes one
   * because in the state that strip is read in there is no toolbar to be an
   * icon in: a config the definition refuses never ran, so there is no
   * result and so no result block (F-14). The panel is the same panel; only
   * the way in is different, and an icon alone at the end of a sentence
   * names nothing.
   */
  trigger?: ReactElement<Record<string, unknown>>;
  /**
   * Draws one half of it, joined to the other by a handle: the toolbar's
   * button inside the toolbar, the popup beside it (`DetachedPopover`).
   * Left out, both halves are drawn here together.
   */
  detached?: DetachedPopover;
}

/**
 * Which columns the table shows, in which order, pinned where, summarised
 * how.
 *
 * All four are one question — "what does a row look like" — and they were
 * three controls in three places before this: a checklist for visibility,
 * the config for the order, and nothing at all for pinning or summaries. A
 * column keeps to its area: the row key leads the held one, and everything
 * else orders freely inside whichever area it is pinned to. The host's
 * action column is in neither — it is a render slot, always last and always
 * held against the right edge, and none of that is a setting (D19).
 */
export function ColumnSettings({
  table,
  fields,
  fieldGroups,
  rowKey,
  released = NO_RELEASE,
  trigger,
  detached,
}: ColumnSettingsProps) {
  const messages = useViewMessages();
  const noteId = useId();
  const { say: announce, region: announcement } = useAnnouncer(
    'column-announcement',
  );
  // Cleared when the popover closes rather than kept: a filter that outlives
  // the opening it was typed in is a list that is missing rows for a reason
  // the reader has forgotten, and the field picker's own filter resets the
  // same way.
  const [query, setQuery] = useState('');

  // Each row named in the surface's words: the list is shown and searched,
  // and gives back field names.
  const rows = useMemo(
    () =>
      columnSettingRows({
        fields: fields.map(field => ({
          ...field,
          label: messages.say(field.label),
        })),
        columns: table.columnFields,
        ...(rowKey === undefined ? {} : { rowKey }),
        summaryFields: table.summaryFields,
        pinnedOf: table.pinnedOf,
        hiddenOf: table.hiddenOf,
        summaryOf: table.summaryOf,
      }),
    [
      fields,
      messages,
      rowKey,
      table.columnFields,
      table.hiddenOf,
      table.pinnedOf,
      table.summaryFields,
      table.summaryOf,
    ],
  );
  const filtering = query.trim() !== '';
  // The rows on screen. Everything the panel *counts* is still counted over
  // the whole list below, because a search narrows what is shown and nothing
  // else: where a move lands is a place among every column the table draws,
  // not among the ones that happen to match.
  const listed = useMemo(() => matchingRows(rows, query), [query, rows]);
  // What the reader is looking at: the columns of this list the table
  // actually draws. A broken one is not one of them — `projectRecord`
  // leaves it out — and neither is the host's action column, which this
  // list does not carry a row for (D19).
  const onScreen = renderedCount(rows);
  /** The word a field wears, for the two voices that name one. */
  const nameOf = useCallback(
    (field: string) =>
      rows.find(entry => entry.field === field)?.label ?? field,
    [rows],
  );
  /** Commits one move and says where the column landed, for both inputs. */
  const moveTo = useCallback(
    (field: string, toIndex: number) => {
      const order = reorderColumns(rows, field, toIndex);
      if (!order) return;
      table.setColumnOrder(order);
      // Counted over every column the table shows rather than over the
      // movable ones alone: "second of four" is what the user is looking at,
      // and "first of three" names a place the table does not have.
      announce(
        messages.label('label.columns.moved', {
          field: nameOf(field),
          index: renderedIndex(reordered(rows, order), field),
          total: onScreen,
        }),
      );
    },
    [announce, messages, nameOf, onScreen, rows, table],
  );
  /**
   * Flips one column's pinning and says what it did.
   *
   * `aria-pressed` reports the toggle's own new state, and what it cannot
   * report is which column and which edge — so the panel's one live region
   * says the sentence too. Said here rather than in the row: this is where
   * the next state is decided, and where the panel's one voice is.
   */
  const pinTo = useCallback(
    (field: string) => {
      const row = rows.find(entry => entry.field === field);
      if (!row) return;
      table.setPinned(field, !row.pinned);
      announce(pinAnnouncement(messages, row.label, !row.pinned));
    },
    [announce, messages, rows, table],
  );

  /** Sets one column's width from its row, and says what it came to. */
  const widthTo = useCallback(
    (field: string, width: number | null) => {
      table.setColumnWidth(field, width);
      announce(
        width === null
          ? messages.label('label.columns.resized-auto', {
              field: nameOf(field),
            })
          : messages.label('label.columns.resized', {
              field: nameOf(field),
              width,
            }),
      );
    },
    [announce, messages, nameOf, table],
  );

  const handle = libraryPopover(detached?.handle);
  const opener = <ColumnsOpener handle={handle} trigger={trigger} />;
  if (detached?.part === 'trigger') return opener;

  return (
    <Popover
      handle={handle}
      onOpenChange={open => {
        if (!open) setQuery('');
      }}
    >
      {detached ? null : opener}
      {/* The popup's own scroll port is handed to the list below instead:
          the search line and the title stay put while twenty rows go past
          them, which is the whole point of having a search line. The `y`
          axis is named on purpose — `overflow-hidden` would not replace
          `overflow-y-auto` on merge, and the horizontal axis is the
          registry's to decide. */}
      <PopoverContent align="end" className="fve:w-96 fve:overflow-y-hidden">
        <PopoverHeader>
          <PopoverTitle>{messages.label('label.columns.title')}</PopoverTitle>
          {/* One sentence, and the only one no row can say for itself.
              Every rule about a particular column — the last one that may
              not be hidden, a column that has to be shown before it can be
              pinned or summarised, a column the data no longer has
              — is written on that column's own row, where the control it
              governs is. Five of them collected here was a paragraph, and a
              paragraph is something a reader has to match against the row
              in front of them. */}
          <PopoverDescription>
            {messages.label('label.columns.hint')}
          </PopoverDescription>
          {/* The header's width keys, where a sighted keyboard user can
              read them: they were only in `aria-keyshortcuts`, which a
              screen reader says and nothing on screen does. Here, beside
              the width boxes, because this is where widths are set. */}
          <p
            data-slot="column-resize-keys"
            className="fve:text-muted-foreground fve:text-xs"
          >
            {messages.label('label.columns.resize-keys')}
          </p>
        </PopoverHeader>

        {/* A list one row long per column is a list to search once it is a
            wide table's: the same control, the same word and the same empty
            sentence as the field picker's, so looking for a field is one
            skill rather than three. */}
        <InputGroup>
          <InputGroupAddon>
            <SearchIcon />
          </InputGroupAddon>
          <InputGroupInput
            data-slot="column-search"
            type="text"
            value={query}
            placeholder={messages.label('label.field.search')}
            aria-label={messages.label('label.field.search')}
            aria-describedby={filtering ? noteId : undefined}
            onChange={event => setQuery(event.target.value)}
          />
        </InputGroup>

        {/* Said where it is true, and only then: ordering is what the list
            cannot mean while it is not showing the neighbours a column
            would be ordered against. One line about the control above it,
            the way the sort editor says it has run out of room — not an
            `Alert`, which is for something that has happened. */}
        {filtering && (
          <p
            id={noteId}
            data-slot="column-filtered"
            className="fve:text-muted-foreground"
          >
            {messages.label('label.columns.filtered')}
          </p>
        )}

        <div
          data-slot="column-list"
          className="fve:relative fve:-mx-2.5 fve:flex fve:min-h-0 fve:flex-1 fve:flex-col fve:overflow-y-auto fve:px-2.5"
        >
          {listed.length === 0 ? (
            // Nothing matched, which is what `Empty` is for — the same shape
            // and the same sentence the field picker shows.
            <Empty data-slot="column-none" className="fve:p-0">
              <EmptyHeader>
                <EmptyDescription>
                  {messages.label('label.field.none')}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <DragDropProvider
              {...sortableList(columnDragAccessibility(messages, nameOf))}
              onDragEnd={({ operation, canceled }) => {
                const drop = columnDrop(operation, canceled, field =>
                  regionOf(rows, field),
                );
                if (!drop) return;
                // Read off the whole list, never off what is on screen: a
                // place in the order is a place among every column the
                // config knows.
                moveTo(drop.source, movableIndex(rows, drop.target));
              }}
            >
              {REGIONS.map(region => (
                <Region
                  key={region}
                  region={region}
                  rows={listed}
                  all={rows}
                  groups={fieldGroups ?? EMPTY_GROUPS}
                  reorderable={!filtering}
                  table={table}
                  released={released}
                  onMove={moveTo}
                  onPin={pinTo}
                  onWidth={widthTo}
                />
              ))}
            </DragDropProvider>
          )}
        </div>

        {/* One voice for a move the user asked for with the arrow keys; the
            library announces its own pick-up and cancel. */}
        {announcement}
      </PopoverContent>
    </Popover>
  );
}

/** No groups at all, as one value, so the default is not a new array. */
const EMPTY_GROUPS: readonly FieldGroupDefinition[] = [];

/**
 * The rows in the order `order` puts them, so a position can be read off
 * the move that was just committed rather than off the list it replaced.
 */
function reordered(
  rows: readonly ColumnSettingRow[],
  order: readonly string[],
): ColumnSettingRow[] {
  const byField = new Map(rows.map(row => [row.field, row]));
  return [
    ...order.flatMap(field => {
      const row = byField.get(field);
      return row ? [row] : [];
    }),
    ...rows.filter(row => !order.includes(row.field)),
  ];
}

/** The control that opens the column settings: the caller's, or the toolbar's. */
function ColumnsOpener({
  handle,
  trigger,
}: Pick<ColumnSettingsProps, 'trigger'> & {
  handle: LibraryPopoverHandle | undefined;
}) {
  const messages = useViewMessages();
  return (
    <>
      {/* The caller's own control, where it gave one: it already says what
          it opens in words, so it wears neither the icon nor the tooltip
          that stand in for them. */}
      {trigger ? (
        <PopoverTrigger
          handle={handle}
          data-control="columns"
          render={trigger}
        />
      ) : (
        /* A bordered icon button with the word in its name and its tooltip
           (D12 Ⅳ): the control reports no state, so it carries no text. */
        <Tooltip>
          <TooltipTrigger
            render={
              // A toolbar item where a toolbar is around it, an ordinary
              // button anywhere else: the bar owns the roving focus order
              // and this is one of the stops in it.
              <ToolbarItem
                render={
                  <PopoverTrigger
                    handle={handle}
                    data-control="columns"
                    aria-label={messages.label('label.toolbar.columns')}
                    render={<Button variant="outline" size="icon-sm" />}
                  />
                }
              />
            }
          >
            <Columns3Icon />
          </TooltipTrigger>
          <TooltipContent>
            {messages.label('label.toolbar.columns')}
          </TooltipContent>
        </Tooltip>
      )}
    </>
  );
}
