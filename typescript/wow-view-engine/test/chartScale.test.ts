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
  measuredTitle,
  sideTitle,
  titleAtHead,
} from '../src/ui/charts/axis.js';
import { niceStep, sharedScales } from '../src/ui/charts/scale.js';
import {
  emphasized,
  inkOn,
  type ChartTheme,
  CHART_FALLBACK,
} from '../src/ui/charts/theme.js';

/**
 * The pieces the chart polish of 2026-09-23 stands on: a value axis's scale
 * the chart owns, the colours a hovered mark and a label on a mark wear, and
 * how an axis title stands. What they add up to on screen is the browser
 * stories' (「分析工作台/回归」「图型/回归」).
 */

const steps = (scale: { min: number; max: number; interval: number }) =>
  (scale.max - scale.min) / scale.interval;

describe('niceStep', () => {
  it('rounds up to 1, 2, 2.5 or 5 of a power of ten', () => {
    expect(niceStep(976, false)).toBe(1000);
    expect(niceStep(1220, false)).toBe(2000);
    expect(niceStep(2100, false)).toBe(2500);
    expect(niceStep(0.33, false)).toBe(0.5);
    expect(niceStep(0.07, false)).toBe(0.1);
  });

  it('steps a whole axis in whole numbers, never under one', () => {
    expect(niceStep(0.4, true)).toBe(1);
    // 2.5 records is no number of anything; 25 is.
    expect(niceStep(2.2, true)).toBe(5);
    expect(niceStep(22, true)).toBe(25);
  });

  it('takes a step of one for nothing to step over', () => {
    expect(niceStep(0, false)).toBe(1);
  });
});

describe('sharedScales', () => {
  it('runs one axis from zero past its highest mark', () => {
    const [scale] = sharedScales([{ low: 0, high: 4880, whole: true }]);
    expect(scale.min).toBe(0);
    expect(scale.max).toBeGreaterThanOrEqual(4880);
    // Snug: not more than a step past the top.
    expect(scale.max - 4880).toBeLessThan(scale.interval);
  });

  it('cuts two axes into as many steps, each a nice one of its own (audit)', () => {
    // An amount and a count: the count followed the amount's gridlines in
    // halves under the library's `alignTicks` — 「0.5」「1.5」 records.
    const [amount, count] = sharedScales([
      { low: 0, high: 4880, whole: false },
      { low: 0, high: 2, whole: true },
    ]);
    expect(steps(amount)).toBe(steps(count));
    expect(Number.isInteger(count.interval)).toBe(true);
    expect(count.max).toBeGreaterThanOrEqual(2);
    expect(amount.max).toBeGreaterThanOrEqual(4880);
  });

  it('keeps zero on one line for both axes when one goes below it', () => {
    const [left, right] = sharedScales([
      { low: -30, high: 100, whole: true },
      { low: 0, high: 5, whole: true },
    ]);
    expect(left.min).toBeLessThanOrEqual(-30);
    // As many steps under zero on either side: zero is one gridline.
    expect(-left.min / left.interval).toBe(-right.min / right.interval);
    expect(steps(left)).toBe(steps(right));
    expect(right.max).toBeGreaterThanOrEqual(5);
  });

  it('writes a decimal as the decimal it stands for', () => {
    const [scale] = sharedScales([{ low: 0, high: 0.3, whole: false }]);
    expect(String(scale.max)).not.toMatch(/0000/);
  });

  it('draws a scale over nothing at all', () => {
    const [scale] = sharedScales([{ low: 0, high: 0, whole: true }]);
    expect(scale).toEqual({ min: 0, max: 5, interval: 1 });
  });
});

const theme = (foreground: string, ground: string): ChartTheme => ({
  ...CHART_FALLBACK,
  palette: [],
  foreground,
  muted: 'gray',
  axis: { color: 'gray' },
  grid: { ...CHART_FALLBACK.grid, color: 'gray' },
  ground,
  text: { ...CHART_FALLBACK.text, family: 'sans-serif' },
  key: 'test',
  resolve: color => color,
});
const LIGHT = theme('rgb(10, 10, 10)', 'rgb(255, 255, 255)');
const DARK = theme('rgb(250, 250, 250)', 'rgb(10, 10, 10)');

/** Relative luminance, near enough to say which of two is darker. */
const lightness = (rgb: string) =>
  (rgb.match(/\d+/g) ?? []).slice(0, 3).reduce((sum, n) => sum + Number(n), 0);

describe('emphasized: a mark under the pointer', () => {
  it('steps toward the ink — darker on a light page, lighter on a dark one', () => {
    const blue = 'rgb(42, 120, 214)';
    expect(lightness(emphasized(LIGHT, blue))).toBeLessThan(lightness(blue));
    expect(lightness(emphasized(DARK, blue))).toBeGreaterThan(lightness(blue));
  });
});

describe('inkOn: a label written on a mark', () => {
  it('takes whichever of the two inks stands further from the mark', () => {
    expect(inkOn(LIGHT, 'rgb(20, 40, 120)')).toBe(LIGHT.ground);
    expect(inkOn(LIGHT, 'rgb(220, 232, 250)')).toBe(LIGHT.foreground);
    // The dark theme's inks are the other way round; the rule is the same.
    expect(inkOn(DARK, 'rgb(20, 40, 120)')).toBe(DARK.foreground);
  });

  it('keeps the foreground where a colour cannot be read', () => {
    expect(inkOn(LIGHT, 'not a colour')).toBe(LIGHT.foreground);
    expect(inkOn(theme('fg', 'ground'), 'rgb(0, 0, 0)')).toBe('fg');
  });
});

describe('an axis title', () => {
  const style = { color: 'gray' };

  it('sets a Chinese title flat at the axis’s head (audit)', () => {
    expect(titleAtHead('金额的总和')).toBe(true);
    expect(titleAtHead('Sum of 金额')).toBe(true);
    expect(sideTitle('金额的总和', 'left', 'end', style, 16)).toEqual({
      name: '金额的总和',
      nameLocation: 'end',
      nameRotate: 0,
      nameGap: 16,
      nameTextStyle: { color: 'gray', align: 'right', verticalAlign: 'bottom' },
    });
    // On the right it runs the other way off the line; a list read top
    // down has its head at the start.
    expect(
      sideTitle('记录数', 'right', 'end', style, 16).nameTextStyle,
    ).toMatchObject({ align: 'left' });
    expect(sideTitle('仓库', 'left', 'start', style, 16).nameLocation).toBe(
      'start',
    );
  });

  it('turns a Latin title along the axis, as Metabase does', () => {
    expect(titleAtHead('Sum of Amount')).toBe(false);
    expect(titleAtHead(undefined)).toBe(false);
    expect(sideTitle('Sum of Amount', 'left', 'end', style, 16)).toEqual({
      name: 'Sum of Amount',
      nameLocation: 'middle',
      nameGap: 16,
      nameMoveOverlap: true,
      nameTextStyle: style,
    });
  });

  it('names what a value axis measures, all of it on a chart of two axes', () => {
    const column = (alias: string | undefined) =>
      ({ a: 'Orders', b: 'Customers', c: 'Amount' })[alias ?? ''];
    expect(measuredTitle(['a', 'a'], false, column, ', ')).toBe('Orders');
    // Two on the one axis: the legend names them.
    expect(measuredTitle(['a', 'b'], false, column, ', ')).toBeUndefined();
    // Two on one of two axes: the legend no longer says which is where.
    expect(measuredTitle(['a', 'b'], true, column, '、')).toBe(
      'Orders、Customers',
    );
    expect(measuredTitle(['a', 'z'], true, column, ', ')).toBeUndefined();
    expect(measuredTitle([], true, column, ', ')).toBeUndefined();
  });
});
