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

import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import {
  act,
  cleanup,
  renderHook,
  render,
  screen,
  fireEvent,
  waitFor,
} from '@testing-library/react';
import { useRemoteFilterOptions } from '../src/filter/useRemoteFilterOptions.js';
import { FilterRemoteSelect } from '../src/filter/FilterRemoteSelect.js';
import type {
  FilterOptionSource,
  FilterOptionValue,
} from '../src/filter/filterOptionSource.js';
afterEach(cleanup);
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(yes => {
    resolve = yes;
  });
  return { resolve, promise };
}
const emptyResolve: FilterOptionSource['resolve'] = async values => ({
  list: values.map(value => ({ value, label: String(value) })),
  missing: [],
});
it('ignores replaced and closed searches even when the source ignores cancellation', async () => {
  const old = deferred<Awaited<ReturnType<FilterOptionSource['search']>>>();
  const late = deferred<Awaited<ReturnType<FilterOptionSource['search']>>>();
  const source: FilterOptionSource = {
    resolve: emptyResolve,
    search: vi.fn(({ search }) =>
      search === ''
        ? old.promise
        : search === 'late'
          ? late.promise
          : Promise.resolve({
              list: [{ value: 'new', label: '新' }],
              nextCursor: null,
            }),
    ),
  };
  const { result } = renderHook(() => useRemoteFilterOptions(source, [], 0));
  act(() => result.current.open(true));
  await act(async () => result.current.change('new'));
  expect(result.current.options.map(o => o.value)).toEqual(['new']);
  await act(async () =>
    old.resolve({ list: [{ value: 'old', label: '旧' }], nextCursor: null }),
  );
  expect(result.current.options.map(o => o.value)).toEqual(['new']);
  act(() => result.current.change('late'));
  act(() => result.current.open(false));
  await act(async () =>
    late.resolve({
      list: [{ value: 'late', label: '迟到' }],
      nextCursor: null,
    }),
  );
  expect(result.current.options).toEqual([]);
});
it('retains earlier pages on failure, retries and rejects repeated cursors', async () => {
  let failed = false;
  const source: FilterOptionSource = {
    resolve: emptyResolve,
    search: vi.fn(async ({ cursor }) => {
      if (!cursor)
        return { list: [{ value: 1, label: '旧标签' }], nextCursor: 'p2' };
      if (!failed) {
        failed = true;
        throw new Error('offline');
      }
      if (cursor === 'p2')
        return {
          list: [
            { value: 1, label: '新标签' },
            { value: '1', label: '文本' },
          ],
          nextCursor: 'p3',
        };
      return { list: [], nextCursor: 'p3' };
    }),
  };
  const { result } = renderHook(() => useRemoteFilterOptions(source, [], 0));
  await act(async () => result.current.open(true));
  await act(async () => result.current.more());
  expect(result.current.options).toHaveLength(1);
  expect(result.current.error).toBeTruthy();
  await act(async () => result.current.retry());
  expect(result.current.options).toEqual([
    { value: 1, label: '新标签' },
    { value: '1', label: '文本' },
  ]);
  await act(async () => result.current.more());
  expect(result.current.error).toBeInstanceOf(TypeError);
  expect(result.current.options).toHaveLength(2);
  await act(async () => result.current.change('新搜索'));
  expect(result.current.failed).toBe(false);
  expect(result.current.nextCursor).toBe('p2');
});
it('hydrates labels without publishing selected properties, and isolates a replacement source', async () => {
  const first = deferred<Awaited<ReturnType<FilterOptionSource['resolve']>>>();
  const second = deferred<Awaited<ReturnType<FilterOptionSource['resolve']>>>();
  const source: FilterOptionSource = {
    search: async () => ({ list: [], nextCursor: null }),
    resolve: () => first.promise,
  };
  const changes = vi.fn();
  const props = {
    label: '用户',
    value: 'u',
    selectedOptions: [{ value: 'u', label: '保存标签' }],
    onValueChange: changes,
  };
  const view = render(<FilterRemoteSelect {...props} source={source} />);
  expect(screen.getByRole('combobox', { name: '用户' }).textContent).toContain(
    '保存标签',
  );
  await act(async () =>
    first.resolve({ list: [{ value: 'u', label: '新标签' }], missing: [] }),
  );
  expect(screen.getByRole('combobox', { name: '用户' }).textContent).toContain(
    '新标签',
  );
  expect(changes).not.toHaveBeenCalled();
  view.rerender(
    <FilterRemoteSelect
      {...props}
      source={{ ...source, resolve: () => second.promise }}
    />,
  );
  expect(screen.getByRole('combobox', { name: '用户' }).textContent).toContain(
    '保存标签',
  );
  await act(async () => second.resolve({ list: [], missing: ['u'] }));
  expect(screen.getByRole('combobox', { name: '用户' }).textContent).toContain(
    '已不可用',
  );
  expect(changes).not.toHaveBeenCalled();
});

it('uses the current candidate label when an ID is removed and selected again', async () => {
  const changes = vi.fn();
  const source: FilterOptionSource = {
    search: async () => ({
      list: [{ value: 'u', label: '新标签' }],
      nextCursor: null,
    }),
    resolve: async () => ({
      list: [{ value: 'u', label: '旧标签' }],
      missing: [],
    }),
  };
  function Example() {
    const [ids, setIds] = useState<FilterOptionValue[]>(['u']);
    return (
      <FilterRemoteSelect
        label="用户"
        multiple
        values={ids}
        source={source}
        onValueChange={(next, snapshots) => {
          changes(next, snapshots);
          setIds(next);
        }}
      />
    );
  }
  render(<Example />);
  await waitFor(() =>
    expect(
      screen.getByRole('combobox', { name: '用户' }).textContent,
    ).toContain('旧标签'),
  );
  fireEvent.click(screen.getByRole('combobox', { name: '用户' }));
  fireEvent.click(await screen.findByRole('option', { name: '新标签' }));
  expect(changes).toHaveBeenLastCalledWith([], []);
  fireEvent.click(screen.getByRole('option', { name: '新标签' }));
  expect(changes).toHaveBeenLastCalledWith(
    ['u'],
    [{ value: 'u', label: '新标签' }],
  );
});

it('preserves existing saved labels when another candidate is selected', async () => {
  const changes = vi.fn();
  const source: FilterOptionSource = {
    resolve: async () => ({
      list: [{ value: 'u', label: '回填标签' }],
      missing: [],
    }),
    search: async () => ({
      list: [{ value: 'new', label: '新选项' }],
      nextCursor: null,
    }),
  };
  render(
    <FilterRemoteSelect
      label="用户"
      multiple
      values={['u']}
      selectedOptions={[{ value: 'u', label: '保存标签' }]}
      source={source}
      onValueChange={changes}
    />,
  );
  await waitFor(() =>
    expect(
      screen.getByRole('combobox', { name: '用户' }).textContent,
    ).toContain('回填标签'),
  );
  fireEvent.click(screen.getByRole('combobox', { name: '用户' }));
  fireEvent.click(await screen.findByRole('option', { name: '新选项' }));
  expect(changes).toHaveBeenLastCalledWith(
    ['u', 'new'],
    [
      { value: 'u', label: '保存标签' },
      { value: 'new', label: '新选项' },
    ],
  );
});

it('waits for IME completion and cancels pending debounce on close', async () => {
  const source: FilterOptionSource = {
    resolve: emptyResolve,
    search: vi.fn(async () => ({ list: [], nextCursor: null })),
  };
  render(
    <FilterRemoteSelect
      label="部门"
      source={source}
      debounceMs={30}
      onValueChange={() => {}}
    />,
  );
  fireEvent.click(screen.getByRole('combobox', { name: '部门' }));
  const input = await screen.findByRole('combobox', { name: '部门搜索' });
  await waitFor(() => expect(source.search).toHaveBeenCalledTimes(1));
  fireEvent.compositionStart(input);
  fireEvent.change(input, { target: { value: '研发' } });
  await new Promise(resolve => setTimeout(resolve, 50));
  expect(source.search).toHaveBeenCalledTimes(1);
  fireEvent.compositionEnd(input, { data: '研发' });
  await waitFor(() => expect(source.search).toHaveBeenCalledTimes(2));
  fireEvent.change(input, { target: { value: '财务' } });
  fireEvent.keyDown(input, { key: 'Escape' });
  await new Promise(resolve => setTimeout(resolve, 50));
  expect(source.search).toHaveBeenCalledTimes(2);
});

it('does not persist the unavailable annotation when adding another selected item', async () => {
  const source: FilterOptionSource = {
    resolve: async () => ({ list: [], missing: ['gone'] }),
    search: async () => ({
      list: [{ value: 'live', label: '正常' }],
      nextCursor: null,
    }),
  };
  const change = vi.fn();
  render(
    <FilterRemoteSelect
      label="用户"
      multiple
      values={['gone']}
      source={source}
      onValueChange={change}
    />,
  );
  await waitFor(() =>
    expect(
      screen.getByRole('combobox', { name: '用户' }).textContent,
    ).toContain('已不可用'),
  );
  fireEvent.click(screen.getByRole('combobox', { name: '用户' }));
  fireEvent.click(await screen.findByRole('option', { name: '正常' }));
  expect(change).toHaveBeenLastCalledWith(
    ['gone', 'live'],
    [
      { value: 'gone', label: 'gone' },
      { value: 'live', label: '正常' },
    ],
  );
});
