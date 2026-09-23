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
  isSmooth,
  isStacked,
  offersStacking,
  stacks,
  withMoved,
  withMovedTo,
  optionTabs,
  withSlot,
  stageValues,
  withSmooth,
  withStacked,
  withStageOrder,
  withStagesFrom,
} from '../src/analysis/index.js';
import type { CartesianSpec, ChartSpec } from '../src/model/index.js';

const cartesian: CartesianSpec = {
  x: 'warehouse',
  splitBy: 'month',
  series: [{ metric: 'orders' }, { metric: 'amount', axis: 'right' }],
};

describe('chartOptions', () => {
  it('lays each family out on the pages it has', () => {
    expect(optionTabs('bar')).toEqual(['data', 'display', 'axes']);
    expect(optionTabs('combo')).toEqual(['data', 'display', 'axes']);
    expect(optionTabs('pie')).toEqual(['data', 'display']);
    expect(optionTabs('funnel')).toEqual(['data', 'display']);
    expect(optionTabs('metric')).toEqual(['data', 'display']);
    expect(optionTabs('scatter')).toEqual(['data']);
    expect(optionTabs('table')).toEqual(['display']);
  });

  it('places an alias in a slot and swaps when the other slot held it', () => {
    expect(withSlot(cartesian, 'x', 'splitBy', 'region')).toMatchObject({
      x: 'region',
      splitBy: 'month',
    });
    // Choosing the split's alias for the axis swaps the two rather than
    // leaving `chart.splitBy.same-as-x` behind.
    expect(withSlot(cartesian, 'x', 'splitBy', 'month')).toMatchObject({
      x: 'month',
      splitBy: 'warehouse',
    });
    expect(withSlot(cartesian, 'splitBy', 'x', 'warehouse')).toMatchObject({
      x: 'month',
      splitBy: 'warehouse',
    });
    // The same rule for a heatmap's two axes and a scatter's two metrics.
    expect(withSlot({ x: 'a', y: 'b', value: 'n' }, 'y', 'x', 'a')).toEqual({
      x: 'b',
      y: 'a',
      value: 'n',
    });
    expect(
      withSlot({ category: 'c', x: 'm1', y: 'm2' }, 'x', 'y', 'm3'),
    ).toEqual({ category: 'c', x: 'm3', y: 'm2' });
  });

  it('stacks bars and areas, never a line (audit P0-2)', () => {
    // A line chart offers no stacking at all; every other cartesian type
    // does.
    expect(offersStacking('line')).toBe(false);
    expect(
      (['bar', 'area', 'combo'] as const).every(type => offersStacking(type)),
    ).toBe(true);
    expect(stacks('combo', { type: 'line' })).toBe(false);
    expect(stacks('combo', {})).toBe(true);
    expect(stacks('area', {})).toBe(true);
    expect(stacks('line', {})).toBe(false);

    // A combo stacks its bars, and its line keeps its own axis and values.
    const combo = {
      x: 'warehouse',
      series: [
        { metric: 'orders', type: 'bar' as const, axis: 'right' as const },
        { metric: 'amount', type: 'bar' as const },
        { metric: 'average', type: 'line' as const, axis: 'right' as const },
      ],
    };
    const stacked = withStacked(combo, true, 'combo');
    expect(stacked.series).toEqual([
      { metric: 'orders', type: 'bar', stack: 'all' },
      { metric: 'amount', type: 'bar', stack: 'all' },
      { metric: 'average', type: 'line', axis: 'right' },
    ]);
    // Read back over the bars alone.
    expect(isStacked(stacked, 'combo')).toBe(true);
    expect(isStacked(stacked, 'bar')).toBe(false);
    expect(
      withStacked(stacked, false, 'combo').series.every(
        series => !('stack' in series),
      ),
    ).toBe(true);
    // Nothing that stacks is not stacked.
    expect(
      isStacked(
        {
          x: 'warehouse',
          series: [{ metric: 'a', type: 'line', stack: 'all' }],
        },
        'combo',
      ),
    ).toBe(false);
  });

  it('stacks and smooths every series as one choice, and reads it back', () => {
    expect(isStacked(cartesian)).toBe(false);
    const stacked = withStacked(cartesian, true);
    expect(stacked.series.map(series => series.stack)).toEqual(['all', 'all']);
    expect(isStacked(stacked)).toBe(true);
    // Joining the stack brings every series onto the one axis: segments
    // piled on each other are being added up, and two scales do not add.
    expect(stacked.series[1]).toEqual({ metric: 'amount', stack: 'all' });
    const flat = withStacked(stacked, false);
    expect(flat.series.every(series => !('stack' in series))).toBe(true);
    // Half-stacked is not stacked: the box is unchecked until every series is.
    expect(
      isStacked({
        ...cartesian,
        series: [{ metric: 'orders', stack: 'all' }, { metric: 'amount' }],
      }),
    ).toBe(false);
    expect(isStacked({ ...cartesian, series: [] })).toBe(false);

    expect(isSmooth(cartesian)).toBe(false);
    const smooth = withSmooth(cartesian, true);
    expect(smooth.series.every(series => series.smooth === true)).toBe(true);
    expect(isSmooth(smooth)).toBe(true);
    expect(
      withSmooth(smooth, false).series.every(series => !('smooth' in series)),
    ).toBe(true);
  });

  it('moves a list item one step and stays put at either end', () => {
    expect(withMoved(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(withMoved(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
    expect(withMoved(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c']);
    expect(withMoved(['a', 'b', 'c'], 2, 1)).toEqual(['a', 'b', 'c']);
    expect(withMoved(['a', 'b', 'c'], 5, 1)).toEqual(['a', 'b', 'c']);
  });

  /**
   * A drop names the place it landed on, which may be any of them — the one
   * step an arrow key makes is that move with the two places next to each
   * other, so both inputs end up here.
   */
  it('takes a list item out and puts it back at the place asked for', () => {
    expect(withMovedTo(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b']);
    expect(withMovedTo(['a', 'b', 'c'], 0, 2)).toEqual(['b', 'c', 'a']);
    expect(withMovedTo(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'b', 'c']);
    expect(withMovedTo(['a', 'b', 'c'], 0, 3)).toEqual(['a', 'b', 'c']);
    expect(withMovedTo(['a', 'b', 'c'], -1, 0)).toEqual(['a', 'b', 'c']);
  });

  it('lists a group’s text values once each in the order the rows came', () => {
    const rows = [
      { stage: 'visited', n: 9 },
      { stage: 'carted', n: 4 },
      { stage: 'visited', n: 1 },
      { stage: 7, n: 2 },
      { stage: null, n: 2 },
      { stage: 'paid', n: 2 },
    ];
    // A number or a null is no stage: the kernel reads a stage's name back
    // as a string key, and those would never match themselves.
    expect(stageValues(rows, 'stage')).toEqual(['visited', 'carted', 'paid']);
    expect(stageValues(rows, 'missing')).toEqual([]);
  });

  it('gives a funnel of group values the order the rows came in, once', () => {
    const rows = [
      { stage: 'visited', n: 9 },
      { stage: 'paid', n: 2 },
    ];
    const funnel: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: { from: 'group', category: 'stage', value: 'n', order: [] },
      },
    };
    const filled = withStagesFrom(funnel, rows);
    expect(filled.funnel?.stages).toMatchObject({
      from: 'group',
      order: ['visited', 'paid'],
    });
    // An order the analyst set is theirs; the rows do not overwrite it.
    const ordered = withStageOrder(filled.funnel!, ['paid', 'visited']);
    expect(withStagesFrom({ ...funnel, funnel: ordered }, rows).funnel).toBe(
      ordered,
    );
    // Metric stages are already in order; another type has no stages.
    const metrics: ChartSpec = {
      type: 'funnel',
      funnel: { stages: { from: 'metrics', items: [{ metric: 'n' }] } },
    };
    expect(withStagesFrom(metrics, rows)).toBe(metrics);
    expect(withStageOrder(metrics.funnel!, ['x'])).toBe(metrics.funnel);
    const bar: ChartSpec = { type: 'bar', funnel: funnel.funnel };
    expect(withStagesFrom(bar, rows)).toBe(bar);
  });

  /**
   * A saved funnel of one stage refuses to draw, and picked again it would
   * refuse again: one stage is no order, so it is completed from the rows,
   * the stage it names first.
   */
  it('completes an order of one stage from the rows', () => {
    const rows = [
      { stage: 'visited', n: 9 },
      { stage: 'carted', n: 4 },
      { stage: 'paid', n: 2 },
    ];
    const one: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'group',
          category: 'stage',
          value: 'n',
          order: ['paid'],
        },
      },
    };
    expect(withStagesFrom(one, rows).funnel?.stages).toMatchObject({
      order: ['paid', 'visited', 'carted'],
    });
  });
});
