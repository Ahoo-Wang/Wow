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
} from './CustomerEventStreamConsole.stories.js';
import {
  RECORDED_CRM_HOST,
  installRecordedCustomerEventService,
} from './customerService.js';
import { readColumn } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

/**
 * The customer event stream console against a recorded service instead of a
 * live one.
 *
 * The display story stays off CI because a live service answers differently
 * every time. Its data is what varies; the definition, the system views and
 * the way the console reads an event stream — its events inside an array a
 * condition reaches by element match and an analysis expands, the command
 * and operator out of its header — do not, and a rule change in View Engine
 * can break them without any service involved.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/客户/事件流分析台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_CRM_HOST },
  beforeEach: installRecordedCustomerEventService,
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

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '最近的事件',
      '客户历史',
      '归属变更',
      '联系人变更',
      '事件类型分布',
      '每月事件量',
      '每日事件量',
      '每日新建客户',
      '变更最多的客户',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('最近的事件')).toHaveAttribute('aria-current', 'true');

    // The newest events first, whichever customer they happened to.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '事件流 ID')).toEqual([
        'CUS-1-v4',
        'CUS-5-v3',
        'CUS-5-v2',
        'CUS-5-v1',
        'CUS-3-v1',
        'CUS-1-v3',
        'CUS-2-v2',
        'CUS-2-v1',
        'CUS-1-v2',
        'CUS-1-v1',
      ]),
    );
    // Each stream reads by what happened in it, in the event's own words,
    // though the page asked for the types alone.
    await expect(readColumn(table, '事件')).toEqual([
      '更新基本信息',
      '禁用客户',
      '转移负责人',
      '创建客户',
      '创建客户',
      '认领客户',
      '释放到公海',
      '创建客户',
      '新增联系人',
      '创建客户',
    ]);
    // Which command made it, and who sent it, out of the stream's header.
    await expect(readColumn(table, '命令')[0]).toBe('update_basic_info');
    await expect(readColumn(table, '操作人')[0]).toBe('op-1');

    // The history template, still unfilled, reads every history in order.
    await userEvent.click(view('客户历史'));
    await waitFor(() =>
      expect(streams()).toEqual([
        'CUS-1-v1',
        'CUS-1-v2',
        'CUS-1-v3',
        'CUS-1-v4',
        'CUS-2-v1',
        'CUS-2-v2',
        'CUS-3-v1',
        'CUS-5-v1',
        'CUS-5-v2',
        'CUS-5-v3',
      ]),
    );

    // A condition on an event reaches into the stream's array.
    await userEvent.click(view('归属变更'));
    await waitFor(() =>
      expect(streams()).toEqual(['CUS-5-v2', 'CUS-1-v3', 'CUS-2-v2']),
    );
    await userEvent.click(view('联系人变更'));
    await waitFor(() => expect(streams()).toEqual(['CUS-1-v2']));

    // The analysis counts events, one bar per type the streams hold.
    await userEvent.click(view('事件类型分布'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(7));

    // Every event lands in some day, and each day says how many customers
    // its events touched. Which day is the zone's to say, so only the sum.
    await userEvent.click(view('每日事件量'));
    await waitFor(() => {
      const table = canvas.getByRole('table');
      const counts = readColumn(table, '事件数').map(Number);
      expect(counts.reduce((sum, count) => sum + count, 0)).toBe(10);
      expect(readColumn(table, '涉及客户').every(n => Number(n) > 0)).toBe(
        true,
      );
    });

    // Three days of new customers, newest first.
    await userEvent.click(view('每日新建客户'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '新建客户')).toEqual([
        '1',
        '1',
        '2',
      ]),
    );

    // The customer changed most, and when it last was — the latest of its
    // streams' times, read as a date and not as epoch milliseconds.
    await userEvent.click(view('变更最多的客户'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '客户 ID')[0]).toBe('CUS-1'),
    );
    await expect(readColumn(canvas.getByRole('table'), '变更次数')[0]).toBe(
      '4',
    );
    const [latest] = readColumn(canvas.getByRole('table'), '最近一次变更');
    await expect(latest).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/);
    await expect(latest).not.toMatch(/^\d{12,}$/);
  },
};
