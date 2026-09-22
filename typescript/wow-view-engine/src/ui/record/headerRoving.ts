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

import type * as React from 'react';
import { useLayoutEffect, useRef } from 'react';
import { resizeByKey } from './ColumnResizer.js';

/**
 * The attribute one header's focusable item wears, so the group can find its
 * members without anything above the cells holding a list of them.
 */
const STOP = 'data-header-stop';

/** Alt and an arrow, as ARIA writes a shortcut. */
const RESIZE_KEYS = 'Alt+ArrowLeft Alt+ArrowRight';

/** What marks an element as this row's item, and what claims the stop. */
export type RovingItem = { [K in typeof STOP]: '' } & {
  'aria-keyshortcuts'?: string;
  onFocus(): void;
};

export interface RovingHeader {
  /** Goes on the header's focusable element — the sort button, or the cell. */
  attach(node: HTMLElement | null): void;
  /** Spread on that same element. */
  item: RovingItem;
  /** Returns whether the key was the group's; the caller then stands down. */
  onKeyDown(event: React.KeyboardEvent<HTMLElement>): boolean;
}

/**
 * One header row as a single tab stop, with the arrows inside it.
 *
 * **Why.** Every column used to bring its own stops — a sort button and a
 * resize handle — so a twenty-column table stood twenty-eight stops between
 * the toolbar and the first row, and a keyboard reaching the rows had to
 * pass every edge of every column. ARIA's answer to a row of peers is a
 * roving tabindex, which is what `Toolbar` already gives the bar above this
 * table: one stop for the row, ←/→ between the columns, Home/End to the
 * ends.
 *
 * **Why the group organises itself.** The row is drawn in `RecordTable`,
 * which maps the columns; the stop could have been decided there and passed
 * down, but then every header would re-render whenever the focus moved, and
 * the one number they would share is already written on the DOM as
 * `tabindex`. So each header asks the row it is in: *does anybody hold the
 * stop?* — if not, it takes it, and otherwise it stands at −1. That runs
 * after every render, which is also what heals the group when a column is
 * added, hidden or reordered: the holder leaving is a render of the row.
 *
 * `tabindex` is written on the node rather than rendered as a prop for the
 * same reason — React is not told, so it never renders the value back, and a
 * sort button is natively focusable and would otherwise be a stop for the
 * frame before the first effect.
 *
 * **What Alt buys.** The plain arrows walk the columns, so the width needs a
 * modifier: Alt+←/→ resizes the focused column by the handle's own contract
 * (Shift for the long step, Alt+Enter back to automatic), which is what the
 * handle answers to this day — it simply is not on the Tab route any more.
 * The shortcut is on the item as `aria-keyshortcuts`, because a resize that
 * is only in the documentation is a resize a reader never hears about.
 */
export function useRovingHeader({
  onResize,
}: {
  /** Left out — an embedded table with no controller — Alt does nothing. */
  onResize?(field: string, width: number | null): void;
}): RovingHeader {
  const item = useRef<HTMLElement | null>(null);

  // No dependency list: the invariant is «exactly one member of this row
  // holds the stop», and every render of the row is a chance for it to have
  // been broken by a column that left.
  useLayoutEffect(() => {
    const node = item.current;
    if (!node) return;
    const holder = members(node).find(one => held(one));
    node.setAttribute('tabindex', !holder || holder === node ? '0' : '-1');
  });

  return {
    attach(node) {
      item.current = node;
    },
    item: {
      [STOP]: '',
      ...(onResize ? { 'aria-keyshortcuts': RESIZE_KEYS } : {}),
      onFocus: () => take(item.current),
    },
    onKeyDown(event) {
      const node = item.current;
      if (!node) return false;
      // Alt is the column's width; the arrows on their own are the row.
      if (event.altKey) {
        const handle = handleOf(node);
        if (!handle || !onResize) return false;
        if (!resizeByKey(handle, event, onResize)) return false;
        event.preventDefault();
        return true;
      }
      if (event.ctrlKey || event.metaKey || event.shiftKey) return false;
      const group = members(node);
      const at = group.indexOf(node);
      const to = destination(event.key, at, group.length);
      if (to === null || to === at) return false;
      event.preventDefault();
      const next = group[to];
      take(next);
      next.focus();
      return true;
    },
  };
}

/** Where an arrow lands, or `null` when the key is not one of the group's. */
function destination(key: string, at: number, count: number): number | null {
  // The ends do not wrap: past the last column lies the first row, and a
  // header row that sends a reader back to column one is a row with no way
  // out. `Toolbar` wraps because a bar is a closed set of controls; a header
  // is the top of the table under it.
  if (key === 'ArrowLeft') return Math.max(0, at - 1);
  if (key === 'ArrowRight') return Math.min(count - 1, at + 1);
  if (key === 'Home') return 0;
  if (key === 'End') return count - 1;
  return null;
}

/**
 * This header's own resize handle, found through the cell they share.
 *
 * The handle is not passed in: it is drawn by `SortableHeader` beside the
 * item, both inside the one `<th>`, and a ref threaded through two elements
 * to say what `closest` already knows would be a second copy of the truth.
 */
function handleOf(node: HTMLElement): HTMLElement | null {
  return (
    node
      .closest('th')
      ?.querySelector<HTMLElement>('[data-slot="column-resizer"]') ?? null
  );
}

/** The items of the row this one is in, in the order they are drawn. */
function members(node: HTMLElement): HTMLElement[] {
  const row = node.closest('tr');
  return row ? [...row.querySelectorAll<HTMLElement>(`[${STOP}]`)] : [];
}

/** Whether this item is the one the row's Tab stop is on. */
function held(node: HTMLElement): boolean {
  return node.getAttribute('tabindex') === '0';
}

/** Moves the stop onto one item, which is what focus means to a group. */
function take(node: HTMLElement | null): void {
  if (!node) return;
  for (const one of members(node))
    one.setAttribute('tabindex', one === node ? '0' : '-1');
}
