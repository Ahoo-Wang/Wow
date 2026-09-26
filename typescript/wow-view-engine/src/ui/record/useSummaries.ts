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

import { useMemo } from 'react';
import {
  pageSummaries,
  type RecordColumnView,
  type RecordPaging,
  type RecordRow,
  type SummaryRow,
} from '../../record/index.js';

/**
 * Whether the rows on this page are every row the conditions match: the
 * first page of a paged result whose total fits in it. Only where the source
 * said how many there are — a cursor result cannot tell its first page from
 * its only one, and neither can a paged one with no total.
 */
function holdsEveryRow(paging: RecordPaging | null): boolean {
  return (
    paging?.mode === 'paged' &&
    paging.index <= 1 &&
    paging.total !== undefined &&
    paging.total <= paging.size
  );
}

/**
 * The two scopes, from the one the runtime executed.
 *
 * The totals row is the one that costs a query; the page row is the rows on
 * screen added up, so it is derived here rather than asked for. When the
 * totals query failed the runtime already fell back to the page, and that
 * single row stands on its own — inventing the other one is exactly the
 * mistake the scope labels exist to prevent.
 *
 * When the page holds every row the conditions match there is one scope, not
 * two: the page row would repeat the totals under another name, and two
 * identical rows ask the reader to find a difference that is not there. The
 * totals row is the one kept, because it stays true when the result grows
 * past a page (D26 Q40, the exception to D18 V's two rows).
 */
export function useSummaries(
  summaries: SummaryRow | null,
  rows: readonly RecordRow[],
  paging: RecordPaging | null,
): readonly SummaryRow[] {
  const whole = holdsEveryRow(paging);
  return useMemo(() => {
    if (!summaries) return [];
    if (summaries.scope === 'page' || whole) return [summaries];
    return [pageSummaries(summaries.cells, rows), summaries];
  }, [summaries, rows, whole]);
}

/**
 * Whether a table's summary band has anything to say under its columns.
 *
 * The band is the numbers under the columns they summarise, with the scope
 * in a label beside them. A summary whose field is not a column on screen —
 * the column taken out of the table since the summary was configured — has
 * no cell to sit in, so a band of such summaries alone is a grey strip that
 * reads 「全部」 and nothing else, and in a board panel it is one more row
 * of height taken from the records. So there is no band unless at least one
 * drawn column carries a summary. The cards keep theirs: they list each
 * summary by its own label rather than under a column.
 */
export function summarisesColumns(
  summaries: SummaryRow | null,
  columns: readonly RecordColumnView[],
): boolean {
  if (!summaries) return false;
  const fields = new Set(columns.map(column => column.field));
  return summaries.cells.some(cell => fields.has(cell.field));
}
