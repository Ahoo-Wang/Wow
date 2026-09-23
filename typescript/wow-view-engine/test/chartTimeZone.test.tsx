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

import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  projectAnalysis,
  shapeChart,
  type AnalysisViewConfig,
  type CartesianData,
  type MetricCardData,
  type ViewInstance,
  type ViewRuntime,
} from '../src/index.js';
import {
  useAnalysisEditor,
  useAnalysisResult,
  useOpenView,
  useViewRuntime,
} from '../src/react/index.js';
import { shortDateTicks } from '../src/ui/charts/dateTicks.js';
import {
  analysisConfig,
  dailyOrdersDefinition,
  testSource,
} from './fixtures.js';

/**
 * The chart is shaped in the engine's zone, not the browser's
 * (`RuntimeEnvironment.timeZone`): a day histogram was cut at the engine
 * zone's midnights, so its missing days are stepped there, and a day that
 * crosses a clock change is 23 or 25 hours long — stepped in another zone,
 * the next midnight lands off the keys and nothing is filled.
 *
 * The engine's zone is one whose clock changes on a day the process's does
 * not, whatever zone this suite runs in.
 */
const HOST = Intl.DateTimeFormat().resolvedOptions().timeZone;
const ZONES = [
  // New York leaves summer time on 1 November 2026: midnight moves from
  // 04:00 to 05:00 UTC.
  {
    zone: 'America/New_York',
    days: ['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02'],
    offsets: [4, 4, 4, 5],
  },
  // London leaves it on 25 October: from 23:00 the day before to 00:00.
  {
    zone: 'Europe/London',
    days: ['2026-10-23', '2026-10-24', '2026-10-25', '2026-10-26'],
    offsets: [-1, -1, -1, 0],
  },
] as const;

/** How far `zone`'s clock is ahead of UTC at `ms`, in hours. */
function offsetOf(zone: string, ms: number): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
  }).formatToParts(new Date(ms));
  const part = (type: string) =>
    Number(parts.find(entry => entry.type === type)?.value);
  const wall = Date.UTC(
    part('year'),
    part('month') - 1,
    part('day'),
    part('hour'),
  );
  return Math.round((wall - ms) / 3_600_000);
}

/** The candidate whose clock change the process's clock does not share. */
const ENGINE = ZONES.find(({ days, offsets }) => {
  const at = (index: number) => {
    const [year, month, date] = days[index]!.split('-').map(Number);
    return Date.UTC(year!, month! - 1, date!, offsets[index]);
  };
  const change = offsets[3] - offsets[2];
  return -(offsetOf(HOST, at(3)) - offsetOf(HOST, at(2))) !== change;
})!;

/** The engine zone's midnight of each day, as the source keys it. */
const midnights = ENGINE.days.map((day, index) => {
  const [year, month, date] = day.split('-').map(Number);
  return Date.UTC(year!, month! - 1, date!, ENGINE.offsets[index]);
});
/** The third day had no orders: its row is missing. */
const ROWS = [
  { day: midnights[0], orders: 3 },
  { day: midnights[1], orders: 5 },
  { day: midnights[3], orders: 4 },
];

const daily = (chart: AnalysisViewConfig['chart']): AnalysisViewConfig =>
  analysisConfig({
    groups: [
      { alias: 'day', field: 'createdAt', type: 'DATE_HISTOGRAM', unit: 'DAY' },
    ],
    layout: 'chart',
    chart,
  });
const LINE = daily({
  type: 'line',
  cartesian: { x: 'day', series: [{ metric: 'orders' }] },
});
const CARD = daily({
  type: 'metric',
  metric: { metric: 'orders', trend: { x: 'day' } },
});

describe('the chart in the engine’s zone', () => {
  it('fills a missing day only when stepped in the zone the days were cut in', () => {
    const inEngine = shapeChart(LINE, ROWS, undefined, {
      timeZone: ENGINE.zone,
    }) as CartesianData;
    expect(inEngine.points.map(point => point.x)).toEqual(midnights);
    // The quiet day is a day of nothing, not a gap.
    expect(inEngine.points.map(point => point.values.orders)).toEqual([
      3, 5, 0, 4,
    ]);

    // Stepped in the process's zone the last midnight is off the run, so
    // nothing is filled rather than something filled wrong.
    const inHost = shapeChart(LINE, ROWS, undefined, {
      timeZone: HOST,
    }) as CartesianData;
    expect(inHost.points).toHaveLength(3);
  });

  it('writes the ticks of the filled axis a day each in the engine’s zone', () => {
    const ticks = shortDateTicks(midnights, 'DAY', {
      locale: 'en-US',
      timeZone: ENGINE.zone,
    });
    const dates = ENGINE.days.map(day => String(Number(day.slice(8))));
    expect(ticks?.map(tick => tick?.match(/\d+(?=,|$)/)?.[0])).toEqual(dates);
  });

  it('is what projectAnalysis shapes under the context it is given', () => {
    const view = projectAnalysis(
      dailyOrdersDefinition(),
      LINE,
      ROWS,
      undefined,
      undefined,
      { timeZone: ENGINE.zone },
    );
    expect((view.chart as CartesianData).points).toHaveLength(4);
  });

  it('is what the runtime and the workbench’s result shape', async () => {
    const view: ViewInstance = {
      id: 'orders-daily',
      definitionId: 'orders',
      title: 'Daily',
      scope: 'personal',
      revision: '1',
      config: CARD,
    };
    // Asked at noon, engine time, on the last day: that day is under way.
    const asked = new Date(midnights[3]! + 12 * 3_600_000);
    const engine = new ViewEngine({
      definitions: [dailyOrdersDefinition()],
      store: new MemoryViewStore({ instances: [view] }),
      resolveSource: () =>
        testSource({ aggregate: vi.fn(() => Promise.resolve([...ROWS])) }),
      environment: defaultRuntimeEnvironment({
        timeZone: ENGINE.zone,
        now: () => asked,
      }),
    });
    const { result } = renderHook(() => {
      const open = useOpenView(engine, 'orders-daily');
      const runtime = open.runtime as ViewRuntime<AnalysisViewConfig> | null;
      const state = useViewRuntime(runtime);
      const analysis = useAnalysisEditor(runtime);
      return {
        state,
        result: useAnalysisResult(runtime, analysis, {
          state,
          canDrill: true,
          drill: vi.fn(),
          follow: vi.fn(),
        }),
      };
    });
    await waitFor(() => expect(result.current.result.view).not.toBeNull());

    // The runtime's projection and the hook's redraw agree: four days, the
    // quiet one filled, and the last day left out of the headline because
    // it was under way when asked — the headline is the quiet day, 0.
    const projected = result.current.result.view?.chart as MetricCardData;
    const redrawn = result.current.result.chartData as MetricCardData;
    for (const card of [projected, redrawn]) {
      expect(card.trend?.map(point => point.value)).toEqual([3, 5, 0, 4]);
      expect(card.value).toBe(0);
      expect(card.period).toMatchObject({
        at: midnights[2],
        skipped: midnights[3],
        change: { delta: -5, ratio: -1 },
      });
    }
  });
});
