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

import { node, configuration } from './fixtures/filterPanel.js';
import { useState } from 'react';
import {
  filter,
  FilterOperator as Op,
  SearchMode,
  StringComparison,
  TimeUnit,
} from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { FilterRemoteSelect } from '../src/filter/FilterRemoteSelect.js';
import { FilterSearchSelect } from '../src/filter/FilterSearchSelect.js';
import { FilterDatePicker } from '../src/filter/FilterDatePicker.js';
import type { FilterOptionSource } from '../src/filter/filterOptionSource.js';
import { mount, change, fields, select } from './fixtures/filterValueEditor.js';

afterEach(cleanup);

it('retains saved selection while retrying failed candidate and label reads, then loads another page', async () => {
  const source: FilterOptionSource = {
    search: vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({
        list: [{ value: 'a', label: '甲' }],
        nextCursor: 'page2',
      })
      .mockResolvedValueOnce({
        list: [{ value: 'b', label: '乙' }],
        nextCursor: null,
      }),
    resolve: vi
      .fn()
      .mockRejectedValueOnce(new Error('labels offline'))
      .mockResolvedValueOnce({
        list: [{ value: 'old', label: '最新名称' }],
        missing: [],
      }),
  };
  const changed = vi.fn();
  const props = {
    source,
    label: '客户',
    value: 'old',
    selectedOptions: [{ value: 'old', label: '保存名称' }],
    onValueChange: changed,
    debounceMs: 0,
  };
  const view = render(<FilterRemoteSelect {...props} />);
  fireEvent.click(screen.getByRole('combobox', { name: '客户' }));
  await screen.findByRole('button', { name: '重试候选' });
  await screen.findByRole('button', { name: '重试回填' });
  expect(screen.getByRole('combobox', { name: '客户' }).textContent).toContain(
    '保存名称',
  );
  view.rerender(<FilterRemoteSelect {...props} disabled />);
  expect(screen.getByRole('button', { name: '重试候选' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(screen.getByRole('button', { name: '重试回填' })).toHaveProperty(
    'disabled',
    true,
  );
  view.rerender(<FilterRemoteSelect {...props} />);
  fireEvent.click(screen.getByRole('button', { name: '重试回填' }));
  await waitFor(() =>
    expect(
      screen.getByRole('combobox', { name: '客户' }).textContent,
    ).toContain('最新名称'),
  );
  fireEvent.click(screen.getByRole('button', { name: '重试候选' }));
  await screen.findByRole('option', { name: '甲' });
  const more = await screen.findByRole('button', { name: '加载更多' });
  view.rerender(<FilterRemoteSelect {...props} disabled />);
  expect(more).toHaveProperty('disabled', true);
  view.rerender(<FilterRemoteSelect {...props} />);
  fireEvent.click(more);
  await screen.findByRole('option', { name: '乙' });
  expect(screen.getByRole('option', { name: '甲' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: '加载更多' })).toBeNull();
  expect(source.search).toHaveBeenLastCalledWith(
    expect.objectContaining({ cursor: 'page2' }),
    expect.any(AbortSignal),
  );
  expect(source.resolve).toHaveBeenCalledTimes(2);
  expect(changed).not.toHaveBeenCalled();
});

it('rejects a missing source at the standalone remote control boundary', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  expect(() =>
    render(
      <FilterRemoteSelect
        label="客户"
        source={undefined as unknown as FilterOptionSource}
        onValueChange={() => {}}
      />,
    ),
  ).toThrow('未注册候选数据源');
});

it('does not commit IME text or a paste until composition has ended', () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'text-values' } }]}
      defaultValue={configuration(
        node('IN', 'name', { values: ['001'] }, { name: 'text-values' }),
      )}
      onApply={apply}
    />,
  );
  const input = screen.getByRole('textbox', { name: '名称' });
  fireEvent.compositionStart(input);
  fireEvent.change(input, { target: { value: '上海' } });
  fireEvent.paste(input, { clipboardData: { getData: () => '不应粘贴' } });
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(screen.queryByRole('button', { name: '移除上海' })).toBeNull();
  expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
    'disabled',
    true,
  );
  fireEvent.compositionEnd(input, { data: '上海' });
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(screen.getByRole('button', { name: '移除上海' })).toBeTruthy();
  expect(apply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      expression: filter.isIn('name', ['001', '上海']),
    }),
  );
});

it('clears optional string-comparison and time-unit choices without deleting primary values', async () => {
  const text = mount({
    id: 'text',
    operator: Op.CONTAINS,
    field: 'name',
    component: { name: 'builtin' },
    props: {
      value: 'hello',
      stringComparison: StringComparison.CASE_INSENSITIVE,
    },
  });
  fireEvent.click(screen.getByRole('button', { name: '名称参数' }));
  await select('大小写比较', '清空选择');
  expect(text.current()).toEqual({
    id: 'text',
    operator: Op.CONTAINS,
    field: 'name',
    component: { name: 'builtin' },
    props: { value: 'hello', stringComparison: undefined },
  });
  text.unmount();
  const time = mount({
    id: 'time',
    operator: Op.RECENT_DAYS,
    field: 'createdAt',
    component: { name: 'builtin' },
    props: { days: 3, timeUnit: TimeUnit.SECONDS },
  });
  fireEvent.click(screen.getByRole('button', { name: '创建时间参数' }));
  await select('时间单位', '清空选择');
  expect(time.current()).toEqual({
    id: 'time',
    operator: Op.RECENT_DAYS,
    field: 'createdAt',
    component: { name: 'builtin' },
    props: { days: 3, timeUnit: undefined },
  });
});

it('clears search mode and deliberately changes default scope to all fields', async () => {
  const state = mount({
    id: 'search',
    operator: Op.SEARCH,
    component: { name: 'builtin' },
    props: { query: 'hello', mode: SearchMode.PHRASE },
  });
  fireEvent.click(screen.getByRole('button', { name: '搜索参数' }));
  await select('搜索模式', '清空选择');
  fireEvent.click(screen.getByRole('button', { name: '搜索全部字段' }));
  expect(state.current()).toEqual({
    id: 'search',
    operator: Op.SEARCH,
    component: { name: 'builtin' },
    props: { query: 'hello', mode: undefined, fields: [] },
  });
});

it('preserves raw values while changing dynamically typed scalar controls', async () => {
  const state = mount(
    {
      id: 'free',
      operator: Op.EQ,
      field: 'free',
      component: { name: 'builtin' },
      props: { value: false },
    },
    { field: 'free', label: '自由值' },
  );
  await select('自由值值类型', '文本');
  expect(state.current().props.value).toEqual({ type: 'string', value: false });
  change('自由值值', '007');
  await select('自由值值类型', '数值');
  expect(state.current().props.value).toEqual({ type: 'number', value: '007' });
  expect(screen.getByRole('textbox', { name: '自由值值' })).toHaveProperty(
    'value',
    '007',
  );
});

it('selects and clears a scalar date with the calendar while retaining its filter binding', async () => {
  const state = mount({
    id: 'birth',
    operator: Op.EQ,
    field: 'birthday',
    component: { name: 'builtin' },
    props: { value: '2026-09-01' },
  });
  fireEvent.click(screen.getByRole('button', { name: '生日日历：2026-09-01' }));
  const dialog = await screen.findByRole('dialog', { name: '生日日历' });
  fireEvent.click(
    dialog.querySelector<HTMLButtonElement>('[data-day="2026/9/5"]')!,
  );
  expect(state.current()).toEqual({
    id: 'birth',
    operator: Op.EQ,
    field: 'birthday',
    component: { name: 'builtin' },
    props: { value: '2026-09-05' },
  });
  fireEvent.click(screen.getByRole('button', { name: '生日日历：2026-09-05' }));
  const again = await screen.findByRole('dialog', { name: '生日日历' });
  fireEvent.click(
    again.querySelector<HTMLButtonElement>('[data-day="2026/9/5"]')!,
  );
  expect(state.current().props.value).toBeUndefined();
});

it('marks an invalid standalone date and allows replacing it from the calendar', async () => {
  const changed = vi.fn();
  function Example() {
    const [value, setValue] = useState<Date | undefined>(new Date('invalid'));
    return (
      <FilterDatePicker
        label="日期"
        value={value}
        onValueChange={next => {
          setValue(next);
          changed(next);
        }}
      />
    );
  }
  render(<Example />);
  const trigger = screen.getByRole('button', { name: '日期：无效日期' });
  expect(trigger.getAttribute('aria-invalid')).toBe('true');
  fireEvent.click(trigger);
  const dialog = await screen.findByRole('dialog', { name: '日期' });
  const day = dialog.querySelector<HTMLButtonElement>(
    '[data-day]:not([data-outside]):not([disabled])',
  )!;
  fireEvent.click(day);
  expect(changed).toHaveBeenCalledWith(expect.any(Date));
  expect(
    screen
      .getByRole('button', { name: /^日期：/ })
      .getAttribute('aria-invalid'),
  ).toBe('false');
});

it('edits and clears typed datetime-range dates without committing until confirmation', async () => {
  const state = mount(
    {
      id: 'range',
      operator: Op.BETWEEN,
      field: 'createdAt',
      component: { name: 'builtin' },
      props: {
        lowerBound: { date: '2026-09-01', time: '09:00' },
        upperBound: { date: '2026-09-02', time: '10:00' },
      },
    },
    undefined,
    { showTime: true, timeZone: 'Asia/Shanghai' },
  );
  fireEvent.click(screen.getByRole('button', { name: /创建时间日期范围/ }));
  await screen.findByRole('dialog');
  change('创建时间开始日期', '');
  expect(state.changes).toHaveLength(0);
  change('创建时间开始日期', '2026-09-03');
  change('创建时间结束日期', '2026-09-04');
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  expect(state.current().props.lowerBound).toEqual({
    date: '2026-09-03',
    time: '09:00',
  });
  expect(state.current().props.upperBound).toEqual({
    date: '2026-09-04',
    time: '10:00',
  });
});

it('supports selection and clearing in an inline searchable control', async () => {
  function Example() {
    const [value, setValue] = useState<string | null>(null);
    return (
      <FilterSearchSelect
        label="搜索客户"
        inline
        options={[{ value: 'one', label: '客户甲' }]}
        value={value}
        onValueChange={setValue}
        onClear={() => setValue(null)}
      />
    );
  }
  render(<Example />);
  await select('搜索客户', '客户甲');
  expect(
    screen.getByRole('combobox', { name: '搜索客户' }).textContent,
  ).toContain('客户甲');
  fireEvent.click(screen.getByRole('button', { name: '清空搜索客户' }));
  expect(
    screen.getByRole('combobox', { name: '搜索客户' }).textContent,
  ).toContain('未设置');
});
