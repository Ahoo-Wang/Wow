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
import { asc, filter } from '@ahoo-wang/fetcher-wow';
import type { OrderExample } from '../../packages/view-engine/examples/react/OrderExample.js';
import { createOrderService } from '../../packages/view-engine/examples/react/orderService.js';

type Play = NonNullable<StoryObj<typeof OrderExample>['play']>;

export const playExtensions: Play = async ({ canvasElement, args }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /DEMO-1/ });
  await expect(canvas.getByLabelText('金额 120.00 元')).toHaveTextContent(
    '120.00',
  );
  await expect(
    within(canvas.getByRole('group', { name: '全局工具栏' })).getByRole(
      'button',
      { name: '创建订单' },
    ),
  ).toBeEnabled();
  await expect(
    within(canvas.getByRole('group', { name: '表格工具栏' })).getByRole(
      'button',
      { name: '批量处理' },
    ),
  ).toBeDisabled();
  await userEvent.click(canvas.getByRole('button', { name: '创建订单' }));
  await expect(canvas.getByRole('button', { name: '创建订单' })).toBeDisabled();
  await canvas.findByRole('row', { name: /DEMO-4/ });
  await waitFor(() =>
    expect(
      canvas.getByRole('status', { name: '订单操作状态' }),
    ).toHaveTextContent('已创建订单 DEMO-4'),
  );
  let process = canvas.queryByRole('button', { name: '处理订单 DEMO-1' });
  if (!process) {
    await userEvent.click(
      canvas.getByRole('button', { name: '记录 DEMO-1 处理' }),
    );
    process = await page.findByRole('button', { name: '处理订单 DEMO-1' });
  }
  await userEvent.click(process);
  await waitFor(() =>
    expect(
      canvas.queryByRole('row', { name: /DEMO-1/ }),
    ).not.toBeInTheDocument(),
  );
  await waitFor(() =>
    expect(
      canvas.getByRole('status', { name: '订单操作状态' }),
    ).toHaveTextContent('已处理订单 DEMO-1'),
  );
  await userEvent.click(
    canvas.getByRole('checkbox', { name: '选择记录 DEMO-2' }),
  );
  await userEvent.click(
    canvas.getByRole('checkbox', { name: '选择记录 DEMO-4' }),
  );
  await userEvent.click(canvas.getByRole('button', { name: '批量处理' }));
  await canvas.findByRole('img', { name: '暂无记录' });
  await expect(
    canvas.getByRole('status', { name: '订单操作状态' }),
  ).toHaveTextContent('已处理 2 笔订单');
  await userEvent.click(canvas.getByRole('combobox', { name: '订单状态' }));
  await userEvent.click(await page.findByRole('option', { name: '已处理' }));
  await expect(
    canvas.getByRole('img', { name: '暂无记录' }),
  ).toBeInTheDocument();
  await expect(canvas.getByText('筛选未生效')).toBeInTheDocument();
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await canvas.findByRole('row', { name: /DEMO-1/ });
  await canvas.findByRole('row', { name: /DEMO-4/ });
  await expect(canvas.getByText('共 4 条记录')).toBeInTheDocument();
  await expect(args.onEvent).toHaveBeenCalledWith({
    type: 'created',
    id: 'DEMO-4',
  });
  await expect(args.onEvent).toHaveBeenCalledWith({
    type: 'processed',
    ids: ['DEMO-1'],
  });
  await expect(args.onEvent).toHaveBeenCalledWith({
    type: 'processed',
    ids: ['DEMO-2', 'DEMO-4'],
  });
  await expect(args.onEvent).toHaveBeenLastCalledWith({
    type: 'query',
    filter: filter.eq('status', 'processed'),
  });

  // An unsupported request must fail even when the example's UI never advertises it.
  const service = createOrderService();
  await expect(
    service.source.paged({ filter: filter.ne('status', 'pending') }),
  ).rejects.toThrow('仅支持');
  await expect(
    service.source.paged({ filter: filter.matchAll(), sort: [asc('amount')] }),
  ).rejects.toThrow('排序');
  expect(service.source.cursor).toBeUndefined();
  const snapshot = await service.source.paged({ filter: filter.matchAll() });
  snapshot.list[0].amount = 999;
  await expect(
    (await service.source.paged({ filter: filter.matchAll() })).list[0].amount,
  ).toBe(120);
};

export const playFailureAndScope: Play = async ({ canvasElement, args }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await userEvent.click(
    await canvas.findByRole('button', { name: '重试查询' }),
  );
  await canvas.findByRole('row', { name: /DEMO-1/ });
  await userEvent.click(canvas.getByRole('button', { name: '创建订单' }));
  await userEvent.click(
    await canvas.findByRole('button', { name: '重试订单操作' }),
  );
  await expect(canvas.getByRole('button', { name: '创建订单' })).toBeDisabled();
  await userEvent.click(canvas.getByRole('combobox', { name: '选择视图实例' }));
  await userEvent.click(await page.findByRole('option', { name: /全部订单/ }));
  await waitFor(() =>
    expect(
      canvas.getByRole('combobox', { name: '选择视图实例' }),
    ).toHaveTextContent('全部订单'),
  );
  await waitFor(() =>
    expect(
      canvas.getByRole('status', { name: '订单操作状态' }),
    ).toHaveTextContent('已创建订单 DEMO-4'),
  );
  await expect(args.onEvent).toHaveBeenLastCalledWith({
    type: 'query',
    filter: filter.eq('status', 'pending'),
  });
  await expect(
    canvas.getByRole('combobox', { name: '选择视图实例' }),
  ).toHaveTextContent('全部订单');
  await expect(
    canvas.queryByRole('row', { name: /DEMO-4/ }),
  ).not.toBeInTheDocument();
  await userEvent.click(canvas.getByRole('button', { name: '刷新' }));
  await canvas.findByRole('row', { name: /DEMO-4/ });
  await expect(args.onEvent).toHaveBeenLastCalledWith({
    type: 'query',
    filter: filter.matchAll(),
  });
};

export const playRefreshRecovery: Play = async ({ canvasElement, args }) => {
  const canvas = within(canvasElement);
  await canvas.findByRole('row', { name: /DEMO-1/ });
  await userEvent.click(canvas.getByRole('button', { name: '创建订单' }));
  await userEvent.click(
    await canvas.findByRole('button', { name: '重试订单操作' }),
  );
  await canvas.findByRole('row', { name: /DEMO-4/ });
  await expect(args.onEvent).toHaveBeenCalledTimes(4);
  await expect(args.onEvent).not.toHaveBeenCalledWith({
    type: 'created',
    id: 'DEMO-5',
  });
  await expect(canvas.getByText('共 3 条记录')).toBeInTheDocument();
  await waitFor(() =>
    expect(canvas.getByRole('button', { name: '创建订单' })).toBeEnabled(),
  );
  await expect(
    canvas.queryByRole('button', { name: '重试订单操作' }),
  ).not.toBeInTheDocument();
};

export const playNarrowDark: Play = async ({ canvasElement, args }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /DEMO-1/ });
  const host = canvas.getByTestId('library-delivery-container');
  const table = canvas.getByRole('table');
  const scroller = table.parentElement!;
  await expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
  await expect(scroller.scrollWidth).toBeGreaterThan(scroller.clientWidth);
  const status = canvas.getByRole('combobox', { name: '订单状态' });
  await userEvent.click(status);
  await userEvent.click(await page.findByRole('option', { name: '已处理' }));
  await userEvent.click(canvas.getByRole('button', { name: '展开视图' }));
  await expect(canvas.getByRole('combobox', { name: '订单状态' })).toBe(status);
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  const dialog = await page.findByRole('dialog', { name: '列设置' });
  await expect(getComputedStyle(dialog).colorScheme).toBe('dark');
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(canvas.getByRole('button', { name: '列设置' })).toHaveFocus(),
  );
  await expect(
    canvas.getByRole('button', { name: '收起视图' }),
  ).toBeInTheDocument();
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(canvas.getByRole('button', { name: '展开视图' })).toHaveFocus(),
  );
  await expect(
    canvas.getByRole('combobox', { name: '订单状态' }),
  ).toHaveTextContent('已处理');
  await expect(args.onEvent).toHaveBeenCalledTimes(1);
};
