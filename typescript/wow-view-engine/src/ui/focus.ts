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

/**
 * Where the keyboard goes when a press takes away what it pressed — the
 * one set of rules the analysis cards (`useListFocus`), the board's filter
 * bar (`dashboard/landing.ts`), the filter band's actions and the
 * workbench's bands share. It lived twice, in `analysis/listFocus.ts` and
 * `dashboard/landing.ts`, and the filter band reached into both (2026-09-27
 * quality review: a helper in a feature folder is a cycle waiting).
 */

/** Anything the Tab key would stop on, before `disabled` is read. */
export const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

/** Where the keyboard should go, looked up once the press has been drawn. */
export type Landing = () => Element | null | undefined;

/** Whether the control is one focus would slide off again. */
export function isBarred(element: HTMLElement): boolean {
  return (
    element.hasAttribute('disabled') ||
    (element as HTMLButtonElement).disabled === true ||
    element.getAttribute('aria-disabled') === 'true'
  );
}

/** The element itself when it takes the keyboard, else the first inside it that does. */
export function focusableIn(
  element: Element | null | undefined,
): HTMLElement | null {
  if (!(element instanceof HTMLElement)) return null;
  if (element.matches(FOCUSABLE) && !isBarred(element)) return element;
  return (
    [...element.querySelectorAll<HTMLElement>(FOCUSABLE)].find(
      found => !isBarred(found),
    ) ?? null
  );
}

/**
 * Focuses the first control of `item` — the item as a keyboard sees it —
 * and says whether there was one.
 */
export function focusIn(item: Element | null | undefined): boolean {
  const found = focusableIn(item);
  if (!found) return false;
  found.focus();
  return true;
}

/**
 * Where the keyboard goes after a press takes away what it pressed (U-02):
 * a filter's ✕ goes with the value it cleared, 「清空」 is disabled by its
 * own press, 「完成接线」 goes with the wiring bar. A focus with nothing under
 * it falls to `<body>`, and the next Tab starts the page again.
 *
 * The press says where the keyboard should land, as a lookup; the effect
 * after the render that took the control away runs it — the control is
 * still on the page while the press is being handled. Only a keyboard that
 * really fell is moved: one that something else took meanwhile (a menu, a
 * popover, a dialog) stays where it is.
 *
 * The component holding this must render after the press.
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
    focusableIn(where())?.focus();
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
