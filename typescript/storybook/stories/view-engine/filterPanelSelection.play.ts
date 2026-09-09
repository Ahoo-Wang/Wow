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
import type { DemoArgs } from './FilterPanelExamples.js';

type Story = StoryObj<DemoArgs>;

export const playGroupedFields: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  const add = canvas.getByRole('button', { name: '添加筛选' });
  await userEvent.click(add);
  let picker = within(
    await page.findByRole('dialog', { name: '选择筛选字段' }),
  );
  for (const group of ['订单信息', '客户信息', '时间', '其他字段'])
    await expect(picker.getByText(group, { exact: true })).toBeVisible();
  for (const label of ['订单状态', '订单金额'])
    await expect(picker.getByRole('checkbox', { name: label })).toBeChecked();
  await userEvent.click(picker.getByText('客户', { exact: true }));
  const customer = picker.getByRole('checkbox', { name: '客户' });
  await expect(customer).toBeChecked();
  await expect(customer).toHaveFocus();
  await expect(canvas.getByRole('textbox', { name: '客户值' })).toBeVisible();
  await userEvent.keyboard(' ');
  await expect(customer).not.toBeChecked();
  await expect(canvas.queryByRole('textbox', { name: '客户值' })).toBeNull();
  await userEvent.keyboard(' ');
  await expect(customer).toBeChecked();
  await userEvent.click(picker.getByRole('checkbox', { name: '优先处理' }));
  await expect(
    picker.getByRole('checkbox', { name: '优先处理' }),
  ).toBeChecked();
  await expect(canvas.getAllByRole('textbox', { name: '客户值' })).toHaveLength(
    1,
  );
  await userEvent.click(picker.getByRole('button', { name: '完成' }));
  await waitFor(() =>
    expect(page.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  await userEvent.click(canvas.getByRole('button', { name: '删除客户条件' }));
  await userEvent.click(add);
  picker = within(await page.findByRole('dialog', { name: '选择筛选字段' }));
  await expect(
    picker.getByRole('checkbox', { name: '客户' }),
  ).not.toBeChecked();
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(page.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  await expect(add).toHaveFocus();
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 0 次',
  );
};

export const playLogicalGroupMenu: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  const add = canvas.getByRole('button', { name: '添加筛选' });
  const group = canvas.getByRole('button', { name: '添加逻辑分组' });
  const buttons = canvas.getByRole('group', { name: '添加筛选' });
  await expect(within(buttons).getAllByRole('button')).toHaveLength(2);
  await expect(group.getBoundingClientRect().left).toBeCloseTo(
    add.getBoundingClientRect().right,
    0,
  );
  await expect(getComputedStyle(add).borderTopRightRadius).toBe('0px');
  await expect(getComputedStyle(group).borderTopLeftRadius).toBe('0px');
  await expect(getComputedStyle(group).borderLeftWidth).toBe('0px');
  await userEvent.click(add);
  let picker = within(
    await page.findByRole('dialog', { name: '选择筛选字段' }),
  );
  await expect(picker.queryByText('组合条件')).toBeNull();
  await expect(
    picker.queryByRole('button', {
      name: /满足全部条件|满足任一条件|全部条件均不满足/,
    }),
  ).toBeNull();
  await userEvent.click(group);
  await waitFor(() =>
    expect(page.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  await expect(
    await page.findByRole('menuitem', { name: /^AND/ }),
  ).toBeVisible();
  await expect(page.getByRole('menuitem', { name: /^NOR/ })).toBeVisible();
  await userEvent.click(page.getByRole('menuitem', { name: /^OR/ }));
  await waitFor(() => expect(group).toHaveFocus());
  await userEvent.click(add);
  picker = within(await page.findByRole('dialog', { name: '选择筛选字段' }));
  await userEvent.click(picker.getByRole('checkbox', { name: '订单状态' }));
  await userEvent.click(picker.getByRole('checkbox', { name: '订单金额' }));
  await userEvent.click(picker.getByRole('button', { name: '完成' }));
  await waitFor(() => expect(page.queryByRole('dialog')).toBeNull());
  await userEvent.click(canvas.getByRole('combobox', { name: '订单状态' }));
  await userEvent.click(await page.findByRole('option', { name: '待处理' }));
  await userEvent.type(
    canvas.getByRole('textbox', { name: '订单金额值' }),
    '1000',
  );
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 0 次',
  );
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    '"op": "OR"',
  );
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 1 次',
  );
};

export const playRepeatedFields: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await expect(
    canvas.getByRole('combobox', { name: '筛选模式' }),
  ).toHaveTextContent('高级');
  await expect(canvas.getAllByRole('textbox', { name: '客户值' })).toHaveLength(
    2,
  );
  await userEvent.click(canvas.getByRole('button', { name: '添加筛选' }));
  const picker = within(
    await page.findByRole('dialog', { name: '选择筛选字段' }),
  );
  await expect(picker.getByText('2 条')).toBeVisible();
  await expect(picker.getByRole('checkbox', { name: '客户' })).toBeChecked();
  await userEvent.click(picker.getByRole('button', { name: '追加客户条件' }));
  await expect(picker.getByText('3 条')).toBeVisible();
  await expect(canvas.getAllByRole('textbox', { name: '客户值' })).toHaveLength(
    3,
  );
  await userEvent.click(picker.getByRole('button', { name: '完成' }));
  await waitFor(() => expect(page.queryByRole('dialog')).toBeNull());
  await userEvent.click(
    canvas.getAllByRole('button', { name: '删除客户条件' })[2],
  );
  await userEvent.click(canvas.getByRole('combobox', { name: '筛选模式' }));
  await expect(
    await page.findByRole('option', { name: '简单' }),
  ).toHaveAttribute('aria-disabled', 'true');
  await userEvent.keyboard('{Escape}');
  await waitFor(() => expect(page.queryByRole('listbox')).toBeNull());
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 0 次',
  );
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    'STARTS_WITH',
  );
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    'CONTAINS',
  );
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 1 次',
  );
};
