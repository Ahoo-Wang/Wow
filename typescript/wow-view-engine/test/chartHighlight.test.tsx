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

/**
 * The group a dashboard panel's press set the board's filter to, marked on
 * its chart (D22 I): every other mark drawn faint, by opacity alone, and
 * nothing but the family's own marks touched.
 */

import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { ChartData, ChartSpec, RecordData } from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { FADED_OPACITY, faded } from '../src/ui/charts/highlight.js';

afterEach(cleanup);

describe('the group pressed, marked on a chart', () => {
  it('fades every mark but the one lit, keeping each datum’s own style', () => {
    const option = {
      series: [
        { id: 's0', type: 'bar', data: [3, null, 5] },
        { id: 'total', type: 'bar', data: [0, 0, 0] },
        {
          id: 's1',
          type: 'line',
          data: [{ value: 1, itemStyle: { color: 'red' } }],
        },
      ],
    };
    const out = faded(option, (series, at) => series === 0 && at === 2);
    const series = out.series as { data: unknown[] }[];
    expect(series[0].data).toEqual([
      { value: 3, itemStyle: { opacity: FADED_OPACITY } },
      { value: null, itemStyle: { opacity: FADED_OPACITY } },
      5,
    ]);
    // A stack's total stands for no group.
    expect(series[1].data).toEqual([0, 0, 0]);
    expect(series[2].data).toEqual([
      { value: 1, itemStyle: { color: 'red', opacity: FADED_OPACITY } },
    ]);
  });

  it('reads a pie’s one series by its position', () => {
    const out = faded(
      {
        series: [
          {
            type: 'pie',
            data: [
              { name: 'a', value: 1 },
              { name: 'b', value: 2 },
            ],
          },
        ],
      },
      (_series, at) => at === 1,
    );
    expect((out.series as { data: unknown[] }[])[0].data).toEqual([
      { name: 'a', value: 1, itemStyle: { opacity: FADED_OPACITY } },
      { name: 'b', value: 2 },
    ]);
  });

  it('reads a scatter’s and a heatmap’s one series by position too', () => {
    const out = faded(
      {
        series: [
          { type: 'scatter', data: [{ value: [1, 2] }, { value: [3, 4] }] },
          { type: 'heatmap', data: [[0, 0, 1, 1]] },
        ],
      },
      (series, at) => series === 0 && at === 0,
    );
    const [scatter, heatmap] = out.series as { data: unknown[] }[];
    expect(scatter.data[1]).toEqual({
      value: [3, 4],
      itemStyle: { opacity: FADED_OPACITY },
    });
    expect(heatmap.data[0]).toEqual({
      value: [0, 0, 1, 1],
      itemStyle: { opacity: FADED_OPACITY },
    });
  });

  it('hands back the option as it is with nothing pressed', () => {
    const option = { series: [{ id: 's0', data: [1] }] };
    expect(faded(option, undefined)).toBe(option);
    expect(faded({ title: {} }, () => false)).toEqual({ title: {} });
    // Nothing lit: the value came from elsewhere, and nothing is faded.
    expect(faded(option, () => false)).toBe(option);
  });
});

describe('a chart marking the group pressed', () => {
  const draw = (
    data: ChartData,
    spec: ChartSpec,
    highlight: (row: RecordData) => boolean,
  ) =>
    render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} highlight={highlight} />
      </ViewSurface>,
    ).container;

  const highlighted = (container: HTMLElement) =>
    waitFor(() => {
      const frame = container.querySelector<HTMLElement>('[data-slot="chart"]');
      expect(frame?.dataset.highlighted).toBeDefined();
      return frame?.dataset.highlighted;
    });

  it('counts the slice pressed, never the merged remainder', async () => {
    const container = draw(
      {
        type: 'pie',
        slices: [
          { category: 'CN', value: 2 },
          { category: 'EU', value: 1 },
          { category: null, value: 1, other: true },
        ],
      },
      { type: 'pie', pie: { category: 'region', value: 'orders' } },
      row => row.region === 'EU' || row.region === null,
    );
    expect(await highlighted(container)).toBe('1');
  });

  it('counts the cell pressed on a heatmap, holes left out', async () => {
    const container = draw(
      {
        type: 'heatmap',
        xs: ['Mon', 'Tue'],
        ys: ['CN', 'JP'],
        cells: [
          [1, 4],
          [2, null],
        ],
      },
      { type: 'heatmap', heatmap: { x: 'day', y: 'region', value: 'orders' } },
      row => row.day === 'Tue' && row.region === 'CN',
    );
    expect(await highlighted(container)).toBe('1');
  });

  it('counts the point pressed on a scatter', async () => {
    const container = draw(
      {
        type: 'scatter',
        points: [
          { category: 'CN', x: 1, y: 2 },
          { category: 'EU', x: 3, y: 4 },
        ],
      },
      { type: 'scatter', scatter: { category: 'region', x: 'a', y: 'b' } },
      row => row.region === 'CN',
    );
    expect(await highlighted(container)).toBe('1');
  });
});
