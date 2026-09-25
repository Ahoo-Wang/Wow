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
  OrderEventStream as DisplayOrderEventStream,
} from './RetailOrderEvents.stories.js';
import { findDataTable, readColumn } from './readTable.js';

/**
 * 订单事件流的轻量孪生：最近的事件按时刻倒序、按事件类型读出；支付超时是对
 * `body` 的元素匹配；事件类型分布展开 `body`、以事件为单位，数字是种子定下的
 * 黄金值。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/订单事件流/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

/** A view's title as the start of a pattern: its parentheses mean themselves. */
const escaped = (title: string) => title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

type Story = StoryObj<typeof displayMeta>;

export const OrderEventStream: Story = {
  ...DisplayOrderEventStream,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${escaped(title)}`) });

    const recent = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(recent, '订单号')[0]).toBe('TO2026092200002'),
    );
    await expect(readColumn(recent, '事件')[0]).toBe('付款成功');

    // An element match on `body`: every stream holds a payment timeout.
    await userEvent.click(view('支付超时'));
    await waitFor(async () => {
      const events = readColumn(await findDataTable(canvasElement), '事件');
      expect(events.length).toBeGreaterThan(0);
      for (const event of events) expect(event).toContain('支付超时');
    });

    // Events, not streams: `body` expanded, one row per event type.
    await userEvent.click(view('事件类型分布'));
    await waitFor(() => {
      const reading = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-reading"] table',
      );
      expect(reading).not.toBeNull();
      // 付款成功 and 包裹发出 tie at 2,432: their order between them is the
      // source's, not the question's.
      const types = readColumn(reading!, '事件类型');
      expect(types[0]).toBe('下单');
      expect(types.slice(1, 3).sort()).toEqual(['付款成功', '包裹发出'].sort());
      expect(readColumn(reading!, '事件数').slice(0, 3)).toEqual([
        '2,643',
        '2,432',
        '2,432',
      ]);
    });
  },
};
