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
import { useEffect } from 'react';
import type { FilterComponentProps } from '../src/filter/filterReactTypes.js';
import { fields, select, builtinCompiler } from './fixtures/filterPanel.js';

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

it('switches element conditions to simple mode without changing their tree', async () => {
  const apply = vi.fn(),
    change = vi.fn();
  const root = {
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
  };
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(root, 'advanced')}
      onApply={apply}
      onChange={change}
    />,
  );
  await select('筛选模式', '简单');
  expect(change).toHaveBeenLastCalledWith({ mode: 'simple', root });
  expect(screen.queryByRole('combobox', { name: '组合方式' })).toBeNull();
  expect(screen.queryByRole('button', { name: /添加逻辑分组/ })).toBeNull();
  expect(apply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenCalledWith({
    configuration: { mode: 'simple', root },
    expression: filter.and([
      filter.eq('amount', 10),
      filter.elementMatch('items', filter.and([filter.eq('quantity', 2)])),
    ]),
  });
  await select('筛选模式', '高级');
  expect(change).toHaveBeenLastCalledWith({ mode: 'advanced', root });
});

it.each([false, true])(
  'adds and completes a simple element scope (element-only: %s)',
  async onlyElement => {
    const apply = vi.fn(),
      change = vi.fn();
    const definitions = fields.map(field =>
      field.field === 'items' && onlyElement
        ? { ...field, operators: [node('ELEMENT_MATCH').operator] }
        : field,
    );
    render(
      <FilterPanel fields={definitions} onApply={apply} onChange={change} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
    fireEvent.click(screen.getByRole('checkbox', { name: '商品明细' }));
    fireEvent.click(screen.getByRole('button', { name: '完成' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    if (!onlyElement) await select('商品明细操作', '同一元素满足');
    expect(change.mock.lastCall?.[0].mode).toBe('simple');
    expect(
      screen.getByRole('group', { name: '商品明细元素条件' }),
    ).toBeTruthy();
    expect(screen.queryByRole('combobox', { name: '组合方式' })).toBeNull();
    expect(
      screen
        .getByRole('button', { name: '查询', exact: true })
        .hasAttribute('disabled'),
    ).toBe(true);
    fireEvent.click(
      screen.getByRole('button', { name: '商品明细元素内添加筛选' }),
    );
    fireEvent.click(screen.getByRole('checkbox', { name: '数量' }));
    expect(
      screen
        .getByRole('checkbox', { name: '数量' })
        .getAttribute('aria-checked'),
    ).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: '完成' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    fireEvent.change(screen.getByLabelText('数量值'), {
      target: { value: '2' },
    });
    expect(apply).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
    expect(apply.mock.lastCall?.[0].expression).toEqual(
      filter.elementMatch('items', filter.and([filter.eq('quantity', 2)])),
    );
    fireEvent.click(screen.getByRole('button', { name: '删除数量条件' }));
    expect(screen.getByRole('alert').textContent).toContain('至少需要一个条件');
    fireEvent.click(
      screen.getByRole('button', { name: '商品明细元素内添加筛选' }),
    );
    fireEvent.click(screen.getByRole('checkbox', { name: '数量' }));
    expect(change.mock.lastCall?.[0].root.predicate.operands).toHaveLength(1);
  },
);

it('creates a single element predicate when AND is not allowed', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      allowedOperators={[node('ELEMENT_MATCH').operator, node('EQ').operator]}
      onApply={apply}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '商品明细' }));
  fireEvent.click(screen.getByRole('button', { name: '完成' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await select('商品明细操作', '同一元素满足');
  fireEvent.click(
    screen.getByRole('button', { name: '商品明细元素内添加筛选' }),
  );
  fireEvent.click(screen.getByRole('checkbox', { name: '数量' }));
  fireEvent.click(screen.getByRole('button', { name: '完成' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  fireEvent.change(screen.getByLabelText('数量值'), { target: { value: '2' } });
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply.mock.lastCall?.[0].expression).toEqual(
    filter.elementMatch('items', filter.eq('quantity', 2)),
  );
});

it('clears invalid custom-editor state when switching to an element container', async () => {
  function InvalidEditor({ onValidityChange }: FilterComponentProps) {
    useEffect(() => {
      onValidityChange(false, '旧编辑器错误');
    }, [onValidityChange]);
    return null;
  }
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('EQ', 'items', {}, { name: 'custom' }))}
      extensions={{
        filters: {
          custom: {
            component: InvalidEditor,
            modes: ['simple', 'advanced'],
            ...builtinCompiler,
          },
        },
      }}
      onApply={apply}
    />,
  );
  expect(screen.getByRole('alert').textContent).toContain('旧编辑器错误');
  await select('商品明细操作', '同一元素满足');
  fireEvent.click(
    screen.getByRole('button', { name: '商品明细元素内添加筛选' }),
  );
  fireEvent.click(screen.getByRole('checkbox', { name: '数量' }));
  fireEvent.click(screen.getByRole('button', { name: '完成' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  fireEvent.change(screen.getByLabelText('数量值'), { target: { value: '2' } });
  expect(screen.queryByRole('alert')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenCalledTimes(1);
});
