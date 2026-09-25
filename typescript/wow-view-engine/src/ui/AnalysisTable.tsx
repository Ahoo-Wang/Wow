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
import { columnTitle, formatNumber } from './display.js';
import { useViewMessages } from './MessagesProvider.js';
import { moveStop, rovingDestination, settleStop, takeStop } from './roving.js';
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
import { onlyWhereText } from './summary.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import type { AnalysisView } from '../analysis/index.js';
import type { AnalysisSort, RecordData, RecordSort } from '../model/index.js';
import { AnalysisEmpty } from './analysis/EmptyResult.js';
import type { HeaderSorting } from './analysis/headerSort.js';
import {
  analysisCellText,
  headerColumnOf,
  isIdentifier,
  useHeldWidths,
} from './analysis/tableColumns.js';
import { useVirtualRows } from './analysis/virtualRows.js';
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
  /**
   * Whether a press opens a menu (`aria-haspopup`): the follow-up menu, as
   * it does unless said otherwise. A dashboard panel whose press sets the
   * board's filter or goes elsewhere opens none (D22 I).
   */
  opensMenu?: boolean;
  /**
   * The group a press set the board's filter to, marked rather than
   * narrowed to (D22 I): its row wears `data-pressed` and `aria-current`.
   */
  highlight?: (row: RecordData) => boolean;
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
export function AnalysisTable({
  view,
  onPick,
  sorting,
  opensMenu = true,
  highlight,
}: AnalysisTableProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // `aria-description` is a draft attribute Chromium alone implements, so
  // the sentence it carried reached one browser and no other. The same
  // sentence is a span only a reader meets, addressed by `aria-describedby`
  // — which every reader has.
  const ids = useId();
  const approximateId = `${ids}-approximate`;
  const additiveId = `${ids}-additive`;
  const conditionId = (index: number) => `${ids}-condition-${index}`;
  const spanId = `${ids}-span`;
  // The keyboard's way to a span (D33 Q52), which a pointer brushes along
  // the chart: a row picked, then another with Shift held, is every bucket
  // between the two — only over a time dimension, where there is a between,
  // and only where a press opens the follow-up menu at all.
  const spans =
    onPick !== undefined &&
    opensMenu &&
    view.columns.some(
      column => column.role === 'group' && column.dateUnit !== undefined,
    );
  const spanFrom = useRef<RecordData | null>(null);
  const pick = (
    row: RecordData,
    anchor: Element,
    origin: HTMLElement,
    shift: boolean,
  ) => {
    if (!onPick) return;
    const from = spanFrom.current;
    // A row of an earlier result is no end of a span over this one.
    if (spans && shift && from && from !== row && view.rows.includes(from)) {
      onPick(from, anchor, origin, row);
      return;
    }
    spanFrom.current = row;
    onPick(row, anchor, origin);
  };
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
  // A long result draws the rows in view and the room the others take
  // (`useVirtualRows`, ui/analysis.md「长表」); a short one draws them all.
  const virtual = useVirtualRows(view.rows.length, port, body);
  const segments = virtual.segments;
  // A number band reads as the band it is; a group key or an ANY shows as
  // its field's values do; the rest, and anything the field's kind has
  // nothing to say about, as before.
  // The one reading the exported file shares (`analysisCellText`).
  const show = (value: unknown, column: AnalysisView['columns'][number]) =>
    analysisCellText(value, column, messages, display);
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

  // What the totals hold that no row shows (`totals-hidden` below).
  const count = (value: number) =>
    formatNumber(value, undefined, display.locale);
  const hidden = [
    ...(view.truncated
      ? [
          messages.label('label.analysis.totals-hidden.cut', {
            limit: count(view.rows.length),
          }),
        ]
      : view.atLimit !== undefined
        ? [
            messages.label('label.analysis.totals-hidden.maybe-cut', {
              limit: count(view.atLimit),
            }),
          ]
        : []),
    ...(view.narrowed
      ? [messages.label('label.analysis.totals-hidden.kept')]
      : []),
  ];

  /**
   * What a header says about its column beyond its name, in its tooltip and
   * to a reader. A percentile's 「≈」 is a sign, and 「近似值」 is the word
   * behind it: Wow computes percentiles approximately, and nothing else on
   * the row says so (D20 口径). A conditioned metric's 「· 已发运」 is the
   * short of its condition, and 「只算 状态 是 已发运」 the whole of it —
   * said even when the analyst's own name took the header's place.
   */
  const headerNote = (
    column: AnalysisView['columns'][number],
    index: number,
  ): { id: string; text: string } | undefined => {
    const notes = [
      ...(column.fn === 'PERCENTILE'
        ? [
            {
              id: approximateId,
              text: messages.label('label.analysis.approximate'),
            },
          ]
        : []),
      ...(column.condition
        ? [
            {
              id: conditionId(index),
              text: onlyWhereText(column.condition.items, messages, display),
            },
          ]
        : []),
    ];
    return notes.length === 0
      ? undefined
      : {
          id: notes.map(note => note.id).join(' '),
          text: notes.map(note => note.text).join(' · '),
        };
  };

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
      note: headerNote(column, index),
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
  const sort = sorting ? asFields(sorting.sort) : NO_SORT;
  const drafted = sorting ? asFields(sorting.drafted) : NO_SORT;

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
      <Table
        ref={table}
        // Drawn virtually, the table says how many rows it has and each row
        // where it stands: the header first, the totals last.
        aria-rowcount={
          segments ? view.rows.length + 1 + (view.totals ? 1 : 0) : undefined
        }
      >
        <TableHeader {...stickyBand('top')}>
          <TableRow aria-rowindex={segments ? 1 : undefined}>
            {columns.map(({ column, head, note }) => (
              <SortableHeader
                key={column.alias}
                column={head}
                sort={sort}
                drafted={drafted}
                {...(sorting
                  ? {
                      upcoming: (alias: string) =>
                        asFields(sorting.next(alias)),
                    }
                  : {})}
                onToggle={(alias, options) => sorting?.onToggle(alias, options)}
                additiveId={additiveId}
                {...(note ? { note } : {})}
              />
            ))}
            <FillerHead />
          </TableRow>
        </TableHeader>
        <TableBody ref={body}>
          {(segments ?? allRows(view.rows.length)).map(segment => {
            if (segment.kind === 'gap')
              return (
                <tr key={segment.key} data-slot="row-gap" aria-hidden>
                  <td
                    colSpan={columns.length + 1}
                    style={{ height: segment.height, padding: 0, border: 0 }}
                  />
                </tr>
              );
            const { index } = segment;
            const row = view.rows[index];
            const pressed = highlight?.(row) === true;
            return (
              <TableRow
                key={segment.key}
                ref={segments ? virtual.measure : undefined}
                data-index={index}
                aria-rowindex={segments ? index + 2 : undefined}
                data-pickable={onPick ? '' : undefined}
                data-pressed={pressed ? '' : undefined}
                aria-current={pressed ? 'true' : undefined}
                aria-haspopup={onPick && opensMenu ? 'menu' : undefined}
                className={cn(
                  onPick && 'cursor-pointer',
                  FOCUS_ROW,
                  'data-[pressed]:bg-muted',
                )}
                // The menu hangs from the cell pressed, or from the row's first
                // cell for a key, never from the row: a menu anchored to a row
                // takes the row's width, and an analysis row is the width of
                // the whole table (the popup's recipe is `--anchor-width`). The
                // row stays where the keyboard goes back to.
                aria-describedby={spans ? spanId : undefined}
                onClick={
                  onPick
                    ? event =>
                        pick(
                          row,
                          cellOf(event.target, event.currentTarget),
                          event.currentTarget,
                          event.shiftKey,
                        )
                    : undefined
                }
                // The stop follows the keyboard: a row focused is the row the
                // group's one stop is on, however focus got there — an arrow,
                // a press, or the menu handing it back when it closes.
                onFocus={
                  onPick
                    ? event => {
                        takeStop(event.currentTarget, rows());
                        virtual.holdStop(index);
                      }
                    : undefined
                }
                onKeyDown={
                  onPick
                    ? event => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          pick(
                            row,
                            event.currentTarget.cells[0] ?? event.currentTarget,
                            event.currentTarget,
                            event.shiftKey,
                          );
                          return;
                        }
                        if (!segments) {
                          moveStop(event, rows(), 'column');
                          return;
                        }
                        // Drawn virtually, the next row may not be drawn
                        // yet: the arrows move by index, and the row is
                        // brought into view and focused once it is.
                        if (
                          event.altKey ||
                          event.ctrlKey ||
                          event.metaKey ||
                          event.shiftKey
                        )
                          return;
                        const to = rovingDestination(
                          event.key,
                          index,
                          view.rows.length,
                          'column',
                        );
                        if (to === null || to === index) return;
                        event.preventDefault();
                        virtual.focusRow(to, next => takeStop(next, rows()));
                      }
                    : undefined
                }
              >
                {columns.map(entry => cell(row[entry.column.alias], entry))}
                <FillerCell />
              </TableRow>
            );
          })}
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
            <TableRow
              data-slot="totals-row"
              aria-rowindex={segments ? view.rows.length + 2 : undefined}
            >
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
                    {/* And where the rows above leave some of that out, what
                        they leave out: the groups past the first N, the
                        groups 「只保留」 dropped. The totals keep their
                        meaning — every record in the range — and the
                        difference is said here, beside the number that
                        differs, instead of being left for the reader to
                        notice (2026-09-23 audit). The same line, so the
                        row stays one reading; whole in its `title` where
                        the column cuts it. */}
                    {hidden.length > 0 && (
                      <span
                        data-slot="totals-hidden"
                        title={hidden.join(' · ')}
                        className={cn('block truncate font-normal', TEXT_UI)}
                      >
                        {hidden.join(' · ')}
                      </span>
                    )}
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
      {spans && (
        <span id={spanId} className="sr-only">
          {messages.label('label.drill.span-hint')}
        </span>
      )}
      {view.columns.some(column => column.fn === 'PERCENTILE') && (
        <span id={approximateId} className="sr-only">
          {messages.label('label.analysis.approximate')}
        </span>
      )}
      {columns.map(({ column }, index) =>
        column.condition ? (
          <span key={column.alias} id={conditionId(index)} className="sr-only">
            {onlyWhereText(column.condition.items, messages, display)}
          </span>
        ) : null,
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

/** Every row, as the body draws them when it is not drawn virtually. */
function allRows(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    kind: 'row' as const,
    index,
    key: index,
  }));
}

/** An analysis sort in the record header's terms: an alias is its field. */
function asFields(sort: readonly AnalysisSort[]) {
  return sort.map(entry => ({
    field: entry.alias,
    direction: entry.direction,
  }));
}
