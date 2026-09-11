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
import { FilterOperator, filter } from '@ahoo-wang/fetcher-wow';
import { getBuiltinFilterCompiler } from '../src/filter/builtinFilterCompilers.js';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { FilterMultiSelect } from '../src/filter/FilterMultiSelect.js';
import { FilterTextValues } from '../src/filter/FilterTextValues.js';
afterEach(cleanup);
it('keeps numeric and string IDs distinct and permits continuous grouped selection', async () => {
  function Example() {
    const [value, setValue] = useState<(string | number)[]>([]);
    return (
      <FilterMultiSelect
        label="客户"
        options={[
          { value: 1, label: '数字', group: '分组' },
          { value: '1', label: '文本', group: '分组' },
        ]}
        value={value}
        onValueChange={setValue}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('combobox', { name: '客户' }));
  expect(await screen.findByRole('listbox', { name: '客户选项' })).toBeTruthy();
  fireEvent.click(await screen.findByRole('option', { name: '数字' }));
  fireEvent.click(screen.getByRole('option', { name: '文本' }));
  expect(
    screen.getByRole('option', { name: '数字' }).getAttribute('aria-selected'),
  ).toBe('true');
  expect(
    screen.getByRole('option', { name: '文本' }).getAttribute('aria-selected'),
  ).toBe('true');
});
it('splits pasted values without losing spaces, leading zeros or triggering Query', () => {
  const change = vi.fn(),
    query = vi.fn();
  render(
    <div onKeyDown={query}>
      <FilterTextValues label="编号" value={[]} onValueChange={change} />
    </div>,
  );
  const input = screen.getByRole('textbox', { name: '编号' });
  fireEvent.paste(input, {
    clipboardData: { getData: () => '001,001；A B\nabc' },
  });
  expect(change).toHaveBeenLastCalledWith(['001', 'A B', 'abc'], '');
  fireEvent.change(input, { target: { value: 'next' } });
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(query).not.toHaveBeenCalled();
});

it.each([
  { text: 'old', start: 0, end: 3, expected: ['kept', '001', '002'] },
  {
    text: 'left,old,right',
    start: 5,
    end: 8,
    expected: ['kept', 'left', '001', '002', 'right'],
  },
  { text: 'AB', start: 1, end: 1, expected: ['kept', 'A001', '002B'] },
])(
  'pastes over the selected buffer range: $text',
  ({ text, start, end, expected }) => {
    const change = vi.fn();
    render(
      <FilterTextValues label="编号" value={['kept']} onValueChange={change} />,
    );
    const input = screen.getByRole('textbox', {
      name: '编号',
    }) as HTMLInputElement;
    fireEvent.change(input, { target: { value: text } });
    input.setSelectionRange(start, end);
    fireEvent.paste(input, { clipboardData: { getData: () => '001,002' } });
    expect(change).toHaveBeenLastCalledWith(expected, '');
    expect(input.value).toBe('');
  },
);

it('selects with the keyboard without submitting the panel and can remove a disabled selection', async () => {
  const changed = vi.fn(),
    query = vi.fn();
  render(
    <div onKeyDown={query}>
      <FilterMultiSelect
        label="状态"
        value={['old']}
        options={[
          { value: 'old', label: '已停用', disabled: true },
          { value: 'next', label: '可选择' },
        ]}
        onValueChange={changed}
      />
    </div>,
  );
  fireEvent.click(screen.getByRole('combobox', { name: '状态' }));
  const input = await screen.findByRole('combobox', { name: '状态搜索' });
  fireEvent.change(input, { target: { value: '可选择' } });
  fireEvent.keyDown(input, { key: 'ArrowDown' });
  query.mockClear();
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(changed).toHaveBeenLastCalledWith(['old', 'next'], expect.any(Array));
  expect(query).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText('已选 1 项'));
  fireEvent.click(screen.getByRole('button', { name: '移除已停用' }));
  expect(changed).toHaveBeenLastCalledWith([], []);
});

it('keeps controlled raw text through IME and removal, then commits atomically', () => {
  const change = vi.fn();
  function Example() {
    const [values, setValues] = useState(['kept']);
    const [rawText, setRawText] = useState('');
    return (
      <FilterTextValues
        label="编号"
        value={values}
        rawText={rawText}
        onRawTextChange={setRawText}
        onValueChange={(values, text) => {
          change(values, text);
          setValues(values);
          setRawText(text);
        }}
      />
    );
  }
  render(<Example />);
  const input = screen.getByRole('textbox', { name: '编号' });
  fireEvent.compositionStart(input);
  fireEvent.change(input, { target: { value: '订单' } });
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(change).not.toHaveBeenCalled();
  expect(input).toHaveProperty('value', '订单');
  fireEvent.compositionEnd(input);
  fireEvent.click(screen.getByRole('button', { name: '移除kept' }));
  expect(change).toHaveBeenLastCalledWith([], '订单');
  expect(input).toHaveProperty('value', '订单');
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(change).toHaveBeenLastCalledWith(['订单'], '');
  expect(input).toHaveProperty('value', '');
});

it('rejects serialized unconfirmed text and clears the raw buffer with values', () => {
  const compiler = getBuiltinFilterCompiler('text-values')!;
  const context = {
    operator: FilterOperator.IN,
    field: { field: 'orderNo', label: '编号', type: 'string' as const },
    fields: [{ field: 'orderNo', label: '编号', type: 'string' as const }],
  };
  const props = { values: ['001'], rawText: '002', presentation: 'retained' };
  expect(() => compiler.compile(props, context)).toThrow('请按回车确认输入');
  expect(() => compiler.compile({ rawText: 2 }, context)).toThrow(
    '文本缓冲必须是字符串',
  );
  expect(compiler.compile({ ...props, rawText: '  ' }, context)).toEqual(
    filter.isIn('orderNo', ['001']),
  );
  const cleared = compiler.clear!(props, context);
  expect(cleared).toEqual({ presentation: 'retained' });
  expect(compiler.compile(cleared, context)).toBeUndefined();
  expect(props).toEqual({
    values: ['001'],
    rawText: '002',
    presentation: 'retained',
  });
});
