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

import { describe, expect, it, vi } from 'vitest';
import {
  MAX_TIMER_DELAY_MS,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  builtinFieldKinds,
  periodRollover,
  type AnalysisGroup,
  type AnalysisViewConfig,
  type DashboardPanel,
  type RecordData,
} from '../src/index.js';
import { MomentTimer } from '../src/runtime/refreshTimer.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import { ROLLOVER_GRACE_MS } from '../src/runtime/runtimeStore.js';
import { dataViewRuntime } from '../src/runtime/recordRuntime.js';
import {
  analysisConfig,
  dailyOrdersDefinition,
  dashboardConfig,
  nextTask,
  NOW,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';

/**
 * A metric card's 「最后一期」 is a statement about a moment (2026-09-23
 * audit): asked on the 16th, the headline is the 15th and the 16th is under
 * way. At midnight the 16th is over; a page left open went on saying the
 * 15th. The kernel says how long the period under way still runs
 * (`periodRollover`), and the runtime asks the question again then — the
 * rows on hand counted only part of the day, so the card cannot just move
 * along them.
 */

const DAY: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'day',
  unit: 'DAY',
};

const day = (n: number) => Date.UTC(2026, 8, n);

/** Three days of orders, the last of them the day `NOW` falls in. */
const ROWS: RecordData[] = [
  { day: day(16), orders: 3 },
  { day: day(15), orders: 10 },
  { day: day(14), orders: 8 },
];

function card(
  trend: Partial<{ headline: 'last' | 'whole' }> = {},
): AnalysisViewConfig {
  return analysisConfig({
    groups: [DAY],
    layout: 'chart',
    chart: {
      type: 'metric',
      metric: { metric: 'orders', trend: { x: 'day', ...trend } },
    },
  });
}

/** What is left of the 16th at `NOW` (10:30 UTC). */
const LEFT = day(17) - NOW.getTime();

describe('periodRollover', () => {
  const context = { timeZone: 'UTC', now: NOW };

  it('says how long the period under way still runs', () => {
    expect(periodRollover(card(), ROWS, context)).toBe(LEFT);
  });

  it('says nothing when every period has ended, or the card reads the whole', () => {
    expect(
      periodRollover(card(), ROWS, {
        timeZone: 'UTC',
        now: new Date(day(17)),
      }),
    ).toBeUndefined();
    expect(
      periodRollover(card({ headline: 'whole' }), ROWS, context),
    ).toBeUndefined();
  });

  it('says nothing for a card without a trend, or a chart that is no card', () => {
    const plain = analysisConfig({
      groups: [],
      layout: 'chart',
      chart: { type: 'metric', metric: { metric: 'orders' } },
    });
    expect(periodRollover(plain, [{ orders: 3 }], context)).toBeUndefined();
    expect(periodRollover(analysisConfig(), ROWS, context)).toBeUndefined();
  });
});

/** A card opened in a data view, on a clock the test turns. */
function openCard(config: AnalysisViewConfig = card()) {
  const clock = testEnvironment();
  const aggregate = vi.fn(() => Promise.resolve(ROWS.map(row => ({ ...row }))));
  const runtime = dataViewRuntime({
    id: 'card',
    definition: dailyOrdersDefinition(),
    config,
    title: 'Orders a day',
    scope: 'personal',
    saved: null,
    kinds: builtinFieldKinds,
    limits: DEFAULT_RUNTIME_LIMITS,
    environment: clock.environment,
    source: testSource({ aggregate }),
    runner: new RequestRunner(),
  });
  return { runtime, clock, aggregate };
}

describe('a card asked again when its period ends', () => {
  it('asks the question again a moment after midnight', async () => {
    const { runtime, clock, aggregate } = openCard();
    runtime.apply();
    await nextTask();
    expect(aggregate).toHaveBeenCalledTimes(1);
    expect(runtime.rolloverAt()).toBe(day(17));

    clock.advance(LEFT);
    expect(aggregate).toHaveBeenCalledTimes(1);
    clock.advance(ROLLOVER_GRACE_MS);
    await nextTask();
    expect(aggregate).toHaveBeenCalledTimes(2);
    runtime.dispose();
  });

  it('waits while the page is hidden, and asks once it is shown', async () => {
    const { runtime, clock, aggregate } = openCard();
    runtime.apply();
    await nextTask();
    clock.setVisible(false);
    clock.advance(LEFT + ROLLOVER_GRACE_MS);
    await nextTask();
    expect(aggregate).toHaveBeenCalledTimes(1);

    clock.setVisible(true);
    clock.advance(0);
    await nextTask();
    expect(aggregate).toHaveBeenCalledTimes(2);
    runtime.dispose();
  });

  it('asks once for a moment, however often the answer fails to come', async () => {
    const { runtime, clock, aggregate } = openCard();
    runtime.apply();
    await nextTask();
    aggregate.mockRejectedValue(new Error('offline'));
    clock.advance(LEFT + ROLLOVER_GRACE_MS);
    await nextTask();
    expect(aggregate).toHaveBeenCalledTimes(2);
    expect(runtime.getSnapshot().query.status).toBe('error');
    // The old answer is still on screen, with its old moment: not asked
    // again and again.
    clock.advance(60_000);
    await nextTask();
    expect(aggregate).toHaveBeenCalledTimes(2);
    runtime.dispose();
  });

  it('arms nothing for a card read as the whole, or a chart of no period', async () => {
    for (const config of [card({ headline: 'whole' }), analysisConfig()]) {
      const { runtime, clock, aggregate } = openCard(config);
      runtime.apply();
      await nextTask();
      const asked = aggregate.mock.calls.length;
      expect(runtime.rolloverAt()).toBeNull();
      clock.advance(LEFT + ROLLOVER_GRACE_MS);
      await nextTask();
      expect(aggregate).toHaveBeenCalledTimes(asked);
      runtime.dispose();
    }
  });

  it('reads the card the draft draws: picked after the run, it is armed', async () => {
    const { runtime } = openCard({
      ...card(),
      chart: { type: 'bar', cartesian: { x: 'day', series: [] } },
    });
    runtime.apply();
    await nextTask();
    expect(runtime.rolloverAt()).toBeNull();
    runtime.edit({ chart: card().chart });
    expect(runtime.rolloverAt()).toBe(day(17));
    runtime.dispose();
  });
});

describe('a dashboard’s cards asked again when their period ends', () => {
  it('refreshes the board at the soonest card’s period end', async () => {
    const clock = testEnvironment();
    const aggregate = vi.fn(() =>
      Promise.resolve(ROWS.map(row => ({ ...row }))),
    );
    const source = testSource({ aggregate });
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'daily',
          definitionId: 'orders',
          title: 'Orders a day',
          scope: 'shared',
          revision: 'r1',
          config: card(),
        },
      ],
    });
    const engine = new ViewEngine({
      definitions: [dailyOrdersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => source,
      environment: clock.environment,
      limits: DEFAULT_RUNTIME_LIMITS,
    });
    const board = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig({
          panels: [
            {
              id: 'card',
              kind: 'view',
              instanceId: 'daily',
              bindings: [],
              layout: { x: 0, y: 0, w: 6, h: 2 },
            } as DashboardPanel,
          ],
        }),
      },
      { requestId: 'r' },
    );
    const runtime = await engine.open(board.id);
    await nextTask();
    if (!(runtime instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');
    const asked = aggregate.mock.calls.length;
    expect(asked).toBeGreaterThan(0);

    clock.advance(LEFT + ROLLOVER_GRACE_MS);
    await nextTask();
    expect(aggregate.mock.calls.length).toBeGreaterThan(asked);
    runtime.dispose();
  });
});

describe('MomentTimer', () => {
  it('waits for a moment past what a host timer counts, in steps', () => {
    const clock = testEnvironment();
    const fire = vi.fn();
    const timer = new MomentTimer(clock.environment, fire);
    timer.syncAt(NOW.getTime() + MAX_TIMER_DELAY_MS + 1000);
    clock.advance(MAX_TIMER_DELAY_MS);
    expect(fire).not.toHaveBeenCalled();
    clock.advance(1000);
    expect(fire).toHaveBeenCalledTimes(1);
  });

  it('keeps one timer for an unchanged moment, and stops for none', () => {
    const clock = testEnvironment();
    const fire = vi.fn();
    const timer = new MomentTimer(clock.environment, fire);
    timer.syncAt(NOW.getTime() + 5000);
    timer.syncAt(NOW.getTime() + 5000);
    expect(clock.timers).toBe(1);
    timer.syncAt(null);
    expect(clock.timers).toBe(0);
    clock.advance(10_000);
    expect(fire).not.toHaveBeenCalled();
  });
});
