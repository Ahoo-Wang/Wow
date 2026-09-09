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
import { useLayoutEffect, useRef } from 'react';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type { FilterComponentProps } from '../src/filter/filterReactTypes.js';
import type { FilterOptionSource } from '../src/filter/filterOptionSource.js';
import { builtinCompiler, fields, select } from './fixtures/filterPanel.js';

afterEach(cleanup);

function AmountEditor({
  props,
  operator,
  onChange,
  onOperatorChange,
  onClear,
}: FilterComponentProps) {
  return (
    <>
      <input
        aria-label="自定义金额"
        defaultValue={String(props.value ?? '')}
        onChange={event => onChange({ value: Number(event.target.value) })}
      />
      <button onClick={() => onOperatorChange(operator)}>保持当前操作</button>
      <button onClick={onClear}>清空自定义金额</button>
    </>
  );
}
const amountField = { ...fields[0], editor: { name: 'amount' } };
const amountEditor = {
  ...builtinCompiler,
  component: AmountEditor,
  render: 'filter' as const,
  modes: ['simple', 'advanced'] as const,
};

it('rejects a non-serializable numeric editor output without replacing its previous value and recovers on valid input', () => {
  const apply = vi.fn(),
    changed = vi.fn();
  render(
    <FilterPanel
      fields={[amountField]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 10 }, { name: 'amount' }),
      )}
      onApply={apply}
      onChange={changed}
      extensions={{ filters: { amount: amountEditor } }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '保持当前操作' }));
  expect(changed).not.toHaveBeenCalled();
  const input = screen.getByRole('textbox', { name: '自定义金额' });
  fireEvent.change(input, { target: { value: 'not-a-number' } });
  const error = screen.getByRole('alert').textContent;
  expect(error).toBeTruthy();
  expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
    'disabled',
    true,
  );
  fireEvent.change(input, { target: { value: 'still-not-a-number' } });
  expect(screen.getAllByRole('alert').map(alert => alert.textContent)).toEqual([
    error,
  ]);
  expect(changed).not.toHaveBeenCalled();
  expect(apply).not.toHaveBeenCalled();
  fireEvent.change(input, { target: { value: '25' } });
  expect(screen.queryByRole('alert')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ expression: filter.eq('amount', 25) }),
  );
});

it.each(['清空自定义金额', '清空条件'])(
  'preserves the draft when %s fails and recovers after editing',
  label => {
    const apply = vi.fn(),
      changed = vi.fn();
    render(
      <FilterPanel
        fields={[amountField]}
        defaultValue={configuration(
          node('EQ', 'amount', { value: -10 }, { name: 'amount' }),
        )}
        onApply={apply}
        onChange={changed}
        extensions={{
          filters: {
            amount: {
              ...amountEditor,
              clear: props => {
                if (Number(props.value) < 0)
                  throw new Error('负数金额无法清空');
                return {};
              },
            },
          },
        }}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: label }));
    expect(screen.getByRole('alert').textContent).toBe('负数金额无法清空');
    expect(screen.getByRole('textbox', { name: '自定义金额' })).toHaveProperty(
      'value',
      '-10',
    );
    expect(changed).not.toHaveBeenCalled();
    expect(apply).not.toHaveBeenCalled();
    fireEvent.change(screen.getByRole('textbox', { name: '自定义金额' }), {
      target: { value: '10' },
    });
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: label }));
    expect(screen.getByRole('textbox', { name: '自定义金额' })).toHaveProperty(
      'value',
      '',
    );
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(apply).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ expression: filter.matchAll() }),
    );
  },
);

it('removes an entire nested logical group without retaining its child criteria', () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration({
        ...node('AND'),
        operands: [
          node('EQ', 'amount', { value: 10 }),
          {
            ...node('OR'),
            operands: [node('EQ', 'status', { value: 'paid' })],
          },
        ],
      })}
      onApply={apply}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '删除满足任一条件条件' }));
  expect(screen.queryByLabelText('订单状态值')).toBeNull();
  expect(screen.getByLabelText('订单金额值')).toHaveProperty('value', '10');
  expect(apply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      expression: filter.and([filter.eq('amount', 10)]),
    }),
  );
});

it('changes and removes a root-only condition through its own operator and delete controls', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('MATCH_NONE'))}
      onApply={apply}
    />,
  );
  await select('特殊条件类型', '记录标识');
  fireEvent.change(screen.getByRole('textbox', { name: '记录标识值' }), {
    target: { value: 'order-1' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: { op: Op.ID, value: 'order-1' } }),
  );
  fireEvent.click(screen.getByRole('button', { name: '删除记录标识条件' }));
  expect(screen.queryByRole('combobox', { name: '特殊条件类型' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
});

it.each([{ pageSize: 0 }, { debounceMs: -1 }])(
  'rejects out-of-range remote options %o before any source request',
  options => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const source: FilterOptionSource = {
      search: vi.fn(async () => ({ list: [], nextCursor: null })),
      resolve: vi.fn(async () => ({ list: [], missing: [] })),
    };
    const apply = vi.fn();
    render(
      <FilterPanel
        fields={[
          {
            ...fields[0],
            editor: {
              name: 'remote-select',
              options: { source: 'customers', ...options },
            },
          },
        ]}
        defaultValue={configuration({
          id: 'remote',
          field: 'amount',
          operator: Op.EQ,
          component: {
            name: 'remote-select',
            options: { source: 'customers', ...options },
          },
          props: {},
        })}

        onApply={apply}
        extensions={{ optionSources: { customers: source } }}
      />,
    );
    expect(screen.getByRole('alert').textContent).toContain(
      '远程候选的分页大小或防抖时间无效',
    );
    expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
      'disabled',
      true,
    );
    expect(source.search).not.toHaveBeenCalled();
    expect(source.resolve).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '使用内置编辑器' }));
    expect(screen.getByRole('textbox', { name: '订单金额值' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(apply).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ expression: filter.matchAll() }),
    );
  },
);

it('adds only allowed fields, root conditions and logical groups from the advanced picker', async () => {
  const apply = vi.fn(),
    changed = vi.fn();
  render(
    <FilterPanel
      fields={fields.map(field =>
        field.field === 'items'
          ? { ...field, operators: [Op.ELEMENT_MATCH] }
          : field,
      )}
      defaultValue={configuration(node('MATCH_ALL'), 'advanced')}

      onApply={apply}
      onChange={changed}
      allowedOperators={[Op.EQ, Op.AND, Op.MATCH_NONE]}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
  const picker = within(
    await screen.findByRole('dialog', { name: '选择筛选字段' }),
  );
  expect(picker.queryByRole('checkbox', { name: '商品明细' })).toBeNull();
  fireEvent.click(picker.getByRole('checkbox', { name: '订单金额' }));
  fireEvent.click(picker.getByRole('button', { name: '不匹配记录' }));
  fireEvent.click(picker.getByRole('button', { name: '完成' }));
  fireEvent.change(screen.getByRole('textbox', { name: '订单金额值' }), {
    target: { value: '15' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      expression: filter.and([filter.eq('amount', 15), filter.matchNone()]),
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '添加逻辑分组' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: /^AND/ }));
  expect(changed.mock.lastCall![0].root).toMatchObject({
    operator: Op.AND,
    operands: [
      { field: 'amount' },
      { operator: Op.MATCH_NONE },
      { operator: Op.AND, operands: [] },
    ],
  });
  expect(apply).toHaveBeenCalledTimes(1);
});

it('does not query when Enter belongs to a checkbox in a custom editor', () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'check' } }]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 10 }, { name: 'check' }),
      )}
      onApply={apply}
      extensions={{
        filters: {
          check: {
            ...builtinCompiler,
            modes: ['simple'],
            component: () => <input type="checkbox" aria-label="启用预览" />,
          },
        },
      }}
    />,
  );
  fireEvent.keyDown(screen.getByRole('checkbox', { name: '启用预览' }), {
    key: 'Enter',
  });
  expect(apply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ expression: filter.eq('amount', 10) }),
  );
});

it('preserves the day count and relative options when switching between day operators', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={[{ field: 'created', label: '创建', type: 'datetime' }]}
      defaultValue={configuration({
        id: crypto.randomUUID(),
        operator: Op.RECENT_DAYS,
        field: 'created',
        component: { name: 'builtin' },
        props: { days: 3, zoneId: 'UTC' },
      })}
      onApply={apply}
      timeZone="UTC"
    />,
  );
  await select('创建操作', '早于天数');
  expect(screen.getByRole('textbox', { name: '创建天数' })).toHaveProperty(
    'value',
    '3',
  );
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      expression: {
        op: Op.EARLIER_DAYS,
        field: 'created',
        days: 3,
        zoneId: 'UTC',
      },
    }),
  );
});

it('keeps the draft available when a query handler throws without a message and allows retry', () => {
  const failure: unknown = '';
  const apply = vi.fn().mockImplementationOnce(() => {
    throw failure;
  });
  render(
    <FilterPanel
      defaultValue={configuration({
        id: 'amount',
        operator: Op.EQ,
        field: 'amount',
        component: { name: 'builtin' },
        props: { value: 1 },
      })}
      fields={[{ field: 'amount', label: '金额', type: 'number' }]}
      onApply={apply}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(screen.getByRole('alert').textContent).toContain('筛选器处理失败。');
  expect(
    (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement).value,
  ).toBe('1');
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledTimes(2);
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.eq('amount', 1) }),
  );
  expect(screen.queryByRole('alert')).toBeNull();
});

it.each([Op.MATCH_ALL, Op.MATCH_NONE])(
  'restricts a loaded %s element predicate to element-safe root operators',
  async op => {
    const apply = vi.fn();
    render(
      <FilterPanel
        fields={fields}
        defaultValue={configuration({
          ...node('ELEMENT_MATCH', 'items'),
          predicate: {
            id: crypto.randomUUID(),
            operator: op,
            component: { name: 'builtin' },
            props: {},
          },
        })}
        onApply={apply}
      />,
    );
    fireEvent.click(screen.getByRole('combobox', { name: '特殊条件类型' }));
    expect(
      await screen.findByRole('option', { name: '全部记录' }),
    ).toBeTruthy();
    expect(screen.getByRole('option', { name: '不匹配记录' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: '记录标识' })).toBeNull();
    expect(screen.queryByRole('option', { name: '搜索' })).toBeNull();
    const next = screen.getByRole('option', {
      name: op === Op.MATCH_ALL ? '不匹配记录' : '全部记录',
    });
    fireEvent.pointerDown(next, { pointerType: 'mouse' });
    fireEvent.click(next);
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(apply).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        expression: filter.elementMatch('items', {
          op: op === Op.MATCH_ALL ? Op.MATCH_NONE : Op.MATCH_ALL,
        }),
      }),
    );
  },
);

it('clears incompatible custom properties when recovering a crashed renderer with the builtin editor', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const apply = vi.fn(),
    changed = vi.fn();
  function Broken(): never {
    throw new Error('金额组件崩溃');
  }
  render(
    <FilterPanel
      fields={[amountField]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 'custom-value' }, { name: 'amount' }),
      )}
      onApply={apply}
      onChange={changed}
      extensions={{
        filters: {
          amount: {
            ...amountEditor,
            component: Broken,
            compile: () => filter.eq('amount', 10),
          },
        },
      }}
    />,
  );
  expect(screen.getByRole('alert').textContent).toContain('金额组件崩溃');
  fireEvent.click(screen.getByRole('button', { name: '使用内置编辑器' }));
  expect(screen.getByRole('textbox', { name: '订单金额值' })).toHaveProperty(
    'value',
    '',
  );
  expect(changed.mock.lastCall![0].root).toMatchObject({
    field: 'amount',
    operator: Op.EQ,
    component: { name: 'builtin' },
  });
  expect(changed.mock.lastCall![0].root.props.value).toBeUndefined();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
});

it('keeps repeated local validity errors blocking until the editor completes its value', () => {
  const apply = vi.fn(),
    validity = vi.fn();
  function DraftAmount({ onChange, onValidityChange }: FilterComponentProps) {
    return (
      <input
        aria-label="金额暂存"
        onChange={event => {
          const text = event.target.value;
          if (/^[0-9]+$/.test(text)) {
            onChange({ value: Number(text) });
            onValidityChange(true);
          } else onValidityChange(false, '金额输入尚未完成');
        }}
      />
    );
  }
  render(
    <FilterPanel
      fields={[amountField]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 10 }, { name: 'amount' }),
      )}
      onApply={apply}
      onValidityChange={validity}
      extensions={{
        filters: { amount: { ...amountEditor, component: DraftAmount } },
      }}
    />,
  );
  const input = screen.getByRole('textbox', { name: '金额暂存' });
  fireEvent.change(input, { target: { value: '-' } });
  fireEvent.change(input, { target: { value: '--' } });
  expect(screen.getAllByRole('alert').map(alert => alert.textContent)).toEqual([
    '金额输入尚未完成',
  ]);
  expect(validity.mock.calls.map(([valid]) => valid)).toEqual([true, false]);
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).not.toHaveBeenCalled();
  fireEvent.change(input, { target: { value: '25' } });
  expect(validity).toHaveBeenLastCalledWith(true);
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ expression: filter.eq('amount', 25) }),
  );
});

it('preserves a specific editor-output error when an operator change requires a different value shape', () => {
  const apply = vi.fn();
  function RangeAmount(props: FilterComponentProps) {
    return (
      <>
        <AmountEditor {...props} />
        <button onClick={() => props.onOperatorChange(Op.BETWEEN)}>
          改用范围
        </button>
        <button
          onClick={() => props.onChange({ lowerBound: 1, upperBound: 5 })}
        >
          设置范围
        </button>
      </>
    );
  }
  render(
    <FilterPanel
      fields={[amountField]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 10 }, { name: 'amount' }),
      )}
      onApply={apply}
      extensions={{
        filters: { amount: { ...amountEditor, component: RangeAmount } },
      }}
    />,
  );
  fireEvent.change(screen.getByRole('textbox', { name: '自定义金额' }), {
    target: { value: 'NaN' },
  });
  const error = screen.getByRole('alert').textContent;
  expect(error).toContain('JSON');
  fireEvent.click(screen.getByRole('button', { name: '改用范围' }));
  expect(screen.getByRole('alert').textContent).toContain(error);
  expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
    'disabled',
    true,
  );
  fireEvent.click(screen.getByRole('button', { name: '设置范围' }));
  expect(screen.queryByRole('alert')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      expression: {
        op: Op.BETWEEN,
        field: 'amount',
        lowerBound: 1,
        upperBound: 5,
      },
    }),
  );
});

it('rejects an asynchronous editor result after its controlled node is rebound to another field', async () => {
  let finish!: () => void;
  const pending = new Promise<void>(resolve => {
    finish = resolve;
  });
  const changed = vi.fn(),
    apply = vi.fn();
  function AsyncAmount(props: FilterComponentProps) {
    return (
      <button
        onClick={async () => {
          await pending;
          props.onChange({ value: 999 });
          props.onValidityChange(false, '旧字段输入错误');
          props.onClear?.();
          props.onOperatorChange(Op.NE);
        }}
      >
        读取{props.field?.label}
      </button>
    );
  }
  const definitions = [
    amountField,
    {
      field: 'total',
      label: '合计',
      type: 'number' as const,
      editor: { name: 'amount' },
    },
  ];
  const extensions = {
    filters: { amount: { ...amountEditor, component: AsyncAmount } },
  };
  const panel = (field: string) => (
    <FilterPanel
      fields={definitions}
      extensions={extensions}

      value={configuration({
        id: 'same-id',
        field,
        operator: Op.EQ,
        component: { name: 'amount' },
        props: { value: 10 },
      })}
      onChange={changed}
      onApply={apply}
    />
  );
  const view = render(panel('amount'));
  fireEvent.click(screen.getByRole('button', { name: '读取订单金额' }));
  view.rerender(panel('total'));
  await act(async () => {
    finish();
    await pending;
  });
  expect(changed).not.toHaveBeenCalled();
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByRole('button', { name: '读取合计' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ expression: filter.eq('total', 10) }),
  );
});

it('keeps a new output error when a replacement component recovers from the previous render failure', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const apply = vi.fn();
  function Broken(): never {
    throw new Error('先前渲染失败');
  }
  function Replacement({ onChange }: FilterComponentProps) {
    const initialized = useRef(false);
    useLayoutEffect(() => {
      if (!initialized.current) {
        initialized.current = true;
        onChange({ value: NaN });
      }
    }, [onChange]);
    return (
      <button onClick={() => onChange({ value: 25 })}>修正初始化金额</button>
    );
  }
  const panel = (component: typeof Broken | typeof Replacement) => (
    <FilterPanel
      fields={[amountField]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 10 }, { name: 'amount' }),
      )}
      onApply={apply}
      extensions={{ filters: { amount: { ...amountEditor, component } } }}
    />
  );
  const view = render(panel(Broken));
  expect(screen.getByRole('alert').textContent).toBe('先前渲染失败');
  view.rerender(panel(Replacement));
  expect(screen.getByRole('alert').textContent).toContain('JSON');
  expect(screen.getByRole('alert').textContent).not.toContain('先前渲染失败');
  expect(screen.getByRole('button', { name: '查询' })).toHaveProperty(
    'disabled',
    true,
  );
  fireEvent.click(screen.getByRole('button', { name: '修正初始化金额' }));
  expect(screen.queryByRole('alert')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ expression: filter.eq('amount', 25) }),
  );
});
