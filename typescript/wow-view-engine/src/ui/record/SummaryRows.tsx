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

import { cn } from 'cn';
import type {
  RecordColumnView,
  SummaryCell,
  SummaryRow,
} from '../../record/index.js';
import { formatNumber } from '../display.js';
import { useViewMessages } from '../MessagesProvider.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { TooltipContent } from '../popups.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { TableCell, TableFooter, TableRow } from '../components/table.js';
import {
  actionCell,
  CLIPPED_CELL,
  SELECT_CELL,
  columnWidth,
  type TablePins,
} from './columns.js';
import { FillerCell } from './Filler.js';
import { TEXT_UI } from '../layout.js';

/**
 * The quiet half of a summary row — the scope, and each function's name.
 *
 * The colour is `--quiet-foreground` in `styles.css`, where the reason it
 * is a dimmed foreground rather than `muted-foreground` is written down and
 * where a host can move it: small grey-on-grey text is exactly where a
 * palette meant for one background fails on another.
 */
const QUIET = `text-quiet-foreground block font-normal ${TEXT_UI}`;

export interface SummaryRowsProps {
  /**
   * One row per scope, in reading order: the rows on screen first, then
   * everything the conditions match. One alone is the page, which is what is
   * left when the totals query fails.
   */
  rows: readonly SummaryRow[];
  columns: readonly RecordColumnView[];
  selectable: boolean;
  /** Whether the rows carry an action column the footer has to match. */
  actions: boolean;
  /** What is held against the edges, the cap's answer included. */
  pins: TablePins;
}

/**
 * The summary rows: this page, and every record the conditions match.
 *
 * Their scope is part of the number, not a detail. A `total` comes from its
 * own aggregation over everything the conditions select, while a `page` is
 * the visible rows added up; an average over twenty rows presented as the
 * average over forty thousand is the one mistake these rows could make, so
 * each says which it is in a label cell of its own rather than sharing one.
 * They are shown together because the answer to "is this page representative"
 * is the other row, and putting them side by side is the whole point.
 */
export function SummaryRows({
  rows,
  columns,
  selectable,
  actions,
  pins,
}: SummaryRowsProps) {
  return (
    // A layer of its own: opaque muted, so the summaries separate from the
    // rows above without looking like two more records, and pinned to the
    // foot of the scroll area — the numbers under a long page are the reason
    // the page is being read, and the sideways scrollbar belongs under them
    // rather than between them and the rows.
    <TableFooter
      data-slot="record-summaries"
      className="bg-muted sticky bottom-0 z-20"
    >
      {rows.map(row => (
        <SummaryLine
          key={row.scope}
          row={row}
          columns={columns}
          selectable={selectable}
          actions={actions}
          pins={pins}
        />
      ))}
    </TableFooter>
  );
}

function SummaryLine({
  row,
  columns,
  selectable,
  actions,
  pins,
}: Omit<SummaryRowsProps, 'rows'> & { row: SummaryRow }) {
  const messages = useViewMessages();
  // A field may carry several functions — `amount` summed and averaged — and
  // the kernel projects a cell for each, so they are grouped rather than
  // keyed, which would keep only the last one configured.
  const byField = new Map<string, SummaryCell[]>();
  for (const cell of row.cells)
    byField.set(cell.field, [...(byField.get(cell.field) ?? []), cell]);

  // The scope rides in the selection column when there is one; without it
  // there is no spare cell, so it labels the first column from above rather
  // than taking a column's place and hiding whatever that column summarises.
  // A table with no columns has nothing to summarise on screen either, so the
  // row is simply empty and `data-scope` carries the scope on its own.
  const scope = (
    <span
      data-slot="summary-scope"
      // A label for the line rather than a number in it: quiet, small and
      // unemphasised where the cells beside it are the footer's own medium.
      // Quiet is a dimmed foreground rather than `muted-foreground`, which
      // is toned for the background and misses 4.5:1 on the muted layer.
      className={QUIET}
    >
      {messages.label(`label.summary.scope.${row.scope}`)}
    </span>
  );

  return (
    <TableRow
      data-scope={row.scope}
      // The muted layer is the row's own colour, and a pinned cell inherits
      // it: `bg-inherit` over a transparent row would let the columns it is
      // pinned over show through it.
      className="bg-muted hover:bg-muted"
    >
      {selectable && (
        <TableCell className={cn(pins.select && SELECT_CELL)}>
          {scope}
        </TableCell>
      )}
      {columns.map((column, index) => {
        const pin = pins.columns.get(column.field);
        return (
          <TableCell
            key={column.field}
            // The footer is one of the rows a column's width has to hold
            // against: a summary wider than the width the user set would
            // push the column back out from underneath the rows.
            className={cn(
              column.width !== undefined && CLIPPED_CELL,
              pin?.className,
            )}
            style={{ ...columnWidth(column), ...pin?.style }}
          >
            {!selectable && index === 0 && scope}
            {(byField.get(column.field) ?? []).map(cell => (
              <SummaryValue key={cell.fn} cell={cell} />
            ))}
          </TableCell>
        );
      })}
      {/* Nothing to summarise about actions, but the row still has to be
          as wide as the ones above it. */}
      {actions && <TableCell className={actionCell(pins)} />}
      {/* And the same last cell every other row carries, so the muted
          footer band ends where the rows above it end. */}
      <FillerCell />
    </TableRow>
  );
}

/**
 * One number under its column: the function it came from, then the value,
 * both ending at the column's right edge where the digits line up with the
 * cells above. The function is named rather than coded — `SUM` is a token
 * from the config, and a reader is owed a word.
 */
export function SummaryValue({ cell }: { cell: SummaryCell }) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // Which field this number is of, and by which function — the heading says
  // the field, several rows above, and nothing on screen says the pair. A
  // `Tooltip` rather than the native `title` this used to be (D16): `title`
  // is the one affordance a touch user never has.
  const of = messages.label('label.summary.of', {
    fn: messages.label(`label.summary.fn.${cell.fn}`),
    field: cell.label,
  });
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <span className="flex items-baseline justify-end gap-1 whitespace-nowrap" />
        }
      >
        <span className={QUIET}>
          {messages.label(`label.summary.fn.${cell.fn}`)}
        </span>
        <span data-slot="summary-value" className="tabular-nums">
          {cell.value === null
            ? messages.label('label.summary.unavailable')
            : formatNumber(cell.value, cell.numberFormat, display.locale)}
        </span>
      </TooltipTrigger>
      <TooltipContent>{of}</TooltipContent>
    </Tooltip>
  );
}
