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
  cleanup,
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
  projectAnalysis,
  type AnalysisViewConfig,
  type MetricCardSpec,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  AnalysisChart,
  DataWorkbench,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dailyOrdersDefinition,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/**
 * The trend card as it reads (user ruling 2026-09-23, audit P1-6): which
 * span the big number is, said over it; the change from the period before,
 * signed, as a share, pointing the way it went and coloured by whether that
 * is good; and the whole range as the other reading, picked in the options.
 */

const day = (n: number) => Date.UTC(2026, 8, n);

const ROWS: RecordData[] = [
  { day: day(22), orders: 12 },
  { day: day(21), orders: 10 },
  { day: day(20), orders: 8 },
];

function daily(spec: Partial<MetricCardSpec> = {}): AnalysisViewConfig {
  return analysisConfig({
    groups: [
      { alias: 'day', field: 'createdAt', type: 'DATE_HISTOGRAM', unit: 'DAY' },
    ],
    layout: 'chart',
    chart: {
      type: 'metric',
      metric: { metric: 'orders', trend: { x: 'day' }, ...spec },
    },
  });
}

/** The card as the workbench draws it: projected, then read in UTC. */
function card(
  config: AnalysisViewConfig,
  rows: RecordData[] = ROWS,
  context: { now?: Date } = {},
) {
  const view = projectAnalysis(
    dailyOrdersDefinition(),
    config,
    rows,
    [{ orders: 99 }],
    undefined,
    { timeZone: 'UTC', ...context },
  );
  return render(
    <ViewSurface locale="en-US" timeZone="UTC">
      <AnalysisChart
        data={view.chart!}
        spec={config.chart}
        columns={view.columns}
      />
    </ViewSurface>,
  );
}

const slot = (name: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${name}"]`);

describe('a trend card read as its last period', () => {
  it('names the period, then the number, then the change against the one before', () => {
    card(daily());
    expect(slot('metric-period')?.textContent).toBe('Sep 22, 2026');
    expect(slot('metric-value')?.textContent).toBe('12');
    const change = slot('metric-change')!;
    expect(change.getAttribute('data-direction')).toBe('up');
    expect(change.textContent).toBe('+2 · +20%vs previous period');
    // A rise is good unless the card says otherwise.
    expect(
      change.querySelector('[data-slot="badge"]')?.getAttribute('data-tone'),
    ).toBe('success');
  });

  it('colours a fall as good where a fall is the good way', () => {
    card(daily({ trend: { x: 'day', lowerIsBetter: true } }), [
      { day: day(22), orders: 6 },
      { day: day(21), orders: 10 },
    ]);
    const change = slot('metric-change')!;
    expect(change.getAttribute('data-direction')).toBe('down');
    expect(change.textContent).toContain('-4 · -40%');
    expect(
      change.querySelector('[data-slot="badge"]')?.getAttribute('data-tone'),
    ).toBe('success');
  });

  /**
   * The change convention (themes.md 2.6, 5.3) picks the badge's colour in
   * the stylesheet — by tone, or by direction — and red and green are one
   * colour to a red–green colour-blind eye, so under every convention the
   * direction is also said without colour: the arrow and the sign. The
   * badge says both halves on itself for the stylesheet to read.
   */
  it.each(['semantic', 'green-up', 'red-up'])(
    'says the direction without colour under %s',
    convention => {
      document.documentElement.setAttribute(
        'data-fve-change-colors',
        convention,
      );
      try {
        for (const [rows, direction, sign, icon] of [
          [ROWS, 'up', '+2', 'lucide-trending-up'],
          [
            [
              { day: day(22), orders: 6 },
              { day: day(21), orders: 10 },
            ],
            'down',
            '-4',
            'lucide-trending-down',
          ],
        ] as const) {
          const { unmount } = card(daily(), [...rows]);
          const badge = slot('metric-change')!.querySelector(
            '[data-slot="badge"]',
          )!;
          expect(badge.getAttribute('data-change')).toBe(direction);
          expect(badge.textContent).toMatch(new RegExp(`^\\${sign}`));
          expect(badge.querySelector(`svg.${icon}`)).not.toBeNull();
          unmount();
        }
      } finally {
        document.documentElement.removeAttribute('data-fve-change-colors');
      }
    },
  );

  it('says which period it left out because it was under way', () => {
    card(daily(), ROWS, { now: new Date(Date.UTC(2026, 8, 22, 9)) });
    expect(slot('metric-period')?.textContent).toBe('Sep 21, 2026');
    expect(slot('metric-value')?.textContent).toBe('10');
    expect(slot('metric-skipped')?.textContent).toBe(
      'Sep 22, 2026 is not over yet and is not counted',
    );
  });

  it('reads the only period there is as the period so far, with no change', () => {
    card(daily(), [{ day: day(22), orders: 3 }], {
      now: new Date(Date.UTC(2026, 8, 22, 9)),
    });
    expect(slot('metric-period')?.textContent).toBe('Sep 22, 2026 so far');
    expect(slot('metric-change')).toBeNull();
  });

  it('says when there is nothing before it to compare with', () => {
    card(daily(), [{ day: day(22), orders: 3 }]);
    expect(slot('metric-change')?.textContent).toBe(
      'No previous period to compare with',
    );
    cleanup();
    card(daily(), [
      { day: day(22), orders: 3 },
      { day: day(21), orders: null },
    ]);
    expect(slot('metric-change')?.textContent).toBe(
      'No number in the previous period to compare with',
    );
  });

  it('names a week by the day it starts', () => {
    const weekly = analysisConfig({
      ...daily(),
      groups: [
        {
          alias: 'day',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'WEEK',
        },
      ],
    });
    card(weekly, [
      { day: day(14), orders: 3 },
      { day: day(21), orders: 4 },
    ]);
    expect(slot('metric-period')?.textContent).toMatch(/^Week of Sep 21/);
  });

  it('says the whole is the whole', () => {
    card(daily({ trend: { x: 'day', headline: 'whole' } }));
    expect(slot('metric-period')?.textContent).toBe('All in range');
    expect(slot('metric-value')?.textContent).toBe('99');
    expect(slot('metric-change')).toBeNull();
  });

  it('keeps a card without a trend as it was', () => {
    render(
      <ViewSurface locale="en-US">
        <AnalysisChart
          data={{ type: 'metric', value: 5 }}
          spec={{ type: 'metric', metric: { metric: 'orders' } }}
        />
      </ViewSurface>,
    );
    expect(slot('metric-period')).toBeNull();
    expect(slot('metric-change')).toBeNull();
  });
});

describe('the trend card’s options', () => {
  async function open(config: AnalysisViewConfig) {
    const source: ViewSource = testSource({
      aggregate: vi.fn((query: { groupBy?: unknown[] }) =>
        Promise.resolve(
          (query.groupBy?.length ?? 0) > 0
            ? ROWS.map(row => ({ ...row }))
            : [{ orders: 30 }],
        ),
      ) as ViewSource['aggregate'],
    });
    const instance: ViewInstance = {
      id: 'orders-daily',
      definitionId: 'orders',
      title: 'Daily',
      scope: 'personal',
      revision: '1',
      config,
    };
    const engine = new ViewEngine({
      definitions: [dailyOrdersDefinition()],
      store: new MemoryViewStore({ instances: [instance] }),
      resolveSource: () => source,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-daily"
        kinds={['analysis']}
        messages={zhCN}
      />,
    );
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole('button', {
        name: zhCN['label.analysis.visualize'],
      }),
    );
    await user.click(
      screen.getByRole('button', {
        name: `${zhCN['label.chart.type.metric']}选项`,
      }),
    );
    return {
      user,
      queries: () =>
        vi
          .mocked(source.aggregate)
          .mock.calls.filter(([query]) => query.groupBy === undefined).length,
      draft: () =>
        engine.openRuntimes()[0]!.getSnapshot().draft as AnalysisViewConfig,
    };
  }

  it('switches the big number to the whole, asks for it once, and back', async () => {
    const { user, queries, draft } = await open(daily());
    const options = document.querySelector<HTMLElement>(
      '[data-slot="chart-options"]',
    )!;
    const headline = within(options).getByRole('group', {
      name: zhCN['label.chart.headline'],
    });
    // The reading chosen says what the comparison and the target are over.
    expect(options.textContent).toContain(
      zhCN['label.chart.headline.last.hint'],
    );
    expect(queries()).toBe(0);

    await user.click(
      within(headline).getByRole('button', {
        name: zhCN['label.chart.headline.whole'],
      }),
    );
    expect(draft().chart.metric?.trend).toEqual({
      x: 'day',
      headline: 'whole',
    });
    await waitFor(() => expect(queries()).toBe(1));
    await waitFor(() =>
      expect(slot('metric-period')?.textContent).toBe(
        zhCN['label.chart.period.whole'],
      ),
    );
    expect(slot('metric-value')?.textContent).toBe('30');
    expect(options.textContent).toContain(
      zhCN['label.chart.headline.whole.hint'],
    );

    await user.click(
      within(headline).getByRole('button', {
        name: zhCN['label.chart.headline.last'],
      }),
    );
    expect(draft().chart.metric?.trend).toEqual({ x: 'day' });
  });

  it('offers which way is good only where a change is drawn', async () => {
    const { user, draft } = await open(daily());
    const options = document.querySelector<HTMLElement>(
      '[data-slot="chart-options"]',
    )!;
    await user.click(
      within(options).getByRole('tab', {
        name: zhCN['label.chart.tab.display'],
      }),
    );
    const lower = await within(options).findByRole('checkbox', {
      name: zhCN['label.chart.lower-is-better'],
    });
    await user.click(lower);
    expect(draft().chart.metric?.trend).toEqual({
      x: 'day',
      lowerIsBetter: true,
    });
    await user.click(lower);
    expect(draft().chart.metric?.trend).toEqual({ x: 'day' });
  });

  it('asks nothing of a card read as the whole for which way is good', async () => {
    const { user } = await open(
      daily({ trend: { x: 'day', headline: 'whole' } }),
    );
    const options = document.querySelector<HTMLElement>(
      '[data-slot="chart-options"]',
    )!;
    await user.click(
      within(options).getByRole('tab', {
        name: zhCN['label.chart.tab.display'],
      }),
    );
    expect(
      within(options).queryByRole('checkbox', {
        name: zhCN['label.chart.lower-is-better'],
      }),
    ).toBeNull();
  });
});
