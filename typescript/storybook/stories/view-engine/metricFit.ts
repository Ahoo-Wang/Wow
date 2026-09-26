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
import { chartsDrawn } from './chartDom.js';

/*
 * A metric card on a board read on a phone (2026-09-26 review, P1-4, #3661):
 * a long figure steps down rather than running past the card, the change
 * badge stays one line (under 10rem it shows the share alone, drawn from
 * `data-share` by `::after`), and the trend under them stays in the panel.
 * All of it is container queries, which jsdom cannot run, so it is measured
 * here, in the browser, where the layout is real.
 */

/** Half a pixel of rounding either way. */
const SLACK = 0.5;

const within = (inner: DOMRect, outer: DOMRect) =>
  inner.left >= outer.left - SLACK &&
  inner.right <= outer.right + SLACK &&
  inner.top >= outer.top - SLACK &&
  inner.bottom <= outer.bottom + SLACK;

/** The height one line of the badge's text takes, drawn by it or its `::after`. */
function oneLine(badge: HTMLElement): number {
  const after = getComputedStyle(badge, '::after');
  const drawn =
    after.content !== 'none' && after.content !== 'normal' ? after : null;
  const style = drawn ?? getComputedStyle(badge);
  const height = parseFloat(style.lineHeight);
  // `normal` has no number: take the usual 1.5 of the font size.
  return Number.isFinite(height) ? height : parseFloat(style.fontSize) * 1.5;
}

/** The badge's content box height: its box less padding and border. */
function contentHeight(badge: HTMLElement): number {
  const style = getComputedStyle(badge);
  return (
    badge.getBoundingClientRect().height -
    parseFloat(style.paddingTop) -
    parseFloat(style.paddingBottom) -
    parseFloat(style.borderTopWidth) -
    parseFloat(style.borderBottomWidth)
  );
}

/** The panel's name, as its group is named, to say which card is wrong. */
function nameOf(card: HTMLElement): string {
  const group = card.closest<HTMLElement>('[role="group"]');
  const labelled = group?.getAttribute('aria-labelledby');
  return (
    group?.getAttribute('aria-label') ??
    (labelled
      ? labelled
          .split(' ')
          .map(id => document.getElementById(id)?.textContent ?? '')
          .join(' ')
      : '(unnamed)')
  );
}

/**
 * What is wrong with every metric card on the board, measured now — an empty
 * list when each fits. Everything is found here, right before it is
 * measured: a chart can redraw after a late layout, and a node held from
 * before would be one the page has already replaced. It only reads.
 */
function misfits(root: ParentNode): string[] {
  const found: string[] = [];
  const cards = [
    ...root.querySelectorAll<HTMLElement>(
      '[data-slot="dashboard-panel"] [data-slot="metric-card"]',
    ),
  ];
  if (cards.length === 0) found.push('no metric card on the board');
  for (const card of cards) {
    const panel = card.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    const name = nameOf(card);
    const cardBox = card.getBoundingClientRect();
    const panelBox = panel.getBoundingClientRect();

    const value = card.querySelector<HTMLElement>('[data-slot="metric-value"]');
    if (value) {
      if (value.scrollWidth > value.clientWidth)
        found.push(
          `${name}: the figure overflows (${value.scrollWidth} > ${value.clientWidth})`,
        );
      if (!within(value.getBoundingClientRect(), cardBox))
        found.push(`${name}: the figure runs past the card`);
    }

    const badge = card.querySelector<HTMLElement>(
      '[data-slot="metric-change"] [data-slot="badge"]',
    );
    if (badge) {
      const line = oneLine(badge);
      if (contentHeight(badge) > line * 1.5)
        found.push(
          `${name}: the change badge wraps (${contentHeight(badge)}px for a ${line}px line)`,
        );
      if (!within(badge.getBoundingClientRect(), cardBox))
        found.push(`${name}: the change badge runs past the card`);
    }

    const trend = card.querySelector<HTMLElement>(
      '[data-slot="chart"][data-chart="sparkline"]',
    );
    if (trend && !within(trend.getBoundingClientRect(), panelBox))
      found.push(`${name}: the trend runs out of the panel`);
  }
  return found;
}

/**
 * Every metric card on the board fits it: the figure in the card, on one
 * line, with nothing scrolled sideways; the change badge one line; the trend
 * inside the panel. Waits for the charts to be drawn first (`data-drawn`),
 * then for the layout to hold — a read, retried until it settles.
 */
export async function expectMetricCardsFit(root: HTMLElement): Promise<void> {
  await chartsDrawn(root);
  await waitFor(() => expect(misfits(root)).toEqual([]), { timeout: 5_000 });
}
