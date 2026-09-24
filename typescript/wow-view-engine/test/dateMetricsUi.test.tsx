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
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  shapeChart,
  type AnalysisView,
  type AnalysisViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import {
  AnalysisChart,
  AnalysisTable,
  DataWorkbench,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';
import {
  INSTANT,
  ZONE,
  analysisConfig,
  inZone,
  ordersDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/** The latest creation time per warehouse, as the projection describes it. */
const latest: AnalysisView['columns'][number] = {
  alias: 'latest',
  label: 'Created',
  role: 'metric',
  fn: 'MAX',
  kind: 'datetime',
  cell: 'datetime',
};

describe('the earliest and the latest of a date, on screen', () => {
  it('reads as a datetime in the table, its header and its totals row', () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <AnalysisTable
          view={{
            columns: [
              { alias: 'wh', label: 'Warehouse', role: 'group' },
              latest,
              { ...latest, alias: 'first', fn: 'MIN' },
            ],
            rows: [{ wh: 'CN', latest: INSTANT, first: INSTANT - 86_400_000 }],
            totals: { latest: INSTANT },
            truncated: false,
          }}
        />
      </ViewSurface>,
    );

    const table = screen.getByRole('table');
    expect(
      within(table).getByRole('columnheader', { name: 'Latest of Created' }),
    ).toBeDefined();
    expect(
      within(table).getByRole('columnheader', { name: 'Earliest of Created' }),
    ).toBeDefined();
    // Once in the row and once in the totals, never as epoch milliseconds.
    expect(within(table).getAllByText(inZone(INSTANT))).toHaveLength(2);
    expect(within(table).getByText(inZone(INSTANT - 86_400_000))).toBeDefined();
    expect(table.textContent).not.toContain(String(INSTANT));
    expect(table.textContent).not.toContain('1,789,723,315,014');
  });

  it('is worded 「最晚」 in the catalogue a zh-CN surface reads', () => {
    render(
      <ViewSurface messages={zhCN}>
        <AnalysisTable
          view={{
            columns: [latest],
            rows: [{ latest: INSTANT }],
            truncated: false,
          }}
        />
      </ViewSurface>,
    );
    expect(
      screen.getByRole('columnheader', { name: 'Created的最晚' }),
    ).toBeDefined();
  });

  it('is a card’s headline, written out as the column reads it', () => {
    const { container } = render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <AnalysisChart
          data={{ type: 'metric', value: INSTANT }}
          spec={{ type: 'metric', metric: { metric: 'latest' } }}
          columns={[latest]}
        />
      </ViewSurface>,
    );
    const card = container.querySelector('[data-slot="metric-card"]');
    expect(card?.textContent).toContain(inZone(INSTANT));
  });

  it('writes out a moment a source answered as text, rather than a dash', () => {
    const day = { ...latest, kind: 'string', cell: 'date' };
    const data = shapeChart(
      analysisConfig({
        groups: [],
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'latest',
            function: 'MAX',
            expression: { type: 'FIELD', field: 'deliveredOn' },
          },
        ],
        layout: 'chart',
        chart: { type: 'metric', metric: { metric: 'latest' } },
      }),
      [{ latest: '2026-09-18' }],
    );
    expect(data).toEqual({ type: 'metric', value: '2026-09-18' });
    const { container } = render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <AnalysisChart
          data={data!}
          spec={{ type: 'metric', metric: { metric: 'latest' } }}
          columns={[day]}
        />
      </ViewSurface>,
    );
    // A wall-clock day reads as written, whatever the zone: the 18th.
    expect(
      container.querySelector('[data-slot="metric-card"]')?.textContent,
    ).toContain('18 Sept 2026');
  });
});

/**
 * Orders whose creation time declares a sum beside its earliest and latest:
 * the tray offers neither the sum nor the average, and names the other two
 * as a date's.
 */
function dated(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'createdAt',
          groups: [],
          functions: [
            AggregationFunction.SUM,
            AggregationFunction.MIN,
            AggregationFunction.MAX,
          ],
        },
      ],
    },
  });
}

const latestMetric = {
  type: 'NUMERIC',
  alias: 'latest',
  function: 'MAX',
  expression: { type: 'FIELD', field: 'createdAt' },
} as const;

async function open(config: Partial<AnalysisViewConfig>) {
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'orders-1',
        definitionId: 'orders',
        title: 'Latest per warehouse',
        scope: 'personal',
        revision: '1',
        config: analysisConfig(config),
      },
    ],
  });
  const engine = new ViewEngine({
    definitions: [dated()],
    store,
    environment: testEnvironment().environment,
    resolveSource: () =>
      testSource({
        aggregate: () =>
          Promise.resolve([{ warehouse: 'CN', orders: 2, latest: INSTANT }]),
      }),
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  return { engine };
}

/** A datetime as a surface with no language of its own shows it on UTC. */
const utc = (value: number) =>
  new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'medium',
    timeZone: 'UTC',
  }).format(value);

describe('an analysis view over the latest of a date', () => {
  it('shows a chart of nothing to measure as its table, the latest as a date', async () => {
    await open({
      metrics: [latestMetric],
      layout: 'chart',
      chart: { type: 'bar', cartesian: { x: 'warehouse', series: [] } },
    });
    const table = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="analysis-table"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await waitFor(() => expect(table.textContent).toContain(utc(INSTANT)));
    expect(table.textContent).not.toContain(String(INSTANT));
  });

  it('draws the count and leaves the latest to the table', async () => {
    const { engine } = await open({
      metrics: [{ type: 'COUNT', alias: 'orders' }, latestMetric],
      layout: 'chart',
      chart: {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
    });
    const reading = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart-reading"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(reading.textContent).toContain('Record count');
    expect(reading.textContent).not.toContain('Latest of Created');
    expect(engine.openRuntimes()[0].getSnapshot().issues).toEqual([]);
  });

  it('greys every chart that measures, saying why, and keeps the table', async () => {
    const user = userEvent.setup();
    await open({
      metrics: [latestMetric],
      layout: 'chart',
      chart: { type: 'bar', cartesian: { x: 'warehouse', series: [] } },
    });
    await user.click(await screen.findByRole('button', { name: 'Visualize' }));
    const picker = document.querySelector<HTMLElement>(
      '[data-slot="chart-picker"]',
    )!;
    for (const type of ['bar', 'line', 'pie']) {
      const tile = picker.querySelector<HTMLElement>(
        `[data-chart-type="${type}"]`,
      )!;
      expect(tile.getAttribute('aria-disabled')).toBe('true');
      expect(tile.textContent).toContain(
        'Needs an amount; a time is not drawn',
      );
    }
    expect(
      picker
        .querySelector('[data-chart-type="table"]')
        ?.getAttribute('aria-disabled'),
    ).toBeNull();
  });

  it('keeps a card over the latest to the latest: no target, no format', async () => {
    const user = userEvent.setup();
    await open({
      groups: [],
      metrics: [latestMetric],
      layout: 'chart',
      chart: { type: 'metric', metric: { metric: 'latest' } },
    });
    const card = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="metric-card"]',
      );
      expect(found?.textContent).toContain(utc(INSTANT));
      return found!;
    });
    expect(card.textContent).not.toContain(String(INSTANT));
    await user.click(await screen.findByRole('button', { name: 'Visualize' }));
    await user.click(screen.getByRole('button', { name: 'metric options' }));
    const panel = document.querySelector<HTMLElement>(
      '[data-slot="chart-options"]',
    )!;
    expect(within(panel).queryByLabelText('Compared with')).toBeNull();
    await user.click(within(panel).getByRole('tab', { name: 'Display' }));
    expect(within(panel).queryByLabelText('Target value')).toBeNull();
  });

  it('offers a date’s earliest and latest in the tray, and never its sum', async () => {
    const user = userEvent.setup();
    await open({ metrics: [latestMetric] });
    await openTray();
    await user.click(await screen.findByLabelText('Summary for Created'));
    const options = (await screen.findAllByRole('option')).map(
      option => option.textContent,
    );
    expect(options).toEqual(['Earliest', 'Latest']);
  });
});
