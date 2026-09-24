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
import { formatShare } from '../src/ui/charts/axis.js';
import {
  LABELLED_SHARE,
  LABEL_ROOM_SHRINK,
  drawnSlices,
  pieCaptions,
  pieFit,
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
      '60.0%',
      '38.0%',
      undefined,
      undefined,
    ]);
    // On for the series, off for a slice with nothing to say.
    expect(series.label.show).toBe(true);
    expect(labels.map((label: Loose) => label.show)).toEqual([
      undefined,
      undefined,
      false,
      false,
    ]);
    expect(series.data[2].labelLine).toEqual({ show: false });
    expect(LABELLED_SHARE).toBe(0.03);
    expect(series.labelLayout).toEqual({ hideOverlap: true });
    // A number is never cut to 「4」: written whole, or not at all.
    expect(series.label.overflow).toBe('none');
    // No negative wedge is drawn.
    expect(series.data[3].value).toBe(0);
    expect(series.radius).toEqual([0, '72%']);
    expect(series.cursor).toBe('default');
  });

  it('adds the value, short, when the spec asks for labels', () => {
    const [series] = optionOf({ spec: { ...spec(), labels: true } }).series;
    expect(series.data[0].label.formatter).toBe('short amount=60 · 60.0%');
    expect(
      pieCaptions(data, context({ spec: { ...spec(), labels: true } })),
    ).toEqual(['short amount=60 · 60.0%', 'short amount=38 · 38.0%', '', '']);
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
    expect(tooltip.formatter({ dataIndex: 1 })).toContain('amount=38 · 38.0%');
    expect(tooltip.formatter({ dataIndex: 3 })).toContain('amount=-5');
    expect(tooltip.formatter({ dataIndex: 3 })).not.toContain('%');
    expect(tooltip.formatter({ dataIndex: 9 })).toBe('');
  });
});

describe('formatShare: a part of a whole, one decimal always', () => {
  it('writes every share to the same decimal, in the surface’s language', () => {
    expect(formatShare(0.36, 'en')).toBe('36.0%');
    expect(formatShare(0.325, 'zh-CN')).toBe('32.5%');
    expect(formatShare(1, 'en')).toBe('100.0%');
    expect(formatShare(0, 'en')).toBe('0.0%');
    // Rounded the ordinary way once it reaches the decimal.
    expect(formatShare(0.0006, 'en')).toBe('0.1%');
  });

  it('writes a sliver as under a tenth, never as none (51 of 1.8 million)', () => {
    expect(formatShare(51 / 1_831_229, 'zh-CN')).toBe('<0.1%');
    expect(formatShare(30 / 1_831_229, 'en')).toBe('<0.1%');
    // …and the rest of such a whole as short of all of it.
    expect(formatShare(1 - 114 / 1_831_229, 'en')).toBe('>99.9%');
  });
});

describe('pieFit: a label whole, or not at all', () => {
  // Seven pixels a character at 12px, as a canvas would roughly have it.
  const measure = (text: string) => text.length * 7;
  const captions = ['47.7%', '28.3%', '23.9%', ''];
  const fit = (
    width: number,
    height: number,
    texts = captions,
    donut = false,
  ) => (pieFit(texts, width, height, measure, donut) as Loose).series[0];

  it('keeps the full pie where the labels have room', () => {
    const series = fit(900, 400);
    expect(series.radius).toEqual([0, 144]);
    expect(series.label).toEqual({ show: true });
    expect(series.labelLine).toEqual({ show: true });
  });

  it('gives way so the widest label fits beside the pie’s widest point', () => {
    // A phone: 382 wide, as tall as it is wide.
    const series = fit(382, 382);
    const full = (0.72 * 382) / 2;
    const [, outer] = series.radius;
    expect(outer).toBeLessThan(full);
    expect(outer).toBeGreaterThanOrEqual(full * LABEL_ROOM_SHRINK);
    // Leader (8 + 8 + 5), the library's margin (8) and the words, at 11px.
    const widest = (5 * 7 * 11) / 12;
    expect(382 / 2 - outer - 29).toBeCloseTo(widest);
    expect(series.label.show).toBe(true);
    // A donut's hole shrinks with it.
    const [inner, ring] = fit(382, 382, captions, true).radius;
    expect(inner / ring).toBeCloseTo(0.5 / 0.72);
  });

  it('writes no label rather than a cut one, past what the pie can give', () => {
    const long = ['¥1.02万 · 47.7%', '¥6,050 · 28.3%', '', ''];
    const series = fit(382, 382, long);
    expect(series.label).toEqual({ show: false });
    expect(series.labelLine).toEqual({ show: false });
    // The pie keeps its size: the legend and the tooltip say the shares.
    expect(series.radius).toEqual([0, (0.72 * 382) / 2]);
    // Taken back when the room comes back — a resize merges, so it says so.
    expect(fit(1200, 382, long).label).toEqual({ show: true });
  });

  it('has nothing to fit when no slice writes a label', () => {
    expect(fit(120, 120, ['', '']).label).toEqual({ show: true });
  });
});
