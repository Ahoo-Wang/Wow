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
 * The opening and the closing value in the tray, and the candlestick drawn
 * from them in the workbench (N1): the summaries a field offers, the time an
 * end is ordered by, 「补齐 K 线的四个数」, the tile, the drawing and its
 * reading.
 */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { AnalysisChart, DataWorkbench, ViewSurface } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

const CLOSE: AnalysisMetric = { alias: 'close', type: 'LAST', field: 'price' };

/** Each metric by what it is: a candle's four prices, rising or falling. */
function answer(query: {
  groupBy?: { alias?: string }[];
  metrics?: { alias?: string; type?: string; function?: string }[];
}): RecordData[] {
  const days = [1, 2, 3];
  const value = (
    metric: { type?: string; function?: string },
    day: number,
  ): number => {
    // Day 2 closes below its open; the others above.
    const [open, close] = day === 2 ? [12, 9] : [10, 11 + day];
    if (metric.type === 'FIRST') return open;
    if (metric.type === 'LAST') return close;
    if (metric.function === 'MAX') return Math.max(open, close) + 1;
    if (metric.function === 'MIN') return Math.min(open, close) - 1;
    return day;
  };
  if ((query.groupBy ?? []).length === 0) return [{}];
  return days.map(day => ({
    day: Date.UTC(2026, 8, day),
    ...Object.fromEntries(
      (query.metrics ?? []).map(metric => [
        metric.alias ?? '',
        value(metric, day),
      ]),
    ),
  }));
}

async function open(
  metrics: AnalysisMetric[] = [CLOSE],
  chart?: AnalysisViewConfig['chart'],
) {
  const source: ViewSource = testSource({
    aggregate: vi.fn((query: Parameters<typeof answer>[0]) =>
      Promise.resolve(answer(query)),
    ) as ViewSource['aggregate'],
  });
  const config = {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [
      { type: 'DATE_HISTOGRAM', field: 'placedAt', alias: 'day', unit: 'DAY' },
    ],
    metrics,
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: chart ?? {
      type: 'line',
      cartesian: { x: 'day', series: [{ metric: metrics[0]!.alias }] },
    },
  } as unknown as AnalysisViewConfig;
  const instance: ViewInstance = {
    id: 'trades-1',
    definitionId: 'orders',
    title: 'Daily price',
    scope: 'personal',
    revision: '1',
    config,
  };
  const definition = ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'status', label: 'Status', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number' },
      { name: 'placedAt', label: 'Placed', kind: 'datetime' },
      { name: 'paidAt', label: 'Paid', kind: 'datetime' },
      { name: 'price', label: 'Price', kind: 'number' },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'placedAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        },
        {
          field: 'price',
          groups: [],
          functions: [AggregationFunction.MIN, AggregationFunction.MAX],
          firstLast: true,
        },
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.MIN, AggregationFunction.MAX],
          firstLast: true,
        },
      ],
    },
  });
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="trades-1"
      kinds={['analysis']}
    />,
  );
  return {
    source,
    draft: () =>
      engine.openRuntimes()[0]!.getSnapshot().draft as AnalysisViewConfig,
  };
}

const tile = (type: string) =>
  document.querySelector<HTMLElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;

async function openMenu(card: HTMLElement) {
  fireEvent.click(card.querySelector<HTMLElement>('[data-slot="card-menu"]')!);
  return screen.findByRole('menu');
}

describe('the opening and the closing value in the tray', () => {
  it('offers both ends as summaries, ordered by the event time or a time the unit holds', async () => {
    const user = userEvent.setup();
    const { draft } = await open();
    const tray = await openTray();
    const card = tray.querySelector<HTMLElement>('[data-slot="metric-card"]')!;
    expect(card.getAttribute('data-metric')).toBe('LAST');
    expect(
      card.querySelector('[data-slot="metric-note"]')?.textContent,
    ).toContain('earliest and latest record');

    const summary = within(card).getByRole('combobox', {
      name: 'Summary for Price',
    });
    expect(summary.textContent).toContain('Closing value');
    await user.click(summary);
    expect(
      (await screen.findAllByRole('option')).map(option => option.textContent),
    ).toEqual(['Min', 'Max', 'Opening value', 'Closing value']);
    await user.keyboard('{Escape}');

    const order = within(card).getByRole('combobox', {
      name: /^Order of /,
    });
    expect(order.textContent).toContain('By event time');
    await user.click(order);
    await user.click(await screen.findByRole('option', { name: 'By Paid' }));
    await waitFor(() =>
      expect(draft().metrics[0]).toEqual({
        alias: 'close',
        type: 'LAST',
        field: 'price',
        orderBy: 'paidAt',
      }),
    );

    // Switched to the other end, the order stays.
    await user.click(
      within(card).getByRole('combobox', { name: 'Summary for Price' }),
    );
    await user.click(
      await screen.findByRole('option', { name: 'Opening value' }),
    );
    await waitFor(() =>
      expect(draft().metrics[0]).toMatchObject({
        type: 'FIRST',
        orderBy: 'paidAt',
      }),
    );

    // And back to the event time, which is no `orderBy` at all.
    await user.click(
      within(card).getByRole('combobox', { name: /^Order of / }),
    );
    await user.click(
      await screen.findByRole('option', { name: 'By event time' }),
    );
    await waitFor(() =>
      expect(draft().metrics[0]).not.toHaveProperty('orderBy'),
    );
  });
});

describe('the candlestick in the workbench (N1)', () => {
  it('is greyed until a card adds the four numbers, then drawn from them', async () => {
    const { draft } = await open();
    await screen.findByRole('img', { name: /^line:/ });
    fireEvent.click(await screen.findByRole('button', { name: 'Visualize' }));
    await waitFor(() => expect(tile('candlestick')).not.toBeNull());
    expect(tile('candlestick').getAttribute('aria-disabled')).toBe('true');
    expect(
      tile('candlestick').querySelector('[data-slot="chart-reason"]')
        ?.textContent,
    ).toBe('Needs a field’s opening value, maximum, minimum and closing value');

    const tray = await openTray();
    const card = tray.querySelector<HTMLElement>('[data-slot="metric-card"]')!;
    fireEvent.click(
      within(await openMenu(card)).getByRole('menuitem', {
        name: 'Add the four numbers for a candlestick',
      }),
    );
    await waitFor(() => expect(draft().metrics).toHaveLength(4));
    expect(draft().metrics.map(metric => metric.type)).toEqual([
      'LAST',
      'FIRST',
      'NUMERIC',
      'NUMERIC',
    ]);
    fireEvent.click(await screen.findByRole('button', { name: /^Apply/ }));
    await waitFor(() =>
      expect(tile('candlestick').getAttribute('aria-disabled')).toBeNull(),
    );
    fireEvent.click(tile('candlestick'));
    const frame = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart"][data-chart="candlestick"]',
      );
      expect(found).not.toBeNull();
      expect(
        found!.querySelector('[data-slot="chart-plot"] svg'),
      ).not.toBeNull();
      return found!;
    });
    expect(frame.getAttribute('data-marks')).toBe('3');
    expect(draft().chart.candlestick).toEqual({
      x: 'day',
      open: draft().metrics[1]!.alias,
      high: draft().metrics[2]!.alias,
      low: draft().metrics[3]!.alias,
      close: 'close',
    });

    // The reading table says each period's four numbers, and which way it
    // went in words.
    const rows = await waitFor(() => {
      const found = [
        ...document.querySelectorAll('[data-slot="chart-reading"] tbody tr'),
      ].map(row => [...row.children].map(cell => cell.textContent));
      expect(found).toHaveLength(3);
      return found;
    });
    expect(rows.map(row => row[row.length - 1])).toEqual([
      'Closed higher',
      'Closed lower',
      'Closed higher',
    ]);
    expect(rows[1]!.slice(1, 5)).toEqual(['12', '13', '8', '9']);

    // Its options ask for the period only: one field, one set.
    fireEvent.click(
      screen.getByRole('button', { name: 'candlestick options' }),
    );
    const panel = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart-options"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(
      within(panel).getByRole('combobox', { name: 'One candle per' }),
    ).toBeDefined();
    expect(
      within(panel).queryByRole('combobox', { name: 'Moves of' }),
    ).toBeNull();
  });
});

/** A press on the first painted candle body, at the middle of its first edge. */
function pressBody(container: HTMLElement) {
  const body = [
    ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
  ].find(
    path =>
      path.getAttribute('stroke') !== null &&
      !['none', 'transparent', null].includes(path.getAttribute('fill')),
  )!;
  const points = [
    ...(body.getAttribute('d') ?? '').matchAll(
      /[ML]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
    ),
  ].map(([, x, y]) => [Number(x), Number(y)]);
  const [dx, dy] = /translate\((-?[\d.]+)[\s,]+(-?[\d.]+)\)/
    .exec(body.getAttribute('transform') ?? '')
    ?.slice(1)
    .map(Number) ?? [0, 0];
  const [a, b] = points;
  const at = {
    clientX: (a![0]! + b![0]!) / 2 + dx!,
    clientY: (a![1]! + b![1]!) / 2 + dy!,
  };
  const surface = container.querySelector(
    '[data-slot="chart-plot"] > div > div',
  )!;
  for (const type of ['mousemove', 'mousedown', 'mouseup', 'click']) {
    const event = new MouseEvent(type, { bubbles: true, ...at });
    Object.defineProperties(event, {
      offsetX: { value: at.clientX },
      offsetY: { value: at.clientY },
    });
    fireEvent(surface, event);
  }
}

describe('two fields’ candles', () => {
  it('ask whose moves are drawn, and swap the four together', async () => {
    const user = userEvent.setup();
    const four = (field: string, at: string): AnalysisMetric[] => [
      { alias: `${at}o`, type: 'FIRST', field },
      {
        alias: `${at}h`,
        type: 'NUMERIC',
        function: 'MAX',
        expression: { type: 'FIELD', field },
      },
      {
        alias: `${at}l`,
        type: 'NUMERIC',
        function: 'MIN',
        expression: { type: 'FIELD', field },
      },
      { alias: `${at}c`, type: 'LAST', field },
    ];
    const { draft } = await open(
      [...four('price', 'p'), ...four('amount', 'a')],
      {
        type: 'candlestick',
        candlestick: {
          x: 'day',
          open: 'po',
          high: 'ph',
          low: 'pl',
          close: 'pc',
        },
      },
    );
    await screen.findByRole('img', { name: /^candlestick:/ });
    fireEvent.click(await screen.findByRole('button', { name: 'Visualize' }));
    fireEvent.click(
      await screen.findByRole('button', { name: 'candlestick options' }),
    );
    const panel = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart-options"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await user.click(within(panel).getByRole('combobox', { name: 'Moves of' }));
    await user.click(
      await screen.findByRole('option', { name: 'Closing value of Amount' }),
    );
    await waitFor(() =>
      expect(draft().chart.candlestick).toEqual({
        x: 'day',
        open: 'ao',
        high: 'ah',
        low: 'al',
        close: 'ac',
      }),
    );
  });
});

describe('a candlestick pressed and read', () => {
  it('hands a candle’s period back, and says how many periods it left out', async () => {
    const onPick = vi.fn();
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'candlestick',
            candles: [
              {
                x: 1,
                open: 10,
                high: 14,
                low: 9,
                close: 13,
                direction: 'rise',
              },
              { x: 2, open: 13, high: 13, low: 8, close: 9, direction: 'fall' },
            ],
            omitted: 1,
          }}
          spec={{
            type: 'candlestick',
            candlestick: {
              x: 'day',
              open: 'o',
              high: 'h',
              low: 'l',
              close: 'c',
            },
          }}
          onPick={onPick}
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(
        container.querySelector('[data-slot="chart-plot"] svg path'),
      ).not.toBeNull(),
    );
    expect(
      container.querySelector('[data-slot="candlestick-notes"]')?.textContent,
    ).toBe('1 periods without all four numbers are not drawn');
    pressBody(container);
    await waitFor(() => expect(onPick).toHaveBeenCalled());
    expect(onPick.mock.calls[0]![0]).toEqual({ day: 1 });
  });
});
