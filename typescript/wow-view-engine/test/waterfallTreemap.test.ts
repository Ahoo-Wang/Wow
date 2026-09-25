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

import { describe, expect, it } from 'vitest';
import {
  fitChartSlots,
  leadMetric,
  shapeChart,
  switchChartType,
  validateChart,
  type TreemapData,
  type WaterfallData,
} from '../src/analysis/index.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  ChartSpec,
} from '../src/model/index.js';
import { readChart } from '../src/ui/charts/reading.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';
import { treemapOption } from '../src/ui/charts/treemapOption.js';
import { waterfallOption } from '../src/ui/charts/waterfallOption.js';
import { formatMessage, zhCN } from '../src/ui/index.js';
import { analysisConfig } from './fixtures.js';

const warehouse: AnalysisGroup = {
  type: 'TERMS',
  field: 'warehouse',
  alias: 'warehouse',
};
const region: AnalysisGroup = {
  type: 'TERMS',
  field: 'region',
  alias: 'region',
};
const month: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'month',
  unit: 'MONTH',
};
const count: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const sum: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'amount',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const average: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'avg',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};

const theme: ChartTheme = {
  palette: ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'Geist',
  key: 'test',
  resolve: color =>
    ({
      'var(--rise)': 'rgb(1, 102, 48)',
      'var(--fall)': 'rgb(193, 0, 7)',
      'var(--chart-1)': 'rgb(38, 117, 211)',
      'var(--chart-2)': 'rgb(235, 104, 52)',
    })[color] ?? 'rgb(0, 131, 0)',
};

const label = (alias: string | undefined, value: unknown, compact?: boolean) =>
  `${compact ? '~' : ''}${String(value)}`;
const column = (alias: string | undefined) => alias && `title:${alias}`;
const words = {
  total: '合计',
  increase: '增加',
  decrease: '减少',
  running: '累计',
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = any;

const messages = {
  label: (key: string, params?: Record<string, unknown>, fallback?: string) =>
    formatMessage(zhCN, key as never, params as never) ?? fallback ?? key,
} as Loose;

describe('a waterfall', () => {
  const waterfallSpec: ChartSpec = {
    type: 'waterfall',
    waterfall: { x: 'warehouse', value: 'amount' },
  };
  const config = analysisConfig({ metrics: [sum], chart: waterfallSpec });
  const rows = [
    { warehouse: '期初', amount: 100 },
    { warehouse: '销售', amount: 40 },
    { warehouse: '退款', amount: -60 },
    { warehouse: '调整', amount: 0 },
  ];

  /**
   * Each row is a step: its number the change, floating from where the
   * steps before it left the running total, which starts at zero; the last
   * bar is the total they arrive at. The rows' order is the view's, since
   * a waterfall is read in that order.
   */
  it('steps from zero through each row, in the rows’ order, to their total', () => {
    const data = shapeChart(config, rows) as WaterfallData;
    expect(data).toEqual({
      type: 'waterfall',
      steps: [
        { x: '期初', value: 100, start: 0, end: 100 },
        { x: '销售', value: 40, start: 100, end: 140 },
        { x: '退款', value: -60, start: 140, end: 80 },
        { x: '调整', value: 0, start: 80, end: 80 },
      ],
      total: 80,
    });
    // Asked for none, there is no closing bar.
    expect(
      shapeChart(
        {
          ...config,
          chart: {
            ...waterfallSpec,
            waterfall: { ...waterfallSpec.waterfall!, total: false },
          },
        },
        rows,
      ),
    ).not.toHaveProperty('total');
  });

  it('runs a time axis forward, and leaves a period the rows lack out', () => {
    const data = shapeChart(
      analysisConfig({
        groups: [month],
        metrics: [count],
        chart: {
          type: 'waterfall',
          waterfall: { x: 'month', value: 'orders' },
        },
      }),
      [
        { month: Date.UTC(2026, 2, 1), orders: 3 },
        { month: Date.UTC(2026, 0, 1), orders: 5 },
      ],
    ) as WaterfallData;
    expect(data.steps.map(step => [step.x, step.start, step.end])).toEqual([
      [Date.UTC(2026, 0, 1), 0, 5],
      [Date.UTC(2026, 2, 1), 5, 8],
    ]);
  });

  /**
   * Bars on an unseen base: the base is where each bar starts, the bar as
   * long as its change; a rise wears `--rise`, a fall `--fall` (the
   * convention's pair), the total the first slot. A value is written past the
   * bar's end, signed on a step, and whole in the tooltip with the running
   * total under it.
   */
  it('draws each step floating on the running total, coloured by its direction', () => {
    const data = shapeChart(config, rows) as WaterfallData;
    const option = waterfallOption(
      data,
      {
        spec: waterfallSpec,
        label,
        column,
        animate: false,
        pickable: true,
        words,
      },
      theme,
    ) as Loose;
    expect(option.xAxis.data).toEqual(['期初', '销售', '退款', '调整', '合计']);
    const [base, bars] = option.series;
    expect(base.data).toEqual([0, 100, 80, 80, 0]);
    expect(base.itemStyle.color).toBe('transparent');
    expect(bars.id).toBe('s0');
    expect(bars.stackStrategy).toBe('all');
    expect(bars.data.map((bar: Loose) => bar.value)).toEqual([
      100, 40, 60, 0, 80,
    ]);
    expect(bars.data.map((bar: Loose) => bar.itemStyle.color)).toEqual([
      'rgb(1, 102, 48)',
      'rgb(1, 102, 48)',
      'rgb(193, 0, 7)',
      'rgb(1, 102, 48)',
      'rgb(38, 117, 211)',
    ]);
    expect(
      bars.data.map((bar: Loose) => [
        bar.label.formatter(),
        bar.label.position,
      ]),
    ).toEqual([
      ['+~100', 'top'],
      ['+~40', 'top'],
      ['~-60', 'bottom'],
      ['~0', 'top'],
      ['~80', 'top'],
    ]);
    // Written on the page, not on the fill: the foreground ink.
    expect(bars.data[2].label.color).toBe(theme.foreground);
    const tip = option.tooltip.formatter({ dataIndex: 2 });
    expect(tip).toContain('退款');
    expect(tip).toContain('减少');
    expect(tip).toContain('-60');
    expect(tip).toContain('累计');
    expect(tip).toContain('80');
    expect(option.tooltip.formatter({ dataIndex: 9 })).toBe('');
  });

  it('floats a step that crosses zero across it, and stands a negative total below it', () => {
    const data: WaterfallData = {
      type: 'waterfall',
      steps: [
        { x: 'a', value: 5, start: 0, end: 5 },
        { x: 'b', value: -8, start: 5, end: -3 },
      ],
      total: -3,
    };
    const option = waterfallOption(
      data,
      {
        spec: waterfallSpec,
        label,
        column,
        animate: true,
        pickable: false,
        words,
      },
      theme,
    ) as Loose;
    const [base, bars] = option.series;
    // From -3 up to 5: the base reaches down to -3, the bar is 8 long.
    expect(base.data).toEqual([0, -3, -3]);
    expect(bars.data.map((bar: Loose) => bar.value)).toEqual([5, 8, 3]);
    expect(bars.data[2].label.position).toBe('bottom');
    expect(bars.cursor).toBe('default');
  });

  it('writes no value on a bar when the analyst turned them off', () => {
    const option = waterfallOption(
      shapeChart(config, rows) as WaterfallData,
      {
        spec: { ...waterfallSpec, labels: false },
        label,
        column,
        animate: false,
        pickable: false,
        words,
      },
      theme,
    ) as Loose;
    expect(
      option.series[1].data.every((bar: Loose) => bar.label.show === false),
    ).toBe(true);
  });

  it('reads as its steps, each change and running total, then the total', () => {
    const reading = readChart(shapeChart(config, rows)!, waterfallSpec, {
      messages,
      label: (_alias, value) => String(value),
      column: alias => (alias === 'amount' ? '金额的总和' : '仓库'),
      locale: 'zh-CN',
    });
    expect(reading.name).toBe('瀑布图：金额的总和，按仓库');
    expect(reading.header).toEqual(['仓库', '变化', '累计']);
    expect(reading.rows).toEqual([
      ['期初', '+100', '100'],
      ['销售', '+40', '140'],
      ['退款', '-60', '80'],
      ['调整', '0', '80'],
      ['合计', '', '80'],
    ]);
  });

  /**
   * Its slots: the one dimension it steps along and a metric that adds up.
   * An average carried in from a bar chart gives way to the count beside
   * it; with nothing that adds up the slot stays empty and the rules say
   * why, in a finding of its own.
   */
  it('fills its slots from what adds up, and refuses what does not', () => {
    expect(
      fitChartSlots({ type: 'waterfall' }, [warehouse], [average, count]),
    ).toEqual({
      type: 'waterfall',
      waterfall: { x: 'warehouse', value: 'orders' },
    });
    const kept = fitChartSlots(
      {
        type: 'waterfall',
        waterfall: { x: 'gone', value: 'amount', total: false },
      },
      [warehouse],
      [count, sum],
    );
    expect(kept.waterfall).toEqual({
      x: 'warehouse',
      value: 'amount',
      total: false,
    });
    const refused = validateChart(
      analysisConfig({
        metrics: [average],
        chart: {
          type: 'waterfall',
          waterfall: { x: 'warehouse', value: 'avg' },
        },
      }),
    );
    expect(refused.map(issue => [issue.code, issue.path])).toEqual([
      ['chart.waterfall.not-additive', ['chart', 'waterfall', 'value']],
    ]);
    expect(
      validateChart(
        analysisConfig({
          groups: [warehouse, region],
          chart: {
            type: 'waterfall',
            waterfall: { x: 'nowhere', value: 'orders' },
          },
        }),
      ).map(issue => issue.code),
    ).toEqual([
      'chart.group.unknown',
      'chart.group.unconsumed',
      'chart.group.unconsumed',
    ]);
    expect(validateChart(config)).toEqual([]);
  });

  it('carries the lead metric in and out, as every type switch does', () => {
    const bars: ChartSpec = {
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'amount' }] },
    };
    const switched = switchChartType(bars, 'waterfall');
    expect(switched.waterfall).toEqual({ x: '', value: 'amount' });
    expect(leadMetric(switched)).toBe('amount');
    expect(
      switchChartType(
        { type: 'waterfall', waterfall: { x: 'warehouse', value: 'amount' } },
        'waterfall',
      ).waterfall,
    ).toEqual({ x: 'warehouse', value: 'amount' });
  });
});

describe('a treemap', () => {
  const flatSpec: ChartSpec = {
    type: 'treemap',
    treemap: { category: 'warehouse', value: 'orders' },
  };
  const nestedSpec: ChartSpec = {
    type: 'treemap',
    treemap: { category: 'warehouse', parent: 'region', value: 'orders' },
  };

  /**
   * A tile a row, largest first; a row whose number is not above zero has
   * no area and is counted rather than drawn.
   */
  it('tiles the rows largest first, counting those with no area', () => {
    const data = shapeChart(analysisConfig({ chart: flatSpec }), [
      { warehouse: 'A', orders: 2 },
      { warehouse: 'B', orders: 5 },
      { warehouse: 'C', orders: 0 },
      { warehouse: 'D', orders: -1 },
      { warehouse: 'E' },
    ]) as TreemapData;
    expect(data).toEqual({
      type: 'treemap',
      tiles: [
        { group: 'B', value: 5 },
        { group: 'A', value: 2 },
      ],
      nested: false,
      omitted: 3,
    });
  });

  it('nests the tiles in a block per outer value, each level largest first', () => {
    const data = shapeChart(
      analysisConfig({ groups: [warehouse, region], chart: nestedSpec }),
      [
        { region: '华东', warehouse: 'A', orders: 1 },
        { region: '华南', warehouse: 'B', orders: 4 },
        { region: '华东', warehouse: 'C', orders: 2 },
        { region: 1, warehouse: 'D', orders: 1 },
        { region: '1', warehouse: 'E', orders: 1 },
      ],
    ) as TreemapData;
    expect(data.nested).toBe(true);
    // The number 1 and the text 「1」 are two blocks, as the query kept them.
    expect(data.tiles).toEqual([
      { group: '华南', value: 4, tiles: [{ group: 'B', value: 4 }] },
      {
        group: '华东',
        value: 3,
        tiles: [
          { group: 'C', value: 2 },
          { group: 'A', value: 1 },
        ],
      },
      { group: 1, value: 1, tiles: [{ group: 'D', value: 1 }] },
      { group: '1', value: 1, tiles: [{ group: 'E', value: 1 }] },
    ]);
  });

  /**
   * Up to eight tiles each take a slot; past eight a slot handed out twice
   * would say two tiles are one thing, so the level wears the first slot,
   * shaded toward the ground by rank. Each tile carries its name and its
   * number, in the ink that stands off its own fill, and its tooltip says
   * its share of the whole.
   */
  it('colours a tile a slot up to eight, one hue shaded by rank past that', () => {
    const few: TreemapData = {
      type: 'treemap',
      tiles: [
        { group: 'A', value: 3 },
        { group: 'B', value: 1 },
      ],
      nested: false,
      omitted: 0,
    };
    const option = treemapOption(
      few,
      { spec: flatSpec, label, column, animate: false, pickable: true },
      theme,
    ) as Loose;
    const [series] = option.series;
    expect(series.type).toBe('treemap');
    expect(series.roam).toBe(false);
    expect(series.nodeClick).toBe(false);
    expect(series.breadcrumb.show).toBe(false);
    expect(series.data.map((node: Loose) => node.itemStyle.color)).toEqual([
      'rgb(38, 117, 211)',
      'rgb(235, 104, 52)',
    ]);
    expect(series.label.formatter({ data: series.data[0] })).toBe(
      '{name|A}\n{value|~3}',
    );
    expect(series.data[0].label.color).toBe('rgb(255, 255, 255)');
    const tip = option.tooltip.formatter({ data: series.data[1] });
    expect(tip).toContain('B');
    expect(tip).toContain('title:orders');
    expect(tip).toContain('1 · 25.0%');
    expect(tip).toContain('fill="rgb(235, 104, 52)"');

    const many: TreemapData = {
      type: 'treemap',
      tiles: Array.from({ length: 12 }, (_, index) => ({
        group: `g${index}`,
        value: 12 - index,
      })),
      nested: false,
      omitted: 0,
    };
    const colours = (
      treemapOption(
        many,
        { spec: flatSpec, label, column, animate: false, pickable: false },
        theme,
      ) as Loose
    ).series[0].data.map((node: Loose) => node.itemStyle.color);
    expect(new Set(colours).size).toBe(12);
    expect(colours[0]).toBe('rgb(38, 117, 211)');
    // The smallest is the slot at 40% over the ground, still a shade of it.
    expect(colours[11]).not.toBe('rgb(255, 255, 255)');
  });

  it('heads a block with its name and fades what a press did not light', () => {
    const data: TreemapData = {
      type: 'treemap',
      tiles: [
        {
          group: '华东',
          value: 3,
          tiles: [
            { group: 'C', value: 2 },
            { group: 'A', value: 1 },
          ],
        },
      ],
      nested: true,
      omitted: 0,
    };
    const option = treemapOption(
      data,
      {
        spec: nestedSpec,
        label,
        column,
        animate: false,
        pickable: true,
        highlight: row => row.warehouse === 'A',
      },
      theme,
    ) as Loose;
    const [series] = option.series;
    expect(series.upperLabel.show).toBe(true);
    expect(series.upperLabel.formatter).toBe('{b}');
    const block = series.data[0];
    expect(block.name).toBe('华东');
    expect(block.children.map((node: Loose) => node.id)).toEqual(['t0', 't1']);
    expect(block.children[0].itemStyle.opacity).toBe(0.5);
    expect(block.children[1].itemStyle.opacity).toBeUndefined();
    // An outer block is no tile: no tooltip, no label of a tile.
    expect(option.tooltip.formatter({ data: block })).toBe('');
    expect(series.label.formatter({ data: block })).toBe('');
    expect(option.tooltip.formatter({ data: block.children[1] })).toContain(
      '华东 · A',
    );
  });

  it('reads as its tiles, the outer level first, with each share', () => {
    const data = shapeChart(
      analysisConfig({ groups: [warehouse, region], chart: nestedSpec }),
      [
        { region: '华东', warehouse: 'A', orders: 1 },
        { region: '华东', warehouse: 'C', orders: 3 },
      ],
    )!;
    const reading = readChart(data, nestedSpec, {
      messages,
      label: (_alias, value) => String(value),
      column: alias =>
        ({ orders: '记录数', region: '地区', warehouse: '仓库' })[alias ?? ''],
      locale: 'zh-CN',
    });
    expect(reading.name).toBe('矩形树图：记录数，按地区、仓库');
    expect(reading.header).toEqual(['地区', '仓库', '记录数', '占比']);
    expect(reading.rows).toEqual([
      ['华东', 'C', '3', '75.0%'],
      ['华东', 'A', '1', '25.0%'],
    ]);
  });

  it('fills its levels from the dimensions, and refuses what does not add up', () => {
    expect(
      fitChartSlots({ type: 'treemap' }, [warehouse, region], [average, sum])
        .treemap,
    ).toEqual({ category: 'warehouse', parent: 'region', value: 'amount' });
    // One dimension left, the outer level goes with the other.
    expect(
      fitChartSlots(
        {
          type: 'treemap',
          treemap: { category: 'region', parent: 'warehouse', value: 'orders' },
        },
        [region],
        [count],
      ).treemap,
    ).toEqual({ category: 'region', value: 'orders' });
    expect(
      validateChart(
        analysisConfig({
          groups: [warehouse, region],
          metrics: [average],
          chart: {
            type: 'treemap',
            treemap: {
              category: 'warehouse',
              parent: 'warehouse',
              value: 'avg',
            },
          },
        }),
      ).map(issue => issue.code),
    ).toEqual([
      'chart.treemap.not-additive',
      'chart.treemap.same-levels',
      'chart.group.unconsumed',
    ]);
    expect(
      validateChart(
        analysisConfig({ groups: [warehouse, region], chart: nestedSpec }),
      ),
    ).toEqual([]);
    expect(leadMetric(switchChartType(nestedSpec, 'pie'))).toBe('orders');
    expect(
      switchChartType(
        { type: 'pie', pie: { category: 'warehouse', value: 'amount' } },
        'treemap',
      ).treemap,
    ).toEqual({ category: '', value: 'amount' });
  });
});
