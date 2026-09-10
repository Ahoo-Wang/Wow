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
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewPage } from '../src/record/ViewPage.js';
import { RecordView } from '../src/record/RecordView.js';
import { ViewEngine } from '../src/record/ViewEngine.js';
import type { GlobalActionsRendererProps } from '../src/record/recordReactTypes.js';
import { definition, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('clears selection when selection is disabled so actions and automatic refresh recover', async () => {
  const { host, paged } = setup();
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      recordActions: { global: { name: 'global' }, toolbar: { name: 'table' } },
    },
    host,
  });
  await engine.load();
  const extensions = {
    globalActions: {
      global: ({ selectedRowKeys }: GlobalActionsRendererProps) => (
        <button>全局选中 {selectedRowKeys.length}</button>
      ),
    },
    toolbarActions: {
      table: ({ selectedRowKeys }: GlobalActionsRendererProps) => (
        <button>表格选中 {selectedRowKeys.length}</button>
      ),
    },
  };
  const view = render(
    <RecordView engine={engine} extensions={extensions} selectable />,
  );
  fireEvent.click(screen.getByRole('checkbox', { name: '选择记录 0' }));
  expect(screen.getByRole('button', { name: '表格选中 1' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '自动刷新设置' }));
  const interval = await screen.findByRole('menuitemradio', {
    name: '每 30 秒',
  });
  vi.useFakeTimers();
  try {
    fireEvent.click(interval);
    const refresh = screen.getByRole('button', { name: '刷新' });
    expect(refresh.getAttribute('title')).toContain('已选择记录');
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(paged).toHaveBeenCalledTimes(1);
    view.rerender(
      <RecordView engine={engine} extensions={extensions} selectable={false} />,
    );
    expect(engine.getSnapshot().sessions.mine.selectedRowKeys).toEqual([]);
    expect(screen.getByRole('button', { name: '全局选中 0' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '表格选中 0' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '取消选择' })).toBeNull();
    expect(screen.queryByRole('checkbox', { name: '选择记录 0' })).toBeNull();
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(paged).toHaveBeenCalledTimes(2);
    view.rerender(
      <RecordView engine={engine} extensions={extensions} selectable />,
    );
    expect(
      screen
        .getByRole('checkbox', { name: '选择记录 0' })
        .getAttribute('aria-checked'),
    ).toBe('false');
  } finally {
    view.unmount();
    engine.dispose();
    vi.useRealTimers();
  }
});

it('automatically refreshes without overlapping requests and stops when disabled', async () => {
  const { host, paged } = setup();
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(screen.getByRole('button', { name: '自动刷新设置' }));
  const interval = await screen.findByRole('menuitemradio', {
    name: '每 5 分钟',
  });
  vi.useFakeTimers();
  try {
    let complete!: (value: {
      list: { id: number; amount: number }[];
      total: number;
    }) => void;
    paged.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          complete = resolve;
        }),
    );
    fireEvent.click(interval);
    const refresh = screen.getByRole('button', { name: '刷新' });
    expect(refresh.textContent).toContain('05:00');
    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(refresh.textContent).toContain('04:59');
    vi.setSystemTime(Date.now() + 10000);
    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(refresh.textContent).toContain('04:48');
    await act(() => vi.advanceTimersByTimeAsync(287999));
    expect(refresh.textContent).toContain('00:01');
    expect(paged).toHaveBeenCalledTimes(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(paged).toHaveBeenCalledTimes(2);
    expect(refresh.textContent).toContain('刷新中');
    expect(screen.getByRole('cell', { name: '42' })).toBeTruthy();
    await act(() => vi.advanceTimersByTimeAsync(900000));
    expect(paged).toHaveBeenCalledTimes(2);
    await act(async () =>
      complete({ list: [{ id: 0, amount: 43 }], total: 1 }),
    );
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(refresh.textContent).toContain('05:00');
    expect(screen.getByRole('cell', { name: '43' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '自动刷新设置' }));
    fireEvent.click(
      screen.getByRole('menuitemradio', { name: '关闭自动刷新' }),
    );
    await act(() => vi.advanceTimersByTimeAsync(600000));
    expect(paged).toHaveBeenCalledTimes(2);
  } finally {
    vi.useRealTimers();
  }
});
it('pauses automatic refresh while hidden, editing or explicitly paused and cleans up on unmount', async () => {
  const { host, paged } = setup();
  const visibility = vi
    .spyOn(document, 'visibilityState', 'get')
    .mockReturnValue('hidden');
  const view = render(
    <ViewPage scopeKey="test-user" definitionId="orders" host={host} />,
  );
  await screen.findByRole('cell', { name: '42' });
  fireEvent.click(screen.getByRole('button', { name: '自动刷新设置' }));
  const interval = await screen.findByRole('menuitemradio', {
    name: '每 30 秒',
  });
  vi.useFakeTimers();
  try {
    fireEvent.click(interval);
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(paged).toHaveBeenCalledTimes(1);
    const refresh = screen.getByRole('button', { name: '刷新' });
    expect(refresh.textContent).toContain('已暂停');
    expect(refresh.getAttribute('title')).toContain('页面处于后台');
    visibility.mockReturnValue('visible');
    const input = screen.getByRole('textbox', { name: '金额值' });
    act(() => input.focus());
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(paged).toHaveBeenCalledTimes(1);
    expect(refresh.getAttribute('title')).toContain('正在编辑');
    act(() => input.blur());
    expect(refresh.textContent).toContain('00:30');
    await act(() => vi.advanceTimersByTimeAsync(10000));
    expect(refresh.textContent).toContain('00:20');
    act(() => input.focus());
    expect(refresh.textContent).toContain('已暂停');
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(paged).toHaveBeenCalledTimes(1);
    act(() => input.blur());
    expect(refresh.textContent).toContain('00:30');
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(paged).toHaveBeenCalledTimes(2);
    view.rerender(
      <ViewPage
        scopeKey="test-user"
        definitionId="orders"
        host={host}
        autoRefreshPaused
      />,
    );
    await act(() => vi.advanceTimersByTimeAsync(45000));
    expect(paged).toHaveBeenCalledTimes(2);
    view.unmount();
    await act(() => vi.advanceTimersByTimeAsync(60000));
    expect(paged).toHaveBeenCalledTimes(2);
  } finally {
    vi.useRealTimers();
  }
});
