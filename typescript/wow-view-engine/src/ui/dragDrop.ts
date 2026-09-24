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
 * What every list here makes of a finished drag, before it makes anything of
 * its own — beside `dragAnnounce.ts`, which is what the same drag says out
 * loud.
 */

/** As much of a finished drag as a drop reads. */
export interface DropOperation {
  source?: { id: string | number } | null;
  target?: { id: string | number } | null;
}

/** The two items one drop is between, by the ids the library carried. */
export interface Drop {
  source: string;
  target: string;
}

/**
 * The drop this was, or null — the one guard every sortable list here
 * shares.
 *
 * A drag that was given up is not a drop, and neither is one that ended on
 * the row it started from: both would spend a write — a query, a revision —
 * to put the list back in the order it is already in. Everything past that
 * is the list's own business: which entry a position names, whether the two
 * rows are in the same audience, whether both aliases name a series this
 * chart draws.
 *
 * The ids come back as strings because that is what the lists read them as:
 * the library types them `string | number`, and a numeric id compared
 * against a field name would be a comparison that never matched.
 */
export function dropped(
  operation: DropOperation,
  canceled: boolean | undefined,
): Drop | null {
  const { source, target } = operation;
  if (canceled || !source || !target || source.id === target.id) return null;
  return { source: String(source.id), target: String(target.id) };
}
