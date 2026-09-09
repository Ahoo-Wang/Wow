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
import { filter } from '@ahoo-wang/fetcher-wow';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewPage } from '../src/record/ViewPage.js';
import { setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('navigation collapse retains the filter buffer and does not query', async () => {
  const { host, paged } = setup();
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '99' },
  });
  fireEvent.click(screen.getByRole('button', { name: '收起视图列表' }));
  expect(screen.queryByRole('complementary', { name: '视图列表' })).toBeNull();
  expect(
    (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement).value,
  ).toBe('99');
  expect(paged).toHaveBeenCalledTimes(1);
});
it.each([false, true])(
  'restores each pending filter after navigating through the grouped view picker (collapsed: %s)',
  async collapsed => {
    const { host, paged } = setup();
    render(
      <ViewPage
        scopeKey="test-user"
        definitionId="orders"
        host={host}
        initialSidebarCollapsed={collapsed}
      />,
    );
    await screen.findByRole('cell', { name: '42' });
    fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
      target: { value: '99' },
    });
    async function select(title: string) {
      if (collapsed) {
        fireEvent.click(screen.getByRole('combobox', { name: '选择视图实例' }));
        const option = await screen.findByRole('option', {
          name: new RegExp(`^${title}`),
        });
        fireEvent.pointerDown(option, { pointerType: 'mouse' });
        fireEvent.click(option);
      } else {
        fireEvent.click(
          screen.getByRole('button', { name: new RegExp(`^${title}`) }),
        );
      }
      await waitFor(() =>
        expect(
          screen.getByRole('combobox', { name: '选择视图实例' }).textContent,
        ).toContain(title),
      );
      await waitFor(() =>
        expect(
          (screen.getByRole('button', { name: '刷新' }) as HTMLButtonElement)
            .disabled,
        ).toBe(false),
      );
    }
    await select('所有订单');
    expect(
      (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement)
        .value,
    ).toBe('10');
    fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
      target: { value: '88' },
    });
    await select('我的订单');
    expect(
      (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement)
        .value,
    ).toBe('99');
    expect(
      (screen.getByRole('button', { name: '保存' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    await select('所有订单');
    expect(
      (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement)
        .value,
    ).toBe('88');
    expect(paged).toHaveBeenCalledTimes(4);
    for (const [query] of paged.mock.calls)
      expect(query.filter).toEqual(filter.gte('amount', 10));
    expect(host.instance!.save).not.toHaveBeenCalled();
  },
);
it.each([false, true])(
  'opens management from the switcher footer without changing selection (empty: %s)',
  async empty => {
    const { host, paged } = setup();
    if (empty)
      host.instance!.list = vi
        .fn()
        .mockResolvedValue({ instances: [], defaultInstanceId: null });
    render(
      <ViewPage
        scopeKey="test-user"
        definitionId="orders"
        host={host}
        initialSidebarCollapsed
      />,
    );
    const chooser = await screen.findByRole('combobox', {
      name: '选择视图实例',
    });
    if (!empty) await screen.findByRole('cell', { name: '42' });
    expect(screen.queryByRole('button', { name: '管理视图' })).toBeNull();
    fireEvent.click(chooser);
    const manage = await screen.findByRole('button', { name: '管理视图' });
    expect(manage.closest('[role="listbox"]')).toBeNull();
    fireEvent.click(manage);
    const dialog = within(
      await screen.findByRole('dialog', { name: '管理视图' }),
    );
    expect(screen.queryByRole('listbox')).toBeNull();
    fireEvent.click(dialog.getByRole('button', { name: '完成' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(chooser));
    expect(paged).toHaveBeenCalledTimes(empty ? 0 : 1);
    expect(chooser.textContent).toContain(empty ? '选择视图' : '我的订单');
  },
);
it('expands the page without remounting filters and lets Escape close overlays first', async () => {
  const { host, paged } = setup();
  const view = render(
    <ViewPage scopeKey="test-user" definitionId="orders" host={host} />,
  );
  await screen.findByRole('cell', { name: '42' });
  const input = screen.getByRole('textbox', { name: '金额值' });
  fireEvent.change(input, { target: { value: '99' } });
  fireEvent.click(screen.getByRole('button', { name: '展开视图' }));
  expect(
    view.container.querySelector('[data-view-expanded="true"]'),
  ).toBeTruthy();
  expect(document.body.style.overflow).toBe('hidden');
  expect(screen.getByRole('textbox', { name: '金额值' })).toBe(input);
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  const columns = await screen.findByRole('dialog', { name: '列设置' });
  fireEvent.keyDown(columns, { key: 'Escape' });
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '列设置' })).toBeNull(),
  );
  expect(screen.getByRole('button', { name: '收起视图' })).toBeTruthy();
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(document.activeElement).toBe(
    screen.getByRole('button', { name: '展开视图' }),
  );
  await screen.findByRole('button', { name: '展开视图' });
  expect(document.body.style.overflow).not.toBe('hidden');
  expect((input as HTMLInputElement).value).toBe('99');
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '展开视图' }));
  view.unmount();
  expect(document.body.style.overflow).not.toBe('hidden');
});
