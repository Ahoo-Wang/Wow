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
import { expect, userEvent, waitFor, within } from 'storybook/test';
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
