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
import type { ScatterData } from '../src/index.js';
import {
  NAMED_POINTS,
  scatterOption,
  type ScatterContext,
} from '../src/ui/charts/scatterOption.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';

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

const context = (over: Partial<ScatterContext> = {}): ScatterContext => ({
  spec: {
    type: 'scatter',
    scatter: { category: 'region', x: 'orders', y: 'amount' },
  },
  label: (alias, value, compact) =>
    `${compact ? 'short ' : ''}${alias}=${String(value)}`,
  column: alias => (alias === 'orders' ? 'Orders' : undefined),
  fallback: { x: 'X', y: 'Y' },
  animate: false,
  pickable: false,
  ...over,
});

const points: ScatterData = {
  type: 'scatter',
  points: [
    { category: 'East', x: 0, y: 1200.5 },
    { category: 'West', x: 2, y: 800 },
  ],
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

describe('scatterOption', () => {
  it('titles each axis by its column, or by which axis it is', () => {
    const option = scatterOption(points, context(), theme) as Loose;
    expect(option.xAxis.name).toBe('Orders');
    expect(option.yAxis.name).toBe('Y');
    expect(option.xAxis.axisLabel.formatter(3)).toBe('short orders=3');
  });

  it('takes whole ticks on a whole axis, and leaves room past both ends', () => {
    const option = scatterOption(points, context(), theme) as Loose;
    expect(option.xAxis.minInterval).toBe(1);
    expect(option.yAxis.minInterval).toBeUndefined();
    expect(option.xAxis.min({ min: 0, max: 2 })).toBeCloseTo(-0.12);
    expect(option.xAxis.max({ min: 0, max: 2 })).toBeCloseTo(2.12);
    // Names need a line of room over the highest point.
    expect(option.yAxis.max({ min: 0, max: 100 })).toBeCloseTo(114);
    // A single value still has room around it.
    expect(option.xAxis.min({ min: 5, max: 5 })).toBeCloseTo(4.7);
    expect(option.xAxis.min({ min: 0, max: 0 })).toBeCloseTo(-0.06);
    expect(option.xAxis.axisLabel.showMinLabel).toBe(false);
  });

  it('names a few points where they are drawn, and many nowhere', () => {
    const [few] = (scatterOption(points, context(), theme) as Loose).series;
    expect(few.label.formatter({ dataIndex: 1 })).toBe('region=West');
    expect(few.labelLayout).toEqual({ hideOverlap: true });
    const many: ScatterData = {
      type: 'scatter',
      points: Array.from({ length: NAMED_POINTS + 1 }, (_, x) => ({
        category: x,
        x,
        y: x,
      })),
    };
    const [crowd] = (scatterOption(many, context(), theme) as Loose).series;
    expect(crowd).not.toHaveProperty('label');
  });

  it('sizes points by a third metric, from the smallest to the largest', () => {
    const sized: ScatterData = {
      type: 'scatter',
      points: [
        { category: 'a', x: 1, y: 1, size: 10 },
        { category: 'b', x: 2, y: 2, size: 30 },
        { category: 'c', x: 3, y: 3, size: 20 },
      ],
    };
    const [series] = (
      scatterOption(
        sized,
        context({
          spec: {
            type: 'scatter',
            scatter: {
              category: 'region',
              x: 'orders',
              y: 'amount',
              size: 'n',
            },
          },
        }),
        theme,
      ) as Loose
    ).series;
    expect(series.data.map((point: Loose) => point.symbolSize)).toEqual([
      8, 28, 18,
    ]);
    const same: ScatterData = {
      type: 'scatter',
      points: [{ category: 'a', x: 1, y: 1, size: 5 }],
    };
    expect(
      (scatterOption(same, context(), theme) as Loose).series[0].data[0]
        .symbolSize,
    ).toBe(18);
    expect(
      (scatterOption(points, context(), theme) as Loose).series[0].data[0]
        .symbolSize,
    ).toBe(10);
  });

  it('heads the tooltip with the group and reads each measure whole', () => {
    const sizedContext = context({
      spec: {
        type: 'scatter',
        scatter: { category: 'region', x: 'orders', y: 'amount', size: 'n' },
      },
      pickable: true,
    });
    const option = scatterOption(
      {
        type: 'scatter',
        points: [{ category: 'East', x: 1, y: 2, size: 3 }],
      },
      sizedContext,
      theme,
    ) as Loose;
    const html: string = option.tooltip.formatter({ dataIndex: 0 });
    expect(html).toContain('region=East');
    expect(html).toContain('Orders');
    expect(html).toContain('amount=2');
    expect(html).toContain('n=3');
    expect(html).not.toContain('short');
    expect(option.tooltip.formatter({ dataIndex: 5 })).toBe('');
    expect(option.series[0].cursor).toBe('pointer');
  });
});
