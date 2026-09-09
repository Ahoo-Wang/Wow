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
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { fields, builtinCompiler } from './fixtures/filterPanel.js';

afterEach(cleanup);

it.each(['simple', 'advanced'] as const)(
  'does not offer additions requiring a forbidden implicit AND in %s mode',
  async mode => {
    const apply = vi.fn();
    render(
      <FilterPanel
        fields={fields}
        defaultValue={configuration(node('EQ', 'amount', { value: 10 }), mode)}

        allowedOperators={[Op.EQ, Op.OR, Op.ID]}
        onApply={apply}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
    const picker = within(
      await screen.findByRole('dialog', { name: '选择筛选字段' }),
    );
    expect(picker.getByRole('checkbox', { name: '订单金额' })).toBeTruthy();
    expect(picker.queryByRole('checkbox', { name: '订单状态' })).toBeNull();
    expect(
      picker.queryByRole('button', { name: '追加订单金额条件' }),
    ).toBeNull();
    expect(picker.queryByRole('button', { name: '记录标识' })).toBeNull();
    if (mode === 'advanced')
      expect(
        screen.getByRole('button', { name: '添加逻辑分组' }),
      ).toHaveProperty('disabled', true);
    fireEvent.click(picker.getByRole('checkbox', { name: '订单金额' }));
    fireEvent.click(picker.getByRole('checkbox', { name: '订单状态' }));
    fireEvent.click(picker.getByRole('button', { name: '完成' }));
    fireEvent.change(screen.getByRole('textbox', { name: '订单状态值' }), {
      target: { value: 'paid' },
    });
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(apply).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ expression: filter.eq('status', 'paid') }),
    );
  },
);

it('allows adding to an existing permitted OR without creating AND', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration({
        ...node('OR'),
        operands: [node('EQ', 'amount', { value: 10 })],
      })}
      allowedOperators={[Op.EQ, Op.OR]}
      onApply={apply}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
  const picker = within(
    await screen.findByRole('dialog', { name: '选择筛选字段' }),
  );
  fireEvent.click(picker.getByRole('checkbox', { name: '订单状态' }));
  fireEvent.click(picker.getByRole('button', { name: '完成' }));
  fireEvent.change(screen.getByRole('textbox', { name: '订单状态值' }), {
    target: { value: 'paid' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      expression: filter.or([
        filter.eq('amount', 10),
        filter.eq('status', 'paid'),
      ]),
    }),
  );
});

it('blocks implicit AND inside an element predicate while retaining its field removal control', async () => {
  render(
    <FilterPanel
      fields={[
        {
          field: 'items',
          label: '条目',
          type: 'array',
          fields: [
            { field: 'quantity', label: '数量', type: 'number' },
            { field: 'price', label: '单价', type: 'number' },
          ],
        },
      ]}
      defaultValue={configuration({
        ...node('ELEMENT_MATCH', 'items'),
        predicate: node('EQ', 'quantity', { value: 1 }),
      })}
      allowedOperators={[Op.ELEMENT_MATCH, Op.EQ]}
      onApply={() => {}}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '条目元素内添加筛选' }));
  const picker = within(
    await screen.findByRole('dialog', { name: '选择筛选字段' }),
  );
  expect(picker.getByRole('checkbox', { name: '数量' })).toBeTruthy();
  expect(picker.queryByRole('checkbox', { name: '单价' })).toBeNull();
});

it.each([true, false])(
  'keeps a crashed editor fallback read-only when disabled (initial=%s)',
  initialDisabled => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const changed = vi.fn();
    function Broken(): never {
      throw new Error('编辑器失败');
    }
    const draw = (disabled: boolean) => (
      <FilterPanel
        fields={[{ ...fields[0], editor: { name: 'broken' } }]}
        defaultValue={configuration(
          node('EQ', 'amount', { value: 10 }, { name: 'broken' }),
        )}
        onApply={() => {}}
        onChange={changed}
        disabled={disabled}
        extensions={{
          filters: {
            broken: {
              ...builtinCompiler,
              component: Broken,
              modes: ['simple', 'advanced'],
            },
          },
        }}
      />
    );
    const view = render(draw(initialDisabled));
    if (!initialDisabled) view.rerender(draw(true));
    const fallback = screen.getByRole('button', { name: '使用内置编辑器' });
    expect(fallback).toHaveProperty('disabled', true);
    fireEvent.click(fallback);
    fireEvent.click(screen.getByRole('button', { name: '清空条件' }));
    fireEvent.click(screen.getByRole('button', { name: '删除订单金额条件' }));
    expect(changed).not.toHaveBeenCalled();
    expect(screen.queryByRole('textbox', { name: '订单金额值' })).toBeNull();
    view.rerender(draw(false));
    fireEvent.click(screen.getByRole('button', { name: '使用内置编辑器' }));
    expect(screen.getByRole('textbox', { name: '订单金额值' })).toHaveProperty(
      'value',
      '10',
    );
    expect(changed).toHaveBeenCalledTimes(1);
  },
);
