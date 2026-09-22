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
 * A group of peers as one Tab stop, with the arrows inside it.
 *
 * ARIA's answer to a row of headers, a column of result rows or a bar of
 * buttons is the same one: the group is one stop on the Tab route, and the
 * arrow keys move between its members. Two places here need it over
 * elements React does not own the `tabindex` of — the record header
 * (`record/headerRoving.ts`) and the analysis result's rows
 * (`AnalysisTable.tsx`) — so the three facts they share live here: which
 * member holds the stop, how the stop moves, and where an arrow lands.
 *
 * `tabindex` is written on the node rather than rendered as a prop on
 * purpose: React is not told, so it never renders the value back over a
 * stop the keyboard has moved, and a natively focusable member would
 * otherwise be a stop of its own for the frame before the first effect.
 */

/** Which way the arrows run through a group. */
export type RovingAxis = 'row' | 'column';

/** Whether this member is the one the group's Tab stop is on. */
export function holdsStop(node: Element): boolean {
  return node.getAttribute('tabindex') === '0';
}

/** Moves the stop onto one member, which is what focus means to a group. */
export function takeStop(
  node: Element | null,
  group: readonly HTMLElement[],
): void {
  if (!node) return;
  for (const one of group)
    one.setAttribute('tabindex', one === node ? '0' : '-1');
}

/**
 * Keeps the invariant «exactly one member holds the stop».
 *
 * Called from a layout effect with no dependency list, because every render
 * of the group is a chance for the holder to have left — a column hidden, a
 * result replaced by a shorter one — and a group with no stop is a group
 * the Tab key walks straight past.
 */
export function settleStop(group: readonly HTMLElement[]): void {
  const first = group[0];
  if (!first) return;
  takeStop(group.find(holdsStop) ?? first, group);
}

/**
 * Where an arrow lands, or `null` when the key is not one of the group's.
 *
 * The ends do not wrap: past the last column lies the first row, and past
 * the last row lies whatever follows the table, so a group that sends a
 * reader back to member one is a group with no way out. `Toolbar` wraps
 * because a bar is a closed set of controls; these are not.
 */
export function rovingDestination(
  key: string,
  at: number,
  count: number,
  axis: RovingAxis,
): number | null {
  const back = axis === 'row' ? 'ArrowLeft' : 'ArrowUp';
  const on = axis === 'row' ? 'ArrowRight' : 'ArrowDown';
  if (key === back) return Math.max(0, at - 1);
  if (key === on) return Math.min(count - 1, at + 1);
  if (key === 'Home') return 0;
  if (key === 'End') return count - 1;
  return null;
}
