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
import { expect, userEvent, within, waitFor } from 'storybook/test';
import displayMeta, {
  Workbench as DisplayWorkbench,
  DarkNarrow as DisplayDarkNarrow,
  InvalidData as DisplayInvalidData,
} from './BuiltinCells.stories.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-专项场景-单元格-内置组件-回归',
  title: 'View Engine/专项场景/组件与主题/内置单元格/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
type Story = StoryObj<typeof displayMeta>;
export const Workbench: Story = {
  ...DisplayWorkbench,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await canvas.findByText('ORDER-20260908-000001');
    await expect(canvas.getByText('¥1,234.50')).toBeVisible();
    const more = canvas.getByRole('button', { name: '查看全部 4 个标签' });
    more.focus();
    await userEvent.keyboard('{Enter}');
    await expect(await page.findByText('重点客户')).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(more).toHaveFocus());
    await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
    await userEvent.click(page.getByRole('checkbox', { name: /显示.*链接/ }));
    await userEvent.keyboard('{Escape}');
    await userEvent.click(canvas.getByRole('button', { name: '保存' }));
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled(),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '重新打开已保存视图' }),
    );
    await waitFor(() =>
      expect(canvas.queryByRole('columnheader', { name: '链接' })).toBeNull(),
    );
    await expect(canvas.getByText('¥1,234.50')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '查看保存 JSON' }),
    );
    await waitFor(() =>
      expect(canvas.getByTestId('builtin-cells-saved')).toHaveTextContent(
        'text',
      ),
    );
    const persisted = JSON.parse(
      canvas.getByTestId('builtin-cells-saved').textContent!,
    );
    const columns = persisted.config.presentation.table.columns;
    await expect(
      columns.find((column: { id: string }) => column.id === 'id').renderer
        .options,
    ).toEqual({ copyable: true, ellipsis: true });
    await expect(
      columns.find((column: { id: string }) => column.id === 'link').visible,
    ).toBe(false);
  },
};
export const DarkNarrow: Story = {
  ...DisplayDarkNarrow,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(canvas.getByText('已完成')).toBeVisible();
    const longCell = canvas.getByTestId('long-text-cell');
    const copy = within(longCell).getByRole('button', { name: '复制文本' });
    await expect(copy.getBoundingClientRect().right).toBeLessThanOrEqual(
      longCell.getBoundingClientRect().right + 1,
    );
    await expect(longCell.scrollWidth).toBeLessThanOrEqual(
      longCell.clientWidth + 1,
    );
    const more = canvas.getByRole('button', { name: '查看全部 3 个标签' });
    more.focus();
    await userEvent.keyboard('{Enter}');
    await expect(
      await page.findByText('需要完整展示的超长标签内容与业务备注'),
    ).toBeVisible();
    const dialog = page.getByRole('dialog');
    await expect(getComputedStyle(dialog).colorScheme).toBe('dark');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(more).toHaveFocus());
  },
};
export const InvalidData: Story = {
  ...DisplayInvalidData,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByText('ORDER-20260908-000001');
    await expect(canvas.getByText('不可访问的地址')).toBeVisible();
    await expect(canvas.queryByRole('link')).toBeNull();
    await expect(canvas.getAllByText('—').length).toBeGreaterThanOrEqual(4);
    await expect(canvas.queryByRole('alert')).toBeNull();
  },
};
