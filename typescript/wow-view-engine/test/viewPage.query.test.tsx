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
import { ViewPage } from './fixtures/OwnedViewPage.js';
import { ViewPageContent } from '../src/view/ViewPage.js';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('places query failure in the record area without claiming an empty result or unknown page count', async () => {
  const { host, paged } = setup();
  paged.mockRejectedValueOnce(new Error('订单服务不可用'));
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  const failure = await screen.findByRole('alert');
  expect(failure.closest('table')).toBe(screen.getByRole('table'));
  expect(failure.textContent).toContain('订单服务不可用');
  expect(screen.queryByRole('img', { name: '暂无记录' })).toBeNull();
  expect(screen.queryByText('本页 0 条记录')).toBeNull();
  expect(screen.queryByText('第 1 / – 页')).toBeNull();
  fireEvent.click(within(failure).getByRole('button', { name: '重试查询' }));
  expect(await screen.findByRole('cell', { name: '42' })).toBeTruthy();
  expect(screen.getByText('共 1 条记录')).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
});

it('navigates page boundaries and resets to the first page after changing page size', async () => {
  const { host, paged } = setup();
  paged.mockResolvedValue({ list: [{ id: 0, amount: 42 }], total: 21 });
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  const previous = screen.getByRole('button', {
    name: '上一页',
  }) as HTMLButtonElement;
  const next = screen.getByRole('button', {
    name: '下一页',
  }) as HTMLButtonElement;
  expect(previous.disabled).toBe(true);
  fireEvent.click(next);
  await screen.findByText('第 2 / 3 页');
  await waitFor(() => expect(next.disabled).toBe(false));
  fireEvent.click(next);
  await screen.findByText('第 3 / 3 页');
  expect(next.disabled).toBe(true);
  fireEvent.click(previous);
  await screen.findByText('第 2 / 3 页');
  await waitFor(() => expect(previous.disabled).toBe(false));
  fireEvent.click(screen.getByRole('combobox', { name: '每页记录数' }));
  const size = await screen.findByRole('option', { name: '50 条' });
  fireEvent.pointerDown(size, { pointerType: 'mouse' });
  fireEvent.click(size);
  await screen.findByText('第 1 / 1 页');
  expect(paged.mock.lastCall?.[0]).toMatchObject({
    pagination: { index: 1, size: 50 },
  });
  expect(previous.disabled).toBe(true);
  expect(next.disabled).toBe(true);
});

it('uses the next cursor without presenting a previous-page action or a total count', async () => {
  const { host, paged } = setup();
  const cursor = vi
    .fn()
    .mockResolvedValueOnce({
      list: [{ id: 0, amount: 42 }],
      nextCursor: 'after-42',
    })
    .mockResolvedValue({ list: [{ id: 1, amount: 84 }], nextCursor: null });
  host.resolveSource = () => ({ paged, cursor });
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    host,
    instances: {
      instances: [
        {
          ...instance,
          config: {
            ...instance.config,
            pagination: { mode: 'cursor', size: 10 },
          },
        },
      ],
      defaultInstanceId: instance.id,
    },
  });
  await engine.load();
  render(<ViewPageContent engine={engine} />);
  expect(screen.getByText('本页 1 条记录')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '上一页' })).toBeNull();
  await act(async () =>
    fireEvent.click(screen.getByRole('button', { name: '下一页' })),
  );
  expect(await screen.findByRole('cell', { name: '84' })).toBeTruthy();
  expect(screen.getByText('第 2 页')).toBeTruthy();
  expect(cursor.mock.lastCall?.[0]).toMatchObject({ cursor: 'after-42' });
  expect(
    (screen.getByRole('button', { name: '下一页' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(paged).not.toHaveBeenCalled();
  engine.dispose();
});

it('retries a failed cursor page from the record error action without returning to the first page', async () => {
  const { host } = setup();
  const cursor = vi
    .fn()
    .mockResolvedValueOnce({
      list: [{ id: 0, amount: 42 }],
      nextCursor: 'after-42',
    })
    .mockRejectedValueOnce(new Error('当前游标页暂时不可用'))
    .mockResolvedValueOnce({ list: [{ id: 1, amount: 84 }], nextCursor: null });
  host.resolveSource = () => ({ cursor });
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    host,
    instances: {
      instances: [
        {
          ...instance,
          config: {
            ...instance.config,
            pagination: { mode: 'cursor', size: 10 },
          },
        },
      ],
      defaultInstanceId: instance.id,
    },
  });
  try {
    await engine.load();
    render(<ViewPageContent engine={engine} />);
    fireEvent.click(screen.getByRole('button', { name: '下一页' }));
    const retry = await screen.findByRole('button', { name: '重试查询' });
    expect(engine.getSnapshot().sessions.mine.page).toBe(2);
    fireEvent.click(retry);
    expect(await screen.findByRole('cell', { name: '84' })).toBeTruthy();
    expect(screen.getByText('第 2 页')).toBeTruthy();
    expect(cursor.mock.calls.map(([query]) => query.cursor)).toEqual([
      null,
      'after-42',
      'after-42',
    ]);
  } finally {
    engine.dispose();
  }
});
