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
import { rovingDestination, settleStop, takeStop } from '../roving.js';
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
 * The stop itself — who holds it, how it moves, where an arrow lands — is
 * `roving.ts`, which the analysis result's rows are a column of peers
 * under; what is left here is what makes this group a header row.
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
  // been broken by a column that left (`roving.ts`).
  useLayoutEffect(() => {
    const node = item.current;
    if (node) settleStop(members(node));
  });

  return {
    attach(node) {
      item.current = node;
    },
    item: {
      [STOP]: '',
      ...(onResize ? { 'aria-keyshortcuts': RESIZE_KEYS } : {}),
      onFocus: () => {
        const node = item.current;
        if (node) takeStop(node, members(node));
      },
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
      const to = rovingDestination(event.key, at, group.length, 'row');
      if (to === null || to === at) return false;
      const next = group[to];
      if (!next) return false;
      event.preventDefault();
      takeStop(next, group);
      next.focus();
      return true;
    },
  };
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
