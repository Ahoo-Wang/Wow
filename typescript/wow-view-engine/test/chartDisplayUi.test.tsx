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

import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type ChartSpec,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { describedText } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The display page's two settings of audit P1-10 — what a missing point
 * draws as, and a stack read as shares — and the combo's right axis picked
 * by what its metrics measure (todo 「组合图的右轴」). Each redraws the rows
 * on screen and asks the source nothing (D20).
 */

const WAREHOUSE: AnalysisGroup = {
  alias: 'warehouse',
  field: 'warehouse',
  type: 'TERMS',
};
const STATUS: AnalysisGroup = {
  alias: 'status',
  field: 'status',
  type: 'TERMS',
};
const ORDERS: AnalysisMetric = { alias: 'orders', type: 'COUNT' };
const TOTAL: AnalysisMetric = {
  alias: 'total',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVERAGE: AnalysisMetric = {
  alias: 'average',
  type: 'NUMERIC',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};

const ROWS: RecordData[] = [
  { warehouse: 'CN', status: 'OPEN', orders: 3, total: 30, average: 10 },
  { warehouse: 'CN', status: 'DONE', orders: 1, total: 10, average: 10 },
  { warehouse: 'US', status: 'OPEN', orders: 2, total: 40, average: 20 },
];

async function open(
  chart: ChartSpec,
  overrides: Partial<AnalysisViewConfig>,
  rows: readonly RecordData[] = ROWS,
) {
  const source: ViewSource = testSource({
    aggregate: vi.fn((query: { groupBy?: unknown[] }) =>
      Promise.resolve(
        (query.groupBy?.length ?? 0) > 0
          ? rows.map(row => ({ ...row }))
          : [{ orders: 6, total: 80, average: 13 }],
      ),
    ) as ViewSource['aggregate'],
  });
  const config = {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [WAREHOUSE],
    metrics: [ORDERS, TOTAL],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart,
    ...overrides,
  } as AnalysisViewConfig;
  const instance: ViewInstance = {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'personal',
    revision: '1',
    config,
  };
  const definition = ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'status', label: 'Status', kind: 'string' },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        summary: ['SUM'],
        // Money: what tells a sum of it from a count of orders.
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'status',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.SUM, AggregationFunction.AVG],
        },
      ],
    },
  });
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  fireEvent.click(await screen.findByRole('button', { name: 'Visualize' }));
  return {
    queries: () => vi.mocked(source.aggregate).mock.calls.length,
    draft: () =>
      engine.openRuntimes()[0]!.getSnapshot().draft as AnalysisViewConfig,
  };
}

const panel = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-options"]')!;

/** Level two of the panel, on its display page. */
async function displayPage(type: string) {
  fireEvent.click(screen.getByRole('button', { name: `${type} options` }));
  await waitFor(() => expect(panel()).not.toBeNull());
  fireEvent.click(within(panel()).getByRole('tab', { name: 'Display' }));
}

describe('the display page', () => {
  it('stacks a split to 100% in one press, and asks for nothing', async () => {
    const { draft, queries } = await open(
      {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          splitBy: 'status',
          series: [{ metric: 'orders' }],
        },
      },
      { groups: [WAREHOUSE, STATUS] },
    );
    await displayPage('bar');
    const ran = queries();

    const box = () =>
      within(panel()).getByRole('checkbox', { name: 'Stacked to 100%' });
    expect(box().getAttribute('aria-checked')).toBe('false');
    expect(describedText(box())).toBe(
      'Each stack reads as shares of its total; the tooltip keeps the values',
    );
    fireEvent.click(box());
    await waitFor(() =>
      expect(draft().chart.cartesian).toMatchObject({
        percentStack: true,
        series: [{ metric: 'orders', stack: 'all' }],
      }),
    );
    // Stacked with it: the one box says both.
    expect(
      within(panel())
        .getByRole('checkbox', { name: 'Stacked' })
        .getAttribute('aria-checked'),
    ).toBe('true');
    fireEvent.click(box());
    await waitFor(() =>
      expect(draft().chart.cartesian?.percentStack).toBeUndefined(),
    );
    expect(queries()).toBe(ran);
  });

  it('offers no 100% stack where a share would mean nothing', async () => {
    await open(
      {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          series: [{ metric: 'orders' }, { metric: 'average' }],
        },
      },
      { metrics: [ORDERS, AVERAGE] },
    );
    await displayPage('bar');
    expect(
      within(panel()).getByRole('checkbox', { name: 'Stacked' }),
    ).toBeDefined();
    expect(
      within(panel()).queryByRole('checkbox', { name: 'Stacked to 100%' }),
    ).toBeNull();
  });

  it('leaves the missing points empty, or back to the rule, on a press', async () => {
    const { draft, queries } = await open(
      {
        type: 'line',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
      {},
    );
    await displayPage('line');
    const ran = queries();
    const choice = within(panel()).getByRole('group', {
      name: 'Missing values',
    });
    const zero = within(choice).getByRole('button', { name: 'Zero' });
    const gap = within(choice).getByRole('button', { name: 'Leave a gap' });
    expect(zero.getAttribute('aria-pressed')).toBe('true');
    // What the default does is said under it: not every hole is a 0.
    expect(describedText(choice)).toContain('Zero where the group is known');

    fireEvent.click(gap);
    await waitFor(() => expect(draft().chart.cartesian?.missing).toBe('gap'));
    expect(describedText(choice)).toBe(
      'A point with no rows is not drawn, and a line breaks there',
    );
    fireEvent.click(zero);
    await waitFor(() =>
      expect(draft().chart.cartesian?.missing).toBeUndefined(),
    );
    expect(queries()).toBe(ran);
  });
});

describe('a combo’s right axis', () => {
  const tile = (type: string) =>
    document.querySelector<HTMLButtonElement>(
      `[data-slot="chart-tile"][data-chart-type="${type}"]`,
    )!;

  it('puts the amount beside a count on the right, from the picker', async () => {
    const { draft } = await open(
      {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
      {},
    );
    fireEvent.click(tile('combo'));
    await waitFor(() =>
      expect(draft().chart.cartesian?.series).toEqual([
        { metric: 'orders', type: 'bar' },
        { metric: 'total', type: 'line', axis: 'right' },
      ]),
    );
  });

  it('puts a series added to a combo on the axis its measure asks for', async () => {
    const { draft } = await open(
      {
        type: 'combo',
        cartesian: {
          x: 'warehouse',
          series: [{ metric: 'orders', type: 'bar' }],
        },
      },
      { metrics: [ORDERS, TOTAL, AVERAGE] },
    );
    fireEvent.click(screen.getByRole('button', { name: 'combo options' }));
    await waitFor(() => expect(panel()).not.toBeNull());
    fireEvent.click(
      within(panel()).getByRole('button', { name: 'Add series' }),
    );
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Sum of Amount' }),
    );
    await waitFor(() =>
      expect(draft().chart.cartesian?.series).toEqual([
        { metric: 'orders', type: 'bar' },
        { metric: 'total', type: 'line', axis: 'right' },
      ]),
    );
  });
});

describe('the axes page says the title the chart draws', () => {
  /** The placeholder of each axis's title box, left then right. */
  async function placeholders(
    chart: ChartSpec,
    metrics: AnalysisViewConfig['metrics'] = [ORDERS, TOTAL],
  ) {
    await open(chart, { metrics });
    fireEvent.click(
      screen.getByRole('button', { name: `${chart.type} options` }),
    );
    await waitFor(() => expect(panel()).not.toBeNull());
    fireEvent.click(within(panel()).getByRole('tab', { name: 'Axes' }));
    return within(panel())
      .getAllByLabelText('Axis title')
      .map(box => (box as HTMLInputElement).placeholder);
  }

  it('shows the column an axis measures while the box is empty (audit)', async () => {
    // The box stood empty, and an empty box reads as "no title" when the
    // axis is in fact titled 「金额的总和」.
    expect(
      await placeholders({
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'total' }] },
      }),
    ).toEqual(['Sum of Amount']);
  });

  it('says the legend names two metrics on the one axis', async () => {
    expect(
      await placeholders({
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          series: [{ metric: 'orders' }, { metric: 'total' }],
        },
      }),
    ).toEqual(['None — the legend names the series']);
  });

  it('names every metric on each of two axes', async () => {
    expect(
      await placeholders(
        {
          type: 'combo',
          cartesian: {
            x: 'warehouse',
            series: [
              { metric: 'orders', type: 'bar' },
              { metric: 'total', type: 'line', axis: 'right' },
              { metric: 'average', type: 'line', axis: 'right' },
            ],
          },
        },
        [ORDERS, TOTAL, AVERAGE],
      ),
    ).toEqual(['Record count', 'Sum of Amount, Average of Amount']);
  });
});

describe('a ranking of long names lies on its side (audit)', () => {
  const LONG: RecordData[] = [
    { warehouse: 'OrderItemReservedTrackEventProcessor', orders: 9, total: 1 },
    { warehouse: 'PaymentSettledProjectionHandler', orders: 4, total: 1 },
  ];

  it('draws horizontal bars until the analyst stands them up', async () => {
    const { draft } = await open(
      {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
      {},
      LONG,
    );
    const frame = () =>
      document.querySelector('[data-slot="chart"][data-chart="bar"]');
    await waitFor(() =>
      expect(frame()?.getAttribute('data-orientation')).toBe('horizontal'),
    );
    // Nothing was written: the names laid it down.
    expect(draft().chart.cartesian?.orientation).toBeUndefined();

    await displayPage('bar');
    const box = within(panel()).getByRole('checkbox', { name: 'Horizontal' });
    // The box says what is drawn.
    expect(box.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(box);
    await waitFor(() =>
      expect(draft().chart.cartesian?.orientation).toBe('vertical'),
    );
    await waitFor(() =>
      expect(frame()?.getAttribute('data-orientation')).toBe('vertical'),
    );
  });

  it('keeps short names upright', async () => {
    await open(
      {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
      {},
    );
    await waitFor(() =>
      expect(
        document
          .querySelector('[data-slot="chart"][data-chart="bar"]')
          ?.getAttribute('data-orientation'),
      ).toBe('vertical'),
    );
  });
});
