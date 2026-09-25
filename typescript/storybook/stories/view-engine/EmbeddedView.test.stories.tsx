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
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  AnalysisEmbed as DisplayAnalysisEmbed,
  Default as DisplayDefault,
  EmptyResult as DisplayEmptyResult,
  FillTheScreen as DisplayFillTheScreen,
  QueryFailed as DisplayQueryFailed,
  ScopeRefused as DisplayScopeRefused,
  ScopeRefusedOnOpen as DisplayScopeRefusedOnOpen,
  ScopedByHost as DisplayScopedByHost,
  TotalCoversThisPageOnly as DisplayTotalCoversThisPageOnly,
  Interactive as DisplayInteractive,
  AnalysisInteractive as DisplayAnalysisInteractive,
} from './EmbeddedView.stories.js';
import { amountOf, readColumn, readPage, readTotal } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/EmbeddedView/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The shared view's condition and sort, as the source answered them. */
const PENDING_BY_AMOUNT = ['SO-1003', 'SO-1005', 'SO-1001', 'SO-1006'];

/** The host's own control, which is the only one an embed ever has. */
const hostToggle = (canvasElement: HTMLElement) =>
  within(canvasElement).getByRole('button', { name: /全屏查看|退出全屏/ });

/**
 * The embed's own surface.
 *
 * The mock host page wears `fve-tokens` rather than a second root (D17-10),
 * so the embed is the only surface on screen — and it is still found by the
 * class the story put on it, because what must fill the screen is *this*
 * embed rather than whichever surface happens to come first.
 */
const surfaceOf = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('.host-embed')!;

export const Default: Story = {
  ...DisplayDefault,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);

    // The result, and what it was fetched under — and nothing else. Every
    // piece of chrome a workbench has is absent here, which is the whole
    // definition of the component.
    await expect(
      canvasElement.querySelector('[data-slot="applied-bar"]'),
    ).not.toBeNull();
    for (const slot of [
      'view-header',
      'view-sidebar',
      'view-list',
      'result-toolbar',
      'save-actions',
      'editor-band',
      'record-pagination',
    ])
      await expect(
        canvasElement.querySelector(`[data-slot="${slot}"]`),
      ).toBeNull();

    // Read-only: a ✕ here would let a reader drop a saved condition, which on
    // a page that embedded this view to show one customer's orders is the
    // page quietly listing everyone's.
    const applied = canvasElement.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    )!;
    await expect(within(applied).queryAllByRole('button')).toHaveLength(0);

    // The static tier (D22): the headers are read, not pressed.
    for (const head of within(table).getAllByRole('columnheader'))
      await expect(within(head).queryByRole('button')).toBeNull();

    // No expand control of its own: the only one on screen is the host's.
    await expect(
      canvasElement.querySelector('[data-slot="view-expand"]'),
    ).toBeNull();
    await expect(
      canvas.queryByRole('button', {
        name: zhCN['label.workbench.expand-view'],
      }),
    ).toBeNull();
    // And the way out stays out of the page — and out of the a11y tree —
    // while nothing is filling the screen.
    await expect(
      canvas.queryByRole('button', {
        name: zhCN['label.workbench.collapse-view'],
      }),
    ).toBeNull();
  },
};

/**
 * 暗色下嵌入块与所在的卡片同底。
 *
 * 根涂的是 `--background`，暗色下它比 `--card` 深一档，嵌入块在卡片里读成一块
 * 更深的区域（明色两者都是白，所以看不出）。宿主在卡片上把 `--fve-background`
 * 与 `--fve-dark-background` 设为卡片色，嵌入视图、它的行都涂卡片色；表头与合计
 * 那两条吸附带仍然不透明。
 */
export const OnTheCardInTheDark: Story = {
  ...DisplayDefault,
  globals: { theme: 'dark' },
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    const surface = surfaceOf(canvasElement);
    const card = surface.closest<HTMLElement>('[data-slot="card"]')!;
    const paint = (element: Element) =>
      getComputedStyle(element).backgroundColor;
    await expect(document.documentElement).toHaveClass('dark');
    await expect(paint(surface)).toBe(paint(card));
    // A row is the card's colour too — opaque, so a pinned cell still hides
    // the column scrolling under it. Waited for: a row fades between
    // colours (`transition-colors`), and the dark class can land after it
    // was first painted light.
    await waitFor(() =>
      expect(paint(table.querySelector('tbody tr')!)).toBe(paint(card)),
    );
    // The two sticky bands — the header and the totals — are their own
    // colour, and opaque.
    for (const band of [
      table.querySelector('thead tr')!,
      table.querySelector('tfoot tr')!,
    ]) {
      await expect(paint(band)).not.toBe(paint(card));
      await expect(paint(band)).not.toMatch(/transparent|rgba\(0, 0, 0, 0\)/);
    }
  },
};

export const ScopedByHost: Story = {
  ...DisplayScopedByHost,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    // The host's condition went in *with* the config, so the opening query
    // was already narrowed: one 华东 order out of the four pending ones.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001']),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(1280);
    // Nothing was refused, so nothing says anything was.
    await expect(
      within(canvasElement).queryByText(zhCN['label.scope.refused']),
    ).toBeNull();
  },
};

export const ScopeRefused: Story = {
  ...DisplayScopeRefused,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // It opens on a narrowing that *was* accepted.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001']),
    );

    // The host swaps it for one this definition cannot take.
    await userEvent.click(canvas.getByRole('button', { name: '按客户收窄' }));
    await expect(
      await canvas.findByText(zhCN['label.scope.refused']),
    ).toBeVisible();
    // The refusal left the previous narrowing running, which is exactly why
    // it has to be said out loud: the page asked for another customer and is
    // still being shown this one's.
    await expect(
      readColumn(await canvas.findByRole('table'), '订单号'),
    ).toEqual(['SO-1001']);

    // Back to something admissible and the refusal goes with it.
    await userEvent.click(canvas.getByRole('button', { name: '不收窄' }));
    await waitFor(() =>
      expect(canvas.queryByText(zhCN['label.scope.refused'])).toBeNull(),
    );
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(
        PENDING_BY_AMOUNT,
      ),
    );
  },
};

/**
 * The same refusal on the first open, in the same words (D17-5).
 *
 * A scope the definition cannot take no longer rides into the first
 * admission as part of the config: what was refused is the *page's* own
 * condition, and the page is the only one who could change it — the view is
 * fine, and a host cannot fix somebody else's saved config anyway.
 */
export const ScopeRefusedOnOpen: Story = {
  ...DisplayScopeRefusedOnOpen,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.scope.refused']),
    ).toBeVisible();
    await expect(
      canvas.queryByText(zhCN['label.view.needs-fixing']),
    ).toBeNull();
    // And the un-narrowed result is on screen under the alert, as it is for
    // a narrowing refused later: the page not getting the range it asked for
    // is no reason to withhold what the view does say.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
  },
};

export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.record.empty']),
    ).toBeVisible();
    await expect(canvas.queryByRole('table')).toBeNull();
  },
};

export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // The strip says what the source answered, in the source's own words:
    // the embed has nothing else on screen to explain an empty card with.
    const strip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="status-strip"][data-tone="error"]',
      );
      if (!found) throw new Error('no error strip yet');
      return found;
    });
    await expect(strip).toHaveTextContent('仓储服务暂时不可用');
    await expect(canvas.queryByRole('table')).toBeNull();
    // No toolbar, so no retry: a button that was the block's only control
    // would make the result look like one.
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.query.retry'] }),
    ).toBeNull();
    // The host's page is untouched by a failed query inside its card.
    await expect(canvas.getByText('明远商贸 · 客户详情')).toBeVisible();
  },
};

export const AnalysisEmbed: Story = {
  ...DisplayAnalysisEmbed,
  play: async ({ canvasElement }) => {
    // Dispatch by kind is the embed's own: the host handed over an id.
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    await expect(
      canvasElement.querySelector('[data-slot="applied-bar"]'),
    ).not.toBeNull();
  },
};

export const TotalCoversThisPageOnly: Story = {
  ...DisplayTotalCoversThisPageOnly,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    // The aggregation was refused, so the summary falls back to this page —
    // and says so, which matters more here than in a workbench: there is no
    // editor, no toolbar and no scope bar to correct a number that lied.
    await waitFor(() => expect(amountOf(readPage(table, '金额'))).toBe(6470));
    await expect(
      within(canvasElement).getByText(zhCN['label.summary.scope.page']),
    ).toBeVisible();
  },
};

/**
 * The one thing an embed cannot do for itself, done by the host.
 *
 * The control is the host's, so a surface filling the screen covers it — and
 * the surface grows its own way out for exactly that case. Three exits are
 * checked here because each is the only one some user has: Escape for a
 * keyboard, the surface's own button for a touch device, and the host's
 * control again once the page is back.
 */
export const FillTheScreen: Story = {
  ...DisplayFillTheScreen,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const surface = surfaceOf(canvasElement);
    const toggle = hostToggle(canvasElement);
    const doc = canvasElement.ownerDocument;
    const before = held(doc);

    await expect(surface).not.toHaveAttribute('data-view-expanded');
    await userEvent.click(toggle);

    await waitFor(() =>
      expect(surface).toHaveAttribute('data-view-expanded', 'true'),
    );
    // On the viewport to the pixel, whatever the host's page did around it.
    await waitFor(() => expect(onViewport(surface)).toBe(true));
    // And the page underneath stops scrolling, in a way a host stylesheet
    // cannot outrank.
    await expect(held(doc)).toEqual(['hidden !important', 'hidden !important']);
    // The host's own chrome is underneath it now — which is the whole reason
    // the surface has to grow a way out.
    await expect(surface.contains(toggle)).toBe(false);
    const exit = canvas.getByRole('button', {
      name: zhCN['label.workbench.collapse-view'],
    });
    await expect(surface.contains(exit)).toBe(true);

    // 1. The surface's own exit — the only one a touch device has.
    await userEvent.click(exit);
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await expect(held(doc)).toEqual(before);
    await expect(doc.activeElement).toBe(toggle);
    await expect(
      canvas.queryByRole('button', {
        name: zhCN['label.workbench.collapse-view'],
      }),
    ).toBeNull();

    // 2. Escape gives the page back, from the host's control again.
    await userEvent.click(toggle);
    await waitFor(() =>
      expect(surface).toHaveAttribute('data-view-expanded', 'true'),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await expect(held(doc)).toEqual(before);
    await expect(doc.activeElement).toBe(toggle);

    // 3. And the host's control is still a toggle: it says so, both ways.
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-expanded', 'true'),
    );
    await userEvent.click(hostToggle(canvasElement));
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await expect(held(doc)).toEqual(before);
  },
};

/** The overflow the page is actually holding, one axis at a time. */
function held(doc: Document): string[] {
  const style = (
    (doc.scrollingElement as HTMLElement | null) ?? doc.documentElement
  ).style;
  return ['overflow-x', 'overflow-y'].map(name => {
    const priority = style.getPropertyPriority(name);
    return style.getPropertyValue(name) + (priority ? ` !${priority}` : '');
  });
}

/** Whether an element covers the viewport, to the pixel. */
function onViewport(element: HTMLElement): boolean {
  const view = element.ownerDocument.defaultView!;
  const box = element.getBoundingClientRect();
  return (
    Math.abs(box.left) < 1 &&
    Math.abs(box.top) < 1 &&
    Math.abs(box.width - view.innerWidth) < 1 &&
    Math.abs(box.height - view.innerHeight) < 1
  );
}

/**
 * The interactive tier end to end (D22): a header orders the rows, the
 * search narrows them within the page's own narrowing, the export is in the
 * first row, and 在工作台中打开 hands the host's route the saved view under
 * the page's condition.
 */
export const Interactive: Story = {
  ...DisplayInteractive,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // The page narrows to the east warehouse: one pending order.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001']),
    );
    // Sorted by a header, and paged.
    await userEvent.click(
      within(table).getByRole('button', { name: /按订单号升序排序/ }),
    );
    await waitFor(() =>
      expect(
        within(table).getByRole('columnheader', { name: /订单号/ }),
      ).toHaveAttribute('aria-sort', 'ascending'),
    );
    await expect(
      canvasElement.querySelector('[data-slot="record-pagination"]'),
    ).not.toBeNull();

    // The search sits at the applied band's end, and narrows within the
    // page's scope: nothing in the east warehouse matches 1003.
    const box = canvas.getByRole('searchbox', { name: '搜索订单' });
    await userEvent.type(box, '1003{Enter}');
    await waitFor(() =>
      expect(canvas.getByText(zhCN['label.record.empty'])).toBeVisible(),
    );
    await userEvent.clear(box);
    await userEvent.type(box, '1001{Enter}');
    await waitFor(async () =>
      expect(readColumn(await canvas.findByRole('table'), '订单号')).toEqual([
        'SO-1001',
      ]),
    );

    // The export, in the first row.
    await expect(
      canvas.getByRole('button', { name: zhCN['label.export.title'] }),
    ).toBeVisible();

    // 在工作台中打开: the saved view under the page's narrowing.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.panel.open'] }),
    );
    const route = canvasElement.querySelector('[data-host-route]')!;
    await expect(route).toHaveTextContent('orders-pending');
    await expect(route).toHaveTextContent('CN-EAST');
  },
};

/**
 * An analysis embed, interactive: the table｜chart switch is the reader's,
 * and a group's follow-up opens through the host's route.
 */
export const AnalysisInteractive: Story = {
  ...DisplayAnalysisInteractive,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    const layout = canvas.getByRole('group', {
      name: zhCN['label.analysis.layout'],
    });
    await userEvent.click(
      within(layout).getByRole('button', { name: zhCN['label.layout.table'] }),
    );
    const table = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="analysis-table"] table',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    const row = await within(table).findByRole('row', { name: /华东/ });
    await expect(row).toHaveAttribute('aria-haspopup', 'menu');
    await userEvent.click(row);
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: new RegExp(zhCN['label.drill.records']),
      }),
    );
    const route = canvasElement.querySelector('[data-host-route]')!;
    await waitFor(() => expect(route).toHaveTextContent('CN-EAST'));
  },
};
