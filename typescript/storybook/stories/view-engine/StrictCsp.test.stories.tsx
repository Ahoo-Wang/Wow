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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import {
  DataWorkbench,
  formatMessage,
  registerChartMap,
  zhCN,
  type ChartMapGeoJson,
} from '@ahoo-wang/wow-view-engine/ui';
import {
  chartsDrawn,
  drawnMarks,
  pressMark,
  raiseTooltip,
} from './chartDom.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { dragEdgeBy, dragHandleOnto } from './pointerDrag.js';
import { findDataTable } from './readTable.js';
import { StoryEngine } from './StoryEngine.js';
import { CHART_VIEWS, CHART_VIEW_IDS } from './retail/chartViews.js';
import { RetailBoardScene } from './retail/RetailHost.js';
import { createRetailEngine } from './retail/source.js';
import {
  SHOWCASE,
  SHOWCASE_MAP,
  SHOWCASE_TABS,
  showcasePanels,
  type Tab,
} from './retail/showcase.js';
import { noPanelOut, panelOf } from './retail/twins.js';
import {
  ORDER_WORKBENCH_VIEWS,
  retailOrderAnalysisDefinition,
  retailOrdersDefinition,
} from './retail/views.js';
import {
  CSP_NONCE,
  expectNoViolations,
  underStrictPolicy,
} from './strictCsp.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/*
 * The engine under the strict Content Security Policy of its README
 * (`strictCsp.ts`): the record workbench, every chart type, the three
 * exports, and a board read and built — each walked with the pointer and
 * the keyboard a reader uses, and not one violation reported (view-engine
 * D74). The places the engine or its libraries add anything to the page are
 * all walked, each with the page's nonce where it needs one: a drag in a
 * list (the drag-and-drop library's `<style>`, D61), a select opened (Base
 * UI's scrollbar rule), a panel moved and resized on the grid (the grid drag
 * library's `<style>`), the board's cells while it is built (drawn in the
 * page, not a `data:` image), every chart's tooltip, and a chart turned into
 * a PNG through a `blob:` image.
 */

const say = (key: keyof typeof zhCN, params: Record<string, string> = {}) =>
  formatMessage(zhCN, key, params);

/** Six squares named for provinces: the map never goes to the network. */
const FIXTURE_MAP: ChartMapGeoJson = {
  type: 'FeatureCollection',
  features: ['广东省', '浙江省', '江苏省', '山东省', '上海市', '北京市'].map(
    (name, at) => ({
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
    }),
  ),
};

function OrderWorkbench() {
  return (
    <StoryEngine
      create={() =>
        createRetailEngine([retailOrdersDefinition], ORDER_WORKBENCH_VIEWS)
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={retailOrdersDefinition.id}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

function ShipHours() {
  return (
    <StoryEngine
      create={() =>
        createRetailEngine([retailOrderAnalysisDefinition], CHART_VIEWS)
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={retailOrderAnalysisDefinition.id}
          instanceId={CHART_VIEW_IDS.durationBands}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

function Showcase({ tab }: { tab?: Tab }) {
  return <RetailBoardScene instanceId={SHOWCASE} initialTab={tab} />;
}

const meta = {
  title: 'View Engine/能力/严格 CSP/回归',
  component: Showcase,
  tags: ['!dev', '!autodocs', 'test'],
  // A desktop's screen, which a board is built on: below it the board is one
  // column, with nothing to drag or size.
  parameters: {
    layout: 'fullscreen',
    viewport: {
      options: {
        desk: {
          name: '1440×900',
          styles: { width: '1440px', height: '900px' },
        },
      },
    },
  },
  globals: { viewport: { value: 'desk' } },
  beforeEach: async () => {
    await underStrictPolicy();
    return registerChartMap({
      name: SHOWCASE_MAP,
      label: '中国（合成的回归夹具）',
      load: async () => FIXTURE_MAP,
    });
  },
} satisfies Meta<typeof Showcase>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * The files the page hands the browser, caught instead of saved: the blob
 * and the name. A PNG's SVG passes through `createObjectURL` on its way onto
 * the canvas, as an image the policy has to let load (`img-src blob:`).
 */
function catchDownloads() {
  const files: { blob: Blob; name: string }[] = [];
  const create = URL.createObjectURL.bind(URL);
  let last: Blob | undefined;
  const originalCreate = URL.createObjectURL;
  const originalClick = HTMLAnchorElement.prototype.click;
  URL.createObjectURL = (blob: Blob | MediaSource) => {
    if (blob instanceof Blob) last = blob;
    return create(blob);
  };
  HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
    if (last && this.download) files.push({ blob: last, name: this.download });
  };
  return {
    files,
    restore() {
      URL.createObjectURL = originalCreate;
      HTMLAnchorElement.prototype.click = originalClick;
    },
  };
}

/** The browser's own mouse: a press, a walk by `dx`/`dy`, a release. */
async function realDrag(element: HTMLElement, dx: number, dy: number) {
  const mouse = globalThis.storybookRealMouse;
  if (!mouse) throw new Error('This story needs the test runner’s mouse.');
  const frame = () =>
    new Promise(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(resolve)),
    );
  const box = element.getBoundingClientRect();
  const x = box.left + box.width / 2;
  const y = box.top + box.height / 2;
  await mouse.move(x, y);
  await mouse.down(x, y);
  for (let step = 1; step <= 8; step += 1) {
    await mouse.move(x + (dx * step) / 8, y + (dy * step) / 8);
    await frame();
  }
  await mouse.up(x + dx, y + dy);
  await frame();
  await mouse.away();
}

/**
 * A plot's tooltip raised: over its grid or a painted mark
 * (`raiseTooltip`), or else along one of its lines — the parallel axes draw
 * nothing but lines, and answer over a line alone.
 */
async function tooltipOver(plot: HTMLElement): Promise<void> {
  try {
    await raiseTooltip(plot);
    return;
  } catch {
    // Not over a mark: along a line.
  }
  const lines = [...plot.querySelectorAll<SVGPathElement>('svg path')].filter(
    path => path.getAttribute('fill') === 'none' && path.getTotalLength() > 0,
  );
  for (const line of lines) {
    const at = line.getPointAtLength(line.getTotalLength() / 2);
    const matrix = line.getScreenCTM()!;
    const point = new DOMPoint(at.x, at.y).matrixTransform(matrix);
    line.dispatchEvent(
      new MouseEvent('mousemove', {
        bubbles: true,
        clientX: point.x,
        clientY: point.y,
      }),
    );
    try {
      await waitFor(
        () =>
          expect(
            plot
              .querySelector('[data-slot="chart-tooltip"]')
              ?.getBoundingClientRect().width,
          ).toBeGreaterThan(0),
        { timeout: 500 },
      );
      return;
    } catch {
      // The next line.
    }
  }
  throw new Error('no tooltip over this plot');
}

/** Opens a select and picks one of its options. */
async function pickOption(combobox: HTMLElement, option: string) {
  await userEvent.click(combobox);
  await userEvent.click(await screen.findByRole('option', { name: option }));
  await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
}

/**
 * The record workbench's settings: the table read, its columns reordered by
 * a drag and a column's summary picked from a select.
 */
export const RecordWorkbench: Story = {
  render: () => <OrderWorkbench />,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(1),
    );
    await expectNoViolations('the table');

    // The column settings: a row dragged by its handle — the drag library
    // adds its `<style>` while it holds it, under the page's nonce.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    const handles = await waitFor(() => {
      const found = [
        ...document.querySelectorAll<HTMLElement>(
          '[data-slot="drag-handle"]:not([disabled])',
        ),
      ];
      expect(found.length).toBeGreaterThan(1);
      return found;
    });
    await dragHandleOnto(handles[0]!, handles[1]!);
    await expectNoViolations('a column dragged');
    // And a summary picked from a select, whose list Base UI hides the
    // scrollbar of with a rule of its own.
    const summary = within(document.body).getAllByRole('combobox', {
      name: /的汇总$/,
    })[0]!;
    await pickOption(summary, zhCN['label.summary.fn.MAX']);
    await expectNoViolations('a summary picked');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  },
};

/**
 * The record workbench's reading: a column widened by its edge, the page
 * size changed from its select, a record's detail opened, and the rows
 * exported as a CSV.
 */
export const RecordDetailAndExport: Story = {
  render: () => <OrderWorkbench />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(1),
    );
    await expectNoViolations('the table');
    // A column widened by its edge.
    const edge = canvas.getAllByRole('separator', {
      name: /^调整 .+ 宽度$/,
    })[0]!;
    await dragEdgeBy(edge, 60);
    await expectNoViolations('a column widened');

    // The page size, from its select.
    await pickOption(
      canvas.getByRole('combobox', {
        name: zhCN['label.pagination.page-size'],
      }),
      say('label.pagination.page-size-option', { size: '50' }),
    );
    await expectNoViolations('the page size changed');

    // A record's detail, opened from its row.
    const row = findDataTable(canvasElement).then(found =>
      found.querySelector<HTMLElement>('tbody tr[data-row-key]'),
    );
    (await row)!.focus();
    await userEvent.keyboard('{Enter}');
    await screen.findByRole('dialog');
    await expectNoViolations('a record’s detail');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    // The rows exported as a CSV.
    const caught = catchDownloads();
    try {
      await userEvent.click(
        canvas.getByRole('button', { name: zhCN['label.export.title'] }),
      );
      const menu = await screen.findByRole('menu').catch(() => null);
      if (menu)
        await userEvent.click(
          within(menu).getByRole('menuitem', {
            name: zhCN['label.export.data'],
          }),
        );
      const dialog = await screen.findByRole('dialog');
      await userEvent.click(
        within(dialog).getByRole('button', {
          name: zhCN['label.export.confirm'],
        }),
      );
      await waitFor(() => expect(caught.files).toHaveLength(1), {
        timeout: 10_000,
      });
      await expect(caught.files[0]!.name).toMatch(/\.csv$/);
    } finally {
      caught.restore();
    }
    await expectNoViolations('the rows exported');
  },
};

/**
 * Every panel of one tab of the showcase board drawn, each chart's tooltip
 * raised where it has one, and not one violation. The 22 types are spread
 * over the four tabs, one story each (`CHARTS_BY_TAB`), so no story walks
 * the whole board at once.
 */
async function walkTab(canvasElement: HTMLElement, tab: Tab) {
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
    const plot = await waitFor(() => {
      const found = panelOf(panel.title).querySelector<HTMLElement>(
        '[data-slot="chart-plot"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    // A gauge draws one reading and has no tooltip to raise.
    if (panel.type !== 'gauge') await tooltipOver(plot);
    await expectNoViolations(
      `${SHOWCASE_TABS[tab]}: ${panel.title} (${panel.type})`,
    );
  }
}

/** The four tabs together hold every chart type, each once. */
const CHARTS_BY_TAB = new Set(showcasePanels().map(panel => panel.type));

/** 走势: the metric card, the gauge and the time charts. */
export const ChartsTrend: Story = {
  args: { tab: 'trend' },
  play: async ({ canvasElement }) => {
    await expect(CHARTS_BY_TAB.size).toBe(22);
    await walkTab(canvasElement, 'trend');
  },
};

/** 构成. */
export const ChartsMix: Story = {
  args: { tab: 'mix' },
  play: ({ canvasElement }) => walkTab(canvasElement, 'mix'),
};

/** 分布与关系. */
export const ChartsSpread: Story = {
  args: { tab: 'spread' },
  play: ({ canvasElement }) => walkTab(canvasElement, 'spread'),
};

/** 地域与转化, on the story's own map. */
export const ChartsRegion: Story = {
  args: { tab: 'region' },
  play: ({ canvasElement }) => walkTab(canvasElement, 'region'),
};

/**
 * A province pressed on the bars: the whole board filtered to it, every
 * panel drawn again.
 */
const BAR = showcasePanels().find(panel => panel.type === 'bar')!;
export const BoardFilteredFromABar: Story = {
  args: { tab: BAR.tab },
  play: async ({ canvasElement }) => {
    await noPanelOut(canvasElement);
    await chartsDrawn(canvasElement);
    const [mark] = drawnMarks(panelOf(BAR.title));
    pressMark(mark!);
    await waitFor(() =>
      expect(screen.getByRole('group', { name: '省份' })).toHaveTextContent(
        '省',
      ),
    );
    await chartsDrawn(canvasElement);
    await expectNoViolations('the board filtered from a bar');
  },
};

/**
 * The analysis workbench's three exports: the chart as an SVG and as a PNG
 * (its SVG drawn onto a canvas through a `blob:` image), and the result as a
 * CSV.
 */
export const Exports: Story = {
  render: () => <ShipHours />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await chartsDrawn(canvasElement);
    const caught = catchDownloads();
    const open = async (item: keyof typeof zhCN) => {
      await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
      await userEvent.click(
        canvas.getByRole('button', { name: zhCN['label.export.title'] }),
      );
      const menu = await screen.findByRole('menu');
      await userEvent.click(
        within(menu).getByRole('menuitem', { name: zhCN[item] }),
      );
    };
    try {
      await open('label.export.image-svg');
      await waitFor(() => expect(caught.files).toHaveLength(1));
      await expect(caught.files[0]!.name).toMatch(/\.svg$/);
      await expectNoViolations('the chart as an SVG');

      await open('label.export.image-png');
      await waitFor(() => expect(caught.files).toHaveLength(2));
      await expect(caught.files[1]!.blob.type).toBe('image/png');
      await expectNoViolations('the chart as a PNG');

      await open('label.export.data');
      const dialog = await screen.findByRole('dialog');
      await userEvent.click(
        within(dialog).getByRole('button', {
          name: zhCN['label.export.confirm'],
        }),
      );
      await waitFor(() => expect(caught.files).toHaveLength(3));
      await expect(caught.files[2]!.name).toMatch(/\.csv$/);
      await expectNoViolations('the result as a CSV');
      await userEvent.keyboard('{Escape}');
    } finally {
      caught.restore();
    }
    // Left with nothing on its way out, for the accessibility check.
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
    await userEvent.unhover(
      canvas.getByRole('button', { name: zhCN['label.export.title'] }),
    );
    (document.activeElement as HTMLElement | null)?.blur();
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="tooltip-content"]'),
      ).toBeNull(),
    );
  },
};

/**
 * The showcase board arranged: 「编辑」, a panel moved by its grip and sized
 * by its corner with the browser's own mouse — the grid drag library's
 * `<style>`, added first under the page's nonce — and the board saved.
 */
export const BoardArranged: Story = {
  // On the lightest tab: the building is under test, not the charts.
  args: { tab: 'region' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await noPanelOut(canvasElement);
    await chartsDrawn(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    const grip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="panel-grip"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expectNoViolations('building begun');

    // A panel dragged by its grip, and resized by its corner: each a real
    // gesture, the panel somewhere else after it.
    const item = grip.closest<HTMLElement>('.react-grid-item')!;
    const box = () => {
      const { left, top, width, height } = item.getBoundingClientRect();
      return [left, top, width, height].map(Math.round);
    };
    const placed = box();
    await realDrag(grip, 240, 120);
    await waitFor(() => expect(box()).not.toEqual(placed));
    await expectNoViolations('a panel dragged');
    const dragged = box();
    await realDrag(
      item.querySelector<HTMLElement>('[data-slot="panel-resize"]')!,
      80,
      90,
    );
    await waitFor(() => expect(box()).not.toEqual(dragged));
    await expectNoViolations('a panel resized');
    // The grid's drag library found its style already there, under the
    // page's nonce, and added none of its own.
    const draggable = document.querySelectorAll('#react-draggable-style-el');
    await expect(draggable).toHaveLength(1);
    await expect((draggable[0] as HTMLStyleElement).nonce).toBe(CSP_NONCE);

    // Saved.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.save'] }),
    );
    const confirm = await screen.findByRole('alertdialog').catch(() => null);
    if (confirm)
      await userEvent.click(
        within(confirm).getByRole('button', {
          name: zhCN['label.save.shared-confirm'],
        }),
      );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
      ).toBeNull(),
    );
    await chartsDrawn(canvasElement);
    await expectNoViolations('the board saved');
  },
};

/**
 * The showcase board built: a tab added and dragged in front of the first,
 * a new analysis made in its dialog (its data picked from a select) and put
 * on the board, and the board saved.
 */
export const BoardBuilt: Story = {
  // On the lightest tab: the building is under test, not the charts.
  args: { tab: 'region' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await noPanelOut(canvasElement);
    await chartsDrawn(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="panel-grip"]'),
      ).not.toBeNull(),
    );
    await expectNoViolations('building begun');

    // A tab added, then dragged in front of the first.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.tabs.add'] }),
    );
    await userEvent.keyboard('异常{Enter}');
    await canvas.findByRole('button', { name: '异常' });
    const first = Object.values(SHOWCASE_TABS)[0]!;
    const moved = canvas.getByRole('button', {
      name: say('label.tabs.reorder', { title: '异常' }),
    });
    const onto = canvas
      .getByRole('button', {
        name: say('label.tabs.reorder', { title: first }),
      })
      .getBoundingClientRect();
    const from = moved.getBoundingClientRect();
    await realDrag(
      moved,
      onto.left + onto.width / 2 - (from.left + from.width / 2),
      0,
    );
    await waitFor(() =>
      expect(
        within(canvas.getByRole('list', { name: zhCN['label.tabs.name'] }))
          .getAllByRole('listitem')
          .map(
            tab =>
              tab.querySelector('[data-slot="dashboard-tab"]')?.textContent,
          )[0],
      ).toBe('异常'),
    );
    await expectNoViolations('a tab dragged');

    // A new analysis, made in its dialog and put on the board.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.add'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.dashboard.add.new-analysis'],
      }),
    );
    const dialog = await screen.findByRole('dialog');
    // The data it reads, picked from a select; then it names itself by
    // what it shows, and runs.
    await waitFor(() =>
      expect(dialog.contains(document.activeElement)).toBe(true),
    );
    await userEvent.click(within(dialog).getByRole('combobox'));
    await userEvent.click((await screen.findAllByRole('option'))[0]!);
    const title = within(dialog).getByRole('textbox', {
      name: zhCN['label.panel.new-analysis.title'],
    });
    await waitFor(() => expect(title).not.toHaveValue(''));
    await expectNoViolations('a new analysis made');
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.panel.new-analysis.add'],
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await expectNoViolations('a new analysis on the board');

    // Saved.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.save'] }),
    );
    const confirm = await screen.findByRole('alertdialog').catch(() => null);
    if (confirm)
      await userEvent.click(
        within(confirm).getByRole('button', {
          name: zhCN['label.save.shared-confirm'],
        }),
      );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
      ).toBeNull(),
    );
    await chartsDrawn(canvasElement);
    await expectNoViolations('the board saved');
  },
};
