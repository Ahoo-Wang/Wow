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
 * The chart on screen as a picture (D33 Q58), from the menu 「导出数据…」 is
 * in (D25 Q28): an SVG handed over at once, headed by the view's title and
 * the conditions, named after the title and the day; a PNG the page refused
 * to draw said on one line, which goes when read; and the one sentence a
 * screen reader hears after the chart's name, and what the options page
 * says of a log scale and a split past the palette.
 */

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type RecordData,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const ROWS: RecordData[] = [
  { warehouse: 'CN', orders: 12 },
  { warehouse: 'US', orders: 3 },
  { warehouse: 'DE', orders: 0 },
];

function show(
  config: AnalysisViewConfig = chartConfig(),
  rows: readonly RecordData[] = ROWS,
) {
  const engine = new ViewEngine({
    definitions: [
      ordersDefinition({
        analysis: {
          count: true,
          fields: [
            {
              field: 'warehouse',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            {
              field: 'status',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            {
              field: 'amount',
              groups: [],
              functions: [AggregationFunction.SUM, AggregationFunction.AVG],
            },
          ],
        },
      }),
    ],
    store: new MemoryViewStore({
      instances: [
        {
          id: 'orders-1',
          definitionId: 'orders',
          title: 'By warehouse',
          scope: 'personal',
          revision: '1',
          config,
        },
      ],
    }),
    resolveSource: () =>
      testSource({ aggregate: vi.fn(() => Promise.resolve([...rows])) }),
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  return { user: userEvent.setup({ pointerEventsCheck: 0 }) };
}

function chartConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisConfig({ layout: 'chart', ...overrides });
}

/** Every file handed to the browser: its blob and the name it goes under. */
function downloads() {
  const files: { blob: Blob; name: string }[] = [];
  let last: Blob | undefined;
  vi.spyOn(URL, 'createObjectURL').mockImplementation(blob => {
    last = blob as Blob;
    return 'blob:file';
  });
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    if (last) files.push({ blob: last, name: this.download });
  });
  return files;
}

async function exportMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Export' }));
  return screen.findByRole('menu');
}

describe('the chart as a picture', () => {
  it('hands over an SVG headed by the title and the conditions', async () => {
    const files = downloads();
    const { user } = show();
    await screen.findByRole('img', { name: /Record count by Warehouse/ });
    const menu = await exportMenu(user);
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['Export data…', 'Export image (PNG)', 'Export image (SVG)']);

    await user.click(
      within(menu).getByRole('menuitem', { name: 'Export image (SVG)' }),
    );
    await waitFor(() => expect(files).toHaveLength(1));
    const [file] = files;
    expect(file.name).toMatch(/^By warehouse-\d{4}-\d{2}-\d{2}\.svg$/);
    expect(file.blob.type).toBe('image/svg+xml');
    const svg = await file.blob.text();
    expect(svg).toContain('By warehouse');
    expect(svg).toContain('Conditions: All records');
  });

  it('says a PNG the page would not draw, and lets the line go', async () => {
    downloads();
    // An image that never loads: a page whose `img-src` refuses `blob:`.
    class Refused {
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      set src(_url: string) {
        queueMicrotask(() => this.onerror?.());
      }
    }
    vi.stubGlobal('Image', Refused);
    const { user } = show();
    await screen.findByRole('img', { name: /Record count by Warehouse/ });
    const menu = await exportMenu(user);
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Export image (PNG)' }),
    );

    const failed = await screen.findByRole('alert');
    expect(failed.textContent).toContain('The image could not be made.');
    await user.click(within(failed).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('keeps the plain button over a table: there is no picture to take', async () => {
    const { user } = show(analysisConfig());
    const button = await screen.findByRole('button', { name: 'Export' });
    expect(button.getAttribute('aria-haspopup')).not.toBe('menu');
    await user.click(button);
    expect(await screen.findByRole('dialog', { name: 'Export' })).toBeTruthy();
  });
});

describe('what a screen reader hears of a chart', () => {
  it('its name, then one sentence: the groups, the highest and the lowest', async () => {
    show();
    const plot = await screen.findByRole('img', {
      name: /Record count by Warehouse/,
    });
    const described = plot.getAttribute('aria-describedby');
    expect(described).not.toBeNull();
    expect(document.getElementById(described!)?.textContent).toBe(
      '3 groups; highest CN, 12; lowest DE, 0.',
    );
  });
});

describe('the options page', () => {
  it('greys a log scale over a 0, and says why', async () => {
    const { user } = show();
    await screen.findByRole('img', { name: /Record count by Warehouse/ });
    await user.click(screen.getByRole('button', { name: 'Visualize' }));
    await user.click(screen.getByRole('button', { name: /options$/ }));
    await user.click(screen.getByRole('tab', { name: 'Axes' }));

    const log = screen.getByRole('button', { name: 'Logarithmic' });
    expect(
      log.hasAttribute('disabled') ||
        log.getAttribute('aria-disabled') === 'true',
    ).toBe(true);
    expect(
      screen.getByText(
        'A log scale has no place for 0 or a negative number, and this axis holds one.',
      ),
    ).toBeTruthy();
  });

  it('says a split past the palette that cannot fold repeats its colours', async () => {
    const statuses = Array.from({ length: 9 }, (_, index) => ({
      warehouse: 'CN',
      status: `s${index}`,
      amount: index + 1,
    }));
    const { user } = show(
      chartConfig({
        groups: [
          { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
          { alias: 'status', field: 'status', type: 'TERMS' },
        ],
        metrics: [
          {
            alias: 'amount',
            type: 'NUMERIC',
            function: 'AVG',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        chart: {
          type: 'bar',
          cartesian: {
            x: 'warehouse',
            splitBy: 'status',
            series: [{ metric: 'amount' }],
          },
        },
      }),
      statuses,
    );
    await waitFor(() =>
      expect(document.querySelector('[data-slot="chart-plot"]')).not.toBeNull(),
    );
    await user.click(screen.getByRole('button', { name: 'Visualize' }));
    await user.click(screen.getByRole('button', { name: /options$/ }));
    await user.click(screen.getByRole('tab', { name: 'Display' }));
    expect(
      screen.getByText(
        'More than 8 series, so colours repeat. A heatmap or the table reads them better.',
      ),
    ).toBeTruthy();
  });
});
