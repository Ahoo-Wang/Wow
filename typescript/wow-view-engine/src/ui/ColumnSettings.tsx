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
  SummaryFunction,
} from '../model/index.js';
import type { RecordTableController } from '../react/index.js';
import { Button } from './components/button.js';
import { Empty, EmptyDescription, EmptyHeader } from './components/empty.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from './components/input-group.js';
import {
  Popover,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from './components/popover.js';
import { PopoverContent, TooltipContent } from './popups.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { NO_RELEASE, type ReleasedPins } from './record/pinCap.js';
import {
  ColumnRow,
  pinAnnouncement,
  SortableColumnRow,
} from './columns/ColumnRow.js';
import { columnDragAccessibility, columnDrop } from './columns/drag.js';
import { announcedPlugins } from './dragPlugins.js';
import {
  columnSettingRows,
  movableIndex,
  regionOf,
  renderedCount,
  renderedIndex,
  reorderColumns,
  REGIONS,
  type ColumnRegion,
  type ColumnSettingRow,
} from './columns/rows.js';
import { columnSections, matchingRows } from './columns/sections.js';
import { useViewMessages } from './MessagesProvider.js';
import { ToolbarItem } from './toolbar.js';
import { useAnnouncer } from './Announcer.js';

/**
 * One sortable group per area, so a drag cannot cross one: what is held
 * against the left edge and what scrolls are two lists, and a column joins
 * the other one by being pinned rather than by being dragged there — which
 * is also what makes a drop into the other area a pinning (D19).
 */
const GROUP: Record<ColumnRegion, string> = {
  pinned: 'columns-pinned',
  scrolling: 'columns-scrolling',
};

/**
 * Each area's heading — which is also its accessible name, because they are
 * the same node.
 *
 * The two words are the two pin states, the same ones a row's own pin
 * toggle switches between: an area *is* a pinning, so naming it with that
 * word makes the list and the control say the same thing. The scrolling one
 * used to be `label.columns.title` — the popover's own name — and as an
 * invisible `aria-label` that only went unnoticed; said out loud under a
 * heading already reading "Column settings" it would be the title twice and
 * the area not at all.
 */
const REGION_LABEL = {
  pinned: 'label.columns.pin.left',
  scrolling: 'label.columns.pin.none',
} as const;

export interface ColumnSettingsProps {
  table: RecordTableController;
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

  const rows = useMemo(
    () =>
      columnSettingRows({
        fields,
        columns: table.columnFields,
        ...(rowKey === undefined ? {} : { rowKey }),
        summaryFields: table.summaryFields,
        pinnedOf: table.pinnedOf,
        hiddenOf: table.hiddenOf,
        summaryOf: table.summaryOf,
      }),
    [
      fields,
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

  return (
    <Popover
      onOpenChange={open => {
        if (!open) setQuery('');
      }}
    >
      {/* The caller's own control, where it gave one: it already says what
          it opens in words, so it wears neither the icon nor the tooltip
          that stand in for them. */}
      {trigger ? (
        <PopoverTrigger data-control="columns" render={trigger} />
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
      {/* The popup's own scroll port is handed to the list below instead:
          the search line and the title stay put while twenty rows go past
          them, which is the whole point of having a search line. The `y`
          axis is named on purpose — `overflow-hidden` would not replace
          `overflow-y-auto` on merge, and the horizontal axis is the
          registry's to decide. */}
      <PopoverContent align="end" className="w-96 overflow-y-hidden">
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
            className="text-muted-foreground"
          >
            {messages.label('label.columns.filtered')}
          </p>
        )}

        <div
          data-slot="column-list"
          className="-mx-2.5 flex min-h-0 flex-1 flex-col overflow-y-auto px-2.5"
        >
          {listed.length === 0 ? (
            // Nothing matched, which is what `Empty` is for — the same shape
            // and the same sentence the field picker shows.
            <Empty data-slot="column-none" className="p-0">
              <EmptyHeader>
                <EmptyDescription>
                  {messages.label('label.field.none')}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <DragDropProvider
              plugins={announcedPlugins(
                columnDragAccessibility(messages, nameOf),
              )}
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

/** What one area and one section inside it are both drawn from. */
interface ListProps {
  region: ColumnRegion;
  /** The rows on screen — what a search left of the area's own. */
  rows: readonly ColumnSettingRow[];
  /** Every row the panel knows, which is what places are counted over. */
  all: readonly ColumnSettingRow[];
  /** Whether the list is showing the order, and can therefore change it. */
  reorderable: boolean;
  table: RecordTableController;
  released: ReleasedPins;
  onMove(field: string, toIndex: number): void;
  /** Cycles one column's pinning, and says what that did. */
  onPin(field: string): void;
}

/** One area's rows, or nothing at all when the area holds none. */
function Region({
  groups,
  ...props
}: ListProps & { groups: readonly FieldGroupDefinition[] }) {
  const messages = useViewMessages();
  const headingId = useId();
  const sections = columnSections(props.rows, props.region, groups);
  if (sections.length === 0) return null;

  return (
    <div data-slot="column-region-group" className="flex flex-col">
      {/* Said, and now also drawn. The areas were `aria-label`s and nothing
          on the screen, so a column that had just been pinned did not read
          as held at the edge — it read as having jumped to the top of the
          list. One level under the popover's own title, and styled like the
          sidebar's group labels: a heading of a list, not of the panel. */}
      <h3
        id={headingId}
        data-slot="column-region-heading"
        className="text-muted-foreground px-2 pt-2 pb-1 text-xs font-medium"
      >
        {messages.label(REGION_LABEL[props.region])}
      </h3>
      {sections.map(section => (
        <Section
          key={section.group?.id ?? ''}
          {...props}
          rows={section.rows}
          group={section.group}
          regionHeadingId={headingId}
        />
      ))}
    </div>
  );
}

/**
 * One block of an area: the catalogue group's rows under its label, or the
 * whole area's under none.
 *
 * Each block is a list of its own — named by its group where it has one and
 * by the area where it does not — and a sortable group of its own, so a
 * column cannot be carried out of the group the definition put it in. What
 * a drop *means* is still read off the whole list: `movableIndex` answers
 * over the area, so a column lands where the row it was dropped on sits,
 * and the sections the reader is not looking at keep their columns.
 */
function Section({
  region,
  rows,
  all,
  reorderable,
  table,
  released,
  onMove,
  onPin,
  group,
  regionHeadingId,
}: ListProps & {
  group: FieldGroupDefinition | undefined;
  regionHeadingId: string;
}) {
  const labelId = useId();
  // Counted inside the block, because that is the list the library is
  // sorting; what the move *commits* is counted over the area instead.
  const movable = rows.filter(row => row.movable);

  return (
    <>
      {group !== undefined && (
        // A level under the area's heading and drawn as one: quieter weight
        // and indented, so the catalogue reads as nested inside the pinning
        // rather than as a fourth area.
        <h4
          id={labelId}
          data-slot="column-group-heading"
          className="text-muted-foreground px-2 pt-1.5 pb-0.5 pl-3 text-xs"
        >
          {group.label}
        </h4>
      )}
      <ul
        data-slot="column-region"
        data-region={region}
        data-group={group?.id}
        aria-labelledby={group === undefined ? regionHeadingId : labelId}
        className="flex flex-col"
      >
        {rows.map(row => {
          // While a search narrows the list nothing is dragged: the rows a
          // move is relative to are the ones that are not on screen. The
          // handle says so by being refused rather than by moving a column
          // somewhere the reader cannot watch it land.
          const listed = reorderable ? row : { ...row, movable: false };
          const shared = {
            row: listed,
            // A row that is only a summary is not in the column list, so
            // its checkbox writes the summary away and leaves the columns
            // exactly as they are (D17-9).
            onToggle: () =>
              row.summaryOnly
                ? table.setSummary(row.field, null)
                : table.setColumns(toggled(all, row)),
            onPin: () => onPin(row.field),
            released: released.fields.has(row.field),
            onSummary: (fn: SummaryFunction | null) =>
              table.setSummary(row.field, fn),
            onMove: (step: -1 | 1) =>
              onMove(row.field, movableIndex(all, row.field) + step),
          };
          return listed.movable ? (
            <SortableColumnRow
              key={row.field}
              {...shared}
              index={movable.indexOf(row)}
              group={`${GROUP[row.region]}:${group?.id ?? ''}`}
            />
          ) : (
            <ColumnRow key={row.field} {...shared} />
          );
        })}
      </ul>
    </>
  );
}

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

/**
 * The columns the table shows after this row's checkbox is flipped, in the
 * order the panel lists them.
 *
 * Read off the rows rather than off the config's field names, because those
 * two are no longer the same list: a switched-off column is still in the
 * config — that is what keeps its place — and a field the config has never
 * mentioned is listed here and in no config at all. What the controller is
 * told is which columns are shown; where each one sits is its own answer
 * (`setColumns`).
 */
function toggled(
  rows: readonly ColumnSettingRow[],
  row: ColumnSettingRow,
): string[] {
  return rows
    .filter(
      entry =>
        // A summary-only row is shown and is not a column (D17-9): naming
        // it here would add the column nobody asked for, which is the very
        // thing its own checkbox exists to avoid.
        !entry.summaryOnly &&
        (entry.field === row.field ? !row.visible : entry.visible),
    )
    .map(entry => entry.field);
}
