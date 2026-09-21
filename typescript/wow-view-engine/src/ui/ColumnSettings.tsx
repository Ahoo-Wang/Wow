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

import { useCallback, useId, useMemo } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { Accessibility } from '@dnd-kit/dom';
import { Columns3Icon } from 'lucide-react';
import {
  columnPin,
  type FieldDefinition,
  type SummaryFunction,
} from '../model/index.js';
import type { RecordTableController } from '../react/index.js';
import { Button } from './components/button.js';
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
import { ColumnRow, SortableColumnRow } from './columns/ColumnRow.js';
import { columnDragAccessibility } from './columns/announce.js';
import {
  ACTIONS_COLUMN,
  columnSettingRows,
  movableIndex,
  nextPin,
  regionRows,
  renderedCount,
  renderedIndex,
  reorderColumns,
  REGIONS,
  type ColumnRegion,
  type ColumnSettingRow,
} from './columns/rows.js';
import { useViewMessages } from './MessagesProvider.js';
import { ToolbarItem } from './toolbar.js';
import { useAnnouncer } from './Announcer.js';

/**
 * One sortable group per area, so a drag cannot cross one: what is held on
 * the left, what scrolls and what is held on the right are three lists, and
 * a column joins another of them by being pinned rather than by being
 * dragged there.
 */
const GROUP: Record<ColumnRegion, string> = {
  left: 'columns-left',
  middle: 'columns-middle',
  right: 'columns-right',
};

/**
 * Each area's heading — which is also its accessible name, because they are
 * the same node.
 *
 * The three words are the three pin states, the same ones a row's own pin
 * control cycles through (`nextPin`): an area *is* a pinning, so naming it
 * with that word makes the list and the control say the same thing. The
 * middle one used to be `label.columns.title` — the popover's own name — and
 * as an invisible `aria-label` that only went unnoticed; said out loud under
 * a heading already reading "Column settings" it would be the title twice
 * and the area not at all.
 */
const REGION_LABEL = {
  left: 'label.columns.pin.left',
  middle: 'label.columns.pin.none',
  right: 'label.columns.pin.right',
} as const;

export interface ColumnSettingsProps {
  table: RecordTableController;
  /** The fields the definition offers, in its order. */
  fields: readonly FieldDefinition[];
  /** The field holding each row's identity, when the definition declares one. */
  rowKey?: string;
  /** Whether the table carries the host's action column. */
  actions?: boolean;
  /** Pins the table's cap is not drawing right now (D17-4); see `ColumnRow`. */
  released?: ReleasedPins;
}

/**
 * Which columns the table shows, in which order, pinned where, summarised
 * how.
 *
 * All four are one question — "what does a row look like" — and they were
 * three controls in three places before this: a checklist for visibility,
 * the config for the order, and nothing at all for pinning or summaries. A
 * column keeps to its area: the row key is held on the left, the host's
 * actions on the right, and what is between them orders freely.
 */
export function ColumnSettings({
  table,
  fields,
  rowKey,
  actions = false,
  released = NO_RELEASE,
}: ColumnSettingsProps) {
  const messages = useViewMessages();
  const { say: announce, region: announcement } = useAnnouncer(
    'column-announcement',
  );

  const rows = useMemo(
    () =>
      columnSettingRows({
        fields,
        columns: table.columnFields,
        ...(rowKey === undefined ? {} : { rowKey }),
        actions,
        summaryFields: table.summaryFields,
        pinnedOf: table.pinnedOf,
        hiddenOf: table.hiddenOf,
        summaryOf: table.summaryOf,
      }),
    [
      actions,
      fields,
      rowKey,
      table.columnFields,
      table.hiddenOf,
      table.pinnedOf,
      table.summaryFields,
      table.summaryOf,
    ],
  );
  // What the reader is looking at: the columns the table actually draws —
  // the action column included, a broken one not, since `projectRecord`
  // leaves that out.
  const onScreen = renderedCount(rows);
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
          field: labelOf(rows, field),
          index: renderedIndex(reordered(rows, order), field),
          total: onScreen,
        }),
      );
    },
    [announce, messages, onScreen, rows, table],
  );

  return (
    <Popover>
      {/* A bordered icon button with the word in its name and its tooltip
          (D12 Ⅳ): the control reports no state, so it carries no text. */}
      <Tooltip>
        <TooltipTrigger
          render={
            // A toolbar item where a toolbar is around it, an ordinary
            // button anywhere else: the bar owns the roving focus order and
            // this is one of the stops in it.
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
      <PopoverContent align="end" className="w-96">
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

        <DragDropProvider
          plugins={defaults =>
            defaults.map(plugin =>
              plugin === Accessibility
                ? Accessibility.configure(
                    columnDragAccessibility(messages, field =>
                      labelOf(rows, field),
                    ),
                  )
                : plugin,
            )
          }
          onDragEnd={({ operation, canceled }) => {
            const { source, target } = operation;
            if (canceled || !source || !target || source.id === target.id)
              return;
            moveTo(String(source.id), movableIndex(rows, String(target.id)));
          }}
        >
          {REGIONS.map(region => (
            <Region
              key={region}
              region={region}
              rows={rows}
              table={table}
              released={released}
              onMove={moveTo}
            />
          ))}
        </DragDropProvider>

        {/* One voice for a move the user asked for with the arrow keys; the
            library announces its own pick-up and cancel. */}
        {announcement}
      </PopoverContent>
    </Popover>
  );
}

/** One area's rows, or nothing at all when the area holds none. */
function Region({
  region,
  rows,
  table,
  released,
  onMove,
}: {
  region: ColumnRegion;
  rows: readonly ColumnSettingRow[];
  table: RecordTableController;
  released: ReleasedPins;
  onMove(field: string, toIndex: number): void;
}) {
  const messages = useViewMessages();
  const headingId = useId();
  const own = regionRows(rows, region);
  if (own.length === 0) return null;

  return (
    <div data-slot="column-region-group" className="flex flex-col">
      {/* Said, and now also drawn. The areas were three `aria-label`s and
          nothing on the screen, so a column pinned right did not read as
          held at the edge — it read as having fallen to the bottom of the
          list. One level under the popover's own title, and styled like the
          sidebar's group labels: a heading of a list, not of the panel. */}
      <h3
        id={headingId}
        data-slot="column-region-heading"
        className="text-muted-foreground px-2 pt-2 pb-1 text-xs font-medium"
      >
        {messages.label(REGION_LABEL[region])}
      </h3>
      <ul
        data-slot="column-region"
        data-region={region}
        aria-labelledby={headingId}
        className="flex flex-col"
      >
        {own.map(row => {
          const shared = {
            row,
            // A row that is only a summary is not in the column list, so
            // its checkbox writes the summary away and leaves the columns
            // exactly as they are (D17-9).
            onToggle: () =>
              row.summaryOnly
                ? table.setSummary(row.field, null)
                : table.setColumns(toggled(rows, row)),
            onPin: () =>
              table.setPinned(row.field, nextPin(columnPin(row.pinned))),
            // The action column is the host's, not a field: the cap reports
            // it on its own flag, and its row is marked like any other.
            released:
              row.field === ACTIONS_COLUMN
                ? released.actions
                : released.fields.has(row.field),
            onSummary: (fn: SummaryFunction | null) =>
              table.setSummary(row.field, fn),
            onMove: (step: -1 | 1) =>
              onMove(row.field, movableIndex(rows, row.field) + step),
          };
          return row.movable ? (
            <SortableColumnRow
              key={row.field}
              {...shared}
              index={movableIndex(rows, row.field)}
              group={GROUP[row.region]}
            />
          ) : (
            <ColumnRow key={row.field} {...shared} />
          );
        })}
      </ul>
    </div>
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
        entry.field !== ACTIONS_COLUMN &&
        // A summary-only row is shown and is not a column (D17-9): naming
        // it here would add the column nobody asked for, which is the very
        // thing its own checkbox exists to avoid.
        !entry.summaryOnly &&
        (entry.field === row.field ? !row.visible : entry.visible),
    )
    .map(entry => entry.field);
}

function labelOf(rows: readonly ColumnSettingRow[], field: string): string {
  return rows.find(row => row.field === field)?.label ?? field;
}
