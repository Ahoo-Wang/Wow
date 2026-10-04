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
type Landing = () => Element | null | undefined;

/**
 * Where a dialog the board opened hands the keyboard back as it closes. A
 * dialog opened from a menu item would return it to that item, which went
 * with the menu; so the board names the control that asked instead — the
 * edit bar's 「＋ 添加」, or the panel's own 「⋯」 — asked at closing, since
 * the board has changed under the dialog by then.
 */
export type FinalFocus = () => HTMLElement | boolean;

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
      found => !isBarred(found) && !found.hasAttribute(PASSED_OVER),
    ) ?? null
  );
}

/**
 * Marks a control a landing passes over though Tab stops on it: an ⓘ
 * beside a term (`TermTip`), which says what the term means on focus. A
 * press that opens the analysis tray landed on the first one and opened
 * its tooltip over the row, answering a question nobody had asked.
 */
export const PASSED_OVER = 'data-landing-skip';

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
    if (!keyboardFell()) return;
    focusableIn(where())?.focus();
  });
  return useCallback((where: Landing) => {
    pending.current = where;
  }, []);
}

/** Whether the keyboard is nowhere: on the body, or on a control gone dead. */
export function keyboardFell(): boolean {
  const active = document.activeElement;
  return (
    active === null ||
    active === document.body ||
    !active.isConnected ||
    (active as HTMLButtonElement).disabled === true
  );
}

/**
 * Where a popup was opened from, kept so its closing can still land near
 * it once it is gone (WCAG 2.4.3). A popup hands the keyboard back to its
 * opener — the trigger, or the control that had the keyboard — when it
 * closes; but the opener can go while the popup is open (a row a refresh
 * filtered out, a selection's bar gone with its selection, a panel taken
 * off the board), and a focus sent to a control that is no longer on the
 * page falls to `<body>`, where the next Tab starts the page again.
 */
interface Opener {
  /** The control that opened the popup. */
  readonly control: HTMLElement;
  /**
   * What the control sat in, nearest first: up to the popup it was in (a
   * menu's item that opened a dialog), else up to the outermost surface,
   * else up to the page's body (`openerAt`).
   */
  readonly within: readonly HTMLElement[];
  /** The opener of the popup the control was in, where it was in one. */
  readonly outer: Opener | undefined;
}

/** Each open popup's opener, by its popup element (`rememberOpener`). */
const openers = new WeakMap<Element, Opener>();

/**
 * Notes where `popup` was opened from, as it is drawn: the control with
 * the keyboard, or — when the keyboard was nowhere (a pointer press in
 * Safari focuses nothing) — the trigger that controls it. Once known it is
 * kept: by the next call the keyboard has moved into the popup.
 */
export function rememberOpener(popup: HTMLElement): void {
  if (openers.has(popup)) return;
  const doc = popup.ownerDocument;
  const active = doc.activeElement;
  const control =
    active instanceof HTMLElement &&
    active !== doc.body &&
    !popup.contains(active)
      ? active
      : triggerOf(popup);
  if (control) openers.set(popup, openerAt(control));
}

/** The element whose `aria-controls` names `popup` or a part of it. */
function triggerOf(popup: HTMLElement): HTMLElement | null {
  const doc = popup.ownerDocument;
  for (const element of doc.querySelectorAll<HTMLElement>('[aria-controls]')) {
    const id = element.getAttribute('aria-controls');
    const controlled = id ? doc.getElementById(id) : null;
    if (controlled && popup.contains(controlled)) return element;
  }
  return null;
}

/**
 * The opener at `control`, with what it sits in: up to the popup it is in,
 * else up to the outermost surface (`.fve-root`) around it — the host's
 * page beyond is the host's to focus — or to the body where there is none.
 */
function openerAt(control: HTMLElement): Opener {
  const within: HTMLElement[] = [];
  const body = control.ownerDocument.body;
  let surface = -1;
  for (
    let element = control.parentElement;
    element && element !== body;
    element = element.parentElement
  ) {
    within.push(element);
    const outer = openers.get(element);
    if (outer) return { control, within, outer };
    if (element.classList.contains('fve-root')) surface = within.length;
  }
  return {
    control,
    within: surface < 0 ? within : within.slice(0, surface),
    outer: undefined,
  };
}

/**
 * Where the keyboard lands when `popup` closes and the place it would go
 * back to has gone: the first control that takes it in the nearest part of
 * the page still standing around the opener — the opener itself while it
 * is there, then its row, its list, its band — and past the popup it was
 * opened from, that popup's opener's. `null` when nothing of it is left.
 */
export function landingFor(popup: Element): HTMLElement | null {
  return landingOf(openers.get(popup));
}

function landingOf(first: Opener | undefined): HTMLElement | null {
  for (let opener = first; opener; opener = opener.outer) {
    for (const place of [opener.control, ...opener.within]) {
      if (!place.isConnected) continue;
      const found = takerIn(place);
      if (found) return found;
    }
  }
  return null;
}

/** `place` when it takes the keyboard, else the first shown control in it. */
function takerIn(place: HTMLElement): HTMLElement | null {
  const candidates = [place, ...place.querySelectorAll<HTMLElement>(FOCUSABLE)];
  return (
    candidates.find(
      element =>
        element.matches(FOCUSABLE) &&
        !isBarred(element) &&
        !element.hasAttribute(PASSED_OVER) &&
        !element.closest('[hidden], [inert], [aria-hidden="true"]') &&
        element.checkVisibility?.() !== false,
    ) ?? null
  );
}

/**
 * What a closing popup hands the keyboard to (`finalFocus`), given what
 * its caller asked for: that, while it is still on the page; otherwise the
 * landing near the opener (`landingFor`). `true` asks for the opener, so it
 * stands while the opener does. `false` and nothing — no hand-back — pass
 * as they are.
 *
 * The answer is read while the popup is being taken off the page, and the
 * focus is sent a moment later; whatever goes in between (the opener and
 * the popup unmounted by the same render, a button the command disabled)
 * would still drop the keyboard. So once the hand-back has run, a keyboard
 * that fell anyway is put on the landing too.
 */
export function handBack<Asked>(
  popup: HTMLElement | null,
  asked: Asked,
): Asked | HTMLElement {
  if (!popup || asked === false || asked === undefined) return asked;
  const opener = openers.get(popup);
  if (!opener) return asked;
  // After the popup's own hand-back, which is queued behind this.
  queueMicrotask(() =>
    queueMicrotask(() => {
      if (keyboardFell()) landingOf(opener)?.focus({ preventScroll: true });
    }),
  );
  const gone =
    asked instanceof HTMLElement
      ? !asked.isConnected
      : !opener.control.isConnected;
  return gone ? (landingFor(popup) ?? asked) : asked;
}
