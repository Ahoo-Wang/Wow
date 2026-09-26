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

import { describe, expect, it } from 'vitest';
import {
  CHART_TYPES,
  builtinFieldKinds,
  compileAnalysis,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type RecordData,
} from '@ahoo-wang/wow-view-engine';
import {
  RETAIL_BOARD_DEFINITIONS,
  retailBoards,
  retailInstances,
} from './boards.js';
import { RETAIL_NOW } from './generate.js';
import {
  SHOWCASE,
  SHOWCASE_MONTH_TARGET,
  SHOWCASE_SKU,
  SHOWCASE_TABS,
  showcaseConfig,
  showcasePanels,
} from './showcase.js';
import { RETAIL_ZONE, retailSource, type RetailSourceKey } from './source.js';

/** What a panel's analysis answers, run as the board runs it. */
async function answer(id: string): Promise<RecordData[]> {
  const panel = showcaseConfig().panels.find(candidate => candidate.id === id);
  if (panel?.kind !== 'view') throw new Error(`No panel ${id}.`);
  const { definitionId, config } = panel.owned
    ? panel.owned
    : retailInstances.find(view => view.id === panel.instanceId)!;
  const definition = RETAIL_BOARD_DEFINITIONS.find(
    candidate => candidate.id === definitionId,
  ) as DataViewDefinition;
  const query = compileAnalysis(
    definition,
    config as AnalysisViewConfig,
    builtinFieldKinds,
    { now: new Date(RETAIL_NOW), timeZone: RETAIL_ZONE },
  );
  return retailSource(definition.source as RetailSourceKey).aggregate(query);
}

const DAY = 86_400_000;

describe('图型全景 (retail/showcase.ts)', () => {
  it('draws every one of the engine’s chart types exactly once', () => {
    const types = showcasePanels().map(panel => panel.type);
    expect([...types].sort()).toEqual([...CHART_TYPES].sort());
  });

  it('is one of the retail boards, on its four tabs, every question a title', () => {
    expect(retailBoards.map(board => board.id)).toContain(SHOWCASE);
    const panels = showcasePanels();
    for (const tab of Object.keys(SHOWCASE_TABS))
      expect(panels.some(panel => panel.tab === tab)).toBe(true);
    // A question, not a label: each title asks or says what it reads.
    for (const panel of panels) expect(panel.title.length).toBeGreaterThan(8);
    // Every saved analysis it refers to is in the boards' store.
    for (const panel of panels)
      if (panel.instanceId)
        expect(retailInstances.map(view => view.id)).toContain(
          panel.instanceId,
        );
  });

  it('reads last month whole against its target, and misses it', async () => {
    const [row] = await answer('gauge');
    const gmv = Number(row?.gmv);
    // 2026-08: a month that ended, not the month to date.
    expect(gmv).toBeGreaterThan(SHOWCASE_MONTH_TARGET * 0.7);
    expect(gmv).toBeLessThan(SHOWCASE_MONTH_TARGET);
  });

  it('keeps to whole months and whole weeks', async () => {
    const months = await answer('combo');
    expect(months).toHaveLength(24);
    expect(Math.max(...months.map(row => Number(row.month)))).toBe(
      Date.parse('2026-08-01T00:00:00+08:00'),
    );
    const weeks = new Set((await answer('river')).map(row => Number(row.week)));
    expect(weeks.size).toBe(52);
    const days = await answer('calendar');
    expect(days).toHaveLength(365);
  });

  it('prices one best-selling product every week, dipping in the promotions', async () => {
    const weeks = await answer('candlestick');
    expect(SHOWCASE_SKU).toBe('SPU-009-1');
    expect(weeks).toHaveLength(52);
    for (const week of weeks) {
      const [open, high, low, close] = ['open', 'high', 'low', 'close'].map(
        alias => Number(week[alias]),
      );
      expect(low).toBeLessThanOrEqual(Math.min(open, close));
      expect(high).toBeGreaterThanOrEqual(Math.max(open, close));
    }
    // The promotions cut the price: the lowest week sits well under the
    // highest, not a ¥0 of an order that paid nothing.
    const lows = weeks.map(week => Number(week.low));
    expect(Math.min(...lows)).toBeGreaterThan(0);
    expect(Math.max(...lows) - Math.min(...lows)).toBeGreaterThan(2);
    expect(Number(weeks.at(-1)!.week)).toBeLessThan(RETAIL_NOW - 7 * DAY);
  });

  it('draws the radar on ratios alone, and the parallel axes on five named channels', async () => {
    for (const row of await answer('radar'))
      for (const alias of [
        'refundRate',
        'discountRate',
        'returningRate',
        'cancelRate',
      ]) {
        expect(Number(row[alias])).toBeGreaterThan(0);
        expect(Number(row[alias])).toBeLessThan(1);
      }
    expect(await answer('parallel')).toHaveLength(5);
  });
});
