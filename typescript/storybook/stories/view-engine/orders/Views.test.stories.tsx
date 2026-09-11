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
import displayMeta, { Workbench } from './Views.stories.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-我的工作视图-回归',
  title: 'View Engine/引擎与宿主/我的工作视图/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
export const SavedRisk: StoryObj<typeof meta> = {
  ...Workbench,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await canvas.findByRole('row', { name: /SO-202609-1001/ });
    await userEvent.click(
      canvas.getByRole('combobox', { name: '交期风险', exact: true }),
    );
    await userEvent.click(
      await page.findByRole('option', { name: '已超期未发完' }),
    );
    await expect(
      canvas.getByRole('row', { name: /SO-202609-1001/ }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '查询', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.queryByRole('row', { name: /SO-202609-1001/ }),
      ).not.toBeInTheDocument(),
    );
    await expect(
      await canvas.findByRole('row', { name: /SO-202609-1004/ }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '另存为', exact: true }),
    );
    const dialog = await page.findByRole('dialog', { name: '另存为视图' });
    await userEvent.clear(
      within(dialog).getByRole('textbox', { name: '视图名称' }),
    );
    await userEvent.type(
      within(dialog).getByRole('textbox', { name: '视图名称' }),
      '超期交付跟进',
    );
    await userEvent.click(
      within(dialog).getByRole('button', { name: '创建视图', exact: true }),
    );
    await waitFor(() =>
      expect(
        page.queryByRole('dialog', { name: '另存为视图' }),
      ).not.toBeInTheDocument(),
    );
    await userEvent.click(canvas.getByText('开发者：查看接入与保存结果'));
    await userEvent.click(
      canvas.getByRole('button', { name: '重新打开已保存视图' }),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: '超期交付跟进', exact: true }),
    );
    await expect(
      await canvas.findByRole('combobox', { name: '交期风险', exact: true }),
    ).toHaveTextContent('已超期未发完');
    await expect(
      await canvas.findByRole('row', { name: /SO-202609-1004/ }),
    ).toBeVisible();
  },
};
