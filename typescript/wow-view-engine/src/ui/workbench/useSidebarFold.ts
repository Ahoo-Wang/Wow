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

import { useLayoutEffect, useState, type RefObject } from 'react';

/**
 * The width below which the list stops being *beside* the view.
 *
 * It is Tailwind's `md`, written as the number the stylesheet uses, because
 * this is the same threshold the surface changes direction at: above it the
 * shell is a row and the column stands next to the work area; below it the
 * shell is a column and the list is a block on top of the result.
 */
const NARROW = 768;

/**
 * The sidebar's fold as the *page* has it.
 *
 * **Folded until someone opens it** (D94): the view is what a reader came
 * for, and a 224px column of navigation beside it is room the rows do not
 * get. The collapsed row names the open view and unfolds the list in one
 * press, so the list is never further away than that. The surface's width
 * once answered instead (open at `md` and up, followed as it changed); a
 * list folded at every width has nothing left to measure.
 *
 * A host that passes `initial` is obeyed at every width: it knows something
 * about its page that a default does not, and `true` is how a host keeps
 * the list beside the view from the start.
 *
 * **A press is the answer from then on**, for as long as this surface is
 * mounted. Nothing is remembered past unmount, since this is view state and
 * nothing else: never saved, never asked about by the leave guard.
 */
export function useSidebarFold(
  initial: boolean | undefined,
  onChange?: (open: boolean) => void,
): { open: boolean; set(open: boolean): void } {
  const [open, setOpen] = useState(initial ?? false);
  return {
    open,
    set(next) {
      if (open === next) return;
      setOpen(next);
      onChange?.(next);
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
 * of 0 is jsdom, a detached tree, or a host that has not laid this out yet,
 * all saying nothing at all, and reads as wide.
 */
export function useNarrowSurface(
  surface: RefObject<HTMLElement | null>,
): boolean {
  const [narrow, setNarrow] = useState(false);
  // Re-attached on every render: the surface can arrive after the first
  // one, and an observer attached to nothing hears nothing.
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
