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
import { chooseInstance } from './playHelpers.js';

export const playBusinessRecords: RecordViewPlay = async ({
  canvasElement,
}) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  const sidebar = within(
    canvas.getByRole('complementary', { name: '视图列表', hidden: true }),
  );
  await expect(
    sidebar
      .getAllByRole('heading', { hidden: true })
      .map(item => item.textContent),
  ).toEqual(['个人视图', '公共视图']);
  const systemView = sidebar.getByRole('button', {
    name: '全部订单 系统',
    hidden: true,
  });
  await expect(within(systemView).getByText('系统')).toBeInTheDocument();
  await expect(systemView.parentElement).toContainElement(
    sidebar.getByRole('button', { name: '团队重点订单', hidden: true }),
  );
  await expect(canvas.getByText('共 18 条记录')).toBeInTheDocument();
  await expect(
    within(canvas.getByRole('table')).getAllByRole('row'),
  ).toHaveLength(6);
  const amount = canvas.getByLabelText('订单金额值');
  await userEvent.clear(amount);
  await userEvent.type(amount, '1000');
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
  await expect(canvas.getByText('筛选未生效')).toBeInTheDocument();
  await userEvent.click(canvas.getByRole('button', { name: '查询' }));
  await canvas.findByRole('row', { name: /ORD-202609-1002/ });
  await expect(canvas.getByText('共 12 条记录')).toBeInTheDocument();
  await expect(canvas.getByTestId('record-query')).toHaveTextContent('1000');

  await userEvent.click(
    canvas.getByRole('checkbox', { name: '选择记录 ORD-202609-1002' }),
  );
  await expect(canvas.getByRole('button', { name: /批量处理/ })).toBeEnabled();
  await userEvent.click(canvas.getByRole('button', { name: /订单金额排序/ }));
  await waitFor(() =>
    expect(
      within(canvas.getByRole('table')).getAllByRole('row')[1],
    ).toHaveTextContent('ORD-202609-1009'),
  );
  await expect(canvas.getByRole('button', { name: /批量处理/ })).toBeDisabled();
  await expect(canvas.getByTestId('record-query')).toHaveTextContent(
    '"field": "state.totalAmount"',
  );
  await userEvent.click(
    canvas.getByRole('checkbox', { name: '选择记录 ORD-202609-1009' }),
  );
  await userEvent.click(canvas.getByRole('button', { name: '下一页' }));
  await canvas.findByRole('row', { name: /ORD-202609-1015/ });
  await expect(canvas.getByText('第 2 / 3 页')).toBeInTheDocument();
  await expect(canvas.getByRole('button', { name: /批量处理/ })).toBeDisabled();
  await userEvent.click(canvas.getByRole('combobox', { name: '每页记录数' }));
  await userEvent.click(await page.findByRole('option', { name: '10 条' }));
  await waitFor(() =>
    expect(within(canvas.getByRole('table')).getAllByRole('row')).toHaveLength(
      11,
    ),
  );
  await expect(canvas.getByText('第 1 / 2 页')).toBeInTheDocument();

  const callsBeforeColumns =
    canvas.getByTestId('record-query-count').textContent;
  await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
  await userEvent.click(
    await page.findByRole('checkbox', { name: /^显示\s*客户$/ }),
  );
  await userEvent.keyboard('{Escape}');
  await expect(
    canvas.queryByRole('columnheader', { name: /客户/ }),
  ).not.toBeInTheDocument();
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    callsBeforeColumns!,
  );
  await userEvent.click(canvas.getByRole('button', { name: '保存' }));
  await waitFor(() =>
    expect(canvas.getByTestId('record-save-count')).toHaveTextContent(/^1$/),
  );
  await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
  await expect(canvas.getByTestId('record-write')).toHaveTextContent(
    '"revision": "2"',
  );
  await expect(
    canvas.queryByText('已编辑', { exact: true }),
  ).not.toBeInTheDocument();

  await userEvent.click(canvas.getByRole('button', { name: '视图选项' }));
  await userEvent.click(await page.findByRole('menuitem', { name: '另存为' }));
  const saveDialog = await page.findByRole('dialog', { name: '另存为视图' });
  await userEvent.clear(
    within(saveDialog).getByRole('textbox', { name: '视图名称' }),
  );
  await userEvent.type(
    within(saveDialog).getByRole('textbox', { name: '视图名称' }),
    '高额订单 · 共享',
  );
  const visibility = within(saveDialog).getByRole('radiogroup', {
    name: '可见范围',
  });
  await expect(
    within(visibility).getByRole('radio', { name: '个人视图' }),
  ).toBeChecked();
  await expect(
    within(visibility).getByRole('radio', { name: '个人视图' }),
  ).toHaveAccessibleDescription('仅自己可见，适合保存个人常用配置。');
  await expect(
    within(visibility).getByRole('radio', { name: '公共视图' }),
  ).toHaveAccessibleDescription('对有访问权限的用户可见，适合团队共享。');
  await userEvent.click(
    within(visibility).getByText('公共视图', { exact: true }),
  );
  await expect(
    within(visibility).getByRole('radio', { name: '公共视图' }),
  ).toBeChecked();
  await userEvent.click(
    within(saveDialog).getByRole('button', { name: '创建视图' }),
  );
  await waitFor(() =>
    expect(
      page.queryByRole('dialog', { name: '另存为视图' }),
    ).not.toBeInTheDocument(),
  );
  await expect(canvas.getByTestId('record-create-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByTestId('record-write')).toHaveTextContent(
    '高额订单 · 共享',
  );
  await expect(canvas.getByTestId('record-write')).toHaveTextContent(
    '"source": "shared"',
  );

  await chooseInstance(canvasElement, '我的订单');
  await userEvent.clear(canvas.getByLabelText('订单金额值'));
  await userEvent.type(canvas.getByLabelText('订单金额值'), '2500');
  await chooseInstance(canvasElement, '全部订单 系统');
  await expect(
    canvas.queryByRole('button', { name: '保存' }),
  ).not.toBeInTheDocument();
  await chooseInstance(canvasElement, '我的订单 · 待查询');
  await expect(canvas.getByLabelText('订单金额值')).toHaveValue('2500');
  await userEvent.click(canvas.getByRole('button', { name: '视图选项' }));
  await userEvent.click(await page.findByRole('menuitem', { name: '还原' }));
  await expect(canvas.getByLabelText('订单金额值')).toHaveValue('1000');
};

export const playRestoreFocus: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  for (const interaction of ['keyboard', 'pointer']) {
    await userEvent.click(canvas.getByRole('button', { name: '列设置' }));
    await userEvent.click(
      await page.findByRole('checkbox', { name: /^显示\s*客户$/ }),
    );
    await userEvent.keyboard('{Escape}');
    const actions = canvas.getByRole('group', { name: '视图操作' });
    const menu = within(actions).getByRole('button', { name: '视图选项' });
    if (interaction === 'keyboard') {
      menu.focus();
      await userEvent.keyboard('{Enter}');
      await page.findByRole('menuitem', { name: '还原' });
      await userEvent.keyboard('{ArrowDown}{Enter}');
    } else {
      await userEvent.click(menu);
      await userEvent.click(
        await page.findByRole('menuitem', { name: '还原' }),
      );
    }
    await waitFor(() => expect(actions).toHaveFocus());
    await expect(menu).not.toBeInTheDocument();
    await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
    await expect(
      canvas.getByRole('columnheader', { name: /客户/ }),
    ).toBeVisible();
    await expect(
      canvas.queryByText('已编辑', { exact: true }),
    ).not.toBeInTheDocument();
    await userEvent.keyboard('{Tab}');
    await expect(
      canvas.getByRole('button', { name: '展示方式：表格' }),
    ).toHaveFocus();
    await userEvent.keyboard('{Tab}');
    await expect(
      canvas.getByRole('button', { name: '收起筛选' }),
    ).toHaveFocus();
  }
  await expect(canvas.getByTestId('record-save-count')).toHaveTextContent(
    /^0$/,
  );
};
