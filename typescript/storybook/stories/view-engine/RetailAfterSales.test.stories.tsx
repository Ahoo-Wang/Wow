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
  AfterSaleWorkbench as DisplayAfterSaleWorkbench,
} from './RetailAfterSales.stories.js';
import { amountOf, findDataTable, readColumn, readTotal } from './readTable.js';

/**
 * 售后工作台的轻量孪生：视图都在，本月售后的笔数与金额、浴巾质量投诉在 5 月
 * 之后的爆发（A1），数字是种子定下的黄金值。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/售后工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

/** A view's title as the start of a pattern: its parentheses mean themselves. */
const escaped = (title: string) => title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

type Story = StoryObj<typeof displayMeta>;

const VIEWS = [
  '全部售后',
  '待处理',
  '售后理由构成',
  '各类目的售后理由',
  '每日退款金额（近 30 天）',
  '本月售后',
  '竹纤维浴巾的质量投诉',
  '竹纤维浴巾：每月售后理由',
];

const total = (canvasElement: HTMLElement) =>
  waitFor(() => {
    // The pager says it, and a live region repeats it for a screen reader.
    const [said] = within(canvasElement).getAllByText(/^共 [\d,]+ 条记录$/);
    return Number(said!.textContent!.replace(/\D/g, ''));
  });

const reading = (canvasElement: HTMLElement) =>
  waitFor(() => {
    const table = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart-reading"] table',
    );
    if (!table) throw new Error('No chart reading yet.');
    return table;
  });

export const AfterSaleWorkbench: Story = {
  ...DisplayAfterSaleWorkbench,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', {
        name: new RegExp(`^${escaped(title)}`),
      });
    for (const title of VIEWS)
      await waitFor(() => expect(view(title)).toBeInTheDocument());

    await waitFor(async () => expect(await total(canvasElement)).toBe(1727));

    await userEvent.click(view('待处理'));
    await waitFor(async () => expect(await total(canvasElement)).toBe(2));

    // Finance's month: 70 cases, and what went back to the buyers.
    await userEvent.click(view('本月售后'));
    await waitFor(async () => expect(await total(canvasElement)).toBe(70));
    const month = await findDataTable(canvasElement);
    await expect(amountOf(readTotal(month, '实退金额'))).toBeGreaterThan(0);

    // A1: quality complaints about the towel run from May on.
    await userEvent.click(view('竹纤维浴巾：每月售后理由'));
    await waitFor(async () => {
      const table = await reading(canvasElement);
      const months = readColumn(table, '申请月');
      const quality = readColumn(table, '质量问题');
      expect(quality[months.indexOf('2026年6月')]).toBe('22');
      expect(quality[months.indexOf('2026年4月')]).not.toMatch(/[1-9]/);
    });

    await userEvent.click(view('竹纤维浴巾的质量投诉'));
    const cases = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(new Set(readColumn(cases, '售后理由'))).toEqual(
        new Set(['质量问题']),
      ),
    );
  },
};
