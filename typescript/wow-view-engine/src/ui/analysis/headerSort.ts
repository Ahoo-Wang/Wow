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

import { useCallback, useState } from 'react';
import type { AnalysisSort } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { cycledSort } from '../../react/recordEdits.js';

/** What the analysis table's headers read and write the sort through. */
export interface HeaderSorting {
  /** The order the rows on screen are in: the sort of the config that ran. */
  sort: readonly AnalysisSort[];
  /**
   * The draft's sort: the order a press is worked out from, and the one
   * Apply will run while a sort waits for it.
   */
  drafted: readonly AnalysisSort[];
  /**
   * The sort one plain press on a column's header would leave — what its
   * name says the press does, worked out exactly as `onToggle` will.
   */
  next(alias: string): AnalysisSort[];
  /**
   * One press on a column's header. A plain press orders the groups by that
   * column alone; `exclusive: false` — Shift, Ctrl or ⌘ held — adds it to
   * the order instead, as the record table's header does.
   */
  onToggle(alias: string, options?: { exclusive?: boolean }): void;
}

/**
 * The sort after one plain press on a header: ascending, descending, then
 * back to the view's own order.
 *
 * The record table's third press takes the column out of the sort, and
 * there that is the view's order — a record view with no sort is still the
 * rows the conditions select. An analysis is not: its sort decides **which**
 * groups 「前 N 组」 keeps, so the order a header press took the table away
 * from is the one it has to come back to, whatever it was (`base`: the sort
 * in force when the presses began). Where that order already was this
 * column descending, descending *is* the way back, and the column turns
 * between its two directions.
 */
export function headerSorted(
  current: readonly AnalysisSort[],
  alias: string,
  base: readonly AnalysisSort[],
): AnalysisSort[] {
  const lead = current[0];
  if (lead?.alias !== alias) return [{ alias, direction: 'ASC' }];
  if (lead.direction === 'ASC') return [{ alias, direction: 'DESC' }];
  return sameSort(base, current) ? [{ alias, direction: 'ASC' }] : [...base];
}

/**
 * The header's half of the analysis editor: the order on screen, and a press
 * written through the editor.
 *
 * **It runs when nothing else waits** (`sortNow`). A sort is a question
 * member, so a tray edit to it runs on its own a moment later (「改了就跑」)
 * — but not while the range waits for Apply, nor with auto-run switched off,
 * and a header that answered a press by changing nothing on screen would
 * look broken; so a press on its own runs at once, as the record table's
 * header does. But it never applies what else the draft holds: with a range
 * condition or a tray edit waiting, the sort joins them, the pending dot
 * shows on Apply, and Apply runs them together — the metric card's whole
 * keeps the same rule.
 *
 * Two readings of the sort, on purpose. The arrows and `aria-sort` say the
 * order of the rows on screen (`shown`, the config that ran): a header that
 * pointed down over rows that go up would be lying about them. The next
 * press is worked out from the draft's sort, which is that same order
 * whenever nothing waits and otherwise the order Apply is about to run —
 * so pressing twice while it waits still goes ascending, then descending.
 * `base` remembers the order the presses started from, for as long as the
 * draft's sort is the one a press wrote; a sort that arrived any other way
 * — the tray, a saved view, a revert — starts a new run of presses from
 * itself.
 */
export function useHeaderSort(
  analysis: AnalysisEditorController,
  shown: readonly AnalysisSort[],
): HeaderSorting {
  // State rather than a ref: the header's name reads it while rendering,
  // to say what the next press will do.
  const [presses, setPresses] = useState<{
    base: readonly AnalysisSort[];
    wrote: readonly AnalysisSort[];
  } | null>(null);
  const { sort: drafted, sortNow } = analysis;
  const base =
    presses && sameSort(presses.wrote, drafted) ? presses.base : drafted;
  const next = useCallback(
    (alias: string) => headerSorted(drafted, alias, base),
    [drafted, base],
  );
  const onToggle = useCallback(
    (alias: string, options?: { exclusive?: boolean }) => {
      const sorted =
        options?.exclusive === false
          ? cycledSort(asFields(drafted), alias, false).map(entry => ({
              alias: entry.field,
              direction: entry.direction,
            }))
          : next(alias);
      setPresses({ base, wrote: sorted });
      sortNow(sorted);
    },
    [drafted, base, next, sortNow],
  );
  return { sort: shown, drafted, next, onToggle };
}

/** Whether two sorts order the groups the same way. */
function sameSort(
  one: readonly AnalysisSort[],
  other: readonly AnalysisSort[],
): boolean {
  return (
    one.length === other.length &&
    one.every(
      (entry, index) =>
        entry.alias === other[index]?.alias &&
        entry.direction === other[index]?.direction,
    )
  );
}

function asFields(sort: readonly AnalysisSort[]) {
  return sort.map(entry => ({
    field: entry.alias,
    direction: entry.direction,
  }));
}
