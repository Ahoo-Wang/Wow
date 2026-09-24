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
import type { RecordData, RecordKey } from '../model/index.js';
import type { RecordColumnView, RecordRow } from '../record/index.js';
import type { RecordTableController } from '../react/index.js';
import { recordValue } from '../record/index.js';
import { useOpenRows } from './record/openRows.js';
import { RowActions } from './RowActions.js';
import { Checkbox } from './components/checkbox.js';
import { RangeHint, RowCheckbox } from './record/RowCheckbox.js';
import { useViewMessages } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';
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
} from './record/columns.js';
import {
  BAND_ROW,
  stickyBand,
  stickyCell,
  stickyHead,
} from './record/sticky.js';
import { usePinnedCap, type ReleasedPins } from './record/pinCap.js';
import { useRoomBelowRows } from './record/roomBelowRows.js';
import { useOverflowing } from './record/overflow.js';
import { cellText } from './display.js';
import { cellValue } from './record/cells.js';
import { EmptyResult } from './record/EmptyResult.js';
import type { EmptyWayOut } from './record/emptyWayOut.js';
import { FillerCell, FillerHead } from './record/Filler.js';
import { SkeletonRows } from './record/SkeletonRows.js';
import { SortableHeader } from './record/SortableHeader.js';
import { SummaryRows } from './record/SummaryRows.js';
import { useSummaries } from './record/useSummaries.js';
import { TableDataRow } from './variants.js';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from './components/table.js';

export interface RecordTableProps {
  table: RecordTableController;
  /** Renders one cell; the default reads it as the column's `cell` says. */
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
}

export interface RecordCell {
  column: RecordColumnView;
  row: RecordData;
  key: RecordKey;
  value: unknown;
}

/**
 * The table is its own scroll area: the header stays while the rows move
 * under it, the summaries stay at the foot, and both scroll sideways with the
 * columns they belong to. The registry's own container is taken out of the
 * way — two nested scrollports and the sticky header would resolve against
 * the inner one, which never scrolls.
 *
 * How tall it is depends on where it stands. In a workbench it takes the
 * height its column leaves it (`styles.css`, "A workbench fills its
 * container"), which is why nothing here measures the viewport any more.
 * Anywhere else — an embed in a host's page — it is capped at the host's
 * `--fve-record-table-max-h`, or 70vh.
 */
const SCROLL_AREA =
  'relative max-h-[var(--fve-record-table-max-h,70vh)] overflow-auto [&>[data-slot=table-container]]:overflow-visible';

/**
 * And the same table where something around it scrolls instead.
 *
 * `overflow` cannot be had on one axis alone — a box that scrolls sideways is
 * a scrollport both ways — so a wrapper that never needs to scroll must not
 * be one at all: inside a dashboard panel shorter than this table's own
 * height, or under a host that scrolls the whole page, the wrapper would
 * become the scrollport the sticky header resolves against and the header
 * would scroll away with the rows while the panel around it did the moving.
 * Left visible, the header, the summaries and the pinned columns all hold
 * against whatever really scrolls.
 */
const STATIC_AREA = 'relative [&>[data-slot=table-container]]:overflow-visible';

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
}: RecordTableProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const renderOne =
    renderCell ??
    (found => cellValue(found.value, found.column, messages, display, 'table'));
  const allSelected =
    table.rows.length > 0 && table.selection.length === table.rows.length;
  const columns = table.columns;
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
    scrolls && table.summaries !== null && table.rows.length > 0,
  );
  // Whether the middle really scrolls, which is what the held columns'
  // edges answer to (P-23). Asked before the cap, whose own observer a
  // test reaches for as the latest one created.
  const overflowing = useOverflowing(port, element);
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
  const summaries = useSummaries(table.summaries, table.rows, table.paging);

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
  if (!table.hasResult && table.status !== 'loading') return null;

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
      // Which of the two shapes above this is, said on the element rather
      // than left to be guessed from the class list: the expanded workbench
      // hands the remaining height to the table that is its own scrollport,
      // and must not hand it to the one holding on against a panel.
      data-scrolls={scrolls ? '' : undefined}
      data-overflowing={overflowing ? '' : undefined}
      className={scrolls ? SCROLL_AREA : STATIC_AREA}
    >
      <Table ref={element} className={TABLE_CELLS}>
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
            <TableRow className={BAND_ROW}>
              {selectable && (
                <TableHead
                  data-column={SELECT_COLUMN}
                  {...stickyHead(pins.select, {
                    className: cn('w-10', HEAD_CELL),
                  })}
                >
                  <Checkbox
                    aria-label={messages.label('label.record.select-all')}
                    checked={allSelected}
                    indeterminate={
                      table.selection.length > 0 && !allSelected
                        ? true
                        : undefined
                    }
                    onCheckedChange={table.toggleAll}
                  />
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
                  {...(readOnly ? {} : { onResize: table.setColumnWidth })}
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
              // hovered, picked — are the wrapper's (`ui/variants.tsx`,
              // D16-8); what is said here is only which of them this row is
              // in.
              <TableDataRow
                key={String(row.key)}
                // Named `row`, because a cell that only offers something
                // while the pointer is on the row has to be able to ask
                // about the row and not about itself (`CopyButton`).
                className={cn('group/row', onOpen && 'cursor-pointer')}
                data-state={table.isSelected(row.key) ? 'selected' : undefined}
                {...opening.row(row)}
              >
                {selectable && (
                  <TableCell {...stickyCell(pins.select)}>
                    <RowCheckbox table={table} row={row} hintId={rangeId} />
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
                          : cellText(value, column, messages, display)
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
        <span id={opening.hintId} className="sr-only">
          {messages.label('label.record.detail.hint')}
        </span>
      )}
      {selectable && table.rows.length > 0 && <RangeHint id={rangeId} />}
      {!firstLoad && columns.some(column => column.sortable) && (
        <span id={additiveId} className="sr-only">
          {messages.label('label.sort.additive')}
        </span>
      )}
    </div>
  );
}
