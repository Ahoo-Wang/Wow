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
import { zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  AllPanels as DisplayAllPanels,
  Building as DisplayBuilding,
  CrossFilter as DisplayCrossFilter,
  EmptyDashboard as DisplayEmptyDashboard,
  Filters as DisplayFilters,
  GlobalFilter as DisplayGlobalFilter,
  LegacyLayout as DisplayLegacyLayout,
  PanelUnavailable as DisplayPanelUnavailable,
  PersonalViewOnSharedBoard as DisplayPersonalViewOnSharedBoard,
  PreBatchCCondition as DisplayPreBatchCCondition,
  QueryFailed as DisplayQueryFailed,
} from './Dashboard.stories.js';
import { amountOf, findDataTable, readColumn, readTotal } from './readTable.js';
import { axisTicks, chartsDrawn, drawnMarks, overlaps } from './chartDom.js';
import {
  measureBorderContrast,
  measureOutlineContrast,
  measureRingContrast,
} from './contrast.js';
import { legacyDashboardConfig, outage } from './fixtures.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/仪表盘视图/Dashboard/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const bars = (canvas: HTMLElement) => drawnMarks(canvas);

/**
 * A desk-width column for the stories that place panels. The test browser
 * is a phone's width, and below `md` the board is one derived column that
 * nothing is placed in.
 */
const DESK = (Story: ComponentType) => (
  <div style={{ width: 1280 }}>
    <Story />
  </div>
);

/** Until the board is a grid rather than the one-column reading. */
async function onTheGrid(canvasElement: HTMLElement): Promise<void> {
  await waitFor(() =>
    expect(
      canvasElement.querySelector('[data-slot="dashboard-grid"]'),
    ).not.toHaveAttribute('data-narrow'),
  );
}

/** 「编辑」: nothing on a board moves until it is being built (D22 A). */
async function startBuilding(canvasElement: HTMLElement): Promise<void> {
  await userEvent.click(
    await within(canvasElement).findByRole('button', {
      name: zhCN['label.dashboard.edit'],
    }),
  );
}

export const AllPanels: Story = {
  ...DisplayAllPanels,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    // The record panel runs its own view: pending orders, largest first.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([
        'SO-1003',
        'SO-1005',
        'SO-1001',
        'SO-1006',
      ]),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await expect(
      canvas.getByRole('link', { name: /^出库异常处理/ }),
    ).toBeVisible();
  },
};

export const GlobalFilter: Story = {
  ...DisplayGlobalFilter,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    // 华南 reaches both panels through their own warehouse field.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1005']),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(1760);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
  },
};

/**
 * A panel whose view was deleted says why once, in the reader's words, and
 * who can bring it back — never 「不可用」 twice, never the id it points at,
 * and no button offering what nothing on the board can do yet (U5).
 */
export const PanelUnavailable: Story = {
  ...DisplayPanelUnavailable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.panel.out.missing']),
    ).toBeVisible();
    const out = canvasElement.querySelector(
      '[data-slot="panel-unavailable"]',
    ) as HTMLElement;
    await expect(out).toHaveTextContent(zhCN['label.panel.way-out.share']);
    await expect(out.textContent).not.toContain('不可用');
    await expect(out.textContent).not.toContain('orders-pending');
    await expect(within(out).queryByRole('button')).toBeNull();
    // The panel keeps its heading, one level under the view's own.
    await expect(
      canvas.getByRole('heading', { level: 3, name: '待出库明细' }),
    ).toBeVisible();
    // The other data panel is not taken down with it.
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
  },
};

/**
 * A phone-width column (2026-09-23 analysis audit): at 414px the twelve
 * columns squeezed the analysis panel to ~130px, its bar labels over each
 * other and cut. Below `md` the grid is one column — the panels in reading
 * order, each full width and as tall as it was saved — so nothing scrolls
 * sideways and every label stays inside its chart. The saved layout is not
 * touched: the one column is derived, and nothing can be dragged in it.
 */
export const OnAPhone: Story = {
  ...DisplayAllPanels,
  decorators: [
    Story => (
      <div style={{ width: 414 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    const grid = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="dashboard-grid"]',
      );
      expect(found).toHaveAttribute('data-narrow');
      return found!;
    });
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

    // No sideways scroll: the grid holds its panels inside its own width.
    // The library slides an item to a new place over 200ms, so the panels
    // are measured once they have landed.
    const column = grid.getBoundingClientRect();
    await waitFor(() => {
      const panels = [
        ...grid.querySelectorAll<HTMLElement>('.react-grid-item'),
      ].map(item => ({
        title: item.querySelector('[data-slot="panel-title"]')?.textContent,
        box: item.getBoundingClientRect(),
      }));
      // Reading order, top to bottom: the stored layout puts the two data
      // panels side by side on the first row and the runbook under them.
      expect(
        [...panels]
          .sort((a, b) => a.box.top - b.box.top)
          .map(panel => panel.title),
      ).toEqual(['待出库明细', '按仓库汇总', '值班手册']);
      // Every panel full width, each on a row of its own.
      for (const panel of panels) {
        expect(panel.box.width).toBeGreaterThan(column.width * 0.9);
        expect(panel.box.left).toBeGreaterThanOrEqual(column.left - 1);
        expect(panel.box.right).toBeLessThanOrEqual(column.right + 1);
      }
    });
    await expect(grid.scrollWidth).toBeLessThanOrEqual(grid.clientWidth + 1);

    // The chart's labels stay inside its drawing, and do not sit on each
    // other: the ~130px panel wrote them over one another.
    // Measured once the drawing has followed its panel to the new width.
    await waitFor(() => {
      const plot = canvasElement
        .querySelector('[data-slot="chart-plot"]')!
        .getBoundingClientRect();
      expect(plot.width).toBeGreaterThan(300);
      const labels = [...axisTicks(canvasElement, 'bottom')].map(text =>
        text.getBoundingClientRect(),
      );
      expect(labels.length).toBeGreaterThan(0);
      for (const [index, box] of labels.entries()) {
        expect(box.left).toBeGreaterThanOrEqual(plot.left - 1);
        expect(box.right).toBeLessThanOrEqual(plot.right + 1);
        for (const other of labels.slice(index + 1))
          expect(overlaps(box, other)).toBe(false);
      }
    });

    // Nothing to drag in a derived column.
    await expect(
      canvasElement.querySelector('[data-slot="panel-grip"]'),
    ).toBeNull();
  },
};

export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(canvas.getAllByText(zhCN['label.query.failed'])).toHaveLength(2),
    );
    await expect(
      canvas.getByRole('link', { name: /^出库异常处理/ }),
    ).toBeVisible();
  },
};

/**
 * The keyboard path, in a real browser.
 *
 * jsdom already holds the contract — `test/dashboardUi.test.tsx` presses the
 * arrows on both handles and reads `controller().panels[0].layout` back. The
 * two things it cannot hold are the two this story is for. jsdom lays nothing
 * out, so every box is 0×0 at the origin and a panel that moved is
 * indistinguishable from one that did not; and it applies no stylesheet, so
 * the corner upstream paints only while a pointer is over the panel would
 * look reachable whether or not focus shows it.
 *
 * The panel is measured against the grid rather than in pixels: applying a
 * placement re-runs the panels, and the container is re-measured as their
 * contents settle, so two pixel widths taken either side of a keypress are
 * not comparable. Where the panel starts within the grid is.
 */
export const KeyboardLayout: Story = {
  ...DisplayBuilding,
  // The test browser is a phone's width, and below `md` the board is one
  // derived column with nothing to place — so this one is given a desk.
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await onTheGrid(canvasElement);
    await startBuilding(canvasElement);
    const grip = canvas.getByLabelText(
      zhCN['label.panel.handle'].replace('{title}', '待出库明细'),
    );
    const panel = grip.closest('.react-grid-item') as HTMLElement;
    const grid = canvasElement.querySelector(
      '[data-slot="dashboard-grid"]',
    ) as HTMLElement;
    /** How far into the grid the panel starts, as a fraction of its width. */
    const from = () =>
      (panel.getBoundingClientRect().left - grid.getBoundingClientRect().left) /
      grid.getBoundingClientRect().width;

    // The first column, give or take the grid's own padding.
    await expect(from()).toBeLessThan(0.03);
    // One handle: Enter starts arranging with it, the arrows move (V-02).
    grip.focus();
    await userEvent.keyboard('{Enter}');
    await expect(grip).toHaveAttribute('aria-pressed', 'true');
    await userEvent.keyboard('{ArrowRight}');
    // The second of 24, once the grid has finished sliding it there.
    await waitFor(() => expect(from()).toBeGreaterThan(0.04));
    // Still on the handle, still arranging; Enter ends it where it is.
    await expect(grip).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await expect(grip).toHaveAttribute('aria-pressed', 'false');

    // Named after its own panel, like the grip: each corner on the board
    // used to share one name (U8).
    const corner = within(panel).getByLabelText(
      zhCN['label.panel.resize'].replace('{title}', '待出库明细'),
    );
    corner.focus();
    // Upstream keeps the corner at `opacity: 0` until a pointer is over the
    // panel; a keyboard that can reach it must be able to see it.
    await expect(getComputedStyle(corner).opacity).toBe('1');
  },
};

/**
 * What an empty dashboard is, and — for whoever may build it — the first
 * steps, under the words (D22 A): a view, a new analysis (the workbench
 * provides the dialog it opens, D22 C), a heading.
 */
export const EmptyDashboard: Story = {
  ...DisplayEmptyDashboard,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(zhCN['label.dashboard.empty']),
    ).toBeVisible();
    const empty = canvasElement.querySelector(
      '[data-slot="dashboard-empty"]',
    ) as HTMLElement;
    await expect(empty).toHaveTextContent(zhCN['label.dashboard.empty-hint']);
    await expect(
      within(empty)
        .getAllByRole('button')
        .map(button => button.textContent),
    ).toEqual([
      zhCN['label.dashboard.empty.add-view'],
      zhCN['label.dashboard.add.new-analysis'],
      zhCN['label.dashboard.empty.add-heading'],
    ]);
  },
};

/**
 * A board whose backend drops out after it has answered, and comes back.
 *
 * jsdom holds the rules (`test/dashboardPlacement.test.tsx`); this is the
 * same in a real browser, with both data panels and the real title bar. The
 * refresh that fails keeps each panel's last answer on screen under a line
 * saying it is the last one — the words the workbenches and the embed use —
 * and that line's 「重试」 re-runs that panel alone.
 */
export const RefreshFailedKeepsData: Story = {
  ...DisplayAllPanels,
  args: { ...DisplayAllPanels.args, behaviour: 'outage' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const stale = new RegExp(
      `${zhCN['label.query.stale'].replace('{error} · ', '')}$`,
    );
    outage.down = false;
    try {
      const table = await findDataTable(canvasElement);
      await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(4));
      await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

      outage.down = true;
      await userEvent.click(
        canvas.getByRole('button', { name: zhCN['label.toolbar.refresh'] }),
      );
      // Both panels say the answer is the last one, and still show it.
      await waitFor(() => expect(canvas.getAllByText(stale)).toHaveLength(2));
      await expect(readColumn(table, '订单号')).toHaveLength(4);
      // The strip above takes height from the chart, which redraws into
      // what is left — its bars grow back in rather than being there at
      // once, in the full-width panel of the phone-width test browser.
      await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
      await expect(canvas.queryByText(zhCN['label.query.failed'])).toBeNull();

      // Back up: one panel's retry re-runs that panel, and only its line goes.
      outage.down = false;
      const [first] = canvas.getAllByRole('button', {
        name: zhCN['label.query.retry'],
      });
      await userEvent.click(first);
      await waitFor(() => expect(canvas.getAllByText(stale)).toHaveLength(1));
      await expect(readColumn(table, '订单号')).toHaveLength(4);
    } finally {
      outage.down = false;
    }
  },
};

/**
 * A keyboard step into a neighbour moves the neighbour out of the way, in a
 * real layout: stepping 值班手册 up one row puts it over 待出库明细's last
 * row, and 待出库明细 goes below it rather than being drawn underneath — the
 * two trade places, and the column closes up (batch-A walk).
 */
export const KeyboardStepPushes: Story = {
  ...DisplayBuilding,
  // A desk, as for `KeyboardLayout`: nothing is placed in the phone-width
  // column the test browser would otherwise give it.
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await onTheGrid(canvasElement);
    await startBuilding(canvasElement);
    const grip = (title: string) =>
      canvas.getByLabelText(
        zhCN['label.panel.handle'].replace('{title}', title),
      );
    const item = (title: string) =>
      grip(title).closest('.react-grid-item') as HTMLElement;
    const runbook = item('值班手册');
    const pending = item('待出库明细');
    const overlap = () => {
      const a = runbook.getBoundingClientRect();
      const b = pending.getBoundingClientRect();
      return (
        a.left < b.right &&
        b.left < a.right &&
        a.top < b.bottom &&
        b.top < a.bottom
      );
    };
    await expect(overlap()).toBe(false);

    grip('值班手册').focus();
    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard('{ArrowUp}');

    // Once the grid has slid them there: the runbook above, nothing overlaid.
    await waitFor(() =>
      expect(runbook.getBoundingClientRect().top).toBeLessThan(
        pending.getBoundingClientRect().top,
      ),
    );
    await waitFor(() => expect(overlap()).toBe(false));
  },
};

/**
 * A board stored in the 12-column grid, drawn in 24 (D22 E), measured in a
 * real layout: every panel sits exactly where its twelve-column numbers put
 * it — the grid library's arithmetic for 12 columns (a column and the gap
 * after it, 10px gaps and padding), against the boxes on screen, to a pixel.
 * The kernel test measures every panel a 12-column board can hold the same
 * way; this one holds that the grid on screen agrees.
 */
export const LegacyLayoutDrawsTheSame: Story = {
  ...DisplayLegacyLayout,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    await onTheGrid(canvasElement);
    await findDataTable(canvasElement);
    const grid = canvasElement.querySelector(
      '.react-grid-layout',
    ) as HTMLElement;
    const gap = 10;
    for (const panel of legacyDashboardConfig().panels) {
      const item = canvasElement.querySelector(
        `.react-grid-item[data-panel-id="${panel.id}"]`,
      ) as HTMLElement;
      await waitFor(() => {
        const width = grid.getBoundingClientRect().width;
        const column = (width - gap * 11 - gap * 2) / 12;
        const box = item.getBoundingClientRect();
        const left = box.left - grid.getBoundingClientRect().left;
        const { x, w } = panel.layout;
        expect(Math.abs(left - ((column + gap) * x + gap))).toBeLessThan(1.5);
        expect(Math.abs(box.width - (column * w + (w - 1) * gap))).toBeLessThan(
          1.5,
        );
      });
    }
    // Read into the new form, not moved: opening it changes nothing to save.
    await expect(
      within(canvasElement).queryByText(zhCN['label.header.unsaved']),
    ).toBeNull();
  },
};

/**
 * A board stored before batch C (D26 Q31): the leaf a filter could hold is
 * that filter's default on the filter bar, the reader's to change; the rest
 * is the board's fixed scope, said on the 「正在显示」 band as the board's,
 * with nothing to remove it by. Both reach the panels, and opening it
 * changes nothing to save.
 */
export const PreBatchCFixedScope: Story = {
  ...DisplayPreBatchCCondition,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);

    const bar = await canvas.findByRole('region', {
      name: zhCN['label.filters.bar'],
    });
    await expect(
      within(bar).getByRole('combobox', { name: '仓库' }),
    ).toHaveTextContent('华南');

    const band = await canvas.findByRole('region', {
      name: zhCN['label.applied.title'],
    });
    const fixed = band.querySelector('[data-fixed]') as HTMLElement;
    await expect(fixed).not.toBeNull();
    await expect(fixed).toHaveTextContent('仓库');
    await expect(fixed).toHaveTextContent('西南');
    await expect(fixed).toHaveTextContent(zhCN['label.applied.fixed']);
    await expect(within(band).queryAllByRole('button')).toHaveLength(0);

    // 华南 and not 西南: the list answers for 华南 alone.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1005']),
    );
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
  },
};

/** WCAG 1.4.11: an edge or a focus mark that is the control's own. */
const NON_TEXT_CONTRAST = 3;

/**
 * Presses Tab from `from` until `target` has the focus, so `:focus-visible`
 * holds — a script's `focus()` is not a keyboard's.
 */
async function tabTo(from: HTMLElement, target: HTMLElement): Promise<void> {
  from.focus();
  for (let presses = 0; presses < 20; presses += 1) {
    if (document.activeElement === target) return;
    await userEvent.tab();
  }
  if (document.activeElement !== target)
    throw new Error('Tab never reached the target.');
}

/**
 * A panel's frame, measured (U-03, U-04). The registry's card draws its
 * edge with a ring, not a border, so a panel carrying a warning takes the
 * warning colour on that ring — it used to ask for a border 0px wide, and
 * only the attribute said anything. And the body, a Tab stop because it
 * scrolls, wears the focus mark inside its edge: it is as wide as the card,
 * whose `overflow-hidden` cut an outside ring down to a grey line at 1.77:1.
 */
const panelChrome = (theme: 'light' | 'dark'): Story => ({
  ...DisplayPersonalViewOnSharedBoard,
  globals: { theme },
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const heading = await canvas.findByRole('heading', {
      level: 3,
      name: '我盯的大额单',
    });
    const warned = heading.closest<HTMLElement>(
      '[data-slot="dashboard-panel"]',
    )!;
    await waitFor(() => expect(warned).toHaveAttribute('data-warning'));
    const quiet = [
      ...canvasElement.querySelectorAll<HTMLElement>(
        '[data-slot="dashboard-panel"]',
      ),
    ].find(panel => !panel.hasAttribute('data-warning'))!;

    const edge = measureRingContrast(warned);
    await expect(
      edge.ratio,
      `${theme} warning edge ${JSON.stringify(edge.colors)}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
    // The colour is the warning's: a panel without one keeps the quiet edge.
    await expect(measureRingContrast(quiet).ratio).toBeLessThan(edge.ratio);

    const body = within(warned).getByRole('group', { name: '我盯的大额单' });
    const menu = within(warned).getByRole('button', {
      name: zhCN['label.panel.menu'].replace('{title}', '我盯的大额单'),
    });
    await tabTo(menu, body);
    const mark = measureOutlineContrast(body);
    await expect(
      mark.ratio,
      `${theme} panel body focus ${JSON.stringify(mark.colors)}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
    // Inside the body's own edge, where the card cannot clip it.
    const style = getComputedStyle(body);
    await expect(parseFloat(style.outlineOffset)).toBeLessThanOrEqual(
      -parseFloat(style.outlineWidth),
    );
  },
});

export const PanelChromeInLightTheme: Story = panelChrome('light');
export const PanelChromeInDarkTheme: Story = panelChrome('dark');

/**
 * A filter chip's edge is its controls' edge — the name, the value and ✕
 * draw none of their own — so it is `--input`, at 3:1 in both themes, the
 * quiet chip (dashed, on the page's ground) included (U-06). On `--border`
 * it measured 1.22:1 on the chip's fill.
 */
const chipEdges = (theme: 'light' | 'dark'): Story => ({
  ...DisplayFilters,
  globals: { theme },
  play: async ({ canvasElement }) => {
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="dashboard-filter"]').length,
      ).toBeGreaterThan(1),
    );
    const measured = [
      ...canvasElement.querySelectorAll<HTMLElement>(
        '[data-slot="dashboard-filter"]',
      ),
    ].map(chip => ({
      name: chip.dataset.filter,
      idle: chip.hasAttribute('data-idle'),
      ...measureBorderContrast(chip),
    }));
    const report = measured
      .map(
        ({ name, idle, ratio, colors }) =>
          `${name}${idle ? ' (idle)' : ''} ${ratio.toFixed(2)}:1 ${JSON.stringify(colors)}`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
  },
});

export const FilterChipEdgesInLightTheme: Story = chipEdges('light');
export const FilterChipEdgesInDarkTheme: Story = chipEdges('dark');

/**
 * On a phone's width a panel's badges take a line under its title rather
 * than squeezing it (U-10): the name is how a reader tells the panels
 * apart, and it used to be the first thing to give way.
 */
export const TitleOverItsBadges: Story = {
  ...DisplayCrossFilter,
  play: async ({ canvasElement }) => {
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="panel-click-filter"]'),
      ).not.toBeNull(),
    );
    const panel = canvasElement
      .querySelector('[data-slot="panel-click-filter"]')!
      .closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    const title = panel.querySelector<HTMLElement>(
      '[data-slot="panel-title"]',
    )!;
    const badges = panel.querySelector<HTMLElement>(
      '[data-slot="panel-badges"]',
    )!;
    // The whole name, not an ellipsis of it…
    await expect(title.scrollWidth).toBeLessThanOrEqual(title.clientWidth + 1);
    // …with the badges on a line of their own under it.
    await expect(badges.getBoundingClientRect().top).toBeGreaterThanOrEqual(
      title.getBoundingClientRect().bottom - 1,
    );
  },
};

/**
 * 「新建分析…」 on a phone: its title comes before 「放进仪表盘」, on screen
 * as in the Tab order (U-14). The registry's footer stacks its children
 * bottom-up on a narrow screen, which put the button over the box it adds
 * by.
 */
export const NewAnalysisTitleFirstOnAPhone: Story = {
  ...DisplayEmptyDashboard,
  // The footer's order follows the page's width (`sm`), not the column's:
  // the runner sizes the page to a phone (`parameters.viewport`).
  parameters: {
    ...DisplayEmptyDashboard.parameters,
    viewport: {
      options: {
        phone: { name: '414×896', styles: { width: '414px', height: '896px' } },
      },
    },
  },
  globals: { viewport: { value: 'phone' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(414);
    await userEvent.click(
      await within(canvasElement).findByRole('button', {
        name: zhCN['label.dashboard.add.new-analysis'],
      }),
    );
    const dialog = await screen.findByRole('dialog');
    const title = within(dialog).getByRole('textbox', {
      name: zhCN['label.panel.new-analysis.title'],
    });
    const add = within(dialog).getByRole('button', {
      name: zhCN['label.panel.new-analysis.add'],
    });
    await expect(title.getBoundingClientRect().bottom).toBeLessThanOrEqual(
      add.getBoundingClientRect().top,
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  },
};
