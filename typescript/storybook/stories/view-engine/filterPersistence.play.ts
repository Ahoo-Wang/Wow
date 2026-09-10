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
import type { OrderWorkbench } from '../../packages/view-engine/examples/react/sales-order/OrderWorkbench.js';

type Play = NonNullable<StoryObj<typeof OrderWorkbench>['play']>;

export const playFilterPersistence: Play = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  const queryCount = () => canvas.getByTestId('persistence-query-count');
  const saveCount = () => canvas.getByTestId('persistence-save-count');
  const save = () => canvas.getByRole('button', { name: '保存' });
  const reopen = () => canvas.getByRole('button', { name: '重新打开已存视图' });
  const status = () => canvas.getByRole('combobox', { name: '订单状态' });
  const label = () => canvas.getByRole('textbox', { name: '状态显示名称' });
  await canvas.findByRole('row', { name: /SO-202609-1018/ });
  await expect(queryCount()).toHaveTextContent(/^1$/);

  await userEvent.click(canvas.getByRole('button', { name: '添加筛选' }));
  const picker = within(
    await page.findByRole('dialog', { name: '选择筛选字段' }),
  );
  await userEvent.click(picker.getByRole('checkbox', { name: '订单状态' }));
  await userEvent.click(picker.getByRole('button', { name: '完成' }));
  await expect(status()).toHaveTextContent('不限');
  await expect(save()).toBeEnabled();
  await expect(canvas.queryByText('筛选未生效')).not.toBeInTheDocument();
  await userEvent.click(save());
  await waitFor(() => expect(saveCount()).toHaveTextContent(/^1$/));
  await expect(queryCount()).toHaveTextContent(/^1$/);
  await expect(canvas.getByTestId('persisted-filter-config')).toHaveTextContent(
    'order-status',
  );
  await expect(
    canvas.getByTestId('persisted-filter-config'),
  ).not.toHaveTextContent('selectedId');

  await userEvent.click(reopen());
  await canvas.findByRole('row', { name: /SO-202609-1018/ });
  await expect(status()).toHaveTextContent('不限');
  await expect(queryCount()).toHaveTextContent(/^2$/);
  await expect(save()).toBeDisabled();

  // This editable label is opaque UI state: MATCH_ALL/EQ cannot reconstruct it.
  await userEvent.type(label(), '人工定义待办');
  await expect(save()).toBeEnabled();
  await userEvent.click(save());
  await waitFor(() => expect(saveCount()).toHaveTextContent(/^2$/));
  await expect(queryCount()).toHaveTextContent(/^2$/);
  await userEvent.click(reopen());
  await canvas.findByRole('row', { name: /SO-202609-1018/ });
  await expect(label()).toHaveValue('人工定义待办');
  await expect(status()).toHaveTextContent('不限');
  await expect(queryCount()).toHaveTextContent(/^3$/);

  await userEvent.click(status());
  await userEvent.click(await page.findByRole('option', { name: '已确认' }));
  await expect(save()).toBeDisabled();
  await expect(canvas.getByText('筛选未生效')).toBeInTheDocument();
  await expect(queryCount()).toHaveTextContent(/^3$/);
  await userEvent.click(label());
  await userEvent.keyboard('{Enter}');
  await waitFor(() => expect(save()).toBeEnabled());
  await expect(queryCount()).toHaveTextContent(/^4$/);
  await expect(await canvas.findByText('共 13 条记录')).toBeInTheDocument();
  await userEvent.click(save());
  await waitFor(() => expect(saveCount()).toHaveTextContent(/^3$/));
  await expect(queryCount()).toHaveTextContent(/^4$/);
  await userEvent.click(reopen());
  await canvas.findByRole('row', { name: /SO-202609-1017/ });
  await expect(queryCount()).toHaveTextContent(/^5$/);
  await expect(status()).toHaveTextContent('已确认');
  await expect(label()).toHaveValue('人工定义待办');
  await expect(canvas.getByTestId('persisted-filter-config')).toHaveTextContent(
    '人工定义待办',
  );
  await expect(canvas.getByTestId('persistence-query')).toHaveTextContent(
    'confirmed',
  );
  await expect(canvas.getByTestId('persistence-query')).not.toHaveTextContent(
    '人工定义待办',
  );
  await expect(save()).toBeDisabled();

  await userEvent.click(
    canvas.getByRole('button', { name: '清空条件值：订单状态 等于 已确认' }),
  );
  await expect(await canvas.findByText('共 18 条记录')).toBeInTheDocument();
  await expect(status()).toHaveTextContent('不限');
  await expect(label()).toHaveValue('人工定义待办');
  await expect(queryCount()).toHaveTextContent(/^6$/);
  await userEvent.click(save());
  await waitFor(() => expect(saveCount()).toHaveTextContent(/^4$/));
  await expect(queryCount()).toHaveTextContent(/^6$/);
  await userEvent.click(reopen());
  await canvas.findByRole('row', { name: /SO-202609-1018/ });
  await expect(queryCount()).toHaveTextContent(/^7$/);
  await expect(status()).toHaveTextContent('不限');
  await expect(label()).toHaveValue('人工定义待办');
  await expect(
    canvas.getByTestId('persisted-filter-config'),
  ).not.toHaveTextContent('selectedId');
  await expect(save()).toBeDisabled();
};
