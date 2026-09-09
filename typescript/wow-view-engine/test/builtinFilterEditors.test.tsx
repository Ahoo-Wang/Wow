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

import { configuration } from './fixtures/filterPanel.js';
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type { FilterPanelProps } from '../src/filter/filterReactTypes.js';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from '../src/filter/filterModel.js';
import type { FilterOptionSource } from '../src/filter/filterOptionSource.js';
import { createFilterConfiguration } from '../src/filter/filterConfiguration.js';
import { select } from './fixtures/filterPanel.js';

afterEach(cleanup);

const customer: FilterFieldDefinition = { field: 'customer', label: '客户' };

function mountPanel(
  initial: FilterComponentConfig,
  options: Partial<FilterPanelProps> = {},
) {
  let current = initial;
  const apply = vi.fn();
  function Example() {
    const [draft, setDraft] = useState(configuration(initial));
    return (
      <FilterPanel
        fields={[customer]}

        {...options}
        value={draft}
        appliedValue={configuration(initial)}
        onApply={apply}
        onChange={next => {
          current = next.root;
          setDraft(next);
        }}
      />
    );
  }
  return { ...render(<Example />), apply, current: () => current };
}

function closeChoices() {
  fireEvent.keyDown(screen.getByRole('combobox', { name: '客户搜索' }), {
    key: 'Escape',
  });
}

it('selects and clears a local scalar, preserving numeric zero and the serialized label', async () => {
  const state = mountPanel(
    {
      id: 'local',
      field: 'customer',
      operator: Op.EQ,
      component: {
        name: 'select',
        options: { items: [{ value: 0, label: '编号零' }] },
      },
      props: { note: 'retained' },
    },
    {
      fields: [{ ...customer, options: [{ value: 0, label: '字段默认项' }] }],
    },
  );
  expect(screen.getByRole('combobox', { name: '客户' }).textContent).toContain(
    '未设置',
  );
  await select('客户', '编号零');
  expect(state.current().props).toEqual({
    note: 'retained',
    value: 0,
    selectedOptions: [{ value: 0, label: '编号零' }],
  });
  expect(state.apply).not.toHaveBeenCalled();
  expect(
    screen.queryAllByRole('alert').map(alert => alert.textContent),
  ).toEqual([]);
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(state.apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.eq('customer', 0) }),
  );
  const restored = JSON.parse(
    JSON.stringify(createFilterConfiguration(state.current())),
  ).root;
  state.unmount();
  const again = mountPanel(restored);
  expect(screen.getByRole('combobox', { name: '客户' }).textContent).toContain(
    '编号零',
  );
  fireEvent.click(screen.getByRole('combobox', { name: '客户' }));
  fireEvent.click(await screen.findByText('已选 1 项'));
  fireEvent.click(screen.getByRole('button', { name: '移除编号零' }));
  closeChoices();
  expect(again.current().props).toEqual({
    note: 'retained',
    value: undefined,
    selectedOptions: [],
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(again.apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
});

it('deduplicates restored local multi-values while keeping numeric and string IDs distinct', async () => {
  const state = mountPanel(
    {
      id: 'local-multi',
      field: 'customer',
      operator: Op.NOT_IN,
      component: { name: 'multi-select' },
      props: {
        note: 'retained',
        values: [1, 1],
        selectedOptions: [{ value: 1, label: '保存标签' }],
      },
    },
    {
      fields: [
        {
          ...customer,
          options: [
            { value: 1, label: '数字' },
            { value: '1', label: '文本' },
          ],
        },
      ],
    },
  );
  expect(screen.getByRole('combobox', { name: '客户' }).textContent).toContain(
    '保存标签',
  );
  await select('客户', '文本');
  closeChoices();
  expect(state.current().props).toEqual({
    note: 'retained',
    values: [1, '1'],
    selectedOptions: [
      { value: 1, label: '保存标签' },
      { value: '1', label: '文本' },
    ],
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(state.apply).toHaveBeenLastCalledWith(
    expect.objectContaining({
      expression: {
        op: Op.NOT_IN,
        field: 'customer',
        values: [1, '1'],
      },
    }),
  );
});

it.each([false, true])(
  'routes remote selection and removal through the registered editor (multiple=%s)',
  async multiple => {
    const source: FilterOptionSource = {
      resolve: async values => ({
        list: values.map(value => ({
          value,
          label: value === 'old' ? '回填名称' : '新选项',
        })),
        missing: [],
      }),
      search: vi.fn(async () => ({
        list: [{ value: 'new', label: '新选项', group: '分组' }],
        nextCursor: null,
      })),
    };
    const state = mountPanel(
      {
        id: 'remote',
        field: 'customer',
        operator: multiple ? Op.IN : Op.NE,
        component: {
          name: multiple ? 'remote-multi-select' : 'remote-select',
          options: { source: 'customers', pageSize: 7, debounceMs: 0 },
        },
        props: {
          note: 'retained',
          ...(multiple ? { values: ['old'] } : { value: 'old' }),
          selectedOptions: [{ value: 'old', label: '保存名称' }],
        },
      },
      { extensions: { optionSources: { customers: source } } },
    );
    await waitFor(() =>
      expect(
        screen.getByRole('combobox', { name: '客户' }).textContent,
      ).toContain('回填名称'),
    );
    expect(state.current().props?.selectedOptions).toEqual([
      { value: 'old', label: '保存名称' },
    ]);
    await select('客户', '新选项');
    if (multiple) closeChoices();
    expect(source.search).toHaveBeenCalledWith(
      { search: '', cursor: null, size: 7 },
      expect.any(AbortSignal),
    );
    expect(state.current().props).toEqual({
      note: 'retained',
      ...(multiple ? { values: ['old', 'new'] } : { value: 'new' }),
      selectedOptions: [
        ...(multiple ? [{ value: 'old', label: '保存名称' }] : []),
        { value: 'new', label: '新选项' },
      ],
    });
    expect(state.apply).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(state.apply).toHaveBeenLastCalledWith(
      expect.objectContaining({
        expression: multiple
          ? filter.isIn('customer', ['old', 'new'])
          : filter.ne('customer', 'new'),
      }),
    );
    fireEvent.click(screen.getByRole('combobox', { name: '客户' }));
    fireEvent.click(
      await screen.findByText(multiple ? '已选 2 项' : '已选 1 项'),
    );
    fireEvent.click(screen.getByRole('button', { name: '移除新选项' }));
    if (multiple)
      fireEvent.click(screen.getByRole('button', { name: '移除回填名称' }));
    closeChoices();
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(state.apply).toHaveBeenLastCalledWith(
      expect.objectContaining({ expression: filter.matchAll() }),
    );
    expect(state.current().props).toEqual({
      note: 'retained',
      ...(multiple ? { values: [] } : { value: undefined }),
      selectedOptions: [],
    });
  },
);

it('blocks Query for uncommitted text and recovers after Enter, paste and removal', () => {
  const state = mountPanel({
    id: 'text',
    field: 'customer',
    operator: Op.IN,
    component: { name: 'text-values' },
    props: { note: 'retained', values: ['001', '001'] },
  });
  expect(screen.getAllByRole('button', { name: '移除001' })).toHaveLength(1);
  const input = screen.getByRole('textbox', { name: '客户' });
  fireEvent.change(input, { target: { value: 'A B' } });
  const query = screen.getByRole('button', { name: '查询' });
  expect(query).toHaveProperty('disabled', true);
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(state.apply).not.toHaveBeenCalled();
  expect(query).toHaveProperty('disabled', false);
  fireEvent.paste(input, { clipboardData: { getData: () => '001;002' } });
  fireEvent.click(screen.getByRole('button', { name: '移除001' }));
  expect(state.current().props).toEqual({
    note: 'retained',
    values: ['A B', '002'],
  });
  fireEvent.click(query);
  expect(state.apply).toHaveBeenLastCalledWith(
    expect.objectContaining({
      expression: filter.isIn('customer', ['A B', '002']),
    }),
  );
});

it('blocks Query for an unfinished registered date range and applies the completed range', async () => {
  const state = mountPanel(
    {
      id: 'range',
      field: 'created',
      operator: Op.BETWEEN,
      component: { name: 'datetime-range' },
      props: {
        note: 'retained',
        lowerBound: '2026-09-01',
        upperBound: '2026-09-02',
      },
    },
    { fields: [{ field: 'created', label: '创建', type: 'date' }] },
  );
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  const dialog = await screen.findByRole('dialog', { name: '创建日期范围' });
  fireEvent.click(
    dialog.querySelector<HTMLButtonElement>('[data-day="2026/9/5"]')!,
  );
  expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
    'disabled',
    true,
  );
  fireEvent.click(
    dialog.querySelector<HTMLButtonElement>('[data-day="2026/9/7"]')!,
  );
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(state.current().props).toEqual({
    note: 'retained',
    lowerBound: '2026-09-05',
    upperBound: '2026-09-07',
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(state.apply).toHaveBeenLastCalledWith(
    expect.objectContaining({
      expression: {
        op: Op.BETWEEN,
        field: 'created',
        lowerBound: '2026-09-05',
        upperBound: '2026-09-07',
      },
    }),
  );
});

it.each([
  ['select', Op.EQ, { value: true }, '单选值无效'],
  ['multi-select', Op.IN, { values: 'invalid' }, '多选值无效'],
  ['text-values', Op.IN, { values: [1] }, '多值文本只接受字符串'],
] as const)(
  'contains invalid restored %s properties and can recover by clearing',
  (name, op, props, error) => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const state = mountPanel({
      id: 'invalid',
      field: 'customer',
      operator: op,
      component: { name },
      props: { ...props },
    });
    expect(
      screen
        .getAllByRole('alert')
        .some(alert => alert.textContent?.includes(error)),
    ).toBe(true);
    expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
      'disabled',
      true,
    );
    fireEvent.click(screen.getByRole('button', { name: '清空条件' }));
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(state.apply).toHaveBeenLastCalledWith(
      expect.objectContaining({ expression: filter.matchAll() }),
    );
  },
);

it.each(['pageSize', 'debounceMs'])(
  'rejects a nonnumeric remote %s before searching and recovers with the builtin fallback',
  key => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const source: FilterOptionSource = {
      search: vi.fn(async () => ({ list: [], nextCursor: null })),
      resolve: async () => ({ list: [], missing: [] }),
    };
    const state = mountPanel(
      {
        id: 'bad-remote',
        field: 'customer',
        operator: Op.EQ,
        component: {
          name: 'remote-select',
          options: { source: 'customers', [key]: '7' },
        },
        props: {},
      },
      { extensions: { optionSources: { customers: source } } },
    );
    expect(screen.getByRole('alert').textContent).toContain(
      '候选配置必须使用数字',
    );
    expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
      'disabled',
      true,
    );
    expect(source.search).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '使用内置编辑器' }));
    expect(screen.getByLabelText('客户值')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(state.apply).toHaveBeenLastCalledWith(
      expect.objectContaining({ expression: filter.matchAll() }),
    );
  },
);
