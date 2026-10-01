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

import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import type { DashboardWidth } from '../../model/index.js';

/**
 * The grid's width, known before the frame it is drawn in paints.
 *
 * `react-grid-layout`'s own `useContainerWidth` starts at 1280px, first
 * measures in a passive effect, and then hears a resize from its observer
 * only to put it off to the next animation frame — whose update React then
 * renders a task later still. So on a first render the wide 24-column layout
 * was handed over at 1280px, on a phone too; and on every resize, and on a
 * board opened again from 「返回」 whose page grew a scrollbar as its panels
 * came in, the grid was painted for a frame or two at the width the container
 * had just lost: wider than its box, the right-hand panels hanging past it.
 *
 * So the width is kept here. The container is measured in a layout effect,
 * whose update React applies before it paints, and the panels are not drawn
 * until it has been; and the observer's answer is applied at once
 * (`flushSync`) inside its callback, which the browser runs after layout and
 * before paint — the grid is laid out at the new width in the same frame
 * the container took it. On the server, where no effect runs, the panels are
 * left out rather than drawn at a guessed width (the client's first render
 * matches, so hydration does too); where the container measures 0 — hidden,
 * or a DOM without layout — the starting width stands, as it always did.
 *
 * The same goes for the board's own width switched (D31, `boardWidth`): the
 * container is narrower or wider at once, and is measured before that paints
 * rather than a frame after, with the panels spilling out of it meanwhile.
 */
export function useGridWidth(boardWidth: DashboardWidth) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(STARTING_WIDTH);
  const [measured, setMeasured] = useState(false);
  useLayoutEffect(() => {
    const node = containerRef.current;
    if (!node) return;
    setWidth(Math.round(contentWidth(node)));
    setMeasured(true);
  }, [boardWidth]);
  // Watched from after the first commit: the first width is the layout
  // effect's, and the observer's first answer only says it again.
  useEffect(() => {
    const node = containerRef.current;
    if (!node || typeof ResizeObserver === 'undefined') return;
    // A browser answers on the frame after `observe`, with the width the
    // layout effect measured already; a stand-in that answers inside the
    // call, while React is still committing, is not heard.
    let observing = false;
    const observer = new ResizeObserver(entries => {
      const entry = entries[0];
      if (!entry || !observing) return;
      const next = Math.round(entry.contentRect.width);
      flushSync(() => setWidth(next));
    });
    observer.observe(node);
    observing = true;
    return () => observer.disconnect();
  }, []);
  return { containerRef, width, measured };
}

/** The width the grid is laid out at before anything was measured. */
const STARTING_WIDTH = 1280;

/** An element's width inside its padding, as the grid measures it. */
function contentWidth(node: HTMLElement): number {
  const style = getComputedStyle(node);
  const computed = Number.parseFloat(style.width);
  if (Number.isFinite(computed)) return Math.max(0, computed);
  const px = (value: string) => {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) ? parsed : 0;
  };
  return Math.max(
    0,
    node.clientWidth - px(style.paddingLeft) - px(style.paddingRight),
  );
}
