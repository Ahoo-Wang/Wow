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
  FirstDashboard as Empty,
  Overview as Loaded,
  MissingReference as Broken,
  ContentCards as Content,
} from './Dashboard.stories.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-dashboard-regression',
  title: 'View Engine/仪表盘视图/业务仪表盘/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
type Story = StoryObj<typeof displayMeta>;
export const CreateAndBind: Story = {
  ...Empty,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      await canvas.findByRole('button', { name: '新建仪表盘' }),
    );
    await userEvent.type(
      await page.findByRole('textbox', { name: '名称' }),
      '区域销售',
    );
    await userEvent.click(page.getByRole('button', { name: '创建草稿' }));
    await userEvent.click(
      await canvas.findByRole('button', { name: '添加', exact: true }),
    );
    await userEvent.click(
      await within(document.body).findByRole('menuitem', { name: '添加面板' }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: /订单明细/ }),
    );
    await expect(
      await canvas.findByRole('cell', { name: 'SO-1', exact: true }),
    ).toBeVisible();
    await userEvent.click(canvas.getByRole('button', { name: '全局筛选设置' }));
    await userEvent.click(canvas.getByRole('button', { name: '添加全局筛选' }));
    await expect(
      canvas.getByRole('button', { name: '查询', exact: true }),
    ).toBeDisabled();
    await expect(
      canvas.getByRole('button', { name: '保存', exact: true }),
    ).toBeDisabled();
    await userEvent.selectOptions(
      canvas.getByRole('combobox', { name: /绑定方式/ }),
      'excluded',
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '查询', exact: true }),
    );
    await userEvent.click(canvas.getByRole('button', { name: '编辑布局' }));
    canvas.getByRole('button', { name: '调整订单明细尺寸' }).focus();
    await userEvent.keyboard('{ArrowDown}');
    await expect(canvas.queryByRole('spinbutton')).not.toBeInTheDocument();
    await userEvent.click(
      canvas.getByRole('button', { name: '保存', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('status', { name: '保存状态' }),
      ).toHaveTextContent('视图已保存'),
    );
  },
};
export const LayoutAndPaging: Story = {
  ...Loaded,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        within(
          canvas.getByRole('table', { name: '订单明细', exact: true }),
        ).getByRole('cell', { name: 'SO-1', exact: true }),
      ).toBeVisible(),
    );
    const table = canvas.getByRole('table', { name: '订单明细', exact: true });
    const panel = within(
      table.closest('[data-dashboard-panel]') as HTMLElement,
    );
    await userEvent.click(panel.getByRole('button', { name: '下一页' }));
    await waitFor(() =>
      expect(
        within(
          canvas.getByRole('table', { name: '订单明细', exact: true }),
        ).getByRole('cell', { name: 'SO-11', exact: true }),
      ).toBeVisible(),
    );
    await userEvent.click(canvas.getByRole('button', { name: '编辑布局' }));
    canvas.getByRole('button', { name: '移动订单明细' }).focus();
    await userEvent.keyboard('{ArrowRight}');
    canvas.getByRole('button', { name: '调整订单明细尺寸' }).focus();
    await userEvent.keyboard('{ArrowDown}');
    await expect(canvas.queryByRole('spinbutton')).not.toBeInTheDocument();
    await expect(
      within(
        canvas.getByRole('table', { name: '订单明细', exact: true }),
      ).getByRole('cell', { name: 'SO-11', exact: true }),
    ).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: '保存', exact: true }),
    ).toBeEnabled();
  },
};
export const ReplaceReference: Story = {
  ...Broken,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(
      await canvas.findByText('库存视图已停用，请替换引用'),
    ).toBeVisible();
    await userEvent.click(canvas.getByRole('button', { name: '编辑布局' }));
    await userEvent.click(canvas.getByRole('button', { name: '替换面板3' }));
    await userEvent.click(
      await page.findByRole('button', { name: /订单明细/ }),
    );
    await waitFor(() =>
      expect(canvas.queryByText('库存视图已停用，请替换引用')).toBeNull(),
    );
    await expect(
      canvas.getAllByRole('table', { name: '订单明细', exact: true }),
    ).toHaveLength(2);
  },
};

export const ContentEditing: Story = {
  ...Content,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const page = within(canvasElement.ownerDocument.body);
    await canvas.findByRole('heading', { name: '本周经营提示' });
    await userEvent.click(
      canvas.getByRole('button', { name: '添加', exact: true }),
    );
    await page.findByRole('menuitem', { name: '添加Markdown' });
    await expect(
      page.queryByRole('menuitem', { name: '添加面板' }),
    ).not.toBeInTheDocument();
    await userEvent.click(
      await within(document.body).findByRole('menuitem', {
        name: '添加Markdown',
      }),
    );
    await userEvent.type(
      page.getByRole('textbox', { name: '标题' }),
      '经营备注',
    );
    await userEvent.type(
      page.getByRole('textbox', { name: 'Markdown 内容' }),
      '**本周关注**',
    );
    await userEvent.click(
      page.getByRole('button', { name: '取消', exact: true }),
    );
    await expect(
      canvas.queryByRole('heading', { name: '经营备注' }),
    ).not.toBeInTheDocument();
    await userEvent.click(
      canvas.getByRole('button', { name: '添加', exact: true }),
    );
    await userEvent.click(
      await within(document.body).findByRole('menuitem', { name: '添加链接' }),
    );
    await userEvent.type(
      page.getByRole('textbox', { name: '标题' }),
      '帮助中心',
    );
    await userEvent.type(
      page.getByRole('textbox', { name: '链接地址' }),
      'https://example.com/help',
    );
    await userEvent.click(page.getByRole('button', { name: '添加内容' }));
    await expect(
      await canvas.findByRole('link', { name: '帮助中心' }),
    ).toHaveAttribute('rel', 'noopener noreferrer');
    await userEvent.click(canvas.getByRole('button', { name: '编辑布局' }));
    await userEvent.click(canvas.getByRole('button', { name: '编辑面板4' }));
    await userEvent.clear(page.getByRole('textbox', { name: '标题' }));
    await userEvent.type(
      page.getByRole('textbox', { name: '标题' }),
      '业务帮助',
    );
    await userEvent.click(page.getByRole('button', { name: '更新内容' }));
    await expect(
      await canvas.findByRole('link', { name: '业务帮助' }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '保存', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('status', { name: '保存状态' }),
      ).toHaveTextContent('视图已保存'),
    );
    await expect(
      canvas.queryByRole('button', { name: '取消布局编辑' }),
    ).not.toBeInTheDocument();
    await userEvent.click(canvas.getByRole('button', { name: '编辑布局' }));
    await userEvent.click(canvas.getByRole('button', { name: '移除面板4' }));
    await expect(
      canvas.queryByRole('link', { name: '业务帮助' }),
    ).not.toBeInTheDocument();
  },
};
