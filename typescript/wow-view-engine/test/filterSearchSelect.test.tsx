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
import { FilterSearchSelect } from '../src/filter/FilterSearchSelect.js';

afterEach(cleanup);
const options = [
  { value: '0', label: '晨星零售' },
  { value: 'cloud', label: '云杉制造' },
  { value: 'disabled', label: '云海停用', disabled: true },
];

it('searches candidates without changing selection, then selects and clears', async () => {
  const changes = vi.fn();
  function Example() {
    const [value, setValue] = useState<string | null>('0');
    return (
      <FilterSearchSelect
        label="客户"
        options={options}
        value={value}
        onValueChange={next => {
          setValue(next);
          changes(next);
        }}
        onClear={() => {
          setValue(null);
          changes(null);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('combobox', { name: '客户' }));
  const search = await screen.findByRole('combobox', { name: '客户搜索' });
  fireEvent.change(search, { target: { value: '云杉' } });
  expect(screen.queryByRole('option', { name: '晨星零售' })).toBeNull();
  expect(screen.getByRole('option', { name: '云杉制造' })).toBeTruthy();
  expect(changes).not.toHaveBeenCalled();
  fireEvent.change(search, { target: { value: '没有这家公司' } });
  expect(await screen.findByText('没有匹配选项')).toBeTruthy();
  fireEvent.change(search, { target: { value: '云杉' } });
  fireEvent.keyDown(search, { key: 'ArrowDown' });
  fireEvent.keyDown(search, { key: 'Enter' });
  expect(changes).toHaveBeenLastCalledWith('cloud');
  fireEvent.click(screen.getByRole('button', { name: '清空客户' }));
  expect(changes).toHaveBeenLastCalledWith(null);
});

it('prevents selecting disabled options and disables an already-open picker', async () => {
  const change = vi.fn();
  const props = { label: '客户', options, onValueChange: change };
  const view = render(<FilterSearchSelect {...props} />);
  fireEvent.click(screen.getByRole('combobox', { name: '客户' }));
  const option = await screen.findByRole('option', { name: '云海停用' });
  expect(option.getAttribute('aria-disabled')).toBe('true');
  fireEvent.click(option);
  expect(change).not.toHaveBeenCalled();
  view.rerender(<FilterSearchSelect {...props} disabled />);
  expect(
    (screen.getByRole('combobox', { name: '客户' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  const search = screen.queryByRole('combobox', { name: '客户搜索' });
  if (search) expect((search as HTMLInputElement).disabled).toBe(true);
  expect(change).not.toHaveBeenCalled();
});
