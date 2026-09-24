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
  DataConsole as DisplayDataConsole,
} from './CustomerDataConsole.stories.js';
import {
  RECORDED_CRM_HOST,
  installRecordedCustomerService,
} from './customerService.js';
import { readColumn } from './readTable.js';
import { drawnMarks } from './chartDom.js';

/**
 * The customer console against a recorded service instead of a live one.
 *
 * The display story stays off CI because a live service answers differently
 * every time. Its data is what varies; the definition, the system views and
 * the way the console reads a customer — its nested paths, its contacts in
 * an array a cell reads by name and an analysis expands — do not, and a rule
 * change in View Engine can break them without any service involved.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/客户/快照控制台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_CRM_HOST },
  beforeEach: installRecordedCustomerService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const bars = (canvasElement: HTMLElement) => drawnMarks(canvasElement);

export const DataConsole: Story = {
  ...DisplayDataConsole,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${title}`) });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '全部客户',
      '最近变更',
      '公海客户',
      '已禁用',
      '按负责人分布',
      '按行业分布',
      '按租户分布',
      '联系人 · 按决策角色',
      '每日新增客户',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('全部客户')).toHaveAttribute('aria-current', 'true');

    // The newest customers first, read through the nested snapshot paths.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '客户 ID')).toEqual([
        'CUS-6',
        'CUS-5',
        'CUS-4',
        'CUS-3',
        'CUS-2',
        'CUS-1',
      ]),
    );
    await expect(readColumn(table, '客户状态')).toEqual([
      '正常',
      '已禁用',
      '正常',
      '正常',
      '正常',
      '正常',
    ]);
    await expect(readColumn(table, '负责人')).toEqual([
      '',
      'sales-a',
      '',
      'sales-b',
      '',
      'sales-a',
    ]);
    // A customer's contacts read by name, though the page asked for the
    // names alone — never as the JSON of them.
    const contacts = readColumn(table, '联系人');
    await expect(contacts[3]).toBe('联系人丁');
    await expect(contacts[4]).toContain('联系人乙');
    await expect(contacts[4]).toContain('联系人丙');
    await expect(contacts.join('')).not.toMatch(/[{}[\]]/);
    // The creation time reads as a date, not as epoch milliseconds.
    await expect(readColumn(table, '创建时间')[0]).toMatch(
      /\d{4}年\d{1,2}月\d{1,2}日/,
    );

    // What changed since it was created, the latest change first.
    await userEvent.click(view('最近变更'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '客户 ID')).toEqual([
        'CUS-1',
        'CUS-5',
        'CUS-2',
      ]),
    );

    // The public pool is the customers nobody owns.
    await userEvent.click(view('公海客户'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '客户 ID')).toEqual([
        'CUS-6',
        'CUS-4',
        'CUS-2',
      ]),
    );

    await userEvent.click(view('已禁用'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '客户 ID')).toEqual([
        'CUS-5',
      ]),
    );

    // The pool counts as one owner: three bars, the pool, sales-a, sales-b.
    await userEvent.click(view('按负责人分布'));
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(3));

    // The customers with an industry, and what they add up to.
    await userEvent.click(view('按行业分布'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '所属行业').sort()).toEqual(
        ['工业传感器', '智能制造', '设备集成'].sort(),
      ),
    );

    await userEvent.click(view('按租户分布'));
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(2));

    // The analysis expands the contacts and counts contacts, not customers.
    await userEvent.click(view('联系人 · 按决策角色'));
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(2));

    // Three days of new customers, newest first, each read as a date.
    await userEvent.click(view('每日新增客户'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '客户数')).toEqual([
        '2',
        '2',
        '2',
      ]),
    );
    await expect(readColumn(canvas.getByRole('table'), '日期')[0]).toMatch(
      /\d{4}年\d{1,2}月\d{1,2}日/,
    );
  },
};
