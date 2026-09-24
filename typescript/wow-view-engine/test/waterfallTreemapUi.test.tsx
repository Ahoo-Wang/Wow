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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type ChartData,
  type ChartSpec,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { AnalysisChart, DataWorkbench, ViewSurface } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

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
  { warehouse: 'US', status: 'DONE', orders: 1, total: -10, average: 10 },
  { warehouse: 'JP', status: 'OPEN', orders: 2, total: 40, average: 20 },
];

/** A saved analysis drawn as `chart`, opened in the workbench with its panel. */
async function open(chart: ChartSpec, overrides: Partial<AnalysisViewConfig>) {
  const source: ViewSource = testSource({
    aggregate: vi.fn((query: { groupBy?: unknown[] }) =>
      Promise.resolve(
        (query.groupBy?.length ?? 0) > 0
          ? ROWS.map(row => ({ ...row }))
          : [{ orders: 6, total: 60, average: 13 }],
      ),
    ) as ViewSource['aggregate'],
  });
  const config = {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [WAREHOUSE],
    metrics: [ORDERS, TOTAL, AVERAGE],
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
      { name: 'amount', label: 'Amount', kind: 'number', summary: ['SUM'] },
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

const tile = (type: string) =>
  document.querySelector<HTMLElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;
const panel = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-options"]')!;

async function optionsPage(type: string, tab: 'Data' | 'Display') {
  fireEvent.click(screen.getByRole('button', { name: `${type} options` }));
  await waitFor(() => expect(panel()).not.toBeNull());
  fireEvent.click(within(panel()).getByRole('tab', { name: tab }));
}

/** The choices a slot's select offers, opened. */
async function choicesOf(name: string) {
  fireEvent.click(within(panel()).getByRole('combobox', { name }));
  const list = await screen.findByRole('listbox');
  const choices = within(list)
    .getAllByRole('option')
    .map(option => option.textContent);
  fireEvent.keyDown(list, { key: 'Escape' });
  return choices;
}

describe('the waterfall in the workbench', () => {
  /**
   * Picked from the picker, it redraws the rows on hand as steps and asks
   * the source nothing (D20). Its value slot lists only what adds up — the
   * average is not a step — and its display page turns the total off.
   */
  it('is picked, redrawn over the rows on hand, and set on its two pages', async () => {
    const { queries, draft } = await open(
      {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'total' }] },
      },
      {},
    );
    await screen.findByRole('img', { name: /^bar:/ });
    const ran = queries();
    fireEvent.click(tile('waterfall'));
    await screen.findByRole('img', { name: /^waterfall:/ });
    expect(queries()).toBe(ran);
    // The lead metric carried over: the steps are the amount's.
    expect(draft().chart.waterfall).toEqual({ x: 'warehouse', value: 'total' });
    const frame = document.querySelector('[data-chart="waterfall"]')!;
    // Three steps and the total.
    expect(frame.getAttribute('data-marks')).toBe('4');
    expect(frame.getAttribute('data-total')).toBe('on');

    await optionsPage('waterfall', 'Data');
    expect(await choicesOf('Value')).toEqual(['Record count', 'Sum of Amount']);
    fireEvent.click(within(panel()).getByRole('tab', { name: 'Display' }));
    const box = within(panel()).getByRole('checkbox', {
      name: 'Show the total',
    });
    expect(box.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(box);
    await waitFor(() => expect(draft().chart.waterfall?.total).toBe(false));
    await waitFor(() =>
      expect(
        document
          .querySelector('[data-chart="waterfall"]')!
          .getAttribute('data-total'),
      ).toBe('off'),
    );
    expect(queries()).toBe(ran);
  });
});

describe('the treemap in the workbench', () => {
  it('nests a second dimension, and swaps its two levels from the data page', async () => {
    const { draft } = await open(
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
    await screen.findByRole('img', { name: /^bar:/ });
    fireEvent.click(tile('treemap'));
    await screen.findByRole('img', { name: /^treemap:/ });
    expect(draft().chart.treemap).toEqual({
      category: 'warehouse',
      parent: 'status',
      value: 'orders',
    });
    expect(
      document
        .querySelector('[data-chart="treemap"]')!
        .getAttribute('data-nested'),
    ).toBe('on');

    // One page only: no tabs to choose between.
    fireEvent.click(screen.getByRole('button', { name: 'treemap options' }));
    await waitFor(() => expect(panel()).not.toBeNull());
    expect(within(panel()).queryByRole('tab')).toBeNull();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('One tile per'));
    await user.click(await screen.findByRole('option', { name: 'Status' }));
    await waitFor(() =>
      expect(draft().chart.treemap).toEqual({
        category: 'status',
        parent: 'warehouse',
        value: 'orders',
      }),
    );
    expect(
      within(panel()).getByRole('combobox', { name: 'Grouped in' })
        ?.textContent,
    ).toContain('Warehouse');
  });
});

/**
 * A press on a mark, where the library reads it: at the middle of the first
 * painted shape of `fill`, the event's offset given, since jsdom leaves it
 * at nothing (the same press as test/chartTheme.test.tsx's bar).
 */
async function pressFirst(
  container: HTMLElement,
  fill: string,
  /** The last shape of that fill: a nested tile, painted over its block. */
  last = false,
) {
  // The chart chunk loads on first use, and the marks land after it.
  const mark = await waitFor(() => {
    const found = [
      ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
    ].filter(path => path.getAttribute('fill') === fill);
    expect(found.length).toBeGreaterThan(0);
    return found[last ? found.length - 1 : 0]!;
  });
  // The shape's corners: its moves and lines, absolute (a bar's `M`/`L`) or
  // relative (a tile's `l`), placed by its own `translate`.
  const corners: number[][] = [];
  let pen = [0, 0];
  for (const [, op, x, y] of (mark.getAttribute('d') ?? '').matchAll(
    /([MLl])\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
  )) {
    pen =
      op === 'l'
        ? [pen[0]! + Number(x), pen[1]! + Number(y)]
        : [Number(x), Number(y)];
    corners.push(pen);
  }
  const [dx, dy] = /translate\((-?[\d.]+)[\s,]+(-?[\d.]+)\)/
    .exec(mark.getAttribute('transform') ?? '')
    ?.slice(1)
    .map(Number) ?? [0, 0];
  const xs = corners.map(([x]) => x! + dx!);
  const ys = corners.map(([, y]) => y! + dy!);
  const at = {
    clientX: (Math.min(...xs) + Math.max(...xs)) / 2,
    clientY: (Math.min(...ys) + Math.max(...ys)) / 2,
  };
  const surface = container.querySelector(
    '[data-slot="chart-plot"] > div > div',
  )!;
  for (const type of ['mousemove', 'mousedown', 'mouseup', 'click']) {
    const event = new MouseEvent(type, { bubbles: true, ...at });
    Object.defineProperties(event, {
      offsetX: { value: at.clientX },
      offsetY: { value: at.clientY },
    });
    fireEvent(surface, event);
  }
}

/** The first slot, as jsdom's charts read it (`CHART_FALLBACK`). */
const FIRST = 'rgb(38, 117, 211)';

describe('a waterfall or a treemap pressed', () => {
  it('hands a step’s group to the follow-up, and says what its total is of', async () => {
    const onPick = vi.fn();
    const data: ChartData = {
      type: 'waterfall',
      steps: [
        { x: 'CN', value: 3, start: 0, end: 3 },
        { x: 'US', value: 1, start: 3, end: 4 },
      ],
      total: 4,
    };
    const spec: ChartSpec = {
      type: 'waterfall',
      waterfall: { x: 'warehouse', value: 'orders' },
    };
    const { container, rerender } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} onPick={onPick} />
      </ViewSurface>,
    );
    await pressFirst(container, FIRST);
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0]![0]).toEqual({ warehouse: 'CN' });
    // The numbers, said as a table beside the drawing.
    const reading = container.querySelector<HTMLElement>(
      '[data-slot="chart-reading"]',
    )!;
    expect(within(reading).getByText('Running total')).toBeDefined();
    expect(within(reading).getByText('Total')).toBeDefined();
    expect(
      container.querySelector('[data-slot="waterfall-total-basis"]'),
    ).toBeNull();

    // A press that set a board's filter: the other steps, and the total,
    // drawn faint (D22 I); the step pressed stays whole.
    rerender(
      <ViewSurface>
        <AnalysisChart
          data={data}
          spec={spec}
          highlight={row => row.warehouse === 'US'}
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(
        [
          ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
        ].filter(path => path.getAttribute('fill-opacity') === '0.5'),
      ).toHaveLength(2),
    );

    rerender(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} cutShort />
      </ViewSurface>,
    );
    expect(
      container.querySelector('[data-slot="waterfall-total-basis"]')
        ?.textContent,
    ).toContain('The total is of the groups shown');
  });

  it('hands a tile’s groups to the follow-up, and says what it left out', async () => {
    const onPick = vi.fn();
    const data: ChartData = {
      type: 'treemap',
      tiles: [
        {
          group: 'OPEN',
          value: 5,
          tiles: [
            { group: 'CN', value: 3 },
            { group: 'JP', value: 2 },
          ],
        },
      ],
      nested: true,
      omitted: 2,
    };
    const spec: ChartSpec = {
      type: 'treemap',
      treemap: { category: 'warehouse', parent: 'status', value: 'orders' },
    };
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={data}
          spec={spec}
          onPick={onPick}
          highlight={row => row.warehouse === 'JP'}
        />
      </ViewSurface>,
    );
    // The first tile of the first block wears the block's slot, whole, and
    // is painted over its block.
    await pressFirst(container, FIRST, true);
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0]![0]).toEqual({
      warehouse: 'CN',
      status: 'OPEN',
    });
    expect(
      container.querySelector('[data-slot="treemap-notes"]')?.textContent,
    ).toContain('2 groups not above zero are not drawn');
    const frame = container.querySelector('[data-chart="treemap"]')!;
    expect(frame.getAttribute('data-marks')).toBe('2');
    expect(frame.getAttribute('data-nested')).toBe('on');
  });

  it('presses nothing on a chart no press was asked of', async () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'treemap',
            tiles: [{ group: 'CN', value: 1 }],
            nested: false,
            omitted: 0,
          }}
          spec={{
            type: 'treemap',
            treemap: { category: 'warehouse', value: 'orders' },
          }}
          cutShort
        />
      </ViewSurface>,
    );
    await pressFirst(container, FIRST);
    expect(
      container.querySelector('[data-slot="treemap-notes"]')?.textContent,
    ).toContain('Shares of the groups shown');
  });
});
