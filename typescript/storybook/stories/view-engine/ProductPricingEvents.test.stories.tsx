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
  EventStreamConsole as DisplayEventStreamConsole,
} from './ProductPricingEvents.stories.js';
import {
  RECORDED_PRICING_EVENTS_HOST,
  installRecordedPricingEventService,
} from './productPricingService.js';
import { readColumn } from './readTable.js';

/**
 * The product pricing event stream console against a recorded service
 * instead of a live one.
 *
 * The display story stays off CI because a live service answers differently
 * every time. Its data is what varies; the definition, the system views and
 * the way the console reads an event stream — its events inside an array a
 * condition reaches by element match and an analysis expands — do not.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/商品定价/事件流分析台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_PRICING_EVENTS_HOST },
  beforeEach: installRecordedPricingEventService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

export const EventStreamConsole: Story = {
  ...DisplayEventStreamConsole,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${title}`) });

    // The definition is admitted: every system view lists, and the first
    // opens.
    for (const title of [
      '最近的事件',
      '定价历史',
      '状态变更',
      '事件类型分布',
      '每月事件量',
      '每日事件量',
      '改动最多的定价',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('最近的事件')).toHaveAttribute('aria-current', 'true');

    // The newest events first, each stream read by its event's type in the
    // type's words — Wow's own tags event too, which the schema omits.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '定价 ID').slice(0, 3)).toEqual([
        'PP-SK-1000J-40812-10',
        'PP-SK-1000J-40812-1',
        'PP-SK-QSH6-20000-10',
      ]),
    );
    await expect(readColumn(table, '事件')).toHaveLength(16);
    await expect(new Set(readColumn(table, '事件'))).toEqual(
      new Set(['保存定价', '变更状态', '应用默认标签']),
    );

    // One stream read whole: its saved price in the detail, as money.
    const rowOf = (id: string) =>
      [...table.querySelectorAll<HTMLElement>('tbody tr')].find(row =>
        row.textContent?.includes(id),
      )!;
    rowOf('0VKveurV00h200V').focus();
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    const [event] = detail.querySelectorAll<HTMLElement>(
      '[data-slot="detail-element"]',
    );
    await expect(within(event!).getByText('保存定价')).toBeVisible();
    await expect(await within(event!).findByText(/108\.90/)).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );

    // The history template, still unfilled, reads every history in order.
    await userEvent.click(view('定价历史'));
    await waitFor(() =>
      expect(
        readColumn(canvas.getByRole('table'), '定价 ID').slice(0, 10),
      ).toEqual([
        ...Array.from({ length: 9 }, () => 'PP-SK-10001-10000-1'),
        'PP-SK-1000J-40812-1',
      ]),
    );
    await expect(
      readColumn(canvas.getByRole('table'), '版本').slice(0, 3),
    ).toEqual(['1', '2', '3']);

    // A condition on an event reaches into the stream's array.
    await userEvent.click(view('状态变更'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '版本')).toEqual([
        '9',
        '6',
        '3',
      ]),
    );

    // The analysis counts events, one bar per type the streams hold.
    await userEvent.click(view('事件类型分布'));
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('.recharts-bar-rectangle'),
      ).toHaveLength(3),
    );

    // The pricings changed after they were created, the most first, and when
    // the last change was — a date, not epoch milliseconds.
    await userEvent.click(view('改动最多的定价'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '定价 ID')).toEqual([
        'PP-SK-10001-10000-1',
        'PP-SK-QSH6-20000-10',
      ]),
    );
    const [latest] = readColumn(canvas.getByRole('table'), '最近一次改动');
    await expect(latest).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/);
    await expect(latest).not.toMatch(/^\d{12,}$/);
  },
};
