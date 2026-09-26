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

import { useLayoutEffect, useState, type CSSProperties } from 'react';

/** Which of a scroll port's sides have more of its content past them. */
export type ScrollMore = 'start' | 'end' | 'both';

/** How far in from an edge the content fades out. */
export const SCROLL_FADE = 32;

/**
 * Which sides of a panel's body have columns past them, and the fade that
 * says so.
 *
 * A panel's table wider than the panel scrolls sideways in the panel's
 * body, and on a system that hides its scrollbars until they are used —
 * macOS by default — nothing on screen said so: the console's cluster
 * panel read as six columns, its seventh 「最早下次重试」 cut at the edge
 * with no sign there was more (compensation console walkthrough, W13). The
 * content now fades out towards a side that has more past it, the way a
 * list that scrolls on a phone does, and stops fading once scrolled there;
 * the body is a Tab stop, so the arrow keys scroll it too.
 *
 * The fade is a mask on the port itself, so it covers whatever the body
 * draws — the sticky header and totals bands included — without a layer
 * over them that could take a click. It stops short of the port's own
 * scrollbar, which stays whole. Measured on scroll, when the port or a
 * table in it resizes, and when what the port holds changes (a result
 * arrives) — a chart redrawing itself is not that — at most once a frame.
 */
export function useScrollMore(
  /** The port, as a callback ref hands it over; `null` while there is none. */
  node: HTMLElement | null,
): { more?: ScrollMore; style?: CSSProperties } {
  const [state, setState] = useState<{ more?: ScrollMore; bar: number }>({
    bar: 0,
  });

  useLayoutEffect(() => {
    if (!node) return;
    const measure = () => {
      const room = node.scrollWidth - node.clientWidth;
      const scrolled = node.scrollLeft;
      const start = room > 1 && scrolled > 1;
      const end = room > 1 && scrolled < room - 1;
      const more: ScrollMore | undefined =
        start && end ? 'both' : start ? 'start' : end ? 'end' : undefined;
      const bar = Math.max(0, node.offsetWidth - node.clientWidth);
      setState(previous =>
        previous.more === more && previous.bar === bar
          ? previous
          : { more, bar },
      );
    };
    let frame: number | null = null;
    const schedule = () => {
      if (frame !== null) return;
      frame = requestAnimationFrame(() => {
        frame = null;
        measure();
      });
    };
    node.addEventListener('scroll', schedule, { passive: true });
    // The port's own size, and each table's in it: a table widens (a
    // result, a column) without the port changing size.
    const resized =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(schedule);
    resized?.observe(node);
    const tables = new Set<Element>();
    const watchTables = () => {
      for (const table of node.querySelectorAll('table'))
        if (!tables.has(table)) {
          tables.add(table);
          resized?.observe(table);
        }
    };
    watchTables();
    measure();
    // What the body holds changing — a table arriving — but not a chart
    // redrawing itself, which rewrites its drawing many times a second.
    const changed =
      typeof MutationObserver === 'undefined'
        ? null
        : new MutationObserver(records => {
            if (records.every(record => inDrawing(record.target))) return;
            watchTables();
            schedule();
          });
    changed?.observe(node, { childList: true, subtree: true });
    return () => {
      node.removeEventListener('scroll', schedule);
      resized?.disconnect();
      changed?.disconnect();
      if (frame !== null) cancelAnimationFrame(frame);
    };
  }, [node]);

  const { more, bar } = state;
  if (!more) return {};
  return { more, style: fadeMask(more, bar) };
}

/** Whether a node is part of a chart's drawing. */
function inDrawing(target: Node): boolean {
  const element = target instanceof Element ? target : target.parentElement;
  return element?.closest('svg, [data-slot="chart-plot"]') != null;
}

/**
 * The mask that fades the content out towards each side with more past it,
 * over `SCROLL_FADE` pixels, and leaves `bar` pixels at the end — the
 * port's vertical scrollbar — drawn whole.
 */
export function fadeMask(more: ScrollMore, bar: number): CSSProperties {
  const start = more !== 'end';
  const end = more !== 'start';
  const edge = `calc(100% - ${bar}px)`;
  const stops = [
    start ? `transparent 0, #000 ${SCROLL_FADE}px` : '#000 0',
    end
      ? `#000 calc(100% - ${bar + SCROLL_FADE}px), transparent ${edge}`
      : `#000 ${edge}`,
    ...(bar > 0 ? [`#000 ${edge}`] : []),
  ];
  const image = `linear-gradient(to right, ${stops.join(', ')})`;
  return { maskImage: image, WebkitMaskImage: image };
}
