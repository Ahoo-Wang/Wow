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
import type { CartesianData, ChartSpec } from '../src/index.js';
import {
  BRUSH_CLEAR,
  BRUSH_CURSOR,
  BRUSH_ID,
  brushedSpan,
  brushes,
  brushOption,
  withoutBrushToolbox,
} from '../src/ui/charts/cartesianBrush.js';
import { cartesianOption } from '../src/ui/charts/cartesianOption.js';
import {
  cartesianPlan,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import { FADED_OPACITY } from '../src/ui/charts/highlight.js';
import { CHART_FALLBACK, type ChartTheme } from '../src/ui/charts/theme.js';

/**
 * A stretch of a time axis brushed for the follow-up menu (D33 batch C,
 * Q52): the option carries the brush only where a drag along the axis can
 * mean a stretch of time, and a finished brush reads back as the first and
 * last category it covers. The drag itself is the browser story's
 * 「框选与追问/回归」.
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

const LIGHT: ChartTheme = {
  ...CHART_FALLBACK,
  palette: [],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  axis: { color: 'rgb(115, 115, 115)' },
  grid: { ...CHART_FALLBACK.grid, color: 'rgb(229, 229, 229)' },
  ground: 'rgb(255, 255, 255)',
  text: { ...CHART_FALLBACK.text, family: 'sans-serif' },
  key: 'light',
  resolve: () => 'rgb(30, 60, 160)',
};

const day = (n: number) => Date.UTC(2026, 0, 1) + n * 86_400_000;

function days(count: number): CartesianData {
  return {
    type: 'cartesian',
    chart: 'bar',
    timeline: true,
    points: Array.from({ length: count }, (_, index) => ({
      x: day(index),
      values: { amount: 100 + index },
    })),
    series: [{ key: 'amount', label: 'amount', metric: 'amount' }],
  };
}

const SPEC: ChartSpec = {
  type: 'bar',
  cartesian: { x: 'day', series: [{ metric: 'amount' }] },
};

const context = (extra: Partial<CartesianContext> = {}): CartesianContext => ({
  spec: SPEC,
  label: (_alias, value) => String(value),
  column: alias => alias,
  locale: 'en',
  animate: false,
  pickable: true,
  brushes: true,
  ...extra,
});

describe('brushOption: a time axis whose marks are pressable is brushed', () => {
  it('brushes along the axis alone, one stretch, no toolbox', () => {
    const option = cartesianOption(days(30), context(), LIGHT) as Loose;
    expect(option.brush).toEqual({
      id: BRUSH_ID,
      xAxisIndex: 0,
      brushLink: 'none',
      brushType: 'lineX',
      brushMode: 'single',
      transformable: false,
      removeOnClick: true,
      toolbox: [],
      brushStyle: {
        color: LIGHT.muted,
        borderColor: LIGHT.muted,
        borderWidth: 1,
        opacity: 0.18,
      },
      inBrush: {},
      outOfBrush: { colorAlpha: FADED_OPACITY },
    });
  });

  it('brushes nothing that cannot be a stretch of time to follow up on', () => {
    // Not asked: an axis of categories, or a finger for a pointer.
    expect(
      cartesianOption(days(30), context({ brushes: false }), LIGHT),
    ).not.toHaveProperty('brush');
    // Nothing to follow up with.
    expect(
      cartesianOption(days(30), context({ pickable: false }), LIGHT),
    ).not.toHaveProperty('brush');
    // One bucket is no stretch.
    expect(cartesianOption(days(1), context(), LIGHT)).not.toHaveProperty(
      'brush',
    );
    // Lying on its side, the categories run down: no drag along them.
    const lying = cartesianPlan(
      days(30),
      context({
        spec: {
          ...SPEC,
          cartesian: { ...SPEC.cartesian!, orientation: 'horizontal' },
        },
      }),
    );
    expect(brushes(lying)).toBe(false);
    expect(brushOption(lying, LIGHT)).toBeUndefined();
  });

  it('turns the drag on and clears the cover through two actions', () => {
    expect(BRUSH_CURSOR).toEqual({
      type: 'takeGlobalCursor',
      key: 'brush',
      brushOption: { brushType: 'lineX', brushMode: 'single' },
    });
    expect(BRUSH_CLEAR).toEqual({ type: 'brush', areas: [] });
  });
});

describe('withoutBrushToolbox: the toolbox the brush injects is left out', () => {
  const injected = () => [{ feature: { brush: { type: ['rect'] } } }];

  it('takes it off an option carrying this package’s brush', () => {
    const option: Record<string, unknown> = {
      brush: [{ id: BRUSH_ID }],
      toolbox: injected(),
    };
    withoutBrushToolbox(option, false);
    expect(option).toEqual({ brush: [{ id: BRUSH_ID }] });
    const single: Record<string, unknown> = {
      brush: { id: BRUSH_ID },
      toolbox: injected(),
    };
    withoutBrushToolbox(single, false);
    expect(single).not.toHaveProperty('toolbox');
  });

  it('leaves anyone else’s toolbox alone', () => {
    // A host's own brush, a host's registered toolbox, no toolbox at all.
    const host = { brush: [{ id: 'theirs' }], toolbox: injected() };
    withoutBrushToolbox(host, false);
    expect(host.toolbox).toEqual(injected());
    const registered = { brush: [{ id: BRUSH_ID }], toolbox: injected() };
    withoutBrushToolbox(registered, true);
    expect(registered.toolbox).toEqual(injected());
    const none = { brush: [{ id: BRUSH_ID }] };
    withoutBrushToolbox(none, false);
    expect(none).toEqual({ brush: [{ id: BRUSH_ID }] });
    expect(() => withoutBrushToolbox(undefined, false)).not.toThrow();
    const empty = { brush: [null], toolbox: injected() };
    withoutBrushToolbox(empty, false);
    expect(empty.toolbox).toEqual(injected());
  });
});

describe('brushedSpan: the categories a finished brush covers', () => {
  const ended = (coordRange: unknown) => ({
    type: 'brushEnd',
    areas: [{ brushType: 'lineX', coordRange }],
  });

  it('reads the one area’s range as its first and last category', () => {
    expect(brushedSpan(ended([3, 5]), 30)).toEqual({ from: 3, to: 5 });
    // Dragged right to left.
    expect(brushedSpan(ended([5, 3]), 30)).toEqual({ from: 3, to: 5 });
    // One category.
    expect(brushedSpan(ended([4, 4]), 30)).toEqual({ from: 4, to: 4 });
    // Between two, the nearer.
    expect(brushedSpan(ended([2.4, 6.6]), 30)).toEqual({ from: 2, to: 7 });
  });

  it('clamps to the categories there are', () => {
    expect(brushedSpan(ended([-2, 3]), 30)).toEqual({ from: 0, to: 3 });
    expect(brushedSpan(ended([27, 40]), 30)).toEqual({ from: 27, to: 29 });
    expect(brushedSpan(ended([40, 50]), 30)).toBeNull();
  });

  it('is nothing for a brush that covers nothing', () => {
    // Taken away by a click.
    expect(brushedSpan({ type: 'brushEnd', areas: [] }, 30)).toBeNull();
    expect(brushedSpan({}, 30)).toBeNull();
    expect(brushedSpan(null, 30)).toBeNull();
    expect(brushedSpan(ended([1]), 30)).toBeNull();
    expect(brushedSpan(ended(['a', 2]), 30)).toBeNull();
    expect(brushedSpan(ended([Number.NaN, 2]), 30)).toBeNull();
    expect(brushedSpan(ended([1, 2]), 0)).toBeNull();
  });
});
