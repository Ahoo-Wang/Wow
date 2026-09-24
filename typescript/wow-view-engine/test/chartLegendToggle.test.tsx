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

import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CartesianData, ChartSpec, PieData } from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { ChartLegend } from '../src/ui/charts/ChartLegend.js';
import { patternsPinned, usePatterns } from '../src/ui/charts/patterns.js';
import { readChartTheme } from '../src/ui/charts/theme.js';

/**
 * What a reader does with a long chart's frame (D33 batch A): switches a
 * series off in the legend and back on — by mouse or keyboard, told by
 * `aria-pressed` — and the reading table follows; scrolls the page past a
 * chart without the chart taking the wheel; and gets patterns over the
 * colours where their system asks for more contrast, or the host pins them.
 */

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const day = (n: number) => Date.UTC(2026, 0, 1) + n * 86_400_000;

function daily(count: number): CartesianData {
  return {
    type: 'cartesian',
    chart: 'line',
    timeline: true,
    points: Array.from({ length: count }, (_, index) => ({
      x: day(index),
      values: { east: 10 + index, south: 20 + index },
    })),
    series: [
      { key: 'east', label: 'east', metric: 'orders', value: 'east' },
      { key: 'south', label: 'south', metric: 'orders', value: 'south' },
    ],
  };
}

const SPEC: ChartSpec = {
  type: 'line',
  cartesian: { x: 'day', splitBy: 'wh', series: [{ metric: 'orders' }] },
};

const headers = (container: HTMLElement) =>
  [...container.querySelectorAll('[data-slot="chart-reading"] thead th')].map(
    cell => cell.textContent,
  );

describe('the legend switches a series off and back on', () => {
  it('says each switch’s state, and the reading table follows it', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={daily(5)} spec={SPEC} />
      </ViewSurface>,
    );
    const list = screen.getByRole('list', {
      name: 'Series shown — press one to hide or show it',
    });
    const south = within(list).getByRole('button', { name: 'south' });
    expect(south?.getAttribute('aria-pressed')).toBe('true');
    expect(headers(container)).toContain('south');

    fireEvent.click(south);
    expect(south?.getAttribute('aria-pressed')).toBe('false');
    expect(
      south
        .closest('[data-slot="chart-legend-item"]')
        ?.getAttribute('data-hidden'),
    ).toBe('true');
    expect(headers(container)).not.toContain('south');
    expect(headers(container)).toContain('east');
    // Still listed, and still in its colour.
    expect(within(list).getAllByRole('button')).toHaveLength(2);

    fireEvent.click(south);
    expect(south?.getAttribute('aria-pressed')).toBe('true');
    expect(headers(container)).toContain('south');
  });

  it('keeps the choice for a re-run of the same series, and lets go of one it lacks', () => {
    const { container, rerender } = render(
      <ViewSurface>
        <AnalysisChart data={daily(5)} spec={SPEC} />
      </ViewSurface>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'south' }));
    rerender(
      <ViewSurface>
        <AnalysisChart data={daily(6)} spec={SPEC} />
      </ViewSurface>,
    );
    expect(headers(container)).not.toContain('south');
    const east: CartesianData = {
      ...daily(3),
      series: [daily(3).series[0]],
    };
    rerender(
      <ViewSurface>
        <AnalysisChart data={east} spec={SPEC} />
      </ViewSurface>,
    );
    expect(headers(container)).toContain('east');
  });

  it('is text where there is nothing to switch: a pie’s slices', () => {
    const pie: PieData = {
      type: 'pie',
      slices: [
        { category: 'SH', value: 1 },
        { category: 'BJ', value: 2 },
      ],
    };
    render(
      <ViewSurface>
        <AnalysisChart
          data={pie}
          spec={{ type: 'pie', pie: { category: 'wh', value: 'orders' } }}
        />
      </ViewSurface>,
    );
    expect(
      document.querySelector('[data-slot="chart-legend-toggle"]'),
    ).toBeNull();
  });

  it('draws a switched-off entry as a ring, its name struck through', () => {
    const toggle = vi.fn();
    render(
      <ChartLegend
        at="top"
        onToggle={toggle}
        entries={[
          { key: 'a', label: 'A', color: 'red' },
          { key: 'b', label: 'B', color: 'blue', hidden: true },
        ]}
      />,
    );
    const b = screen.getByRole('button', { name: 'B' });
    const dot = b.querySelector<HTMLElement>('[data-slot="chart-legend-dot"]')!;
    expect(dot.style.background).toBe('transparent');
    expect(dot.style.borderColor).toBe('blue');
    fireEvent.click(b);
    expect(toggle).toHaveBeenCalledWith('b');
  });
});

describe('a long chart and the page’s wheel', () => {
  const chart = (zoomGestures?: boolean, count = 90) =>
    render(
      <ViewSurface>
        <AnalysisChart
          data={daily(count)}
          spec={SPEC}
          zoomGestures={zoomGestures}
        />
      </ViewSurface>,
    );

  it('zooms by its slider alone unless the host gives it gestures', () => {
    const { container } = chart();
    expect(
      container.querySelector('[data-slot="chart"]')?.getAttribute('data-zoom'),
    ).toBe('slider');
    cleanup();
    const workbench = chart(true);
    expect(
      workbench.container
        .querySelector('[data-slot="chart"]')
        ?.getAttribute('data-zoom'),
    ).toBe('gestures');
    cleanup();
    const short = chart(true, 30);
    expect(
      short.container
        .querySelector('[data-slot="chart"]')
        ?.hasAttribute('data-zoom'),
    ).toBe(false);
  });

  it('lets a plain wheel pass it by, and hands a Ctrl wheel to the drawing', () => {
    const { container } = chart(true);
    const drawing = container.querySelector<HTMLElement>(
      '[data-slot="chart-plot"] > div',
    )!;
    const reached = vi.fn();
    drawing.addEventListener('wheel', reached);
    const target = drawing.firstElementChild ?? drawing;
    const plain = new WheelEvent('wheel', { bubbles: true, cancelable: true });
    target.dispatchEvent(plain);
    expect(reached).not.toHaveBeenCalled();
    expect(plain.defaultPrevented).toBe(false);
    target.dispatchEvent(
      new WheelEvent('wheel', { bubbles: true, ctrlKey: true }),
    );
    expect(reached).toHaveBeenCalledTimes(1);
  });
});

describe('patterns over the colours', () => {
  /** A `matchMedia` whose more-contrast answer the test can change. */
  function contrast(more: boolean) {
    const listeners = new Set<() => void>();
    const list = {
      matches: more,
      addEventListener: (_: string, listener: () => void) =>
        listeners.add(listener),
      removeEventListener: (_: string, listener: () => void) =>
        listeners.delete(listener),
    };
    vi.stubGlobal('matchMedia', () => list);
    return (next: boolean) => {
      list.matches = next;
      listeners.forEach(listener => listener());
    };
  }

  it('follow the reader’s system, live', () => {
    const set = contrast(false);
    const { result } = renderHook(() => usePatterns());
    expect(result.current).toBe(false);
    act(() => set(true));
    expect(result.current).toBe(true);
  });

  it('are off where the platform cannot say', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(renderHook(() => usePatterns()).result.current).toBe(false);
  });

  it('take the host’s pin over the system', () => {
    expect(patternsPinned(' on ')).toBe(true);
    expect(patternsPinned('OFF')).toBe(false);
    expect(patternsPinned('auto')).toBeUndefined();
    expect(patternsPinned('')).toBeUndefined();
    const element = document.createElement('div');
    element.style.setProperty('--fve-chart-patterns', 'on');
    document.body.append(element);
    expect(readChartTheme(element).patterns).toBe(true);
    element.remove();
  });

  it('are said on the frame', () => {
    contrast(true);
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={daily(5)} spec={SPEC} />
      </ViewSurface>,
    );
    expect(
      container
        .querySelector('[data-slot="chart"]')
        ?.getAttribute('data-patterns'),
    ).toBe('on');
  });
});
