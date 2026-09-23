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

import { useEffect, useRef } from 'react';

/** Which list a press is in, as three selectors read from the press up. */
export interface ListShape {
  /** The element holding the items, as a selector above the press. */
  list: string;
  /** One item of it: `[data-slot="metric-card"]`. */
  item: string;
  /** The way to add one, for the list that can empty out. */
  add?: string;
}

/** The half of a press this needs, which is a click's own `currentTarget`. */
export interface Press {
  currentTarget: HTMLElement;
}

export interface ListFocus {
  /** Said from the press: the item at this index is going. */
  removing(press: Press, at: number): void;
  /** Said from the press: the item has landed here, going this way. */
  moved(press: Press, at: number, way: Move): void;
}

/** Which way a move button carries its item; also its `data-move`. */
export type Move = 'up' | 'down';

/** Anything the Tab key would stop on, before `disabled` is read. */
const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

/**
 * Where the keyboard stands after the control it was on leaves the page.
 *
 * **Why.** The editor and the visualization panel are lists of cards, and
 * every card carries the button that removes it. Pressing that button with
 * the keyboard removes the element the focus was on, and a focus with
 * nothing under it falls to `<body>` — the next Tab then starts the page
 * again, from the title bar, which for a user taking three dimensions out
 * means walking back down three times. The same happens to a move: the card
 * carries its own 「上移」, and moving it to the end disables the very button
 * being pressed.
 *
 * **The rule.** A removal leaves the keyboard where the row was — the item
 * that took its place, the one before it if the list ended there, and the
 * list's own 「添加」 when nothing is left. A move keeps the keyboard on the
 * button that made it, at whichever index the item landed on, so the next
 * press moves it again; where that direction has run out, the other one
 * takes the focus rather than the page losing it. Neither is a button kept
 * enabled at the boundary: a control that says it can do something it
 * cannot is a worse answer than a focus that moves one step sideways.
 *
 * **Why an effect and not the press.** The item is still on the page while
 * the press is being handled — it is React's next render that takes it away
 * — so the press only says what happened, and the effect after that render
 * is what places the focus. Nothing here waits on a timer: a timer would be
 * a guess about when React is finished, and it would move the focus out
 * from under whoever had taken it meanwhile.
 */
export function useListFocus(shape: ListShape): ListFocus {
  const pending = useRef<{
    list: HTMLElement;
    at: number;
    way?: Move;
  } | null>(null);
  // No dependency list: the render that follows the press is the one that
  // has taken the item away, and it is that render's effect that has a list
  // to look at. A press that changed nothing left nothing pending.
  useEffect(() => {
    const next = pending.current;
    if (!next) return;
    pending.current = null;
    place(next.list, next.at, shape, next.way);
  });
  const from = (press: Press) => press.currentTarget.closest(shape.list);
  return {
    removing(press, at) {
      const list = from(press);
      if (list instanceof HTMLElement) pending.current = { list, at };
    },
    moved(press, at, way) {
      const list = from(press);
      if (list instanceof HTMLElement) pending.current = { list, at, way };
    },
  };
}

/** The list as it is now, and the one control the keyboard should be on. */
function place(
  list: HTMLElement,
  at: number,
  shape: ListShape,
  way?: Move,
): void {
  const items = [...list.querySelectorAll<HTMLElement>(shape.item)];
  const landed = items[at];
  if (way) {
    if (!landed) return;
    const other: Move = way === 'up' ? 'down' : 'up';
    for (const which of [way, other]) {
      const button = landed.querySelector<HTMLElement>(
        `[data-move="${which}"]`,
      );
      if (button && !barred(button)) {
        button.focus();
        return;
      }
    }
    focusIn(landed);
    return;
  }
  // The index the removed item held is now the one after it; the end of the
  // list is the one before. Both are «where the row was», which is what a
  // keyboard means by staying put.
  if (focusIn(landed) || focusIn(items[at - 1])) return;
  const add = shape.add ? list.querySelector<HTMLElement>(shape.add) : null;
  if (add && !barred(add)) add.focus();
}

/**
 * The first control of one item, which is the item as a keyboard sees it;
 * whether there was one to focus. Also where the keyboard goes into a band
 * that was opened by a press (`WorkbenchShell`).
 */
export function focusIn(item: HTMLElement | null | undefined): boolean {
  if (!item) return false;
  const found = [item, ...item.querySelectorAll<HTMLElement>(FOCUSABLE)].find(
    node => node.matches(FOCUSABLE) && !barred(node),
  );
  if (!found) return false;
  found.focus();
  return true;
}

/** Whether the control is one focus would slide off again. */
function barred(node: HTMLElement): boolean {
  return (
    node.hasAttribute('disabled') ||
    node.getAttribute('aria-disabled') === 'true'
  );
}
