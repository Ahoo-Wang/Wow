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
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewPage } from '../src/record/ViewPage.js';
import { instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('updates save-as permissions while preserving an open form and its name', async () => {
  const { host, paged } = setup();
  const view = render(
    <ViewPage scopeKey="user" definitionId="orders" host={host} />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(screen.getByRole('button', { name: '视图选项' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '另存为' }));
  const dialog = within(
    await screen.findByRole('dialog', { name: '另存为视图' }),
  );
  fireEvent.change(dialog.getByRole('textbox', { name: '视图名称' }), {
    target: { value: '保留的名称' },
  });
  view.rerender(
    <ViewPage
      scopeKey="user"
      definitionId="orders"
      host={{
        ...host,
        permission: {
          getInstance: () => ({
            save: true,
            saveAsPersonal: false,
            saveAsShared: true,
          }),
        },
      }}
    />,
  );
  await waitFor(() =>
    expect(
      dialog
        .getByRole('radio', { name: '个人视图' })
        .getAttribute('aria-disabled'),
    ).toBe('true'),
  );
  expect(
    dialog
      .getByRole('radio', { name: '公共视图' })
      .getAttribute('aria-disabled'),
  ).not.toBe('true');
  expect(
    (dialog.getByRole('textbox', { name: '视图名称' }) as HTMLInputElement)
      .value,
  ).toBe('保留的名称');
  expect(paged).toHaveBeenCalledTimes(1);
});

it('refreshes open management and delete controls when host capabilities change', async () => {
  const { host, paged } = setup();
  const local = {
    instances: [instance, { ...instance, id: 'other', title: '其他视图' }],
    defaultInstanceId: instance.id,
  };
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
  }));
  host.instance!.delete = vi.fn(async () => ({ defaultInstance: null }));
  host.preference!.saveOrder = vi.fn(async () => {});
  const view = render(
    <ViewPage
      scopeKey="user"
      definitionId="orders"
      host={host}
      instances={local}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(screen.getAllByRole('button', { name: '管理视图' })[0]);
  const manager = within(
    await screen.findByRole('dialog', { name: '管理视图' }),
  );
  const handle = manager.getByRole('button', {
    name: '拖动调整我的订单顺序',
  }) as HTMLButtonElement;
  expect(handle.disabled).toBe(false);
  fireEvent.click(manager.getByRole('button', { name: '删除我的订单' }));
  const confirm = within(
    await screen.findByRole('dialog', { name: '删除视图' }),
  );
  view.rerender(
    <ViewPage
      scopeKey="user"
      definitionId="orders"
      instances={local}
      host={{
        ...host,
        preference: { saveOrder: undefined },
        permission: {
          getInstance: () => ({
            save: true,
            saveAsPersonal: true,
            saveAsShared: false,
            rename: false,
            delete: false,
          }),
        },
      }}
    />,
  );
  await waitFor(() =>
    expect(
      (confirm.getByRole('button', { name: '删除视图' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true),
  );
  expect(handle.disabled).toBe(true);
  expect(
    manager.queryByRole('button', { name: '编辑我的订单名称', hidden: true }),
  ).toBeNull();
  expect(
    manager.queryByRole('button', { name: '删除我的订单', hidden: true }),
  ).toBeNull();
  expect(paged).toHaveBeenCalledTimes(1);
  expect(host.instance!.delete).not.toHaveBeenCalled();
});
