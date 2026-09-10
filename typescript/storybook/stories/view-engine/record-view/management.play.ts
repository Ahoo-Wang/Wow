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
import { ViewEngine } from '@ahoo-wang/fetcher-view-engine';
import { createHost } from './createHost.js';
import { definition } from './fixtures.js';
import type { RecordViewPlay } from './demoTypes.js';

export const playManageViews: RecordViewPlay = async ({ canvasElement }) => {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('row', { name: /ORD-202609-1001/ });
  const amount = canvas.getByRole('textbox', { name: '订单金额值' });
  await userEvent.clear(amount);
  await userEvent.type(amount, '2000');
  await userEvent.click(canvas.getByRole('button', { name: '视图选项' }));
  await expect(
    page.queryByRole('menuitem', { name: '删除视图' }),
  ).not.toBeInTheDocument();
  await userEvent.keyboard('{Escape}');
  await expect(
    canvas.queryByRole('button', { name: '管理视图' }),
  ).not.toBeInTheDocument();
  const chooser = canvas.getByRole('combobox', { name: '选择视图实例' });
  await userEvent.click(chooser);
  const manage = await page.findByRole('button', { name: '管理视图' });
  await expect(manage.closest('[role="listbox"]')).toBeNull();
  page.getByRole('option', { name: '我的订单' }).focus();
  await userEvent.keyboard('{Tab}');
  await expect(manage).toHaveFocus();
  await userEvent.keyboard('{Enter}');
  const manager = within(await page.findByRole('dialog', { name: '管理视图' }));
  await expect(page.queryByRole('listbox')).not.toBeInTheDocument();
  await expect(chooser).toHaveTextContent('我的订单');
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(
    manager.queryByRole('textbox', { name: '全部订单名称' }),
  ).not.toBeInTheDocument();
  await expect(
    manager.queryByRole('button', { name: '删除全部订单' }),
  ).not.toBeInTheDocument();
  const defaultButton = manager.getByRole('button', {
    name: '将全部订单设为默认视图',
  });
  await userEvent.click(defaultButton);
  await waitFor(() =>
    expect(defaultButton).toHaveAccessibleName('取消全部订单的默认视图'),
  );
  await expect(manager.getAllByText('默认')).toHaveLength(1);
  await expect(
    within(manager.getByRole('listitem', { name: '全部订单' })).getByText(
      '默认',
    ),
  ).toBeInTheDocument();
  await expect(defaultButton).toHaveFocus();
  await expect(chooser).toHaveTextContent('我的订单');
  await expect(amount).toHaveValue('2000');
  await userEvent.keyboard('{Enter}');
  await waitFor(() =>
    expect(manager.queryByText('默认')).not.toBeInTheDocument(),
  );
  await expect(defaultButton).toHaveFocus();
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(manager.queryByRole('textbox')).not.toBeInTheDocument();
  await expect(
    manager.queryByRole('button', { name: '编辑全部订单名称' }),
  ).not.toBeInTheDocument();
  await userEvent.click(
    manager.getByRole('button', { name: '编辑我的订单名称' }),
  );
  const name = manager.getByRole('textbox', { name: '我的订单名称' });
  await waitFor(() => expect(name).toHaveFocus());
  await userEvent.clear(name);
  await userEvent.type(name, '我的工作台');
  await userEvent.click(
    manager.getByRole('button', { name: '保存我的订单名称' }),
  );
  const renamed = await manager.findByRole('button', {
    name: '编辑我的工作台名称',
  });
  await expect(manager.queryByRole('textbox')).not.toBeInTheDocument();
  await waitFor(() => expect(renamed).toHaveFocus());
  await expect(canvas.getByTestId('record-rename-count')).toHaveTextContent(
    /^1$/,
  );
  await expect(canvas.getByTestId('record-save-count')).toHaveTextContent(
    /^0$/,
  );
  await expect(amount).toHaveValue('2000');
  const publicList = manager.getByRole('list', { name: '公共视图顺序' });
  const labels = () =>
    within(publicList)
      .getAllByRole('listitem')
      .map(item => item.getAttribute('aria-label'));
  const handle = manager.getByRole('button', {
    name: '拖动调整全部订单顺序',
  });
  handle.focus();
  await userEvent.keyboard('{ArrowDown}');
  await waitFor(() => expect(labels()).toEqual(['团队重点订单', '全部订单']));
  await waitFor(() => expect(handle).toHaveFocus());
  const personalList = manager.getByRole('list', { name: '个人视图顺序' });
  const dataTransfer = new DataTransfer();
  await fireEvent.dragStart(handle, { dataTransfer });
  await fireEvent.dragOver(personalList, {
    dataTransfer,
    clientY: personalList.getBoundingClientRect().top,
  });
  await expect(
    personalList.querySelector('[data-slot="view-drop-indicator"]'),
  ).not.toBeInTheDocument();
  await fireEvent.drop(personalList, {
    dataTransfer,
    clientY: personalList.getBoundingClientRect().top,
  });
  await fireEvent.dragEnd(handle, { dataTransfer });
  await expect(canvas.getByTestId('record-order-count')).toHaveTextContent(
    /^1$/,
  );
  await fireEvent.dragStart(handle, { dataTransfer });
  const top = publicList.getBoundingClientRect().top;
  await fireEvent.dragOver(publicList, { dataTransfer, clientY: top });
  await expect(
    publicList.querySelector('[data-slot="view-drop-indicator"]'),
  ).toBeInTheDocument();
  await fireEvent.drop(publicList, { dataTransfer, clientY: top });
  await fireEvent.dragEnd(handle, { dataTransfer });
  await waitFor(() => expect(labels()).toEqual(['全部订单', '团队重点订单']));
  await expect(canvas.getByTestId('record-order-count')).toHaveTextContent(
    /^2$/,
  );
  await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
    /^1$/,
  );
  async function openDelete(title: string) {
    await userEvent.click(
      manager.getByRole('button', { name: `删除${title}` }),
    );
    return within(await page.findByRole('dialog', { name: '删除视图' }));
  }
  let confirm = await openDelete('我的工作台');
  await expect(confirm.getByText(/我的工作台/)).toHaveTextContent(
    '待查询的修改也会丢弃',
  );
  await waitFor(() =>
    expect(confirm.getByRole('button', { name: '取消' })).toHaveFocus(),
  );
  await userEvent.click(confirm.getByRole('button', { name: '取消' }));
  await expect(canvas.getByTestId('record-delete-count')).toHaveTextContent(
    /^0$/,
  );
  confirm = await openDelete('我的工作台');
  await userEvent.click(confirm.getByRole('button', { name: '删除视图' }));
  await expect(await confirm.findByRole('alert')).toHaveTextContent('删除失败');
  await expect(amount).toHaveValue('2000');
  await userEvent.click(confirm.getByRole('button', { name: '删除视图' }));
  await waitFor(() =>
    expect(
      page.queryByRole('dialog', { name: '删除视图' }),
    ).not.toBeInTheDocument(),
  );
  await expect(
    page.getByRole('dialog', { name: '管理视图' }),
  ).toBeInTheDocument();
  await expect(
    manager.queryByRole('textbox', { name: '我的工作台名称' }),
  ).not.toBeInTheDocument();
  await waitFor(() =>
    expect(manager.getByRole('heading', { name: '管理视图' })).toHaveFocus(),
  );
  confirm = await openDelete('团队重点订单');
  await expect(confirm.getByText(/其他使用者/)).toBeInTheDocument();
  await userEvent.click(confirm.getByRole('button', { name: '删除视图' }));
  await waitFor(() =>
    expect(
      page.queryByRole('dialog', { name: '删除视图' }),
    ).not.toBeInTheDocument(),
  );
  await expect(canvas.getByTestId('record-delete-count')).toHaveTextContent(
    /^2$/,
  );
  await expect(manager.queryByRole('textbox')).not.toBeInTheDocument();
  await userEvent.click(manager.getByRole('button', { name: '完成' }));
  await canvas.findByText('共 18 条记录');
  await waitFor(() =>
    expect(
      canvas.getByRole('combobox', { name: '选择视图实例' }),
    ).toHaveFocus(),
  );
  await expect(
    canvas.queryByRole('button', { name: '管理视图' }),
  ).not.toBeInTheDocument();
  await expect(
    canvas.queryByRole('button', { name: '视图选项' }),
  ).not.toBeInTheDocument();

  // The same fake service must preserve a deletion fallback through later sorting and engine reload.
  const { host, initialInstances } = createHost(
    {},
    () => {},
    () => {},
    () => {},
    () => {},
  );
  const engine = new ViewEngine({ definitionId: definition.id, host });
  const [personal, system, shared] = initialInstances.instances;
  try {
    await engine.load();
    await engine.setDefaultInstance(personal.id);
    await engine.deleteInstance(personal.id);
    await expect(engine.getSnapshot().defaultInstanceId).toBe(system.id);
    await engine.reorderInstances([shared.id, system.id]);
    await engine.load();
    await expect(engine.getSnapshot()).toMatchObject({
      defaultInstanceId: system.id,
      selectedInstanceId: system.id,
    });
  } finally {
    engine.dispose();
  }
};
