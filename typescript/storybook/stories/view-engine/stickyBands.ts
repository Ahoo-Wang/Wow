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
import { expect, waitFor, within } from 'storybook/test';
import { near, nextFrame } from './panelEdges.js';

/**
 * An analysis table's two bands measured where its rows really scroll
 * (docs/design/ui/analysis.md「表头与合计行吸在两端」): scrolled to
 * the middle and to the end, the header's top is the scroll container's
 * top and the totals row's bottom its bottom, and the totals label — 「合计」
 * over 「范围内全部记录」 — is whole. The header is still a row of column
 * headers and the totals still a row.
 *
 * `scroller` is what scrolls: the table's own port in a workbench, a
 * dashboard panel's body on a board.
 */
export async function expectBandsHeld(
  scroller: HTMLElement,
  port: HTMLElement,
) {
  const head = port.querySelector<HTMLElement>('thead')!;
  const foot = port.querySelector<HTMLElement>('tfoot')!;
  await expect(foot, 'a totals row to hold').not.toBeNull();
  await expect(
    within(head).getAllByRole('columnheader').length,
  ).toBeGreaterThan(0);
  await expect(within(foot).getAllByRole('row')).toHaveLength(1);
  const range = scroller.scrollHeight - scroller.clientHeight;
  await expect(range, 'rows taller than their scroller').toBeGreaterThan(0);
  for (const at of [Math.round(range / 2), range]) {
    scroller.scrollTop = at;
    await nextFrame();
    await waitFor(() => expect(scroller.scrollTop).toBeGreaterThan(0));
    const box = scroller.getBoundingClientRect();
    const top = box.top + scroller.clientTop;
    const bottom = top + scroller.clientHeight;
    near(head.getBoundingClientRect().top, top, 'the header against the top');
    near(
      foot.getBoundingClientRect().bottom,
      bottom,
      'the totals against the bottom',
    );
    const label = foot.querySelector<HTMLElement>(
      '[data-slot="totals-heading"]',
    );
    if (label) {
      await expect(
        label.scrollHeight,
        'the totals label drawn whole',
      ).toBeLessThanOrEqual(label.clientHeight);
      await expect(
        label.getBoundingClientRect().bottom,
        'the totals label inside the scroller',
      ).toBeLessThanOrEqual(bottom + 1);
    }
  }
  scroller.scrollTop = 0;
  await nextFrame();
}
