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

/*
 * A split series and a stream wear their value's tone, as the pie's slice
 * and the value's badge do (the pre-release review's 「直角坐标图的拆分系列读选项的
 * `tone`」): 「失败」 red on the bars too, not whichever slot came next.
 */

import { describe, expect, it } from 'vitest';
import type { CartesianData, ChartSpec, ThemeRiverData } from '../src/index.js';
import { drawnSeries } from '../src/ui/charts/cartesianPlan.js';
import type { ToneOf } from '../src/ui/charts/family.js';
import { drawnStreams } from '../src/ui/charts/timeOption.js';

const TONES: Record<string, 'danger' | 'success' | 'warning' | 'neutral'> = {
  FAILED: 'danger',
  SUCCEEDED: 'success',
  PENDING: 'neutral',
};
const toneOf: ToneOf = (alias, value) =>
  alias === 'status' ? TONES[String(value)] : undefined;

const split = (values: unknown[]): CartesianData => ({
  type: 'cartesian',
  chart: 'bar',
  points: [
    {
      x: 'day',
      values: Object.fromEntries(values.map(value => [String(value), 1])),
    },
  ],
  series: values.map(value => ({
    key: String(value),
    label: String(value),
    metric: 'count',
    value,
  })),
});

const spec = (extra: Partial<ChartSpec> = {}): ChartSpec => ({
  type: 'bar',
  cartesian: {
    x: 'day',
    series: [{ metric: 'count' }],
    splitBy: 'status',
  },
  ...extra,
});

const base = {
  label: (_alias: string | undefined, value: unknown) => String(value),
  column: () => undefined,
};

describe('a split series by a toned value', () => {
  it('wears its tone, and a neutral or untoned value its slot', () => {
    const series = drawnSeries(
      split(['FAILED', 'OTHER', 'SUCCEEDED', 'PENDING']),
      { ...base, spec: spec(), toneOf },
    );
    expect(series.map(entry => entry.color)).toEqual([
      'var(--destructive)',
      'var(--chart-2)',
      'var(--success)',
      'var(--chart-4)',
    ]);
  });

  it('takes slots where two values share a tone, and keeps a pinned colour', () => {
    const shared: ToneOf = (_alias, value) =>
      value === 'OTHER' ? undefined : 'danger';
    expect(
      drawnSeries(split(['FAILED', 'SUCCEEDED', 'OTHER']), {
        ...base,
        spec: spec(),
        toneOf: shared,
      }).map(entry => entry.color),
    ).toEqual(['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)']);
    expect(
      drawnSeries(split(['FAILED']), {
        ...base,
        spec: spec({ colors: { FAILED: '#123456' } }),
        toneOf,
      })[0].color,
    ).toBe('#123456');
  });

  it('leaves an unsplit series to its slot', () => {
    const data: CartesianData = {
      ...split([]),
      series: [{ key: 'count', label: 'count', metric: 'count' }],
    };
    expect(
      drawnSeries(data, {
        ...base,
        spec: spec({ cartesian: { x: 'day', series: [{ metric: 'count' }] } }),
        toneOf: () => 'danger',
      })[0].color,
    ).toBe('var(--chart-1)');
  });
});

describe('a stream of a toned value', () => {
  it('wears its tone, as a split series does', () => {
    const data: ThemeRiverData = {
      type: 'themeRiver',
      times: ['2026-09-01'],
      streams: [
        { key: 'FAILED', value: 'FAILED' },
        { key: 'OTHER', value: 'OTHER' },
      ],
      values: [[1, 2]],
      uncertain: 0,
    } as ThemeRiverData;
    const streams = drawnStreams(data, {
      spec: {
        type: 'themeRiver',
        themeRiver: { x: 'day', value: 'count', splitBy: 'status' },
      } as ChartSpec,
      label: base.label,
      other: '其他',
      toneOf,
    });
    expect(streams.map(stream => stream.color)).toEqual([
      'var(--destructive)',
      'var(--chart-2)',
    ]);
  });
});
