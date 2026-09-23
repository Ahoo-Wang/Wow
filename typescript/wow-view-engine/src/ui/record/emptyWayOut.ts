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
 * The one way out of a query that matched nothing, by what was asked.
 *
 * - `restore` — a saved view whose conditions were added to: the rows are
 *   missing because of what the reader added, so the way out is back to
 *   the conditions the view was saved with, not to none. Clearing them
 *   would turn 「不可恢复」 into 「全部」 — another view under its name.
 * - `edit` — a saved view asking exactly what it was saved to ask: the view
 *   is empty right now, which is an answer. Its conditions are what it is,
 *   so nothing is taken away; the way on is to ask something else.
 * - `clear` — a view never saved, under conditions: nothing to go back to,
 *   so the conditions go and what there is shows.
 * - `add` — no conditions at all: everything there is was shown, and the
 *   only thing left is a question.
 */
export type EmptyWayOut = 'restore' | 'edit' | 'clear' | 'add';

export function emptyWayOut(
  /** `conditionsDrifted`: `null` for a view never saved. */
  drifted: boolean | null,
  hasConditions: boolean,
): EmptyWayOut {
  if (drifted === null) return hasConditions ? 'clear' : 'add';
  if (drifted) return 'restore';
  return hasConditions ? 'edit' : 'add';
}
