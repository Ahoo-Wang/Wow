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
import type { FunnelData, HeatmapData } from '../src/index.js';
import { drawnStages, funnelOption } from '../src/ui/charts/funnelOption.js';
import { heatmapOption } from '../src/ui/charts/heatmapOption.js';
import { sparklineOption } from '../src/ui/charts/sparklineOption.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';

/** The sparkline, the funnel and the heatmap as the library is asked for them. */
const theme: ChartTheme = {
  palette: ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'],
  foreground: 'fg',
  muted: 'muted',
  border: 'rule',
  ground: 'ground',
  fontFamily: 'Geist',
  key: 'test',
  resolve: color => `resolved(${color})`,
};

const label = (alias: string | undefined, value: unknown, compact?: boolean) =>
  `${compact ? 'short ' : ''}${alias}=${String(value)}`;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

describe('sparklineOption', () => {
  const trend = [
    { x: 'd1', value: 3 },
    { x: 'd2', value: null },
    { x: 'd3', value: 5 },
  ];
  const option = sparklineOption(
    trend,
    { label, x: 'day', metric: 'orders', name: 'Trend', animate: false },
    theme,
  ) as Loose;

  it('is a line and a faint fill, with no axes and no dots', () => {
    expect(option.xAxis.show).toBe(false);
    expect(option.yAxis.show).toBe(false);
    expect(option.xAxis.data).toEqual(['day=d1', 'day=d2', 'day=d3']);
    const [line] = option.series;
    expect(line.data).toEqual([3, null, 5]);
    expect(line.showSymbol).toBe(false);
    expect(line.connectNulls).toBe(false);
    expect(line.areaStyle.opacity).toBe(0.12);
  });

  it('reads the point under the pointer, whole, and nothing at a gap', () => {
    const html: string = option.tooltip.formatter([{ dataIndex: 2 }]);
    expect(html).toContain('day=d3');
    expect(html).toContain('orders=5');
    expect(option.tooltip.formatter([{ dataIndex: 1 }])).toBe('');
    expect(option.tooltip.formatter([])).toBe('');
  });
});

describe('funnelOption', () => {
  const data: FunnelData = {
    type: 'funnel',
    stages: [
      { label: 'visited', value: 100, conversion: 1 },
      { label: 'bought', value: 25, conversion: 0.25 },
    ],
  };
  const context = {
    spec: {
      type: 'funnel' as const,
      funnel: {
        stages: {
          from: 'metrics' as const,
          items: [{ metric: 'visits', label: 'Visited' }, { metric: 'buys' }],
        },
      },
    },
    label,
    column: (alias: string | undefined) =>
      alias === 'buys' ? 'Bought' : undefined,
    locale: 'en',
    conversion: 'Conversion from previous stage',
    animate: false,
  };

  it('names each stage, reads it as its metric, and says its conversion', () => {
    expect(drawnStages(data, context)).toEqual([
      { name: 'Visited', value: 100, text: 'visits=100', conversion: '100%' },
      { name: 'Bought', value: 25, text: 'buys=25', conversion: '25%' },
    ]);
  });

  it('draws a centred bar a stage, in the order given, one colour, words beside it', () => {
    const option = funnelOption(data, context, theme) as Loose;
    const [before, stage, after] = option.series;
    // Bars, not the library's trapezoids: one length for one number
    // (2026-09-23 audit). The unseen bars either side centre it.
    expect(option.series.map((series: Loose) => series.type)).toEqual([
      'bar',
      'bar',
      'bar',
    ]);
    expect(new Set(option.series.map((series: Loose) => series.stack))).toEqual(
      new Set(['funnel']),
    );
    expect(stage.data).toEqual([100, 25]);
    expect(before.data).toEqual([0, 37.5]);
    expect(after.data).toEqual([0, 37.5]);
    expect(before.itemStyle.color).toBe('transparent');
    expect(before.silent).toBe(true);
    expect(stage.itemStyle.color).toBe('resolved(var(--chart-1))');
    // The first stage on top, never re-sorted.
    expect(option.yAxis.type).toBe('category');
    expect(option.yAxis.inverse).toBe(true);
    expect(option.yAxis.data).toEqual(['Visited', 'Bought']);
    expect(option.xAxis.max).toBe(100);
    // The words ride the bar past the stage, so they stand in one column.
    expect(after.label.position).toBe('right');
    expect(after.label.formatter({ dataIndex: 1 })).toBe(
      '{name|Bought}  {value|buys=25}  {rate|25%}',
    );
    expect(after.label.formatter({ dataIndex: 7 })).toBe('');
    expect(stage).not.toHaveProperty('label');
    expect(option.tooltip.formatter({ dataIndex: 0 })).toContain('visits=100');
    expect(option.tooltip.formatter({ dataIndex: 7 })).toBe('');
  });

  it('lays itself on its side, and says no rate where none is asked', () => {
    const plain: FunnelData = {
      type: 'funnel',
      stages: [{ label: 'a', value: -3 }],
    };
    const option = funnelOption(
      plain,
      {
        ...context,
        spec: {
          type: 'funnel',
          funnel: { ...context.spec.funnel, orientation: 'horizontal' },
        },
      },
      theme,
    ) as Loose;
    const [under, stage] = option.series;
    expect(option.xAxis.type).toBe('category');
    expect(option.xAxis.inverse).toBe(false);
    // The words ride the unseen bar under the stage, in one row.
    expect(under.label.position).toBe('bottom');
    // No negative stage is drawn.
    expect(stage.data).toEqual([0]);
    expect(under.label.formatter({ dataIndex: 0 })).toBe(
      '{name|Visited}\n{value|visits=-3}',
    );
  });
});

describe('heatmapOption', () => {
  const data: HeatmapData = {
    type: 'heatmap',
    xs: ['Mon', 'Tue'],
    ys: ['CN', 'JP'],
    cells: [
      [1, 100],
      [10, null],
    ],
  };
  const context = (scale?: 'log', labels?: boolean) => ({
    spec: {
      type: 'heatmap' as const,
      heatmap: { x: 'day', y: 'region', value: 'orders', scale },
      labels,
    },
    label,
    column: (alias: string | undefined) =>
      alias === 'orders' ? 'Orders' : alias,
    animate: false,
    pickable: true,
  });

  it('draws a cell per value, none where nothing fell, the first row on top', () => {
    const option = heatmapOption(data, context(), theme) as Loose;
    expect(option.series[0].data.map((cell: Loose) => cell.value)).toEqual([
      [0, 0, 1, 1],
      [1, 0, 100, 100],
      [0, 1, 10, 10],
    ]);
    // The scale reads the shade, the third place.
    expect(option.visualMap.dimension).toBe(2);
    expect(option.yAxis.inverse).toBe(true);
    expect(option.xAxis.data).toEqual(['day=Mon', 'day=Tue']);
    expect(option.xAxis.name).toBe('day');
    expect(option.series[0].cursor).toBe('pointer');
    expect(option.series[0].label.show).toBe(false);
  });

  it('keeps a colour scale, from the ground to the first slot', () => {
    const { visualMap } = heatmapOption(data, context(), theme) as Loose;
    expect([visualMap.min, visualMap.max]).toEqual([1, 100]);
    expect(visualMap.text).toEqual(['short orders=100', 'short orders=1']);
    expect(visualMap.formatter(40)).toBe('short orders=40');
  });

  it('runs its colours from a concrete pale end on the actual ground, in either mode', () => {
    // The dark theme's ground and first slot, as `readChartTheme` gives them.
    const dark: ChartTheme = {
      ...theme,
      ground: 'rgb(10, 10, 10)',
      resolve: () => 'rgb(60, 140, 240)',
    };
    const { visualMap } = heatmapOption(data, context(), dark) as Loose;
    // The palest cell is the slot at a fifth over the near-black ground —
    // dark, as the cells are — not the slot at a fifth over nothing, which
    // the bar drew bright; no opacity is left for the library to composite.
    expect(visualMap.inRange).toEqual({
      color: ['rgb(20, 36, 56)', 'rgb(60, 140, 240)'],
    });
    const light: ChartTheme = {
      ...dark,
      ground: 'rgb(255, 255, 255)',
    };
    expect(
      (heatmapOption(data, context(), light) as Loose).visualMap.inRange.color,
    ).toEqual(['rgb(216, 232, 252)', 'rgb(60, 140, 240)']);
  });

  it('keeps the slot when the ground reads as no colour', () => {
    const { visualMap } = heatmapOption(data, context(), theme) as Loose;
    expect(visualMap.inRange.color).toEqual([
      'resolved(var(--chart-1))',
      'resolved(var(--chart-1))',
    ]);
  });

  it('shades by the log on a log scale, and still writes the numbers', () => {
    const option = heatmapOption(data, context('log', true), theme) as Loose;
    expect(option.series[0].data[1].value[2]).toBeCloseTo(Math.log1p(99));
    expect(option.series[0].data[1].value[3]).toBe(100);
    expect(option.visualMap.max).toBeCloseTo(Math.log1p(99));
    // The scale's ends are read back out of the log.
    expect(
      Number(option.visualMap.formatter(Math.log1p(9)).split('=')[1]),
    ).toBeCloseTo(10);
    expect(option.series[0].label.show).toBe(true);
    expect(option.series[0].label.formatter({ value: [0, 0, 4.6, 100] })).toBe(
      'short orders=100',
    );
    expect(option.tooltip.formatter({ value: [1, 0, 4.6, 100] })).toContain(
      'region=CN · day=Tue',
    );
  });

  it('writes each number in the ink that stands off its own cell (audit)', () => {
    const light: ChartTheme = {
      ...theme,
      foreground: 'rgb(10, 10, 10)',
      ground: 'rgb(255, 255, 255)',
      resolve: () => 'rgb(30, 60, 160)',
    };
    const [palest, deepest] = (
      heatmapOption(data, context(undefined, true), light) as Loose
    ).series[0].data;
    // The palest cell takes the dark ink, the deepest the ground's.
    expect(palest.label.color).toBe('rgb(10, 10, 10)');
    expect(deepest.label.color).toBe('rgb(255, 255, 255)');
    // No halo: the ink is the contrast.
    const series = (
      heatmapOption(data, context(undefined, true), light) as Loose
    ).series[0];
    expect(series.label).not.toHaveProperty('textBorderWidth');
    expect(series).not.toHaveProperty('labelLayout');
  });

  it('gives an even matrix a scale that still has two ends', () => {
    const even: HeatmapData = {
      ...data,
      cells: [[5]],
      xs: ['Mon'],
      ys: ['CN'],
    };
    const { visualMap } = heatmapOption(even, context(), theme) as Loose;
    expect([visualMap.min, visualMap.max]).toEqual([4, 5]);
    const empty: HeatmapData = {
      ...data,
      cells: [[null]],
      xs: ['Mon'],
      ys: ['CN'],
    };
    expect(
      (heatmapOption(empty, context(), theme) as Loose).visualMap.min,
    ).toBe(-1);
  });
});
