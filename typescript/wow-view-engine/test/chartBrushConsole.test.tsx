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
 * A brushed chart says nothing in the console. The library's brush asks for
 * a toolbox this package does not register, and its development build — the
 * one every host runs while building — logged 「Component toolbox is used
 * but not imported」 once per page (`withoutBrushToolbox`). A file of its
 * own: the library logs a missing component once per module instance, so a
 * chart drawn by an earlier test in the same file would hide it.
 */

import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { ChartData, ChartSpec } from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

const DAY_MS = 86_400_000;
const FIRST = Date.UTC(2026, 8, 1);

const data: ChartData = {
  type: 'cartesian',
  chart: 'bar',
  timeline: true,
  points: Array.from({ length: 10 }, (_, n) => ({
    x: FIRST + n * DAY_MS,
    values: { orders: n + 1 },
  })),
  series: [{ key: 'orders', label: 'Orders', metric: 'orders' }],
};

const spec: ChartSpec = {
  type: 'bar',
  cartesian: { x: 'day', series: [{ metric: 'orders' }] },
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

function mouse(target: Element, type: string, x: number) {
  const event = new MouseEvent(type, {
    bubbles: true,
    cancelable: true,
    clientX: x,
    clientY: 400,
  });
  Object.defineProperties(event, {
    offsetX: { value: x },
    offsetY: { value: 400 },
  });
  fireEvent(target, event);
}

it('draws, redraws and brushes without a word in the console', async () => {
  const error = vi.spyOn(console, 'error');
  const warn = vi.spyOn(console, 'warn');
  const onPick = vi.fn();
  const { container, rerender } = render(
    <ViewSurface>
      <AnalysisChart
        data={data}
        spec={spec}
        columns={columns}
        onPick={onPick}
      />
    </ViewSurface>,
  );
  const frame = await waitFor(() => {
    const found = container.querySelector('[data-slot="chart"]')!;
    expect(found.getAttribute('data-drawn')).toBe('true');
    return found;
  });
  expect(frame.getAttribute('data-brush')).toBe('on');
  // A whole new option, which runs the preprocessors again.
  rerender(
    <ViewSurface>
      <AnalysisChart
        data={{ ...data }}
        spec={spec}
        columns={columns}
        onPick={onPick}
      />
    </ViewSurface>,
  );
  const plot = container.querySelector('[data-slot="chart-plot"] > div')!;
  const surface = plot.firstElementChild!;
  const down = new Event('pointerdown', { bubbles: true });
  Object.defineProperty(down, 'pointerType', { value: 'mouse' });
  fireEvent(plot, down);
  mouse(surface, 'mousemove', 300);
  mouse(surface, 'mousedown', 300);
  for (let x = 320; x <= 560; x += 40) mouse(surface, 'mousemove', x);
  mouse(surface, 'mouseup', 560);
  // Brushing still works: the stretch reached the follow-up.
  await waitFor(() => expect(onPick).toHaveBeenCalledTimes(1));
  expect(onPick.mock.calls[0]![3]).toBeDefined();
  expect(error).not.toHaveBeenCalled();
  expect(warn).not.toHaveBeenCalled();
});
