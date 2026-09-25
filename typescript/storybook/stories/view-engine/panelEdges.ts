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
import { panelOf } from './retail/twins.js';

/*
 * Where a dashboard panel's parts stand across it, measured in the browser
 * (docs/design/ui/dashboard.md「表格贴到面板两边」): a table runs to the
 * card's edges and its first cell's content stands where the title does; a
 * drawing keeps the panel's gutter on both sides.
 */

/** One pixel either way: sub-pixel layout, never a gutter. */
const NEAR = 1;

export function near(actual: number, expected: number, what: string) {
  return expect(
    Math.abs(actual - expected),
    `${what}: ${actual} against ${expected}`,
  ).toBeLessThanOrEqual(NEAR);
}

/** The next frame, after a scroll or a density change has been laid out. */
export const nextFrame = () =>
  new Promise<void>(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
  );

export interface PanelEdges {
  /** The panel's body, the region that scrolls. */
  body: HTMLElement;
  /** Its inner left and right, the scrollbar left out. */
  left: number;
  right: number;
  /** Where the title's text starts. */
  title: number;
  /** The title's inset from the card's inner edge: the panel's gutter. */
  gutter: number;
}

/** A panel's edges, by its title. */
export function panelEdges(name: string): PanelEdges {
  const body = panelOf(name);
  const card = body.closest<HTMLElement>('[data-slot="dashboard-panel"]');
  const heading = card?.querySelector<HTMLElement>('[data-slot="panel-title"]');
  if (!card || !heading) throw new Error(`No panel titled ${name}.`);
  const box = body.getBoundingClientRect();
  const left = box.left + body.clientLeft;
  const title = heading.getBoundingClientRect().left;
  return {
    body,
    left,
    right: left + body.clientWidth,
    title,
    gutter: title - card.getBoundingClientRect().left - card.clientLeft,
  };
}

const leftOf = (element: Element) => element.getBoundingClientRect().left;
const rightOf = (element: Element) => element.getBoundingClientRect().right;

/**
 * A table panel: the table spans the body edge to edge, the first cell's
 * content of each band stands at the title, and the last column keeps the
 * gutter on the right — at rest and scrolled to either end, the held
 * columns staying against the card's edges.
 */
export async function expectTableBleeds(name: string, table: string) {
  const edges = await waitFor(() => {
    const found = panelEdges(name);
    expect(
      found.body.querySelector(`[data-slot="${table}"] tbody td`),
    ).not.toBeNull();
    return found;
  });
  const { body, left, right, title, gutter } = edges;
  expect(gutter).toBeGreaterThan(0);
  const port = body.querySelector<HTMLElement>(`[data-slot="${table}"]`)!;
  near(leftOf(port), left, 'the table’s left');
  near(rightOf(port), right, 'the table’s right');
  const content = (row: HTMLTableRowElement) => {
    const first = row.cells[0];
    // What the first cell draws: its checkbox, a column's label, or
    // its text, which starts where the cell's padding ends.
    const inner = first.querySelector(
      '[role="checkbox"], [data-slot="column-label"]',
    );
    return inner
      ? leftOf(inner)
      : leftOf(first) + parseFloat(getComputedStyle(first).paddingLeft);
  };
  const lastColumn = (row: HTMLTableRowElement) =>
    [...row.cells].filter(cell => cell.dataset.column !== 'filler').at(-1)!;
  const [head, firstRow] = [
    body.querySelector<HTMLTableRowElement>('thead tr')!,
    body.querySelector<HTMLTableRowElement>('tbody tr')!,
  ];
  const ends = async () => {
    for (const row of [head, firstRow]) {
      near(content(row), title, 'the first cell’s content');
      near(leftOf(row.cells[0]), left, 'the first cell’s left');
    }
  };
  await ends();
  // A record table scrolls with the body; an analysis table is its own port.
  const scroller = [port, body].find(
    element =>
      getComputedStyle(element).overflowX !== 'visible' &&
      element.scrollWidth > element.clientWidth,
  );
  if (scroller) {
    scroller.scrollLeft = scroller.scrollWidth;
    await nextFrame();
    // The last column ends at the card's edge, its content a gutter in. The
    // scroll offset and `scrollWidth` are whole pixels while the table's
    // width is not, so the end of the scroll can fall short of the table's
    // end by up to two: the column is read against the table it ends, and
    // the table's end against the card's edge within that rounding.
    const last = lastColumn(head);
    const filler = head.cells[head.cells.length - 1];
    const fillerWidth =
      filler.dataset.column === 'filler'
        ? filler.getBoundingClientRect().width
        : 0;
    near(
      rightOf(last),
      rightOf(port.querySelector('table')!) - fillerWidth,
      'the last column’s right against the table’s end',
    );
    expect(Math.abs(rightOf(last) - right)).toBeLessThanOrEqual(2);
    near(
      parseFloat(getComputedStyle(last).paddingRight),
      gutter,
      'the last column’s right padding',
    );
    // A held first column stays at the card's edge while the rows pass.
    if (head.cells[0].dataset.pin === 'left') await ends();
    scroller.scrollLeft = 0;
    await nextFrame();
  }
}

/** A drawing keeps the gutter on both sides. */
export async function expectDrawingKeepsGutter(name: string, slot: string) {
  const drawing = await waitFor(() => {
    const found = panelOf(name).querySelector(`[data-slot="${slot}"]`);
    expect(found).not.toBeNull();
    return found!;
  });
  const { left, right, gutter } = panelEdges(name);
  near(leftOf(drawing) - left, gutter, 'the drawing’s left gutter');
  near(right - rightOf(drawing), gutter, 'the drawing’s right gutter');
}
