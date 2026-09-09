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

import { filter } from '@ahoo-wang/fetcher-wow';
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
import { ViewEngine } from '../src/record/ViewEngine.js';
import { ViewPage, ViewPageContent } from '../src/record/ViewPage.js';
import type { ViewInstance } from '../src/record/recordModel.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('saves an added unset control without querying and requires Query after entering a value', async () => {
  const { host, paged } = setup();
  render(
    <ViewPage
      scopeKey="test-user"
      definitionId="orders"
      host={host}
      definition={{
        ...definition,
        fields: [
          ...definition.fields,
          { field: 'customer', label: '客户', type: 'string' },
        ],
      }}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(
    screen.getByRole('button', { name: '添加筛选', exact: true }),
  );
  fireEvent.click(await screen.findByRole('checkbox', { name: '客户' }));
  fireEvent.click(screen.getByRole('button', { name: '完成' }));
  const input = await screen.findByRole('textbox', { name: '客户值' });
  const save = screen.getByRole('button', {
    name: '保存',
    exact: true,
  }) as HTMLButtonElement;
  expect(save.disabled).toBe(false);
  expect(screen.queryByText('筛选未生效')).toBeNull();
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(save);
  await waitFor(() => expect(host.instance!.save).toHaveBeenCalledTimes(1));
  const saved = vi.mocked(host.instance!.save!).mock.calls[0][0];
  const customer = saved.config.filters.root.operands?.find(
    node => node.field === 'customer',
  );
  expect(customer?.component).toEqual({ name: 'builtin' });
  expect(customer?.props).toEqual({});
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.change(input, { target: { value: 'Alice' } });
  expect(save.disabled).toBe(true);
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.keyDown(input, { key: 'Enter' });
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  expect(save.disabled).toBe(false);
});

it('keeps filter edits manual, blocks saves while pending, then saves applied configuration', async () => {
  const { host, paged } = setup();
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  const amount = screen.getByRole('textbox', { name: '金额值' });
  fireEvent.change(amount, { target: { value: '20' } });
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '视图选项' }));
  expect(
    (await screen.findByRole('menuitem', { name: '另存为' })).getAttribute(
      'aria-disabled',
    ),
  ).toBe('true');
  fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  expect(paged.mock.calls[1][0].filter).toEqual(filter.gte('amount', 20));
  fireEvent.click(screen.getByRole('button', { name: '保存', exact: true }));
  await waitFor(() => expect(host.instance!.save).toHaveBeenCalledTimes(1));
  await waitFor(() =>
    expect(
      (
        screen.getByRole('button', {
          name: '保存',
          exact: true,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true),
  );
  expect(
    screen.getByRole('status', { name: '保存状态' }).textContent,
  ).toContain('视图已保存');
});
it.each(['personal', 'shared'])(
  'save-as radios explain visibility and respect %s-only permission',
  async allowed => {
    const { host } = setup();
    host.permission!.getInstance = () => ({
      save: true,
      saveAsPersonal: allowed === 'personal',
      saveAsShared: allowed === 'shared',
    });
    render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
    await screen.findByRole('cell', { name: '42' });
    fireEvent.click(screen.getByRole('button', { name: '视图选项' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '另存为' }));
    const dialog = within(
      await screen.findByRole('dialog', { name: '另存为视图' }),
    );
    const group = within(dialog.getByRole('radiogroup', { name: '可见范围' }));
    const selected = group.getByRole('radio', {
      name: allowed === 'personal' ? '个人视图' : '公共视图',
    });
    const unavailable = group.getByRole('radio', {
      name: allowed === 'personal' ? '公共视图' : '个人视图',
    });
    expect(selected.getAttribute('aria-checked')).toBe('true');
    expect(unavailable.getAttribute('aria-disabled')).toBe('true');
    expect(group.getByText(/仅自己可见/)).toBeTruthy();
    expect(group.getByText(/对有访问权限的用户可见/)).toBeTruthy();
    fireEvent.click(unavailable);
    expect(selected.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(dialog.getByRole('button', { name: '创建视图' }));
    await waitFor(() => expect(host.instance!.create).toHaveBeenCalledTimes(1));
    expect(vi.mocked(host.instance!.create!).mock.calls[0][0].scope).toEqual(
      allowed === 'personal'
        ? { type: 'personal' }
        : { type: 'public', source: 'shared' },
    );
  },
);
it('opens Save As from the save menu and preserves the host create contract', async () => {
  const { host } = setup();
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(screen.getByRole('button', { name: '视图选项' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '另存为' }));
  const dialog = within(
    await screen.findByRole('dialog', { name: '另存为视图' }),
  );
  fireEvent.change(dialog.getByRole('textbox', { name: '视图名称' }), {
    target: { value: '工作副本' },
  });
  fireEvent.click(dialog.getByRole('button', { name: '创建视图' }));
  await waitFor(() => expect(host.instance!.create).toHaveBeenCalledTimes(1));
  expect(vi.mocked(host.instance!.create!).mock.calls[0][0]).toMatchObject({
    title: '工作副本',
    scope: { type: 'personal' },
    config: instance.config,
  });
});
it('returns focus to view actions after restoring without Save As permission', async () => {
  const { host } = setup();
  host.permission!.getInstance = () => ({
    save: true,
    saveAsPersonal: false,
    saveAsShared: false,
  });
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  engine.setTitle('修改后的订单');
  render(<ViewPageContent engine={engine} />);
  const actions = screen.getByRole('group', { name: '视图操作' });
  const trigger = screen.getByRole('button', { name: '视图选项' });
  trigger.focus();
  fireEvent.click(trigger);
  const restore = await screen.findByRole('menuitem', { name: '还原' });
  restore.focus();
  fireEvent.click(restore);
  await waitFor(() =>
    expect(engine.getSnapshot().sessions.mine.dirty).toBe(false),
  );
  await waitFor(() => expect(document.activeElement).toBe(actions));
  expect(trigger.isConnected).toBe(false);
  const save = screen.getByRole('button', {
    name: '保存',
  }) as HTMLButtonElement;
  expect(save.disabled).toBe(true);
  fireEvent.click(save);
  expect(host.instance!.save).not.toHaveBeenCalled();
  engine.dispose();
});
it('keeps a late save failure on its source instance after navigation', async () => {
  const { host } = setup();
  let rejectSave: (error: Error) => void = () => {};
  host.instance!.save = vi.fn(
    () =>
      new Promise<ViewInstance>((_, reject) => {
        rejectSave = reject;
      }),
  );
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  engine.setTitle('编辑后的订单');
  render(<ViewPageContent engine={engine} />);
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  await act(() => engine.selectInstance('system'));
  await act(async () => {
    rejectSave(new Error('源实例保存失败'));
  });
  expect(screen.queryByText('源实例保存失败')).toBeNull();
  await act(() => engine.selectInstance('mine'));
  expect(screen.getByText('源实例保存失败')).toBeTruthy();
  engine.dispose();
});
it('offers reload after a revision conflict and saves the retained draft with the fresh revision', async () => {
  const { host } = setup();
  const saveInstance = vi
    .fn()
    .mockRejectedValueOnce(new Error('版本冲突'))
    .mockImplementation(async (value: ViewInstance) => ({
      ...value,
      revision: 'r3',
    }));
  host.instance!.save = saveInstance;
  host.instance!.load = vi.fn(async () => ({ ...instance, revision: 'r2' }));
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  engine.setTitle('我的新名称');
  render(<ViewPageContent engine={engine} />);
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  await screen.findByText('版本冲突');
  fireEvent.click(screen.getByRole('button', { name: '重新加载并保留编辑' }));
  await waitFor(() =>
    expect(engine.getSnapshot().sessions.mine.instance.revision).toBe('r2'),
  );
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  await waitFor(() => expect(saveInstance).toHaveBeenCalledTimes(2));
  expect(saveInstance.mock.calls[1][0]).toMatchObject({
    title: '我的新名称',
    revision: 'r2',
  });
  engine.dispose();
});
