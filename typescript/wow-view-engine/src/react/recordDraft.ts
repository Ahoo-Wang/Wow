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
 * The draft's lists as a control may read them.
 *
 * **The controller is the boundary.** A config comes from a store, so
 * `sort` may be an object, a string or an array with `null` in it, and
 * `summaries` may be any of those too. `validateRecord` reports the shape
 * (`record.sort.invalid`, `record.summaries.invalid`) and the draft rightly
 * stays in the error state — but the editor that would let the user delete
 * the offending entry is rendered from that same draft, and a `.map` over a
 * string takes the workbench down before they can. So what a controller
 * hands the UI is always a safely iterable list of well-formed entries,
 * whatever the store held, and no panel needs a defensive reading of its
 * own: the next one someone writes inherits the guarantee.
 *
 * Dropping a malformed entry here is also the repair. The list the user
 * edits is the sound one, so the first change they make writes it back
 * without the entry that was never readable, and admission goes quiet.
 */

import {
  columnPin,
  SUMMARY_FUNCTIONS,
  type RecordColumn,
  type RecordSort,
  type RecordSummary,
  type SortDirection,
  type SummaryFunction,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';

/** Entries of `value` that are objects naming a `field`; none if it is not a list. */
function entries(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is Record<string, unknown> =>
      isPlainObject(entry) && typeof entry.field === 'string',
  );
}

/**
 * A direction as one of the two there are.
 *
 * Anything else reads as ascending rather than being dropped: the entry
 * names a field the user chose, and the direction is the part they can flip
 * once they can see it. `validateRecord` reports the value separately.
 */
export function sortDirection(value: unknown): SortDirection {
  return value === 'DESC' ? 'DESC' : 'ASC';
}

export function recordSort(value: unknown): RecordSort[] {
  return entries(value).map(entry => ({
    field: entry.field as string,
    direction: sortDirection(entry.direction),
  }));
}

/**
 * Summaries as cells that can be drawn. An entry whose function is not one
 * the model names is dropped rather than guessed at — there is no sensible
 * default for "which number is this", and a wrong one would be a figure on
 * screen that nobody asked for.
 */
export function recordSummaries(value: unknown): RecordSummary[] {
  return entries(value).flatMap(entry =>
    SUMMARY_FUNCTIONS.includes(entry.fn as SummaryFunction)
      ? [{ field: entry.field as string, fn: entry.fn as SummaryFunction }]
      : [],
  );
}

/**
 * Whether `raw` was already the sound list `read` made of it.
 *
 * A list that had to be repaired is a config the kernel refuses
 * (`record.sort.invalid`, `record.summaries.invalid`, `record.table.invalid`)
 * over entries no control can address — they are not on screen, because they
 * could not be read. So the next write of any kind carries the repair with
 * it: the user changes a column and the sort that was a string becomes the
 * list they can see, which is the same promise this module makes about
 * entries, made about the list itself.
 */
export function wasSound(raw: unknown, read: readonly unknown[]): boolean {
  return Array.isArray(raw) && raw.length === read.length;
}

/** Columns as the draft holds them, with a width and a pinning it can use. */
export function recordColumns(value: unknown): RecordColumn[] {
  return entries(value).map(entry => {
    const pinned = columnPin(entry.pinned);
    return {
      field: entry.field as string,
      ...(typeof entry.width === 'number' ? { width: entry.width } : {}),
      ...(pinned === null ? {} : { pinned }),
    };
  });
}
