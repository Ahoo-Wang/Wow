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
} from './EventStreamConsole.stories.js';
import {
  RECORDED_EVENT_STREAM_HOST,
  installRecordedEventStreamService,
} from './eventStreamService.js';
import { readColumn } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

/**
 * The event stream console against a recorded service instead of a live one.
 *
 * The display story stays off CI because a live service answers differently
 * every time. Its data is what varies; the definition, the system views and
 * the way the console reads an event stream — its events inside an array a
 * condition reaches by element match and an analysis expands — do not, and a
 * rule change in View Engine can break them without any service involved.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/补偿控制台/事件流分析台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_EVENT_STREAM_HOST },
  beforeEach: installRecordedEventStreamService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

export const EventStreamConsole: Story = {
  ...DisplayEventStreamConsole,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${title}`) });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '最近的事件',
      '执行历史',
      '重试成功',
      '人工干预',
      '事件类型分布',
      '每月事件量',
      '每日事件量',
      '每日重试成功',
      '重试最多的执行',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('最近的事件')).toHaveAttribute('aria-current', 'true');

    // The newest events first, whichever execution appended them.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '事件流 ID')).toEqual([
        'EF-1-v5',
        'EF-1-v4',
        'EF-2-v2',
        'EF-3-v1',
        'EF-1-v3',
        'EF-1-v2',
        'EF-2-v1',
        'EF-1-v1',
      ]),
    );
    // Each stream reads by what happened in it — its events by type, in the
    // type's words — though the page asked for the types alone.
    await expect(readColumn(table, '事件')).toEqual([
      '重试成功',
      '准备重试',
      '标记可恢复性',
      '首次失败',
      '重试失败',
      '准备重试',
      '首次失败',
      '首次失败',
    ]);

    // One stream read whole: its event laid out in the detail — the type,
    // the declared fields, and the payload no field declares, key by key.
    table.querySelector<HTMLElement>('tbody tr:last-child')!.focus();
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    const [event] = detail.querySelectorAll<HTMLElement>(
      '[data-slot="detail-element"]',
    );
    await expect(within(event!).getByText('第 1 项')).toBeVisible();
    await expect(within(event!).getByText('首次失败')).toBeVisible();
    await expect(within(event!).getByText('errorMsg')).toBeVisible();
    await expect(
      await within(event!).findByText('Inventory refused.'),
    ).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );

    // The history template, still unfilled, reads every history in order.
    await userEvent.click(view('执行历史'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '事件流 ID')).toEqual([
        'EF-1-v1',
        'EF-1-v2',
        'EF-1-v3',
        'EF-1-v4',
        'EF-1-v5',
        'EF-2-v1',
        'EF-2-v2',
        'EF-3-v1',
      ]),
    );

    // A condition on an event reaches into the stream's array.
    await userEvent.click(view('重试成功'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '事件流 ID')).toEqual([
        'EF-1-v5',
      ]),
    );

    // The analysis counts events, one bar per type the streams hold.
    await userEvent.click(view('事件类型分布'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(5));

    // The execution retried most, and when it last was — the latest of its
    // retry events' times, read as a date and not as epoch milliseconds.
    await userEvent.click(view('重试最多的执行'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '执行 ID')).toEqual([
        'EF-1',
      ]),
    );
    const [latest] = readColumn(canvas.getByRole('table'), '最近一次重试');
    await expect(latest).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/);
    await expect(latest).not.toMatch(/^\d{12,}$/);
  },
};
