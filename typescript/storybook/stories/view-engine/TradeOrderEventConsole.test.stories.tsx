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
} from './TradeOrderEventConsole.stories.js';
import { readColumn } from './readTable.js';
import {
  RECORDED_TRADING_HOST,
  installRecordedTradeOrderEventService,
} from './tradeOrderService.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

/**
 * The trade order event console against a recorded service instead of a
 * live one: the definition, the system views and the way the console reads
 * an event stream — its events inside an array a condition reaches by
 * element match and an analysis expands — checked over a fixed set of
 * streams, so a rule change in View Engine fails here rather than in front
 * of someone opening the catalog.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/交易订单/事件流分析台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_TRADING_HOST },
  beforeEach: installRecordedTradeOrderEventService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

export const EventStreamConsole: Story = {
  ...DisplayEventStreamConsole,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${title}`) });
    const streams = () => readColumn(canvas.getByRole('table'), '事件流 ID');

    for (const title of [
      '最近的事件',
      '订单历史',
      '评审驳回',
      '改单',
      '取消与关闭',
      '事件类型分布',
      '每日事件量',
      '变动最多的订单',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('最近的事件')).toHaveAttribute('aria-current', 'true');

    // The newest first, whichever order they happened to.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '事件流 ID')).toEqual([
        'TO-6-v1',
        'TO-5-v2',
        'TO-3-v3',
        'TO-5-v1',
        'TO-4-v2',
        'TO-4-v1',
        'TO-3-v2',
        'TO-3-v1',
        'TO-2-v1',
        'TO-1-v1',
      ]),
    );
    // Each stream reads by what happened in it, in the type's words — two
    // events where one command appended two.
    const events = readColumn(table, '事件');
    await expect(events.slice(0, 3)).toEqual(['下单', '订单取消', '订单变更']);
    await expect(events[3]).toContain('下单');
    await expect(events[3]).toContain('订单确认');
    await expect(events.join('')).not.toContain('com.linyikj');

    // The history template, still unfilled, reads every history in order.
    await userEvent.click(view('订单历史'));
    await waitFor(() =>
      expect(streams()).toEqual([
        'TO-1-v1',
        'TO-2-v1',
        'TO-3-v1',
        'TO-3-v2',
        'TO-3-v3',
        'TO-4-v1',
        'TO-4-v2',
        'TO-5-v1',
        'TO-5-v2',
        'TO-6-v1',
      ]),
    );

    // A condition on an event reaches into the stream's array.
    await userEvent.click(view('评审驳回'));
    await waitFor(() => expect(streams()).toEqual(['TO-3-v2']));
    await userEvent.click(view('改单'));
    await waitFor(() => expect(streams()).toEqual(['TO-3-v3']));
    await userEvent.click(view('取消与关闭'));
    await waitFor(() => expect(streams()).toEqual(['TO-5-v2']));

    // The analysis counts events, one bar per type the streams hold.
    await userEvent.click(view('事件类型分布'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(7));

    // The streams by the day they were appended — all ten, whichever time
    // zone the browser reads the days in — and the orders they touched.
    await userEvent.click(view('每日事件量'));
    await waitFor(() =>
      expect(
        readColumn(canvas.getByRole('table'), '事件流数').map(Number),
      ).toSatisfy((counts: number[]) => counts.reduce((a, b) => a + b) === 10),
    );
    await expect(
      readColumn(canvas.getByRole('table'), '涉及订单').every(
        count => Number(count) > 0,
      ),
    ).toBe(true);

    // The order handled most, and when it last was — read as a date, not
    // as epoch milliseconds.
    await userEvent.click(view('变动最多的订单'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')[0]).toBe('TO-3'),
    );
    const busiest = canvas.getByRole('table');
    await expect(readColumn(busiest, '变动次数')[0]).toBe('3');
    const [latest] = readColumn(busiest, '最近一次');
    await expect(latest).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/);
    await expect(latest).not.toMatch(/^\d{12,}$/);
  },
};
