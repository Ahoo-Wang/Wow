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
import displayMeta, { ImportableThemes } from './Themes.stories.js';

const meta = {
  ...displayMeta,
  id: 'view-engine-专项场景-主题-可导入主题-回归',
  title: 'View Engine/扩展与组件/可导入主题/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
export const UsableViews: StoryObj<typeof meta> = {
  ...ImportableThemes,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('table')).toBeVisible();
    await expect(await canvas.findByText('SO-202609-1018')).toBeVisible();
    const note = canvas.getByRole('textbox', { name: '业务备注' });
    await userEvent.type(note, '保留主题输入');
    await userEvent.click(
      canvas.getByRole('button', { name: '切换明暗', exact: true }),
    );
    await userEvent.click(canvas.getByRole('button', { name: '切换密度' }));
    await expect(note).toHaveValue('保留主题输入');
    await expect(canvas.getByRole('table')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '展示方式：表格' }),
    );
    await userEvent.click(
      await page.findByRole('menuitemradio', { name: '卡片', exact: true }),
    );
    await expect(
      await canvas.findByRole('list', { name: '记录卡片' }),
    ).toBeVisible();
    await expect(canvas.queryByRole('alert')).not.toBeInTheDocument();
  },
};
