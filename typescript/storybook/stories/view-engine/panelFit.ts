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
import { nextFrame } from './panelEdges.js';
import { panelOf } from './retail/twins.js';

/*
 * A table panel on a board read (2026-09-26 review, P1-3, and
 * docs/design/ui/dashboard.md「表格面板按行长高」): it grows to show its rows
 * whole, up to eight grid rows (710px), and what is still past its bottom
 * or its end is said in a cue on the body's edge — measured here in the
 * browser, where the layout is real.
 */

/** The tallest a panel grows to: eight 80px rows and the seven gaps between. */
export const GROWN_AT_MOST = 8 * 80 + 7 * 10;

/** A panel's card, its body and its cue, by the panel's name. */
export function panelParts(name: string) {
  const body = panelOf(name);
  const card = body.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
  const cue = card.querySelector<HTMLElement>('[data-slot="panel-scroll-cue"]');
  return { body, card, cue };
}

/** Every row of the panel's table, the header's and the totals' aside. */
function bodyRows(body: HTMLElement) {
  return [...body.querySelectorAll('table > tbody > tr')];
}

/**
 * The panel shows its body whole: nothing under its bottom edge, every row
 * of its table (`rows`, when it has one) drawn inside it, and no cue about
 * rows.
 */
export async function expectShownWhole(name: string, rows?: number) {
  await waitFor(
    () => {
      const { body, cue } = panelParts(name);
      if (rows !== undefined) expect(bodyRows(body)).toHaveLength(rows);
      expect(
        body.scrollHeight - body.clientHeight,
        `${name}: nothing under the bottom edge`,
      ).toBeLessThanOrEqual(1);
      expect(body.dataset.scrollBelow).toBeUndefined();
      expect(cue?.dataset.rows).toBeUndefined();
    },
    { timeout: 10_000 },
  );
  const { card } = panelParts(name);
  await expect(card.getBoundingClientRect().height).toBeLessThanOrEqual(
    GROWN_AT_MOST + 1,
  );
}

/**
 * The panel's columns run past its end edge, and the cue says how many;
 * scrolled to the end, the cue has nothing left to say about them.
 */
export async function expectColumnsCue(name: string) {
  let columns = 0;
  await waitFor(
    () => {
      const { cue } = panelParts(name);
      columns = Number(cue?.dataset.columns);
      expect(columns, `${name}: the columns past the end`).toBeGreaterThan(0);
      expect(cue?.textContent).toContain(`右边还有 ${columns} 列`);
    },
    { timeout: 10_000 },
  );
  const { body } = panelParts(name);
  body.scrollLeft = body.scrollWidth;
  await nextFrame();
  await waitFor(() =>
    expect(panelParts(name).cue?.dataset.columns).toBeUndefined(),
  );
  body.scrollLeft = 0;
  await nextFrame();
}

/**
 * The panel is as tall as it grows and its rows still run past the bottom:
 * the cue says how many, the count falls as the body scrolls, and at the
 * bottom there is nothing left to say about rows.
 */
export async function expectRowsCue(name: string) {
  let rows = 0;
  await waitFor(
    () => {
      const { card, cue } = panelParts(name);
      rows = Number(cue?.dataset.rows);
      expect(rows, `${name}: the rows under the bottom edge`).toBeGreaterThan(
        0,
      );
      expect(cue?.textContent).toContain(`下面还有 ${rows} 行`);
      expect(
        Math.abs(card.getBoundingClientRect().height - GROWN_AT_MOST),
        `${name}: grown to the cap`,
      ).toBeLessThanOrEqual(1);
    },
    { timeout: 10_000 },
  );
  const { body } = panelParts(name);
  // Half way: fewer rows under the edge, and the cue says so.
  body.scrollTop = (body.scrollHeight - body.clientHeight) / 2;
  await nextFrame();
  await waitFor(() => {
    const left = Number(panelParts(name).cue?.dataset.rows);
    expect(left).toBeGreaterThan(0);
    expect(left).toBeLessThan(rows);
  });
  body.scrollTop = body.scrollHeight;
  await nextFrame();
  await waitFor(() =>
    expect(panelParts(name).cue?.dataset.rows).toBeUndefined(),
  );
  body.scrollTop = 0;
  await nextFrame();
}

/**
 * The cue is a picture, not a control: hidden from a screen reader, never a
 * Tab stop, and it lets a press through; the body stays the Tab stop.
 */
export async function expectCueIsNoStop(name: string) {
  const { body, cue } = panelParts(name);
  await expect(cue).not.toBeNull();
  await expect(cue!.closest('[aria-hidden="true"]')).not.toBeNull();
  await expect(cue!.matches(':is(a, button, input, [tabindex])')).toBe(false);
  await expect(getComputedStyle(cue!).pointerEvents).toBe('none');
  await expect(body.tabIndex).toBe(0);
}

/** No two panels on the board share a pixel: a grown one pushed the rest. */
export async function expectNoPanelsOverlap(canvasElement: HTMLElement) {
  const boxes = [
    ...canvasElement.querySelectorAll('[data-slot="dashboard-panel"]'),
  ].map(panel => panel.getBoundingClientRect());
  for (const [index, a] of boxes.entries())
    for (const b of boxes.slice(index + 1))
      await expect(
        a.left < b.right - 1 &&
          b.left < a.right - 1 &&
          a.top < b.bottom - 1 &&
          b.top < a.bottom - 1,
      ).toBe(false);
}
