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

export const playSummaries: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  const local = () => canvas.getByRole('row', { name: '本页汇总' });
  const all = () => canvas.getByRole('row', { name: '所有汇总' });
  await expect(local()).toHaveTextContent('8,499');
  await expect(local()).toHaveTextContent('¥3,039.00');
  await waitFor(() => expect(all()).toHaveTextContent('36,456'));
  await expect(all()).toHaveTextContent('¥14,198.50');
  await expect(
    canvas.queryByRole('combobox', { name: '汇总范围' }),
  ).not.toBeInTheDocument();
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    /^1$/,
  );
  await userEvent.click(canvas.getByRole('button', { name: '下一页' }));
  await canvas.findByRole('row', { name: /ORD-202609-1006/ });
  await expect(local()).toHaveTextContent('9,538');
  await expect(all()).toHaveTextContent('36,456');
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    /^1$/,
  );
  await userEvent.clear(canvas.getByRole('textbox', { name: '订单金额值' }));
  await userEvent.type(
    canvas.getByRole('textbox', { name: '订单金额值' }),
    '1000',
  );
  await expect(local()).toHaveTextContent('9,538');
  await expect(all()).toHaveTextContent('36,456');
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await waitFor(() => expect(all()).toHaveTextContent('32,117'));
  await expect(local()).toHaveTextContent('13,958');
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    /^2$/,
  );
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  for (const title of [
    '订单编号',
    '客户',
    '订单状态',
    '创建人',
    '最后操作人',
    '下单时间',
    '支付时间',
  ])
    await expect(
      page.queryByRole('combobox', { name: `${title}汇总方式` }),
    ).not.toBeInTheDocument();
  await userEvent.click(
    await page.findByRole('combobox', { name: '订单金额汇总方式' }),
  );
  await expect(
    page.queryByRole('option', { name: '记录数' }),
  ).not.toBeInTheDocument();
  await userEvent.click(await page.findByRole('option', { name: '平均值' }));
  await expect(page.getByRole('listbox')).toHaveAttribute(
    'aria-multiselectable',
    'true',
  );
  await expect(page.getByRole('option', { name: '合计' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(page.getByRole('option', { name: '平均值' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(
      page.getByRole('combobox', { name: '订单金额汇总方式' }),
    ).toHaveFocus(),
  );
  await userEvent.keyboard('{Escape}');
  await waitFor(() =>
    expect(
      page.queryByRole('dialog', { name: '列设置' }),
    ).not.toBeInTheDocument(),
  );
  await expect(local()).toHaveTextContent('13,958');
  await expect(local()).toHaveTextContent('¥2,791.60');
  await waitFor(() => expect(all()).toHaveTextContent('¥2,676.42'));
  await expect(all()).toHaveTextContent('32,117');
  await expect(canvas.getAllByText('本页', { exact: true })).toHaveLength(1);
  await expect(canvas.getAllByText('所有', { exact: true })).toHaveLength(1);
  const average = within(all()).getByLabelText('所有订单金额平均值：¥2,676.42');
  await expect(getComputedStyle(average).whiteSpace).toBe('nowrap');
  average.focus();
  await expect(await page.findByRole('tooltip')).toHaveTextContent(
    '原值：2676.4166666666665',
  );
  await userEvent.keyboard('{Escape}');
  await waitFor(() => expect(page.queryByRole('tooltip')).toBeNull());
  average.blur();
  await userEvent.hover(average);
  await expect(await page.findByRole('tooltip')).toHaveTextContent(
    '原值：2676.4166666666665',
  );
  await userEvent.unhover(average);
  await waitFor(() => expect(page.queryByRole('tooltip')).toBeNull());
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    /^3$/,
  );
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^3$/,
  );
  await userEvent.click(canvas.getByRole('button', { name: '保存' }));
  await waitFor(() =>
    expect(canvas.getByTestId('record-save-count')).toHaveTextContent(/^1$/),
  );
  await expect(canvas.getByTestId('record-write')).toHaveTextContent(
    /"summary":\s*\[\s*"SUM",\s*"AVG"\s*\]/,
  );
};

export const playLoadingSummaries: RecordViewPlay = async ({
  canvasElement,
}) => {
  const canvas = within(canvasElement);
  await expect(canvas.getAllByRole('status')).toHaveLength(3);
  await expect(
    canvas.getByRole('status', { name: '正在加载记录' }),
  ).toBeVisible();
  for (const label of ['本页', '所有']) {
    const scope = within(canvas.getByRole('row', { name: `${label}汇总` }));
    await expect(
      scope.getByRole('status', { name: `${label}汇总加载中` }),
    ).toBeVisible();
    await expect(scope.getAllByRole('group')).toHaveLength(4);
  }
  await expect(canvas.queryByText(/统计中|正在加载/)).toBeNull();
};

export const playSummaryFailure: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  const firstRow = await canvas.findByRole('row', {
    name: /ORD-202609-1001/,
  });
  const selectionCell = within(firstRow).getByRole('checkbox').closest('td')!;
  const idCell = within(firstRow).getByText('ORD-202609-1001').closest('td')!;
  await waitFor(() => {
    for (const label of ['本页', '所有']) {
      const scope = canvas.getByLabelText(`${label}汇总状态`);
      const area = scope.closest('th')!.getBoundingClientRect();
      const content = scope.getBoundingClientRect();
      expect(
        Math.abs(area.left - selectionCell.getBoundingClientRect().left),
      ).toBeLessThan(2);
      expect(
        Math.abs(area.right - idCell.getBoundingClientRect().right),
      ).toBeLessThan(2);
      expect(
        Math.abs(
          content.left + content.width / 2 - (area.left + area.width / 2),
        ),
      ).toBeLessThan(2);
    }
  });
  const all = canvas.getByRole('row', { name: '所有汇总' });
  const error = await within(all).findByRole('button', {
    name: '所有汇总失败，查看详情',
  });
  await expect(canvas.getAllByRole('alert')).toHaveLength(1);
  await expect(within(all).getByRole('alert')).toHaveTextContent(
    '所有汇总失败',
  );
  for (const label of ['订单金额合计', '实付金额合计'])
    await expect(
      within(within(all).getByRole('group', { name: label })).getByText('—'),
    ).toBeVisible();
  await expect(
    canvas.queryByText('汇总服务暂时不可用，请重试汇总。'),
  ).toBeNull();
  await expect(canvas.getByRole('row', { name: '本页汇总' })).toHaveTextContent(
    '8,499',
  );
  await expect(
    canvas.getByRole('row', { name: /ORD-202609-1001/ }),
  ).toBeInTheDocument();
  await userEvent.click(error);
  await expect(
    await page.findByRole('dialog', { name: '所有汇总失败' }),
  ).toHaveTextContent('汇总服务暂时不可用，请重试汇总。');
  await userEvent.keyboard('{Escape}');
  await waitFor(() => expect(page.queryByRole('dialog')).toBeNull());
  await waitFor(() => expect(error).toHaveFocus());
  await userEvent.click(error);
  const details = await page.findByRole('dialog', { name: '所有汇总失败' });
  await userEvent.click(
    within(details).getByRole('button', { name: '重试汇总' }),
  );
  await waitFor(() => expect(all).toHaveTextContent('36,456'));
  await expect(page.queryByRole('dialog')).toBeNull();
  await expect(canvas.queryByRole('alert')).toBeNull();
  await waitFor(() =>
    expect(canvas.getByLabelText('所有汇总状态')).toHaveFocus(),
  );
  await expect(canvas.getByRole('row', { name: '本页汇总' })).toHaveTextContent(
    '8,499',
  );
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    /^2$/,
  );
};

export const playEmptySummary: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  await expect(
    await canvas.findByRole('img', { name: '暂无记录' }),
  ).toBeVisible();
  await expect(canvas.queryByText('暂无记录')).toBeNull();
  for (const name of ['本页汇总', '所有汇总']) {
    for (const label of ['订单金额合计', '实付金额合计'])
      await waitFor(() =>
        expect(
          within(
            within(canvas.getByRole('row', { name })).getByRole('group', {
              name: label,
            }),
          ).getByText('—', { exact: true }),
        ).toBeInTheDocument(),
      );
  }
  await expect(
    canvas.queryByText('记录数', { exact: true }),
  ).not.toBeInTheDocument();
  await expect(canvas.getByTestId('record-summary-count')).toHaveTextContent(
    /^1$/,
  );
};
