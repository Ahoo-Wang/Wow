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

export const playRuntimeTools: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  const view = canvasElement.querySelector('[data-slot="view-page"]')!;
  const input = canvas.getByRole('textbox', { name: '订单金额值' });
  const globalToolbar = within(
    canvas.getByRole('group', { name: '全局工具栏' }),
  );
  const tableToolbar = within(
    canvas.getByRole('group', { name: '记录工具栏' }),
  );
  const table = canvas.getByRole('table');
  const query = canvas.getByRole('button', { name: '查询' });
  const tableTop = table.getBoundingClientRect().top;
  const queryTop = query.getBoundingClientRect().top;
  const addFilter = canvas.getByRole('button', { name: '添加筛选' });
  await userEvent.click(addFilter);
  const fieldPicker = await page.findByRole('dialog', {
    name: '选择筛选字段',
  });
  await waitFor(() => expect(fieldPicker).toBeVisible());
  await expect(table.getBoundingClientRect().top).toBe(tableTop);
  await expect(query.getBoundingClientRect().top).toBe(queryTop);
  await expect(fieldPicker.getBoundingClientRect().height).toBeLessThanOrEqual(
    448,
  );
  await expect(fieldPicker.getBoundingClientRect().right).toBeLessThanOrEqual(
    canvasElement.ownerDocument.documentElement.clientWidth,
  );
  await userEvent.click(
    within(fieldPicker).getByRole('button', { name: '完成' }),
  );
  await waitFor(() =>
    expect(page.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  await expect(addFilter).toHaveFocus();
  await expect(table.getBoundingClientRect().top).toBe(tableTop);
  await userEvent.click(addFilter);
  await page.findByRole('dialog', { name: '选择筛选字段' });
  await userEvent.click(input);
  await waitFor(() =>
    expect(page.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  await expect(input).toHaveFocus();
  for (const name of ['刷新', '自动刷新设置', '展开视图']) {
    await expect(globalToolbar.getByRole('button', { name })).toBeVisible();
    await expect(tableToolbar.queryByRole('button', { name })).toBeNull();
  }
  await userEvent.clear(input);
  await userEvent.type(input, '500');
  for (const label of ['30 秒', '1 分钟', '5 分钟']) {
    await userEvent.click(
      globalToolbar.getByRole('button', { name: '自动刷新设置' }),
    );
    await expect(
      (await page.findAllByRole('menuitemradio')).map(item => item.textContent),
    ).toEqual(['关闭自动刷新', '每 30 秒', '每 1 分钟', '每 5 分钟']);
    await userEvent.click(
      await page.findByRole('menuitemradio', { name: `每 ${label}` }),
    );
    await waitFor(() => expect(page.queryByRole('menu')).toBeNull());
    await expect(
      globalToolbar.getByRole('button', { name: '刷新' }),
    ).toHaveTextContent(label);
  }
  await expect(canvas.getByRole('button', { name: '刷新' })).toHaveTextContent(
    '已暂停',
  );
  await userEvent.click(canvas.getByRole('button', { name: '展开视图' }));
  await waitFor(() => {
    expect(view.getBoundingClientRect().left).toBe(0);
    expect(view.getBoundingClientRect().top).toBe(0);
    expect(view.getBoundingClientRect().width).toBe(
      canvasElement.ownerDocument.documentElement.clientWidth,
    );
    expect(view.getBoundingClientRect().height).toBe(
      canvasElement.ownerDocument.documentElement.clientHeight,
    );
  });
  await expect(canvas.getByRole('textbox', { name: '订单金额值' })).toBe(input);
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  const columns = await page.findByRole('dialog', { name: '列设置' });
  await expect(columns.getBoundingClientRect().right).toBeLessThanOrEqual(
    view.getBoundingClientRect().right,
  );
  await userEvent.keyboard('{Escape}');
  await expect(
    canvas.getByRole('button', { name: '收起视图' }),
  ).toBeInTheDocument();
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(
      canvas.getByRole('button', { name: '展开视图' }),
    ).toBeInTheDocument(),
  );
  await expect(input).toHaveValue('500');
  await expect(canvas.getByRole('button', { name: '展开视图' })).toHaveFocus();
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await userEvent.click(canvas.getByRole('button', { name: '自动刷新设置' }));
  await userEvent.click(
    await page.findByRole('menuitemradio', { name: '关闭自动刷新' }),
  );
  await waitFor(() => expect(page.queryByRole('menu')).toBeNull());
  await userEvent.click(canvas.getByRole('button', { name: '撤销筛选修改' }));
  await expect(
    canvas.getByRole('button', { name: '刷新' }),
  ).not.toHaveTextContent('5 分钟');
  await userEvent.click(
    globalToolbar.getByRole('button', { name: '自动刷新设置' }),
  );
  await userEvent.click(
    await page.findByRole('menuitemradio', { name: '每 30 秒' }),
  );
  await waitFor(() => expect(page.queryByRole('menu')).toBeNull());
  const refresh = globalToolbar.getByRole('button', { name: '刷新' });
  await waitFor(() => expect(refresh).toHaveTextContent(/00:2[89]/), {
    timeout: 3000,
  });
  await userEvent.click(canvas.getByRole('textbox', { name: '订单金额值' }));
  await expect(refresh).toHaveTextContent('已暂停');
  await userEvent.tab();
  await expect(refresh).toHaveTextContent('00:30');
  await userEvent.click(
    globalToolbar.getByRole('button', { name: '自动刷新设置' }),
  );
  await userEvent.click(
    await page.findByRole('menuitemradio', { name: '关闭自动刷新' }),
  );
  await waitFor(() => expect(page.queryByRole('menu')).toBeNull());
  await expect(refresh).not.toHaveTextContent(/\d{2}:\d{2}/);
};
