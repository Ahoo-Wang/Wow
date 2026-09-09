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

import { FILTER_OPERATORS } from '@ahoo-wang/fetcher-view-engine';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, within } from 'storybook/test';
import type { DemoArgs } from './FilterPanelExamples.js';

type Story = StoryObj<DemoArgs>;

export const playBusinessFilters: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  await expect(
    canvas.queryAllByRole('button', {
      name: /条件操作|移动.*条件|订单金额值选项/,
    }),
  ).toHaveLength(0);
  await expect(
    canvas.queryByRole('button', { name: '清空订单金额值' }),
  ).toBeNull();
  await userEvent.clear(canvas.getByLabelText('订单金额值'));
  await expect(canvas.getByLabelText('订单金额值')).toHaveValue('');
  await userEvent.clear(canvas.getByLabelText('订单金额值'));
  await userEvent.type(canvas.getByLabelText('订单金额值'), '1500');
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 0 次',
  );
  await expect(canvas.getByText('待查询', { exact: true })).toBeInTheDocument();
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent('1500');
  await userEvent.click(canvas.getByRole('button', { name: '清空条件' }));
  await expect(canvas.getByLabelText('宿主状态')).toHaveTextContent(
    '已应用 1 次',
  );
  await userEvent.click(canvas.getByRole('button', { name: '撤销筛选修改' }));
  await expect(canvas.getByLabelText('订单金额值')).toHaveValue('1500');
};

export const playAdvancedTree: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  await expect(
    canvas.queryAllByRole('button', { name: /移动.*条件/ }),
  ).toHaveLength(0);
  await expect(canvas.getByLabelText('数量值')).toHaveValue('2');
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    'ELEMENT_MATCH',
  );
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent('NOR');
};

export const playSavedDateTime: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement);
  const time = canvas.getByRole('textbox', { name: '创建时间时间' });
  await expect(time).toHaveValue('01:30:00');
  await userEvent.clear(time);
  await userEvent.type(time, '01:30:00');
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(
    '1793514600000',
  );
};

export const playAllOperators: NonNullable<Story['play']> = async ({
  canvasElement,
  args,
}) => {
  if (args.disabled) return;
  const canvas = within(canvasElement),
    page = within(canvasElement.ownerDocument.body);
  for (const op of [
    FilterOperator.SEARCH,
    FilterOperator.ELEMENT_MATCH,
    FilterOperator.BETWEEN,
    FilterOperator.NEXT_YEAR,
    FilterOperator.DELETION,
    FilterOperator.IDS,
  ]) {
    await userEvent.click(canvas.getByRole('combobox', { name: '协议操作' }));
    await userEvent.click(
      await page.findByRole('option', {
        name: `${op} · ${FILTER_OPERATORS[op].label}`,
      }),
    );
    await expect(canvas.getByRole('button', { name: '查询' })).toBeEnabled();
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await expect(canvas.getByTestId('applied-filter')).toHaveTextContent(op);
  }
};
