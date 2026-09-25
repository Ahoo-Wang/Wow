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
 * A span and a press on the screen (D33 batch C): a drag along a time axis
 * and a second row picked with Shift both open the follow-up menu over a
 * stretch of time, said aloud as it opens; a first tap on a touch screen
 * reads a mark and the second presses it; a funnel staged by a dimension is
 * pressed a stage at a time. The drag in a real browser, and the board's
 * 「设为〈筛选〉」 end to end, are the stories 「框选与追问/回归」.
 */

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type AnalysisViewConfig,
  type ChartData,
  type ChartSpec,
  type ViewInstance,
} from '../src/index.js';
import type { FollowUp } from '../src/react/index.js';
import {
  AnalysisChart,
  DataWorkbench,
  ViewSurface,
  defaultMessages,
} from '../src/ui/index.js';
import { DrillMenu, pickOf } from '../src/ui/analysis/DrillMenu.js';
import {
  analysisConfig,
  dailyOrdersDefinition,
  mine,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const DAY_MS = 86_400_000;
const FIRST = Date.UTC(2026, 8, 1);
const day = (n: number) => FIRST + n * DAY_MS;

/** The plot's drawing surface, where the library listens for the pointer. */
function surfaceOf(container: HTMLElement): Element {
  return container.querySelector('[data-slot="chart-plot"] > div > div')!;
}

/** One mouse event at a point of the drawing, as a pointer sends it. */
function mouse(target: Element, type: string, x: number, y: number) {
  const event = new MouseEvent(type, {
    bubbles: true,
    cancelable: true,
    clientX: x,
    clientY: y,
  });
  Object.defineProperties(event, {
    offsetX: { value: x },
    offsetY: { value: y },
  });
  fireEvent(target, event);
}

/** The follow-up menu once it is open, named as asked. */
async function drillMenu(name: string): Promise<HTMLElement> {
  return waitFor(() => {
    const found = document.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    expect(found?.getAttribute('aria-label')).toBe(name);
    return found!;
  });
}

/** A date dimension and a count, as the result names its columns. */
const DAILY_COLUMNS = [
  {
    alias: 'day',
    label: 'Day',
    role: 'group' as const,
    kind: 'datetime',
    dateUnit: 'DAY' as const,
    timeZone: 'UTC',
  },
  {
    alias: 'orders',
    label: 'Orders',
    role: 'metric' as const,
    fn: 'COUNT' as const,
  },
];

/** A pointer going down on the plot, of the type asked. */
function pointerDown(container: HTMLElement, pointerType: string) {
  const event = new Event('pointerdown', { bubbles: true });
  Object.defineProperty(event, 'pointerType', { value: pointerType });
  fireEvent(container.querySelector('[data-slot="chart-plot"] > div')!, event);
}

describe('a brush along a time axis (D33 Q52)', () => {
  const data: ChartData = {
    type: 'cartesian',
    chart: 'bar',
    timeline: true,
    points: Array.from({ length: 10 }, (_, n) => ({
      x: day(n),
      values: { orders: n + 1 },
    })),
    series: [{ key: 'orders', label: 'Orders', metric: 'orders' }],
  };
  const spec: ChartSpec = {
    type: 'bar',
    cartesian: { x: 'day', series: [{ metric: 'orders' }] },
  };

  it('hands the stretch dragged over to the follow-up as its two ends', async () => {
    const onPick = vi.fn();
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={data}
          spec={spec}
          columns={DAILY_COLUMNS}
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
    // Along the middle of the plot, from about the third day to the sixth.
    const surface = surfaceOf(container);
    // A mouse going down on the plot takes the drag for the brush.
    pointerDown(container, 'mouse');
    mouse(surface, 'mousemove', 300, 400);
    mouse(surface, 'mousedown', 300, 400);
    for (let x = 320; x <= 560; x += 40) mouse(surface, 'mousemove', x, 400);
    mouse(surface, 'mouseup', 560, 400);
    await waitFor(() => expect(onPick).toHaveBeenCalledTimes(1));
    const [row, anchor, origin, through] = onPick.mock.calls[0]!;
    expect(origin).toBeUndefined();
    expect(typeof anchor.getBoundingClientRect).toBe('function');
    expect(row.day).toBeLessThan(through.day);
    expect(Object.keys(row)).toEqual(['day']);
    expect(Object.keys(through)).toEqual(['day']);
    expect(frame.getAttribute('data-brushed')).toBe('true');
    // No menu stood over it: the cover goes.
    await waitFor(() => expect(frame.hasAttribute('data-brushed')).toBe(false));
  });

  it('brushes nothing the reader cannot follow up on, or an axis of categories', async () => {
    const { container, rerender } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} columns={DAILY_COLUMNS} />
      </ViewSurface>,
    );
    const frame = container.querySelector('[data-slot="chart"]')!;
    expect(frame.hasAttribute('data-brush')).toBe(false);
    const categories: ChartData = {
      ...data,
      timeline: undefined,
      points: [
        { x: 'CN', values: { orders: 1 } },
        { x: 'JP', values: { orders: 2 } },
      ],
    };
    rerender(
      <ViewSurface>
        <AnalysisChart data={categories} spec={spec} onPick={vi.fn()} />
      </ViewSurface>,
    );
    expect(
      container
        .querySelector('[data-slot="chart"]')!
        .hasAttribute('data-brush'),
    ).toBe(false);
  });

  it('leaves a finger to scroll the page: no brush under a coarse pointer', () => {
    const coarse = vi.spyOn(window, 'matchMedia').mockImplementation(
      query =>
        ({
          matches: query.includes('pointer: coarse'),
          media: query,
          addEventListener: () => {},
          removeEventListener: () => {},
        }) as unknown as MediaQueryList,
    );
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={data}
          spec={spec}
          columns={DAILY_COLUMNS}
          onPick={vi.fn()}
        />
      </ViewSurface>,
    );
    expect(
      container
        .querySelector('[data-slot="chart"]')!
        .hasAttribute('data-brush'),
    ).toBe(false);
    coarse.mockRestore();
  });
});

describe('a tap on a touch screen', () => {
  const data: ChartData = {
    type: 'cartesian',
    chart: 'bar',
    points: [
      { x: 'CN', values: { orders: 4 } },
      { x: 'JP', values: { orders: 2 } },
    ],
    series: [{ key: 'orders', label: 'Orders', metric: 'orders' }],
  };
  const spec: ChartSpec = {
    type: 'bar',
    cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
  };

  /** A tap on the first bar: the pointer's type, then the mouse's events. */
  async function tap(container: HTMLElement, pointerType: string) {
    const bar = await waitFor(() => {
      const found = [
        ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
      ].find(path => path.getAttribute('fill') === 'rgb(38, 117, 211)');
      expect(found).toBeDefined();
      return found!;
    });
    const xs = [
      ...(bar.getAttribute('d') ?? '').matchAll(
        /[ML]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
      ),
    ].map(([, x, y]) => [Number(x), Number(y)]);
    const x =
      (Math.min(...xs.map(p => p[0]!)) + Math.max(...xs.map(p => p[0]!))) / 2;
    const y =
      (Math.min(...xs.map(p => p[1]!)) + Math.max(...xs.map(p => p[1]!))) / 2;
    pointerDown(container, pointerType);
    const surface = surfaceOf(container);
    for (const type of ['mousemove', 'mousedown', 'mouseup', 'click'])
      mouse(surface, type, x, y);
  }

  it('reads the mark first, with a word to tap again, and presses it on the second tap', async () => {
    const onPick = vi.fn();
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} onPick={onPick} />
      </ViewSurface>,
    );
    const frame = container.querySelector<HTMLElement>('[data-slot="chart"]')!;
    expect(frame.style.getPropertyValue('--_fve-tap-hint')).toBe(
      `"${defaultMessages['label.drill.tap-again']}"`,
    );
    await tap(container, 'touch');
    expect(onPick).not.toHaveBeenCalled();
    expect(frame.getAttribute('data-tap-armed')).toBe('true');
    await tap(container, 'touch');
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0]![0]).toEqual({ warehouse: 'CN' });
    expect(frame.hasAttribute('data-tap-armed')).toBe(false);
    // A mouse presses at once.
    await tap(container, 'mouse');
    expect(onPick).toHaveBeenCalledTimes(2);
  });
});

describe('a funnel staged by a dimension', () => {
  const data: ChartData = {
    type: 'funnel',
    stages: [
      { label: 'CN', value: 4, group: 'CN', conversion: 1 },
      { label: 'JP', value: 2, group: 'JP', conversion: 0.5 },
    ],
  };
  const spec: ChartSpec = {
    type: 'funnel',
    funnel: {
      stages: {
        from: 'group',
        category: 'warehouse',
        value: 'orders',
        order: ['CN', 'JP'],
      },
    },
  };

  it('is pressed a stage at a time, and marks the stage a board set', async () => {
    const onPick = vi.fn();
    const { container, rerender } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} onPick={onPick} />
      </ViewSurface>,
    );
    const stage = await waitFor(() => {
      const found = [
        ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
      ].find(path => path.getAttribute('fill') === 'rgb(38, 117, 211)');
      expect(found).toBeDefined();
      return found!;
    });
    const corners = [
      ...(stage.getAttribute('d') ?? '').matchAll(
        /[ML]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
      ),
    ].map(([, x, y]) => [Number(x), Number(y)]);
    const x =
      (Math.min(...corners.map(p => p[0]!)) +
        Math.max(...corners.map(p => p[0]!))) /
      2;
    const y =
      (Math.min(...corners.map(p => p[1]!)) +
        Math.max(...corners.map(p => p[1]!))) /
      2;
    const surface = surfaceOf(container);
    for (const type of ['mousemove', 'mousedown', 'mouseup', 'click'])
      mouse(surface, type, x, y);
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0]![0]).toEqual({ warehouse: 'CN' });

    rerender(
      <ViewSurface>
        <AnalysisChart
          data={data}
          spec={spec}
          highlight={row => row.warehouse === 'JP'}
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(
        [
          ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
        ].filter(path => path.getAttribute('fill-opacity') === '0.5'),
      ).toHaveLength(1),
    );
  });

  it('presses nothing when its stages are metrics', async () => {
    const onPick = vi.fn();
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'funnel',
            stages: [
              { label: 'orders', value: 4 },
              { label: 'paid', value: 2 },
            ],
          }}
          spec={{
            type: 'funnel',
            funnel: {
              stages: {
                from: 'metrics',
                items: [{ metric: 'orders' }, { metric: 'paid' }],
              },
            },
          }}
          onPick={onPick}
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(
        container
          .querySelector('[data-slot="chart"]')!
          .getAttribute('data-drawn'),
      ).toBe('true'),
    );
    const surface = surfaceOf(container);
    for (const type of ['mousemove', 'mousedown', 'mouseup', 'click'])
      mouse(surface, type, 200, 200);
    expect(onPick).not.toHaveBeenCalled();
  });
});

describe('the follow-up menu over a span', () => {
  const followUp = (run = vi.fn()): FollowUp => ({
    groups: [
      {
        conditions: [
          {
            path: ['children', 0],
            field: 'createdAt',
            label: 'Created',
            kind: 'datetime',
            operator: 'BETWEEN',
            text: '',
            unresolved: false,
            value: {
              kind: 'periods',
              unit: 'DAY',
              from: new Date(day(0)).toISOString(),
              last: new Date(day(2)).toISOString(),
              timeZone: 'UTC',
            },
          },
        ],
      },
    ],
    actions: [
      { kind: 'focus', subject: 'By day', run: vi.fn() },
      { kind: 'filter', filter: 'Created', run },
    ],
  });

  it('names the stretch, says it aloud, and sets a board’s filter to it', async () => {
    const run = vi.fn();
    const onClose = vi.fn();
    render(
      <ViewSurface>
        <DrillMenu
          pick={pickOf({ day: day(0) }, document.body, undefined, {
            day: day(2),
          })}
          followUp={followUp(run)}
          onClose={onClose}
        />
      </ViewSurface>,
    );
    const menu = await drillMenu('This period');
    expect(within(menu).getByText(/Sep 1, 2026/)).toBeDefined();
    expect(
      within(menu).getByRole('menuitem', { name: 'Only this period' }),
    ).toBeDefined();
    fireEvent.click(
      within(menu).getByRole('menuitem', {
        name: 'Set “Created” to this period',
      }),
    );
    expect(run).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalled();
    const said = document.querySelector('[data-slot="drill-announcement"]');
    expect(said?.textContent).toMatch(
      /^Selected Created between Sep 1, 2026 .+ Sep 3, 2026\. Follow-up menu open\.$/,
    );
  });

  it('keeps one group’s words for a press on one group', async () => {
    const one: FollowUp = {
      ...followUp(),
      actions: [{ kind: 'focus', subject: 'By day', run: vi.fn() }],
    };
    render(
      <ViewSurface>
        <DrillMenu
          pick={pickOf({ day: day(0) }, document.body)}
          followUp={one}
          onClose={vi.fn()}
        />
      </ViewSurface>,
    );
    const menu = await drillMenu('This group');
    expect(
      within(menu).getByRole('menuitem', { name: 'Only this group' }),
    ).toBeDefined();
    expect(
      document.querySelector('[data-slot="drill-announcement"]')?.textContent,
    ).toBe('');
  });
});

describe('a span picked from the table with Shift', () => {
  const daily: ViewInstance = {
    id: 'orders-daily',
    definitionId: 'orders',
    title: 'By day',
    scope: 'shared',
    revision: '1',
    config: analysisConfig({
      layout: 'table',
      groups: [
        {
          alias: 'day',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'DAY',
        },
      ],
    } satisfies Partial<AnalysisViewConfig>),
  };

  it('opens the menu over every day between the two rows, and the records of them', async () => {
    const source = testSource({
      aggregate: vi.fn(() =>
        Promise.resolve(
          [0, 1, 2, 3].map(n => ({ day: day(n), orders: n + 1 })),
        ),
      ),
    });
    const engine = new ViewEngine({
      definitions: [dailyOrdersDefinition()],
      store: new MemoryViewStore({ instances: [mine, daily] }),
      resolveSource: () => source,
      environment: defaultRuntimeEnvironment({ timeZone: 'UTC' }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={daily.id}
      />,
    );
    const table = await screen.findByRole('table');
    const rows = await waitFor(() => {
      const found = within(table)
        .getAllByRole('row')
        .filter(row => row.hasAttribute('data-pickable'));
      expect(found).toHaveLength(4);
      return found;
    });
    // Said to whoever reaches a row: Shift picks the other end.
    const hint = rows[0]!.getAttribute('aria-describedby');
    expect(document.getElementById(hint!)?.textContent).toBe(
      defaultMessages['label.drill.span-hint'],
    );
    // The first end: a press on one day, its own menu.
    fireEvent.keyDown(rows[0]!, { key: 'Enter' });
    expect(await drillMenu('This group')).toBeDefined();
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
    // The other end, with Shift: the three days from one to the other.
    fireEvent.keyDown(rows[2]!, { key: 'Enter', shiftKey: true });
    const menu = await drillMenu('This period');
    expect(
      document.querySelector('[data-slot="drill-announcement"]')?.textContent,
    ).toContain('Follow-up menu open');
    fireEvent.click(
      within(menu).getByRole('menuitem', { name: 'See these records' }),
    );
    // Exactly the three days: the first one's start to the third one's end.
    await waitFor(() =>
      expect(source.paged).toHaveBeenCalledWith(
        expect.objectContaining({
          filter: {
            field: 'createdAt',
            op: 'BETWEEN',
            lowerBound: day(0),
            upperBound: day(3) - 1,
          },
        }),
        undefined,
        expect.anything(),
      ),
    );
  });
});
