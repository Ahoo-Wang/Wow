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
import { TableHead } from '../components/table.js';
import {
  HEAD_CELL,
  NUMERIC_CELL,
  isNumeric,
  type ColumnPin,
} from './columns.js';

export interface SortableHeaderProps {
  column: RecordColumnView;
  /**
   * Every sorted column in priority order, which is what a header needs to
   * show its own place in the sort rather than only its direction.
   */
  sort: readonly RecordSort[];
  onToggle(field: string): void;
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
  pin,
}: SortableHeaderProps) {
  const messages = useViewMessages();
  const at = sort.findIndex(entry => entry.field === column.field);
  const direction = at < 0 ? null : sort[at].direction;
  const style = {
    ...(column.width ? { width: column.width } : {}),
    ...pin?.style,
  };
  const label = (
    <span data-slot="column-label" className="truncate">
      {column.label}
    </span>
  );
  // A numeric column reads from the right, header included, or the label
  // points at one edge while the digits under it point at the other.
  const numeric = isNumeric(column);
  const head = cn(HEAD_CELL, numeric && NUMERIC_CELL, pin?.className);

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
          'flex max-w-full cursor-pointer items-center gap-1',
          // The label keeps the column's edge; the marks follow it inward.
          numeric && 'ml-auto flex-row-reverse',
        )}
        onClick={() => onToggle(column.field)}
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
 */
function SortMark({ direction }: { direction: SortDirection | null }) {
  if (direction === 'ASC') return <ArrowUpIcon className="size-3.5" />;
  if (direction === 'DESC') return <ArrowDownIcon className="size-3.5" />;
  return (
    <ArrowUpDownIcon
      data-slot="sort-available"
      className="text-muted-foreground/60 size-3.5"
    />
  );
}
