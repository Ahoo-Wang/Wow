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

import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon } from 'lucide-react';
import type { RecordSort, SortDirection } from '../../model/index.js';
import type { RecordColumnView } from '../../record/index.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { FOCUS_RING } from '../layout.js';
import { TableHead } from '../components/table.js';
import {
  HEAD_CELL,
  NUMERIC_CELL,
  columnWidth,
  isNumeric,
  type ColumnPin,
} from './columns.js';
import { ColumnResizer } from './ColumnResizer.js';

export interface SortableHeaderProps {
  column: RecordColumnView;
  /**
   * Every sorted column in priority order, which is what a header needs to
   * show its own place in the sort rather than only its direction.
   */
  sort: readonly RecordSort[];
  /**
   * Cycles a column's sort. A plain click is `exclusive` — order the rows
   * by this column alone; Shift, Ctrl or ⌘ held makes it join the others.
   */
  onToggle(field: string, options?: { exclusive?: boolean }): void;
  /**
   * Commits a width in pixels, or `null` to let the column size itself
   * again. Left out, the header's edge is a line rather than a handle — an
   * embedded table whose host has no controller to write to.
   */
  onResize?(field: string, width: number | null): void;
  pin?: ColumnPin;
}

/**
 * One column header, and the whole of the sorting interaction.
 *
 * Three things are said rather than drawn. `aria-sort` on the cell is how a
 * table says which column it is ordered by, and it goes on the column that
 * decides that order and on no other. The button's name is what the *next*
 * click does — ascending, then descending, then off — because a control is
 * named by its action, and the column's own label is inside that sentence, so
 * what is heard still contains what is seen. And a column that could be
 * sorted but is not wears a neutral mark, so the affordance does not appear
 * only after it is used.
 *
 * A second sorted column joins the first rather than replacing it (see
 * `useRecordTable.toggleSort`), so while more than one is sorted each header
 * also carries its position, drawn as a number and spoken in its name: two
 * arrows say what the table is ordered by but not in which order, and ARIA
 * has one attribute where this has several columns.
 */
export function SortableHeader({
  column,
  sort,
  onToggle,
  onResize,
  pin,
}: SortableHeaderProps) {
  const messages = useViewMessages();
  const at = sort.findIndex(entry => entry.field === column.field);
  const direction = at < 0 ? null : sort[at].direction;
  const style = { ...columnWidth(column), ...pin?.style };
  // The handle is placed against the cell's own right edge, so the cell has
  // to be the positioned ancestor. A pinned header already is one — `sticky`
  // positions it — and two `position` classes on one element is a race
  // between stylesheet rules rather than a choice.
  const resizer = onResize && (
    <ColumnResizer column={column} onResize={onResize} />
  );
  const label = (
    <span data-slot="column-label" className="truncate">
      {column.label}
    </span>
  );
  // A numeric column reads from the right, header included, or the label
  // points at one edge while the digits under it point at the other.
  const numeric = isNumeric(column);
  const head = cn(
    HEAD_CELL,
    numeric && NUMERIC_CELL,
    pin?.className ?? 'relative',
  );

  // The header is what the pinned offsets are measured from, so a pinned
  // column says so on its header cell and names the offset it owns.
  const pinned = { 'data-pin': pin?.side, 'data-pin-index': pin?.index };

  if (!column.sortable)
    return (
      <TableHead
        data-field={column.field}
        {...pinned}
        className={head}
        style={style}
      >
        {label}
        {resizer}
      </TableHead>
    );

  // What the next click will do, which is also what the button is called.
  const action =
    direction === null
      ? 'ascending'
      : direction === 'ASC'
        ? 'descending'
        : 'none';
  const name = messages.label(`label.sort.${action}`, { field: column.label });
  // A position is only meaningful against another one.
  const position = sort.length > 1 && at >= 0 ? at + 1 : null;

  return (
    <TableHead
      data-field={column.field}
      {...pinned}
      aria-sort={ariaSort(direction, at === 0)}
      className={head}
      style={style}
    >
      <button
        type="button"
        aria-label={
          position === null
            ? name
            : `${name} · ${messages.label('label.sort.at', {
                position,
                count: sort.length,
              })}`
        }
        className={cn(
          'flex max-w-full cursor-pointer items-center gap-1 rounded-sm',
          FOCUS_RING,
          // The label keeps the column's edge; the marks follow it inward.
          numeric && 'ml-auto flex-row-reverse',
        )}
        // A plain click sorts by this column alone — what a table header
        // means everywhere else — and a modifier adds the column to the sort
        // instead, the other way round from the additive default of
        // `toggleSort`. Keyboard: Shift+Enter or Shift+Space. Browsers put
        // the held modifiers on a button's activation click, but not every
        // environment does, so the keys are read where they are pressed and
        // the activation that would follow is stood down.
        aria-description={messages.label('label.sort.additive')}
        onClick={event =>
          onToggle(column.field, { exclusive: !additive(event) })
        }
        onKeyDown={event => {
          if (!additive(event)) return;
          if (event.key !== 'Enter' && event.key !== ' ') return;
          event.preventDefault();
          onToggle(column.field, { exclusive: false });
        }}
        onKeyUp={event => {
          // Space activates on release; the press above already answered.
          if (event.key === ' ' && additive(event)) event.preventDefault();
        }}
      >
        {label}
        <SortMark direction={direction} />
        {position !== null && (
          <span
            aria-hidden="true"
            data-slot="sort-position"
            className="text-muted-foreground text-[0.625rem] leading-none tabular-nums"
          >
            {position}
          </span>
        )}
      </button>
      {resizer}
    </TableHead>
  );
}

/**
 * ARIA's word for the direction, and only for the column that decides the
 * order.
 *
 * A table is "sorted by" one column as far as these semantics go: marking
 * every sortable header made the unsorted ones announce `none` each in turn,
 * and a two-level sort announce two columns as ascending with nothing to say
 * which is consulted first. The rest of the order is in the button's name,
 * where it can be said in words.
 */
function ariaSort(
  direction: SortDirection | null,
  primary: boolean,
): 'ascending' | 'descending' | undefined {
  if (!primary) return undefined;
  return direction === 'ASC' ? 'ascending' : 'descending';
}

/**
 * The arrow, or the neutral pair a sortable column wears until it is used:
 * an affordance that only appears once discovered is not an affordance.
 *
 * **One mark, and it is always drawn.** One, because a header says one
 * thing — which way this column orders the table — and a second glyph
 * saying "sortable" beside an arrow already saying which way is the same
 * sentence twice; that was `↕ 金额 ↓` on the bar's own sort button, and it
 * is not repeated here. Always, because the alternative considered was to
 * fade the neutral `↕` in on hover or focus: that hides the affordance from
 * a touch screen entirely, makes the header row shift by 18px under the
 * pointer, and contradicts the line above, which is a decision this package
 * already made rather than a preference. The mark is `muted-foreground/60`
 * instead — quiet enough not to compete with the column's name, present
 * enough to be found without moving the pointer.
 */
function SortMark({ direction }: { direction: SortDirection | null }) {
  if (direction === null)
    return (
      <ArrowUpDownIcon
        data-slot="sort-available"
        className="text-muted-foreground/60 size-3.5"
      />
    );
  const Arrow = direction === 'ASC' ? ArrowUpIcon : ArrowDownIcon;
  return <Arrow data-slot="sort-direction" className="size-3.5" />;
}

/** Whether the modifier that turns a click into "also sort by this" is held. */
function additive(event: {
  shiftKey: boolean;
  metaKey: boolean;
  ctrlKey: boolean;
}): boolean {
  return event.shiftKey || event.metaKey || event.ctrlKey;
}
