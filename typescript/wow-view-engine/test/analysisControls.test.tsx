/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import {
  AggregationExpressionType as Type,
  AggregationExpressionOperator as Operator,
  AggregationFunction,
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import { AnalysisExpressionEditor } from '../src/analysis/AnalysisExpressionEditor.js';
import { AnalysisScopeEditor } from '../src/analysis/AnalysisScopeEditor.js';
import { createFilterConfiguration } from '../src/index.js';
import type {
  AnalysisNumericExpression,
  AnalysisViewConfig,
  AnalysisCompileContext,
} from '../src/index.js';
afterEach(cleanup);
const context: AnalysisCompileContext = {
  fields: [
    { field: 'amount', label: '金额', type: 'number' },
    { field: 'tax', label: '税额', type: 'number' },
  ],
  capability: {
    count: true,
    fields: ['amount', 'tax'].map(field => ({
      field,
      groups: [],
      functions: [AggregationFunction.SUM],
      unit: 'CNY',
    })),
  },
};
async function choose(label: string, option: string) {
  fireEvent.click(screen.getByRole('combobox', { name: label }));
  const item = await screen.findByRole('option', { name: option, exact: true });
  fireEvent.pointerDown(item, { pointerType: 'mouse' });
  fireEvent.click(item);
}
it('edits a binary formula, preserves raw constants, and changes field and operator independently', async () => {
  const changed = vi.fn();
  function Example() {
    const [value, setValue] = useState<AnalysisNumericExpression>({
      type: Type.CONSTANT,
      value: 0,
    });
    return (
      <AnalysisExpressionEditor
        value={value}
        context={context}
        label="公式"
        function={AggregationFunction.SUM}
        onChange={next => {
          changed(next);
          setValue(next);
        }}
      />
    );
  }
  render(<Example />);
  await choose('公式 类型', '运算');
  await choose('公式 运算符', '乘 ×');
  await choose('公式 左侧 字段', '税额 (CNY)');
  fireEvent.change(screen.getByRole('textbox', { name: '公式 右侧 常量' }), {
    target: { value: '-' },
  });
  expect(changed.mock.lastCall?.[0]).toEqual({
    type: Type.BINARY,
    operator: Operator.MULTIPLY,
    left: { type: Type.FIELD, field: 'tax' },
    right: { type: Type.CONSTANT, value: '-' },
  });
  await choose('公式 类型', '字段');
  expect(changed.mock.lastCall?.[0]).toEqual({
    type: Type.FIELD,
    field: 'amount',
  });
  await choose('公式 类型', '常量');
  expect(changed.mock.lastCall?.[0]).toEqual({
    type: Type.CONSTANT,
    value: '',
  });
});
it('repairs a missing operand and blocks excessive nesting', () => {
  const changed = vi.fn();
  const view = render(
    <AnalysisExpressionEditor
      value={null as unknown as AnalysisNumericExpression}
      context={context}
      label="公式"
      onChange={changed}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '修复公式' }));
  expect(changed).toHaveBeenCalledWith({ type: Type.CONSTANT, value: 0 });
  view.rerender(
    <AnalysisExpressionEditor
      value={{ type: Type.CONSTANT, value: 1 }}
      context={context}
      label="公式"
      depth={9}
      onChange={changed}
    />,
  );
  expect(screen.getByRole('alert').textContent).toContain('8 层');
});
const all = createFilterConfiguration({
  id: 'all',
  component: { name: 'builtin' },
  operator: FilterOperator.MATCH_ALL,
  props: {},
});
const config: AnalysisViewConfig = {
  filters: all,
  dimensions: [],
  metrics: [],
  sort: [],
  limit: 10,
  presentation: { layout: 'table', columns: [] },
};
const scoped: AnalysisCompileContext = {
  ...context,
  capability: {
    ...context.capability,
    scopes: [
      {
        id: 'items',
        label: '订单明细',
        elements: [
          {
            path: 'items',
            fields: [
              {
                field: 'status',
                label: '状态',
                type: 'string',
                operators: [FilterOperator.EQ],
              },
            ],
          },
        ],
        fields: [],
        capability: { count: true, fields: [] },
      },
    ],
  },
};
it('repairs element filters without modifying the root and allows returning to root scope', async () => {
  const changed = vi.fn(),
    validity = vi.fn();
  function Example() {
    const [value, setValue] = useState<AnalysisViewConfig>({
      ...config,
      scope: { id: 'items', filters: [] },
    });
    return (
      <AnalysisScopeEditor
        value={value}
        context={scoped}
        onFilterValidityChange={validity}
        onChange={next => {
          changed(next);
          setValue(next);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '修复元素筛选配置' }));
  expect(changed.mock.lastCall?.[0].scope.filters[0].root.operator).toBe(
    FilterOperator.MATCH_ALL,
  );
  expect(changed.mock.lastCall?.[0].filters).toEqual(all);
  expect(validity).toHaveBeenCalledWith(true);
  await choose('统计对象', '根记录');
  expect(changed.mock.lastCall?.[0].scope).toBeUndefined();
});
it('edits an element condition without replacing the root condition', () => {
  const changed = vi.fn();
  const condition = createFilterConfiguration({
    id: 'status',
    field: 'status',
    component: { name: 'builtin' },
    operator: FilterOperator.EQ,
    props: { value: 'old' },
  });
  render(
    <AnalysisScopeEditor
      value={{ ...config, scope: { id: 'items', filters: [condition] } }}
      context={scoped}
      onChange={changed}
    />,
  );
  fireEvent.change(screen.getByRole('textbox', { name: '状态值' }), {
    target: { value: 'new' },
  });
  expect(changed.mock.lastCall?.[0].scope.filters[0].root.props.value).toBe(
    'new',
  );
  expect(changed.mock.lastCall?.[0].filters).toEqual(all);
});
it('keeps an unavailable scope visible for repair and honors disabled repairs', () => {
  const changed = vi.fn();
  const view = render(
    <AnalysisScopeEditor
      value={{ ...config, scope: { id: 'missing', filters: [] } }}
      context={scoped}
      onChange={changed}
    />,
  );
  expect(screen.getByRole('alert').textContent).toContain('不可用');
  view.rerender(
    <AnalysisScopeEditor
      value={{ ...config, scope: { id: 'items', filters: [] } }}
      context={scoped}
      onChange={changed}
      disabled
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '修复元素筛选配置' }));
  expect(changed).not.toHaveBeenCalled();
});
