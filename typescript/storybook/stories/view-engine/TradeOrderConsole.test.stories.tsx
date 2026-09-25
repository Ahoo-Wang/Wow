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
import displayMeta, {
  SnapshotConsole as DisplaySnapshotConsole,
} from './TradeOrderConsole.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';
import {
  RECORDED_TRADING_HOST,
  installRecordedTradeOrderService,
} from './tradeOrderService.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

/**
 * The trade order console against a recorded service instead of a live one.
 *
 * The display story stays off CI because a live service answers differently
 * every time. Its data is what varies; the definition, the system views and
 * the way the console reads an order — its lines inside an array a cell
 * reads by their codes and an analysis expands — do not, and a rule change
 * in View Engine can break them without any service involved.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/交易订单/快照控制台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_TRADING_HOST },
  beforeEach: installRecordedTradeOrderService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

export const SnapshotConsole: Story = {
  ...DisplaySnapshotConsole,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${title}`) });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '待处理',
      '待付款',
      '已取消',
      '全部订单',
      '按状态分布',
      '每日下单',
      '客户排行',
      '商品排行',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('待处理')).toHaveAttribute('aria-current', 'true');

    // The queue: waiting on a review or a revision, the longest waiting
    // first, and what it is worth in the footer.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['TO-1', 'TO-3', 'TO-6']),
    );
    await expect(readColumn(table, '订单状态')).toEqual([
      '待评审',
      '待修改',
      '待评审',
    ]);
    await expect(readColumn(table, '客户')).toEqual([
      '华东机电',
      '北方五金',
      '南方电气',
    ]);
    await expect(amountOf(readTotal(table, '应付金额'))).toBe(2568);
    // The lines read as their model codes, never as the JSON of them.
    const [one, two] = readColumn(table, '商品');
    await expect(one).toBe('BTN-22R');
    await expect(two).toContain('BTN-22R');
    await expect(two).toContain('LMP-16W');
    await expect(two).not.toContain('{');

    // The unpaid: the one closest to cancelling itself first, with when.
    await userEvent.click(view('待付款'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual([
        'TO-4',
        'TO-2',
      ]),
    );
    const [cancelsAt] = readColumn(canvas.getByRole('table'), '自动取消时间');
    await expect(cancelsAt).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/);

    await userEvent.click(view('已取消'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(['TO-5']),
    );

    // The analysis is a view of the same workbench: one bar per status.
    await userEvent.click(view('按状态分布'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));

    // Every order placed, by the day it was placed — all six, whichever
    // time zone the browser reads the days in.
    await userEvent.click(view('每日下单'));
    await waitFor(() =>
      expect(
        readColumn(canvas.getByRole('table'), '订单数').map(Number),
      ).toSatisfy((counts: number[]) => counts.reduce((a, b) => a + b) === 6),
    );
    const [day] = readColumn(canvas.getByRole('table'), '日期');
    await expect(day).toMatch(/^2026年9月1[34]日$/);

    // The customers by what they ordered, the largest first.
    await userEvent.click(view('客户排行'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '客户')).toEqual([
        '华东机电',
        '北方五金',
        '南方电气',
      ]),
    );

    // Lines, not orders: the ranking expands `items` and counts each line.
    await userEvent.click(view('商品排行'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单行')).toEqual([
        '3',
        '2',
        '2',
      ]),
    );
    const ranked = canvas.getByRole('table');
    await expect(
      readColumn(ranked, '商品').map(name => name.split(' ')[0]),
    ).toEqual(['BTN-22R', 'LMP-16W', 'BTN-22G']);
    await expect(readColumn(ranked, '数量')).toEqual(['17', '3', '4']);
    await expect(readColumn(ranked, '金额').map(amountOf)).toEqual([
      2040, 792, 390.72,
    ]);
  },
};
