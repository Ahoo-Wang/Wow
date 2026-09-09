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

import { expect, fireEvent, userEvent, waitFor, within } from 'storybook/test';
import type { RecordViewPlay } from './demoTypes.js';
import { chooseInstance } from './playHelpers.js';

export const playResponsiveColumns: RecordViewPlay = async ({
  canvasElement,
}) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1015/ });
  // Leave room to exercise growing columns as well as the full order table's horizontal scroll.
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  for (const title of [
    '实付金额',
    '商品明细',
    '创建人',
    '最后操作人',
    '支付时间',
  ])
    await userEvent.click(
      page.getByRole('checkbox', { name: `显示 ${title}` }),
    );
  await userEvent.keyboard('{Escape}');
  await userEvent.click(canvas.getByRole('button', { name: '保存' }));
  await waitFor(() =>
    expect(canvas.getByTestId('record-save-count')).toHaveTextContent(/^1$/),
  );
  await waitFor(() =>
    expect(
      canvas.queryByRole('status', { name: '所有汇总加载中' }),
    ).not.toBeInTheDocument(),
  );
  const summaryCalls = canvas.getByTestId('record-summary-count').textContent!;
  const table = canvas.getByRole('table');
  const scroller = table.parentElement!;
  const row = canvas.getByRole('row', { name: /ORD-202609-1001/ });
  const id = within(row).getByText('ORD-202609-1001').closest('td')!;
  const idHandle = canvas.getByRole('separator', {
    name: '调整订单编号列宽',
  });
  await expect(getComputedStyle(idHandle).borderRightWidth).toBe('0px');
  await expect(getComputedStyle(idHandle.closest('th')!).boxShadow).toBe(
    getComputedStyle(id).boxShadow,
  );
  const startX = idHandle.getBoundingClientRect().right;
  await fireEvent.mouseDown(idHandle, { clientX: startX });
  await fireEvent.mouseMove(canvasElement.ownerDocument, {
    clientX: startX + 24,
  });
  await waitFor(() =>
    expect(getComputedStyle(idHandle).borderRightWidth).toBe('1px'),
  );
  await fireEvent.mouseUp(canvasElement.ownerDocument, { clientX: startX });
  await waitFor(() =>
    expect(getComputedStyle(idHandle).borderRightWidth).toBe('0px'),
  );
  const amount = within(row).getByText('¥680.00').closest('td')!;
  const customer = within(row).getByText('青岚科技').closest('td')!;
  const actions = within(row)
    .getByRole('button', { name: '查看订单 ORD-202609-1001' })
    .closest('td')!;
  const near = (a: number, b: number) =>
    expect(Math.abs(a - b)).toBeLessThan(2);
  await waitFor(() => {
    near(table.getBoundingClientRect().width, scroller.clientWidth);
    expect(customer.getBoundingClientRect().width).toBeGreaterThan(180);
    near(id.getBoundingClientRect().width, 210);
    near(amount.getBoundingClientRect().width, 150);
    near(actions.getBoundingClientRect().width, 110);
    near(
      actions.getBoundingClientRect().right,
      scroller.getBoundingClientRect().right,
    );
  });
  await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
  await userEvent.click(canvas.getByRole('button', { name: '切换窄容器' }));
  await waitFor(() => {
    near(customer.getBoundingClientRect().width, 180);
    expect(scroller.scrollWidth).toBeGreaterThan(scroller.clientWidth);
  });
  await userEvent.click(canvas.getByRole('button', { name: '展开工作区' }));
  await waitFor(() =>
    expect(customer.getBoundingClientRect().width).toBeGreaterThan(180),
  );
  const previousWidth = customer.getBoundingClientRect().width;
  const handle = canvas.getByRole('separator', { name: '调整客户列宽' });
  await expect(getComputedStyle(handle).borderRightWidth).toBe('1px');
  handle.focus();
  await userEvent.keyboard('{ArrowLeft}');
  await waitFor(() =>
    near(customer.getBoundingClientRect().width, previousWidth - 10),
  );
  await waitFor(() =>
    expect(canvas.getByRole('button', { name: '保存' })).toBeEnabled(),
  );
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    summaryCalls,
  );
  await userEvent.click(canvas.getByRole('button', { name: '视图选项' }));
  await userEvent.click(await page.findByRole('menuitem', { name: '还原' }));
  await waitFor(() =>
    expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled(),
  );
};

export const playNarrowRecords: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const document = canvasElement.ownerDocument;
  const page = within(document.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  const firstRow = canvas.getByRole('row', { name: /ORD-202609-1001/ });
  const keyCell = within(firstRow)
    .getByLabelText('ORD-202609-1001')
    .closest('td')!;
  const actionTrigger = within(firstRow).getByRole('button', {
    name: '记录 ORD-202609-1001 操作',
  });
  await waitFor(() =>
    expect(
      actionTrigger.closest('td')!.getBoundingClientRect().left -
        keyCell.getBoundingClientRect().right,
    ).toBeGreaterThanOrEqual(127),
  );
  await userEvent.click(actionTrigger);
  const actions = await page.findByRole('dialog', {
    name: '记录 ORD-202609-1001 操作',
  });
  await userEvent.click(
    within(actions).getByRole('button', { name: '查看订单 ORD-202609-1001' }),
  );
  await expect(
    canvas.getByRole('status', { name: '订单操作结果' }),
  ).toHaveTextContent('青岚科技');
  await userEvent.keyboard('{Escape}');
  await waitFor(() => expect(page.queryByRole('dialog')).toBeNull());
  await waitFor(() => expect(actionTrigger).toHaveFocus());
  await expect(
    canvas.queryByRole('complementary', { name: '视图列表' }),
  ).not.toBeInTheDocument();
  await expect(
    canvas.getByRole('combobox', { name: '选择视图实例' }),
  ).toBeVisible();
  await chooseInstance(canvasElement, '全部订单 系统');
  await expect(
    canvas.getByRole('combobox', { name: '选择视图实例' }),
  ).toHaveTextContent('全部订单');

  // A modified read-only instance offers Save As as the primary button and Restore in the menu.
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  await userEvent.click(
    await page.findByRole('checkbox', { name: /^显示\s*客户$/ }),
  );
  await userEvent.keyboard('{Escape}');
  await expect(canvas.getByRole('button', { name: '视图选项' })).toBeVisible();

  const host = canvas.getByTestId('narrow-record-host');
  const scroller = canvas.getByRole('table').parentElement!;
  await expect(host.getBoundingClientRect().width).toBeLessThanOrEqual(414);
  await expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth);
  await expect(scroller.scrollWidth).toBeGreaterThan(scroller.clientWidth);
  await expect(getComputedStyle(scroller).overflowX).toBe('auto');
  scroller.scrollLeft = scroller.scrollWidth;
  await expect(scroller.scrollLeft).toBeGreaterThan(0);
  scroller.scrollLeft = 0;
  await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
    document.documentElement.clientWidth,
  );

  for (const [trigger, title] of [
    ['列设置', '列设置'],
    ['另存为', '另存为视图'],
  ]) {
    await userEvent.click(canvas.getByRole('button', { name: trigger }));
    const dialog = await page.findByRole('dialog', { name: title });
    if (title === '另存为视图') {
      await waitFor(() => {
        const rect = dialog.getBoundingClientRect();
        expect(
          Math.abs(
            rect.left +
              rect.width / 2 -
              document.documentElement.clientWidth / 2,
          ),
        ).toBeLessThan(2);
        expect(
          Math.abs(
            rect.top +
              rect.height / 2 -
              document.documentElement.clientHeight / 2,
          ),
        ).toBeLessThan(2);
        expect(getComputedStyle(dialog).boxSizing).toBe('border-box');
      });
    }
    const bounds = dialog.getBoundingClientRect();
    await expect(bounds.left).toBeGreaterThanOrEqual(0);
    await expect(bounds.right).toBeLessThanOrEqual(
      document.documentElement.clientWidth,
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        page.queryByRole('dialog', { name: title }),
      ).not.toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: trigger })).toHaveFocus(),
    );
  }
};
