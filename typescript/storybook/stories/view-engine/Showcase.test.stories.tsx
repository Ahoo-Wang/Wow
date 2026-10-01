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

import type { StoryObj } from '@storybook/react-vite';
import { expect, screen, waitFor } from 'storybook/test';
import type { ChartType } from '@ahoo-wang/wow-view-engine';
import {
  registerChartMap,
  type ChartMapGeoJson,
} from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  Mix as DisplayMix,
  Region as DisplayRegion,
  Spread as DisplaySpread,
  Trend as DisplayTrend,
} from './Showcase.stories.js';
import { redUp } from './changeColors.js';
import {
  chartsDrawn,
  drawnMarks,
  overlaps,
  pressMark,
  typeBox,
} from './chartDom.js';
import { expectMetricCardsFit } from './metricFit.js';
import { SHOWCASE_MAP, showcasePanels, type Tab } from './retail/showcase.js';
import { noPanelOut, panelOf } from './retail/twins.js';

/*
 * 图型全景, as a lightweight twin: on each tab every panel draws the chart
 * type its analysis asks for and says what it reads; the 22 types are there
 * once each; pressing a province filters the board; nothing runs sideways
 * at 1440 or 390. The map is a small one of its own — six squares named for
 * provinces — so the twin never goes to the network.
 */

const FIXTURE_PROVINCES = [
  '广东省',
  '浙江省',
  '江苏省',
  '山东省',
  '上海市',
  '北京市',
] as const;

const FIXTURE_MAP: ChartMapGeoJson = {
  type: 'FeatureCollection',
  features: FIXTURE_PROVINCES.map((name, at) => ({
    type: 'Feature',
    properties: { name },
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [100 + at * 3, 30],
          [102 + at * 3, 30],
          [102 + at * 3, 32],
          [100 + at * 3, 32],
          [100 + at * 3, 30],
        ],
      ],
    },
  })),
};

const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/图型全景/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
  beforeEach: () => {
    const takeBack = redUp();
    const unregister = registerChartMap({
      name: SHOWCASE_MAP,
      label: '中国（合成的回归夹具）',
      load: async () => FIXTURE_MAP,
    });
    return () => {
      unregister();
      takeBack();
    };
  },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The `data-chart` a chart's frame carries, by the type its spec names. */
const FRAME_OF: Partial<Record<ChartType, readonly string[]>> = {
  // The payment methods are a donut (a pie with its total in the middle).
  pie: ['pie', 'donut'],
};

const viewport = (width: number, height: number) => ({
  viewport: {
    options: {
      screen: {
        name: `${width}×${height}`,
        styles: { width: `${width}px`, height: `${height}px` },
      },
    },
  },
});

/**
 * Every panel of the tab drawn: its chart the type its analysis asks for,
 * landed (`data-drawn`), with a sentence that reads a number; a metric card
 * shows its figure. Each panel is found again right before it is read — a
 * chart can redraw after a late layout.
 */
async function expectTabDrawn(canvasElement: HTMLElement, tab: Tab) {
  await noPanelOut(canvasElement);
  await chartsDrawn(canvasElement);
  for (const panel of showcasePanels().filter(one => one.tab === tab)) {
    if (panel.type === 'metric') {
      await waitFor(() =>
        expect(
          panelOf(panel.title).querySelector('[data-slot="metric-value"]')
            ?.textContent,
        ).toMatch(/\d/),
      );
      continue;
    }
    const names = FRAME_OF[panel.type] ?? [panel.type];
    await waitFor(
      () => {
        const frames = [
          ...panelOf(panel.title).querySelectorAll<HTMLElement>(
            '[data-slot="chart"]',
          ),
        ].filter(frame => names.includes(frame.dataset.chart ?? ''));
        expect(frames, `${panel.title}: one ${panel.type}`).toHaveLength(1);
        expect(frames[0]).toHaveAttribute('data-drawn');
        expect(
          frames[0]!.querySelector('[data-slot="chart-sentence"]')?.textContent,
          `${panel.title}: a sentence with a reading`,
        ).toMatch(/\d/);
      },
      { timeout: 10_000 },
    );
  }
  // Nothing runs sideways, the page nor any panel.
  await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
    window.innerWidth,
  );
}

/** 走势 at 1440: the metric card, the gauge and six time charts. */
export const TrendDrawn: Story = {
  ...DisplayTrend,
  name: '走势：每张图都画出、都有读数',
  parameters: { ...DisplayTrend.parameters, ...viewport(1440, 900) },
  globals: { viewport: { value: 'screen' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(1440);
    await expectTabDrawn(canvasElement, 'trend');
    // The gauge's words stand apart: 「达成目标的 87.7%」 once ran into the
    // scale's end 「¥20万」 at a third of the board (second review R2-77).
    const gauge = showcasePanels().find(panel => panel.type === 'gauge')!;
    const words = [...panelOf(gauge.title).querySelectorAll('svg text')]
      .filter(text => (text.textContent ?? '').trim() !== '')
      .map(text => ({
        text: text.textContent,
        box: typeBox(text as SVGTextElement),
      }));
    await expect(words.length).toBeGreaterThanOrEqual(4);
    for (const [index, one] of words.entries())
      for (const other of words.slice(index + 1))
        await expect(
          overlaps(one.box, other.box),
          `${one.text} / ${other.text}`,
        ).toBe(false);
    // The candlestick reads red up: the board's page says so.
    await expect(document.documentElement).toHaveAttribute(
      'data-fve-change-colors',
      'red-up',
    );
  },
};

/** 构成 at 1440. */
export const MixDrawn: Story = {
  ...DisplayMix,
  name: '构成：每张图都画出、都有读数',
  parameters: { ...DisplayMix.parameters, ...viewport(1440, 900) },
  globals: { viewport: { value: 'screen' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(1440);
    await expectTabDrawn(canvasElement, 'mix');
  },
};

/** 分布与关系 at 1440. */
export const SpreadDrawn: Story = {
  ...DisplaySpread,
  name: '分布与关系：每张图都画出、都有读数',
  parameters: { ...DisplaySpread.parameters, ...viewport(1440, 900) },
  globals: { viewport: { value: 'screen' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(1440);
    await expectTabDrawn(canvasElement, 'spread');
  },
};

/** 地域与转化 at 1440, on the twin's own map. */
export const RegionDrawn: Story = {
  ...DisplayRegion,
  name: '地域与转化：每张图都画出、都有读数',
  parameters: { ...DisplayRegion.parameters, ...viewport(1440, 900) },
  globals: { viewport: { value: 'screen' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(1440);
    await expectTabDrawn(canvasElement, 'region');
  },
};

/**
 * Pressing the top province on 「哪些省份买得最多」 filters the whole board
 * to it: the filter bar says the province, and the panels beside it answer
 * again — the heatmap reads fewer orders than before.
 */
export const ProvinceCrossFilters: Story = {
  ...DisplaySpread,
  name: '点省份交叉筛选整板',
  play: async ({ canvasElement }) => {
    await expectTabDrawn(canvasElement, 'spread');
    const [bar, heat] = ['bar', 'heatmap'].map(
      id => showcasePanels().find(panel => panel.id === id)!.title,
    );
    const sentence = () =>
      panelOf(heat!).querySelector('[data-slot="chart-sentence"]')?.textContent;
    const before = sentence();
    // Found right before it is pressed: the chart can redraw meanwhile. The
    // bars run top to bottom, largest first.
    const [top] = drawnMarks(panelOf(bar!));
    await expect(top).toBeDefined();
    pressMark(top!);
    const filters = screen.getByRole('group', { name: '省份' });
    await waitFor(() => expect(filters).toHaveTextContent('江苏省'));
    await waitFor(() => expect(sentence()).not.toBe(before), {
      timeout: 10_000,
    });
    await chartsDrawn(canvasElement);
  },
};

/** 走势 on a 390 phone: one column, nothing sideways, every card fits. */
export const TrendOnAPhone: Story = {
  ...DisplayTrend,
  name: '走势 · 手机',
  parameters: { ...DisplayTrend.parameters, ...viewport(390, 844) },
  globals: { viewport: { value: 'screen' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(390);
    await expectTabDrawn(canvasElement, 'trend');
    await expectMetricCardsFit(canvasElement);
  },
};

/** 构成 on a 390 phone. */
export const MixOnAPhone: Story = {
  ...DisplayMix,
  name: '构成 · 手机',
  parameters: { ...DisplayMix.parameters, ...viewport(390, 844) },
  globals: { viewport: { value: 'screen' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(390);
    await expectTabDrawn(canvasElement, 'mix');
  },
};
