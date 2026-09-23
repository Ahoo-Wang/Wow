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

import { useId, useLayoutEffect, useRef } from 'react';
import { cn } from 'cn';
import { bandText } from './band.js';
import { columnTitle, displayValue, valueText } from './display.js';
import { useViewMessages } from './MessagesProvider.js';
import { moveStop, settleStop, takeStop } from './roving.js';
import { FOCUS_ROW } from './variants.js';
import { TEXT_UI } from './layout.js';
import {
  CLIPPED_CELL,
  IDENTIFIER_FACE,
  NUMERIC_CELL,
  columnWidth,
  isNumeric,
} from './record/columns.js';
import { FILLER_COLUMN, FillerCell, FillerHead } from './record/Filler.js';
import { useRoomBelowRows } from './record/roomBelowRows.js';
import { SortableHeader } from './record/SortableHeader.js';
import { stickyBand } from './record/sticky.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import type { AnalysisView } from '../analysis/index.js';
import type { RecordSort } from '../model/index.js';
import { AnalysisEmpty } from './analysis/EmptyResult.js';
import type { HeaderSorting } from './analysis/headerSort.js';
import {
  headerColumnOf,
  isIdentifier,
  useHeldWidths,
} from './analysis/tableColumns.js';
import type { OnPick } from './charts/family.js';
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHeader,
  TableRow,
} from './components/table.js';

export interface AnalysisTableProps {
  view: AnalysisView;
  /**
   * Makes the rows pressable: a row opens the follow-up menu on the group
   * it is. This is the keyboard's path to the menu the chart's marks open
   * with a pointer (F10): a row takes focus and opens on Enter or Space.
   */
  onPick?: OnPick;
  /**
   * Makes the headers sort the groups: a press orders them by that column,
   * ascending, then descending, then back to the view's own order, and the
   * question runs again (`useHeaderSort`). Left out — a dashboard panel, an
   * embedded view — the headers name their columns and nothing more.
   */
  sorting?: HeaderSorting;
}

/** What marks a row as a member of the result's one Tab stop. */
const ROW = 'tr[data-pickable]';

const NO_SORT: readonly RecordSort[] = [];

/**
 * The aggregation as a table: groups first, then metrics, with the totals row
 * from its own ungrouped query rather than from summing what is on screen.
 *
 * It reads as the record table does, from the same recipes: a number on the
 * right edge in tabular figures, header included (`NUMERIC_CELL`); an id in
 * the monospace the record view's copyable reading wears (`IDENTIFIER_FACE`);
 * each header the record table's `SortableHeader`; each column at a width
 * held on its cells (`columnWidth`), the surplus in the aria-hidden filler
 * (`record/Filler.tsx`). The widths come from the columns and never from the
 * rows (`analysis/tableColumns.ts`), so a question asked again with other
 * answers leaves every column where it was.
 */
export function AnalysisTable({ view, onPick, sorting }: AnalysisTableProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // `aria-description` is a draft attribute Chromium alone implements, so
  // the sentence it carried reached one browser and no other. The same
  // sentence is a span only a reader meets, addressed by `aria-describedby`
  // — which every reader has.
  const ids = useId();
  const approximateId = `${ids}-approximate`;
  const additiveId = `${ids}-additive`;
  /**
   * The rows are peers, and a hundred peers are one Tab stop (A9).
   *
   * A pressable row used to be `tabIndex={0}`, so a result of a hundred
   * groups stood a hundred stops between the toolbar and whatever follows
   * the table, and a keyboard leaving the result had to walk every group it
   * had already read. They are a column of the same kind of thing, which is
   * what a roving tabindex is for (`roving.ts`, and the header row above
   * them, one stop of its own): ↑/↓ between the rows, Home/End to the ends,
   * Enter and Space opening the follow-up menu exactly as a press does.
   */
  const body = useRef<HTMLTableSectionElement | null>(null);
  const rows = () => [
    ...(body.current?.querySelectorAll<HTMLElement>(ROW) ?? []),
  ];
  // No dependency list: a result that lands is a new set of rows, and the
  // stop has to be on one of *these*.
  useLayoutEffect(() => {
    settleStop(rows());
  });
  // The totals row sits at the bottom of the result as the record view's
  // summaries do (the user's 2026-09-23 review): the table is its own
  // scroll port, the header and the totals are its two sticky bands, and
  // the room the rows leave in a taller port is a row that draws nothing.
  const port = useRef<HTMLDivElement | null>(null);
  const table = useRef<HTMLTableElement | null>(null);
  const room = useRoomBelowRows(port, table, view.totals !== undefined);
  // A number band reads as the band it is; a group key or an ANY shows as
  // its field's values do; the rest, and anything the field's kind has
  // nothing to say about, as before.
  const show = (value: unknown, column: AnalysisView['columns'][number]) =>
    bandText(value, column, messages, display) ??
    displayValue(value, column, display) ??
    valueText(value, messages, column.numberFormat, display.locale);
  const titled = view.columns.map(column => ({
    column,
    title: columnTitle(column, messages),
    // Read only for a column not yet sized: what it is first drawn with.
    values: () =>
      [...view.rows, ...(view.totals ? [view.totals] : [])].map(row =>
        show(row[column.alias], column),
      ),
  }));
  const widths = useHeldWidths(titled);
  if (view.rows.length === 0) return <AnalysisEmpty />;

  const columns = titled.map(({ column, title }, index) => {
    const head = headerColumnOf(
      column,
      title,
      widths[index] ?? 0,
      sorting !== undefined,
    );
    return {
      column,
      head,
      // What every cell of the column wears: its width, held (a width on its
      // own is a suggestion an automatic layout overrides), cut with an
      // ellipsis where a value is longer, and the right edge for numbers.
      wears: {
        className: cn(CLIPPED_CELL, isNumeric(head) && NUMERIC_CELL),
        style: columnWidth(head),
        'data-numeric': isNumeric(head) ? '' : undefined,
      },
    };
  });
  type Column = (typeof columns)[number];
  // One value as a cell draws it: the text, in an id's monospace where the
  // field is one, and whole in the `title` for when the width cut it — as
  // the record table's clipped cells do.
  const cell = (value: unknown, { column, wears }: Column) => {
    const text = show(value, column);
    return (
      <TableCell key={column.alias} {...wears} title={text || undefined}>
        {isIdentifier(column) && text !== '' ? (
          <span data-slot="identifier" className={IDENTIFIER_FACE}>
            {text}
          </span>
        ) : (
          text
        )}
      </TableCell>
    );
  };
  const sort = sorting
    ? sorting.sort.map(entry => ({
        field: entry.alias,
        direction: entry.direction,
      }))
    : NO_SORT;

  return (
    // Its own scroll port, as `RecordTable` is: the vendored `Table`'s
    // container (`overflow-x-auto`) is taken out of the way, because two
    // nested scrollports put the sticky header and totals against the inner
    // one, which never scrolls up and down. In a workbench this port takes
    // the height the column leaves it (`styles.css`); anywhere else it is
    // as tall as its rows and scrolls only sideways. A port with no row to
    // focus is a stop of its own, or a keyboard could not scroll it.
    <div
      ref={port}
      data-slot="analysis-table"
      tabIndex={onPick ? undefined : 0}
      className="relative overflow-auto [&>[data-slot=table-container]]:overflow-visible"
    >
      <Table ref={table}>
        <TableHeader {...stickyBand('top')}>
          <TableRow>
            {columns.map(({ column, head }) => (
              <SortableHeader
                key={column.alias}
                column={head}
                sort={sort}
                onToggle={(alias, options) => sorting?.onToggle(alias, options)}
                additiveId={additiveId}
                // A percentile's 「≈」 is a sign; this is the word behind it.
                // Wow computes percentiles approximately, and nothing else on
                // the row says so (D20 口径).
                {...(column.fn === 'PERCENTILE'
                  ? {
                      note: {
                        id: approximateId,
                        text: messages.label('label.analysis.approximate'),
                      },
                    }
                  : {})}
              />
            ))}
            <FillerHead />
          </TableRow>
        </TableHeader>
        <TableBody ref={body}>
          {view.rows.map((row, index) => (
            <TableRow
              key={index}
              data-pickable={onPick ? '' : undefined}
              aria-haspopup={onPick ? 'menu' : undefined}
              className={cn(onPick && 'cursor-pointer', FOCUS_ROW)}
              // The menu hangs from the cell pressed, or from the row's first
              // cell for a key, never from the row: a menu anchored to a row
              // takes the row's width, and an analysis row is the width of
              // the whole table (the popup's recipe is `--anchor-width`). The
              // row stays where the keyboard goes back to.
              onClick={
                onPick
                  ? event =>
                      onPick(
                        row,
                        cellOf(event.target, event.currentTarget),
                        event.currentTarget,
                      )
                  : undefined
              }
              // The stop follows the keyboard: a row focused is the row the
              // group's one stop is on, however focus got there — an arrow,
              // a press, or the menu handing it back when it closes.
              onFocus={
                onPick
                  ? event => takeStop(event.currentTarget, rows())
                  : undefined
              }
              onKeyDown={
                onPick
                  ? event => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onPick(
                          row,
                          event.currentTarget.cells[0] ?? event.currentTarget,
                          event.currentTarget,
                        );
                        return;
                      }
                      moveStop(event, rows(), 'column');
                    }
                  : undefined
              }
            >
              {columns.map(entry => cell(row[entry.column.alias], entry))}
              <FillerCell />
            </TableRow>
          ))}
        </TableBody>
        {room > 0 && (
          <tbody data-slot="row-room" aria-hidden>
            <tr>
              <td
                colSpan={columns.length + 1}
                style={{ height: room, padding: 0, border: 0 }}
              />
            </tr>
          </tbody>
        )}
        {view.totals && (
          <TableFooter {...stickyBand('bottom')}>
            <TableRow data-slot="totals-row">
              {columns.map((entry, index) =>
                index === 0 && entry.column.role === 'group' ? (
                  <TableCell
                    key={entry.column.alias}
                    data-slot="totals-heading"
                    {...entry.wears}
                  >
                    {messages.label('label.summary.total')}
                    {/* The word 「合计」 alone invites "the rows above, added
                        up", and the two numbers disagree whenever anything
                        was left out — groups past the first N, groups
                        「只保留」 dropped, records with no value. So the scope
                        is said where the word is, and seen: it was a `title`
                        and a described-by span, which a pointer had to find
                        and a keyboard never could. A line of its own under
                        the word, at the one small size and the plain weight
                        where the row is medium, as the record view's summary
                        rows name their scope — but in the row's own ink
                        rather than their `--quiet-foreground`: this result
                        fades to 60% while a changed question is about to run
                        (`data-stale`), and quiet ink faded measured 2.73:1.
                        Text, so it adds no Tab stop to a table with no
                        action to take. */}
                    <span
                      data-slot="totals-scope"
                      className={cn('block truncate font-normal', TEXT_UI)}
                    >
                      {messages.label('label.analysis.totals-scope')}
                    </span>
                  </TableCell>
                ) : (
                  cell(view.totals?.[entry.column.alias], entry)
                ),
              )}
              <FillerCell />
            </TableRow>
          </TableFooter>
        )}
      </Table>
      {/* The sentences the headers point at, said once each and drawn
          nowhere: a description is text somewhere on the page, and a
          `sr-only` span is the whole of what "somewhere" has to be. They
          are written only where something addresses them — an
          `aria-describedby` reaching an id that is not there is a broken
          description rather than a missing one. */}
      {sorting && (
        <span id={additiveId} className="sr-only">
          {messages.label('label.sort.additive')}
        </span>
      )}
      {view.columns.some(column => column.fn === 'PERCENTILE') && (
        <span id={approximateId} className="sr-only">
          {messages.label('label.analysis.approximate')}
        </span>
      )}
    </div>
  );
}

/**
 * The cell a press landed in, which the follow-up menu hangs from — the
 * row's first cell when the press landed past the columns, in the filler,
 * which holds nothing to hang from — and the row itself only when the press
 * found no cell of it (its padding, say).
 */
function cellOf(
  target: EventTarget,
  row: HTMLTableRowElement,
): HTMLTableCellElement | HTMLTableRowElement {
  const cell = target instanceof Element ? target.closest('td, th') : null;
  if (!(cell instanceof HTMLTableCellElement)) return row;
  return cell.dataset.column === FILLER_COLUMN ? (row.cells[0] ?? cell) : cell;
}
