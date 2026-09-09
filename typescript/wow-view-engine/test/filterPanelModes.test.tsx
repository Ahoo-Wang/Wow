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
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { fields, select } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('preserves loaded AND duplicates in advanced mode until simple mode can represent them', async () => {
  const apply = vi.fn();
  const value = filter.and([filter.gte('amount', 1), filter.lte('amount', 2)]);
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration({
        ...node('AND'),
        operands: [
          node('GTE', 'amount', { value: 1 }),
          node('LTE', 'amount', { value: 2 }),
        ],
      })}
      onApply={apply}
    />,
  );
  expect(screen.getAllByLabelText('订单金额值')).toHaveLength(2);
  expect(
    screen.getByRole('combobox', { name: '筛选模式' }).textContent,
  ).toContain('高级');
  expect(screen.queryByRole('alert')).toBeNull();
  fireEvent.click(screen.getByRole('combobox', { name: '筛选模式' }));
  expect(
    (await screen.findByRole('option', { name: '简单' })).getAttribute(
      'aria-disabled',
    ),
  ).toBe('true');
  fireEvent.keyDown(screen.getByRole('listbox'), { key: 'Escape' });
  fireEvent.click(screen.getByRole('button', { name: /^查询$/ }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({ expression: value }),
  );
  fireEvent.click(
    screen.getAllByRole('button', { name: '删除订单金额条件' })[1],
  );
  await select('筛选模式', '简单');
  expect(
    screen.getByRole('combobox', { name: '筛选模式' }).textContent,
  ).toContain('简单');
  fireEvent.click(screen.getByRole('button', { name: /^查询$/ }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({
      expression: filter.and([filter.gte('amount', 1)]),
    }),
  );
});

it('allows switching repeated-field groups from OR to AND without dropping rules', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration({
        ...node('OR'),
        operands: [
          node('EQ', 'amount', { value: 1 }),
          node('GTE', 'amount', { value: 2 }),
        ],
      })}
      onApply={apply}
    />,
  );
  fireEvent.click(screen.getByRole('combobox', { name: '组合方式' }));
  const and = await screen.findByRole('option', { name: '满足全部条件' });
  expect(and.getAttribute('aria-disabled')).not.toBe('true');
  fireEvent.pointerDown(and, { pointerType: 'mouse' });
  fireEvent.click(and);
  expect(apply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: /^查询$/ }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({
      expression: filter.and([filter.eq('amount', 1), filter.gte('amount', 2)]),
    }),
  );
});

it('keeps mode guards in the panel when its toolbar is composed externally', () => {
  const apply = vi.fn();
  const modeChange = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(
        node('BETWEEN', 'amount', { lowerBound: 1, upperBound: 10 }),
      )}
      onApply={apply}
      onChange={next => modeChange(next.mode)}
      renderToolbar={({ mode, options, onModeChange }) => (
        <div>
          <span>{mode}</span>
          <button onClick={() => onModeChange('advanced')}>高级模式</button>
          <button
            aria-disabled={options[0].disabled}
            onClick={() => onModeChange('simple')}
          >
            简单模式
          </button>
        </div>
      )}
    />,
  );
  expect(screen.queryByRole('combobox', { name: '筛选模式' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '高级模式' }));
  const upper = screen.getByLabelText('订单金额上限');
  fireEvent.change(upper, { target: { value: '' } });
  const simple = screen.getByRole('button', { name: '简单模式' });
  expect(simple.getAttribute('aria-disabled')).toBe('true');
  fireEvent.click(simple);
  expect(modeChange).toHaveBeenLastCalledWith('advanced');
  fireEvent.change(upper, { target: { value: '30' } });
  fireEvent.click(simple);
  expect(modeChange).toHaveBeenLastCalledWith('simple');
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.between('amount', 1, 30) }),
  );
});

it('adds fields without rebinding and changes modes without querying', async () => {
  const apply = vi.fn(),
    mode = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('MATCH_ALL'))}
      onApply={apply}
      onChange={next => mode(next.mode)}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '订单金额' }));
  expect(screen.getByLabelText('订单金额值')).toBeTruthy();
  expect(screen.queryByRole('combobox', { name: '字段' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '完成' }));
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '选择筛选字段' })).toBeNull(),
  );
  await select('筛选模式', '高级');
  expect(mode).toHaveBeenCalledWith('advanced');
  await select('筛选模式', '简单');
  expect(mode).toHaveBeenCalledWith('simple');
  expect(apply).not.toHaveBeenCalled();
});

it('keeps simple filters free of condition menus and applies the original order', () => {
  const apply = vi.fn();
  const value = filter.and([
    filter.eq('amount', 10),
    filter.eq('status', 'pending'),
  ]);
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration({
        ...node('AND'),
        operands: [
          node('EQ', 'amount', { value: 10 }),
          node('EQ', 'status', { value: 'pending' }),
        ],
      })}
      onApply={apply}
    />,
  );
  expect(
    screen.queryByRole('button', { name: /条件操作|移动.*条件/ }),
  ).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({ expression: value }),
  );
});

it('does not offer destructive simple-mode conversion for element conditions', async () => {
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration({
        ...node('AND'),
        operands: [
          node('EQ', 'amount', { value: 10 }),
          {
            ...node('ELEMENT_MATCH', 'items'),
            predicate: {
              ...node('AND'),
              operands: [node('EQ', 'quantity', { value: 2 })],
            },
          },
        ],
      })}
      onApply={() => {}}
    />,
  );
  fireEvent.click(screen.getByRole('combobox', { name: '筛选模式' }));
  expect(
    (await screen.findByRole('option', { name: '简单' })).getAttribute(
      'aria-disabled',
    ),
  ).toBe('true');
  fireEvent.keyDown(screen.getByRole('option', { name: '高级', exact: true }), {
    key: 'Escape',
  });
});
