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
 * What the sort editor makes of a drag: which entry a drop moves where, the
 * order that comes out of it, and what a screen reader hears meanwhile.
 *
 * All three are here rather than inside the popover because none of them
 * needs a DOM to be true, and the drop is the one decision a pointer makes
 * that jsdom cannot reach — `@dnd-kit/dom` picks its target by measuring
 * boxes, and every box there is 0×0 at the origin.
 */

import type { RecordSort } from '../../model/index.js';
import { dragAccessibility } from '../dragAnnounce.js';
import { dropped, type DropOperation } from '../dragDrop.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/** The two places one drop is between, as positions in the sort. */
export interface SortDrop {
  from: number;
  to: number;
}

/**
 * A sort entry is identified by its place rather than by its field.
 *
 * One config may order by the same field twice — the kernel refuses it
 * (`record.sort.duplicate`) and the editor still lists both, because the
 * whole point of listing them is that one of them can be taken out. Two
 * items under one id is not something the library can hold apart, so the
 * position is the identity here, exactly as it is in the list's React key.
 */
const ENTRY_ID = 'sort-entry-';

/** The library's id for the entry at this place. */
export function sortEntryId(index: number): string {
  return `${ENTRY_ID}${index}`;
}

/** The place an id names, or null when it names nothing this list drew. */
export function sortEntryIndex(id: string): number | null {
  if (!id.startsWith(ENTRY_ID)) return null;
  const at = Number(id.slice(ENTRY_ID.length));
  return Number.isInteger(at) && at >= 0 ? at : null;
}

/**
 * The move this drop is, or null.
 *
 * Whether there was a drop at all is the one guard every list here shares
 * ({@link dropped}) — a drag that was given up is not a drop, and neither is
 * one that ended on the entry it started from: both would spend an `edit` and
 * an `apply` — a query and a revision — to put the rows back in the order they
 * are already in. What is added on top is that the two ids have to name places
 * in this list.
 */
export function sortDrop(
  operation: DropOperation,
  canceled: boolean | undefined,
): SortDrop | null {
  const drop = dropped(operation, canceled);
  if (!drop) return null;
  const from = sortEntryIndex(drop.source);
  const to = sortEntryIndex(drop.target);
  if (from === null || to === null || from === to) return null;
  return { from, to };
}

/**
 * The whole sort after one entry moves, or null when the move changes
 * nothing — an end of the list, a place that is not in it, a drop on the
 * entry it started from.
 *
 * The answer is the whole order rather than a pair of indexes because that
 * is what `setSort` takes: which field comes first is one write, the same
 * write that flips a direction or drops an entry, and reordering is not a
 * different kind of edit from those.
 */
export function reorderSort(
  sort: readonly RecordSort[],
  from: number,
  to: number,
): RecordSort[] | null {
  const moved = sort[from];
  if (moved === undefined || to < 0 || to >= sort.length || to === from)
    return null;
  const rest = sort.filter((_entry, at) => at !== from);
  return [...rest.slice(0, to), moved, ...rest.slice(to)];
}

/**
 * What a screen reader hears while a sort entry is being carried: the shared
 * wording of a drag ({@link dragAccessibility}) said in this editor's own
 * words, with the ids the library is holding turned back into the field
 * labels on screen.
 */
export function sortDragAccessibility(
  messages: MessageFormatters,
  labelFor: (id: string) => string,
) {
  return dragAccessibility(
    messages.label('label.sort.instructions'),
    {
      picked: field => messages.label('label.sort.picked', { field }),
      cancelled: field => messages.label('label.sort.cancelled', { field }),
    },
    labelFor,
  );
}
