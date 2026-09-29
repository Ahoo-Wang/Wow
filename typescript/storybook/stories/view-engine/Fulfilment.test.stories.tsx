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
import type { ComponentType } from 'react';
import type { StoryObj } from '@storybook/react-vite';
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  AfterSales as DisplayAfterSales,
  FulfilmentTab as DisplayFulfilment,
  Guangdong as DisplayGuangdong,
} from './Fulfilment.stories.js';
import { chartsDrawn, drawnMarks, pressMark } from './chartDom.js';
import { expectTableBleeds } from './panelEdges.js';
import {
  expectColumnsCue,
  expectCueIsNoStop,
  expectNoPanelsOverlap,
  expectRowsCue,
  expectSameHeights,
  expectShownWhole,
  GROWN_AT_MOST,
  panelHeights,
  panelParts,
  settledHeights,
} from './panelFit.js';
import {
  findReading,
  label,
  noPanelOut,
  panelOf,
  rowsOf,
} from './retail/twins.js';

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

/**
 * 履约: the overdue detail on the board read shows its eleven orders whole
 * (2026-09-26 review, P1-3: six showed and 发货仓 was cut), and says how
 * many columns are past its end.
 */
export const OverdueDetailShowsItsRows: Story = {
  ...DisplayFulfilment,
  name: '履约与售后 · 超时明细按行长高',
  play: async ({ canvasElement }) => {
    await noPanelOut(canvasElement);
    await expectShownWhole('超时明细：付款超过 48 小时仍未发货', 11);
    await expectColumnsCue('超时明细：付款超过 48 小时仍未发货');
    await expectNoPanelsOverlap(canvasElement);
  },
};

/**
 * 售后: the after-sales detail holds a page of twenty, more than a panel
 * grows to — it stops at eight rows of the grid and says how many rows are
 * under its bottom edge and how many columns past its end (实退金额 was
 * cut with no sign, 2026-09-26 review, P1-3).
 */
export const AfterSalesDetailSaysWhatIsPast: Story = {
  ...DisplayAfterSales,
  name: '履约与售后 · 售后单明细说出还有多少',
  play: async ({ canvasElement }) => {
    await noPanelOut(canvasElement);
    await expectRowsCue('售后单明细');
    await expectColumnsCue('售后单明细');
    await expectCueIsNoStop('售后单明细');
    await expectNoPanelsOverlap(canvasElement);
  },
};

/**
 * A desk: the test browser is a phone's width, and below `md` the board is
 * one column in which nothing is sized.
 */
const DESK = (Story: ComponentType) => (
  <div style={{ width: 1280 }}>
    <Story />
  </div>
);

/** 「编辑」, and the grips out: the board is being built. */
async function startBuilding(canvasElement: HTMLElement) {
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: zhCN['label.dashboard.edit'],
    }),
  );
  await waitFor(() =>
    expect(
      canvasElement.querySelector('[data-slot="panel-grip"]'),
    ).not.toBeNull(),
  );
}

/**
 * What the author sized is what a reader sees (D68, revising D52; the user
 * on 2026-09-28): the after-sales detail, saved five rows tall, is read
 * grown to its cap, and 「编辑」 starts from there — it and every other
 * panel as tall as they were read, nothing on the board jumping. Sized
 * three rows shorter by its corner and saved, it is read at exactly that
 * size, never grown back, with 「下面还有 N 行」 on its edge; and 「编辑」
 * again moves nothing. The board as stored carries no mark: every panel on
 * it reads as untouched.
 */
export const SizedByHandIsWhatIsRead: Story = {
  ...DisplayAfterSales,
  name: '履约与售后 · 手调过高度就照手调',
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const detail = '售后单明细';
    await noPanelOut(canvasElement);
    // Grown to its cap, over the five rows it is saved at.
    await expectRowsCue(detail);
    await expect(canvasElement.querySelector('[data-fixed-height]')).toBeNull();
    const read = await settledHeights(canvasElement);
    await expect(
      Math.abs(read.get('after-sales')! - GROWN_AT_MOST),
    ).toBeLessThanOrEqual(1);

    await startBuilding(canvasElement);
    await expectSameHeights(canvasElement, read);

    // Three rows shorter by the corner's keys: a size chosen by hand.
    const corner = within(canvasElement).getByRole('button', {
      name: label('label.panel.resize', { title: detail }),
    });
    corner.focus();
    await userEvent.keyboard('{ArrowUp}{ArrowUp}{ArrowUp}');
    await waitFor(() =>
      expect(panelHeights(canvasElement).get('after-sales')).toBeLessThan(
        GROWN_AT_MOST - 200,
      ),
    );
    const sized = await settledHeights(canvasElement);
    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.dashboard.save'],
      }),
    );
    const confirm = await screen.findByRole('alertdialog');
    await userEvent.click(
      within(confirm).getByRole('button', {
        name: zhCN['label.save.shared-confirm'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="panel-grip"]'),
      ).toBeNull(),
    );

    // Read at exactly that size, and the rows past its edge said.
    await expectSameHeights(canvasElement, sized);
    await expect(
      canvasElement.querySelector(
        '.react-grid-item[data-panel-id="after-sales"]',
      ),
    ).toHaveAttribute('data-fixed-height', 'true');
    await waitFor(() => {
      const { cue } = panelParts(detail);
      const rows = Number(cue?.dataset.rows);
      expect(rows).toBeGreaterThan(0);
      expect(cue?.textContent).toContain(`下面还有 ${rows} 行`);
    });

    await startBuilding(canvasElement);
    await expectSameHeights(canvasElement, sized);
  },
};
