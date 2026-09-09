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
import type { DemoArgs } from './FilterPanelExamples.js';

type Story = StoryObj<DemoArgs>;

export const playCustomEditor: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  await userEvent.click(canvas.getByRole('button', { name: '模拟候选失败' }));
  await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
  await userEvent.click(canvas.getByRole('button', { name: '恢复候选' }));
  await expect(canvas.getByRole('button', { name: '查询' })).toBeEnabled();
};

export const playSearchableSelect: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement),
    page = within(canvasElement.ownerDocument.body);
  await userEvent.click(canvas.getByRole('combobox', { name: '客户' }));
  const search = await page.findByRole('combobox', { name: '客户搜索' });
  await userEvent.type(search, '云杉');
  await expect(
    page.getByRole('option', { name: '云杉制造' }),
  ).toBeInTheDocument();
  await expect(
    page.queryByRole('option', { name: '远山科技' }),
  ).not.toBeInTheDocument();
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 0 次',
  );
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    'customer-1',
  );
  await userEvent.clear(search);
  await userEvent.type(search, '无匹配客户');
  await expect(page.getByText('没有匹配选项')).toBeInTheDocument();
  await userEvent.clear(search);
  await userEvent.type(search, '云杉');
  await userEvent.keyboard('{ArrowDown}{Enter}');
  await expect(
    canvas.getByRole('combobox', { name: '客户' }),
  ).toHaveTextContent('云杉制造');
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 0 次',
  );
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    'customer-3',
  );
  await userEvent.click(canvas.getByRole('combobox', { name: '客户' }));
  await userEvent.click(await page.findByText('已选 1 项'));
  await userEvent.click(page.getByRole('button', { name: '移除云杉制造' }));
  await userEvent.keyboard('{Escape}');
  await expect(
    canvas.getByRole('combobox', { name: '客户' }),
  ).toHaveTextContent('未设置');
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 1 次',
  );
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    'MATCH_ALL',
  );
};

export const playCompleteFilter: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  await expect(
    canvas.getByRole('group', { name: '自定义客户筛选器' }),
  ).toBeInTheDocument();
  await expect(
    canvas.queryByRole('group', { name: '客户筛选' }),
  ).not.toBeInTheDocument();
  await expect(
    canvas.queryByRole('button', { name: '删除客户条件' }),
  ).not.toBeInTheDocument();
  await userEvent.click(canvas.getByRole('button', { name: '清空客户选择' }));
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 0 次',
  );
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    'MATCH_ALL',
  );
  await userEvent.click(canvas.getByRole('button', { name: '移除此筛选' }));
  await expect(
    canvas.queryByRole('group', { name: '自定义客户筛选器' }),
  ).not.toBeInTheDocument();
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 1 次',
  );
  await expect(
    canvas.getByRole('button', { name: '撤销筛选修改' }),
  ).toBeDisabled();
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '筛选已同步',
  );
};
