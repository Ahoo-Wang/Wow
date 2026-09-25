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
import { expect, waitFor, within } from 'storybook/test';
import displayMeta, {
  AfterSales as DisplayAfterSales,
  FulfilmentTab as DisplayFulfilment,
  Guangdong as DisplayGuangdong,
} from './Fulfilment.stories.js';
import { chartsDrawn, drawnMarks, pressMark } from './chartDom.js';
import { expectTableBleeds } from './panelEdges.js';
import { findReading, noPanelOut, panelOf, rowsOf } from './retail/twins.js';

function percentOf(text: string | undefined): number {
  return Number((text ?? '').replace('%', ''));
}

/**
 * 履约与售后, as a lightweight twin (docs/scenarios.md 6.1): it draws without a
 * panel refusing its view, and shows what 4.1 says it shows.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/履约与售后/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * 履约: the weekly breach rate crosses its 5% line in the Spring Festival
 * shutdown (A6) — the tallest point of the line is in February 2026.
 */
export const FulfilmentShowsTheShutdown: Story = {
  ...DisplayFulfilment,
  name: '履约与售后 · 履约（A6）',
  play: async ({ canvasElement }) => {
    await waitFor(
      () =>
        expect(
          canvasElement.querySelectorAll('[data-slot="dashboard-panel"]')
            .length,
        ).toBe(4),
      { timeout: 10_000 },
    );
    await noPanelOut(canvasElement);
    await chartsDrawn(canvasElement);
    const reading = await findReading(panelOf('每周发货超时率'));
    const rows = rowsOf(reading);
    const top = rows.reduce((a, b) =>
      percentOf(b[1]) > percentOf(a[1]) ? b : a,
    );
    await expect(top[0]).toMatch(/2026年2月/);
    await expect(percentOf(top[1])).toBeGreaterThan(5);
  },
};

/**
 * 履约 narrowed to 广东省: 中通's slowest week this year is the typhoon's,
 * late July (A2).
 */
export const FulfilmentShowsTheTyphoon: Story = {
  ...DisplayGuangdong,
  name: '履约与售后 · 广东省（A2）',
  play: async ({ canvasElement }) => {
    await noPanelOut(canvasElement);
    await chartsDrawn(canvasElement);
    const reading = await findReading(
      panelOf('承运商 × 周：平均签收时长（小时，今年）'),
    );
    // One row per carrier, one column per week: 中通's slowest week.
    const weeks = [...reading.tHead!.rows[0].cells].map(
      cell => cell.textContent?.trim() ?? '',
    );
    const zto = rowsOf(reading).find(row => row[0] === '中通快递')!;
    // A week without a parcel reads as no number, and is no candidate.
    const hours = zto
      .slice(1)
      .map(cell => Number(cell.replace(/[^\d.]/g, '')) || 0);
    const slowest = hours.indexOf(Math.max(...hours)) + 1;
    await expect(weeks[slowest]).toMatch(/2026年7月/);
    await expect(hours[slowest - 1]).toBeGreaterThan(100);
  },
};

/**
 * 售后: a press on a reason opens the after-sales list in the host's
 * workbench, with that reason as its condition.
 */
export const AfterSalesReasonOpensTheList: Story = {
  ...DisplayAfterSales,
  name: '履约与售后 · 售后理由去明细',
  play: async ({ canvasElement }) => {
    await noPanelOut(canvasElement);
    await chartsDrawn(panelOf('售后理由构成'));
    pressMark(drawnMarks(panelOf('售后理由构成'))[0]);
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-host-route="workbench"]'),
      ).not.toBeNull(),
    );
    await expect(
      await within(canvasElement).findByText(/售后理由 是/),
    ).toBeInTheDocument();
  },
};

/**
 * 售后: an analysis drawn as a table runs to the panel's edges, as a record
 * table does (docs/design/ui/dashboard.md).
 */
export const AnalysisTableRunsToThePanelEdges: Story = {
  ...DisplayAfterSales,
  name: '履约与售后 · 分析表格贴到面板两边',
  play: async ({ canvasElement }) => {
    await noPanelOut(canvasElement);
    await expectTableBleeds('退款率最高的商品（近 3 个月）', 'analysis-table');
  },
};
