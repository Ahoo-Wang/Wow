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

import type { AnalysisColumnView } from '../../analysis/index.js';
import type { AnalysisLayout } from '../../model/index.js';
import { Skeleton } from '../components/skeleton.js';
import { Table, TableBody, TableCell, TableRow } from '../components/table.js';
import { SKELETON_ROWS, barWidth } from '../record/SkeletonRows.js';

export interface AnalysisSkeletonProps {
  /** The layout the answer will be drawn in: the draft's, as the result's is. */
  layout: AnalysisLayout;
  /** The columns the table will draw (`useAnalysisResult().tableColumns`). */
  columns: readonly AnalysisColumnView[];
}

/**
 * The first answer of an analysis still on its way, in the shape it will
 * land in: rows of bars under a table, one area where a chart will be.
 *
 * It is the record view's recipe (`record/SkeletonRows.tsx`) — the same
 * number of rows, a bar per column as wide as its name suggests, the
 * registry's `Skeleton` — and, like that one, it draws **no header**: the
 * column names are known from the question, but a header over bars reads
 * as a table of empty rows rather than as rows on their way. Nothing here
 * is read out — `aria-hidden` all through; the result's live region says
 * the query is running (`label.status.querying`), which is the whole of
 * what is known.
 *
 * It stands where the result will stand and takes its height, so the
 * toolbar above it and the caption under it are where they will be when
 * the rows land: a blank frame that filled up as the answer arrived read
 * as a failure while it was blank, and moved under the reader's eyes when
 * it filled.
 */
export function AnalysisSkeleton({ layout, columns }: AnalysisSkeletonProps) {
  if (layout === 'chart')
    // The drawing's own gutter (`styles.css`, "A drawing keeps the column's
    // gutter") and all the height the result is given, which is what a
    // chart takes.
    return (
      <Skeleton
        data-slot="analysis-chart-skeleton"
        aria-hidden
        className="mx-4 mt-3 min-h-0 flex-1"
      />
    );

  // A question always has a column: one that measures nothing is refused
  // before it runs, and a skeleton is only drawn for a query that was sent.
  return (
    <div data-slot="analysis-table-skeleton" aria-hidden>
      <Table>
        <TableBody>
          {Array.from({ length: SKELETON_ROWS }, (_unused, index) => (
            <TableRow key={index}>
              {columns.map(column => (
                // A column given a width keeps it, as the rows will.
                <TableCell
                  key={column.alias}
                  style={column.width ? { width: column.width } : undefined}
                >
                  <Skeleton
                    className="h-4"
                    style={{ width: barWidth(column.label) }}
                  />
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

/**
 * The caption row while the first answer is on its way: the footer's own
 * slot and height, a bar where 「正在显示 N 组，耗时 X 秒」 will be, at the
 * right where the sentence sits. A footer that appeared with the rows
 * would take its height out of the result the moment they landed.
 */
export function CaptionSkeleton() {
  return (
    <div
      data-slot="analysis-caption"
      data-loading=""
      aria-hidden
      className="flex justify-end"
    >
      {/* One line of the caption's `text-sm`: 20px. */}
      <Skeleton className="h-5 w-40" />
    </div>
  );
}
