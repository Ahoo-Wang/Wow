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

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewPage } from '../src/record/ViewPage.js';
import { ViewServiceError } from '../src/record/viewServiceContract.js';
import { instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('can reopen and retry the original uncertain deletion without enabling other writes', async () => {
  const { host } = setup();
  host.permission!.getInstance = () => ({
    save: true,
    rename: true,
    delete: true,
  });
  host.instance!.rename = vi.fn(async (id, title) => ({
    ...instance,
    id,
    title,
  }));
  host.instance!.delete = vi
    .fn()
    .mockRejectedValueOnce(
      new ViewServiceError('UNKNOWN_OUTCOME', '删除结果未知'),
    )
    .mockResolvedValue(undefined);
  render(
    <ViewPage scopeKey="delete-recovery" definitionId="orders" host={host} />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(screen.getAllByRole('button', { name: '管理视图' })[0]);
  const manager = within(
    await screen.findByRole('dialog', { name: '管理视图' }),
  );
  fireEvent.click(manager.getByRole('button', { name: '删除我的订单' }));
  let confirmation = within(
    await screen.findByRole('dialog', { name: '删除视图' }),
  );
  fireEvent.click(
    confirmation.getByRole('button', { name: '删除视图', exact: true }),
  );
  await confirmation.findByText('删除结果未知');
  fireEvent.click(confirmation.getByRole('button', { name: '取消' }));
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '删除视图' })).toBeNull(),
  );
  const rename = await manager.findByRole('button', {
    name: '编辑我的订单名称',
  });
  expect(rename.hasAttribute('disabled')).toBe(true);
  const retry = await manager.findByRole('button', { name: '删除我的订单' });
  expect(retry.hasAttribute('disabled')).toBe(false);
  fireEvent.click(retry);
  confirmation = within(
    await screen.findByRole('dialog', { name: '删除视图' }),
  );
  fireEvent.click(
    confirmation.getByRole('button', { name: '删除视图', exact: true }),
  );
  await waitFor(() =>
    expect(manager.queryByRole('button', { name: '删除我的订单' })).toBeNull(),
  );
  expect(host.instance!.delete).toHaveBeenCalledTimes(2);
  expect(vi.mocked(host.instance!.delete!).mock.calls[1]).toEqual(
    vi.mocked(host.instance!.delete!).mock.calls[0],
  );
});

it('manages names and deletion together while protecting system views and pending filters', async () => {
  const { host } = setup();
  host.permission!.getInstance = () => ({
    save: true,
    saveAsPersonal: true,
    saveAsShared: false,
    rename: true,
    delete: true,
  });
  host.instance!.rename = vi.fn(async (id, title) => ({
    ...instance,
    id,
    title,
    revision: 'renamed',
  }));
  host.instance!.delete = vi
    .fn()
    .mockRejectedValueOnce(new ViewServiceError('CONFLICT', '删除失败，请重试'))
    .mockResolvedValue(undefined);
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '99' },
  });
  fireEvent.click(screen.getByRole('button', { name: '视图选项' }));
  expect(
    within(await screen.findByRole('menu')).queryByRole('menuitem', {
      name: '删除视图',
    }),
  ).toBeNull();
  fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
  fireEvent.click(screen.getAllByRole('button', { name: '管理视图' })[0]);
  const manager = within(
    await screen.findByRole('dialog', { name: '管理视图' }),
  );
  expect(manager.queryByRole('textbox', { name: '所有订单名称' })).toBeNull();
  expect(manager.queryByRole('button', { name: '删除所有订单' })).toBeNull();
  expect(manager.queryByRole('textbox')).toBeNull();
  expect(
    manager.queryByRole('button', { name: '编辑所有订单名称' }),
  ).toBeNull();
  fireEvent.click(manager.getByRole('button', { name: '编辑我的订单名称' }));
  const nameInput = manager.getByRole('textbox', { name: '我的订单名称' });
  await waitFor(() => expect(document.activeElement).toBe(nameInput));
  fireEvent.change(nameInput, { target: { value: '取消的名称' } });
  fireEvent.keyDown(nameInput, { key: 'Escape' });
  expect(manager.queryByRole('textbox')).toBeNull();
  expect(host.instance!.rename).not.toHaveBeenCalled();
  await waitFor(() =>
    expect(document.activeElement).toBe(
      manager.getByRole('button', { name: '编辑我的订单名称' }),
    ),
  );

  fireEvent.click(manager.getByRole('button', { name: '编辑我的订单名称' }));
  fireEvent.change(manager.getByRole('textbox', { name: '我的订单名称' }), {
    target: { value: '我的工作台' },
  });
  fireEvent.click(manager.getByRole('button', { name: '保存我的订单名称' }));
  await manager.findByRole('button', { name: '编辑我的工作台名称' });
  expect(manager.queryByRole('textbox')).toBeNull();
  expect(host.instance!.save).not.toHaveBeenCalled();
  expect(
    (
      screen.getByRole('textbox', {
        name: '金额值',
        hidden: true,
      }) as HTMLInputElement
    ).value,
  ).toBe('99');
  fireEvent.click(manager.getByRole('button', { name: '删除我的工作台' }));
  let confirm = within(await screen.findByRole('dialog', { name: '删除视图' }));
  fireEvent.click(confirm.getByRole('button', { name: '取消' }));
  expect(host.instance!.delete).not.toHaveBeenCalled();
  fireEvent.click(manager.getByRole('button', { name: '删除我的工作台' }));
  confirm = within(await screen.findByRole('dialog', { name: '删除视图' }));
  fireEvent.click(confirm.getByRole('button', { name: '删除视图' }));
  await waitFor(() =>
    expect(confirm.getByRole('alert').textContent).toContain('删除失败'),
  );
  fireEvent.click(confirm.getByRole('button', { name: '删除视图' }));
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '删除视图' })).toBeNull(),
  );
  expect(screen.getByRole('dialog', { name: '管理视图' })).toBeTruthy();
  expect(manager.queryByRole('textbox')).toBeNull();
  fireEvent.click(manager.getByRole('button', { name: '完成' }));
  expect(await screen.findByRole('cell', { name: '42' })).toBeTruthy();
});

it('retains a rejected name for retry and discards the editor buffer on closing management', async () => {
  const { host } = setup();
  host.permission!.getInstance = () => ({
    save: true,
    saveAsPersonal: false,
    saveAsShared: false,
    rename: true,
  });
  let rejectRename!: (error: Error) => void;
  host.instance!.rename = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectRename = reject;
        }),
    )
    .mockImplementation(async (id, title) => ({ ...instance, id, title }));
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  const trigger = screen.getAllByRole('button', { name: '管理视图' })[0];
  fireEvent.click(trigger);
  const manager = within(
    await screen.findByRole('dialog', { name: '管理视图' }),
  );
  fireEvent.click(manager.getByRole('button', { name: '编辑我的订单名称' }));
  const input = manager.getByRole('textbox', {
    name: '我的订单名称',
  }) as HTMLInputElement;
  fireEvent.change(input, { target: { value: '待重试名称' } });
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(input.disabled).toBe(true);
  fireEvent.keyDown(screen.getByRole('dialog', { name: '管理视图' }), {
    key: 'Escape',
  });
  expect(screen.getByRole('dialog', { name: '管理视图' })).toBeTruthy();
  await act(async () =>
    rejectRename(new ViewServiceError('CONFLICT', '名称保存失败')),
  );
  expect(manager.getByRole('alert').textContent).toBe('名称保存失败');
  expect(input.value).toBe('待重试名称');
  await waitFor(() => expect(document.activeElement).toBe(input));
  fireEvent.keyDown(input, { key: 'Enter' });
  const renamed = await manager.findByRole('button', {
    name: '编辑待重试名称名称',
  });
  await waitFor(() => expect(document.activeElement).toBe(renamed));
  expect(host.instance!.save).not.toHaveBeenCalled();
  fireEvent.click(renamed);
  fireEvent.change(manager.getByRole('textbox', { name: '待重试名称名称' }), {
    target: { value: '未提交名称' },
  });
  fireEvent.click(manager.getByRole('button', { name: '完成' }));
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '管理视图' })).toBeNull(),
  );
  fireEvent.click(trigger);
  const reopened = within(
    await screen.findByRole('dialog', { name: '管理视图' }),
  );
  expect(reopened.queryByRole('textbox')).toBeNull();
  expect(
    reopened.getByRole('button', { name: '编辑待重试名称名称' }),
  ).toBeTruthy();
  expect(host.instance!.rename).toHaveBeenCalledTimes(2);
});
it('manages names for prototype-like instance IDs', async () => {
  const { host } = setup();
  const value = { ...instance, id: 'constructor' };
  host.instance!.list = vi
    .fn()
    .mockResolvedValue({ instances: [value], defaultInstanceId: value.id });
  host.permission!.getInstance = () => ({
    save: false,
    saveAsPersonal: false,
    saveAsShared: false,
    rename: true,
  });
  host.instance!.rename = vi.fn(async (id, title) => ({ ...value, id, title }));
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(screen.getAllByRole('button', { name: '管理视图' })[0]);
  const manager = within(
    await screen.findByRole('dialog', { name: '管理视图' }),
  );
  expect(manager.queryByRole('textbox')).toBeNull();
  fireEvent.click(manager.getByRole('button', { name: '编辑我的订单名称' }));
  fireEvent.change(manager.getByRole('textbox', { name: '我的订单名称' }), {
    target: { value: '新的名称' },
  });
  fireEvent.click(manager.getByRole('button', { name: '保存我的订单名称' }));
  expect(
    await manager.findByRole('button', { name: '编辑新的名称名称' }),
  ).toBeTruthy();
});
