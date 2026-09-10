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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { FieldSort } from '@ahoo-wang/fetcher-wow';
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { RecordSortSettings } from '../src/record/page/RecordSortSettings.js';
import { definition } from './fixtures/viewPage.js';
afterEach(cleanup);
it('reorders active rules and removes them without persisting drag metadata', async () => {
  const onChange = vi.fn();
  const fields = [
    ...definition.fields,
    { field: 'name', label: '名称', type: 'string' as const, sortable: true },
  ];
  function Example() {
    const [sort, setSort] = useState<FieldSort[]>([
      { field: 'amount', direction: SortDirection.ASC },
      { field: 'name', direction: SortDirection.DESC },
    ]);
    return (
      <RecordSortSettings
        definition={{ ...definition, fields }}
        sort={sort}
        onChange={next => {
          onChange(next);
          setSort(next);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '排序：金额 ↑、名称 ↓' }));
  const handle = await screen.findByRole('button', {
    name: '拖动调整金额排序优先级',
  });
  handle.focus();
  fireEvent.keyDown(handle, { key: 'ArrowDown' });
  expect(onChange.mock.lastCall?.[0]).toEqual([
    { field: 'name', direction: 'DESC' },
    { field: 'amount', direction: 'ASC' },
  ]);
  expect(document.activeElement).toBe(handle);
  fireEvent.click(screen.getByRole('button', { name: '名称排序：降序' }));
  expect(onChange.mock.lastCall?.[0]).toEqual([
    { field: 'name', direction: 'ASC' },
    { field: 'amount', direction: 'ASC' },
  ]);
  fireEvent.click(screen.getByRole('button', { name: '名称排序：升序' }));

  expect(
    screen
      .getByRole('button', { name: '金额排序：升序' })
      .getAttribute('title'),
  ).toContain('从低到高');
  expect(
    screen
      .getByRole('button', { name: '名称排序：降序' })
      .getAttribute('title'),
  ).toContain('倒序');
  expect(screen.getByRole('combobox', { name: '添加排序' })).toHaveProperty(
    'disabled',
    true,
  );
  fireEvent.click(screen.getByRole('button', { name: '移除金额排序' }));
  expect(onChange.mock.lastCall?.[0]).toEqual([
    { field: 'name', direction: 'DESC' },
  ]);
  expect(screen.getByRole('combobox', { name: '添加排序' })).toHaveProperty(
    'disabled',
    false,
  );
  fireEvent.click(screen.getByRole('button', { name: '清除全部' }));
  expect(screen.getByText('尚未设置排序')).toBeTruthy();
  expect(onChange.mock.lastCall?.[0]).toEqual([]);
});

it('hides sorting when no fields support it and prevents edits while disabled', async () => {
  const onChange = vi.fn();
  const sort = [{ field: 'amount', direction: SortDirection.ASC }];
  const view = render(
    <RecordSortSettings
      definition={{ ...definition, fields: [] }}
      sort={[]}
      onChange={onChange}
    />,
  );
  expect(screen.queryByRole('button')).toBeNull();
  view.rerender(
    <RecordSortSettings
      definition={definition}
      sort={sort}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '排序：金额 ↑' }));
  await screen.findByRole('button', { name: '清除全部' });
  view.rerender(
    <RecordSortSettings
      definition={definition}
      sort={sort}
      onChange={onChange}
      disabled
    />,
  );
  expect(screen.getByRole('button', { name: '金额排序：升序' })).toHaveProperty(
    'disabled',
    true,
  );
  fireEvent.click(screen.getByRole('button', { name: '清除全部' }));
  fireEvent.click(screen.getByRole('button', { name: '移除金额排序' }));
  expect(onChange).not.toHaveBeenCalled();
});
it('offers chronological direction labels and stops adding at the contract limit', async () => {
  const fields = Array.from({ length: 33 }, (_, index) => ({
    field: `date${index}`,
    label: `日期${index}`,
    type: 'datetime' as const,
    sortable: true,
  }));
  const sort = fields
    .slice(0, 32)
    .map(field => ({ field: field.field, direction: SortDirection.ASC }));
  render(
    <RecordSortSettings
      definition={{ ...definition, fields }}
      sort={sort}
      onChange={vi.fn()}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: /^排序：/ }));
  const add = await screen.findByRole('combobox', { name: '添加排序' });
  expect(add).toHaveProperty('disabled', true);
  expect(add.textContent).toContain('最多 32 条排序');
  expect(
    screen
      .getByRole('button', { name: '日期0排序：升序' })
      .getAttribute('title'),
  ).toContain('从早到晚');
  expect(
    screen
      .getByRole('button', { name: '日期0排序：升序' })
      .getAttribute('title'),
  ).toContain('点击切换为从晚到早');
});
