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
import { ArrowDownIcon, ArrowUpIcon, InboxIcon } from 'lucide-react';
import type { RecordData, RecordKey } from '../model/index.js';
import type {
  RecordColumnView,
  SummaryCell,
  SummaryRow,
} from '../record/index.js';
import type { RecordTableController } from '../react/index.js';
import { recordValue } from '../record/index.js';
import { Checkbox } from './components/checkbox.js';
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty.js';
import { Skeleton } from './components/skeleton.js';
import { displayValue, formatNumber, type DisplayContext } from './display.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from './components/table.js';

export interface RecordTableProps {
  table: RecordTableController;
  /** Renders one cell; the default formats by the column's declared kind. */
  renderCell?(cell: RecordCell): React.ReactNode;
  /** Overrides the catalogue's own wording for an empty result. */
  emptyTitle?: string;
  emptyDescription?: string;
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
  emptyTitle,
  emptyDescription,
}: RecordTableProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const renderOne =
    renderCell ?? (found => defaultCell(found, messages, display));
  const allSelected =
    table.rows.length > 0 && table.selection.length === table.rows.length;

  if (table.status === 'success' && table.rows.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <InboxIcon />
          </EmptyMedia>
          <EmptyTitle>
            {emptyTitle ?? messages.label('label.record.empty')}
          </EmptyTitle>
          <EmptyDescription>
            {emptyDescription ?? messages.label('label.record.empty-hint')}
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    );
  }

  return (
    <div data-slot="record-table" className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-10">
              <Checkbox
                aria-label={messages.label('label.record.select-all')}
                checked={allSelected}
                indeterminate={
                  table.selection.length > 0 && !allSelected ? true : undefined
                }
                onCheckedChange={table.toggleAll}
              />
            </TableHead>
            {table.columns.map(column => (
              <TableHead
                key={column.field}
                style={column.width ? { width: column.width } : undefined}
              >
                {column.sortable ? (
                  <button
                    type="button"
                    className="flex items-center gap-1"
                    onClick={() => table.toggleSort(column.field)}
                  >
                    {column.label}
                    <SortMark direction={table.sortOf(column.field)} />
                  </button>
                ) : (
                  column.label
                )}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {table.status === 'loading' && table.rows.length === 0
            ? Array.from({ length: 3 }, (_unused, index) => (
                <TableRow key={`skeleton-${index}`}>
                  <TableCell colSpan={table.columns.length + 1}>
                    <Skeleton className="h-4 w-full" />
                  </TableCell>
                </TableRow>
              ))
            : table.rows.map(row => (
                <TableRow
                  key={String(row.key)}
                  data-state={
                    table.isSelected(row.key) ? 'selected' : undefined
                  }
                >
                  <TableCell>
                    <Checkbox
                      aria-label={messages.label('label.record.select', {
                        key: String(row.key),
                      })}
                      checked={table.isSelected(row.key)}
                      onCheckedChange={() => table.toggle(row.key)}
                    />
                  </TableCell>
                  {table.columns.map(column => (
                    <TableCell key={column.field}>
                      {renderOne({
                        column,
                        row: row.data,
                        key: row.key,
                        value: recordValue(row.data, column.field),
                      })}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
        </TableBody>
        {table.summaries && (
          <SummaryFooter summaries={table.summaries} columns={table.columns} />
        )}
      </Table>
    </div>
  );
}

/**
 * The summary row.
 *
 * Its scope is part of the number, not a detail: a `total` comes from its own
 * aggregation over everything the conditions match, while a `page` is the
 * visible rows added up, which is what is left when that query fails. An
 * average over twenty rows presented as the average over forty thousand is
 * the one mistake this row could make, so the row says which it is.
 */
function SummaryFooter({
  summaries,
  columns,
}: {
  summaries: SummaryRow;
  columns: readonly RecordColumnView[];
}) {
  const messages = useViewMessages();
  // A field may carry several functions — `amount` summed and averaged — and
  // the kernel projects a cell for each, so they are grouped rather than
  // keyed, which would keep only the last one configured.
  const byField = new Map<string, SummaryCell[]>();
  for (const cell of summaries.cells)
    byField.set(cell.field, [...(byField.get(cell.field) ?? []), cell]);

  return (
    <TableFooter data-scope={summaries.scope}>
      <TableRow>
        <TableCell className="text-muted-foreground text-xs font-normal">
          {messages.label(`label.summary.${summaries.scope}`)}
        </TableCell>
        {columns.map(column => (
          <TableCell key={column.field}>
            {(byField.get(column.field) ?? []).map(cell => (
              <span
                key={cell.fn}
                className="block whitespace-nowrap"
                title={messages.label('label.summary.of', {
                  fn: cell.fn,
                  field: cell.label,
                })}
              >
                <span className="text-muted-foreground mr-1 text-xs">
                  {cell.fn}
                </span>
                {cell.value === null
                  ? messages.label('label.summary.unavailable')
                  : formatNumber(cell.value, cell.numberFormat)}
              </span>
            ))}
          </TableCell>
        ))}
      </TableRow>
    </TableFooter>
  );
}

function SortMark({ direction }: { direction: 'ASC' | 'DESC' | null }) {
  if (direction === 'ASC') return <ArrowUpIcon />;
  if (direction === 'DESC') return <ArrowDownIcon />;
  return null;
}

/** Numbers follow the field's declared format; everything else is text. */
function defaultCell(
  { column, value }: RecordCell,
  messages: MessageFormatters,
  display: DisplayContext,
): React.ReactNode {
  if (value === null || value === undefined) return null;
  // A time, a date or an enum shows as the field says; a number keeps its
  // format and a boolean its wording below.
  const shown = displayValue(value, column, display);
  if (shown !== undefined) return shown;
  if (typeof value === 'number')
    return formatNumber(value, column.numberFormat);
  if (typeof value === 'boolean')
    return messages.label(value ? 'label.value.yes' : 'label.value.no');
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}
