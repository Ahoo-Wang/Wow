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

export const playCursorRecords: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  await expect(
    canvas.queryByRole('button', { name: '上一页' }),
  ).not.toBeInTheDocument();
  await userEvent.click(
    canvas.getByRole('checkbox', { name: '选择记录 ORD-202609-1001' }),
  );
  for (const id of ['1006', '1011', '1016']) {
    await userEvent.click(canvas.getByRole('button', { name: '下一页' }));
    await canvas.findByRole('row', { name: new RegExp(`ORD-202609-${id}`) });
    await expect(
      canvas.getByRole('button', { name: /批量处理/ }),
    ).toBeDisabled();
  }
  await expect(canvas.getByText('第 4 页')).toBeInTheDocument();
  await expect(canvas.getByText('本页 3 条记录')).toBeInTheDocument();
  await expect(canvas.getByRole('button', { name: '下一页' })).toBeDisabled();
  await expect(canvas.getByTestId('record-query')).toHaveTextContent(
    'orders:15',
  );
};

export const playEmptyRecords: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  await canvas.findByText('共 0 条记录');
  await expect(canvas.getByRole('button', { name: '创建订单' })).toBeEnabled();
  await expect(canvas.getByRole('button', { name: /批量处理/ })).toBeDisabled();
  await userEvent.click(canvas.getByRole('button', { name: '创建订单' }));
  await canvas.findByRole('row', { name: /ORD-202609-1019/ });
  await expect(canvas.getByText('共 1 条记录')).toBeInTheDocument();
  await expect(
    canvas.getByRole('status', { name: '订单操作结果' }),
  ).toHaveTextContent('已创建订单 ORD-202609-1019');
};

export const playQueryFailure: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  await canvas.findByText('订单服务暂时不可用，请重试查询。');
  await expect(canvas.getByRole('alert').closest('table')).toBe(
    canvas.getByRole('table'),
  );
  await expect(canvas.queryByRole('img', { name: '暂无记录' })).toBeNull();
  await expect(canvas.queryByText('本页 0 条记录')).toBeNull();
  await expect(
    canvas.queryByRole('navigation', { name: '记录分页' }),
  ).toBeNull();
  await userEvent.clear(canvas.getByLabelText('订单金额值'));
  await userEvent.type(canvas.getByLabelText('订单金额值'), '1000');
  await userEvent.click(canvas.getByRole('button', { name: '重试查询' }));
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  await expect(canvas.getByLabelText('订单金额值')).toHaveValue('1000');
  await expect(canvas.getByText('筛选未生效')).toBeInTheDocument();
  await expect(canvas.getByTestId('record-query')).toHaveTextContent(
    '"value": 0',
  );
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await canvas.findByText('共 12 条记录');
  await expect(
    canvas.queryByText('订单服务暂时不可用，请重试查询。'),
  ).not.toBeInTheDocument();
  await expect(canvas.getByTestId('record-query')).toHaveTextContent('1000');
};

export const playLocalDefinitions: RecordViewPlay = async ({
  canvasElement,
}) => {
  const canvas = within(canvasElement);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  await userEvent.click(
    canvas.getByRole('button', { name: '查看订单 ORD-202609-1001' }),
  );
  await expect(
    canvas.getByRole('status', { name: '订单操作结果' }),
  ).toHaveTextContent('青岚科技 · 负责人 林晨 · 上海 · 当前视图：我的订单');
  await userEvent.click(
    canvas.getByRole('checkbox', { name: '选择记录 ORD-202609-1001' }),
  );
  await userEvent.click(canvas.getByRole('button', { name: /批量处理/ }));
  await waitFor(() =>
    expect(
      canvas.getByRole('status', { name: '订单操作结果' }),
    ).toHaveTextContent('已处理 1 笔订单'),
  );
  await expect(
    canvas.getByRole('row', { name: /ORD-202609-1001/ }),
  ).toHaveTextContent('处理中');
  await expect(
    canvas.getByRole('checkbox', { name: '选择记录 ORD-202609-1001' }),
  ).not.toBeChecked();
  await userEvent.click(canvas.getByRole('button', { name: '创建订单' }));
  await canvas.findByText('共 19 条记录');
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^3$/,
  );
};
