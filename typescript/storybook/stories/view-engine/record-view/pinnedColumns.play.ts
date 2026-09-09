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
import type { ViewInstance } from '@ahoo-wang/fetcher-view-engine';

export const playPinnedColumns: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  const row = await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  const table = canvas.getByRole('table');
  const scroller = table.parentElement!;
  const actionCell = within(row)
    .getByRole('button', { name: '查看订单 ORD-202609-1001' })
    .closest('td')!;
  const actionHeader = canvas
    .getByRole('separator', { name: '调整操作列宽' })
    .closest('th')!;
  const idCell = within(row).getByText('ORD-202609-1001').closest('td')!;
  const idHeader = canvas
    .getByRole('separator', { name: '调整订单编号列宽' })
    .closest('th')!;
  const rightEdge = () => scroller.getBoundingClientRect().right;
  const near = (actual: number, expected: number) =>
    expect(Math.abs(actual - expected)).toBeLessThan(2);
  await expect(scroller.scrollWidth).toBeGreaterThan(scroller.clientWidth);
  for (const scrollLeft of [0, 120, scroller.scrollWidth]) {
    scroller.scrollLeft = scrollLeft;
    await waitFor(() => {
      near(actionCell.getBoundingClientRect().right, rightEdge());
      near(actionHeader.getBoundingClientRect().right, rightEdge());
      near(
        idCell.getBoundingClientRect().left,
        scroller.getBoundingClientRect().left + 48,
      );
      near(
        idHeader.getBoundingClientRect().left,
        idCell.getBoundingClientRect().left,
      );
    });
  }
  await userEvent.click(within(row).getByRole('checkbox'));
  await waitFor(() =>
    expect(getComputedStyle(actionCell).backgroundColor).toBe(
      getComputedStyle(row).backgroundColor,
    ),
  );
  await userEvent.click(within(row).getByRole('checkbox'));

  async function togglePin(title: string, pressed: boolean) {
    await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
    const pin = await page.findByRole('button', { name: `固定${title}` });
    pin.focus();
    await userEvent.keyboard(' ');
    await expect(pin).toHaveAttribute('aria-pressed', String(pressed));
    await expect(pin).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        page.queryByRole('dialog', { name: '列设置' }),
      ).not.toBeInTheDocument(),
    );
  }
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  await expect(
    page.queryByRole('combobox', { name: /固定位置/ }),
  ).not.toBeInTheDocument();
  for (const title of ['订单编号', '操作']) {
    await expect(
      page.getByRole('button', { name: `固定${title}` }),
    ).toBeDisabled();
    await expect(
      page.getByRole('button', { name: `固定${title}` }),
    ).toHaveAttribute('aria-pressed', 'true');
    await expect(
      page.getByRole('button', { name: `拖动调整${title}顺序` }),
    ).toBeDisabled();
  }
  await expect(page.queryByRole('spinbutton')).not.toBeInTheDocument();
  await expect(
    page.getByRole('button', { name: '固定订单金额' }),
  ).toBeDisabled();
  await expect(page.getByRole('button', { name: '固定客户' })).toBeEnabled();
  await expect(
    page.getByRole('button', { name: '固定支付时间' }),
  ).toBeEnabled();
  const summaryControl = page.getByRole('combobox', {
    name: '订单金额汇总方式',
  });
  near(
    summaryControl.getBoundingClientRect().top +
      summaryControl.getBoundingClientRect().height / 2,
    page.getByRole('button', { name: '固定订单金额' }).getBoundingClientRect()
      .top +
      page.getByRole('button', { name: '固定订单金额' }).getBoundingClientRect()
        .height /
        2,
  );
  const customerHandle = page.getByRole('button', {
    name: '拖动调整客户顺序',
  });
  const amountItem = page
    .getByRole('checkbox', { name: /显示\s*订单金额/ })
    .closest('li')!;
  const columnList = amountItem.parentElement!;
  const dropY =
    (amountItem.getBoundingClientRect().bottom +
      amountItem.nextElementSibling!.getBoundingClientRect().top) /
    2;
  const dataTransfer = new DataTransfer();
  await fireEvent.dragStart(customerHandle, { dataTransfer });
  await fireEvent.dragOver(columnList, { dataTransfer, clientY: dropY });
  const dropIndicator = columnList.querySelector(
    '[data-slot="column-drop-indicator"]',
  )!;
  await expect(dropIndicator).toBeInTheDocument();
  near(dropIndicator.getBoundingClientRect().top, dropY);
  await expect(
    canvas.queryByText('已编辑', { exact: true }),
  ).not.toBeInTheDocument();
  await fireEvent.drop(columnList, { dataTransfer, clientY: dropY });
  await fireEvent.dragEnd(customerHandle, { dataTransfer });
  await expect(canvas.getByRole('button', { name: '保存' })).toBeEnabled();
  await expect(
    canvas
      .getAllByRole('columnheader')
      .slice(1, 4)
      .map(header => header.textContent),
  ).toEqual(['订单编号', '订单金额', '客户']);
  customerHandle.focus();
  await userEvent.keyboard('{ArrowDown}');
  await expect(customerHandle).toHaveFocus();
  await expect(
    canvas
      .getAllByRole('columnheader')
      .slice(1, 5)
      .map(header => header.textContent),
  ).toEqual(['订单编号', '订单金额', '订单状态', '客户']);
  await expect(
    page.queryByRole('button', { name: /上移|下移/ }),
  ).not.toBeInTheDocument();
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(
      page.queryByRole('dialog', { name: '列设置' }),
    ).not.toBeInTheDocument(),
  );
  await togglePin('订单金额', true);
  const amountCell = within(row).getByText('¥680.00').closest('td')!;
  const idSummary = within(
    canvas.getByRole('row', { name: '本页汇总' }),
  ).getByRole('rowheader', { name: '本页' });
  const amountSummary = within(canvas.getByRole('row', { name: '本页汇总' }))
    .getByRole('group', { name: '订单金额合计' })
    .closest('td')!;
  scroller.scrollLeft = 80;
  await waitFor(() => {
    near(
      idCell.getBoundingClientRect().left,
      scroller.getBoundingClientRect().left + 48,
    );
    near(
      idSummary.getBoundingClientRect().left,
      scroller.getBoundingClientRect().left,
    );
    near(
      amountCell.getBoundingClientRect().left,
      idCell.getBoundingClientRect().right,
    );
    near(
      amountSummary.getBoundingClientRect().left,
      amountCell.getBoundingClientRect().left,
    );
  });
  const handle = canvas.getByRole('separator', { name: '调整订单编号列宽' });
  handle.focus();
  await userEvent.keyboard('{ArrowRight}');
  await waitFor(() => {
    near(idCell.getBoundingClientRect().width, 220);
    near(idSummary.getBoundingClientRect().width, 220 + 48);
  });
  await togglePin('订单金额', false);
  await expect(getComputedStyle(amountCell).position).not.toBe('sticky');
  await expect(getComputedStyle(idCell).position).toBe('sticky');
  await expect(getComputedStyle(actionCell).position).toBe('sticky');
  await userEvent.click(canvas.getByRole('button', { name: '保存' }));
  await waitFor(() =>
    expect(canvas.getByTestId('record-write')).toHaveTextContent(
      '"pinned": false',
    ),
  );
  const saved = JSON.parse(
    canvas.getByTestId('record-write').textContent!,
  ) as ViewInstance;
  await expect(
    saved.config.presentation.table.columns.map(column => column.id),
  ).toEqual([
    'aggregateId',
    'totalAmount',
    'status',
    'customer',
    'paidAmount',
    'items',
    'firstOperator',
    'operator',
    'firstEventTime',
    'paidAt',
    'actions',
  ]);
  await togglePin('订单金额', true);
  await userEvent.click(canvas.getByRole('button', { name: '保存' }));
  await waitFor(() => {
    expect(canvas.getByTestId('record-write')).toHaveTextContent(
      '"pinned": "left"',
    );
  });
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    /^1$/,
  );
  await userEvent.click(
    within(row).getByRole('button', { name: '查看订单 ORD-202609-1001' }),
  );
  await expect(
    canvas.getByRole('status', { name: '订单操作结果' }),
  ).toHaveTextContent('青岚科技 · 负责人 林晨 · 上海 · 当前视图：我的订单');
};
