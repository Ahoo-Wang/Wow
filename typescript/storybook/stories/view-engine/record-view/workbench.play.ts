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

import { expect, userEvent, waitFor, within } from 'storybook/test';
import type { RecordViewPlay } from './demoTypes.js';

export const playCompactWorkbench: RecordViewPlay = async ({
  canvasElement,
}) => {
  const canvas = within(canvasElement);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  const page = within(canvasElement.ownerDocument.body);
  const toolbar = canvas.getByRole('group', { name: '全局工具栏' });
  const tableToolbar = canvas.getByRole('group', { name: '记录工具栏' });
  const appliedFilters = canvas.getByRole('region', { name: '已应用筛选' });
  await expect(appliedFilters).toHaveTextContent('订单金额 大于等于 0');
  await expect(
    appliedFilters.getBoundingClientRect().top,
  ).toBeGreaterThanOrEqual(
    canvas.getByRole('region', { name: '筛选器' }).getBoundingClientRect()
      .bottom,
  );
  await expect(
    appliedFilters.getBoundingClientRect().bottom,
  ).toBeLessThanOrEqual(tableToolbar.getBoundingClientRect().top);
  const save = within(toolbar)
    .getByRole('button', { name: '保存' })
    .getBoundingClientRect();
  const title = within(toolbar)
    .getByRole('heading', { name: '订单管理' })
    .getBoundingClientRect();
  const create = within(toolbar)
    .getByRole('button', { name: '创建订单' })
    .getBoundingClientRect();
  await expect(
    Math.abs(save.top + save.height / 2 - create.top - create.height / 2),
  ).toBeLessThan(2);
  await expect(title.right).toBeLessThan(save.left);
  await expect(title.right).toBeLessThan(create.left);
  await expect(
    within(toolbar).queryByRole('button', { name: /批量处理|列设置/ }),
  ).not.toBeInTheDocument();
  await expect(
    within(tableToolbar).getByRole('button', { name: /批量处理/ }),
  ).toBeInTheDocument();
  await expect(
    within(tableToolbar).getByRole('button', { name: '列设置' }),
  ).toBeInTheDocument();
  const batch = within(tableToolbar)
    .getByRole('button', { name: /批量处理/ })
    .getBoundingClientRect();
  const columns = within(tableToolbar)
    .getByRole('button', { name: '列设置' })
    .getBoundingClientRect();
  await expect(batch.left).toBeGreaterThan(
    tableToolbar.getBoundingClientRect().left +
      tableToolbar.getBoundingClientRect().width / 2,
  );
  await expect(batch.right).toBeLessThan(columns.left);
  await expect(
    canvas.queryByText('筛选条件', { exact: true }),
  ).not.toBeInTheDocument();
  const query = canvas
    .getByRole('button', { name: '查询' })
    .getBoundingClientRect();
  await expect(
    canvas.getByRole('button', { name: '清空条件' }).getBoundingClientRect()
      .right,
  ).toBeLessThan(query.left);

  const saveOptions = canvas.getByRole('button', { name: '视图选项' });
  await userEvent.click(saveOptions);
  await userEvent.click(await page.findByRole('menuitem', { name: '另存为' }));
  const dialog = await page.findByRole('dialog', { name: '另存为视图' });
  await waitFor(() =>
    expect(
      within(dialog).getByRole('textbox', { name: '视图名称' }),
    ).toHaveFocus(),
  );
  await userEvent.keyboard('{Escape}');
  await waitFor(() => expect(saveOptions).toHaveFocus());
  const mode = within(toolbar).getByRole('button', { name: '筛选模式' });
  await userEvent.click(mode);
  await userEvent.click(
    await page.findByRole('menuitemradio', { name: '高级' }),
  );
  await waitFor(() => expect(page.queryByRole('menu')).not.toBeInTheDocument());
  await expect(
    within(toolbar).getByRole('button', { name: '收起筛选' }),
  ).toHaveAttribute('title', '收起筛选 · 高级');
  await userEvent.click(mode);
  await userEvent.click(
    await page.findByRole('menuitemradio', { name: '简单' }),
  );
  await waitFor(() => expect(page.queryByRole('menu')).not.toBeInTheDocument());
  await expect(
    within(toolbar).getByRole('button', { name: '收起筛选' }),
  ).toHaveAttribute('title', '收起筛选 · 简单');
  await expect(
    within(canvas.getByRole('navigation', { name: '记录分页' })).getByText(
      '共 18 条记录',
    ),
  ).toBeInTheDocument();
  const amount = canvas.getByRole('textbox', { name: '订单金额值' });
  await userEvent.clear(amount);
  await userEvent.type(amount, '2500');
  await expect(canvas.getByText('筛选未生效')).toBeVisible();
  await expect(appliedFilters).toHaveTextContent('订单金额 大于等于 0');
  await expect(
    within(appliedFilters).getByRole('button', {
      name: '清空条件值：订单金额 大于等于 0',
    }),
  ).toBeDisabled();
  await expect(
    within(toolbar).getByRole('button', { name: '收起筛选' }),
  ).not.toHaveTextContent('待查询');
  const tableTop = canvas.getByRole('table').getBoundingClientRect().top;
  await userEvent.click(
    within(toolbar).getByRole('button', { name: '收起筛选' }),
  );
  const expand = within(toolbar).getByRole('button', { name: '展开筛选' });
  await expect(expand).toHaveAttribute('aria-expanded', 'false');
  await expect(expand).toHaveFocus();
  await expect(expand).toHaveTextContent('待查询');
  await expect(amount).toBeInTheDocument();
  await expect(amount).not.toBeVisible();
  await expect(appliedFilters).toBeVisible();
  await expect(
    tableTop - canvas.getByRole('table').getBoundingClientRect().top,
  ).toBeGreaterThan(60);
  await userEvent.click(
    canvas.getByRole('checkbox', { name: '选择记录 ORD-202609-1001' }),
  );
  await expect(
    within(tableToolbar).getByText('已选本页 1 条'),
  ).toBeInTheDocument();
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
  await userEvent.click(expand);
  await expect(canvas.getByRole('textbox', { name: '订单金额值' })).toBe(
    amount,
  );
  await expect(amount).toHaveValue('2500');
  await userEvent.click(
    within(tableToolbar).getByRole('button', { name: '取消选择' }),
  );
  await expect(tableToolbar).toHaveFocus();
  await expect(
    canvas.getByRole('checkbox', { name: '选择记录 ORD-202609-1001' }),
  ).not.toBeChecked();
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(canvas.getByTestId('record-query-count')).toHaveTextContent(/^2$/),
  );
  await expect(canvas.getByTestId('record-query')).toHaveTextContent('2500');
  await expect(appliedFilters).toHaveTextContent('订单金额 大于等于 2500');
  await userEvent.click(
    within(toolbar).getByRole('button', { name: '收起筛选' }),
  );
  const clearApplied = within(appliedFilters).getByRole('button', {
    name: '清空条件值：订单金额 大于等于 2500',
  });
  await waitFor(() => expect(clearApplied).toBeEnabled());
  clearApplied.focus();
  await userEvent.keyboard('{Enter}');
  await waitFor(() =>
    expect(canvas.getByTestId('record-query-count')).toHaveTextContent(/^3$/),
  );
  await expect(appliedFilters).toHaveFocus();
  await expect(appliedFilters).toHaveTextContent('全部记录');
  await expect(canvas.getByTestId('record-query')).toHaveTextContent(
    'MATCH_ALL',
  );
  await userEvent.click(
    within(toolbar).getByRole('button', { name: '展开筛选' }),
  );
  await expect(canvas.getByRole('textbox', { name: '订单金额值' })).toBe(
    amount,
  );
  await expect(amount).toHaveValue('');
  await userEvent.type(amount, '1250');
  await userEvent.keyboard('{Enter}');
  await waitFor(() =>
    expect(canvas.getByTestId('record-query-count')).toHaveTextContent(/^4$/),
  );
  await expect(appliedFilters).toHaveTextContent('订单金额 大于等于 1250');
  await userEvent.click(
    within(toolbar).getByRole('button', { name: '收起筛选' }),
  );
};
