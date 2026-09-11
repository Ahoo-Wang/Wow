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
  Mixed as DisplayMixed,
  AnalysisOnly as DisplayAnalysisOnly,
} from './Analysis.stories.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-分析视图-回归',
  title: 'View Engine/分析视图/配置与执行/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
type Story = StoryObj<typeof displayMeta>;
export const Mixed: Story = {
  ...DisplayMixed,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByRole('cell', { name: '1,800', exact: true }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '配置查询', exact: true }),
    );
    let query = within(
      await within(canvasElement.ownerDocument.body).findByRole('dialog', {
        name: '配置查询',
      }),
    );
    await userEvent.click(query.getByText('高级设置', { exact: true }));
    const limit = query.getByRole('textbox', { name: '最多结果行数' });
    await userEvent.clear(limit);
    await userEvent.click(query.getByRole('button', { name: '运行并查看' }));
    await expect(
      query.getByRole('textbox', { name: '最多结果行数' }),
    ).toHaveFocus();
    await userEvent.click(query.getByRole('button', { name: '运行并查看' }));
    await expect(
      query.getByRole('textbox', { name: '最多结果行数' }),
    ).toHaveFocus();
    await userEvent.click(
      query.getByRole('button', { name: '查看结果', exact: true }),
    );
    const doc = within(canvasElement.ownerDocument.body);
    const switchTo = async (title: string) => {
      const sidebar = canvas.queryByRole('button', {
        name: new RegExp(`^${title}`),
      });
      if (sidebar) await userEvent.click(sidebar);
      else {
        await userEvent.click(
          canvas.getByRole('combobox', { name: '选择视图实例' }),
        );
        await userEvent.click(
          await doc.findByRole('option', { name: new RegExp(title) }),
        );
      }
    };
    await switchTo('订单记录');
    await expect(await canvas.findByText('SO-001')).toBeVisible();
    await switchTo('地区销售分析');
    await expect(
      canvas.getByRole('button', { name: '配置查询' }),
    ).toHaveAttribute('aria-expanded', 'false');
    await expect(
      canvas.getByRole('cell', { name: '1,800', exact: true }),
    ).toBeVisible();
    await userEvent.click(canvas.getByRole('button', { name: '配置查询' }));
    query = within(await doc.findByRole('dialog', { name: '配置查询' }));
    await expect(
      query.getByRole('textbox', { name: '最多结果行数' }),
    ).toHaveValue('');
    await userEvent.type(
      query.getByRole('textbox', { name: '最多结果行数' }),
      '1',
    );
    await userEvent.click(query.getByRole('button', { name: '运行并查看' }));
    await expect(doc.queryByRole('dialog', { name: '配置查询' })).toBeNull();
    await expect(
      await canvas.findByRole('cell', { name: '华东', exact: true }),
    ).toBeVisible();
    await waitFor(() =>
      expect(
        canvas.queryByRole('cell', { name: '华南', exact: true }),
      ).toBeNull(),
    );
  },
};
export const AnalysisOnly: Story = {
  ...DisplayAnalysisOnly,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByRole('cell', { name: '1,800', exact: true }),
    ).toBeVisible();
    await expect(
      canvas.queryByRole('button', { name: '查询', exact: true }),
    ).toBeNull();
    await userEvent.click(
      canvas.getByRole('button', { name: '另存为', exact: true }),
    );
    const dialog = within(
      await within(canvasElement.ownerDocument.body).findByRole('dialog', {
        name: '另存为视图',
      }),
    );
    await userEvent.clear(dialog.getByRole('textbox', { name: '视图名称' }));
    await userEvent.type(
      dialog.getByRole('textbox', { name: '视图名称' }),
      '我的地区分析',
    );
    await userEvent.click(
      dialog.getByRole('button', { name: '创建视图', exact: true }),
    );
    await expect(
      await canvas.findByRole('button', { name: '保存', exact: true }),
    ).toBeVisible();
  },
};
