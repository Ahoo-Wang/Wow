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
import type { NumberFormat, RecordData, RecordKey } from '../model/index.js';
import type { RecordColumnView } from '../record/index.js';
import type { RecordTableController } from '../react/index.js';
import { Checkbox } from './components/checkbox.js';
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty.js';
import { Skeleton } from './components/skeleton.js';
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
  /** Renders one cell; the default formats by the column's declared kind. */
  renderCell?(cell: RecordCell): React.ReactNode;
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
  renderCell = defaultCell,
  emptyTitle = 'Nothing to show',
  emptyDescription = 'No record matches the current conditions.',
}: RecordTableProps) {
  const allSelected =
    table.rows.length > 0 && table.selection.length === table.rows.length;

  if (table.status === 'success' && table.rows.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <InboxIcon />
          </EmptyMedia>
          <EmptyTitle>{emptyTitle}</EmptyTitle>
          <EmptyDescription>{emptyDescription}</EmptyDescription>
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
                aria-label="Select all rows"
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
                      aria-label={`Select ${String(row.key)}`}
                      checked={table.isSelected(row.key)}
                      onCheckedChange={() => table.toggle(row.key)}
                    />
                  </TableCell>
                  {table.columns.map(column => (
                    <TableCell key={column.field}>
                      {renderCell({
                        column,
                        row: row.data,
                        key: row.key,
                        value: row.data[column.field],
                      })}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
        </TableBody>
      </Table>
    </div>
  );
}

function SortMark({ direction }: { direction: 'ASC' | 'DESC' | null }) {
  if (direction === 'ASC') return <ArrowUpIcon />;
  if (direction === 'DESC') return <ArrowDownIcon />;
  return null;
}

/** Numbers follow the field's declared format; everything else is text. */
function defaultCell({ column, value }: RecordCell): React.ReactNode {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number')
    return formatNumber(value, column.numberFormat);
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}

function formatNumber(value: number, format?: NumberFormat): string {
  if (!format) return String(value);
  const { locale, ...options } = format;
  return new Intl.NumberFormat(locale, options).format(value);
}
