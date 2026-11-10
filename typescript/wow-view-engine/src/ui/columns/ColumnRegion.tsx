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

/**
 * The column settings' area lists: what is held against the left edge and
 * what scrolls, each under its heading (`Region`), and inside each the
 * catalogue group's blocks (`Section`), every row a pin, a width, a summary
 * and a place in the order. `ColumnSettings` draws one `Region` per area.
 */

import { useId } from 'react';
import type {
  FieldGroupDefinition,
  SummaryFunction,
} from '../../model/index.js';
import type { ReleasedPins } from '../record/pinCap.js';
import { ColumnRow, SortableColumnRow } from './ColumnRow.js';
import { moveTarget, type HandleMove } from '../kit/DragHandle.js';
import {
  movableFields,
  movableIndex,
  type ColumnRegion,
  type ColumnSettingRow,
} from './rows.js';
import { columnSections } from './sections.js';
import { useSay, useViewMessages } from '../kit/MessagesProvider.js';

import type { ColumnSettingsTable } from './ColumnSettings.js';

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

/** What one area and one section inside it are both drawn from. */
interface ListProps {
  region: ColumnRegion;
  /** The rows on screen — what a search left of the area's own. */
  rows: readonly ColumnSettingRow[];
  /** Every row the panel knows, which is what places are counted over. */
  all: readonly ColumnSettingRow[];
  /** Whether the list is showing the order, and can therefore change it. */
  reorderable: boolean;
  table: ColumnSettingsTable;
  released: ReleasedPins;
  onMove(field: string, toIndex: number): void;
  /** Cycles one column's pinning, and says what that did. */
  onPin(field: string): void;
  /** Sets one column's width, and says what it came to. */
  onWidth(field: string, width: number | null): void;
}

/** One area's rows, or nothing at all when the area holds none. */
export function Region({
  groups,
  ...props
}: ListProps & { groups: readonly FieldGroupDefinition[] }) {
  const messages = useViewMessages();
  const headingId = useId();
  const sections = columnSections(props.rows, props.region, groups);
  if (sections.length === 0) return null;

  return (
    <div data-slot="column-region-group" className="fve:flex fve:flex-col">
      {/* Said, and now also drawn. The areas were `aria-label`s and nothing
          on the screen, so a column that had just been pinned did not read
          as held at the edge — it read as having jumped to the top of the
          list. One level under the popover's own title, and styled like the
          sidebar's group labels: a heading of a list, not of the panel. */}
      <h3
        id={headingId}
        data-slot="column-region-heading"
        className="fve:text-muted-foreground fve:px-2 fve:pt-2 fve:pb-1 fve:text-xs fve:font-medium"
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
  onWidth,
  group,
  regionHeadingId,
}: ListProps & {
  group: FieldGroupDefinition | undefined;
  regionHeadingId: string;
}) {
  const say = useSay();
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
          className="fve:text-muted-foreground fve:px-2 fve:pt-1.5 fve:pb-0.5 fve:pl-3 fve:text-xs"
        >
          {say(group.label)}
        </h4>
      )}
      <ul
        data-slot="column-region"
        data-region={region}
        data-group={group?.id}
        aria-labelledby={group === undefined ? regionHeadingId : labelId}
        className="fve:flex fve:flex-col"
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
            width: table.columns.find(column => column.field === row.field)
              ?.width,
            onWidth: (width: number | null) => onWidth(row.field, width),
            place: {
              index: movableIndex(all, row.field),
              total: movableFields(all, row.region).length,
            },
            onMove: (move: HandleMove) =>
              onMove(
                row.field,
                moveTarget(
                  move,
                  movableIndex(all, row.field),
                  movableFields(all, row.region).length,
                ),
              ),
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
