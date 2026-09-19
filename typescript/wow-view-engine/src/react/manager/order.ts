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
 * Where a row goes when it moves: pure arithmetic over orders of ids, with the
 * audience groups the sidebar renders taken into account.
 *
 * A move reasons over ids alone — the order a queued move computed has nothing
 * but ids — so the audience of each row arrives as a lookup rather than being
 * carried along with the summaries.
 */

import type { ViewAudience } from '../../model/index.js';

export type MoveDirection = 'up' | 'down';

/**
 * The order a queued move computed, while the list it was computed from is
 * still a reload behind it.
 *
 * `base` is the full rendered order it started from: once the list is no
 * longer that, the reload has landed and the rendered order is the truth
 * again. The two orders after the swap are both kept, because they answer
 * different questions — `order` is what was submitted, and `visible` is what
 * the next move looks for a neighbour in. Which inputs the move was raised
 * under is the caller's to tag on; this module only does the arithmetic.
 */
export interface OptimisticOrder {
  /** The full rendered order, unfiltered, before this move. */
  base: readonly string[];
  /** The full order this move submitted. */
  order: readonly string[];
  /** The narrowed order as this move left it. */
  visible: readonly string[];
}

/**
 * The two orders one swap produces; see {@link OptimisticOrder}. Both are
 * fresh arrays the caller owns — one of them is what it submits.
 */
export interface MovedOrder {
  order: string[];
  visible: string[];
}

export function sameOrder(
  one: readonly string[],
  other: readonly string[],
): boolean {
  return one.length === other.length && one.every((id, at) => id === other[at]);
}

/**
 * The index the row would swap with: the nearest one in that direction that
 * the sidebar shows in the same group, or -1 when there is none.
 *
 * Audience is the reason this is not `index ± 1`. Both lists render personal
 * views above shared ones whatever order is stored, so the row above a
 * shared view on screen may be a personal one, and swapping the two would
 * store a new order, spend a revision and move nothing anybody can see.
 *
 * It walks an order of ids rather than the summaries themselves, because the
 * order a queued move computed is the one the next move has to reason over
 * while a row's audience is the same wherever that order puts it. Both
 * indices a swap needs then come from the same list.
 */
export function neighbourOf(
  order: readonly string[],
  audiences: ReadonlyMap<string, ViewAudience>,
  id: string,
  direction: MoveDirection,
): number {
  const from = order.indexOf(id);
  const audience = from < 0 ? undefined : audiences.get(id);
  if (audience === undefined) return -1;
  const step = direction === 'up' ? -1 : 1;
  for (let at = from + step; at >= 0 && at < order.length; at += step)
    if (audiences.get(order[at]) === audience) return at;
  return -1;
}

/**
 * What one move submits and what it leaves on screen, or null when there is
 * nothing to move.
 *
 * The pair to swap is found among the rows the user can see, and in the same
 * group: the two lists render personal views above shared ones, so a swap
 * across that boundary would store a new order and move nothing on screen. A
 * row at either end of its own audience has nowhere to go, and a row the list
 * no longer holds cannot be placed — submitting the order unchanged would
 * still cost a revision and still be able to conflict.
 *
 * The swap itself happens in the *full* order. Submitting the visible one
 * would store a list with every other kind's id missing, and the store keeps
 * one order for the whole definition: a record workbench reordering its own
 * views would silently drop the analyses.
 */
export function planMove(
  visible: readonly string[],
  full: readonly string[],
  audiences: ReadonlyMap<string, ViewAudience>,
  id: string,
  direction: MoveDirection,
): MovedOrder | null {
  const from = visible.indexOf(id);
  const to = neighbourOf(visible, audiences, id, direction);
  if (from < 0 || to < 0) return null;
  const other = visible[to];
  const order = [...full];
  const at = order.indexOf(id);
  const otherAt = order.indexOf(other);
  if (at < 0 || otherAt < 0) return null;
  [order[at], order[otherAt]] = [order[otherAt], order[at]];
  const moved = [...visible];
  [moved[from], moved[to]] = [moved[to], moved[from]];
  return { order, visible: moved };
}
