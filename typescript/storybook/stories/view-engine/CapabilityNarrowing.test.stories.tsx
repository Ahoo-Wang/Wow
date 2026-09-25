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
import { expect, userEvent, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  OnElasticsearch as DisplayElasticsearch,
  OnMongoDb as DisplayMongoDb,
} from './CapabilityNarrowing.stories.js';
import { findDataTable, readColumn } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/随部署收窄/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out: a file's own description would otherwise replace the
  // display meta's parameters, and the host application with them.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * Whether the condition picker offers a field, by its label: the filter
 * fold opened, 「添加」 pressed, the checklist read and closed again.
 */
async function offers(
  canvasElement: HTMLElement,
  labels: readonly string[],
): Promise<boolean[]> {
  const canvas = within(canvasElement);
  await userEvent.click(
    canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    }),
  );
  await userEvent.click(
    await canvas.findByRole('button', { name: zhCN['label.filter.add'] }),
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

/** The search box and the notes condition, where the store has both. */
export const SearchesOnElasticsearch: Story = {
  ...DisplayElasticsearch,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const rows = readColumn(await findDataTable(canvasElement), '订单号');
    await expect(rows.length).toBeGreaterThan(0);
    await expect(
      await canvas.findByRole('searchbox', { name: '搜索备注' }),
    ).toBeVisible();
    await expect(await offers(canvasElement, ['备注', '仓库'])).toEqual([
      true,
      true,
    ]);
  },
};

/**
 * G15: on a store with no full-text search the box is not drawn at all —
 * hidden, not greyed out (Q1) — and the notes take no condition; the view
 * still opens and shows its rows.
 */
export const HidesTheSearchOnMongoDb: Story = {
  ...DisplayMongoDb,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const rows = readColumn(await findDataTable(canvasElement), '订单号');
    await expect(rows.length).toBeGreaterThan(0);
    await expect(canvas.queryByRole('searchbox')).toBeNull();
    await expect(await offers(canvasElement, ['备注', '仓库'])).toEqual([
      false,
      true,
    ]);
  },
};
