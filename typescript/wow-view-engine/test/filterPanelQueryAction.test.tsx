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

import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { node, configuration, fields } from './fixtures/filterPanel.js';
afterEach(cleanup);
it('editing-only panels hide query actions and leave Enter unused while preserving edits and validation', () => {
  const onApply = vi.fn();
  const onChange = vi.fn();
  const onValidityChange = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('EQ', 'amount', { value: 10 }))}
      onApply={onApply}
      onChange={onChange}
      onValidityChange={onValidityChange}
      showQueryAction={false}
    />,
  );
  const input = screen.getByRole('textbox', { name: '订单金额值' });
  fireEvent.change(input, { target: { value: '25' } });
  expect(onChange).toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '查询' })).toBeNull();
  expect(screen.queryByText('待查询')).toBeNull();
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(onApply).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '添加筛选' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '清空条件' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '撤销筛选修改' }));
  const restored = screen.getByRole('textbox', { name: '订单金额值' });
  expect((restored as HTMLInputElement).value).toBe('10');
  fireEvent.change(restored, { target: { value: 'invalid' } });
  expect(onValidityChange).toHaveBeenLastCalledWith(false);
});
it('hides custom-toolbar query status but leaves query enabled by default', () => {
  const apply = vi.fn();
  const props = {
    fields,
    defaultValue: configuration(node('EQ', 'amount', { value: 10 })),
    onApply: apply,
    renderToolbar: () => <span>自定义工具栏</span>,
  };
  const view = render(<FilterPanel {...props} showQueryAction={false} />);
  fireEvent.change(screen.getByRole('textbox', { name: '订单金额值' }), {
    target: { value: '25' },
  });
  expect(screen.queryByText('筛选未生效')).toBeNull();
  view.rerender(<FilterPanel {...props} />);
  expect(screen.getByText('筛选未生效')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledTimes(1);
});
