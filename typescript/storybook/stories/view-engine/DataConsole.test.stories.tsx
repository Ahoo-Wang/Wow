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
} from './DataConsole.stories.js';
import {
  RECORDED_COMPENSATION_HOST,
  installRecordedCompensationService,
} from './compensationService.js';
import { readColumn, readTotal } from './readTable.js';

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
  title: 'View Engine/真实后端/补偿控制台/回归',
  tags: ['!dev', '!autodocs', 'test'],
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
    await expect(
      await canvas.findByText(/重试/, {
        selector: '[data-slot="bulk-outcome"] *',
      }),
    ).toBeVisible();
    await waitFor(() =>
      expect(readColumn(table, '状态')).toEqual([
        '已准备重试',
        '失败',
        '失败',
        '已准备重试',
      ]),
    );

    // The analysis is a view of the same workbench, not another console.
    await userEvent.click(canvas.getByRole('button', { name: /^按状态分布/ }));
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('.recharts-bar-rectangle'),
      ).toHaveLength(3),
    );
  },
};
