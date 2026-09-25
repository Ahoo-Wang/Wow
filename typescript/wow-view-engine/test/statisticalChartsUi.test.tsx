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
  AggregationDateUnit,
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
import { openTray } from './fixtures/workbench.js';

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

/** Every row answers any metric the query names with a number of its own. */
function answer(query: {
  groupBy?: { alias?: string }[];
  metrics?: { alias?: string }[];
}): RecordData[] {
  const aliases = (query.metrics ?? []).map(metric => metric.alias ?? '');
  const grouped = (query.groupBy ?? []).length;
  const by = new Set((query.groupBy ?? []).map(group => group.alias));
  // A day bucket, where the query groups by one: three days in September.
  const days = { CN: '2026-09-01', US: '2026-09-02', JP: '2026-09-03' };
  const row = (warehouse: keyof typeof days | undefined, base: number) => ({
    ...(warehouse === undefined ? {} : { warehouse }),
    ...(warehouse !== undefined && by.has('day')
      ? { day: Date.parse(`${days[warehouse]}T00:00:00Z`) }
      : {}),
    ...(grouped > 1 ? { status: warehouse === 'US' ? 'DONE' : 'OPEN' } : {}),
    ...Object.fromEntries(aliases.map((alias, at) => [alias, base + at * 10])),
  });
  return (query.groupBy?.length ?? 0) > 0
    ? [row('CN', 3), row('US', 5), row('JP', 7)]
    : [row(undefined, 870)];
}

/** A saved analysis drawn as `chart`, opened in the workbench. */
async function open(
  chart: ChartSpec,
  overrides: Partial<AnalysisViewConfig> = {},
) {
  const source: ViewSource = testSource({
    aggregate: vi.fn((query: Parameters<typeof answer>[0]) =>
      Promise.resolve(answer(query)),
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
      { name: 'placedAt', label: 'Placed', kind: 'datetime' },
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
          field: 'placedAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        },
        {
          field: 'amount',
          groups: [],
          functions: [
            AggregationFunction.SUM,
            AggregationFunction.AVG,
            AggregationFunction.MIN,
            AggregationFunction.MAX,
          ],
          percentile: true,
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
  return {
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

async function visualize() {
  fireEvent.click(await screen.findByRole('button', { name: 'Visualize' }));
  await waitFor(() => expect(tile('bar')).not.toBeNull());
}

async function optionsOf(type: string, tab?: 'Data' | 'Display') {
  fireEvent.click(screen.getByRole('button', { name: `${type} options` }));
  await waitFor(() => expect(panel()).not.toBeNull());
  if (tab) fireEvent.click(within(panel()).getByRole('tab', { name: tab }));
}

/** The chart's frame once its marks are in. */
const frame = (type: string) =>
  waitFor(() => {
    const found = document.querySelector<HTMLElement>(
      `[data-slot="chart"][data-chart="${type}"]`,
    );
    expect(found).not.toBeNull();
    expect(found!.querySelector('[data-slot="chart-plot"] svg')).not.toBeNull();
    return found!;
  });

const readingRows = (container: ParentNode) =>
  [
    ...(container
      .querySelector('[data-slot="chart-reading"] table')
      ?.querySelectorAll('tbody tr') ?? []),
  ].map(row => [...row.children].map(cell => cell.textContent));

describe('the boxplot in the workbench (D41)', () => {
  it('is greyed until a metric card adds the five numbers, then drawn from them', async () => {
    const { draft } = await open(
      {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'total' }] },
      },
      { metrics: [ORDERS, TOTAL] },
    );
    await screen.findByRole('img', { name: /^bar:/ });
    await visualize();
    expect(tile('boxplot').getAttribute('aria-disabled')).toBe('true');
    expect(
      tile('boxplot').querySelector('[data-slot="chart-reason"]')?.textContent,
    ).toBe('Needs a field’s minimum, three percentiles and maximum');

    // The record count names no field: nothing to add a box of.
    const tray = await openTray();
    const cards = tray.querySelectorAll<HTMLElement>(
      '[data-slot="metric-card"]',
    );
    const openMenu = async (card: HTMLElement) => {
      fireEvent.click(
        card.querySelector<HTMLElement>('[data-slot="card-menu"]')!,
      );
      return screen.findByRole('menu');
    };
    const countMenu = await openMenu(cards[0]!);
    expect(
      within(countMenu).queryByRole('menuitem', {
        name: 'Add the five numbers for a boxplot',
      }),
    ).toBeNull();
    fireEvent.keyDown(countMenu, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());

    fireEvent.click(
      within(await openMenu(cards[1]!)).getByRole('menuitem', {
        name: 'Add the five numbers for a boxplot',
      }),
    );
    await waitFor(() => expect(draft().metrics).toHaveLength(7));
    expect(
      draft()
        .metrics.slice(2)
        .map(metric => metric.alias),
    ).toEqual([
      'amount_min_1',
      'amount_p25_1',
      'amount_p50_1',
      'amount_p75_1',
      'amount_max_1',
    ]);
    fireEvent.click(await screen.findByRole('button', { name: /^Apply/ }));
    await waitFor(() =>
      expect(tile('boxplot').getAttribute('aria-disabled')).toBeNull(),
    );
    fireEvent.click(tile('boxplot'));
    const box = await frame('boxplot');
    expect(box.getAttribute('data-marks')).toBe('3');
    expect(
      box.querySelector('[data-slot="boxplot-notes"]')?.textContent,
    ).toContain('Quartiles and median are approximate');
    expect(draft().chart.boxplot).toEqual({
      category: 'warehouse',
      low: 'amount_min_1',
      q1: 'amount_p25_1',
      median: 'amount_p50_1',
      q3: 'amount_p75_1',
      high: 'amount_max_1',
    });
    // One set, so no choice of whose spread: the data page asks the boxes.
    await optionsOf('boxplot');
    expect(
      within(panel()).getByRole('combobox', { name: 'One box per' }),
    ).toBeDefined();
    expect(
      within(panel()).queryByRole('combobox', { name: 'Spread of' }),
    ).toBeNull();
  });
});

describe('the radar and parallel axes in the workbench', () => {
  it('draws three metrics as axes, and keeps three when one is taken away', async () => {
    const { draft } = await open({
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });
    await screen.findByRole('img', { name: /^bar:/ });
    await visualize();
    fireEvent.click(tile('radar'));
    const radar = await frame('radar');
    expect(radar.getAttribute('data-axes')).toBe('3');
    expect(radar.getAttribute('data-marks')).toBe('3');
    expect(draft().chart.radar).toEqual({
      category: 'warehouse',
      metrics: ['orders', 'total', 'average'],
    });
    await optionsOf('radar', 'Data');
    const axes = within(panel()).getByRole('group', { name: 'Axes' });
    const boxes = within(axes).getAllByRole('checkbox');
    expect(boxes).toHaveLength(3);
    // Three is the fewest: none of them can be taken away.
    for (const box of boxes)
      expect(
        box.getAttribute('aria-disabled') ?? box.getAttribute('data-disabled'),
      ).not.toBeNull();

    fireEvent.click(
      screen.getByRole('button', { name: 'Back to the chart types' }),
    );
    fireEvent.click(tile('parallel'));
    const parallel = await frame('parallel');
    expect(parallel.getAttribute('data-axes')).toBe('3');
    // The lead carried over from the radar: its axes as they stood.
    expect(draft().chart.parallel?.metrics).toEqual([
      'orders',
      'total',
      'average',
    ]);
  });
});

describe('the sunburst in the workbench (D41)', () => {
  it('orders its levels by hand and sizes by what adds up', async () => {
    const { draft } = await open(
      {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          splitBy: 'status',
          series: [{ metric: 'total' }],
        },
      },
      { groups: [WAREHOUSE, STATUS] },
    );
    await screen.findByRole('img', { name: /^bar:/ });
    await visualize();
    fireEvent.click(tile('sunburst'));
    await frame('sunburst');
    expect(draft().chart.sunburst).toEqual({
      levels: ['warehouse', 'status'],
      value: 'total',
    });
    await optionsOf('sunburst');
    const levels = panel().querySelectorAll('[data-slot="level-card"]');
    expect([...levels].map(card => card.getAttribute('data-level'))).toEqual([
      'warehouse',
      'status',
    ]);
    fireEvent.click(
      within(panel()).getByRole('button', { name: 'Move Status up' }),
    );
    await waitFor(() =>
      expect(draft().chart.sunburst?.levels).toEqual(['status', 'warehouse']),
    );
    fireEvent.click(
      within(panel()).getByRole('button', { name: 'Move Status down' }),
    );
    await waitFor(() =>
      expect(draft().chart.sunburst?.levels).toEqual(['warehouse', 'status']),
    );
    // Only what adds up sizes a part: the average is not offered.
    fireEvent.click(within(panel()).getByRole('combobox', { name: 'Value' }));
    const list = await screen.findByRole('listbox');
    expect(
      within(list)
        .getAllByRole('option')
        .map(option => option.textContent),
    ).toEqual(['Record count', 'Sum of Amount']);
    fireEvent.keyDown(list, { key: 'Escape' });
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Value'));
    await user.click(
      await screen.findByRole('option', { name: 'Record count' }),
    );
    await waitFor(() => expect(draft().chart.sunburst?.value).toBe('orders'));
  });
});

const DAY: AnalysisGroup = {
  alias: 'day',
  field: 'placedAt',
  type: 'DATE_HISTOGRAM',
  unit: 'DAY',
  timeZone: 'UTC',
};

describe('the calendar and the river in the workbench (D41)', () => {
  it('lays out the day dimension, and streams a river along it', async () => {
    const { draft } = await open(
      { type: 'bar', cartesian: { x: 'day', series: [{ metric: 'total' }] } },
      { groups: [DAY] },
    );
    await screen.findByRole('img', { name: /^bar:/ });
    await visualize();
    fireEvent.click(tile('calendar'));
    const calendar = await frame('calendar');
    expect(calendar.getAttribute('data-marks')).toBe('3');
    expect(draft().chart.calendar).toEqual({ date: 'day', value: 'total' });
    await optionsOf('calendar');
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Value'));
    await user.click(
      await screen.findByRole('option', { name: 'Average of Amount' }),
    );
    await waitFor(() => expect(draft().chart.calendar?.value).toBe('average'));
    expect(
      within(panel()).getByRole('combobox', { name: 'Days' }).textContent,
    ).toContain('Placed');
  });

  it('streams the other dimension, along the date alone', async () => {
    const { draft } = await open(
      {
        type: 'bar',
        cartesian: {
          x: 'day',
          splitBy: 'warehouse',
          series: [{ metric: 'total' }],
        },
      },
      { groups: [DAY, WAREHOUSE] },
    );
    await screen.findByRole('img', { name: /^bar:/ });
    await visualize();
    fireEvent.click(tile('themeRiver'));
    await frame('themeRiver');
    expect(draft().chart.themeRiver).toEqual({
      x: 'day',
      splitBy: 'warehouse',
      value: 'total',
    });
    await optionsOf('theme river', 'Data');
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Value'));
    await user.click(
      await screen.findByRole('option', { name: 'Record count' }),
    );
    await waitFor(() => expect(draft().chart.themeRiver?.value).toBe('orders'));
    await user.click(screen.getByLabelText('One stream per'));
    await user.click(await screen.findByRole('option', { name: 'Warehouse' }));
    // Only a date is an axis a river runs along: the one date is listed.
    await user.click(screen.getByLabelText('Horizontal axis'));
    expect(await screen.findAllByRole('option')).toHaveLength(1);
    await user.keyboard('{Escape}');
    expect(draft().chart.themeRiver?.x).toBe('day');
  });
});

describe('the gauge in the workbench', () => {
  it('is picked over no dimension, and set to a target and a scale', async () => {
    const { draft } = await open(
      { type: 'metric', metric: { metric: 'orders' } },
      { groups: [] },
    );
    await visualize();
    // The two one-number tiles say what each answers.
    expect(
      tile('metric').querySelector('[data-slot="chart-hint"]')?.textContent,
    ).toBe('The number and its change');
    expect(
      tile('gauge').querySelector('[data-slot="chart-hint"]')?.textContent,
    ).toBe('Where it stands on a scale');
    fireEvent.click(tile('gauge'));
    const gauge = await frame('gauge');
    expect(gauge.getAttribute('data-marks')).toBe('1');
    expect(draft().chart.gauge).toEqual({ metric: 'orders' });

    await optionsOf('gauge', 'Display');
    const set = (name: string, value: string) => {
      const input = within(panel()).getByRole('textbox', { name });
      fireEvent.change(input, { target: { value } });
      fireEvent.blur(input);
    };
    set('Target value', '1000');
    set('Scale ends at', '800');
    await waitFor(() =>
      expect(draft().chart.gauge).toMatchObject({ target: 1000, max: 800 }),
    );
    await waitFor(() =>
      expect(
        document
          .querySelector('[data-chart="gauge"]')
          ?.getAttribute('data-beyond'),
      ).toBe('above'),
    );
    expect(
      document.querySelector('[data-slot="gauge-notes"]')?.textContent,
    ).toBe('The value is past the end of the scale');
    set('Target value', '');
    await waitFor(() =>
      expect(draft().chart.gauge).not.toHaveProperty('target'),
    );
  });
});

/** A press on the first painted shape stroked `stroke`, at its first edge. */
async function pressStroke(container: HTMLElement, stroke: string) {
  const mark = await waitFor(() => {
    const found = [
      ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
    ].filter(path => path.getAttribute('stroke') === stroke);
    expect(found.length).toBeGreaterThan(0);
    return found[0]!;
  });
  const points = [
    ...(mark.getAttribute('d') ?? '').matchAll(
      /[ML]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
    ),
  ].map(([, x, y]) => [Number(x), Number(y)]);
  const [dx, dy] = /translate\((-?[\d.]+)[\s,]+(-?[\d.]+)\)/
    .exec(mark.getAttribute('transform') ?? '')
    ?.slice(1)
    .map(Number) ?? [0, 0];
  const [a, b] = points;
  const at = {
    clientX: (a![0]! + b![0]!) / 2 + dx!,
    clientY: (a![1]! + b![1]!) / 2 + dy!,
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

function draw(data: ChartData, spec: ChartSpec, onPick = vi.fn()) {
  const view = render(
    <ViewSurface>
      <AnalysisChart data={data} spec={spec} onPick={onPick} />
    </ViewSurface>,
  );
  return { ...view, onPick };
}

describe('a statistical chart pressed and read', () => {
  it('hands a box’s group back, and reads its five numbers', async () => {
    const { container, onPick } = draw(
      {
        type: 'boxplot',
        boxes: [
          { group: 'CN', low: 1, q1: 2, median: 3, q3: 4, high: 5 },
          { group: 'US', low: 2, q1: 3, median: 6, q3: 7, high: 9 },
        ],
        omitted: 1,
        approximate: true,
      },
      {
        type: 'boxplot',
        boxplot: {
          category: 'warehouse',
          low: 'min',
          q1: 'p25',
          median: 'p50',
          q3: 'p75',
          high: 'max',
        },
      },
    );
    await pressStroke(container, FIRST);
    expect(onPick).toHaveBeenCalled();
    expect(onPick.mock.calls[0]![0]).toEqual({ warehouse: 'CN' });
    expect(readingRows(container)).toEqual([
      ['CN', '1', '2', '3', '4', '5'],
      ['US', '2', '3', '6', '7', '9'],
    ]);
    expect(
      container.querySelector('[data-slot="boxplot-notes"]')?.textContent,
    ).toContain('1 groups without all five numbers are not drawn');
    expect(
      container
        .querySelector('[data-slot="chart-plot"]')
        ?.getAttribute('aria-description') ?? container.textContent,
    ).toContain('highest median US, 6');
  });

  it('hands a radar’s shape back, and names every group in its legend', async () => {
    const { container, onPick } = draw(
      {
        type: 'radar',
        metrics: ['a', 'b', 'c'],
        profiles: [
          { group: 'CN', values: [3, 3, 3] },
          { group: 'US', values: [1, 1, 1] },
        ],
        omitted: 3,
      },
      {
        type: 'radar',
        radar: { category: 'warehouse', metrics: ['a', 'b', 'c'] },
      },
    );
    await pressStroke(container, FIRST);
    expect(onPick.mock.calls[0]?.[0]).toEqual({ warehouse: 'CN' });
    const legend = container.querySelector('[data-slot="chart-legend"]')!;
    expect(legend.textContent).toContain('CN');
    expect(legend.textContent).toContain('US');
    expect(
      container.querySelector('[data-slot="radar-notes"]')?.textContent,
    ).toBe('3 more groups are only in the table');
    expect(readingRows(container)).toEqual([
      ['CN', '3', '3', '3'],
      ['US', '1', '1', '1'],
    ]);
  });

  it('draws many parallel lines in one colour, with no legend', async () => {
    const profiles = Array.from({ length: 12 }, (_, at) => ({
      group: `W${at}`,
      values: [at, at * 2, 12 - at],
    }));
    const { container, onPick } = draw(
      { type: 'parallel', metrics: ['a', 'b', 'c'], profiles, omitted: 0 },
      {
        type: 'parallel',
        parallel: { category: 'warehouse', metrics: ['a', 'b', 'c'] },
      },
    );
    const chart = await frame('parallel');
    expect(chart.getAttribute('data-marks')).toBe('12');
    expect(container.querySelector('[data-slot="chart-legend"]')).toBeNull();
    await pressStroke(container, FIRST);
    expect(onPick).toHaveBeenCalled();
  });

  it('reads a gauge’s target, share and scale', async () => {
    const { container } = draw(
      {
        type: 'gauge',
        value: 870,
        target: 1000,
        reached: 0.87,
        min: 0,
        max: 1000,
      },
      { type: 'gauge', gauge: { metric: 'orders', target: 1000 } },
    );
    await frame('gauge');
    expect(readingRows(container)).toEqual([
      ['Value', '870'],
      ['Target', '1,000'],
      ['Of the target', '87.0%'],
      ['Scale start', '0'],
      ['Scale end', '1,000'],
    ]);
    expect(container.textContent).toContain('870, 87.0% of the target 1,000.');
  });
});
