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

/**
 * What a record command works out before it writes, with no React in it.
 *
 * The cycle one header press walks, the ladder a page-size select offers,
 * the spelling "no summaries" has to be written in and the repairs a patch
 * carries along are all functions of a config and an argument. They lived
 * inside `useRecordTable`'s callbacks, where they could only be reached
 * through a rendered hook and a runtime; here they are read and tested on
 * their own, and the controller is left as edit-and-apply wiring.
 *
 * `recordColumns.ts` holds the same kind of function for a column list.
 */

import type {
  RecordSort,
  RecordSummary,
  RecordViewConfig,
  SortDirection,
  ViewInstance,
} from '../model/index.js';
import type { ViewRuntimeState } from '../runtime/index.js';
import {
  recordColumns,
  recordSort,
  recordSummaries,
  wasSound,
} from './recordDraft.js';

/**
 * The sort after one press on a column header: ascending, then descending,
 * then off.
 *
 * By default the field joins the sort — a new one at the end, an existing
 * one in its place, since the order of `sort` is the priority between
 * columns — so several columns may order the rows. With `exclusive` the
 * cycled field becomes the whole sort, wherever it stood: what a plain click
 * on a header means, where Shift is what adds.
 */
export function cycledSort(
  current: readonly RecordSort[],
  field: string,
  exclusive: boolean,
): RecordSort[] {
  const at = current.findIndex(entry => entry.field === field);
  // The same cycle either way: off → ascending → descending → off.
  const turned: SortDirection | null =
    at < 0 ? 'ASC' : current[at].direction === 'ASC' ? 'DESC' : null;
  if (exclusive)
    // This column alone: a plain click says "order the rows by this", not
    // "also by this".
    return turned === null ? [] : [{ field, direction: turned }];
  if (at < 0) return [...current, { field, direction: 'ASC' }];
  if (turned === null) return current.filter((_entry, index) => index !== at);
  return current.map((entry, index) =>
    index === at ? { field, direction: turned } : entry,
  );
}

/**
 * The page sizes worth offering, in order and without a repeat.
 *
 * The ladder is the engine's (`RuntimeLimits.pageSizes`): a product that
 * wants 15/30/60 hands it in with the budgets rather than shipping a build.
 * A size above `max` is refused by `validateRecord`, so offering it would be
 * offering a way to break the view — and the size the view is running at
 * joins whatever it is, since a view saved at 500 under an older limit still
 * has to show what it is running at, and a select whose value is not among
 * its options shows nothing at all.
 */
export function offeredPageSizes(
  ladder: readonly number[],
  max: number | undefined,
  current: number,
): number[] {
  const offered = ladder.filter(size => max === undefined || size <= max);
  return [...new Set([...offered, ...(current > 0 ? [current] : [])])].sort(
    (left, right) => left - right,
  );
}

/**
 * The summaries to write, in the shape the saved config uses for none.
 *
 * `summaries` is optional, so "no summaries" is spelled two ways — an empty
 * list, or no member at all — and `dirty` is an equality against the saved
 * config, which cannot tell the difference between a shape and a change.
 * Adding a summary and taking it away again therefore left the view unsaved
 * for the rest of the session, with the leave guard asking about an edit
 * that had already been undone. `edit` removes a member given as
 * `undefined`, so answering with the saved config's own spelling makes
 * undoing an undo.
 */
export function summariesOf(
  next: RecordSummary[],
  saved: ViewInstance | null,
): RecordSummary[] | undefined {
  if (next.length > 0) return next;
  const config = saved?.config;
  return config?.kind === 'record' && config.summaries !== undefined
    ? []
    : undefined;
}

/**
 * The patch, plus the sound form of any list the draft could not be read
 * from and this patch does not already replace.
 *
 * A list the controller had to repair is a config the kernel refuses over
 * entries that are not on screen — they could not be read, so no control
 * lists them and no control can take them out. Carrying the repair along
 * with whatever the user *did* change is what makes "the first change they
 * make writes the sound list back" true of every list rather than only of
 * the one they touched: with no sortable field left to add, an unreadable
 * `sort` had no other way out at all.
 */
export function repairing(
  patch: Partial<RecordViewConfig>,
  state: ViewRuntimeState<RecordViewConfig>,
): Partial<RecordViewConfig> {
  const draft = state.draft;
  const repairs: Partial<RecordViewConfig> = {};

  const sort = recordSort(draft.sort);
  if (patch.sort === undefined && !wasSound(draft.sort, sort))
    repairs.sort = sort;

  const summaries = recordSummaries(draft.summaries);
  if (
    !('summaries' in patch) &&
    draft.summaries !== undefined &&
    !wasSound(draft.summaries, summaries)
  )
    repairs.summaries = summariesOf(summaries, state.saved);

  const columns = recordColumns(draft.table?.columns);
  if (patch.table === undefined && !wasSound(draft.table?.columns, columns))
    repairs.table = { columns };

  return { ...repairs, ...patch };
}
