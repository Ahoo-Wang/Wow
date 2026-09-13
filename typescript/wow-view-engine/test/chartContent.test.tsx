/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import {
  ChartContainer,
  ChartTooltipContent,
  ChartLegendContent,
} from '../src/components/ui/chart.js';
afterEach(cleanup);
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(
    new DOMRect(0, 0, 800, 600),
  );
});
const Icon = () => <svg role="img" aria-label="销售图标" />;
const config = {
  sales: { label: '销售额', color: 'red', icon: Icon },
  count: { label: '订单数', color: 'blue' },
};
const payload = [
  {
    name: 'sales',
    dataKey: 'sales',
    value: 0,
    color: 'red',
    payload: { group: 'sales' },
  },
];
it('requires a chart context instead of silently rendering a misleading tooltip', () => {
  const log = vi.spyOn(console, 'error').mockImplementation(() => {});
  try {
    expect(() =>
      render(<ChartTooltipContent active payload={payload} />),
    ).toThrow('ChartContainer');
  } finally {
    log.mockRestore();
  }
});
it('shows zero values and configured labels/icons, while respecting inactive and hidden content', () => {
  const view = render(
    <ChartContainer config={config}>
      <div>
        <ChartTooltipContent active payload={payload} label="sales" />
      </div>
    </ChartContainer>,
  );
  expect(screen.getByText('0')).toBeTruthy();
  expect(screen.getByRole('img', { name: '销售图标' })).toBeTruthy();
  view.rerender(
    <ChartContainer config={config}>
      <div>
        <ChartTooltipContent active payload={payload} hideLabel hideIndicator />
      </div>
    </ChartContainer>,
  );
  expect(screen.getAllByText('销售额')).toHaveLength(1);
  view.rerender(
    <ChartContainer config={config}>
      <div>
        <ChartTooltipContent active={false} payload={payload} />
      </div>
    </ChartContainer>,
  );
  expect(screen.queryByText('销售额')).toBeNull();
});
it('formats tooltip labels and values and resolves aliases from nested payload', () => {
  const formatter = vi.fn(() => <span>格式化金额</span>);
  render(
    <ChartContainer config={config}>
      <div>
        <ChartTooltipContent
          active
          payload={payload}
          labelKey="group"
          nameKey="group"
          indicator="line"
          labelFormatter={label => `分组：${label}`}
          formatter={formatter}
        />
      </div>
    </ChartContainer>,
  );
  expect(screen.getByText('格式化金额')).toBeTruthy();
  expect(formatter).toHaveBeenCalledWith(
    0,
    'sales',
    expect.anything(),
    0,
    expect.anything(),
  );
});
it('renders indicators and filtered legends with mapped names', () => {
  render(
    <ChartContainer config={config}>
      <div>
        <ChartTooltipContent
          active
          indicator="dashed"
          label="分组"
          payload={[
            {
              name: 'count',
              dataKey: 'count',
              value: 1234,
              payload: { fill: 'blue' },
            },
          ]}
        />
        <ChartLegendContent
          verticalAlign="top"
          nameKey="group"
          payload={[
            {
              value: 'sales',
              color: 'red',
              type: 'rect',
              payload: { group: 'sales' },
            },
            { value: 'skip', type: 'none' },
          ]}
        />
      </div>
    </ChartContainer>,
  );
  expect(screen.getByText('分组')).toBeTruthy();
  expect(screen.getByText('订单数')).toBeTruthy();
  expect(screen.getByRole('img', { name: '销售图标' })).toBeTruthy();
  expect(screen.queryByText('skip')).toBeNull();
});
