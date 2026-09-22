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
import { Button } from '../components/button.js';
import { TableHead } from '../components/table.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { TooltipContent } from '../popups.js';
import { HEAD_CELL, NUMERIC_CELL, columnWidth, isNumeric } from './columns.js';
import { OWN_LAYER, stickyHead, type StickyPin } from './sticky.js';
import { ColumnResizer } from './ColumnResizer.js';
import { useRovingHeader } from './headerRoving.js';

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
  pin?: StickyPin;
  /**
   * The id of the sentence saying how to add a column to the sort rather
   * than replace it. One sentence serves the whole header row, so the table
   * writes it once and every sortable header points at it; a column that
   * cannot be sorted has no button and points at nothing.
   */
  additiveId: string;
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
  additiveId,
}: SortableHeaderProps) {
  const messages = useViewMessages();
  // One stop for the whole header row, and Alt+←/→ on whichever column it is
  // standing on. The item is this header's own focusable element — the sort
  // button where there is one, the cell itself where the column cannot be
  // sorted, so every column is reachable by the same arrows.
  const {
    attach,
    item: stop,
    onKeyDown: rove,
  } = useRovingHeader({ ...(onResize ? { onResize } : {}) });
  const at = sort.findIndex(entry => entry.field === column.field);
  const direction = at < 0 ? null : sort[at].direction;
  // The handle is placed against the cell's own right edge, so the cell has
  // to be the positioned ancestor. A pinned header already is one — `sticky`
  // positions it — and two `position` classes on one element is a race
  // between stylesheet rules rather than a choice, which is what
  // `OWN_LAYER` is the alternative to.
  const resizer = onResize && (
    <ColumnResizer column={column} onResize={onResize} />
  );
  // `truncate` is visual only — a reader still hears the whole name, but a
  // pointer or touch user has no way back to it once the ellipsis lands, and
  // a column called «订..» is a column with no name at all. The whole of it is
  // one hover away: a `Tooltip` and not the native `title` (D16-6), which
  // opens for a mouse and for nothing else. The trigger renders the same span
  // — nothing is wrapped around it — so the cell's layout and the order of
  // its slots are untouched.
  const label = (
    <Tooltip>
      <TooltipTrigger
        render={<span data-slot="column-label" className="truncate" />}
      >
        {column.label}
      </TooltipTrigger>
      <TooltipContent>{column.label}</TooltipContent>
    </Tooltip>
  );
  // A numeric column reads from the right, header included, or the label
  // points at one edge while the digits under it point at the other.
  const numeric = isNumeric(column);
  // The one home of the sticky chrome (`sticky.ts`): the freeze, the edge,
  // the measured offset and the `data-pin*` attributes the offsets and the
  // cap are read back through, all from the pin this header was handed.
  const head = stickyHead(pin, {
    className: cn(
      HEAD_CELL,
      numeric && NUMERIC_CELL,
      pin === undefined && OWN_LAYER,
    ),
    style: columnWidth(column),
  });

  if (!column.sortable)
    return (
      <TableHead
        data-field={column.field}
        {...head}
        // A column with nothing to press is still a column to walk to and
        // still a column to widen, so the cell is the group's item here.
        ref={attach}
        {...stop}
        onKeyDown={rove}
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
      {...head}
      aria-sort={ariaSort(direction, at === 0)}
    >
      <Button
        type="button"
        variant="ghost"
        size="sm"
        ref={attach}
        {...stop}
        aria-label={
          position === null
            ? name
            : `${name} · ${messages.label('label.sort.at', {
                position,
                count: sort.length,
              })}`
        }
        className={cn(
          // The button's own horizontal padding is pulled straight back out —
          // by exactly the cell's `px-2`, not a pixel more. A header cell
          // already pads its content, and a control that pads it again
          // starts the column's name inside the values under it — a column
          // that does not line up with itself. Pulling out more than the
          // cell gives (`-mx-2.5` once did) made the last button overhang
          // its cell by 2px, and a table that fits its port then scrolled
          // by those 2px: a scrollbar on every table, and the pin cap
          // (D17-4) reading a fitting table as one that overflows. What the
          // padding buys is kept: the ghost hover fill reaches past the
          // label rather than hugging it.
          '-mx-2 max-w-full',
          // The label keeps the column's edge; the marks follow it inward.
          numeric && 'ml-auto flex-row-reverse',
        )}
        // A plain click sorts by this column alone — what a table header
        // means everywhere else — and a modifier adds the column to the sort
        // instead, the other way round from the additive default of
        // `toggleSort`. Keyboard: Shift+Enter or Shift+Space. Browsers put
        // the held modifiers on a button's activation click, but not every
        // environment does, so the keys are read where they are pressed and
        // the activation that would follow is stood down. The rule is said
        // as a description because nothing on screen says it, and through
        // `aria-describedby` because the draft `aria-description` reaches
        // Chromium's readers and nobody else's.
        aria-describedby={additiveId}
        onClick={event =>
          onToggle(column.field, { exclusive: !additive(event) })
        }
        onKeyDown={event => {
          // The row's own keys first — walking to the next column and
          // widening this one are answered before anything about sorting,
          // and neither is a key this button would otherwise use.
          if (rove(event)) return;
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
      </Button>
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
      // No size class: the button around it is the vendored `Button` at
      // `sm`, which sizes the icons inside it — to the same 3.5 this used
      // to ask for by hand.
      <ArrowUpDownIcon
        data-slot="sort-available"
        className="text-muted-foreground/60"
      />
    );
  const Arrow = direction === 'ASC' ? ArrowUpIcon : ArrowDownIcon;
  return <Arrow data-slot="sort-direction" />;
}

/** Whether the modifier that turns a click into "also sort by this" is held. */
function additive(event: {
  shiftKey: boolean;
  metaKey: boolean;
  ctrlKey: boolean;
}): boolean {
  return event.shiftKey || event.metaKey || event.ctrlKey;
}
