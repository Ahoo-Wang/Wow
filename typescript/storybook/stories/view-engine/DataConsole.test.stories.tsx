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
import { formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  DataConsole as DisplayDataConsole,
} from './DataConsole.stories.js';
import {
  RECORDED_COMPENSATION_HOST,
  installRecordedCompensationService,
} from './compensationService.js';
import { readColumn, readTotal } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

/**
 * The compensation console against a recorded service instead of a live one.
 *
 * The display story stays off CI because a live service answers differently
 * every time. Its data is what varies; the definition, the system views, the
 * row and bulk commands and the way the console reads a snapshot do not, and
 * a rule change in View Engine can break them without any service involved.
 * This runs the same console over a fixed set of executions — whose
 * commands change them the way the service does — so that such a change
 * fails here instead of in front of someone opening the catalog.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/补偿控制台/快照控制台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_COMPENSATION_HOST },
  beforeEach: installRecordedCompensationService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The commands a row's menu offers, and which of them it will run. */
async function rowMenu(canvas: ReturnType<typeof within>, id: string) {
  await userEvent.click(canvas.getByRole('button', { name: `${id} 的操作` }));
  // The menu fades in, and until it is visible it takes no pointer: wait for
  // the one this press opened to be there to be pressed.
  const menu = await within(document.body).findByRole('menu');
  await waitFor(() => expect(menu).toBeVisible());
  const item = (name: string) => within(menu).getByRole('menuitem', { name });
  return { menu, item };
}

const enabled = (item: HTMLElement) =>
  item.getAttribute('aria-disabled') !== 'true' &&
  !item.hasAttribute('data-disabled');

export const DataConsole: Story = {
  ...DisplayDataConsole,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '活动中',
      '不可重试',
      '不可恢复',
      '已成功',
      '全部',
      '按状态分布',
      '活动失败 · 按处理器',
      '每日新增失败',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: /^活动中/ }),
    ).toHaveAttribute('aria-current', 'true');

    // Active executions, newest first, read through the nested snapshot paths.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']),
    );
    await expect(readColumn(table, '状态')).toEqual([
      '已准备重试',
      '失败',
      '失败',
      '失败',
    ]);
    await expect(readTotal(table, '已重试次数').replace(/\D/g, '')).toBe('10');

    // A row offers what its execution takes: past the retry limit only the
    // forced retry, and never the recoverability it already has.
    const past = await rowMenu(canvas, 'EF-4');
    await expect(enabled(past.item('重试'))).toBe(false);
    await expect(enabled(past.item('强制重试'))).toBe(true);
    await expect(enabled(past.item('不可恢复'))).toBe(false);
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('menu')).toBeNull(),
    );

    // Retrying one from its row prepares it, says so, and the row shows it.
    const within1 = await rowMenu(canvas, 'EF-1');
    await userEvent.click(within1.item('重试'));
    // The workbench says what the command came to, above the rows.
    const settled = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="bulk-status"][data-state="settled"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(settled).toHaveTextContent('重试 · 1 项已完成');
    await waitFor(() =>
      expect(readColumn(table, '状态')).toEqual([
        '已准备重试',
        '失败',
        '失败',
        '已准备重试',
      ]),
    );

    // One execution read whole, from the keyboard: the rows are one Tab
    // stop, the arrows walk them, and Enter opens the panel beside the list
    // with what no column holds — the error the service recorded.
    const rowOf = (id: string) =>
      canvas.getByRole('button', { name: `${id} 的操作` }).closest('tr')!;
    rowOf('EF-5').focus();
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() => expect(rowOf('EF-4')).toHaveFocus());
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    await expect(
      await within(detail).findByText('Inventory refused.'),
    ).toBeVisible();
    // Closed, the reader is back on the row they opened.
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );
    await waitFor(() => expect(rowOf('EF-4')).toHaveFocus());

    // The analysis is a view of the same workbench, not another console.
    await userEvent.click(canvas.getByRole('button', { name: /^按状态分布/ }));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(3));
  },
};

/**
 * A condition on a processor, picked from the values the service holds
 * rather than typed blind.
 *
 * `处理器` is text the definition lets the service group by value, so the
 * value box of its condition lists the processors the executions name, each
 * with how many executions failed in it — the service's own count, asked as a
 * `TERMS` aggregation (`POST …/snapshot/aggregation`) once the box opens.
 * Picking one and applying narrows the rows to it.
 */
export const ValuesFromTheData: Story = {
  ...DisplayDataConsole,
  name: '快照控制台 · 条件值取自数据',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']),
    );

    const toggle = canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    });
    if (toggle.getAttribute('aria-expanded') !== 'true')
      await userEvent.click(toggle);
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.add'] }),
    );
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '处理器' }),
    );
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );
    await waitFor(() =>
      expect(document.body.querySelector('[role="dialog"]')).toBeNull(),
    );

    const box = await canvas.findByRole('combobox', {
      name: formatMessage(zhCN, 'label.filter.value-of', { field: '处理器' }),
    });
    await expect(box).toHaveAttribute(
      'placeholder',
      zhCN['label.filter.pick-or-type'],
    );
    await userEvent.click(box);
    // Every processor the executions name, the most frequent first, each with
    // its count — across the whole service, not only the active rows on
    // screen: what is offered is what the field can hold.
    const listbox = await within(document.body).findByRole('listbox');
    await waitFor(() =>
      expect(
        within(listbox)
          .getAllByRole('option')
          .map(option => option.getAttribute('aria-label')),
      ).toEqual([
        'OrderSaga（3 条记录）',
        'InventorySaga（1 条记录）',
        'PaymentSaga（1 条记录）',
      ]),
    );

    await userEvent.click(
      within(listbox).getByRole('option', { name: /^InventorySaga/ }),
    );
    await expect(box).toHaveValue('InventorySaga');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    );
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-4']));
  },
};

/**
 * 搜索错误常驻在标题栏：输入一段错误、按 Enter，只剩那几次执行；✕ 撤掉搜索、
 * 行回来。此前要打开条件、添加、勾「搜索错误」、完成、再输入，五步。
 */
export const SearchesTheErrors: Story = {
  ...DisplayDataConsole,
  name: '快照控制台 · 搜索错误',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']),
    );

    // Named by what it searches, and there without opening anything.
    const box = canvas.getByRole('searchbox', { name: '搜索错误' });
    await userEvent.type(box, 'gateway timed out');
    // Typing asks nothing yet: every key would be a query over the store.
    await expect(readColumn(table, 'ID')).toHaveLength(4);
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-2']));

    // The search is one of the conditions: the band says so.
    const applied = canvasElement.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    )!;
    await expect(applied).toHaveTextContent('gateway timed out');

    // ✕ takes it away and asks again.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.search.clear'] }),
    );
    await waitFor(() => expect(readColumn(table, 'ID')).toHaveLength(4));
    await expect(box).toHaveValue('');
  },
};
