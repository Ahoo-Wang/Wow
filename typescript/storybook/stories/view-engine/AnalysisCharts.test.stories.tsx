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
  AnyRepresentative as DisplayAny,
} from './AnalysisCharts.stories.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-分析图表-回归',
  title: 'View Engine/分析视图/图表与结果/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
type Story = StoryObj<typeof displayMeta>;
export const AnyRepresentative: Story = {
  ...DisplayAny,
  args: { scenario: 'representative', reloadable: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await expect(
      within(canvas.getByRole('application')).getByText('机械键盘 · P-1001', {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      within(canvas.getByRole('application')).getByText('机械键盘 · P-1003', {
        exact: true,
      }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '配置查询', exact: true }),
    );
    await expect(page.queryByLabelText('编辑指标 2')).toBeNull();
    await userEvent.click(page.getByLabelText('编辑维度 1'));
    await expect(
      await page.findByRole('combobox', { name: '维度 1 显示字段' }),
    ).toHaveTextContent('商品名称');
    const name = page.getByRole('textbox', { name: '维度 1 名称' });
    await userEvent.clear(name);
    await userEvent.type(name, '商品销售');
    await userEvent.click(
      page.getByRole('button', { name: '完成编辑', exact: true }),
    );
    await userEvent.click(
      page.getByRole('button', { name: '查看结果', exact: true }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '保存', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '保存', exact: true }),
      ).toBeDisabled(),
    );
    await userEvent.click(
      canvas.getByRole('tab', { name: '数据表', exact: true }),
    );
    await expect(
      await canvas.findByRole('row', {
        name: 'P-1001 机械键盘 300,000',
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      canvas.getByRole('row', { name: 'P-1003 机械键盘 170,000', exact: true }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('tab', { name: '分析', exact: true }),
    );
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '重载已保存视图' }),
    );
    await expect(
      await canvas.findByRole('button', { name: '展开查询配置' }),
    ).toHaveTextContent('商品销售');
    await expect(await canvas.findByRole('application')).toBeVisible();
    await expect(
      within(canvas.getByRole('application')).getByText('机械键盘 · P-1001', {
        exact: true,
      }),
    ).toBeVisible();
  },
};

export const RefreshAndExpansion: Story = {
  ...DisplayAny,
  name: '分析自动刷新与全屏',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '刷新', exact: true }),
    );
    await waitFor(() =>
      expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
        '查询次数：2',
      ),
    );
    await userEvent.click(canvas.getByRole('button', { name: '自动刷新设置' }));
    await userEvent.click(
      await page.findByRole('menuitemradio', { name: '每 30 秒' }),
    );
    await expect(
      canvas.getByRole('button', { name: '刷新', exact: true }),
    ).toHaveTextContent('30 秒');
    await userEvent.click(canvas.getByRole('button', { name: '展开视图' }));
    await expect(
      canvasElement.querySelector('[data-view-expanded="true"]'),
    ).not.toBeNull();
    await userEvent.keyboard('{Escape}');
    await expect(
      canvasElement.querySelector('[data-view-expanded="true"]'),
    ).toBeNull();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：2',
    );
    await userEvent.click(canvas.getByRole('button', { name: '自动刷新设置' }));
    await userEvent.click(
      await page.findByRole('menuitemradio', { name: '关闭自动刷新' }),
    );
  },
};
