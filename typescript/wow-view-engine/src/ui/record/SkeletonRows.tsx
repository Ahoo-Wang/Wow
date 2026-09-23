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
import type { RecordColumnView } from '../../record/index.js';
import { Skeleton } from '../components/skeleton.js';
import { TableCell, TableRow } from '../components/table.js';
import { CLIPPED_CELL, columnWidth } from './columns.js';
import { stickyCell } from './sticky.js';
import { FillerCell } from './Filler.js';

/** How many rows a running query is drawn as. */
export const SKELETON_ROWS = 3;

/**
 * How wide one column's bar is, in characters of its own name.
 *
 * A column name is the only thing known about a column whose values have
 * not arrived, and it is a fair guess at them: a table lays out by content,
 * so a short-named column is a narrow column. It is bounded at both ends —
 * a two-letter name still gets a bar wide enough to read as one, and a
 * sentence of a label does not get a bar the width of the screen.
 */
export function barWidth(label: string): string {
  return `${Math.min(Math.max(label.length, 4), 16)}ch`;
}

export interface SkeletonRowsProps {
  /** The last known columns; empty on a first load, which has none yet. */
  columns: readonly RecordColumnView[];
  selectable: boolean;
  actions: boolean;
}

/**
 * The rows of a query that is running with nothing on screen to keep.
 *
 * Where the columns are known the skeleton is drawn **by column**, each bar
 * as wide as the name above it: three equal bars across the whole table said
 * only "something is loading", while a row of unequal bars under the real
 * headers says *this* table is loading, and the shape the answer will take
 * is already on screen. Where they are not — a first load, which has no
 * result and so no columns and draws no header either (#1585) — one bar per
 * row is all there is to say, and inventing column names from the draft
 * would be heading rows with columns the result has not agreed to.
 */
export function SkeletonRows({
  columns,
  selectable,
  actions,
}: SkeletonRowsProps) {
  const rows = Array.from({ length: SKELETON_ROWS }, (_unused, index) => index);
  if (columns.length === 0)
    return rows.map(index => (
      <TableRow key={`skeleton-${index}`}>
        <TableCell colSpan={(selectable ? 1 : 0) + (actions ? 1 : 0) || 1}>
          <Skeleton className="h-4 w-full" />
        </TableCell>
      </TableRow>
    ));

  return rows.map(index => (
    <TableRow key={`skeleton-${index}`}>
      {selectable && (
        <TableCell>
          <Skeleton className="size-4" />
        </TableCell>
      )}
      {columns.map(column => (
        // A column that was given a width keeps it while it reloads, or the
        // table would shuffle sideways the moment the rows land. Nothing is
        // held here — a query that has not answered yet has no result to
        // freeze a column of — and `stickyCell` is what says so, in the one
        // place that says the opposite for the layers that do.
        <TableCell
          key={column.field}
          {...stickyCell(undefined, {
            className: cn(column.width !== undefined && CLIPPED_CELL),
            style: columnWidth(column),
          })}
        >
          <Skeleton className="h-4" style={{ width: barWidth(column.label) }} />
        </TableCell>
      ))}
      {actions && (
        <TableCell>
          <Skeleton className="h-4 w-12" />
        </TableCell>
      )}
      {/* The same last cell the real rows carry, so the bars come out at the
          widths the columns will have rather than sharing the surplus out
          among them and shuffling sideways when the rows land. The
          column-less branch above has none: there the one cell already spans
          the row, and a filler beside it would squeeze its bar to nothing. */}
      <FillerCell />
    </TableRow>
  ));
}
