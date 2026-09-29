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

import type { StoryObj } from '@storybook/react-vite';
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  OrderWorkbenchScene as DisplayOrderWorkbench,
} from './RetailOrders.stories.js';
import { amountOf, findDataTable, readColumn, readTotal } from './readTable.js';
import { DAILY_GOLDEN } from './retail/goldens.js';

/**
 * 订单工作台的轻量孪生（docs/scenarios.md 6.1）：视图都在，打开的是值班队列
 * 「发货超时」，数字是种子定下的黄金值——生成器一改，就在同一个 PR 里更新。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/订单工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

/** A view's title as the start of a pattern: its parentheses mean themselves. */
const escaped = (title: string) => title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

type Story = StoryObj<typeof displayMeta>;

/** The views the scene ships and saves, as the list names them. */
const VIEWS = [
  '发货超时',
  '全部订单',
  '昨日订单',
  '礼品加急',
  '全额退款关闭',
  '留言提到改地址',
  '浴巾退款单（近 3 个月）',
  '我跟的大客户',
];

/** The total the pager states under a record view. */
const total = (canvasElement: HTMLElement) =>
  waitFor(() => {
    // The pager says it, and a live region repeats it for a screen reader.
    const [said] = within(canvasElement).getAllByText(/^共 [\d,]+ 条记录$/);
    return Number(said!.textContent!.replace(/\D/g, ''));
  });

export const OrderWorkbenchScene: Story = {
  ...DisplayOrderWorkbench,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${escaped(title)}`) });
    for (const title of VIEWS)
      await expect(
        await canvas.findByRole('button', {
          name: new RegExp(`^${escaped(title)}`),
        }),
      ).toBeVisible();

    // The queue opens first: eleven orders paid over 48 hours ago and not
    // shipped, every one from the East China warehouse (A7).
    await expect(view('发货超时')).toHaveAttribute('aria-current', 'true');
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(11));
    await expect(await total(canvasElement)).toBe(11);
    await expect(new Set(readColumn(table, '发货仓'))).toEqual(
      new Set(['华东（嘉兴）']),
    );
    await expect(readColumn(table, '订单号')[0]).toBe('TO2026091700021');

    // The host's reminder over two of them: both taken.
    await userEvent.click(
      canvas.getByRole('checkbox', {
        name: zhCN['label.record.select'].replace('{key}', 'TO2026091700021'),
      }),
    );
    await userEvent.click(
      canvas.getByRole('checkbox', {
        name: zhCN['label.record.select'].replace('{key}', 'TO2026091800019'),
      }),
    );
    await userEvent.click(canvas.getByRole('button', { name: /催发货 2 单/ }));
    const done = zhCN['label.bulk.done'].replace('{done}', '2');
    await waitFor(() => expect(canvasElement.textContent).toContain(done));

    // Yesterday's orders, the gift-and-urgent ones, the fully refunded.
    // 「昨日订单」 reads the same day, on the same basis, as the daily report's
    // cards (docs/scenarios.md 6.3): its count and its paid total are the
    // golden 订单数 and 实付金额 of 2026-09-21.
    await userEvent.click(view('昨日订单'));
    await waitFor(async () =>
      expect(await total(canvasElement)).toBe(
        Number(DAILY_GOLDEN.cards['订单数（单）']),
      ),
    );
    await waitFor(async () =>
      expect(
        amountOf(readTotal(await findDataTable(canvasElement), '实付')),
      ).toBe(amountOf(DAILY_GOLDEN.cards.实付金额)),
    );
    await userEvent.click(view('礼品加急'));
    await waitFor(async () => expect(await total(canvasElement)).toBe(36));
    await userEvent.click(view('全额退款关闭'));
    await waitFor(async () => expect(await total(canvasElement)).toBe(672));
    // The customer service team's search of the buyers' remarks.
    await userEvent.click(view('留言提到改地址'));
    await waitFor(async () => expect(await total(canvasElement)).toBe(317));

    // A1 down to the orders: every one sold the bath towel and refunded it.
    await userEvent.click(view('浴巾退款单（近 3 个月）'));
    await waitFor(async () => {
      const items = readColumn(await findDataTable(canvasElement), '商品');
      expect(items.length).toBeGreaterThan(0);
      for (const item of items)
        expect(item).toContain('竹纤维浴巾 70×140 · 米白');
    });
  },
};

/**
 * #3691: the detail's lines say every field in words. Each line carried four
 * keys the definition never declared, and the drawer drew them by their raw
 * names — lineId, spuId, listPrice, discountShare — among the Chinese ones.
 */
export const DetailNamesEveryLineField: Story = {
  ...DisplayOrderWorkbench,
  play: async ({ canvasElement }) => {
    await within(canvasElement).findByRole('table');
    const row = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        'tr[data-row-key="TO2026091700021"]',
      );
      if (!found) throw new Error('no row TO2026091700021');
      return found;
    });
    row.focus();
    await userEvent.keyboard('{Enter}');
    const panel = await screen.findByRole('dialog');
    await waitFor(() => expect(panel.textContent).toContain('分摊优惠'), {
      timeout: 5_000,
    });
    for (const label of ['行号', 'SPU', '标价'])
      await expect(panel.textContent).toContain(label);
    for (const key of ['lineId', 'spuId', 'listPrice', 'discountShare'])
      await expect(panel.textContent).not.toContain(key);
  },
};

/** The hint a summary row gives about the columns it sums out of view. */
const offscreenHint = (table: HTMLElement) =>
  table.querySelector<HTMLButtonElement>(
    'tfoot tr[data-scope="total"] [data-slot="summary-offscreen"]',
  );

/** Whether a box lies whole between the held columns of the port. */
function inView(port: HTMLElement, box: Element): boolean {
  const held = [...port.querySelectorAll('thead [data-pin]')];
  const edge = port.getBoundingClientRect();
  let left = edge.left;
  let right = edge.right;
  for (const cell of held) {
    const at = cell.getBoundingClientRect();
    if (cell.getAttribute('data-pin') === 'left')
      left = Math.max(left, at.right);
    else right = Math.min(right, at.left);
  }
  const at = box.getBoundingClientRect();
  return at.left >= left - 1 && at.right <= right + 1;
}

/**
 * D51: the queue's one summary, 实付, stands far to the right of a table
 * wider than its port, so the totals row says so at its left end — in the
 * row key's held cell, beside the selection column — and takes the reader
 * there. Scrolled back, it says so again. Left showing, for axe.
 */
export const OffscreenTotals: Story = {
  ...DisplayOrderWorkbench,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(11));
    const port = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await waitFor(() =>
      expect(port.scrollWidth).toBeGreaterThan(port.clientWidth),
    );
    await expect(port.scrollLeft).toBe(0);

    const hint = await waitFor(() => {
      const found = offscreenHint(table);
      expect(found).not.toBeNull();
      return found!;
    });
    // Named by the words it shows, then what they leave unsaid: the row,
    // that it is out of view, and where the press goes (WCAG 2.5.3).
    // (The hidden part is out of the flow, so a browser puts a space
    // before it.)
    await expect(hint).toHaveAccessibleName(
      /^实付 总和 ¥[\d,.]+ ?，全部，不在视野内，滚动到实付$/,
    );
    await expect(hint.dataset.side).toBe('right');
    // In the row key's cell, which is held, and whole in view.
    await expect(hint.closest('td')?.dataset.pin).toBe('left');
    const cell = hint.closest('td')!.getBoundingClientRect();
    const edge = port.getBoundingClientRect();
    await expect(cell.left).toBeGreaterThanOrEqual(edge.left);
    await expect(hint.getBoundingClientRect().right).toBeLessThanOrEqual(
      cell.right,
    );

    const reading = table.querySelector(
      'tfoot tr[data-scope="total"] [data-summary-field="state.amounts.paidAmount"]',
    )!;
    await expect(inView(port, reading)).toBe(false);
    await userEvent.click(hint);
    // The column is brought in, the hint goes, and focus is on the total.
    await waitFor(() => expect(inView(port, reading)).toBe(true));
    await expect(port.scrollLeft).toBeGreaterThan(0);
    await waitFor(() => expect(offscreenHint(table)).toBeNull());
    await expect(document.activeElement).toBe(reading.closest('td'));

    // Back to the left edge: the hint says so again.
    port.scrollLeft = 0;
    await waitFor(() => expect(offscreenHint(table)).not.toBeNull());

    // And by keyboard: Enter on it does the same.
    offscreenHint(table)!.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(inView(port, reading)).toBe(true));
    await waitFor(() => expect(offscreenHint(table)).toBeNull());
    port.scrollLeft = 0;
    await waitFor(() => expect(offscreenHint(table)).not.toBeNull());
  },
};

/**
 * 390×844 的手机上打开「发货超时」的筛选（第二轮审查 R1-P1-4）：编辑带封顶约
 * 三分之一，从前整条带在里面滚，首屏把一个条件片从中间切断，「应用」在视野
 * 之外。现在条件在带里滚，「添加条件／清空／应用」这一行立在带底——与分析
 * 托盘「窄屏底行不滚」同一个做法。
 */
export const FilterBandOnAPhone: Story = {
  ...DisplayOrderWorkbench,
  parameters: {
    ...DisplayOrderWorkbench.parameters,
    viewport: {
      options: {
        phone: {
          name: '390×844',
          styles: { width: '390px', height: '844px' },
        },
      },
    },
  },
  globals: { viewport: { value: 'phone' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(390);
    const canvas = within(canvasElement);
    await findDataTable(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const part = (slot: string) =>
      canvasElement.querySelector<HTMLElement>(`[data-slot="${slot}"]`)!;
    const band = await waitFor(() => {
      const found = part('editor-band');
      expect(found).not.toBeNull();
      expect(part('filter-actions')).not.toBeNull();
      return found;
    });
    const box = (element: HTMLElement) => element.getBoundingClientRect();
    // The band itself does not scroll; the conditions do, inside it.
    await waitFor(() =>
      expect(band.scrollHeight).toBeLessThanOrEqual(band.clientHeight + 1),
    );
    const actions = part('filter-actions');
    await expect(box(actions).bottom).toBeLessThanOrEqual(box(band).bottom + 1);
    await expect(box(actions).top).toBeGreaterThanOrEqual(box(band).top);
    await expect(
      within(actions).getByRole('button', {
        name: zhCN['label.filter.apply'],
      }),
    ).toBeVisible();
  },
};

/**
 * The host's navigation as the engine gives it (host-integration.md 4.3):
 * the retail workbenches in the shell's column are resources of the retail
 * host, their links where its route table puts them and the one on screen
 * marked by the address, all read off `useViewNavigation`
 * (`shared/sceneNavigation.ts`) — the shell only names them.
 */
export const HostNavigation: Story = {
  ...DisplayOrderWorkbench,
  play: async () => {
    const places = within(
      await screen.findByRole('navigation', { name: '应用导航' }),
    );
    const here = places.getByRole('link', { name: '订单工作台' });
    await expect(here).toHaveAttribute('aria-current', 'page');
    await expect(here.getAttribute('href')).toBe(
      './?path=/story/view-engine-业务场景-订单工作台--order-workbench-scene',
    );
    const elsewhere = places.getByRole('link', { name: '售后工作台' });
    await expect(elsewhere).not.toHaveAttribute('aria-current');
    await expect(elsewhere.getAttribute('href')).toBe(
      './?path=/story/view-engine-业务场景-售后工作台--after-sale-workbench',
    );
  },
};
