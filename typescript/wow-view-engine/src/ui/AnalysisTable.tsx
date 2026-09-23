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
import { columnTitle, displayValue, valueText } from './display.js';
import { useViewMessages } from './MessagesProvider.js';
import { moveStop, settleStop, takeStop } from './roving.js';
import { FOCUS_ROW } from './variants.js';
import { useRoomBelowRows } from './record/roomBelowRows.js';
import { stickyBand } from './record/sticky.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import type { AnalysisView } from '../analysis/index.js';
import { AnalysisEmpty } from './analysis/EmptyResult.js';
import type { OnPick } from './charts/family.js';
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
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
}

/** What marks a row as a member of the result's one Tab stop. */
const ROW = 'tr[data-pickable]';

/**
 * The aggregation as a table: groups first, then metrics, with the totals row
 * from its own ungrouped query rather than from summing what is on screen.
 */
export function AnalysisTable({ view, onPick }: AnalysisTableProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // `aria-description` is a draft attribute Chromium alone implements, so
  // the sentence it carried reached one browser and no other. The same
  // sentence is now a span only a reader meets, addressed by
  // `aria-describedby` — which every reader has — while `title` goes on
  // saying it to the pointer.
  const ids = useId();
  const approximateId = `${ids}-approximate`;
  const totalsId = `${ids}-totals`;
  /**
   * The rows are peers, and a hundred peers are one Tab stop (A9).
   *
   * A pressable row used to be `tabIndex={0}`, so a result of a hundred
   * groups stood a hundred stops between the toolbar and whatever follows
   * the table, and a keyboard leaving the result had to walk every group it
   * had already read. They are a column of the same kind of thing, which is
   * what a roving tabindex is for (`roving.ts`, and the record header above
   * the same table): ↑/↓ between the rows, Home/End to the ends, Enter and
   * Space opening the follow-up menu exactly as a press does.
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
  // A group key or an ANY shows as its field's values do; the rest, and
  // anything the field's kind has nothing to say about, as before.
  const show = (value: unknown, column: AnalysisView['columns'][number]) =>
    displayValue(value, column, display) ??
    valueText(value, messages, column.numberFormat, display.locale);
  if (view.rows.length === 0) return <AnalysisEmpty />;

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
            {view.columns.map(column => (
              <TableHead
                key={column.alias}
                style={column.width ? { width: column.width } : undefined}
                // A percentile's 「≈」 is a sign; this is the word behind it.
                // Wow computes percentiles approximately, and nothing else on
                // the row says so (D20 口径).
                {...(column.fn === 'PERCENTILE'
                  ? {
                      'data-approximate': '',
                      title: messages.label('label.analysis.approximate'),
                      'aria-describedby': approximateId,
                    }
                  : {})}
              >
                {columnTitle(column, messages)}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody ref={body}>
          {view.rows.map((row, index) => (
            <TableRow
              key={index}
              data-pickable={onPick ? '' : undefined}
              aria-haspopup={onPick ? 'menu' : undefined}
              className={cn(onPick && 'cursor-pointer', FOCUS_ROW)}
              onClick={
                onPick ? event => onPick(row, event.currentTarget) : undefined
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
                        onPick(row, event.currentTarget);
                        return;
                      }
                      moveStop(event, rows(), 'column');
                    }
                  : undefined
              }
            >
              {view.columns.map(column => (
                <TableCell key={column.alias}>
                  {show(row[column.alias], column)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
        {room > 0 && (
          <tbody data-slot="row-room" aria-hidden>
            <tr>
              <td
                colSpan={view.columns.length}
                style={{ height: room, padding: 0, border: 0 }}
              />
            </tr>
          </tbody>
        )}
        {view.totals && (
          <TableFooter {...stickyBand('bottom')}>
            <TableRow data-slot="totals-row">
              {view.columns.map((column, index) => {
                const heading = index === 0 && column.role === 'group';
                return (
                  <TableCell
                    key={column.alias}
                    // The word 「合计」 alone invites "the rows above, added
                    // up", and the two numbers disagree whenever anything was
                    // left out. The scope is therefore said on the cell that
                    // carries the word: a `title` for the pointer and a
                    // described-by span for the reader.
                    //
                    // Not the registry `Tooltip`: it opens on a trigger, and
                    // a trigger is a control that takes hover *and* focus. A
                    // totals cell is neither focusable nor pressable, so a
                    // tooltip here would reach a pointer only — while adding
                    // a tab stop to every row of the footer to fix that would
                    // put a control in a table where there is no action to
                    // take. `title` says the same thing to the same pointer,
                    // and the description says it to the reader as part of
                    // the cell rather than as something to go and open.
                    {...(heading
                      ? {
                          'data-slot': 'totals-heading',
                          title: messages.label('label.analysis.totals-scope'),
                          'aria-describedby': totalsId,
                        }
                      : {})}
                  >
                    {heading
                      ? messages.label('label.summary.total')
                      : show(view.totals?.[column.alias], column)}
                  </TableCell>
                );
              })}
            </TableRow>
          </TableFooter>
        )}
      </Table>
      {/* The two sentences the cells above point at, said once each and
          drawn nowhere: a description is text somewhere on the page, and a
          `sr-only` span is the whole of what "somewhere" has to be. They
          are written only where something addresses them — an
          `aria-describedby` reaching an id that is not there is a broken
          description rather than a missing one. */}
      {view.columns.some(column => column.fn === 'PERCENTILE') && (
        <span id={approximateId} className="sr-only">
          {messages.label('label.analysis.approximate')}
        </span>
      )}
      {view.totals && view.columns[0]?.role === 'group' && (
        <span id={totalsId} className="sr-only">
          {messages.label('label.analysis.totals-scope')}
        </span>
      )}
    </div>
  );
}
