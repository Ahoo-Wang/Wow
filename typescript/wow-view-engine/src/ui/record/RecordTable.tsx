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

import type * as React from 'react';
import { useEffect, useId, useMemo, useRef } from 'react';
import { cn } from 'cn';
import type { RecordData, RecordKey } from '../../model/index.js';
import type { RecordColumnView, RecordRow } from '../../record/index.js';
import type { RecordTableController } from '../../react/index.js';
import { recordValue } from '../../record/index.js';
import { useOpenRows } from './openRows.js';
import { RowActions } from './RowActions.js';
import { RangeHint, RowCheckbox } from './RowCheckbox.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { useSurfaceAnnouncer } from '../kit/Announcer.js';
import { useSurfaceDisplay } from '../kit/ViewSurface.js';
import {
  ACTION_CELL,
  ACTIONS_COLUMN,
  CLIPPED_CELL,
  HEAD_CELL,
  NUMERIC_CELL,
  SELECT_COLUMN,
  TABLE_CELLS,
  columnWidth,
  isNumeric,
  pinnedSlots,
  tablePins,
  usePinnedOffsets,
} from './columns.js';
import {
  BAND_ROW,
  stickyBand,
  stickyCell,
  stickyHead,
  stickyPort,
} from './sticky.js';
import { usePinnedCap, type ReleasedPins } from './pinCap.js';
import { useRoomBelowRows } from './roomBelowRows.js';
import { useOverflowing } from './overflow.js';
import { cellText } from '../kit/display.js';
import { inRowCurrency } from '../kit/currency.js';
import { cellValue } from './cells.js';
import { EmptyResult } from './EmptyResult.js';
import type { EmptyWayOut } from './emptyWayOut.js';
import { FillerCell, FillerHead } from './Filler.js';
import { SkeletonRows } from './SkeletonRows.js';
import { SortableHeader } from './SortableHeader.js';
import { SummaryRows } from './SummaryRows.js';
import { summarisesColumns, useSummaries } from './useSummaries.js';
import { TableDataRow } from '../kit/variants.js';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../components/table.js';
import { MixedCheckbox } from '../kit/MixedCheckbox.js';

/**
 * The room a selection checkbox stands in: the box where it always stood, at
 * the start of the cell (the cell's padding, in line with a board panel's
 * title and the rest of the column's content), with its room on the side
 * its neighbours are. A 1.75rem box and the cell's 0.5rem of right padding,
 * which the registry drops for a checkbox cell and {@link SELECT_CELL} puts
 * back, put the cell's right edge 1.25rem from the box's centre.
 *
 * Why (second review R1-P1-9, R3-P1-7): the box is 16px, the WCAG 2.2
 * minimum target is 24px, and the exception for a smaller target is room —
 * no other target within a 24px circle centred on it. Where a table laid
 * the column out at its content, 28px, the header's first sort button began
 * 8px from the box's centre, inside that circle (axe `target-size`,
 * serious), and the registry's own larger hit area (its `::after`, 0.75rem
 * past the box on either side, so 1.25rem from its centre) lay over the
 * button. Now the nearest neighbour starts 1.25rem from the centre and the
 * hit area ends at the cell's edge. The room goes on the right, not around
 * the box: moved in from the cell's padding, the box would stand out of
 * line with everything else at that edge (a board panel holds a table's
 * first content at its title, `OpsDailyEdges`). The rows keep their height:
 * the room is across, not down.
 */
const SELECT_BOX = 'fve:flex fve:w-7 fve:items-center';
const SELECT_CELL = 'fve:[&:has([role=checkbox])]:pr-2';

export interface RecordTableProps {
  table: RecordTableController;
  /**
   * Renders one cell; the default reads it as the column's `cell` says.
   * It is handed the raw value and the column as the runtime holds them —
   * a keyed definition's labels as keys: what it shows, it says (`useSay`,
   * or `cellValue` given the surface's `useSurfaceDisplay()`, whose `say`
   * says an option's label).
   */
  renderCell?(cell: RecordCell): React.ReactNode;
  /**
   * Whether rows can be picked. On by default; a surface that offers nothing
   * to do with a selection turns it off rather than showing a column of
   * checkboxes that lead nowhere.
   */
  selectable?: boolean;
  /**
   * Told whenever the cap (D17-4) lets a pin go or gives it back, so the
   * column settings can say which pins are not drawn right now: the config
   * still holds them, and a switch that shows "pinned" over a column with
   * no edge is a state the screen fails to reflect.
   */
  onReleasedPins?(released: ReleasedPins): void;
  /**
   * What a host offers on one row, as a pinned last column.
   *
   * It takes the row and nothing else: the table knows a result, not a
   * runtime, and the caller that owns the runtime — a workbench, an embedded
   * view — is the one that binds it into a `RecordRowActionContext`. Leaving
   * it out leaves the column out; the output is wrapped in `RowActions`, so
   * a host hands over buttons rather than a layout.
   */
  rowActions?(row: RecordRow): React.ReactNode;
  /**
   * Opens a row's detail: a press on the row's own ground, or Enter/Space on
   * the row the keyboard is on — the rows are one Tab stop with the arrows
   * inside it (`record/openRows.ts`). Absent, rows are not openable.
   */
  onOpen?(row: RecordRow): void;
  /**
   * Whether the table is its own scroll area. On by default, which is what a
   * workbench wants: the rows scroll under a header that stays.
   *
   * A surface that scrolls itself — a dashboard panel, a host page that
   * scrolls as a whole — turns it off and keeps the sticky header, summaries
   * and pinned columns, which then hold against *its* scrolling. Leaving it
   * on inside such a surface is what takes the header away: the wrapper
   * becomes a second scrollport that nothing ever scrolls.
   */
  scrolls?: boolean;
  /**
   * Whether the table holds its own last column against the right edge
   * (D13's end). On by default, which is what a workbench wants: the table
   * is worked, scrolled across, and the end frames the middle passing under
   * it.
   *
   * A surface that is read at rest — a dashboard panel — turns it off. A
   * column held on the right covers the middle as soon as the table
   * overflows, before anything has scrolled, and the cap (D17-4) cannot
   * help: it weighs the held group against half the port, and one column
   * under half of it is kept however much of its neighbour it covers. On a
   * workbench that is the frame doing its job; on a panel, read at a glance
   * and seldom scrolled sideways, it is the neighbour's last characters gone
   * — 「已重试次数」 read as 「已重试次」 on the home page. The key
   * and the columns pinned left stay held, since those cover nothing until
   * the rows are scrolled.
   */
  holdEnd?: boolean;
  /** Overrides the catalogue's own wording for an empty result. */
  emptyTitle?: string;
  emptyDescription?: string;
  /**
   * Which way out an empty result offers (`emptyWayOut`); `add` when left
   * out — a table handed rows by hand has no conditions it knows of.
   */
  emptyWayOut?: EmptyWayOut;
  /**
   * What that way out does. Left out, the empty result offers none — an
   * embedded view or a dashboard panel has no condition editor of its own to
   * send anybody to, and a button that leads nowhere is worse than no button.
   */
  onEmptyAction?(): void;
  /**
   * Whether the headers are only read (off by default): no sort button and
   * no width handle, each column still a stop for the arrow keys. An
   * embed's static tier (D22, D36), where the rows are what the page shows
   * and nothing on it reorders or reshapes them.
   */
  readOnly?: boolean;
  /**
   * What the table is called, for a reader moving by tables: the view's
   * title in a workbench or an embed, the panel's on a board, where several
   * record panels stand side by side and a table named by nothing is one of
   * several nobody can tell apart. A key is said (`text(key)`).
   */
  name?: string;
}

export interface RecordCell {
  column: RecordColumnView;
  row: RecordData;
  key: RecordKey;
  value: unknown;
}

/**
 * The record view as a table.
 *
 * Columns and rows come from the last successful result rather than the
 * draft, because the kernel projected them from the config that ran: what is
 * on screen always answers a question that was actually asked.
 */
export function RecordTable({
  table,
  renderCell,
  selectable = true,
  rowActions,
  onOpen,
  scrolls = true,
  holdEnd = true,
  emptyTitle,
  emptyDescription,
  emptyWayOut = 'add',
  onEmptyAction,
  onReleasedPins,
  readOnly = false,
  name,
}: RecordTableProps) {
  const messages = useViewMessages();
  // A width set from the keyboard is said, step by step: the handle's value
  // moves, but a reader on the header hears nothing of it otherwise — and
  // the rows are not fetched again for it (`restyle`), so there is no query
  // sentence to stand in for it either.
  const { say, region } = useSurfaceAnnouncer('column-width-announcement');
  const display = useSurfaceDisplay();
  const renderOne =
    renderCell ??
    (found =>
      cellValue(
        found.value,
        inRowCurrency(found.column, found.row),
        messages,
        display,
        'table',
      ));
  const allSelected =
    table.rows.length > 0 && table.selection.length === table.rows.length;
  const columns = table.columns;
  // No band unless a column on screen carries one of the summaries.
  const summarised = summarisesColumns(table.summaries, columns);
  const element = useRef<HTMLTableElement>(null);
  const port = useRef<HTMLDivElement>(null);
  const additiveId = useId();
  const rangeId = useId();
  const body = useRef<HTMLTableSectionElement>(null);
  const opening = useOpenRows(body, onOpen, 'column');
  const layout = useMemo(
    () => ({
      selectable,
      actions: rowActions !== undefined,
      end: holdEnd,
    }),
    [selectable, rowActions, holdEnd],
  );
  // What the table would hold, and what the port has room for it to hold:
  // beyond half the visible width the group is capped and the outermost
  // pins are let go, the config untouched (D17-4).
  const slots = useMemo(() => pinnedSlots(columns, layout), [columns, layout]);
  // The room the rows leave in a port taller than them, so the summaries
  // sit at its bottom beside the pagination rather than under the last row.
  // Measured first: its observer is not the cap's, and a suite reaches for
  // the cap's as the latest one created.
  const room = useRoomBelowRows(
    port,
    element,
    scrolls && summarised && table.rows.length > 0,
  );
  // Whether the middle really scrolls, which is what the held columns'
  // edges answer to (P-23). Asked before the cap, whose own observer a
  // test reaches for as the latest one created.
  const overflow = useOverflowing(port, element, scrolls);
  const released = usePinnedCap(port, element, slots);
  useEffect(() => onReleasedPins?.(released), [onReleasedPins, released]);
  const pins = useMemo(
    () => tablePins(columns, layout, released),
    [columns, layout, released],
  );
  // Last of the three measurements, and after the pins it is keyed on: what
  // it publishes is where each held column stops, which is the one of them
  // that depends on the cap having had its say.
  usePinnedOffsets(element, pins);
  const summaries = useSummaries(
    summarised ? table.summaries : null,
    table.rows,
    table.paging,
  );

  // No result to draw and none on the way. The table is built from the
  // result, so there are no columns either: what would be drawn is a header
  // of one empty cell over no rows, with a tab-reachable "Select all rows"
  // in it that selects nothing and cannot be told it is disabled. Nothing is
  // a truer frame than that, and the reason is already on screen — the query
  // strip says the query failed, the error strip says the config has to be
  // fixed first. Neither is this table's sentence to repeat.
  //
  // Narrow on purpose. A refresh that fails keeps the rows it could not
  // replace and turns the status to `error`, and the first `loading` has its
  // skeleton rows to draw: both have something to show, so both keep the
  // table. What has nothing is a view that never got a result and has
  // nothing running to get one.
  if (!table.hasResult && table.status !== 'loading')
    // The one frame with something to say: the source takes no query
    // without a condition, and none has been added yet (Q3).
    return table.filterRequired ? (
      <EmptyResult
        title={messages.label('label.record.filter-required')}
        description={messages.label('label.record.filter-required-hint')}
        wayOut="add"
        {...(emptyWayOut === 'add' && onEmptyAction
          ? { onAction: onEmptyAction }
          : {})}
      />
    ) : null;

  // The first load is that same empty frame with a query running under it.
  // It keeps the table, because the skeleton rows are worth drawing — but it
  // has no columns to head them with, and the header it used to draw was the
  // dead one all over again: a single cell holding a tab-reachable "Select
  // all rows" over rows that do not exist yet. So the skeleton is drawn
  // without a header at all. An empty `<th>` would be no better — a screen
  // reader announces a blank column header, and axe counts it a defect —
  // and inventing the names from the draft would be heading the rows with
  // columns the result has not agreed to yet.
  const firstLoad = !table.hasResult;

  // A result that matched nothing is a different sentence, and the user acts
  // differently on it: the conditions ran, and these are the records there
  // are. It is the table's own to say, because nothing above says it.
  if (table.status === 'success' && table.rows.length === 0) {
    return (
      <EmptyResult
        title={emptyTitle}
        description={emptyDescription}
        wayOut={emptyWayOut}
        onAction={onEmptyAction}
      />
    );
  }

  return (
    <div
      ref={port}
      data-slot="record-table"
      // Its own scroll port, or none where something around it scrolls
      // (`stickyPort`), said on the element as `data-scrolls`.
      {...stickyPort(scrolls)}
      data-overflowing={overflow.overflowing ? '' : undefined}
      data-more-start={overflow.start ? '' : undefined}
      data-more-end={overflow.end ? '' : undefined}
    >
      {/* Its own region only where widths can change and nobody lent it a
          voice (an interactive embed); a workbench and a board lend theirs. */}
      {!readOnly && region}
      <Table
        ref={element}
        className={TABLE_CELLS}
        {...(name ? { 'aria-label': messages.say(name) } : {})}
      >
        {/* A band rather than a row: it stays while the rows move under it,
            and it is the same grey as the summary band at the other end, so
            the two of them bracket the data (`stickyBand`, P-21). The 2px
            rule it used to carry is gone — `[&_tr]:border-b-2` named the
            `<tr>`, and in the separate border model a row has no border to
            paint, so what has always been on screen is the cells' own 1px
            `border-b` from `TABLE_CELLS`. One hairline is what parts the
            summary band from the rows as well, and the fill does the rest. */}
        {!firstLoad && (
          <TableHeader {...stickyBand('top')}>
            <TableRow className={BAND_ROW.top}>
              {selectable && (
                <TableHead
                  data-column={SELECT_COLUMN}
                  {...stickyHead(pins.select, {
                    className: cn('fve:w-10', HEAD_CELL, SELECT_CELL),
                  })}
                >
                  <span className={SELECT_BOX}>
                    <MixedCheckbox
                      aria-label={messages.label('label.record.select-all')}
                      checked={allSelected}
                      indeterminate={
                        table.selection.length > 0 && !allSelected
                          ? true
                          : undefined
                      }
                      onCheckedChange={table.toggleAll}
                    />
                  </span>
                </TableHead>
              )}
              {/* The arrows say the order the rows on screen are in — the
                  sort that ran; a press held back while other edits wait
                  for Apply shows on the Apply dot, not in the arrow. The
                  button's name is worked out from the draft, as the press
                  is, and says what waits. */}
              {columns.map(column => (
                <SortableHeader
                  key={column.field}
                  column={
                    readOnly && column.sortable
                      ? { ...column, sortable: false }
                      : column
                  }
                  sort={table.ranSort}
                  drafted={table.sort}
                  onToggle={table.toggleSort}
                  {...(readOnly
                    ? {}
                    : {
                        onResize: (field: string, width: number | null) => {
                          table.setColumnWidth(field, width);
                          say(
                            width === null
                              ? messages.label('label.columns.resized-auto', {
                                  field: column.label,
                                })
                              : messages.label('label.columns.resized', {
                                  field: column.label,
                                  width,
                                }),
                          );
                        },
                      })}
                  pin={pins.columns.get(column.field)}
                  additiveId={additiveId}
                />
              ))}
              {rowActions && (
                <TableHead
                  data-column={ACTIONS_COLUMN}
                  {...stickyHead(pins.actions, {
                    className: cn(HEAD_CELL, ACTION_CELL),
                  })}
                >
                  {messages.label('label.toolbar.actions')}
                </TableHead>
              )}
              <FillerHead />
            </TableRow>
          </TableHeader>
        )}
        <TableBody ref={body}>
          {table.status === 'loading' && table.rows.length === 0 ? (
            <SkeletonRows
              columns={columns}
              selectable={selectable}
              actions={rowActions !== undefined}
            />
          ) : (
            table.rows.map(row => (
              // The three states a row has to be told apart in — at rest,
              // hovered, picked — are the wrapper's (`ui/kit/variants.tsx`,
              // D16-8); what is said here is only which of them this row is
              // in.
              <TableDataRow
                key={String(row.key)}
                // Named `row`, because a cell that only offers something
                // while the pointer is on the row has to be able to ask
                // about the row and not about itself (`CopyButton`).
                className={cn('fve:group/row', onOpen && 'fve:cursor-pointer')}
                data-state={table.isSelected(row.key) ? 'selected' : undefined}
                {...opening.row(row)}
              >
                {selectable && (
                  <TableCell
                    {...stickyCell(pins.select, { className: SELECT_CELL })}
                  >
                    <span className={SELECT_BOX}>
                      <RowCheckbox table={table} row={row} hintId={rangeId} />
                    </span>
                  </TableCell>
                )}
                {columns.map(column => {
                  const pin = pins.columns.get(column.field);
                  const value = recordValue(row.data, column.field);
                  return (
                    <TableCell
                      key={column.field}
                      {...stickyCell(pin, {
                        className: cn(
                          isNumeric(column) && NUMERIC_CELL,
                          // A width the user set is a width they meant, so
                          // the cell is cut to it — and what was cut is one
                          // hover away, the way a clamped paragraph is.
                          column.width !== undefined && CLIPPED_CELL,
                        ),
                        style: columnWidth(column),
                      })}
                      title={
                        column.width === undefined
                          ? undefined
                          : cellText(
                              value,
                              inRowCurrency(column, row.data),
                              messages,
                              display,
                            )
                      }
                    >
                      {renderOne({
                        column,
                        row: row.data,
                        key: row.key,
                        value,
                      })}
                    </TableCell>
                  );
                })}
                {rowActions && (
                  <TableCell
                    {...stickyCell(pins.actions, { className: ACTION_CELL })}
                  >
                    <RowActions>{rowActions(row)}</RowActions>
                  </TableCell>
                )}
                <FillerCell />
              </TableDataRow>
            ))
          )}
        </TableBody>
        {/* The room the rows leave, drawn as nothing: a body of its own so
            the rows' body still holds only rows, hidden from a reader, and
            one cell across every column so it adds no column of its own. */}
        {room > 0 && (
          <tbody data-slot="row-room" aria-hidden>
            <tr>
              <td
                colSpan={
                  columns.length +
                  (selectable ? 1 : 0) +
                  (rowActions === undefined ? 0 : 1) +
                  1
                }
                style={{ height: room, padding: 0, border: 0 }}
              />
            </tr>
          </tbody>
        )}
        {summaries.length > 0 && (
          <SummaryRows
            rows={summaries}
            columns={columns}
            selectable={selectable}
            actions={rowActions !== undefined}
            pins={pins}
          />
        )}
      </Table>
      {/* How to add a column to the sort, said once for every sortable
          header and drawn nowhere. Outside the table rather than in each
          header cell: text inside a `<th>` is part of the column's header,
          and a reader would say it again beside every value under it. Only
          while a sortable header is there to point at it. */}
      {opening.hintId && table.rows.length > 0 && (
        <span id={opening.hintId} className="fve:sr-only">
          {messages.label('label.record.detail.hint')}
        </span>
      )}
      {selectable && table.rows.length > 0 && <RangeHint id={rangeId} />}
      {!firstLoad && columns.some(column => column.sortable) && (
        <span id={additiveId} className="fve:sr-only">
          {messages.label('label.sort.additive')}
        </span>
      )}
    </div>
  );
}
