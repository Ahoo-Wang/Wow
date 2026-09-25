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
 * A chart taken away as a picture says nothing in the console (D33 Q58),
 * as a brushed one says nothing (`chartBrushConsole.test.tsx`): the picture
 * is the drawing rendered again off the page, the brush and the slider
 * taken out and the head drawn with the graphic component the page already
 * registers — no component the library would log as missing. A file of its
 * own, for the reason that one is: the library logs a missing component
 * once per module instance.
 */

import { cleanup, render, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import type { ChartData, ChartSpec } from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { useChartImageSlot } from '../src/ui/analysis/imageExport.js';
import {
  chartImageSvg,
  IMAGE_MIN_WIDTH,
  type CaptureChart,
} from '../src/ui/charts/image.js';

afterEach(cleanup);

const DAY_MS = 86_400_000;
const FIRST = Date.UTC(2026, 8, 1);

// A long time axis: it draws a slider and a brush, both of which the
// picture leaves out.
const data: ChartData = {
  type: 'cartesian',
  chart: 'bar',
  timeline: true,
  points: Array.from({ length: 120 }, (_, n) => ({
    x: FIRST + n * DAY_MS,
    values: { east: n + 1, west: 2 * n + 1 },
  })),
  series: [
    { key: 'east', label: 'east', metric: 'orders', value: 'east' },
    { key: 'west', label: 'west', metric: 'orders', value: 'west' },
  ],
};

const spec: ChartSpec = {
  type: 'bar',
  cartesian: { x: 'day', splitBy: 'wh', series: [{ metric: 'orders' }] },
};

const columns = [
  {
    alias: 'day',
    label: 'Day',
    role: 'group' as const,
    kind: 'datetime',
    dateUnit: 'DAY' as const,
    timeZone: 'UTC',
  },
];

let captured: CaptureChart | null = null;

function Harness() {
  const image = useChartImageSlot();
  useEffect(() => {
    captured = image.capture;
  }, [image.capture]);
  return (
    <AnalysisChart
      data={data}
      spec={spec}
      columns={columns}
      onPick={() => undefined}
      image={image.slot}
    />
  );
}

it('draws the picture off the page without a word in the console', async () => {
  const error = vi.spyOn(console, 'error');
  const warn = vi.spyOn(console, 'warn');
  const { container } = render(
    <ViewSurface>
      <Harness />
    </ViewSurface>,
  );
  await waitFor(() =>
    expect(
      container
        .querySelector('[data-slot="chart"]')
        ?.getAttribute('data-drawn'),
    ).toBe('true'),
  );
  await waitFor(() => expect(captured).not.toBeNull());
  const drawn = captured!(IMAGE_MIN_WIDTH)!;
  expect(drawn.width).toBeGreaterThanOrEqual(IMAGE_MIN_WIDTH);
  expect(drawn.legend.map(entry => entry.label)).toEqual(['east', 'west']);

  const picture = chartImageSvg(drawn, {
    title: 'Orders by day',
    range: 'Conditions: All records',
  });
  expect(picture.svg).toContain('Orders by day');
  expect(picture.svg).toContain('east');
  expect(error).not.toHaveBeenCalled();
  expect(warn).not.toHaveBeenCalled();
});
