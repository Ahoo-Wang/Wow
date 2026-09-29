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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  FillTheScreen as DisplayFillTheScreen,
  FillTheScreenInScaledHost as DisplayFillTheScreenInScaledHost,
  FillTheScreenInTransformedHost as DisplayFillTheScreenInTransformedHost,
  FillTheScreenWithPopups as DisplayFillTheScreenWithPopups,
  NarrowTitleBar as DisplayNarrowTitleBar,
  WideTable as DisplayWideTable,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { readColumn } from './readTable.js';
import {
  PENDING_BY_AMOUNT,
  headerOf,
  inFrontOf,
} from './recordWorkbenchTest.js';

/**
 * Layout: block spacing, filling the host, the floor height, filling the screen
 * and the phone width. One of the record workbench's regression files, split by
 * concern; they all share one title, so every story keeps its id, and the
 * helpers more than one of them needs are in `recordWorkbenchTest.ts`.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * The ruler between the blocks of the main column, the right way up.
 *
 * This workbench handed the shell a `className="gap-2"` and `cn` let it beat
 * `SPACE.BLOCKS`, so with the conditions out the column measured header →
 * 8px → editor band → 8px → result while the rows *inside* each block sat
 * 12px apart: two blocks stood closer together than two buttons do, and
 * nothing on the screen read as a group. Analysis and Dashboard, on the very
 * same shell, measured 16px throughout.
 *
 * It is measured here rather than in jsdom because what a class is worth in
 * pixels is the stylesheet's answer, and jsdom lays out nothing: the package's
 * jsdom suite can pin the class (`test/recordWorkbench.test.tsx`) and no more.
 * Both steps of the ladder are read, because the bug was never one number on
 * its own — it was the two of them in the wrong order.
 */
export const BlockSpacing: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // Out, so all three blocks of the column are on the page at once.
    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="editor-band"]'),
      ).not.toBeNull(),
    );

    const main = canvasElement.querySelector<HTMLElement>('main')!;
    await expect(canvasElement.querySelectorAll('main')).toHaveLength(1);
    const column = getComputedStyle(main);
    await expect(column.rowGap).toBe('16px');
    await expect(column.gap).toBe('16px');

    // And the result block is one framed region (D12 Ⅳ–Ⅶ): no row gap of
    // its own — the toolbar is its first row and the pagination its last,
    // each ruled off from the rows between them with a hairline, so the
    // space inside the frame is the slots' padding, not a gap.
    const result = canvasElement.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    )!;
    await expect(result).toHaveAttribute('data-framed', 'true');
    await expect(getComputedStyle(result).rowGap).toBe('normal');
    await expect(getComputedStyle(result).borderTopWidth).toBe('1px');
    const toolbar = canvasElement.querySelector<HTMLElement>(
      '[data-slot="result-toolbar"]',
    )!;
    await expect(getComputedStyle(toolbar).borderBottomWidth).toBe('1px');
    const pagination = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    )!;
    await expect(getComputedStyle(pagination).borderTopWidth).toBe('1px');
  },
};

/**
 * 列各占自己要的宽度，行照样通到框边（P-11）。
 *
 * 注册表的表是 `w-full`，自动布局把富余按比例分给各列：1300px 的结果区里，
 * 四列表把「金额」画成 446px，里面是一句 `¥2,450.00`——一眼从订单号扫到
 * 金额要横穿大半个屏幕。现在富余归末尾那一格空格子，各列落回自己内容要的
 * 宽度，而行（发丝线、悬停底色、两条汇总行）仍然与端口同宽。宿主宽度写死
 * 成 1300px（`narrowHost` 那个定宽壳子，这里给的是一个宽数——场景框自己
 * 限宽 1040px，量的是布局不是画面），量四件事：没有一列是那 446px、每一行
 * 都通到端口右缘、D13 右边那道线还在最后一个数据列上、列名一个字也没被
 * 截掉。
 */
export const ColumnsKeepTheirWidthAndRowsFillTheFrame: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, narrowHost: true, narrowWidth: 1300 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    const port = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    // The result area is wide and nothing scrolls sideways in it.
    await expect(port.clientWidth).toBeGreaterThan(800);
    await expect(port.scrollWidth).toBeLessThanOrEqual(port.clientWidth + 1);

    const heads = [
      ...table.querySelectorAll<HTMLElement>('thead tr:first-child>th'),
    ];
    const filler = heads[heads.length - 1]!;
    await expect(filler.dataset.column).toBe('filler');

    // No real column is a sea of empty: the widest is nowhere near the 446px
    // the same fixture drew when the surplus was shared out among them.
    const columns = heads.slice(0, -1);
    const widest = Math.max(
      ...columns.map(head => head.getBoundingClientRect().width),
    );
    await expect(widest).toBeLessThan(200);
    // And the surplus is real: it went somewhere, and that somewhere is the
    // cell nobody is told about.
    await expect(filler.getBoundingClientRect().width).toBeGreaterThan(400);

    // Every row reaches the frame — the header, a body row and both summary
    // rows — so a hairline, a hover band and the muted footer are whole.
    const edge = port.getBoundingClientRect().right;
    for (const row of table.querySelectorAll<HTMLElement>('tr'))
      await expect(Math.round(row.getBoundingClientRect().right)).toBe(
        Math.round(edge),
      );

    // D13's right edge belongs to the last *data* column, which is where
    // the columns end; past it there is nothing rather than more table. And
    // at 1300px nothing scrolls, so the edge is not drawn (P-23): a line
    // there cut the surplus off the column and made the filler read as an
    // empty column.
    const amount = columns[columns.length - 1]!;
    await expect(amount.dataset.pin).toBe('right');
    await expect(port.hasAttribute('data-overflowing')).toBe(false);
    await expect(getComputedStyle(amount).boxShadow).toBe('none');
    await expect(
      Math.round(
        filler.getBoundingClientRect().left -
          amount.getBoundingClientRect().right,
      ),
    ).toBe(0);

    // And the names survive it. The sort button carries `max-w-full`, which
    // is the cell's content box, while it pulls that padding back out with
    // `-mx-2`: capped there it was 16px short and `订单号` read `订…` — a
    // name the tooltip could give back, from a column that had the room.
    for (const head of columns) {
      const label = head.querySelector<HTMLElement>(
        '[data-slot="column-label"]',
      );
      if (!label) continue;
      await expect(label.scrollWidth).toBeLessThanOrEqual(
        Math.ceil(label.getBoundingClientRect().width),
      );
    }
  },
};

/**
 * 宿主给了定高，工作台就填满它：分页贴在底边，本页／全部合计贴在分页上面，行
 * 少时空白留在表格里（2026-09-23，借鉴 legacy 控制台）。
 *
 * 从前结果区只是「最多长到视口底」：四行数据时合计紧跟第四行、分页紧跟合计，
 * 两个页脚一起浮在半屏处，每换一页位置都不同。这里把工作台放进一个 640px 高的
 * 宿主框里，量三件事：分页的下边就是结果框的下边，合计的下边就是表格滚动区的
 * 下边，结果框的下边就是宿主框底（结果区是贴边的带，穿过工作列的内边距）。
 */
export const FooterStaysAtTheBottom: Story = {
  ...DisplayWithData,
  decorators: [
    Story => (
      <div data-testid="host-frame" style={{ display: 'grid', height: 640 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = (await canvas.findByRole('table')) as HTMLTableElement;
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    const host = canvas.getByTestId('host-frame');
    const frame = canvasElement.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    )!;
    const port = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    const pagination = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    )!;
    const bottom = (element: Element) => element.getBoundingClientRect().bottom;

    // The frame reaches the host's bottom: it is a band that bleeds through
    // the work column's padding, so its bottom is the workbench's own.
    await waitFor(() =>
      expect(Math.abs(bottom(frame) - bottom(host))).toBeLessThanOrEqual(1),
    );
    // The pagination is the frame's last row, at its bottom edge.
    await expect(
      Math.abs(bottom(pagination) - bottom(frame)),
    ).toBeLessThanOrEqual(1);
    // Four rows leave room, and the totals sit at the bottom of the port —
    // beside the pagination — rather than under the last row.
    await waitFor(() =>
      expect(table.querySelector('[data-slot="row-room"]')).not.toBeNull(),
    );
    await expect(
      Math.abs(bottom(table.tFoot!) - bottom(port)),
    ).toBeLessThanOrEqual(1);
    // The room row is not a row: the rows' own body still holds only rows.
    await expect(table.tBodies[0].rows).toHaveLength(PENDING_BY_AMOUNT.length);
  },
};

/**
 * 卡片一张不少地排下来，每张都有它自己那么高。
 *
 * 工作台填满容器之后，卡片区是一个定高的网格；网格按它有的空间分行高而不是按
 * 卡片的内容，而卡片裁掉自己的溢出、最少要的是零——五十张卡在 900px 里被压成
 * 一排排只剩标题的条（用户 2026-09-23 走查发现）。这里切到卡片，五十张：每张
 * 卡的内容都在它自己的框里（没有被裁掉），卡片区自己滚动。
 */
export const CardsKeepTheirHeight: Story = {
  ...DisplayWideTable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.layout.cards'] }),
    );
    const region = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="record-cards"]',
      );
      if (!found) throw new Error('卡片区还没出来');
      return found;
    });
    const cards = await waitFor(() => {
      const found = [
        ...region.querySelectorAll<HTMLElement>('[data-slot="card"]'),
      ];
      expect(found.length).toBeGreaterThan(20);
      return found;
    });
    // Nothing of a card is cut off: what it holds fits the box it has.
    for (const card of cards)
      await expect(card.scrollHeight).toBeLessThanOrEqual(
        card.clientHeight + 1,
      );
    // And more cards than room: the region scrolls rather than squeezing.
    await expect(region.scrollHeight).toBeGreaterThan(region.clientHeight + 1);
  },
};

/**
 * 宿主没给高度，工作台就停在它的保底高度上，页脚照样贴底。
 *
 * 高度布局只有一种（2026-09-23，用户按推荐定）：工作台永远填满容器。容器没有
 * 确定高度时 `h-full` 什么也不是，36rem 的保底（`--fve-workbench-min-height`）
 * 就是它的全部——从前这里是「页面流」：表格按量出来的视窗剩余空间封顶，行少时
 * 分页浮在半屏。这里把工作台放在一个按内容定高的外层里，五十行的宽表：根的高
 * 度正是 36rem，表格在自己的滚动口里滚，两行合计与分页都在根的底边以内，分页
 * 的下边就是根的下边。
 */
export const HeldAtItsFloor: Story = {
  ...DisplayWideTable,
  decorators: [
    Story => (
      <div data-content-sized style={{ alignSelf: 'start' }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = (await canvas.findByRole('table')) as HTMLTableElement;
    await waitFor(() =>
      expect(table.tBodies[0].rows.length).toBeGreaterThan(10),
    );
    const root = canvasElement.querySelector<HTMLElement>(
      '[data-content-sized] > .fve-root',
    )!;
    const port = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    const pagination = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    )!;
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);

    // The floor is the whole of its height, and the rows scroll inside it.
    await waitFor(() =>
      expect(
        Math.abs(root.getBoundingClientRect().height - 36 * rem),
      ).toBeLessThanOrEqual(1),
    );
    await waitFor(() =>
      expect(port.scrollHeight).toBeGreaterThan(port.clientHeight + 1),
    );
    // The footer is at the root's bottom: the pagination's edge is its edge,
    // and both summary rows are inside it.
    const bottom = root.getBoundingClientRect().bottom;
    await expect(
      Math.abs(pagination.getBoundingClientRect().bottom - bottom),
    ).toBeLessThanOrEqual(1);
    for (const row of table.querySelectorAll<HTMLElement>('tfoot tr'))
      await expect(row.getBoundingClientRect().bottom).toBeLessThanOrEqual(
        bottom,
      );
  },
};

/**
 * The view filling the screen, and the page given back.
 *
 * The three things that go wrong here are all asserted rather than looked
 * at: the surface must expand **in place** (the same table node, under the
 * same parent — a portal would remount it and take the draft with it), the
 * document's scrolling must be locked while it is open and handed back
 * exactly as it was, and the table's sticky layers must still hold against
 * whatever actually scrolls, in both states.
 */
export const FillTheScreen: Story = {
  ...DisplayFillTheScreen,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    const parent = surface.parentElement;
    const before = held(doc);

    // Which column is frozen, read off the header that declares it: only the
    // headers carry `data-pin`, and a body cell is sticky one cell at a time
    // — freezing the header and letting the cells under it slide away is
    // worse than not freezing at all, so the cell is what is checked.
    const pinnedHead = table.querySelector<HTMLTableCellElement>(
      'thead th[data-pin-index]',
    )!;
    const cellUnder = (section: string) =>
      table.querySelector<HTMLTableRowElement>(`${section} tr`)!.cells[
        pinnedHead.cellIndex
      ];
    /** The three layers that must not come unstuck, whatever is tall. */
    const sticky = () => ({
      header: getComputedStyle(pinnedHead).position,
      summary: getComputedStyle(table.querySelector('tfoot td')!).position,
      pinned: getComputedStyle(cellUnder('tbody')).position,
    });
    const STUCK = { header: 'sticky', summary: 'sticky', pinned: 'sticky' };
    await expect(sticky()).toEqual(STUCK);
    const before70vh = table
      .closest<HTMLElement>('[data-slot="record-table"]')!
      .getBoundingClientRect();

    const toggle = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view'],
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);

    // Pinned to the viewport, and nowhere else: `position: fixed` is the
    // whole of the visual change, and it is one attribute on the element
    // that was already there.
    await waitFor(() =>
      expect(surface).toHaveAttribute('data-view-expanded', 'true'),
    );
    await expect(getComputedStyle(surface).position).toBe('fixed');
    await expect(surface.parentElement).toBe(parent);
    await expect(canvas.getByRole('table')).toBe(table);
    await expect(onViewport(surface)).toBe(true);
    // The background cannot be scrolled out from under it — and as important
    // as the value, the priority: a host stylesheet's `!important` would
    // otherwise outrank a plain inline declaration and go on scrolling. It is
    // taken on whatever actually scrolls this document (`<html>` here) and
    // one axis at a time, because the shorthand can neither read back nor
    // hand back a page that set only one of them.
    await expect(held(doc)).toEqual(['hidden !important', 'hidden !important']);
    // Expanding changes which box is tall; it must not change what sticks.
    await expect(sticky()).toEqual(STUCK);

    // And the height goes where the expansion was for. The rows are the
    // point of a bigger screen, so the table takes what the header, the
    // strips, the toolbar and the pagination left — not 70vh of it and a
    // blank half-screen underneath.
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await expect(area).toHaveAttribute('data-scrolls');
    await expect(getComputedStyle(area).maxHeight).toBe('none');
    const filled = area.getBoundingClientRect();
    await expect(filled.height).toBeGreaterThan(before70vh.height);
    // Everything still fits inside one screen: the page under it is locked,
    // so anything spilling past the fold would be unreachable.
    await expect(Math.round(filled.bottom)).toBeLessThanOrEqual(
      Math.round(surface.getBoundingClientRect().bottom) + 1,
    );

    // Not a modal, and it says so by omission: nothing here claims one.
    await expect(surface.getAttribute('aria-modal')).toBeNull();
    await expect(surface.getAttribute('role')).toBeNull();
    await expect(doc.body.querySelector('[inert]')).toBeNull();

    // Escape is the way out, and focus comes back to the control that opened
    // it — the key is announced on the button rather than spent on a tooltip.
    const back = canvas.getByRole('button', {
      name: zhCN['label.workbench.collapse-view'],
    });
    await expect(back).toBe(toggle);
    await expect(back).toHaveAttribute('aria-keyshortcuts', 'Escape');
    back.focus();
    await userEvent.keyboard('{Escape}');

    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await expect(doc.activeElement).toBe(toggle);
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    // The page is the page it was, and the table is still stuck together.
    await expect(held(doc)).toEqual(before);
    await expect(sticky()).toEqual(STUCK);
  },
};

/**
 * The overflow the page is actually holding, one axis at a time.
 *
 * Read off whatever scrolls this document — `<html>` in standards mode, which
 * is the element whose overflow the viewport takes; body's reaches it only
 * while html's is `visible`. Both axes and both priorities, because the
 * shorthand can describe neither a host that set one of them nor a host that
 * gave them different priorities.
 */
function held(doc: Document): string[] {
  const style = (
    (doc.scrollingElement as HTMLElement | null) ?? doc.documentElement
  ).style;
  return ['overflow-x', 'overflow-y'].map(name => {
    const priority = style.getPropertyPriority(name);
    return style.getPropertyValue(name) + (priority ? ` !${priority}` : '');
  });
}

/**
 * Whether an element covers the viewport, to the pixel.
 *
 * This is the assertion `position: fixed` cannot be trusted to satisfy on its
 * own: it resolves against the viewport only while no ancestor has made
 * itself the containing block.
 */
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
 * The same expansion inside a host that owns the containing block.
 *
 * `transform` — and `filter`, `perspective`, `backdrop-filter`,
 * `will-change`, `contain`, `container-type` — makes an ancestor the
 * containing block for every `position: fixed` inside it, so "fill the
 * screen" would fill *that container*. Animated panels and GPU-hinted grid
 * shells do it as a matter of course, and an embedded view is meant to sit
 * in an arbitrary host, so this is the case that decides whether the feature
 * works at all outside a plain page.
 *
 * Enumerating the triggers is a list that goes stale with the next CSS
 * module, so the hook measures the box the browser actually gave it: the
 * difference from the viewport *is* the correction. This play is the check
 * that the measurement is real.
 */
export const FillTheScreenInTransformedHost: Story = {
  ...DisplayFillTheScreenInTransformedHost,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const host = canvasElement.querySelector<HTMLElement>(
      '[data-transformed-host]',
    )!;
    const surface = host.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;

    // The premise: this host really is a containing block, and it really is
    // smaller than the screen. Without both, the test proves nothing.
    await expect(getComputedStyle(host).transform).not.toBe('none');
    const hostBox = host.getBoundingClientRect();
    await expect(hostBox.height).toBeLessThan(window.innerHeight);

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-view'],
      }),
    );
    await waitFor(() =>
      expect(surface).toHaveAttribute('data-view-expanded', 'true'),
    );

    // Still in place — the node never moved, which is the point of the whole
    // design — and still on the viewport rather than on its host's box.
    await expect(surface.parentElement).toBe(host);
    await expect(onViewport(surface)).toBe(true);
    await expect(held(doc)).toEqual(['hidden !important', 'hidden !important']);
    // The correction is written back as geometry, not guessed from a list of
    // properties that would go stale.
    await expect(surface.style.getPropertyValue('--_fve-expanded-w')).toBe(
      `${window.innerWidth}px`,
    );

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    // And the host's element is handed back without our arithmetic on it.
    await expect(surface.style.getPropertyValue('--_fve-expanded-w')).toBe('');
    await expect(
      Math.round(surface.getBoundingClientRect().width),
    ).toBeLessThanOrEqual(Math.round(hostBox.width));
  },
};

/**
 * The same correction, through a host that *scales* rather than only moves.
 *
 * `translateZ(0)` above makes an ancestor the containing block without
 * changing any size, so it exercises only half of `transform`. A
 * `scale(.75)` host exercises the other half, and it is the half that reads
 * backwards: `getBoundingClientRect()` already reports screen pixels, while
 * the four `--_fve-expanded-*` are read in the element's own coordinates,
 * where one pixel is `.75` of a screen pixel. Handing the measured difference
 * straight back would leave the surface at three quarters of the screen and
 * still short of the corner — so the ratio between what was asked for and
 * what appeared is measured too, and divided out.
 */
export const FillTheScreenInScaledHost: Story = {
  ...DisplayFillTheScreenInScaledHost,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const host =
      canvasElement.querySelector<HTMLElement>('[data-scaled-host]')!;
    const surface = host.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;

    // The premise: this host really does scale, and by the ratio the story
    // set. Without it the test proves nothing.
    const matrix = new DOMMatrixReadOnly(getComputedStyle(host).transform);
    await expect(matrix.a).toBeCloseTo(0.75, 2);

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-view'],
      }),
    );
    await waitFor(() =>
      expect(surface).toHaveAttribute('data-view-expanded', 'true'),
    );

    // In screen pixels — the only ones a reader has — exactly the viewport.
    await expect(onViewport(surface)).toBe(true);
    // And the written width is *larger* than the viewport by the ratio,
    // which is the whole of the second pass: the naive value would have been
    // the viewport's own width and would have painted three quarters of it.
    const written = Number.parseFloat(
      surface.style.getPropertyValue('--_fve-expanded-w'),
    );
    await expect(written).toBeGreaterThan(window.innerWidth);
    await expect(written * 0.75).toBeCloseTo(window.innerWidth, 0);

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await expect(surface.style.getPropertyValue('--_fve-expanded-w')).toBe('');
  },
};

/**
 * The promise the whole normal-layer decision was made to keep: a popup still
 * opens *in front of* a view that fills the screen.
 *
 * Every popup here is portalled to `document.body` inside a positioner the
 * layout engine gives `transform: translate(...)` — which makes the
 * positioner a stacking context — and the `isolate z-50` on it is a Tailwind
 * utility this package pins to `:where(.fve-root, .fve-root *)`. The popup's
 * *content* carries `fve-root`; the positioner does not, so its `z-50`
 * matches nothing and it stays at `z-index: auto`. Any positive `z-index` on
 * the expanded surface therefore buries every popup in the package, content
 * `z-50` and all, because a `z-index` inside a transformed ancestor cannot
 * escape it. The column-settings and sort popovers did not exist when that
 * decision was written down, so this is the play that holds it.
 *
 * It also holds the sticky layers against a column the *config* froze rather
 * than the row key, which the projection pins left whatever anyone asked for.
 */
export const FillTheScreenWithPopups: Story = {
  ...DisplayFillTheScreenWithPopups,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;

    // The premise: `金额` is frozen because the saved config says so, and it
    // is not the row key.
    const pinned = headerOf(table, '金额');
    await expect(pinned).toHaveAttribute('data-pin', 'left');
    await expect(headerOf(table, '订单号')).toHaveAttribute('data-pin', 'left');

    /** Every layer that must keep holding, whichever box is the tall one. */
    const sticky = () => ({
      header: getComputedStyle(pinned).position,
      summary: getComputedStyle(table.querySelector('tfoot td')!).position,
      cell: getComputedStyle(
        table.querySelector<HTMLTableRowElement>('tbody tr')!.cells[
          pinned.cellIndex
        ],
      ).position,
    });
    const STUCK = { header: 'sticky', summary: 'sticky', cell: 'sticky' };
    await expect(sticky()).toEqual(STUCK);

    const toggle = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view'],
    });
    // Still where the toolbar row puts it, after the editor's fold and before
    // whatever the host adds — #1555 rearranged the row under it, not this.
    const controls = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    await expect(controls.contains(toggle)).toBe(true);
    await expect(
      [
        ...controls.querySelectorAll(
          '[data-slot="editor-toggle"], [data-slot="view-expand"]',
        ),
      ].map(node => node.getAttribute('data-slot')),
    ).toEqual(['editor-toggle', 'view-expand']);

    await userEvent.click(toggle);
    await waitFor(() =>
      expect(surface).toHaveAttribute('data-view-expanded', 'true'),
    );
    await expect(onViewport(surface)).toBe(true);

    // Each popover, opened over the expanded view and found by the only test
    // that matters: what the browser hands back at the middle of its own box.
    for (const trigger of canvasElement.querySelectorAll<HTMLElement>(
      '[data-slot="result-toolbar"] [data-slot="popover-trigger"]',
    )) {
      await userEvent.click(trigger);
      const popup = await waitFor(() => {
        const found = document.body.querySelector<HTMLElement>(
          '[data-slot="popover-content"]',
        );
        if (!found) throw new Error('no popover');
        return found;
      });
      // Portalled out of the surface, which is exactly why this can go wrong.
      await expect(popup.closest('[data-slot="view-surface"]')).toBeNull();
      await expect(inFrontOf(popup)).toBe(true);
      await userEvent.keyboard('{Escape}');
      // The popup took the key, and only the popup.
      await waitFor(() =>
        expect(
          document.body.querySelector('[data-slot="popover-content"]'),
        ).toBeNull(),
      );
      await expect(surface).toHaveAttribute('data-view-expanded', 'true');
    }
    // Level 0 and no higher, which is the whole of the fix — said here as
    // well, so a regression names the cause and not only the symptom.
    await expect(getComputedStyle(surface).zIndex).toBe('0');

    // And the layers still hold once a different box is the tall one — with
    // the scrollport scrolled as far as it goes, in both directions, so this
    // is what the rows actually do and not only what the rule says.
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    const port = table.parentElement!;
    port.scrollTop = port.scrollHeight;
    port.scrollLeft = port.scrollWidth;
    await expect(area).toHaveAttribute('data-scrolls');
    await expect(sticky()).toEqual(STUCK);
    // The frozen header stays inside the scrollport's own left edge rather
    // than riding away with the columns beside it.
    await expect(
      Math.round(pinned.getBoundingClientRect().left),
    ).toBeGreaterThanOrEqual(Math.round(port.getBoundingClientRect().left) - 1);

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await expect(sticky()).toEqual(STUCK);
  },
};

/**
 * Every element under this one, so nothing can hang off the surface unseen.
 *
 * A `scrollWidth` on the column says *that* something overflows; the walk
 * says *what*, which is the difference between a failure that can be fixed
 * and a failure that has to be hunted.
 */
function descendantsOf(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>('*')].filter(
    element => element.getBoundingClientRect().width > 0,
  );
}

/** How many lines these boxes are laid out on, by where their tops are. */
const lineCount = (boxes: HTMLElement[]) =>
  new Set(boxes.map(node => Math.round(node.getBoundingClientRect().top))).size;

/** The three groups on the right, which wrap as one block. */
function arrangementGroups(canvasElement: HTMLElement): HTMLElement[] {
  const right = canvasElement.querySelector<HTMLElement>(
    '[data-slot="toolbar-arrangement"]',
  )!;
  return [...right.children] as HTMLElement[];
}

/** Every group in the bar: the selection when there is one, then the three. */
function toolbarGroups(canvasElement: HTMLElement): HTMLElement[] {
  const selection = canvasElement.querySelector<HTMLElement>(
    '[data-slot="toolbar-selection"]',
  );
  return [
    ...(selection ? [selection] : []),
    ...arrangementGroups(canvasElement),
  ];
}

/**
 * The right-hand block of the toolbar ends exactly where the toolbar's
 * content does — inside the padding the result frame gives its first row.
 */
function rightGroupEndsTheBar(canvasElement: HTMLElement): number {
  const toolbar = canvasElement.querySelector<HTMLElement>(
    '[data-slot="result-toolbar"]',
  )!;
  const right = canvasElement.querySelector<HTMLElement>(
    '[data-slot="toolbar-arrangement"]',
  )!;
  const edge =
    toolbar.getBoundingClientRect().right -
    parseFloat(getComputedStyle(toolbar).paddingRight);
  return Math.abs(right.getBoundingClientRect().right - edge);
}

/**
 * The whole shell inside a phone, with nothing hanging off the side of it.
 *
 * Measured at 341px of root and 309px of result card, four things were
 * painted outside the column they belong to: the pagination row could not
 * wrap, so Next's right edge was 365.6 against a card ending at 342, and
 * "4 records in all" broke over three lines with the Chinese "共 4 条记录"
 * split mid-word; the condition band's `minmax(20rem, 1fr)` pinned every
 * track to 320px, so a pill ended at 366 against an editor band ending at
 * 342; and the toolbar's selection group could not wrap, so the host's bulk
 * action hung off the end of it.
 *
 * The assertion is the general one rather than four specific ones: the main
 * column and the result block scroll no wider than they are, and nothing
 * under the surface ends past the surface's own right edge. The title bar is
 * the one part allowed an honest overflow once it runs out of irreducible
 * room — at this width it has not, because the audience tag is down to its
 * icon and Save to its own — so it is walked here like everything else.
 */
export const NarrowColumnHoldsTheWidth: Story = {
  ...DisplayNarrowTitleBar,
  args: { ...DisplayNarrowTitleBar.args, withActions: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The conditions have to be on screen to overflow: the band is folded on
    // a saved view, and the grid that pinned its tracks is inside it.
    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="filter-conditions"]'),
      ).not.toBeNull(),
    );

    // And the toolbar has to be carrying its heaviest row: a count, a way to
    // drop the selection, and the host's bulk action.
    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await canvas.findByRole('button', { name: '导出所选' });

    const surface = canvasElement.querySelector<HTMLElement>('.fve-root')!;
    const main = canvasElement.querySelector<HTMLElement>('main')!;
    const result = canvasElement.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    )!;

    await expect(main.scrollWidth).toBeLessThanOrEqual(main.clientWidth);
    await expect(result.scrollWidth).toBeLessThanOrEqual(result.clientWidth);

    // The table is the one thing allowed to scroll sideways — that is what a
    // frozen column is for — so it answers for where its own box ends and
    // the rows inside it are not walked into.
    const edge = surface.getBoundingClientRect().right;
    const spilling = descendantsOf(main)
      .filter(node => node.closest('[data-slot="record-table"]') === null)
      .filter(node => node.getBoundingClientRect().right > edge + 1)
      .map(
        node =>
          `${node.getAttribute('data-slot') ?? node.tagName} ends at ` +
          `${Math.round(node.getBoundingClientRect().right)} of ${Math.round(edge)}`,
      );
    await expect(spilling).toEqual([]);
  },
};

/**
 * The result toolbar wraps as groups, not as a spill.
 *
 * It used to be `[selection][flex-1 spacer][layout][columns/sort][refresh]`,
 * and a spacer is the worst thing to wrap around: it took a line of its own
 * width, stranded the layout switch alone at the right of the first line,
 * dropped the arrange group to the left of the second and the refresh split
 * button to a third — 2 lines at 1280 with a selection, 3 at 768 and 3 at
 * 375, where it stood 104px tall; with nothing selected the placeholder box
 * held 32px of nothing.
 *
 * The three right-hand groups are one block now. With nothing selected the
 * bar is at most two lines at both widths, and at 768 it is one line even
 * with four rows picked. At 375 with a selection it is still three, and
 * honestly so: the three groups measure 112 + 167 + 111 with two 8px gaps,
 * and 406px does not go into a 317px bar however it wraps. Closing that
 * last line would mean taking the words off the toolbar's controls — the
 * sort button's summary among them, which `ui/record.md` says has to be
 * readable where it stands. What the block may not do, and did, is
 * scatter.
 */
export const ToolbarWrapsAsGroups: Story = {
  ...DisplayNarrowTitleBar,
  // The list is folded by the host, not by the shell's own measurement: the
  // widths below are the *toolbar's* subject, and a shell that follows the
  // column would put a 224px list back at 768 and take it away again a
  // frame later, so every number here would be measuring the sidebar.
  args: { ...DisplayNarrowTitleBar.args, withActions: true, collapsed: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;

    // Nothing selected: no placeholder box, and the block still ends the bar.
    for (const width of [768, 375]) {
      host.style.width = `${width}px`;
      await expect(
        canvasElement.querySelector('[data-slot="toolbar-selection"]'),
      ).toBeNull();
      await expect(
        lineCount(toolbarGroups(canvasElement)),
        `${width}px, nothing selected`,
      ).toBeLessThanOrEqual(2);
      await expect(rightGroupEndsTheBar(canvasElement)).toBeLessThanOrEqual(1);
    }

    // And with a selection. At 768 the whole bar is one line; at 375 the
    // selection takes the first and the three groups wrap between the two
    // below it, because they measure 112 + 167 + 111 with two 8px gaps in a
    // 317px bar and no amount of wrapping fits 406 into 317. What they may
    // not do — and what they did — is scatter: the block stays a block, it
    // never takes more than two lines of its own, and it ends the bar.
    host.style.width = '768px';
    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await canvas.findByRole('button', { name: '导出所选' });

    await expect(
      lineCount(toolbarGroups(canvasElement)),
      '768px, four rows selected',
    ).toBeLessThanOrEqual(2);

    for (const width of [768, 375]) {
      host.style.width = `${width}px`;
      await expect(
        lineCount(arrangementGroups(canvasElement)),
        `${width}px, four rows selected`,
      ).toBeLessThanOrEqual(2);
      await expect(rightGroupEndsTheBar(canvasElement)).toBeLessThanOrEqual(1);
    }
    host.style.width = '375px';
  },
};
