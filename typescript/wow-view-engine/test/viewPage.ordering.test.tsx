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

import { keyboardOrder } from './fixtures/listOrder.js';
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
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { ViewPageContent } from '../src/view/ViewPage.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

async function openOrderedViews() {
  const { host, paged } = setup();
  const second = { ...instance, id: 'second', title: '第二个视图' };
  const system = {
    ...instance,
    id: 'system',
    title: '系统视图',
    scope: { type: 'public', source: 'system' } as const,
  };
  host.preference!.saveOrder = vi.fn().mockResolvedValue(undefined);
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: {
      instances: [instance, system, second],
      defaultInstanceId: instance.id,
    },
    host,
  });
  await engine.load();
  render(<ViewPageContent engine={engine} />);
  fireEvent.click(screen.getAllByRole('button', { name: '管理视图' })[0]);
  const dialog = await screen.findByRole('dialog', { name: '管理视图' });
  const manager = within(dialog);
  const personal = manager.getByRole('list', { name: '个人视图顺序' });
  const publicList = manager.getByRole('list', { name: '公共视图顺序' });
  return { engine, host, paged, dialog, manager, personal, publicList };
}

it('retries group-local keyboard ordering while preserving drafts, selection and focus', async () => {
  const { engine, host, paged, dialog, manager, personal, publicList } =
    await openOrderedViews();
  await act(() => engine.setTitle('未保存的标题'));
  const saveOrder = vi.mocked(host.preference!.saveOrder!);
  let rejectSave!: (error: Error) => void;
  saveOrder.mockImplementationOnce(
    () =>
      new Promise((_resolve, reject) => {
        rejectSave = reject;
      }),
  );
  const handle = manager.getByRole('button', { name: '拖动调整我的订单顺序' });
  act(() => handle.focus());
  await keyboardOrder(handle, 'ArrowUp');
  expect(saveOrder).not.toHaveBeenCalled();
  await keyboardOrder(handle, 'ArrowDown');
  expect(
    (manager.getByRole('button', { name: '完成' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  fireEvent.keyDown(dialog, { key: 'Escape' });
  expect(screen.getByRole('dialog', { name: '管理视图' })).toBeTruthy();
  await act(async () => rejectSave(new Error('顺序保存失败')));
  await manager.findByText('顺序保存失败');
  expect(
    within(personal)
      .getAllByRole('listitem')
      .map(item => item.getAttribute('aria-label')),
  ).toEqual(['我的订单', '第二个视图']);
  await waitFor(() => expect(document.activeElement).toBe(handle));
  await keyboardOrder(handle, 'ArrowDown');
  await manager.findByText(/已移至第 2 项/);
  expect(saveOrder).toHaveBeenLastCalledWith('orders', [
    'system',
    'second',
    'mine',
  ]);
  expect(
    within(personal)
      .getAllByRole('listitem')
      .map(item => item.getAttribute('aria-label')),
  ).toEqual(['第二个视图', '我的订单']);
  expect(
    within(publicList)
      .getAllByRole('listitem')
      .map(item => item.getAttribute('aria-label')),
  ).toEqual(['系统视图']);
  await waitFor(() => expect(document.activeElement).toBe(handle));
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  expect(engine.getSnapshot().sessions.mine).toMatchObject({
    dirty: true,
    instance: { title: '未保存的标题' },
  });
  expect(host.instance!.save).not.toHaveBeenCalled();
  expect(paged).toHaveBeenCalledTimes(1);
  engine.dispose();
});

it('isolates ordering permissions between groups', async () => {
  const { engine, host, manager, publicList } = await openOrderedViews();
  const locked = within(publicList).getByRole('button', {
    name: '拖动调整系统视图顺序',
  }) as HTMLButtonElement;
  expect(locked.disabled).toBe(true);
  await keyboardOrder(locked, 'ArrowUp');
  expect(host.preference!.saveOrder).not.toHaveBeenCalled();
  await keyboardOrder(
    manager.getByRole('button', { name: '拖动调整第二个视图顺序' }),
    'ArrowUp',
  );
  await waitFor(() =>
    expect(host.preference!.saveOrder).toHaveBeenCalledExactlyOnceWith(
      'orders',
      ['second', 'mine', 'system'],
    ),
  );
  engine.dispose();
});
