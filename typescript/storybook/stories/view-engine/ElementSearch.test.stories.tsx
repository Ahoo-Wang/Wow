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
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  OnElasticsearch as DisplayElasticsearch,
  OnMongoDb as DisplayMongoDb,
} from './ElementSearch.stories.js';
import { findDataTable, readColumn } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/元素内检索/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out: a file's own description would otherwise replace the
  // display meta's parameters, and the host application with them.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

async function openFilters(canvasElement: HTMLElement): Promise<void> {
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    }),
  );
}

/**
 * Which of `labels` the element match's own picker offers: 「明细 添加条件」
 * pressed, the checklist read and closed again.
 */
async function offersInLines(
  canvasElement: HTMLElement,
  labels: readonly string[],
): Promise<boolean[]> {
  await userEvent.click(
    await within(canvasElement).findByRole('button', {
      name: `明细 ${zhCN['label.filter.add-condition']}`,
    }),
  );
  const picker = await within(document.body).findByRole('dialog');
  await expect(picker).toHaveTextContent(zhCN['label.filter.pick-fields']);
  const offered = labels.map(
    name => within(picker).queryByRole('checkbox', { name }) !== null,
  );
  await userEvent.click(
    within(picker).getByRole('button', {
      name: zhCN['label.filter.pick-done'],
    }),
  );
  return offered;
}

/**
 * Elasticsearch: the saved view searches the lines' SKUs for 「tea」, and
 * only orders with such a line come back; the search is a condition inside
 * the element match, offered by its picker, and typing another word there
 * searches the same line again.
 */
export const SearchesInsideTheLines: Story = {
  ...DisplayElasticsearch,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      readColumn(await findDataTable(canvasElement), '订单号'),
    ).toEqual(['SO-1001', 'SO-1003', 'SO-1006']);

    await openFilters(canvasElement);
    const box = await canvas.findByRole('textbox', { name: '搜索货号 值' });
    await expect(box).toHaveValue('tea');
    await expect(await offersInLines(canvasElement, ['货号', '数量'])).toEqual([
      true,
      true,
    ]);

    await userEvent.clear(box);
    await userEvent.type(box, 'cup');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    );
    await waitFor(async () =>
      expect(readColumn(await findDataTable(canvasElement), '订单号')).toEqual([
        'SO-1001',
        'SO-1004',
      ]),
    );
    await expect(
      canvas.getByRole('button', {
        name: /明细 任一条目满足 搜索货号 全文搜索 cup$/,
      }),
    ).toBeVisible();
  },
};

/**
 * MongoDB: the lines can still be filtered one by one, but their picker
 * offers no search — hidden, not greyed out — because the store lists none
 * on the element.
 */
export const OffersNoSearchOnMongoDb: Story = {
  ...DisplayMongoDb,
  play: async ({ canvasElement }) => {
    const rows = readColumn(await findDataTable(canvasElement), '订单号');
    await expect(rows.length).toBeGreaterThan(0);

    await openFilters(canvasElement);
    await userEvent.click(
      await within(canvasElement).findByRole('button', {
        name: zhCN['label.filter.add'],
      }),
    );
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '明细' }),
    );
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );

    await expect(
      await offersInLines(canvasElement, ['货号', '数量', '搜索货号']),
    ).toEqual([true, true, false]);
  },
};
