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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { RecordTable } from '../src/record/RecordTable.js';
import { cleanupTable, instance, props } from './fixtures/recordTable.js';

afterEach(cleanupTable);

it('keeps zero, string and number keys distinct and selects only the supplied page', () => {
  const onSelectionChange = vi.fn();
  const rows = [
    { meta: { id: 0 }, name: '零' },
    { meta: { id: '1' }, name: '字符串' },
    { meta: { id: 1 }, name: '数字' },
  ];
  const view = render(
    <RecordTable
      {...props({
        rows,
        selectable: true,
        selectedRowKeys: ['1', 'off-page'],
        onSelectionChange,
      })}
    />,
  );
  const boxes = screen.getAllByRole('checkbox');
  expect(boxes.map(box => box.getAttribute('aria-checked'))).toEqual([
    'mixed',
    'false',
    'true',
    'false',
  ]);
  fireEvent.click(boxes[3]);
  expect(onSelectionChange).toHaveBeenLastCalledWith(['1', 1]);
  fireEvent.click(boxes[0]);
  expect(onSelectionChange).toHaveBeenLastCalledWith([0, '1', 1]);
  view.rerender(<RecordTable {...props({ rows, selectable: false })} />);
  expect(screen.queryByRole('checkbox')).toBeNull();
});

it('emits server multi-sort with a reset cycle and leaves server row order unchanged', () => {
  const onSortChange = vi.fn();
  function Example() {
    const [sort, setSort] = useState(instance.config.sort);
    return (
      <RecordTable
        {...props({
          rows: [
            { meta: { id: 1 }, name: 'Zulu', amount: 10 },
            { meta: { id: 2 }, name: 'Alpha', amount: 20 },
          ],
          instance: { ...instance, config: { ...instance.config, sort } },
          onSortChange: next => {
            onSortChange(next);
            setSort(next);
          },
        })}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: /名称.*排序/ }));
  expect(onSortChange).toHaveBeenLastCalledWith([
    { field: 'name', direction: SortDirection.ASC },
  ]);
  fireEvent.click(screen.getByRole('button', { name: /金额.*排序/ }), {
    shiftKey: true,
  });
  expect(onSortChange).toHaveBeenLastCalledWith([
    { field: 'name', direction: SortDirection.ASC },
    { field: 'amount', direction: SortDirection.ASC },
  ]);
  fireEvent.click(screen.getByRole('button', { name: /金额.*排序/ }), {
    shiftKey: true,
  });
  fireEvent.click(screen.getByRole('button', { name: /金额.*排序/ }), {
    shiftKey: true,
  });
  expect(onSortChange).toHaveBeenLastCalledWith([
    { field: 'name', direction: SortDirection.ASC },
  ]);
  expect(
    screen
      .getAllByRole('row')
      .slice(1)
      .map(row => within(row).getAllByRole('cell')[0].textContent),
  ).toEqual(['Zulu', 'Alpha']);
});

it('shares field sorting across duplicate columns without emitting duplicate sort fields', () => {
  const onSortChange = vi.fn();
  function Example() {
    const [sort, setSort] = useState(instance.config.sort);
    return (
      <RecordTable
        {...props({
          instance: {
            ...instance,
            config: {
              ...instance.config,
              sort,
              presentation: {
                layout: 'table',
                table: {
                  columns: [
                    ...instance.config.presentation.table.columns,
                    {
                      id: 'alias',
                      kind: 'field',
                      field: 'name',
                      title: '别名',
                    },
                  ],
                },
              },
            },
          },
          onSortChange: next => {
            onSortChange(next);
            setSort(next);
          },
        })}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '别名排序：未排序' }));
  expect(screen.getByRole('button', { name: '别名排序：升序' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '名称排序：升序' })).toBeTruthy();
  expect(onSortChange).toHaveBeenLastCalledWith([
    { field: 'name', direction: SortDirection.ASC },
  ]);
  fireEvent.click(screen.getByRole('button', { name: '金额排序：未排序' }), {
    shiftKey: true,
  });
  fireEvent.click(screen.getByRole('button', { name: '别名排序：升序' }), {
    shiftKey: true,
  });
  expect(onSortChange).toHaveBeenLastCalledWith([
    { field: 'name', direction: SortDirection.DESC },
    { field: 'amount', direction: SortDirection.ASC },
  ]);
  expect(screen.getByRole('button', { name: '别名排序：降序' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '别名排序：降序' }), {
    shiftKey: true,
  });
  expect(onSortChange).toHaveBeenLastCalledWith([
    { field: 'amount', direction: SortDirection.ASC },
  ]);
  expect(screen.getByRole('button', { name: '别名排序：未排序' })).toBeTruthy();
});
