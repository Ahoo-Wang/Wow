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

import { keyboardOrder } from './fixtures/listOrder.js';
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import {
  aggregation,
  SortDirection,
  AggregationFunction,
  FilterOperator,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import type { AnalysisRegistration } from '../src/analysis/analysisReactTypes.js';
import { cloneSnapshot } from '../src/lib/types.js';
import type { AnalysisComponentConfig } from '../src/analysis/analysisModel.js';
import { AnalysisEditor } from '../src/analysis/AnalysisEditor.js';
import { compileAnalysis } from '../src/analysis/analysisCompiler.js';
import type {
  AnalysisViewConfig,
  AnalysisCompileContext,
} from '../src/analysis/analysisModel.js';
afterEach(cleanup);
// Interaction tests open the appropriate editor before addressing a control.
function control(...args: Parameters<typeof screen.getByRole>) {
  const name = args[1]?.name;
  if (typeof name === 'string') {
    const item = name.match(/^(?:删除|修复)?(维度|指标) (\d+)/);
    if (item) {
      const trigger = screen.getByRole('button', {
        name: `编辑${item[1]} ${item[2]}`,
      });
      if (trigger.getAttribute('aria-expanded') !== 'true')
        fireEvent.click(trigger);
    }
    if (['最多结果行数', '添加排序', '清除排序'].includes(name)) {
      const trigger = screen.getByText('高级设置', { exact: true });
      if (!trigger.closest('details')?.open) fireEvent.click(trigger);
    }
  }
  return screen.getByRole(...args);
}

const context: AnalysisCompileContext = {
  fields: [],
  capability: { fields: [], count: true },
};
const initial: AnalysisViewConfig = {
  filters: {
    mode: 'simple',
    root: {
      id: 'all',
      component: { name: 'builtin' },
      operator: FilterOperator.MATCH_ALL,
      props: {},
    },
  },
  dimensions: [],
  metrics: [
    {
      id: 'count',
      component: { name: 'count' },
      alias: 'orders',
      title: '订单数',
      props: {},
    },
  ],
  sort: [],
  limit: 100,
  presentation: { layout: 'table', columns: [] },
};
it('edits labels with stable alias and retains incomplete limit across remount', () => {
  const changed = vi.fn();
  function Example() {
    const [value, setValue] = useState(initial);
    return (
      <AnalysisEditor
        value={value}
        context={context}
        onChange={next => {
          changed(next);
          setValue(next);
        }}
      />
    );
  }
  const view = render(<Example />);
  fireEvent.change(control('textbox', { name: '指标 1 名称' }), {
    target: { value: '新名称' },
  });
  expect(changed.mock.lastCall?.[0].metrics[0].alias).toBe('orders');
  fireEvent.change(control('textbox', { name: '最多结果行数' }), {
    target: { value: '1e' },
  });
  const draft = changed.mock.lastCall![0];
  expect(compileAnalysis(draft, context).plan).toBeUndefined();
  view.unmount();
  render(<AnalysisEditor value={draft} context={context} onChange={changed} />);
  expect(
    (control('textbox', { name: '最多结果行数' }) as HTMLInputElement).value,
  ).toBe('1e');
  fireEvent.change(control('textbox', { name: '最多结果行数' }), {
    target: { value: '20' },
  });
  expect(compileAnalysis(changed.mock.lastCall![0], context).errors).toEqual(
    [],
  );
});
it('retains unknown components, supports removal and respects disabled', () => {
  const changed = vi.fn();
  const value = {
    ...initial,
    metrics: [{ ...initial.metrics[0], component: { name: 'old-plugin' } }],
  };
  const view = render(
    <AnalysisEditor value={value} context={context} onChange={changed} />,
  );
  fireEvent.click(screen.getByRole('button', { name: '编辑指标 1' }));
  expect(screen.getByText(/未知组件：old-plugin/)).toBeTruthy();
  fireEvent.click(control('button', { name: '删除指标 1' }));
  expect(changed.mock.lastCall![0].metrics).toEqual([]);
  view.rerender(
    <AnalysisEditor
      value={value}
      context={context}
      onChange={changed}
      disabled
    />,
  );
  expect(
    (control('button', { name: '删除指标 1' }) as HTMLButtonElement).disabled,
  ).toBe(true);
});

it('retains incomplete bucket input and reorders stable component identities', async () => {
  const changed = vi.fn();
  const scoped: AnalysisCompileContext = {
    fields: [{ field: 'amount', label: '金额', type: 'number' }],
    capability: {
      count: true,
      fields: [
        {
          field: 'amount',
          groups: [AggregationGroupType.HISTOGRAM],
          functions: [],
        },
      ],
    },
  };
  const value: AnalysisViewConfig = {
    ...initial,
    dimensions: [
      {
        id: 'bucket',
        alias: 'bucket',
        title: '金额区间',
        field: 'amount',
        component: { name: 'histogram' },
        props: { interval: 10 },
      },
    ],
    metrics: [
      initial.metrics[0],
      { ...initial.metrics[0], id: 'second', alias: 'second', title: '第二项' },
    ],
  };
  function Example() {
    const [current, setCurrent] = useState(value);
    return (
      <AnalysisEditor
        value={current}
        context={scoped}
        onChange={next => {
          changed(next);
          setCurrent(next);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.change(control('textbox', { name: '维度 1 桶宽' }), {
    target: { value: '1.' },
  });
  expect(changed.mock.lastCall![0].dimensions[0].props.interval).toBe('1.');
  expect(
    compileAnalysis(changed.mock.lastCall![0], scoped).plan,
  ).toBeUndefined();
  fireEvent.change(control('textbox', { name: '维度 1 桶宽' }), {
    target: { value: '1.5' },
  });
  expect(compileAnalysis(changed.mock.lastCall![0], scoped).errors).toEqual([]);
  await keyboardOrder(control('button', { name: '排序指标 2' }), 'ArrowUp');
  expect(
    changed.mock.lastCall![0].metrics.map((item: { id: string }) => item.id),
  ).toEqual(['second', 'count']);
  fireEvent.click(control('button', { name: '添加指标' }));
  expect(changed.mock.lastCall![0].metrics).toHaveLength(3);
  fireEvent.click(control('button', { name: '添加排序' }));
  expect(changed.mock.lastCall![0].sort).toHaveLength(1);
  fireEvent.click(control('button', { name: '清除排序' }));
  expect(changed.mock.lastCall![0].sort).toEqual([]);
});
it.each(['money', 'constructor', 'toString', '__proto__'])(
  'pairs custom editor %s with its compiler and preserves stable identity',
  name => {
    const changed = vi.fn();
    const registration: AnalysisRegistration = {
      roles: ['metric'],
      compile: value =>
        aggregation.sum(aggregation.field(value.field!), value.alias),
      component: ({ value, onChange, disabled }) => (
        <button
          disabled={disabled}
          onClick={() =>
            onChange({
              ...cloneSnapshot<AnalysisComponentConfig>(value),
              id: 'replaced',
              alias: 'replaced',
              field: 'amount',
              props: {},
            })
          }
        >
          选择自定义金额
        </button>
      ),
    };
    const scoped: AnalysisCompileContext = {
      fields: [{ field: 'amount', label: '金额', type: 'number' }],
      capability: {
        count: true,
        fields: [
          { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
        ],
      },
      compilers: { [name]: registration },
    };
    const value = {
      ...initial,
      metrics: [{ ...initial.metrics[0], component: { name } }],
    };
    render(
      <AnalysisEditor
        value={value}
        context={scoped}
        extensions={{ analysis: { [name]: registration } }}
        onChange={changed}
      />,
    );
    expect(screen.queryByText(/未知组件/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '编辑指标 1' }));
    fireEvent.click(control('button', { name: '选择自定义金额' }));
    const next = changed.mock.lastCall![0];
    expect(next.metrics[0]).toMatchObject({
      id: 'count',
      alias: 'orders',
      field: 'amount',
    });
    expect(compileAnalysis(next, scoped).plan?.query.metrics).toEqual([
      aggregation.sum(aggregation.field('amount'), 'orders'),
    ]);
  },
);
it('isolates custom render failure and allows retry and removal', () => {
  const log = vi.spyOn(console, 'error').mockImplementation(() => {});
  let fail = true;
  const registration: AnalysisRegistration = {
    roles: ['metric'],
    compile: value => aggregation.count(value.alias),
    component: () => {
      if (fail) throw new Error('broken editor');
      return <span>编辑器已恢复</span>;
    },
  };
  const changed = vi.fn();
  const value = {
    ...initial,
    metrics: [{ ...initial.metrics[0], component: { name: 'fragile' } }],
  };
  try {
    render(
      <AnalysisEditor
        value={value}
        context={context}
        extensions={{ analysis: { fragile: registration } }}
        onChange={changed}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '编辑指标 1' }));
    expect(control('alert').textContent).toContain('编辑器无法显示');
    expect(control('button', { name: '删除指标 1' })).toBeTruthy();
    fail = false;
    fireEvent.click(control('button', { name: '重试编辑器' }));
    expect(screen.getByText('编辑器已恢复')).toBeTruthy();
  } finally {
    log.mockRestore();
  }
});

it('keeps decimal zeroes while typing a fractional histogram interval', () => {
  const scoped: AnalysisCompileContext = {
    fields: [{ field: 'amount', label: '金额', type: 'number' }],
    capability: {
      count: true,
      fields: [
        {
          field: 'amount',
          groups: [AggregationGroupType.HISTOGRAM],
          functions: [],
        },
      ],
    },
  };
  let current: AnalysisViewConfig = {
    ...initial,
    dimensions: [
      {
        id: 'bucket',
        alias: 'bucket',
        title: '金额区间',
        field: 'amount',
        component: { name: 'histogram' },
        props: { interval: 1 },
      },
    ],
  };
  function Example() {
    const [value, setValue] = useState(current);
    return (
      <AnalysisEditor
        value={value}
        context={scoped}
        onChange={next => {
          current = next;
          setValue(next);
        }}
      />
    );
  }
  render(<Example />);
  const input = control('textbox', {
    name: '维度 1 桶宽',
  }) as HTMLInputElement;
  fireEvent.change(input, { target: { value: '1.0' } });
  expect(input.value).toBe('1.0');
  fireEvent.change(input, { target: { value: '1.05' } });
  expect(
    compileAnalysis(current, scoped).plan?.query.groupBy?.[0],
  ).toMatchObject({ interval: 1.05 });
});
it('retains raw expression constants across remount and explains ANY representative semantics', () => {
  const expressionContext: AnalysisCompileContext = {
    fields: [{ field: 'amount', label: '金额', type: 'number' }],
    capability: {
      count: true,
      expressions: true,
      fields: [
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.SUM],
          any: true,
          unit: 'CNY',
        },
      ],
    },
  };
  const value: AnalysisViewConfig = {
    ...initial,
    metrics: [
      {
        ...initial.metrics[0],
        component: { name: 'numeric' },
        expression: aggregation.constant(2),
        props: { function: AggregationFunction.SUM },
      },
      {
        ...initial.metrics[0],
        id: 'any',
        alias: 'sample',
        component: { name: 'any' },
        field: 'amount',
      },
    ],
  };
  const changed = vi.fn();
  const view = render(
    <AnalysisEditor
      value={value}
      context={expressionContext}
      onChange={changed}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '编辑指标 2' }));
  expect(screen.getByText(/代表值不保证固定/)).toBeTruthy();
  fireEvent.change(control('textbox', { name: '指标 1 公式 常量' }), {
    target: { value: '1e' },
  });
  const next = changed.mock.lastCall![0];
  expect(next.metrics[0].expression.value).toBe('1e');
  expect(compileAnalysis(next, expressionContext).plan).toBeUndefined();
  view.rerender(
    <AnalysisEditor
      value={next}
      context={expressionContext}
      onChange={changed}
    />,
  );
  expect(
    (
      control('textbox', {
        name: '指标 1 公式 常量',
      }) as HTMLInputElement
    ).value,
  ).toBe('1e');
});
it('switches to a declared scope while preserving root filters and recoverable metric drafts', async () => {
  const changed = vi.fn();
  const scopeContext: AnalysisCompileContext = {
    ...context,
    capability: {
      ...context.capability,
      scopes: [
        {
          id: 'lines',
          label: '订单明细',
          fields: [],
          capability: { fields: [], count: true },
          elements: [{ path: 'lines', fields: [] }],
        },
      ],
    },
  };
  render(
    <AnalysisEditor
      value={initial}
      context={scopeContext}
      onChange={changed}
    />,
  );
  fireEvent.click(control('combobox', { name: '统计对象' }));
  const option = await screen.findByRole('option', { name: '订单明细' });
  fireEvent.pointerDown(option, { pointerType: 'mouse' });
  fireEvent.click(option);
  const next = changed.mock.lastCall![0];
  expect(next.scope).toMatchObject({
    id: 'lines',
    filters: [{ root: { operator: FilterOperator.MATCH_ALL } }],
  });
  expect(next.filters).toEqual(initial.filters);
  expect(next.metrics).toEqual(initial.metrics);
});
it('selects an authorized ANY metric and renders element filters without query actions', async () => {
  const local: AnalysisCompileContext = {
    fields: [{ field: 'state', label: '状态', type: 'string' }],
    capability: {
      fields: [{ field: 'state', groups: [], functions: [], any: true }],
      count: true,
    },
  };
  const changed = vi.fn();
  function Example() {
    const [value, setValue] = useState(initial);
    return (
      <AnalysisEditor
        value={value}
        context={local}
        onChange={next => {
          setValue(next);
          changed(next);
        }}
      />
    );
  }
  const view = render(<Example />);
  async function choose(label: string, option: string) {
    fireEvent.click(control('combobox', { name: label }));
    const node = await screen.findByRole('option', {
      name: option,
      exact: true,
    });
    fireEvent.pointerDown(node, { pointerType: 'mouse' });
    fireEvent.click(node);
  }
  await choose('指标 1 类型', '代表值');
  await choose('指标 1 字段', '状态');
  expect(
    compileAnalysis(changed.mock.lastCall![0], local).plan?.query.metrics[0],
  ).toEqual(aggregation.any('state', 'orders'));
  view.unmount();
  const scoped = {
    ...local,
    capability: {
      ...local.capability,
      scopes: [
        {
          id: 'items',
          label: '明细',
          elements: [{ path: 'items', fields: local.fields }],
          fields: local.fields,
          capability: local.capability,
        },
      ],
    },
  };
  render(
    <AnalysisEditor
      value={{ ...initial, scope: { id: 'items', filters: [initial.filters] } }}
      context={scoped}
      onChange={changed}
    />,
  );
  expect(screen.getByText('第 1 层 · items 元素筛选')).toBeTruthy();
  expect(
    screen.queryByRole('button', { name: '查询', exact: true }),
  ).toBeNull();
});
it('edits binary operands without evaluating them or replacing stable metric identity', () => {
  const changed = vi.fn();
  const local: AnalysisCompileContext = {
    fields: [{ field: 'amount', label: '金额', type: 'number' }],
    capability: {
      count: true,
      expressions: true,
      fields: [
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  };
  const value: AnalysisViewConfig = {
    ...initial,
    metrics: [
      {
        ...initial.metrics[0],
        component: { name: 'numeric' },
        expression: aggregation.divide(
          aggregation.field('amount'),
          aggregation.constant(2),
        ),
        props: { function: AggregationFunction.SUM },
      },
    ],
  };
  render(<AnalysisEditor value={value} context={local} onChange={changed} />);
  fireEvent.change(control('textbox', { name: '指标 1 公式 右侧 常量' }), {
    target: { value: '0.00' },
  });
  expect(changed.mock.lastCall![0].metrics[0]).toMatchObject({
    id: 'count',
    alias: 'orders',
    expression: { right: { value: '0.00' } },
  });
  expect(
    compileAnalysis(changed.mock.lastCall![0], local).plan?.query.metrics[0],
  ).toEqual(
    aggregation.sum(
      aggregation.divide(aggregation.field('amount'), aggregation.constant(0)),
      'orders',
    ),
  );
});
it('keeps malformed persisted operands repairable', () => {
  const changed = vi.fn();
  const local = {
    ...context,
    capability: { ...context.capability, expressions: true },
  };
  const value = {
    ...initial,
    metrics: [
      {
        ...initial.metrics[0],
        component: { name: 'numeric' },
        expression: {
          ...aggregation.add(aggregation.constant(1), aggregation.constant(2)),
          left: null,
        },
        props: { function: AggregationFunction.SUM },
      },
    ],
  } as unknown as AnalysisViewConfig;
  render(<AnalysisEditor value={value} context={local} onChange={changed} />);
  fireEvent.click(control('button', { name: '修复指标 1 公式 左侧' }));
  expect(changed.mock.lastCall![0].metrics[0].expression.left).toEqual(
    aggregation.constant(0),
  );
});

it('keeps presentation mappings when query outputs are deleted', () => {
  const changed = vi.fn();
  const value: AnalysisViewConfig = {
    ...initial,
    metrics: [
      ...initial.metrics,
      { ...initial.metrics[0], id: 'other', alias: 'other', title: '其他计数' },
    ],
    presentation: {
      layout: 'metric',
      columns: [{ alias: 'orders', width: 150 }],
      metrics: ['orders'],
      orientation: 'horizontal',
    },
  };
  render(<AnalysisEditor value={value} context={context} onChange={changed} />);
  fireEvent.click(control('button', { name: '删除指标 1' }));
  const next = changed.mock.calls[0][0];
  expect(
    next.metrics.map((metric: AnalysisComponentConfig) => metric.alias),
  ).toEqual(['other']);
  expect(next.presentation).toEqual(value.presentation);
  expect(next.presentation.orientation).toBe('horizontal');
});
it('preserves old chart axes until the user explicitly repairs their mapping', () => {
  const ctx: AnalysisCompileContext = {
    fields: ['region', 'channel'].map(field => ({
      field,
      label: field,
      type: 'string',
    })),
    capability: {
      count: true,
      fields: ['region', 'channel'].map(field => ({
        field,
        groups: [AggregationGroupType.TERMS],
        functions: [],
      })),
    },
  };
  const value: AnalysisViewConfig = {
    ...initial,
    dimensions: ['region', 'channel'].map(field => ({
      id: field,
      field,
      alias: field,
      title: field,
      component: { name: 'terms' },
      props: {},
    })),
    presentation: {
      layout: 'bar',
      columns: [],
      x: 'region',
      series: 'channel',
      metrics: ['orders'],
    },
  };
  const changed = vi.fn();
  render(<AnalysisEditor value={value} context={ctx} onChange={changed} />);
  fireEvent.click(control('button', { name: '删除维度 1' }));
  const next = changed.mock.calls[0][0];
  expect(next.presentation).toEqual(value.presentation);
  expect(compileAnalysis(next, ctx).errors).toEqual([]);
});

it.each([0, 26, '1e'])(
  'describes the actual limit range and marks %s invalid',
  limit => {
    const scoped = {
      ...context,
      capability: { ...context.capability, limits: { maxLimit: 25 } },
    };
    const value = { ...initial, limit };
    const compiled = compileAnalysis(value, scoped);
    expect(
      compiled.errors.some(error => error.message.includes('1 至 25 的整数')),
    ).toBe(true);
    render(
      <AnalysisEditor
        value={value}
        context={scoped}
        onChange={vi.fn()}
        errors={compiled.errors}
      />,
    );
    const input = control('textbox', { name: '最多结果行数' });
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const hint = document.getElementById(
      input.getAttribute('aria-describedby')!,
    );
    expect(hint?.textContent).toContain('1 至 25 的整数');
    expect(
      hint!.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  },
);

it('starts with compact metric summaries and keeps edits when folded', () => {
  function Example() {
    const [value, setValue] = useState(initial);
    return (
      <AnalysisEditor value={value} context={context} onChange={setValue} />
    );
  }
  render(<Example />);
  const summary = screen.getByLabelText('编辑指标 1');
  expect(summary.getAttribute('aria-expanded')).toBe('false');
  expect(summary.textContent).toContain('订单数');
  fireEvent.click(summary);
  expect(summary.getAttribute('aria-expanded')).toBe('true');
  fireEvent.change(control('textbox', { name: '指标 1 名称' }), {
    target: { value: '有效订单' },
  });
  fireEvent.click(summary);
  expect(summary.getAttribute('aria-expanded')).toBe('false');
  expect(summary.textContent).toContain('有效订单');
  fireEvent.click(summary);
  expect(
    (control('textbox', { name: '指标 1 名称' }) as HTMLInputElement).value,
  ).toBe('有效订单');
  expect(screen.getByText('高级设置').closest('details')!.open).toBe(false);
});

it('edits a metric in a retained popover and closes overlays when the surface hides', async () => {
  function Example({ visible = true }: { visible?: boolean }) {
    const [value, setValue] = useState(initial);
    return (
      <AnalysisEditor
        value={value}
        context={context}
        onChange={setValue}
        visible={visible}
      />
    );
  }
  const view = render(<Example />);
  fireEvent.click(control('button', { name: '编辑指标 1' }));
  expect(await screen.findByRole('dialog', { name: '指标设置' })).toBeTruthy();
  fireEvent.change(control('textbox', { name: '指标 1 名称' }), {
    target: { value: '订单合计' },
  });
  fireEvent.click(control('combobox', { name: '指标 1 类型' }));
  expect(await screen.findByRole('listbox')).toBeTruthy();
  view.rerender(<Example visible={false} />);
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '指标设置' })).toBeNull(),
  );
  await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
  view.rerender(<Example />);
  fireEvent.click(control('button', { name: '编辑指标 1' }));
  expect(
    (
      (await screen.findByRole('textbox', {
        name: '指标 1 名称',
      })) as HTMLInputElement
    ).value,
  ).toBe('订单合计');
  expect(screen.queryByRole('listbox')).toBeNull();
});

it('removes the query display field and sort but retains its presentation mapping', async () => {
  const changed = vi.fn();
  render(
    <AnalysisEditor
      value={{
        ...initial,
        dimensions: [
          {
            id: 'product',
            alias: 'product',
            title: '商品',
            field: 'id',
            component: { name: 'terms' },
            props: {},
            label: { field: 'name', alias: 'product_name', title: '商品名称' },
          },
        ],
        sort: [{ alias: 'product_name', direction: SortDirection.ASC }],
        presentation: { layout: 'bar', columns: [{ alias: 'product_name' }] },
      }}
      context={{
        fields: [
          { field: 'id', label: 'ID', type: 'string' },
          { field: 'name', label: '名称', type: 'string' },
        ],
        capability: {
          count: true,
          fields: [
            {
              field: 'id',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            { field: 'name', groups: [], functions: [], any: true },
          ],
        },
      }}
      onChange={changed}
    />,
  );
  fireEvent.click(control('combobox', { name: '维度 1 显示字段' }));
  const clear = screen.getByRole('option', { name: '清空选择' });
  fireEvent.pointerDown(clear, { pointerType: 'mouse' });
  fireEvent.click(clear);
  await waitFor(() => expect(changed).toHaveBeenCalled());
  const updated = changed.mock.lastCall![0];
  expect(updated.dimensions[0].label).toBeUndefined();
  expect(updated.presentation.columns).toEqual([{ alias: 'product_name' }]);
  expect(updated.sort).toEqual([]);
  expect(updated.metrics).toEqual(initial.metrics);
});

it('edits and removes result ordering without changing measure definitions', async () => {
  const changed = vi.fn();
  function Example() {
    const [value, setValue] = useState<AnalysisViewConfig>({
      ...initial,
      dimensions: [
        {
          id: 'state',
          alias: 'state',
          title: '状态',
          component: { name: 'terms' },
          field: 'state',
          props: {},
        },
      ],
    });
    return (
      <AnalysisEditor
        value={value}
        context={{
          fields: [{ field: 'state', label: '状态', type: 'string' }],
          capability: {
            count: true,
            fields: [
              {
                field: 'state',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
            ],
          },
        }}
        onChange={next => {
          changed(next);
          setValue(next);
        }}
      />
    );
  }
  async function choose(name: string, label: string) {
    fireEvent.click(control('combobox', { name }));
    const option = await screen.findByRole('option', {
      name: label,
      exact: true,
    });
    fireEvent.pointerDown(option, { pointerType: 'mouse' });
    fireEvent.click(option);
  }
  render(<Example />);
  fireEvent.click(control('button', { name: '添加排序' }));
  await choose('排序 1 输出', '订单数');
  await choose('排序 1 方向', '降序');
  expect(changed.mock.lastCall?.[0].sort).toEqual([
    { alias: 'orders', direction: SortDirection.DESC },
  ]);
  fireEvent.click(screen.getByRole('button', { name: '删除排序 1' }));
  expect(changed.mock.lastCall?.[0].sort).toEqual([]);
  fireEvent.click(screen.getByRole('button', { name: '添加排序' }));
  fireEvent.click(screen.getByRole('button', { name: '清除排序' }));
  expect(changed.mock.lastCall?.[0].sort).toEqual([]);
  expect(changed.mock.lastCall?.[0].metrics).toEqual(initial.metrics);
});

it.each([
  { maxSort: 1, sort: [{ alias: 'state', direction: SortDirection.ASC }] },
  { maxSort: 2, sort: [{ alias: 'orders', direction: SortDirection.DESC }] },
])(
  'bounds added sorts including implicit dimensions at maxSort=$maxSort',
  ({ maxSort, sort }) => {
    const value: AnalysisViewConfig = {
      ...initial,
      dimensions: [
        {
          id: 'state',
          alias: 'state',
          title: '状态',
          component: { name: 'terms' },
          field: 'state',
          props: {},
        },
      ],
      metrics: [
        ...initial.metrics,
        {
          ...initial.metrics[0],
          id: 'other',
          alias: 'other',
          title: '其他记录数',
        },
      ],
      sort,
    };
    const limitedContext: AnalysisCompileContext = {
      fields: [{ field: 'state', label: '状态', type: 'string' }],
      capability: {
        count: true,
        fields: [
          {
            field: 'state',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
        ],
        limits: { maxSort },
      },
    };
    const changed = vi.fn();
    function Example() {
      const [draft, setDraft] = useState(value);
      return (
        <AnalysisEditor
          value={draft}
          context={limitedContext}
          onChange={next => {
            changed(next);
            setDraft(next);
          }}
        />
      );
    }
    render(<Example />);
    const add = control('button', { name: '添加排序' }) as HTMLButtonElement;
    if (maxSort === 2) {
      // Making the implicit dimension explicit consumes no additional slot.
      expect(add.disabled).toBe(false);
      fireEvent.click(add);
      expect(changed.mock.lastCall![0].sort).toEqual([
        { alias: 'orders', direction: SortDirection.DESC },
        { alias: 'state', direction: SortDirection.ASC },
      ]);
      expect(
        compileAnalysis(changed.mock.lastCall![0], limitedContext).plan?.query
          .sort,
      ).toHaveLength(2);
    }
    expect(add.disabled).toBe(true);
    changed.mockClear();
    fireEvent.click(add);
    expect(changed).not.toHaveBeenCalled();
  },
);

it('recomputes sort capacity when capability changes and excludes overflowing replacements', async () => {
  const value: AnalysisViewConfig = {
    ...initial,
    dimensions: [
      {
        id: 'state',
        alias: 'state',
        title: '状态',
        component: { name: 'terms' },
        field: 'state',
        props: {},
      },
    ],
    sort: [{ alias: 'state', direction: SortDirection.ASC }],
  };
  const limitedContext: AnalysisCompileContext = {
    fields: [{ field: 'state', label: '状态', type: 'string' }],
    capability: {
      count: true,
      fields: [
        { field: 'state', groups: [AggregationGroupType.TERMS], functions: [] },
      ],
      limits: { maxSort: 2 },
    },
  };
  const changed = vi.fn();
  const view = render(
    <AnalysisEditor
      value={value}
      context={limitedContext}
      onChange={changed}
    />,
  );
  expect(
    (control('button', { name: '添加排序' }) as HTMLButtonElement).disabled,
  ).toBe(false);
  view.rerender(
    <AnalysisEditor
      value={value}
      context={{
        ...limitedContext,
        capability: { ...limitedContext.capability, limits: { maxSort: 1 } },
      }}
      onChange={changed}
    />,
  );
  expect(
    (control('button', { name: '添加排序' }) as HTMLButtonElement).disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole('combobox', { name: '排序 1 输出' }));
  expect(
    await screen.findByRole('option', { name: '状态', exact: true }),
  ).toBeTruthy();
  expect(
    screen.queryByRole('option', { name: '订单数', exact: true }),
  ).toBeNull();
});

it('counts owned ANY display fields in the metric budget and preserves replacement/clear at capacity', async () => {
  const scoped: AnalysisCompileContext = {
    fields: [
      { field: 'id', label: '商品 ID', type: 'string' },
      { field: 'name', label: '商品名称', type: 'string' },
    ],
    capability: {
      count: true,
      limits: { maxMetrics: 1 },
      fields: [
        { field: 'id', groups: [AggregationGroupType.TERMS], functions: [] },
        { field: 'name', groups: [], functions: [], any: true },
      ],
    },
  };
  const value: AnalysisViewConfig = {
    ...initial,
    dimensions: [
      {
        id: 'product',
        alias: 'product',
        title: '商品',
        field: 'id',
        component: { name: 'terms' },
        props: {},
      },
    ],
  };
  const changed = vi.fn();
  const view = render(
    <AnalysisEditor value={value} context={scoped} onChange={changed} />,
  );
  expect(control('combobox', { name: '维度 1 显示字段' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(changed).not.toHaveBeenCalled();
  const labeled: AnalysisViewConfig = {
    ...value,
    dimensions: [
      {
        ...value.dimensions[0],
        label: { field: 'name', alias: 'product_name', title: '名称' },
      },
    ],
  };
  const expanded = {
    ...scoped,
    capability: { ...scoped.capability, limits: { maxMetrics: 2 } },
  };
  view.rerender(
    <AnalysisEditor value={labeled} context={expanded} onChange={changed} />,
  );
  expect(screen.getByRole('button', { name: '添加指标' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(control('combobox', { name: '维度 1 显示字段' })).toHaveProperty(
    'disabled',
    false,
  );
  fireEvent.click(control('combobox', { name: '维度 1 显示字段' }));
  const clear = screen.getByRole('option', { name: '清空选择' });
  fireEvent.pointerDown(clear, { pointerType: 'mouse' });
  fireEvent.click(clear);
  await waitFor(() => expect(changed).toHaveBeenCalled());
  const next = changed.mock.lastCall![0] as AnalysisViewConfig;
  view.rerender(
    <AnalysisEditor value={next} context={expanded} onChange={changed} />,
  );
  expect(screen.getByRole('button', { name: '添加指标' })).toHaveProperty(
    'disabled',
    false,
  );
  expect(compileAnalysis(next, expanded).plan?.query.metrics).toHaveLength(1);
});

it('offers a custom numeric compiler only as a metric and supplies its role to the editor', () => {
  const numeric: AnalysisRegistration = {
    roles: ['metric'],
    compile: value => aggregation.count(value.alias),
    component: ({ context }) => <span>编辑角色 {context.role}</span>,
  };
  const scoped = { ...context, compilers: { custom: numeric } };
  render(
    <AnalysisEditor
      value={initial}
      context={scoped}
      extensions={{ analysis: { custom: numeric } }}
      onChange={() => {}}
    />,
  );
  expect(screen.getByRole('button', { name: '添加维度' })).toHaveProperty(
    'disabled',
    true,
  );
  cleanup();
  render(
    <AnalysisEditor
      value={{
        ...initial,
        metrics: [{ ...initial.metrics[0], component: { name: 'custom' } }],
      }}
      context={scoped}
      extensions={{ analysis: { custom: numeric } }}
      onChange={() => {}}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '编辑指标 1' }));
  expect(screen.getByText('编辑角色 metric')).toBeTruthy();
});

it('reserves automatic sort slots before adding a dimension', () => {
  const scoped: AnalysisCompileContext = {
    fields: [{ field: 'id', label: 'ID', type: 'string' }],
    capability: {
      count: true,
      limits: { maxGroups: 3, maxSort: 1 },
      fields: [
        { field: 'id', groups: [AggregationGroupType.TERMS], functions: [] },
      ],
    },
  };
  const value: AnalysisViewConfig = {
    ...initial,
    dimensions: [
      {
        id: 'id',
        alias: 'id',
        title: 'ID',
        field: 'id',
        component: { name: 'terms' },
        props: {},
      },
    ],
  };
  render(<AnalysisEditor value={value} context={scoped} onChange={() => {}} />);
  expect(compileAnalysis(value, scoped).plan).toBeDefined();
  expect(screen.getByRole('button', { name: '添加维度' })).toHaveProperty(
    'disabled',
    true,
  );
});

it.each([
  { type: 'datetime', group: AggregationGroupType.DATE_HISTOGRAM },
  { type: 'string', group: AggregationGroupType.HISTOGRAM },
  { type: 'datetime', group: AggregationGroupType.TERMS },
] as const)(
  'does not offer impossible builtin grouping: %j',
  ({ type, group }) => {
    render(
      <AnalysisEditor
        value={initial}
        onChange={vi.fn()}
        context={{
          fields: [{ field: 'x', label: 'X', type }],
          capability: {
            fields: [{ field: 'x', groups: [group], functions: [] }],
            count: true,
          },
        }}
      />,
    );
    expect(
      (screen.getByRole('button', { name: '添加维度' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  },
);

it('targets delayed custom changes by live identity and discards removed or replaced targets', () => {
  let delayed: (() => void) | undefined;
  const changed = vi.fn();
  const registration: AnalysisRegistration = {
    roles: ['metric'],
    compile: value => aggregation.count(value.alias),
    component: ({ value, onChange }) => (
      <button
        onClick={() => {
          delayed = () =>
            onChange({
              ...cloneSnapshot<AnalysisComponentConfig>(value),
              title: 'Updated',
            });
        }}
      >
        delay
      </button>
    ),
  };
  const first = { ...initial.metrics[0], component: { name: 'custom' } };
  const second = { ...first, id: 'second', alias: 'second', title: 'Second' };
  const props = {
    context,
    extensions: { analysis: { custom: registration } },
    onChange: changed,
  };
  const view = render(
    <AnalysisEditor
      {...props}
      value={{ ...initial, metrics: [first, second] }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '编辑指标 1' }));
  fireEvent.click(screen.getByRole('button', { name: 'delay' }));
  view.rerender(
    <AnalysisEditor
      {...props}
      value={{ ...initial, limit: 25, metrics: [second, first] }}
    />,
  );
  delayed!();
  expect(changed.mock.lastCall![0]).toMatchObject({
    limit: 25,
    metrics: [
      { id: 'second', title: 'Second' },
      { id: 'count', title: 'Updated' },
    ],
  });
  changed.mockClear();
  view.rerender(
    <AnalysisEditor {...props} value={{ ...initial, metrics: [second] }} />,
  );
  delayed!();
  expect(changed).not.toHaveBeenCalled();
  view.rerender(<AnalysisEditor {...props} value={initial} />);
  delayed!();
  expect(changed).not.toHaveBeenCalled();
  view.unmount();
  delayed!();
  expect(changed).not.toHaveBeenCalled();
});

it('uses the matching executed element filter as its undo baseline', () => {
  const changed = vi.fn();
  const applied = {
    ...initial,
    scope: { id: 'lines', filters: [initial.filters] },
  };
  const value = {
    ...applied,
    scope: {
      id: 'lines',
      filters: [
        {
          ...initial.filters,
          root: {
            ...initial.filters.root,
            id: 'changed',
            operator: FilterOperator.MATCH_NONE,
          },
        },
      ],
    },
  };
  render(
    <AnalysisEditor
      value={value}
      appliedValue={applied}
      onChange={changed}
      context={{
        ...context,
        capability: {
          ...context.capability,
          scopes: [
            {
              id: 'lines',
              label: 'Lines',
              fields: [],
              capability: context.capability,
              elements: [{ path: 'lines', fields: [] }],
            },
          ],
        },
      }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '撤销筛选修改' }));
  expect(changed.mock.lastCall![0].scope.filters).toEqual(
    applied.scope.filters,
  );
});

it('offers only aggregation functions authorized for expression fields', async () => {
  render(
    <AnalysisEditor
      value={{
        ...initial,
        metrics: [
          {
            ...initial.metrics[0],
            component: { name: 'numeric' },
            expression: aggregation.field('amount'),
            props: { function: AggregationFunction.SUM },
          },
        ],
      }}
      context={{
        fields: [{ field: 'amount', label: 'Amount', type: 'number' }],
        capability: {
          count: true,
          expressions: true,
          fields: [
            {
              field: 'amount',
              groups: [],
              functions: [AggregationFunction.SUM],
            },
          ],
        },
      }}
      onChange={vi.fn()}
    />,
  );
  fireEvent.click(control('combobox', { name: '指标 1 函数' }));
  expect(await screen.findByRole('option', { name: '求和' })).toBeTruthy();
  expect(screen.queryByRole('option', { name: '平均值' })).toBeNull();
  expect(screen.queryByRole('option', { name: '最大值' })).toBeNull();
});

const reviewContext: AnalysisCompileContext = {
  fields: [
    { field: 'id', label: 'ID', type: 'string' },
    { field: 'channel', label: '渠道', type: 'string' },
    { field: 'name', label: '商品名称', type: 'string' },
  ],
  capability: {
    count: true,
    fields: [
      { field: 'id', groups: [AggregationGroupType.TERMS], functions: [] },
      { field: 'channel', groups: [AggregationGroupType.TERMS], functions: [] },
      { field: 'name', groups: [], functions: [], any: true },
    ],
  },
};
const reviewDimension: AnalysisComponentConfig = {
  id: 'product',
  alias: 'product',
  title: '商品',
  field: 'id',
  component: { name: 'terms' },
  props: {},
};
it('includes dimension-owned display aliases in advanced sort choices', async () => {
  render(
    <AnalysisEditor
      value={{
        ...initial,
        dimensions: [
          {
            ...reviewDimension,
            label: { field: 'name', alias: 'product_label', title: '商品名称' },
          },
        ],
        sort: [{ alias: 'product_label', direction: SortDirection.ASC }],
      }}
      context={reviewContext}
      onChange={() => {}}
    />,
  );
  control('button', { name: '添加排序' });
  const select = screen.getByRole('combobox', { name: '排序 1 输出' });
  expect(select.textContent).toContain('商品名称');
  fireEvent.click(select);
  expect(
    await screen.findByRole('option', { name: '商品名称', exact: true }),
  ).toBeTruthy();
});
it('allocates display aliases across dimensions, measures and existing labels', async () => {
  const changed = vi.fn();
  render(
    <AnalysisEditor
      value={{
        ...initial,
        metrics: [{ ...initial.metrics[0], alias: 'product_label' }],
        dimensions: [
          reviewDimension,
          {
            ...reviewDimension,
            id: 'channel',
            alias: 'channel',
            field: 'channel',
            label: { field: 'name', alias: 'product_label_2', title: '渠道名' },
          },
        ],
      }}
      context={reviewContext}
      onChange={changed}
    />,
  );
  fireEvent.click(control('combobox', { name: '维度 1 显示字段' }));
  const option = await screen.findByRole('option', {
    name: '商品名称',
    exact: true,
  });
  fireEvent.pointerDown(option, { pointerType: 'mouse' });
  fireEvent.click(option);
  const config = changed.mock.lastCall![0];
  expect(config.dimensions[0].label.alias).toBe('product_label_3');
  expect(compileAnalysis(config, reviewContext).errors).toEqual([]);
});

it('cancels ordering when a kept-mounted editor becomes hidden', async () => {
  const changed = vi.fn();
  const value: AnalysisViewConfig = {
    ...initial,
    metrics: [
      initial.metrics[0],
      { ...initial.metrics[0], id: 'second', alias: 'second', title: '第二项' },
    ],
  };
  const view = render(
    <AnalysisEditor value={value} context={context} onChange={changed} />,
  );
  await keyboardOrder(
    screen.getByRole('button', { name: '排序指标 1' }),
    'ArrowDown',
    false,
    () => {
      view.rerender(
        <AnalysisEditor
          value={value}
          context={context}
          onChange={changed}
          visible={false}
        />,
      );
    },
  );
  fireEvent.keyDown(document, { key: ' ', code: 'Space' });
  expect(changed).not.toHaveBeenCalled();
  expect(document.querySelector('[data-dnd-dragging]')).toBeNull();
});
