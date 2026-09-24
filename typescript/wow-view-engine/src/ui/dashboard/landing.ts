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

import { useCallback, useEffect, useRef } from 'react';

/** Where the keyboard should go, looked up once the press has been drawn. */
export type Landing = () => Element | null | undefined;

/** Anything the Tab key would stop on, before `disabled` is read. */
const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

/**
 * Where the keyboard goes after a press on the filter bar takes away what
 * it pressed (U-02) — the panel rule (`useListFocus`, `history.land()`)
 * brought to the bar: a filter's ✕ goes with the value it cleared, 「清空」
 * is disabled by its own press, 「完成接线」 goes with the wiring bar, and
 * 「移除筛选」 with the chip its settings hang off. A focus with nothing
 * under it falls to `<body>`, and the next Tab starts the page again.
 *
 * The press says where the keyboard should land, as a lookup; the effect
 * after the render that took the control away runs it — the control is
 * still on the page while the press is being handled. Only a keyboard that
 * really fell is moved: one that something else took meanwhile (a menu, a
 * popover, a dialog) stays where it is.
 *
 * The component holding this must render after the press, which every
 * press here causes: the bar and the board are drawn from what it changed.
 */
export function useLanding(): (where: Landing) => void {
  const pending = useRef<Landing | null>(null);
  // No dependency list: the render after the press is the one whose effect
  // has the new page to look at.
  useEffect(() => {
    const where = pending.current;
    if (!where) return;
    pending.current = null;
    if (!fell()) return;
    focusIn(where())?.focus();
  });
  return useCallback((where: Landing) => {
    pending.current = where;
  }, []);
}

/** Whether the keyboard is nowhere: on the body, or on a control gone dead. */
function fell(): boolean {
  const active = document.activeElement;
  return (
    active === null ||
    active === document.body ||
    !active.isConnected ||
    (active as HTMLButtonElement).disabled === true
  );
}

/** The element itself when it takes the keyboard, else the first inside it that does. */
export function focusIn(
  element: Element | null | undefined,
): HTMLElement | null {
  if (!(element instanceof HTMLElement)) return null;
  if (element.matches(FOCUSABLE) && !barred(element)) return element;
  return (
    [...element.querySelectorAll<HTMLElement>(FOCUSABLE)].find(
      found => !barred(found),
    ) ?? null
  );
}

function barred(element: HTMLElement): boolean {
  return (
    (element as HTMLButtonElement).disabled === true ||
    element.getAttribute('aria-disabled') === 'true'
  );
}

/** The board a control is on: the grid's column, header and all. */
export function boardOf(from: Element | null | undefined): Element | null {
  return from?.closest('[data-slot="dashboard-grid"]') ?? null;
}

/** One filter's chip on the bar, by the filter's name. */
export function chipOf(
  board: Element | null,
  name: string,
): HTMLElement | undefined {
  return chipsOf(board).find(chip => chip.dataset.filter === name);
}

/** The filters' chips on the bar, in its order. */
export function chipsOf(board: Element | null): HTMLElement[] {
  return [
    ...(board?.querySelectorAll<HTMLElement>(
      '[data-slot="dashboard-filter"]',
    ) ?? []),
  ];
}

/** A chip's value control: what a reader edits the filter with. */
export function valueOf(chip: Element | null | undefined): Element | null {
  return chip?.querySelector('[data-slot="filter-value"]') ?? chip ?? null;
}

/** 「撤销」 on the edit bar: where a step that took its own control away lands. */
export function undoOf(board: Element | null): Element | null {
  return board?.querySelector('[data-slot="dashboard-undo"]') ?? null;
}

/** 「添加筛选」 on the edit bar. */
export function addFilterOf(board: Element | null): Element | null {
  return board?.querySelector('[data-slot="dashboard-add-filter"]') ?? null;
}
