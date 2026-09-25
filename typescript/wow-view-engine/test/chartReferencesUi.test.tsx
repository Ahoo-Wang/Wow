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
  shapeChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CartesianData,
  type CartesianSpec,
  type ChartSpec,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { AnalysisChart } from '../src/ui/AnalysisChart.js';
import { cartesianOption } from '../src/ui/charts/cartesianOption.js';
import type { CartesianContext } from '../src/ui/charts/cartesianPlan.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';
import { DataWorkbench, ViewSurface, zhCN } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { describedText } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * What batch B draws over a cartesian chart's marks, as the reader meets
 * it: a statistic line and a target band where the kernel placed them, the
 * highest and lowest points marked, a computed line dashed in the
 * foreground and named as computed in the tooltip, the legend and the
 * reading table — and, where the rows are not whole, the line left out and
 * the reason said over the chart and on the options page (Q53).
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

const THEME: ChartTheme = {
  palette: [],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'sans-serif',
  key: 'light',
  resolve: () => 'rgb(30, 60, 160)',
};

const day = (n: number) => Date.UTC(2026, 8, n);

const DAILY: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'day',
  unit: 'DAY',
  timeZone: 'UTC',
};
const ORDERS: AnalysisMetric = { type: 'COUNT', alias: 'orders' };

/** Five days, the third one known empty. */
const ROWS: RecordData[] = [
  { day: day(1), orders: 4 },
  { day: day(2), orders: 8 },
  { day: day(4), orders: 2 },
  { day: day(5), orders: 6 },
];

function config(
  cartesian: Partial<CartesianSpec>,
  type: 'bar' | 'line' = 'line',
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [DAILY],
    metrics: [ORDERS],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type,
      cartesian: { x: 'day', series: [{ metric: 'orders' }], ...cartesian },
    },
  };
}

const WORDS: NonNullable<CartesianContext['words']> = {
  derived: line => `${line.kind}（算出的）`,
  statistic: (of, value) => `${of} ${value}`,
  high: '最高',
  low: '最低',
  other: '其他',
};

function optionOver(cfg: AnalysisViewConfig, cutShort = false): Loose {
  const data = shapeChart(cfg, ROWS, undefined, {
    timeZone: 'UTC',
    cutShort,
  }) as CartesianData;
  return cartesianOption(
    data,
    {
      spec: cfg.chart,
      label: (_alias, value) => String(value),
      column: alias => alias,
      locale: 'zh-CN',
      animate: false,
      pickable: false,
      words: WORDS,
    },
    THEME,
  ) as Loose;
}

describe('the option over the marks', () => {
  it('stands an average line at the measured values’ mean, captioned', () => {
    const option = optionOver(
      config({
        referenceLines: [
          { axis: 'left', statistic: 'average', metric: 'orders' },
        ],
      }),
    );
    const carrier = option.series.find((entry: Loose) => entry.markLine);
    // (4 + 8 + 2 + 6) / 4: the filled day is no day of 0.
    expect(carrier.markLine.data).toEqual([{ yAxis: 5 }]);
    expect(carrier.markLine.label.formatter({ dataIndex: 0 })).toBe(
      'average 5',
    );
  });

  it('shades a target band behind the marks on its axis', () => {
    const option = optionOver(
      config({
        referenceBands: [{ axis: 'left', from: 3, to: 6, label: '目标' }],
      }),
    );
    const carrier = option.series.find((entry: Loose) => entry.markArea);
    expect(carrier.markArea.data).toEqual([
      [{ yAxis: 3, name: '目标' }, { yAxis: 6 }],
    ]);
    expect(carrier.markArea.silent).toBe(true);
    expect(carrier.markLine).toBeUndefined();
  });

  it('marks the highest and the lowest point and writes their numbers once', () => {
    const option = optionOver(config({ extremes: true }, 'bar'));
    const bars = option.series[0];
    expect(bars.markPoint.data).toEqual([
      expect.objectContaining({ coord: [1, 8], value: 8 }),
      expect.objectContaining({ coord: [3, 2], value: 2 }),
    ]);
    expect(bars.markPoint.data[0].label.formatter).toBe('最高 8');
    expect(bars.markPoint.data[1].label.formatter).toBe('最低 2');
    // The bar's own label is not written where the mark writes it.
    expect(bars.label.formatter({ value: 8, dataIndex: 1 })).toBe('');
    expect(bars.label.formatter({ value: 4, dataIndex: 0 })).toBe('4');
  });

  it('does not mark a segment of a stack of more than one', () => {
    const TOTAL: AnalysisMetric = {
      type: 'NUMERIC',
      alias: 'total',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'amount' },
    };
    const cfg: AnalysisViewConfig = {
      ...config({}, 'bar'),
      metrics: [ORDERS, TOTAL],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'day',
          extremes: true,
          series: [
            { metric: 'orders', stack: 'all' },
            { metric: 'total', stack: 'all' },
          ],
        },
      },
    };
    const data = shapeChart(
      cfg,
      ROWS.map(row => ({ ...row, total: (row.orders as number) * 10 })),
      undefined,
      { timeZone: 'UTC' },
    ) as CartesianData;
    // The kernel finds them; the drawing declines to pin them on segments.
    expect(data.extremes).toBeDefined();
    const option = cartesianOption(
      data,
      {
        spec: cfg.chart,
        label: (_alias, value) => String(value),
        column: alias => alias,
        animate: false,
        pickable: false,
      },
      THEME,
    ) as Loose;
    expect(option.series[0].markPoint).toBeUndefined();
    expect(option.series[1].markPoint).toBeUndefined();
  });

  it('draws a derived line dashed in the foreground, unstacked, named as computed in the tooltip', () => {
    const option = optionOver(
      config(
        {
          series: [{ metric: 'orders', stack: 'all' }],
          derived: [{ kind: 'cumulative', metric: 'orders' }],
        },
        'bar',
      ),
    );
    const line = option.series.find((entry: Loose) => entry.id === 'd0');
    expect(line).toMatchObject({
      type: 'line',
      data: [4, 12, 12, 14, 20],
      lineStyle: { color: THEME.foreground, type: [6, 4] },
      symbol: 'none',
      silent: true,
    });
    expect(line.stack).toBeUndefined();
    const html = option.tooltip.formatter([{ dataIndex: 1 }]) as string;
    expect(html).toContain('cumulative（算出的）');
    expect(html).toContain('12');
  });

  it('puts a running total on the free axis, titled by it; a trend stays on its series’ axis', () => {
    const option = optionOver(
      config(
        {
          derived: [
            { kind: 'cumulative', metric: 'orders' },
            { kind: 'trend', metric: 'orders' },
          ],
        },
        'bar',
      ),
    );
    const running = option.series.find((entry: Loose) => entry.id === 'd0');
    const trend = option.series.find((entry: Loose) => entry.id === 'd1');
    expect(running.yAxisIndex).toBe(1);
    expect(trend.yAxisIndex).toBe(0);
    expect(option.yAxis).toHaveLength(2);
    expect(option.yAxis[1].name).toBe('cumulative（算出的）');
    // The bars keep a scale of their own: their axis ends near their top.
    expect(option.yAxis[0].max).toBeLessThan(20);
  });

  it('draws no derived line over rows cut short', () => {
    const option = optionOver(
      config({ derived: [{ kind: 'trend', metric: 'orders' }] }),
      true,
    );
    expect(option.series.some((entry: Loose) => entry.id === 'd0')).toBe(false);
  });
});

describe('the chart as the reader meets it', () => {
  const draw = (cfg: AnalysisViewConfig, cutShort = false) => {
    const data = shapeChart(cfg, ROWS, undefined, {
      timeZone: 'UTC',
      cutShort,
    }) as CartesianData;
    return render(
      <ViewSurface>
        <AnalysisChart data={data} spec={cfg.chart} />
      </ViewSurface>,
    );
  };

  it('lists a derived line in the legend as a dash, and switches it off with the reading table', () => {
    const { container } = draw(
      config({
        derived: [{ kind: 'moving-average', metric: 'orders', window: 2 }],
      }),
    );
    const dash = container.querySelector('[data-slot="chart-legend-dash"]');
    expect(dash).not.toBeNull();
    const name = '2-period moving average (computed)';
    const reading = () =>
      container.querySelector('[data-slot="chart-reading"]')!;
    expect(within(reading() as HTMLElement).getByText(name)).toBeTruthy();
    // The first bucket has no window behind it yet: no number, said so.
    const toggle = screen.getByRole('button', { name });
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    expect(within(reading() as HTMLElement).queryByText(name)).toBeNull();
  });

  it('says over the chart why a line it was asked for is not drawn', () => {
    const { container } = draw(
      config({ derived: [{ kind: 'cumulative', metric: 'orders' }] }),
      true,
    );
    expect(
      container.querySelector('[data-slot="chart-gap-note"]')?.textContent,
    ).toBe(
      'Running total (computed) is not drawn: the result shows only the first 100 groups, so the line would be incomplete.',
    );
    expect(container.querySelector('[data-slot="chart-legend-dash"]')).toBe(
      null,
    );
  });

  it('captions an average line with its number, and says why one over a split is not drawn', async () => {
    const { container } = draw(
      config({
        referenceLines: [
          { axis: 'left', statistic: 'average', metric: 'orders' },
        ],
      }),
    );
    await waitFor(() =>
      expect(
        [...container.querySelectorAll('[data-slot="chart-plot"] svg text')]
          .map(text => text.textContent)
          .some(text => text === 'Average 5'),
      ).toBe(true),
    );
    cleanup();

    const split: AnalysisViewConfig = {
      ...config({
        splitBy: 'wh',
        referenceLines: [
          { axis: 'left', statistic: 'median', metric: 'orders' },
        ],
      }),
      groups: [DAILY, { type: 'TERMS', field: 'warehouse', alias: 'wh' }],
    };
    const data = shapeChart(
      split,
      [
        { day: day(1), wh: 'CN', orders: 1 },
        { day: day(1), wh: 'US', orders: 2 },
      ],
      undefined,
      { timeZone: 'UTC' },
    ) as CartesianData;
    const again = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={split.chart} />
      </ViewSurface>,
    );
    expect(
      again.container.querySelector('[data-slot="chart-gap-note"]')
        ?.textContent,
    ).toBe(
      'Median orders is not drawn: a split draws a line per value, and an average or median line is drawn on an unsplit chart only.',
    );
  });

  it('says it in Chinese, in the analyst’s words', () => {
    const cfg = config({ derived: [{ kind: 'cumulative', metric: 'orders' }] });
    const data = shapeChart(cfg, ROWS, undefined, {
      timeZone: 'UTC',
      cutShort: true,
    }) as CartesianData;
    const { container } = render(
      <ViewSurface messages={zhCN}>
        <AnalysisChart data={data} spec={cfg.chart} />
      </ViewSurface>,
    );
    expect(
      container.querySelector('[data-slot="chart-gap-note"]')?.textContent,
    ).toBe('累计（算出的）没有画：结果只显示了前 100 组，算出的线会不完整。');
  });
});

describe('the display page', () => {
  async function open(
    chart: ChartSpec,
    overrides: Partial<AnalysisViewConfig> = {},
    rows: readonly RecordData[] = ROWS,
  ) {
    const source: ViewSource = testSource({
      aggregate: vi.fn((query: { groupBy?: unknown[] }) =>
        Promise.resolve(
          (query.groupBy?.length ?? 0) > 0
            ? rows.map(row => ({ ...row }))
            : [{ orders: 20 }],
        ),
      ) as ViewSource['aggregate'],
    });
    const instance: ViewInstance = {
      id: 'orders-1',
      definitionId: 'orders',
      title: 'By day',
      scope: 'personal',
      revision: '1',
      config: { ...config({}), chart, ...overrides },
    };
    const definition = ordersDefinition({
      fields: [
        { name: 'id', label: 'Order', kind: 'string', sortable: true },
        { name: 'createdAt', label: 'Created', kind: 'datetime' },
        { name: 'warehouse', label: 'Warehouse', kind: 'string' },
        { name: 'amount', label: 'Amount', kind: 'number', summary: ['SUM'] },
      ],
      analysis: {
        count: true,
        fields: [
          {
            field: 'createdAt',
            groups: [AggregationGroupType.DATE_HISTOGRAM],
            functions: [],
            dateUnits: [AggregationDateUnit.DAY],
          },
          {
            field: 'warehouse',
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
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="chart"][data-chart]'),
      ).not.toBeNull(),
    );
    fireEvent.click(
      screen.getByRole('button', { name: `${chart.type} options` }),
    );
    await waitFor(() => expect(panel()).not.toBeNull());
    fireEvent.click(within(panel()).getByRole('tab', { name: 'Display' }));
    return {
      queries: () => vi.mocked(source.aggregate).mock.calls.length,
      draft: () =>
        engine.openRuntimes()[0]!.getSnapshot().draft as AnalysisViewConfig,
    };
  }
  const panel = () =>
    document.querySelector<HTMLElement>('[data-slot="chart-options"]')!;

  it('turns a trend on and a moving average with its periods, asking nothing', async () => {
    const { draft, queries } = await open(config({}).chart);
    const ran = queries();
    fireEvent.click(
      within(panel()).getByRole('checkbox', { name: 'Trend line' }),
    );
    await waitFor(() =>
      expect(draft().chart.cartesian?.derived).toEqual([
        { kind: 'trend', metric: 'orders' },
      ]),
    );
    fireEvent.click(
      within(panel()).getByRole('checkbox', { name: 'Moving average' }),
    );
    // Five days are too few for a week's window: said under the box, which
    // stays pressable, and the periods lowered brings the line back.
    const average = within(panel()).getByRole('checkbox', {
      name: 'Moving average',
    });
    await waitFor(() =>
      expect(describedText(average)).toBe(
        'there are too few points to compute it.',
      ),
    );
    const periods = await within(panel()).findByLabelText('Periods');
    fireEvent.change(periods, { target: { value: '3' } });
    await waitFor(() =>
      expect(draft().chart.cartesian?.derived).toEqual([
        { kind: 'trend', metric: 'orders' },
        { kind: 'moving-average', metric: 'orders', window: 3 },
      ]),
    );
    await waitFor(() => expect(describedText(average)).toBe(''));
    expect(queries()).toBe(ran);
  });

  it('greys the computed lines with the reason when the rows are cut short', async () => {
    // A limit of four over five rows: the probe came back.
    await open(config({}).chart, { limit: 3 });
    const box = within(panel()).getByRole('checkbox', {
      name: 'Running total',
    });
    expect(box.hasAttribute('data-disabled')).toBe(true);
    expect(describedText(box)).toBe(
      'the result shows only the first 3 groups, so the line would be incomplete.',
    );
  });

  it('offers only a number for a reference line over a split, and says why', async () => {
    await open(
      {
        type: 'line',
        cartesian: {
          x: 'day',
          splitBy: 'wh',
          series: [{ metric: 'orders' }],
          referenceLines: [{ axis: 'left', value: 2 }],
        },
      },
      {
        groups: [DAILY, { type: 'TERMS', field: 'warehouse', alias: 'wh' }],
      },
      [
        { day: day(1), wh: 'CN', orders: 1 },
        { day: day(1), wh: 'US', orders: 2 },
      ],
    );
    expect(
      panel().querySelector('[data-slot="reference-statistic-gap"]')
        ?.textContent,
    ).toBe(
      'a split draws a line per value, and an average or median line is drawn on an unsplit chart only.',
    );
  });

  it('adds a target band over the values on screen and refuses one that runs downward', async () => {
    const { draft } = await open(config({}).chart);
    fireEvent.click(
      within(panel()).getByRole('button', { name: 'Add target band' }),
    );
    await waitFor(() =>
      expect(draft().chart.cartesian?.referenceBands).toEqual([
        { axis: 'left', from: 0, to: 8 },
      ]),
    );
    const card = panel().querySelector<HTMLElement>(
      '[data-slot="reference-band-card"]',
    )!;
    fireEvent.change(within(card).getByLabelText('To'), {
      target: { value: '-1' },
    });
    await waitFor(() =>
      expect(within(card).getByRole('alert').textContent).toBe(
        'The band runs from the smaller number to the larger one.',
      ),
    );
    expect(within(card).getByLabelText('To').getAttribute('aria-invalid')).toBe(
      'true',
    );
  });

  it('switches a reference line to the median of the metric', async () => {
    const { draft } = await open({
      type: 'line',
      cartesian: {
        x: 'day',
        series: [{ metric: 'orders' }],
        referenceLines: [{ axis: 'left', value: 2, label: '线' }],
      },
    });
    const card = panel().querySelector<HTMLElement>(
      '[data-slot="reference-line-card"]',
    )!;
    const user = userEvent.setup();
    await user.click(within(card).getByLabelText('Stands at'));
    await user.click(await screen.findByRole('option', { name: 'The median' }));
    await waitFor(() =>
      expect(draft().chart.cartesian?.referenceLines).toEqual([
        { axis: 'left', label: '线', statistic: 'median', metric: 'orders' },
      ]),
    );
  });
});
