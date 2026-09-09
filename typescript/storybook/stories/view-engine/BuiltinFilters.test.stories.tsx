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
  NextPageFailure as DisplayNextPageFailure,
  ResolveFailure as DisplayResolveFailure,
  DarkNarrow as DisplayDarkNarrow,
} from './BuiltinFilters.stories.js';
const meta = {
  ...displayMeta,
  title: 'View Engine/过滤器/内置组件/回归',
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
    await waitFor(() =>
      expect(canvas.getByTestId('builtin-query-count')).toHaveTextContent('1'),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('combobox', { name: '参与人' }),
      ).toHaveTextContent('用户甲'),
    );
    await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
    await userEvent.click(canvas.getByRole('combobox', { name: '参与人' }));
    await userEvent.click(await page.findByRole('option', { name: '用户乙' }));
    await userEvent.click(page.getByRole('button', { name: '加载更多' }));
    await userEvent.click(await page.findByRole('option', { name: '用户丙' }));
    await userEvent.keyboard('{Escape}');
    await expect(
      canvas.queryAllByRole('textbox', { name: /创建时间.*时间/ }),
    ).toHaveLength(0);
    await userEvent.click(
      canvas.getByRole('button', { name: /创建时间日期范围/ }),
    );
    const rangePicker = within(
      await page.findByRole('dialog', { name: '创建时间日期范围' }),
    );
    await expect(rangePicker.getAllByRole('grid')).toHaveLength(2);
    const today = new Date();
    const monthOffset =
      (2026 - today.getFullYear()) * 12 + 8 - today.getMonth();
    for (let month = 0; month < Math.abs(monthOffset); month++)
      await userEvent.click(
        rangePicker.getByRole('button', {
          name: monthOffset < 0 ? '前往上个月' : '前往下个月',
        }),
      );
    await userEvent.click(
      rangePicker.getByRole('button', { name: /^2026年9月6日 星期日/ }),
    );
    await userEvent.keyboard('{Escape}');
    await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
    await userEvent.click(
      canvas.getByRole('button', { name: /创建时间日期范围/ }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: /^2026年9月6日 星期日/ }),
    );
    await expect(canvas.getByTestId('builtin-query-count')).toHaveTextContent(
      '1',
    );
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await waitFor(() =>
      expect(canvas.getByTestId('builtin-query')).toHaveTextContent('u3'),
    );
    await expect(
      JSON.parse(canvas.getByTestId('builtin-query').textContent!),
    ).toMatchObject({
      op: 'AND',
      operands: expect.arrayContaining([
        {
          op: 'BETWEEN',
          field: 'created',
          lowerBound: Date.parse('2026-09-05T16:00:00Z'),
          upperBound: Date.parse('2026-09-06T15:59:59.999Z'),
        },
      ]),
    });
    await userEvent.click(canvas.getByRole('button', { name: '保存' }));
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled(),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '查看保存 JSON' }),
    );
    await waitFor(() =>
      expect(canvas.getByTestId('builtin-saved')).toHaveTextContent(
        'selectedOptions',
      ),
    );
    await expect(canvas.getByTestId('builtin-saved')).toHaveTextContent(
      'remote-multi-select',
    );
    await expect(
      JSON.parse(canvas.getByTestId('builtin-saved').textContent!),
    ).toMatchObject({
      config: {
        filters: {
          root: {
            operands: expect.arrayContaining([
              expect.objectContaining({
                field: 'created',
                component: { name: 'datetime-range' },
                props: {
                  lowerBound: { date: '2026-09-06' },
                  upperBound: { date: '2026-09-06' },
                },
              }),
            ]),
          },
        },
      },
    });
    await userEvent.click(
      canvas.getByRole('button', { name: '重新打开已保存视图' }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('combobox', { name: '参与人' }),
      ).toHaveTextContent('+2'),
    );
    await expect(
      canvas.getByRole('button', { name: /创建时间日期范围/ }),
    ).toHaveTextContent('2026-09-06 至 2026-09-06');
    await expect(
      canvas.queryAllByRole('textbox', { name: /创建时间.*时间/ }),
    ).toHaveLength(0);
    await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
  },
};
export const NextPageFailure: Story = {
  ...DisplayNextPageFailure,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      await canvas.findByRole('combobox', { name: '参与人' }),
    );
    await page.findByRole('option', { name: '用户甲' });
    await userEvent.click(page.getByRole('button', { name: '加载更多' }));
    await expect(
      await page.findByRole('button', { name: '重试候选' }),
    ).toBeVisible();
    await expect(page.getByRole('option', { name: '用户甲' })).toBeVisible();
    await userEvent.click(page.getByRole('button', { name: '重试候选' }));
    await expect(
      await page.findByRole('option', { name: '用户丙' }),
    ).toBeVisible();
    await userEvent.keyboard('{Escape}');
  },
};
export const ResolveFailure: Story = {
  ...DisplayResolveFailure,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await waitFor(() =>
      expect(
        canvas.getByRole('combobox', { name: '参与人' }),
      ).toHaveTextContent('保存的用户甲'),
    );
    await userEvent.click(canvas.getByRole('combobox', { name: '参与人' }));
    await userEvent.click(
      await page.findByRole('button', { name: '重试回填' }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('combobox', { name: '参与人' }),
      ).toHaveTextContent('用户甲'),
    );
    await userEvent.keyboard('{Escape}');
    await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
    await expect(canvas.getByTestId('builtin-query-count')).toHaveTextContent(
      '1',
    );
  },
};
export const DarkNarrow: Story = {
  ...DisplayDarkNarrow,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      await canvas.findByRole('combobox', { name: '状态' }),
    );
    const item = await page.findByRole('option', { name: '待处理' });
    await expect(item).toBeVisible();
    await userEvent.click(item);
    await userEvent.keyboard('{Escape}');
    const values = canvas.getByRole('textbox', { name: '批量编号' });
    await userEvent.type(values, '001');
    await userEvent.keyboard('{Enter}');
    await expect(canvas.getByRole('button', { name: '移除001' })).toBeVisible();
    await expect(canvas.getByTestId('builtin-query-count')).toHaveTextContent(
      '1',
    );
  },
};

export const PasteSelection: Story = {
  ...DisplayWorkbench,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const input = await canvas.findByRole('textbox', { name: '批量编号' });
    await userEvent.type(input, 'old');
    await userEvent.click(input);
    (input as HTMLInputElement).setSelectionRange(0, 3);
    await userEvent.paste('001,002');
    await expect(canvas.getByRole('button', { name: '移除001' })).toBeVisible();
    await expect(canvas.getByRole('button', { name: '移除002' })).toBeVisible();
    await expect(
      canvas.queryByRole('button', { name: '移除old001' }),
    ).toBeNull();
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await waitFor(() =>
      expect(canvas.getByTestId('builtin-query')).toHaveTextContent('001'),
    );
  },
};
