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

import { createContext, useContext, useEffect, useMemo, useRef } from 'react';
import { focusIn } from '../analysis/listFocus.js';

/**
 * What a condition belongs to on screen: the group block it sits in, or —
 * in simple mode, where the root is drawn as a strip and not as a block —
 * the panel itself.
 */
const OWNER = '[data-slot="filter-group"], [data-slot="filter-panel"]';

/** One entry of a group's strip: a condition, or a nested group's wrapper. */
const ENTRY = '[data-slot="filter-conditions"] > *';

/** The field picker's trigger, the way into a group that has emptied. */
const ADD = '[data-filter-add]';

/** The group (or panel) an element is drawn in, never the element itself. */
export function ownerOf(element: Element | null | undefined): Element | null {
  return element?.parentElement?.closest(OWNER) ?? null;
}

/**
 * The owner's own entries and way in — not those of a group nested in it,
 * nor those of an element match's predicate, which keep their own.
 */
function own(owner: Element, selector: string): HTMLElement[] {
  return [...owner.querySelectorAll<HTMLElement>(selector)].filter(
    found => found.parentElement?.closest(OWNER) === owner,
  );
}

function entriesOf(owner: Element): HTMLElement[] {
  const strip = own(owner, '[data-slot="filter-conditions"]')[0];
  return strip ? [...strip.children].filter(isHTMLElement) : [];
}

function isHTMLElement(node: Element): node is HTMLElement {
  return node instanceof HTMLElement;
}

/**
 * The condition at a path among an owner's own entries — the one a picker
 * just added. Paths restart inside an element match's predicate, so only
 * the owner's direct conditions are candidates.
 */
export function conditionAt(
  owner: Element | null,
  path: readonly number[],
): HTMLElement | null {
  if (!owner) return null;
  const key = path.join('.');
  return entriesOf(owner).find(entry => entry.dataset.path === key) ?? null;
}

/** Whether the keyboard is nowhere: on the body, or on a control gone dead. */
export function fell(): boolean {
  const active = document.activeElement;
  return (
    active === null ||
    active === document.body ||
    !active.isConnected ||
    (active as HTMLButtonElement).disabled === true
  );
}

export interface ConditionFocus {
  /**
   * Said from the press that takes a condition or a group out: the entry
   * holding `control` is going.
   */
  removing(control: Element): void;
}

const NO_FOCUS: ConditionFocus = { removing() {} };

const ConditionFocusContext = createContext<ConditionFocus>(NO_FOCUS);

export const ConditionFocusProvider = ConditionFocusContext.Provider;

export function useConditionFocus(): ConditionFocus {
  return useContext(ConditionFocusContext);
}

/**
 * Where the keyboard stands after a condition or a group is taken out of
 * the editor — the rule `useListFocus` keeps for the analysis cards,
 * brought to the filter's strips.
 *
 * Pressing 「移除 X」 or 「移除分组」 with the keyboard removed the element
 * the focus was on, and a focus with nothing under it falls to `<body>`: the
 * next Tab started the page again, from the title bar (review R1-P1-1). A
 * removal now leaves the keyboard where the entry was — the entry that took
 * its place, the one before it if the strip ended there, and the group's
 * own 「添加」 when nothing is left in it.
 *
 * The press only says what is going; the effect after the render that took
 * it away places the focus, and only when the focus really fell — one that
 * something else took meanwhile stays where it is. The holder must render
 * after the press, which every removal causes: it is the panel, drawn from
 * the tree the press changed.
 */
export function useConditionFocusRoot(): ConditionFocus {
  const pending = useRef<{
    owner: Element;
    panel: Element | null;
    at: number;
  } | null>(null);
  useEffect(() => {
    const next = pending.current;
    if (!next) return;
    pending.current = null;
    if (!fell()) return;
    // The root's block goes when the tree it held can be drawn in simple
    // mode again — a removed group is the usual reason — and the panel then
    // draws the same entries as its own strip.
    const owner = next.owner.isConnected ? next.owner : next.panel;
    if (!owner?.isConnected) return;
    const entries = entriesOf(owner);
    if (focusIn(entries[next.at]) || focusIn(entries[next.at - 1])) return;
    focusIn(own(owner, ADD)[0]);
  });
  return useMemo(
    () => ({
      removing(control) {
        const entry = control.closest(ENTRY);
        const owner = ownerOf(entry);
        if (!entry?.parentElement || !owner) return;
        const at = [...entry.parentElement.children].indexOf(entry);
        const panel = owner.closest('[data-slot="filter-panel"]');
        pending.current = { owner, panel, at };
      },
    }),
    [],
  );
}
