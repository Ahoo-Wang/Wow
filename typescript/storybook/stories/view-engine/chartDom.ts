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

import { expect, waitFor } from 'storybook/test';

/**
 * What a chart drawn by the library put on the screen, read off its SVG.
 *
 * The library writes no class names on what it draws, so these read the
 * drawing by what each part is: a bar is a filled outline, a value label is
 * text with a halo (`textBorderColor`, written as a stroke), and a tick is
 * text without one. One home for the reading, so a story says "the bars"
 * rather than how a bar is spelled.
 */
const plots = (root: ParentNode) =>
  root.querySelectorAll<HTMLElement>('[data-slot="chart-plot"]');

/** Every painted shape — a bar, a slice — left to right: not a rule, not air. */
export function drawnMarks(root: ParentNode): SVGPathElement[] {
  return (
    painted(root)
      // In the order a reader meets them — the library raises the mark under
      // the pointer to the top of the drawing, which moves it last.
      .sort((a, b) => {
        const one = a.getBoundingClientRect();
        const other = b.getBoundingClientRect();
        return one.left - other.left || one.top - other.top;
      })
  );
}

/**
 * The marks drawn faint — a group other than the one pressed on a panel
 * that filters the board — left to right.
 */
export function fadedMarks(root: ParentNode): SVGPathElement[] {
  return [...plots(root)]
    .flatMap(plot => [
      ...plot.querySelectorAll<SVGPathElement>('svg path[fill]'),
    ])
    .filter(
      path =>
        !path.closest('defs, clipPath') &&
        Number(path.getAttribute('fill-opacity') ?? 1) <= 0.5 &&
        Number(path.getAttribute('fill-opacity') ?? 1) > 0 &&
        area(path) > 0,
    )
    .sort(
      (a, b) => a.getBoundingClientRect().left - b.getBoundingClientRect().left,
    );
}

/**
 * A pie's slices in the order it drew them, which is the result's until a
 * pointer raises one: the first is the first group.
 */
export function slicesInOrder(root: ParentNode): SVGPathElement[] {
  return painted(root);
}

function painted(root: ParentNode): SVGPathElement[] {
  return [...plots(root)].flatMap(plot =>
    [...plot.querySelectorAll<SVGPathElement>('svg path[fill]')].filter(
      path => {
        const fill = path.getAttribute('fill');
        return (
          // A clip is geometry the library cuts with, never paint.
          !path.closest('defs, clipPath') &&
          fill !== 'none' &&
          fill !== 'transparent' &&
          // A mark is (nearly) opaque; the band under the pointer is half
          // see-through.
          Number(path.getAttribute('fill-opacity') ?? 1) > 0.5 &&
          // A bar of nothing — the carrier of a stack's total — encloses
          // no area.
          area(path) > 0
        );
      },
    ),
  );
}

/** The names the legend lists, in its order: a slice's or a series'. */
export function legendNames(root: ParentNode): string[] {
  return [
    ...root.querySelectorAll('[data-slot="chart-legend-item"] .truncate'),
  ].map(name => name.textContent ?? '');
}

function area(path: SVGGraphicsElement): number {
  const box = path.getBBox();
  return box.width * box.height;
}

/**
 * The numbers written on the marks themselves — a stacked bar's parts, a
 * heatmap's cells: text whose middle falls inside a mark. They wear no
 * halo — the ink that stands off the mark is their contrast — so they are
 * told apart from the axes' text by where they stand.
 */
export function markLabels(root: ParentNode): SVGTextElement[] {
  const marks = new Set<Element>(painted(root));
  return [...plots(root)].flatMap(plot =>
    [...plot.querySelectorAll<SVGTextElement>('svg text:not([stroke])')].filter(
      text => {
        // What the browser hits under the text's middle, not a mark's box:
        // a slice's box covers a donut's hole, and the total written there
        // is on no mark.
        const box = text.getBoundingClientRect();
        return document
          .elementsFromPoint(
            (box.left + box.right) / 2,
            (box.top + box.bottom) / 2,
          )
          .some(hit => marks.has(hit));
      },
    ),
  );
}

/** The numbers written over the marks, and on them. */
export function valueLabels(root: ParentNode): SVGTextElement[] {
  return [
    ...[...plots(root)].flatMap(plot => [
      ...plot.querySelectorAll<SVGTextElement>('svg text[stroke]'),
    ]),
    ...markLabels(root),
  ];
}

/** The text on the axes: ticks, and the axis titles. */
export function axisTexts(root: ParentNode): SVGTextElement[] {
  const onMarks = new Set(markLabels(root));
  return [...plots(root)].flatMap(plot =>
    [...plot.querySelectorAll<SVGTextElement>('svg text:not([stroke])')].filter(
      text => !onMarks.has(text),
    ),
  );
}

/**
 * An axis title is set in a heavier weight than its ticks, as Metabase sets
 * its own; the weight is what tells the two apart.
 */
const titled = (text: SVGTextElement) =>
  /font-weight:\s*[5-9]00|font:\s*[5-9]00/.test(
    text.getAttribute('style') ?? '',
  );

/** The titles along the axes. */
export function axisTitles(root: ParentNode): SVGTextElement[] {
  return axisTexts(root).filter(titled);
}

/** Whether two boxes on the screen share any area. */
export function overlaps(a: DOMRect, b: DOMRect): boolean {
  return (
    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
  );
}

/**
 * The tick labels on one side of an upright chart, told apart by where they
 * stand against the marks: under them (`bottom`), or beside them. An axis
 * title is left out.
 */
export function axisTicks(
  root: ParentNode,
  side: 'bottom' | 'left' | 'right',
): SVGTextElement[] {
  const marks = drawnMarks(root).map(mark => mark.getBoundingClientRect());
  const floor = Math.max(...marks.map(box => box.bottom));
  const west = Math.min(...marks.map(box => box.left));
  const east = Math.max(...marks.map(box => box.right));
  return axisTexts(root).filter(text => {
    if (titled(text)) return false;
    const box = text.getBoundingClientRect();
    if (side === 'bottom') return box.top >= floor - 1;
    if (box.top >= floor - 1) return false;
    return side === 'left' ? box.right <= west + 1 : box.left >= east - 1;
  });
}

/**
 * A press on a mark where a pointer would land: its middle, as the three
 * mouse events a press is. The library reads where a press landed off each
 * event's own coordinates; `userEvent`'s pointer sequence reached it with
 * none it could place, and a press that lands on nothing opens nothing.
 */
export function pressMark(mark: Element): void {
  const box = mark.getBoundingClientRect();
  const at = {
    bubbles: true,
    cancelable: true,
    clientX: box.left + box.width / 2,
    clientY: box.top + box.height / 2,
  };
  for (const type of ['mousedown', 'mouseup', 'click'])
    mark.dispatchEvent(new MouseEvent(type, at));
}

/**
 * The pointer moved over a mark's middle, as the library hears a pointer:
 * what raises the mark's tooltip.
 */
export function hoverMark(mark: Element): void {
  const box = mark.getBoundingClientRect();
  mark.dispatchEvent(
    new MouseEvent('mousemove', {
      bubbles: true,
      clientX: box.left + box.width / 2,
      clientY: box.top + box.height / 2,
    }),
  );
}

/** The drawing's tooltip under `root`, while the library holds one. */
export function chartTooltip(root: ParentNode): HTMLElement | null {
  return root.querySelector<HTMLElement>('[data-slot="chart-tooltip"]');
}

/**
 * Until every chart under `root` has landed: drawn, its marks grown into
 * place (`data-drawn`, set when the library says it has finished). What a
 * story measures before then is a bar on its way up.
 */
export async function chartsDrawn(root: ParentNode): Promise<void> {
  await waitFor(
    () => {
      const frames = [...root.querySelectorAll('[data-slot="chart"]')];
      expect(frames.length).toBeGreaterThan(0);
      expect(frames.every(frame => frame.hasAttribute('data-drawn'))).toBe(
        true,
      );
      // The query answers first, then the marks sweep in.
    },
    { timeout: 4_000 },
  );
}
