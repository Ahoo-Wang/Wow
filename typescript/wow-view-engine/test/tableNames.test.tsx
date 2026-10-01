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

/**
 * The names a reader moving by tables and images hears: the record table is
 * called by its view or its panel, and a metric card's sparkline says what
 * it drew in the sentence every time axis says, over a table whose first
 * column is named by the dimension (the accessibility review's 「表格与小图的
 * 名字」).
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { text, type ChartSpec, type MetricCardData } from '../src/index.js';
import { readChart } from '../src/ui/charts/reading.js';
import { RecordTable, ViewSurface, zhCN } from '../src/ui/index.js';
import {
  defaultMessages,
  formatMessage,
  type ViewMessages,
} from '../src/ui/kit/messages.js';
import { twoColumnTable } from './fixtures/ui.js';

afterEach(cleanup);

describe('the record table', () => {
  it('is called by the name it is given', () => {
    render(<RecordTable table={twoColumnTable()} name="Pending orders" />);
    expect(screen.getByRole('table', { name: 'Pending orders' })).toBeDefined();
  });

  it('says a name that is a key in the surface’s words', () => {
    render(
      <ViewSurface messages={{ 'orders.pending': '待出库订单' }}>
        <RecordTable table={twoColumnTable()} name={text('orders.pending')} />
      </ViewSurface>,
    );
    expect(screen.getByRole('table', { name: '待出库订单' })).toBeDefined();
  });
});

describe('a column header', () => {
  /**
   * Its name is the column's label. Computed from what it holds, it was the
   * sort button's action and the width handle's — 「按订单号升序排序 调整
   * 订单号 宽度」 — and a reader hears the header's name in every cell it
   * walks into (second-round review, A11Y-10). The sort is `aria-sort`'s,
   * and the button keeps the action as its own name.
   */
  it('is called by the column’s label alone', () => {
    render(<RecordTable table={twoColumnTable()} name="Orders" />);
    expect(screen.getByRole('columnheader', { name: 'Amount' })).toBeDefined();
    expect(
      screen.getByRole('columnheader', { name: 'Warehouse' }),
    ).toBeDefined();
    expect(
      document.querySelector('[data-slot="column-resizer"]'),
    ).not.toBeNull();
  });
});

describe('a metric card’s sparkline', () => {
  const spec: ChartSpec = {
    metric: { metric: 'gmv', trend: { x: 'month' } },
  } as ChartSpec;
  const data: MetricCardData = {
    type: 'metric',
    value: 30,
    trend: [
      { x: '2026-01', value: 10 },
      { x: '2026-02', value: 5 },
      { x: '2026-03', value: 30 },
    ],
  };
  const read = (catalogue: ViewMessages) =>
    readChart(data, spec, {
      messages: {
        label: (key, params) => formatMessage(catalogue, key as never, params),
        say: value => value,
        issue: () => '',
        issues: () => '',
      },
      label: (_alias, value) => String(value),
      column: alias =>
        alias === 'month' ? 'Month' : alias === 'gmv' ? 'GMV' : undefined,
      locale: 'en',
    });

  it('says its periods, its way and its extremes', () => {
    expect(read(defaultMessages).sentence).toBe(
      '3 periods from 2026-01 to 2026-03, rising overall; highest 2026-03, 30; lowest 2026-02, 5.',
    );
    expect(read({ ...defaultMessages, ...zhCN }).sentence).toContain('2026-01');
  });

  it('heads its table with the dimension, not 「category」', () => {
    expect(read(defaultMessages).header).toEqual(['Month', 'GMV']);
  });

  it('says nothing more of a single period', () => {
    const one = readChart(
      { ...data, trend: [{ x: '2026-01', value: 10 }] },
      spec,
      {
        messages: {
          label: (key, params) =>
            formatMessage(defaultMessages, key as never, params),
          say: value => value,
          issue: () => '',
          issues: () => '',
        },
        label: (_alias, value) => String(value),
        column: () => undefined,
        locale: 'en',
      },
    );
    expect(one.sentence).toBeUndefined();
  });
});
