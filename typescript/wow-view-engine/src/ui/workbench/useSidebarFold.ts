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

import { useLayoutEffect, useRef, useState, type RefObject } from 'react';

/**
 * The width below which the list stops being *beside* the view.
 *
 * It is Tailwind's `md`, written as the number the stylesheet uses, because
 * this is the same threshold the surface changes direction at: above it the
 * shell is a row and the column stands next to the work area; below it the
 * shell is a column and the list is a block on top of the result.
 */
export const NARROW = 768;

/**
 * The sidebar's fold as the *page* has it, and the measurement that answers
 * for it until the user answers for themselves.
 *
 * **The width is followed, not sampled once.** A window dragged from 1280 to
 * 608 is the same phone-shaped column as one that opened at 608, and a list
 * that stayed beside a view which is no longer beside anything is 224px of
 * navigation stacked on top of the first row. So the surface is watched with
 * a `ResizeObserver` and the rule runs again on every change, in both
 * directions: narrowing folds the list away, widening brings it back.
 *
 * **It is the surface that is observed, not the window.** A 360px panel on a
 * 1440px page is the same phone-shaped column, and a host that resizes that
 * panel without the window moving at all would otherwise never be heard —
 * which is the lesson `ViewHeader`'s container queries already learned.
 *
 * **A press ends the measurement.** Once the user has folded the list away or
 * called it back, that is the answer for as long as this surface is mounted:
 * a measurement that flipped it back would be undoing, under their hands,
 * the one thing they explicitly asked for — and every later drag of the
 * window would undo it again. Before they press, the measurement decides both
 * ways; after, it decides nothing. Nothing is remembered past unmount, since
 * this is view state and nothing else: never saved, never asked about by the
 * leave guard.
 *
 * A width of 0 is jsdom, a detached tree, or a host that has not laid this
 * out yet, all saying nothing at all — and nothing is not a reason to fold.
 * A host that passed `forced` is obeyed at every width and never measured:
 * it knows something about its page that a measurement does not.
 */
export function useSidebarFold(
  surface: RefObject<HTMLElement | null>,
  forced: boolean | undefined,
  onChange?: (open: boolean) => void,
): { open: boolean; set(open: boolean): void } {
  const [open, setOpen] = useState(forced ?? true);
  // What is on screen, readable from inside the observer without the
  // observer having to be rebuilt by the render that saw it change.
  const shown = useRef(open);
  // Whether the user has answered this themselves.
  const chosen = useRef(false);

  const apply = (next: boolean) => {
    if (shown.current === next) return;
    shown.current = next;
    setOpen(next);
    onChange?.(next);
  };

  // No dependency array: the effect re-attaches on every render, which is
  // how the observer always calls the current `onChange` without the host
  // having to hand over a stable one. It is a layout effect so a column that
  // opens folded has folded before the first paint rather than flashing the
  // list and taking it away.
  useLayoutEffect(() => {
    const node = surface.current;
    if (forced !== undefined || chosen.current || node === null)
      return () => {};
    // `getBoundingClientRect` rather than the entry's `contentRect`: the
    // observer only says *that* the box changed, and the box the rule is
    // about is the border box the sidebar and the work area share.
    const measure = () => {
      if (chosen.current) return;
      const width = node.getBoundingClientRect().width;
      if (width === 0) return;
      apply(width >= NARROW);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return () => {};
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  });

  return {
    open,
    set(next) {
      chosen.current = true;
      apply(next);
    },
  };
}

/**
 * Whether the surface is narrower than `NARROW` — the width below which the
 * shell stacks its column over the work area — followed as it changes, and
 * never overruled by a press: it is a fact about the room, not a choice.
 *
 * What stands in the column (the visualization panel) is a block on top of
 * the result on such a screen: on a phone the chart types took the whole
 * first screen and pushed the chart they were for below it (2026-09-23
 * audit). The shell draws it in a drawer instead (`SidebarColumn`). A width
 * of 0 says nothing, as in `useSidebarFold`, and reads as wide.
 */
export function useNarrowSurface(
  surface: RefObject<HTMLElement | null>,
): boolean {
  const [narrow, setNarrow] = useState(false);
  // Re-attached on every render, as `useSidebarFold`'s is: the surface can
  // arrive after the first one, and an observer attached to nothing hears
  // nothing.
  useLayoutEffect(() => {
    const node = surface.current;
    if (node === null) return () => {};
    const measure = () => {
      const width = node.getBoundingClientRect().width;
      if (width > 0) setNarrow(width < NARROW);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return () => {};
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  });
  return narrow;
}
