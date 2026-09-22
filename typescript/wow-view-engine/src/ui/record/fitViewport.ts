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
 * The least a scroll port is given, whatever is above it. Below this the
 * rows would be a slit; the page scrolls instead, which is what a table
 * this far down a page needs anyway.
 */
export const MIN_FIT_PX = 192;

/**
 * How tall the table's own scroll port may be so that the frame it is in
 * ends at the bottom of the viewport: the summaries and the pagination row
 * stay in view whenever there are more rows than room (P-22).
 *
 * **Why measure.** The port's cap used to be a flat `70vh`. A cap in
 * viewport units knows nothing about where the port starts: under a title
 * bar, an editor tray and a toolbar, 70vh of rows ran past the bottom of
 * the window, and the two summary rows — `sticky bottom-0` against the
 * port, not the window — went with them. The reader scrolled the page to
 * find a footer that exists to be always in view.
 *
 * **What is measured.** The port's top in the viewport, and what the frame
 * draws under the port (the pagination row, the frame's own edge): the cap
 * is the viewport's height less both. Measured again whenever the page's
 * height changes — a tray unfolding above the table moves its top — and
 * whenever the window is resized. Not on scroll: a workbench that fits its
 * viewport has no page to scroll, and a host page that scrolls as a whole
 * turns the port off (`scrolls={false}`) and keeps sticky against itself.
 *
 * Returns `null` before the first measurement and where nothing can be
 * measured (jsdom); the caller then keeps its static fallback.
 */
export function useViewportFit(
  port: RefObject<HTMLElement | null>,
  enabled: boolean,
): number | null {
  const [fit, setFit] = useState<number | null>(null);

  useLayoutEffect(() => {
    const node = port.current;
    if (!enabled || !node || typeof window === 'undefined') return;
    const measure = () => {
      const rect = node.getBoundingClientRect();
      const frame = node.closest<HTMLElement>('[data-slot="result-block"]');
      const below = frame
        ? Math.max(0, frame.getBoundingClientRect().bottom - rect.bottom)
        : 0;
      const room = window.innerHeight - rect.top - below;
      if (!Number.isFinite(room) || rect.height === 0) return;
      setFit(Math.max(MIN_FIT_PX, Math.floor(room)));
    };
    measure();
    window.addEventListener('resize', measure);
    if (typeof ResizeObserver === 'undefined')
      return () => window.removeEventListener('resize', measure);
    const observer = new ResizeObserver(measure);
    observer.observe(document.body);
    return () => {
      window.removeEventListener('resize', measure);
      observer.disconnect();
    };
  }, [port, enabled]);

  return fit;
}
