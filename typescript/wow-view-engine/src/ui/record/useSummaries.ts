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
  type RecordRow,
  type SummaryRow,
} from '../../record/index.js';

/**
 * The two scopes, from the one the runtime executed.
 *
 * The totals row is the one that costs a query; the page row is the rows on
 * screen added up, so it is derived here rather than asked for. When the
 * totals query failed the runtime already fell back to the page, and that
 * single row stands on its own — inventing the other one is exactly the
 * mistake the scope labels exist to prevent.
 */
export function useSummaries(
  summaries: SummaryRow | null,
  rows: readonly RecordRow[],
): readonly SummaryRow[] {
  return useMemo(() => {
    if (!summaries) return [];
    if (summaries.scope === 'page') return [summaries];
    return [pageSummaries(summaries.cells, rows), summaries];
  }, [summaries, rows]);
}
