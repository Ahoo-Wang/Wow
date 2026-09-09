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
import { filter } from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { FilterFieldPicker } from '../src/filter/FilterFieldPicker.js';
import { fields } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('retains hidden field controls across opens, respects disabled and removes them on unmount', async () => {
  const onAdd = vi.fn();
  const onRemove = vi.fn();
  const draw = (disabled: boolean) => (
    <FilterFieldPicker
      label="添加筛选"
      options={[{ value: 'amount', label: '金额', group: '', count: 0 }]}
      disabled={disabled}
      onAdd={onAdd}
      onRemove={onRemove}
    />
  );
  const view = render(draw(false));
  const trigger = screen.getByRole('button', { name: '添加筛选' });
  fireEvent.click(trigger);
  const dialog = await screen.findByRole('dialog', { name: '选择筛选字段' });
  const checkbox = within(dialog).getByRole('checkbox', { name: '金额' });
  fireEvent.click(within(dialog).getByRole('button', { name: '完成' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(checkbox.isConnected).toBe(true);
  expect(screen.queryByRole('checkbox', { name: '金额' })).toBeNull();
  expect(dialog.closest('[hidden]')).not.toBeNull();

  fireEvent.click(trigger);
  expect(await screen.findByRole('dialog', { name: '选择筛选字段' })).toBe(
    dialog,
  );
  expect(within(dialog).getByRole('checkbox', { name: '金额' })).toBe(checkbox);
  view.rerender(draw(true));
  expect(checkbox.getAttribute('aria-disabled')).toBe('true');
  fireEvent.click(checkbox);
  expect(onAdd).not.toHaveBeenCalled();
  expect(onRemove).not.toHaveBeenCalled();
  fireEvent.click(within(dialog).getByRole('button', { name: '完成' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(trigger).toHaveProperty('disabled', true);
  fireEvent.click(trigger);
  expect(screen.queryByRole('dialog')).toBeNull();
  view.unmount();
  expect(checkbox.isConnected).toBe(false);
});

it('uses field checkboxes for continuous selection and removal without querying', async () => {
  const apply = vi.fn();
  const definitions = fields.map(field => ({
    ...field,
    group: field.type === 'array' ? undefined : '订单信息',
  }));
  render(
    <FilterPanel
      fields={definitions}
      defaultValue={configuration(node('GTE', 'amount', { value: 10 }))}
      onApply={apply}
    />,
  );
  const trigger = screen.getByRole('button', { name: '添加筛选' });
  fireEvent.click(trigger);
  const dialog = await screen.findByRole('dialog', { name: '选择筛选字段' });
  const picker = within(dialog);
  expect(picker.getByText('订单信息')).toBeTruthy();
  expect(picker.getByText('其他字段')).toBeTruthy();
  expect(screen.queryByRole('listbox')).toBeNull();
  const amount = picker.getByRole('checkbox', { name: '订单金额' });
  const status = picker.getByRole('checkbox', { name: '订单状态' });
  expect(amount.getAttribute('aria-checked')).toBe('true');
  expect(picker.queryByRole('button', { name: '追加订单金额条件' })).toBeNull();
  fireEvent.click(status);
  expect(status.getAttribute('aria-checked')).toBe('true');
  expect(screen.getByLabelText('订单状态值')).toBeTruthy();
  fireEvent.click(amount);
  expect(amount.getAttribute('aria-checked')).toBe('false');
  expect(screen.queryByLabelText('订单金额值')).toBeNull();
  fireEvent.click(status);
  expect(screen.queryByLabelText('订单状态值')).toBeNull();
  expect(screen.getByRole('dialog', { name: '选择筛选字段' })).toBe(dialog);
  fireEvent.click(amount);
  expect(amount.getAttribute('aria-checked')).toBe('true');
  expect(screen.getAllByLabelText('订单金额值')).toHaveLength(1);
  fireEvent.click(amount);
  expect(apply).not.toHaveBeenCalled();
  fireEvent.click(picker.getByRole('button', { name: '完成' }));
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  await waitFor(() => expect(document.activeElement).toBe(trigger));
  fireEvent.click(screen.getByRole('button', { name: /^查询$/ }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
});

it.each(['AND', 'OR', 'NOR'] as const)(
  'appends repeated %s conditions and unchecks only the current group field',
  async op => {
    const apply = vi.fn();
    const group = { AND: filter.and, OR: filter.or, NOR: filter.nor }[op];
    render(
      <FilterPanel
        fields={fields}
        defaultValue={configuration({
          ...node('AND'),
          operands: [
            node('EQ', 'amount', { value: 1 }),
            {
              ...node(op),
              operands: [
                node('EQ', 'amount', { value: 2 }),
                node('EQ', 'status', { value: 'pending' }),
              ],
            },
          ],
        })}
        onApply={apply}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '添加到组合 3' }));
    const picker = within(
      await screen.findByRole('dialog', { name: '选择筛选字段' }),
    );
    const amount = picker.getByRole('checkbox', { name: '订单金额' });
    expect(amount.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(picker.getByRole('button', { name: '追加订单金额条件' }));
    expect(screen.getAllByLabelText('订单金额值')).toHaveLength(3);
    expect(picker.getByText('2 条')).toBeTruthy();
    fireEvent.click(amount);
    expect(screen.getAllByLabelText('订单金额值')).toHaveLength(1);
    expect(amount.getAttribute('aria-checked')).toBe('false');
    expect(
      picker
        .getByRole('checkbox', { name: '订单状态' })
        .getAttribute('aria-checked'),
    ).toBe('true');
    expect(apply).not.toHaveBeenCalled();
    fireEvent.click(picker.getByRole('button', { name: '完成' }));
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
    );
    fireEvent.click(screen.getByRole('button', { name: /^查询$/ }));
    expect(apply).toHaveBeenCalledWith(
      expect.objectContaining({
        expression: filter.and([
          filter.eq('amount', 1),
          group([filter.eq('status', 'pending')]),
        ]),
      }),
    );
  },
);

it('keeps an element picker open when its last field is unchecked and reselected', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration({
        ...node('ELEMENT_MATCH', 'items'),
        predicate: node('EQ', 'quantity', { value: 1 }),
      })}
      onApply={apply}
    />,
  );
  fireEvent.click(
    screen.getByRole('button', { name: '商品明细元素内添加筛选' }),
  );
  const dialog = await screen.findByRole('dialog', { name: '选择筛选字段' });
  const picker = within(dialog);
  const quantity = picker.getByRole('checkbox', { name: '数量' });
  expect(quantity.getAttribute('aria-checked')).toBe('true');
  fireEvent.click(quantity);
  expect(screen.queryByLabelText('数量值')).toBeNull();
  expect(screen.getByRole('dialog', { name: '选择筛选字段' })).toBe(dialog);
  fireEvent.click(quantity);
  expect(screen.getAllByLabelText('数量值')).toHaveLength(1);
  fireEvent.click(picker.getByRole('button', { name: '完成' }));
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  fireEvent.change(screen.getByLabelText('数量值'), { target: { value: '2' } });
  fireEvent.click(screen.getByRole('button', { name: /^查询$/ }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({
      expression: filter.elementMatch(
        'items',
        filter.and([filter.eq('quantity', 2)]),
      ),
    }),
  );
});
