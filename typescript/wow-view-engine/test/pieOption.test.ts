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
import type { PieData, ChartSpec } from '../src/index.js';
import {
  LABELLED_SHARE,
  drawnSlices,
  pieOption,
  type PieContext,
} from '../src/ui/charts/pieOption.js';
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

const data: PieData = {
  type: 'pie',
  slices: [
    { category: 'CN', value: 60 },
    { category: 'JP', value: 38 },
    { category: 'KR', value: 2 },
    { category: null, value: -5, other: true },
  ],
};

const spec = (pie: Partial<NonNullable<ChartSpec['pie']>> = {}): ChartSpec => ({
  type: 'pie',
  pie: { category: 'country', value: 'amount', ...pie },
});

const context = (over: Partial<PieContext> = {}): PieContext => ({
  spec: spec(),
  label: (alias, value, compact) =>
    `${compact ? 'short ' : ''}${alias}=${String(value)}`,
  locale: 'en',
  other: 'Other',
  total: 'Total',
  adds: true,
  animate: false,
  pickable: false,
  ...over,
});

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;
const optionOf = (over: Partial<PieContext> = {}) =>
  pieOption(data, context(over), theme) as Loose;

describe('drawnSlices', () => {
  it('names, shares and colours each slice; the remainder is grey and shareless when negative', () => {
    const slices = drawnSlices(data, context());
    expect(slices.map(slice => slice.name)).toEqual([
      'country=CN',
      'country=JP',
      'country=KR',
      'Other',
    ]);
    expect(slices.map(slice => slice.share)).toEqual([
      0.6,
      0.38,
      0.02,
      undefined,
    ]);
    expect(slices.map(slice => slice.color)).toEqual([
      'var(--chart-1)',
      'var(--chart-2)',
      'var(--chart-3)',
      'var(--muted-foreground)',
    ]);
  });
});

describe('pieOption', () => {
  it('writes each share outside its slice, leaving slivers to the tooltip', () => {
    const [series] = optionOf().series;
    const labels = series.data.map((slice: Loose) => slice.label);
    expect(labels.map((label: Loose) => label.formatter)).toEqual([
      '60%',
      '38%',
      '',
      '',
    ]);
    expect(labels.map((label: Loose) => label.show)).toEqual([
      true,
      true,
      false,
      false,
    ]);
    expect(LABELLED_SHARE).toBe(0.03);
    expect(series.labelLayout).toEqual({ hideOverlap: true });
    // No negative wedge is drawn.
    expect(series.data[3].value).toBe(0);
    expect(series.radius).toEqual([0, '72%']);
    expect(series.cursor).toBe('default');
  });

  it('adds the value, short, when the spec asks for labels', () => {
    const [series] = optionOf({ spec: { ...spec(), labels: true } }).series;
    expect(series.data[0].label.formatter).toBe('short amount=60 · 60%');
  });

  it('writes a donut’s whole in its hole, only when the measure adds up', () => {
    const donut = optionOf({ spec: spec({ donut: true }), pickable: true });
    expect(donut.series[0].radius).toEqual(['50%', '72%']);
    expect(donut.series[0].cursor).toBe('pointer');
    expect(donut.graphic[0].style.text).toBe(
      '{value|short amount=100}\n{word|Total}',
    );
    expect(
      optionOf({ spec: spec({ donut: true }), adds: false }),
    ).not.toHaveProperty('graphic');
    expect(optionOf()).not.toHaveProperty('graphic');
  });

  it('says a slice’s value whole in the tooltip, and its share', () => {
    const { tooltip } = optionOf();
    expect(tooltip.trigger).toBe('item');
    expect(tooltip.formatter({ dataIndex: 1 })).toContain('amount=38 · 38%');
    expect(tooltip.formatter({ dataIndex: 3 })).toContain('amount=-5');
    expect(tooltip.formatter({ dataIndex: 3 })).not.toContain('%');
    expect(tooltip.formatter({ dataIndex: 9 })).toBe('');
  });
});
