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

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChartData } from '../src/index.js';

/**
 * The chart chunk as a first chart meets it: not here yet. Every other
 * suite loads it before rendering (`test/setup.ts`); this one stands in for
 * the loader, so the wait and the failure are both on the table.
 */
const loader = vi.hoisted(() => ({
  pending: undefined as
    { resolve(value: unknown): void; reject(error: unknown): void } | undefined,
}));

vi.mock('../src/ui/charts/load.js', () => ({
  loadedCharts: () => undefined,
  loadCharts: () =>
    new Promise((resolve, reject) => {
      loader.pending = { resolve, reject };
    }),
}));

const { AnalysisChart, RenderBoundary, ViewSurface } =
  await import('../src/ui/index.js');

afterEach(cleanup);

const data: ChartData = {
  type: 'cartesian',
  chart: 'bar',
  points: [{ x: 'CN', values: { orders: 2 } }],
  series: [{ key: 'orders', label: 'orders', metric: 'orders' }],
};

function chart() {
  return render(
    <ViewSurface>
      <RenderBoundary name="result">
        <AnalysisChart
          data={data}
          spec={{
            type: 'bar',
            cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
          }}
        />
      </RenderBoundary>
    </ViewSurface>,
  );
}

describe('the chart chunk on first use', () => {
  it('holds the frame and its name while the library is on its way', async () => {
    const { container } = chart();
    expect(screen.getByRole('img', { name: /bar/ })).toBeDefined();
    expect(container.querySelector('[data-slot="chart-plot"] svg')).toBeNull();

    loader.pending!.resolve(
      await vi.importActual('../src/ui/charts/echarts.js'),
    );
    await waitFor(() =>
      expect(
        container.querySelector('[data-slot="chart-plot"] svg'),
      ).not.toBeNull(),
    );
  });

  it('says it could not be drawn when the library does not arrive', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    chart();
    loader.pending!.reject(new Error('chunk failed'));
    expect(
      await screen.findByText('This part could not be drawn'),
    ).toBeDefined();
  });
});
