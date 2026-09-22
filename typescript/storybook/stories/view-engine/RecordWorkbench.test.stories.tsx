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
import {
  defaultMessages,
  formatMessage,
  zhCN,
} from '@ahoo-wang/fetcher-view-engine/ui';
import type { RecordViewConfig } from '@ahoo-wang/fetcher-view-engine';
import displayMeta, {
  AutoRefresh as DisplayAutoRefresh,
  CannotOpen as DisplayCannotOpen,
  CellFamily as DisplayCellFamily,
  CollapsedSidebar as DisplayCollapsedSidebar,
  DeleteConflicted as DisplayDeleteConflicted,
  EarliestAndLatest as DisplayEarliestAndLatest,
  EmptyResult as DisplayEmptyResult,
  English as DisplayEnglish,
  ExportCapped as DisplayExportCapped,
  ExportFailed as DisplayExportFailed,
  ExportResult as DisplayExportResult,
  ExportRunning as DisplayExportRunning,
  FillTheScreen as DisplayFillTheScreen,
  FillTheScreenInScaledHost as DisplayFillTheScreenInScaledHost,
  FillTheScreenInTransformedHost as DisplayFillTheScreenInTransformedHost,
  FillTheScreenWithPopups as DisplayFillTheScreenWithPopups,
  Loading as DisplayLoading,
  ManageViews as DisplayManageViews,
  NarrowTitleBar as DisplayNarrowTitleBar,
  NeedsFixing as DisplayNeedsFixing,
  NoViews as DisplayNoViews,
  Opening as DisplayOpening,
  Paged as DisplayPaged,
  PinnedEdges as DisplayPinnedEdges,
  PinnedGroupCapped as DisplayPinnedGroupCapped,
  RenderFailure as DisplayRenderFailure,
  PopupsOverRaisedHostLayer as DisplayPopupsOverRaisedHostLayer,
  QueryFailed as DisplayQueryFailed,
  RenameConflicted as DisplayRenameConflicted,
  SaveConflicted as DisplaySaveConflicted,
  SaveRefused as DisplaySaveRefused,
  SaveResultUnknown as DisplaySaveResultUnknown,
  TableSettings as DisplayTableSettings,
  TotalCoversThisPageOnly as DisplayTotalCoversThisPageOnly,
  WideTable as DisplayWideTable,
  WithActions as DisplayWithActions,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import {
  measureBorderContrast,
  measureFillContrast,
  measureLayerSeparation,
  measureTextContrast,
} from './contrast.js';
import { tableSettingsStore } from './fixtures.js';
import { outcomesStore } from './outcomesStore.js';
import { dragEdgeBy, dragHandleOnto } from './pointerDrag.js';
import {
  amountOf,
  readColumn,
  readHeaders,
  readPage,
  readTotal,
} from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/Record 工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The shared view's condition and sort, as the source answered them. */
const PENDING_BY_AMOUNT = ['SO-1003', 'SO-1005', 'SO-1001', 'SO-1006'];

/** One catalogue sentence with its numbers filled in, as the bar writes it. */
const say = (key: string, params: Record<string, string | number>) =>
  formatMessage(zhCN, key, params);

/** The bar under the rows. */
const paginationBar = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('[data-slot="record-pagination"]')!;

/**
 * 正文对比度的下限：WCAG 1.4.3 的 4.5:1。
 *
 * 状态条的句子、表头的列名、徽章上的状态词，都是正文大小或更小的文字——不是
 * 大字号，也不是装饰，所以三处守的是同一个数，写在一处。
 */
const TEXT_CONTRAST = 4.5;

export const WithData: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    // Two scopes, side by side: the total covers what the conditions select
    // rather than every order, and the page covers the four rows on screen —
    // here the same four, which is exactly what the labels let a reader tell.
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
    await expect(amountOf(readPage(table, '金额'))).toBe(6470);
    await expect(scopeLabels(table)).toEqual([
      zhCN['label.summary.scope.page'],
      zhCN['label.summary.scope.total'],
    ]);
    // The function is named rather than left as the config's token.
    await expect(readTotal(table, '金额')).toContain(
      zhCN['label.summary.fn.SUM'],
    );

    // A status is one of a set the definition names, so it reads as a badge
    // with the option's label rather than as the stored `PENDING`.
    await expect(badgeIn(table, '状态')).toHaveTextContent('待出库');
    // The number beside it is not a set, and wears no pill.
    await expect(badgeIn(table, '金额')).toBeNull();

    // The saved view orders by amount; Shift-clicking another sortable
    // header adds it (a plain click would sort by it alone), and each header
    // then says where it sits in that order.
    await expect(headerOf(table, '金额')).toHaveAttribute(
      'aria-sort',
      'descending',
    );
    // A sortable column nobody sorted offers the affordance before it is
    // used, and says what a click would do.
    await expect(
      headerOf(table, '订单号').querySelector('[data-slot="sort-available"]'),
    ).not.toBeNull();
    // Nothing else claims to be sorted: ARIA marks the column the table is
    // ordered by, and there is one of those.
    await expect(headerOf(table, '订单号')).not.toHaveAttribute('aria-sort');

    await addSort(table, '订单号');
    await waitFor(() => expect(positionOf(table, '订单号')).toBe('2'));
    // Amount still decides, so it keeps the attribute and the first place;
    // the column that breaks its ties says where it sits in its own name.
    await expect(positionOf(table, '金额')).toBe('1');
    await expect(headerOf(table, '金额')).toHaveAttribute(
      'aria-sort',
      'descending',
    );
    await expect(headerOf(table, '订单号')).not.toHaveAttribute('aria-sort');
    await expect(
      headerOf(table, '订单号')
        .querySelector('button')!
        .getAttribute('aria-label'),
    ).toContain(say('label.sort.at', { position: 2, count: 2 }));
    // Amount still decides, and the second column only breaks its ties, so
    // the rows are where they were.
    await expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT);

    // The page reads from what the view is down to the rows and their
    // paging. A saved view opens folded, and folding unmounts the band
    // rather than hiding it, so it is not in this list yet.
    await expect(slots(canvasElement)).toEqual([
      'view-header',
      'applied-bar',
      'result-toolbar',
      'record-pagination',
    ]);

    // The title bar names the view and says it is shared, without repeating
    // either in the save button.
    const header = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-header"]',
    );
    await expect(header).toHaveTextContent('待出库订单');
    await expect(header).toHaveTextContent(zhCN['label.scope.tag.shared']);

    // A saved view opens folded, and the bar above the rows says what they
    // were fetched under rather than what the editor now holds.
    const band = canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    });
    await expect(band).toHaveAttribute('aria-expanded', 'false');
    await expect(
      canvas.getByRole('region', {
        name: zhCN['label.applied.title'],
      }),
    ).toHaveTextContent('待出库');

    // Paging is under the rows it pages, not in the toolbar, and it counts
    // what the conditions select rather than what fitted on the screen.
    const paging = paginationBar(canvasElement);
    await expect(paging).toHaveTextContent(
      say('label.pagination.total', { total: 4 }),
    );
    // Four rows at twenty a page is the whole of it, said as such — and
    // with no arrows at all (D12 Ⅶ): two dead ones were the same fact in a
    // form that still cost two tab stops to read.
    await expect(paging).toHaveTextContent(
      say('label.toolbar.page-of', { index: 1, pages: 1 }),
    );
    await expect(
      within(paging).queryByRole('button', {
        name: zhCN['label.toolbar.next'],
      }),
    ).toBeNull();
    await expect(
      within(paging).queryByRole('button', {
        name: zhCN['label.toolbar.previous'],
      }),
    ).toBeNull();
    // The size control stays: how many rows a page holds is what makes it
    // one page, and it is the one thing still worth changing here.
    await expect(
      within(paging).getByRole('combobox', {
        name: zhCN['label.pagination.page-size'],
      }),
    ).toBeVisible();

    // Opening the fold brings the editor back, in its own block between the
    // title bar and the result, with the one way out of it.
    await userEvent.click(band);
    await expect(
      await canvas.findByRole('button', {
        name: zhCN['label.filter.apply'],
      }),
    ).toBeVisible();
    await expect(slots(canvasElement)).toEqual([
      'view-header',
      'editor-band',
      'applied-bar',
      'result-toolbar',
      'record-pagination',
    ]);
  },
};

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
 * Every reading a definition can declare, in a real browser.
 *
 * The three that jsdom cannot answer for are here: whether a link really
 * carries the two attributes that keep the opened document from reaching
 * back, whether the tone reaches the badge as a variant rather than only as
 * an attribute, and whether an undeclared column is still exactly what it
 * always was.
 */
export const CellFamily: Story = {
  ...DisplayCellFamily,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));

    // A status is one badge in the tone its own option declares. The colour
    // is never the only difference — the label says which status this is.
    const pending = badgeIn(table, '状态')!;
    await expect(pending).toHaveTextContent('待出库');
    await expect(pending).toHaveAttribute('data-tone', 'warning');
    const cancelled = cellAt(table, '状态', 1).querySelector(
      '[data-slot="badge"]',
    )!;
    await expect(cancelled).toHaveTextContent('已取消');
    await expect(cancelled).toHaveAttribute('data-tone', 'danger');
    // Danger is the one tone the registry itself has a variant for.
    await expect(cancelled).toHaveAttribute('data-variant', 'destructive');

    // A list is one badge per entry, and an entry the options stopped
    // naming shows the code it came as rather than disappearing.
    await expect(badgeTexts(cellAt(table, '标记', 0))).toEqual([
      '加急',
      '易碎',
    ]);
    await expect(badgeTexts(cellAt(table, '标记', 5))).toEqual(['vip']);

    // A URL is a link out of the application, so the document it opens must
    // not reach back through `window.opener` nor arrive knowing where from.
    const link = cellAt(table, '运单', 0).querySelector('a')!;
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    await expect(link).toHaveAccessibleName(
      'https://example.com/track/SO-1001',
    );
    // And a scheme the content rules refuse is text, never a live link.
    await expect(cellAt(table, '运单', 4).querySelector('a')).toBeNull();
    await expect(cellAt(table, '运单', 4)).toHaveTextContent(
      'javascript:alert(1)',
    );

    // A note is clamped to a few lines with the whole of it one hover away,
    // which is the one assertion that needs a browser to lay the box out.
    const note = cellAt(table, '备注', 4).querySelector<HTMLElement>(
      '[data-slot="cell-text"]',
    )!;
    await expect(note).toHaveAttribute('title', note.textContent!);
    await expect(getComputedStyle(note).webkitLineClamp).toBe('3');
    await expect(note.scrollHeight).toBeGreaterThan(note.clientHeight);

    // And the column that declared nothing is what it always was: text, with
    // no pill around it and nothing to click.
    const key = cellAt(table, '订单号', 0);
    await expect(key).toHaveTextContent('SO-1001');
    await expect(key.querySelector('[data-slot="badge"]')).toBeNull();
    await expect(key.querySelector('a')).toBeNull();
  },
};

/**
 * A date column's earliest and latest, in the footer.
 *
 * Three things have to hold at once and only a browser shows all three: the
 * word is the one a moment takes (「最早」, never 「最小」), the value goes
 * through the same reading the cells above it go through — the surface's
 * language and the engine's zone, so neither thirteen digits nor a raw ISO
 * string reaches the screen — and the money in the same row is still a sum
 * with its currency, so one reading has not swallowed the other.
 */
export const EarliestAndLatest: Story = {
  ...DisplayEarliestAndLatest,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // No condition, so all six orders are on screen — the seventh is
    // soft-deleted and a Wow source does not answer it.
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));

    // They were created between these two moments, read as the column reads
    // a cell: the surface's language, the engine's zone.
    const shown = (iso: string) =>
      new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium',
        timeStyle: 'medium',
      }).format(new Date(iso));
    const earliest = shown('2026-09-15T02:10:00.000Z');
    const latest = shown('2026-09-17T08:45:00.000Z');

    for (const read of [readTotal, readPage]) {
      const cell = read(table, '创建时间');
      await expect(cell).toContain(zhCN['label.summary.fn.date.MIN']);
      await expect(cell).toContain(zhCN['label.summary.fn.date.MAX']);
      await expect(cell).toContain(earliest);
      await expect(cell).toContain(latest);
      // Not the stored value, and not the number it was compared as.
      await expect(cell).not.toContain('2026-09-15T02:10');
      await expect(cell).not.toContain(zhCN['label.summary.fn.MIN']);
      // The money beside it is unchanged: a sum, in its own format.
      await expect(amountOf(read(table, '金额'))).toBe(10230);
      await expect(read(table, '金额')).toContain(zhCN['label.summary.fn.SUM']);
    }
  },
};

/**
 * The bar under the rows, on a result that has somewhere to go.
 *
 * It is one row: how many records there are in all on the left, and on the
 * right how many are shown per page, which page this is, and the two steps
 * out of it. `WithData` above only ever sees the single-page form of it.
 */
export const Paged: Story = {
  ...DisplayPaged,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1002']),
    );

    const bar = paginationBar(canvasElement);
    // The total is every order the conditions select, not the two on screen.
    await expect(bar).toHaveTextContent(
      say('label.pagination.total', { total: 6 }),
    );
    await expect(bar).toHaveTextContent(
      say('label.toolbar.page-of', { index: 1, pages: 3 }),
    );
    // The size in force is offered back with its unit attached.
    await expect(
      within(bar).getByRole('combobox', {
        name: zhCN['label.pagination.page-size'],
      }),
    ).toHaveTextContent(say('label.pagination.page-size-option', { size: 2 }));

    // Page one has nowhere to go back to.
    const previous = within(bar).getByRole('button', {
      name: zhCN['label.toolbar.previous'],
    });
    const next = within(bar).getByRole('button', {
      name: zhCN['label.toolbar.next'],
    });
    await expect(previous).toBeDisabled();

    // Forward: new rows, a new page sentence, the same total.
    await userEvent.click(next);
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1003', 'SO-1004']),
    );
    await expect(paginationBar(canvasElement)).toHaveTextContent(
      say('label.toolbar.page-of', { index: 2, pages: 3 }),
    );
    await expect(paginationBar(canvasElement)).toHaveTextContent(
      say('label.pagination.total', { total: 6 }),
    );

    // And back again, which is now open.
    await expect(
      within(paginationBar(canvasElement)).getByRole('button', {
        name: zhCN['label.toolbar.previous'],
      }),
    ).toBeEnabled();
    await userEvent.click(
      within(paginationBar(canvasElement)).getByRole('button', {
        name: zhCN['label.toolbar.previous'],
      }),
    );
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1002']),
    );
    await expect(paginationBar(canvasElement)).toHaveTextContent(
      say('label.toolbar.page-of', { index: 1, pages: 3 }),
    );

    // And the page said outright rather than stepped to (D18 ruling Ⅷ). The
    // box carries the page that landed, so typing over it is a jump from
    // where the reader is; past the end it is clamped rather than refused.
    const goTo = within(paginationBar(canvasElement)).getByRole('textbox', {
      name: zhCN['label.pagination.go-to'],
    });
    await expect(goTo).toHaveValue('1');
    await userEvent.clear(goTo);
    await userEvent.type(goTo, '3{Enter}');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1005', 'SO-1006']),
    );
    await expect(paginationBar(canvasElement)).toHaveTextContent(
      say('label.toolbar.page-of', { index: 3, pages: 3 }),
    );

    const past = within(paginationBar(canvasElement)).getByRole('textbox', {
      name: zhCN['label.pagination.go-to'],
    });
    await userEvent.clear(past);
    await userEvent.type(past, '99{Enter}');
    await expect(past).toHaveValue('3');
    await expect(paginationBar(canvasElement)).toHaveTextContent(
      say('label.toolbar.page-of', { index: 3, pages: 3 }),
    );
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

    // D13's right edge stays on the last *data* column, which is where the
    // columns end; past it there is nothing rather than more table.
    const amount = columns[columns.length - 1]!;
    await expect(amount.dataset.pin).toBe('right');
    await expect(getComputedStyle(amount).boxShadow).toContain('inset');
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
 * P-22: the table's scroll port ends where the viewport does, so the two
 * summary rows and the pagination row are in view whenever there are more
 * rows than room — no matter how much title bar, tray and toolbar stand
 * above the table. Fifty rows in a wide view, and the frame's bottom edge is
 * the window's.
 */
export const SummariesStayInView: Story = {
  ...DisplayWideTable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(10),
    );

    const port = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    const frame = port.closest<HTMLElement>('[data-slot="result-block"]')!;
    // More rows than room: the port really scrolls.
    await waitFor(() =>
      expect(port.scrollHeight).toBeGreaterThan(port.clientHeight + 1),
    );
    // The frame ends at the window's bottom edge, give or take a pixel, so
    // nothing of it is below the fold.
    await waitFor(() =>
      expect(
        Math.abs(frame.getBoundingClientRect().bottom - window.innerHeight),
      ).toBeLessThanOrEqual(2),
    );
    // Both summary rows and the pagination row are inside the window.
    for (const row of table.querySelectorAll<HTMLElement>('tfoot tr')) {
      const rect = row.getBoundingClientRect();
      await expect(rect.bottom).toBeLessThanOrEqual(window.innerHeight);
      await expect(rect.top).toBeGreaterThanOrEqual(0);
    }
    const pagination = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    )!;
    await expect(pagination.getBoundingClientRect().bottom).toBeLessThanOrEqual(
      window.innerHeight,
    );
  },
};

/**
 * The host's three slots, each where it belongs: over the view, over a
 * selection, and on one row. The middle one exists only while rows are picked.
 */
export const WithActions: Story = {
  ...DisplayWithActions,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    await expect(
      within(
        canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]')!,
      ).getByRole('button', { name: '新建订单' }),
    ).toBeVisible();
    await expect(canvas.queryByRole('button', { name: '导出所选' })).toBeNull();

    // The line between this package's controls and the host's own action
    // stands centred on the row it parts — not parked at its top, which is
    // where a stretched item with a height of its own ends up.
    const controls = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    const divider = controls.querySelector<HTMLElement>(
      '[data-slot="separator"]',
    )!;
    const middle = (rect: DOMRect) => rect.top + rect.height / 2;
    await expect(
      Math.abs(
        middle(divider.getBoundingClientRect()) -
          middle(controls.getBoundingClientRect()),
      ),
    ).toBeLessThanOrEqual(1);

    // One row action per row, in a column pinned to the end of the table.
    await expect(canvas.getAllByRole('button', { name: '打开' })).toHaveLength(
      PENDING_BY_AMOUNT.length,
    );
    await expect(
      canvas.getByRole('columnheader', {
        name: zhCN['label.toolbar.actions'],
      }).className,
    ).toContain('sticky');

    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await expect(
      await canvas.findByRole('button', { name: '导出所选' }),
    ).toBeVisible();
  },
};

/**
 * 批量命令跑完之后那一条：它说清结果，并且活得比它作用的那份选择久。
 *
 * 宿主只写了命令与按钮，剩下的（在途、结局、刷新、选择怎么办）都来自
 * `useBulkCommand` 与 `BulkOutcomeStrip`。这一条待出库的订单里没有已取消的，
 * 所以读到的是「全做完」那一档；清掉选择之后工具栏左端的徽章与 bulk 槽位一起
 * 收走，而结局条还在——它画在工作台旁边，正是为了这一刻。
 */
export const BulkOutcomeOutlivesTheSelection: Story = {
  ...DisplayWithActions,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: '导出所选' }),
    );

    // While it runs the button is disabled, so a second press cannot send a
    // second write over the same rows.
    await expect(
      canvas.getByRole('button', { name: '导出所选' }),
    ).toBeDisabled();

    const outcome = await waitFor(() => {
      const line = canvasElement.querySelector<HTMLElement>(
        '[data-slot="bulk-outcome"]',
      );
      expect(line).not.toBeNull();
      return line!;
    });
    // A run everything took is a note, not an interruption.
    await expect(outcome).toHaveAttribute('role', 'status');
    await expect(outcome).toHaveAttribute('data-tone', 'info');
    await expect(outcome).toHaveTextContent(
      say('label.bulk.done', { done: PENDING_BY_AMOUNT.length }),
    );

    // The selection it acted on is gone with it — and the line is not.
    await expect(canvas.queryByRole('button', { name: '导出所选' })).toBeNull();

    // Nothing expires on its own; the one way out is the button on the line.
    await userEvent.click(
      within(outcome).getByRole('button', {
        name: zhCN['label.bulk.dismiss'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="bulk-outcome"]'),
      ).toBeNull(),
    );
  },
};

/**
 * 一屏只有一个 primary，它是跑查询的那个 Apply——宿主的全局动作不是（D12 Ⅰ）。
 *
 * D12 Ⅰ 原本写的是「宿主的主功能按钮，同屏唯一 primary」，而[动作槽位](
 * packages/view-engine/docs/design/ui/README.md)一直写着相反的规矩：编辑带一
 * 展开，屏幕上就有两个 primary。2026-09-21 用户裁定了后者——排在最右说的是
 * 「这是业务的去处」，不是「这是这一屏最该按的东西」。
 *
 * 这里不认类名认颜色：把编辑带打开，量遍这一屏上每一颗按钮的实际底色，与
 * Apply 同色的应当只有 Apply 自己。jsdom 不套样式表，这个数只有真浏览器给得
 * 出（`test/analysisUi.test.tsx` 按 variant 的类名钉的是结构那一半）。
 */
export const OnlyApplyIsPrimary: Story = {
  ...DisplayWithActions,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const host = canvas.getByRole('button', { name: '新建订单' });
    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const apply = await canvas.findByRole('button', {
      name: zhCN['label.filter.apply'],
    });
    // The vendored button has `transition-all`, so the steady colour is what
    // is read — and the band has just opened.
    await settled(() => getComputedStyle(apply).backgroundColor);
    const primary = getComputedStyle(apply).backgroundColor;

    const alike = [...canvasElement.querySelectorAll<HTMLElement>('button')]
      .filter(button => getComputedStyle(button).backgroundColor === primary)
      .map(button => button.textContent?.trim());

    await expect(alike, `painted ${primary}`).toEqual([
      zhCN['label.filter.apply'],
    ]);
    // And the host's own action is drawn as an outline button: a fill it
    // shares with the surface behind it, and an edge of its own.
    const drawn = getComputedStyle(host);
    await expect(drawn.backgroundColor).not.toBe(primary);
    await expect(drawn.borderTopWidth).not.toBe('0px');
  },
};

/**
 * Renaming, deleting, reordering and the default view: all of it about the
 * list rather than about the view on screen, so all of it in one dialog
 * behind the sidebar's gear.
 */
export const ManageViews: Story = {
  ...DisplayManageViews,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.manage.open'],
      }),
    );
    // The dialog portals out of the canvas, so it is found on the document.
    await within(document.body).findByRole('dialog');
    const row = (title: string) => {
      const found = [
        ...document.querySelectorAll<HTMLElement>(
          '[data-slot="view-manager-row"]',
        ),
      ].find(
        candidate =>
          candidate.textContent?.includes(title) ||
          [...candidate.querySelectorAll('input')].some(field =>
            field.value.includes(title),
          ),
      );
      if (!found) throw new Error(`no row for ${title}`);
      return found;
    };

    // Every record view of the definition is here, grouped as the sidebar
    // groups them; a system view ships with the definition, so it cannot be
    // deleted. The dialog fades in, so the rows are awaited rather than read
    // at once.
    await waitFor(() => expect(row('待出库订单')).toBeDefined());
    // The definition also holds an analysis view. This page cannot draw one,
    // so it neither lists it nor lets this dialog reorder it away.
    await expect(() => row('仓库金额分布')).toThrow();
    await expect(
      within(row('全部订单')).queryByRole('button', {
        name: zhCN['label.manage.delete'],
      }),
    ).toBeNull();

    // A column of icons looks like a column, so the same action has to sit at
    // the same x on every row. The cluster used to be sized to its contents
    // and pushed right, and rows do not all carry the same actions: the
    // system row's icons landed under the other rows' last ones, so each of
    // them sat exactly where a different action sits above it. It is
    // left-aligned in a slot as wide as the fullest row now — and the missing
    // actions are still missing rather than drawn greyed out, which is why
    // this is worth measuring at all.
    const drift = new Map<string, number[]>();
    for (const managed of document.querySelectorAll<HTMLElement>(
      '[data-slot="view-manager-row"]',
    ))
      for (const button of managed.querySelectorAll<HTMLElement>(
        '[data-slot="view-manager-actions"] button',
      )) {
        const name = button.getAttribute('aria-label') ?? '';
        drift.set(name, [
          ...(drift.get(name) ?? []),
          Math.round(button.getBoundingClientRect().x),
        ]);
      }
    // Shared actions only: one row's own button has nothing to line up with.
    const shared = [...drift].filter(([, xs]) => xs.length > 1);
    await expect(shared.filter(([, xs]) => new Set(xs).size > 1)).toEqual([]);
    // And the check is not vacuous: the rows really do differ in what they
    // carry, which is the only reason any of them could drift.
    await expect(
      shared.map(([name]) => name).includes(zhCN['label.manage.set-default']),
    ).toBe(true);
    await expect(drift.get(zhCN['label.manage.delete'])!.length).toBeLessThan(
      drift.get(zhCN['label.manage.set-default'])!.length,
    );

    // The handle is the other end of the row and lines up the same way: it
    // leads every row, because a list that is dragged says so before it is
    // read. It is a list-wide permission, so either every row has one or
    // none does.
    // The handle's name is the catalogue's, so the selector is built from
    // the part of it that comes before the title.
    const DRAG = zhCN['label.manage.drag'].split('{title}')[0];
    const handles = [
      ...document.querySelectorAll<HTMLElement>(
        `[data-slot="view-manager-row"] button[aria-label^="${DRAG}"]`,
      ),
    ];
    await expect(handles).toHaveLength(3);
    await expect(
      new Set(handles.map(grip => Math.round(grip.getBoundingClientRect().x)))
        .size,
    ).toBe(1);

    // Renaming happens in the row, and the list follows it.
    await userEvent.click(
      within(row('我盯的大额单')).getByRole('button', {
        name: zhCN['label.manage.rename'],
      }),
    );
    // The field is named after the view it renames, which is what tells one
    // row's field from the next one's.
    const title = within(row('我盯的大额单')).getByLabelText(
      say('label.manage.rename-of', { title: '我盯的大额单' }),
    );
    await userEvent.clear(title);
    await userEvent.type(title, '大额单');
    await userEvent.click(
      within(row('大额单')).getByRole('button', {
        name: zhCN['label.manage.rename-confirm'],
      }),
    );
    await waitFor(() => expect(row('大额单').textContent).toContain('大额单'));

    // Which view opens first is the list's to choose, and it is marked where
    // it is set.
    await userEvent.click(
      within(row('待出库订单')).getByRole('button', {
        name: zhCN['label.manage.set-default'],
      }),
    );
    await waitFor(() =>
      expect(row('待出库订单')).toHaveTextContent(zhCN['label.manage.default']),
    );
    // Said on the star itself and not only in the badge beside the title:
    // the attribute was there and nothing was drawn from it, so pressing the
    // button changed nothing the button itself showed.
    await expect(
      within(row('待出库订单'))
        .getByRole('button', {
          name: zhCN['label.manage.unset-default'],
        })
        .querySelector('svg')!.classList,
    ).toContain('fill-current');
    await expect(
      within(row('全部订单'))
        .getByRole('button', {
          name: zhCN['label.manage.set-default'],
        })
        .querySelector('svg')!.classList,
    ).not.toContain('fill-current');

    // And the order is the user's: a row is carried by its handle rather
    // than clicked up one step at a time. This is the half jsdom cannot
    // run — `@dnd-kit/dom` picks its drop target by measuring boxes, and
    // every box there is 0×0 at the origin (see pointerDrag.ts).
    const SHARED = ['全部订单', '待出库订单'];
    const listed = (audience: string) =>
      [
        ...document.querySelectorAll<HTMLElement>(
          `[data-slot="view-manager-group"][data-audience="${audience}"] [data-slot="view-manager-row"]`,
        ),
      ].map(managed => managed.textContent ?? '');
    const order = () =>
      listed('shared').map(
        text => SHARED.find(title => text.includes(title)) ?? text,
      );
    const before = order();
    await expect(before).toHaveLength(2);

    await dragHandleOnto(
      within(row(before[1])).getByRole('button', {
        name: say('label.manage.drag', { title: before[1] }),
      }),
      row(before[0]),
    );

    // The shared group reordered, and the personal one did not: the two
    // audiences are two sortable lists, so nothing can be carried across the
    // line between them.
    await waitFor(() => expect(order()).toEqual([before[1], before[0]]));
    await expect(listed('personal')).toHaveLength(1);
    await expect(listed('personal')[0]).toContain('大额单');

    // Deleting asks first, and says what it costs — then the scene backs out
    // of it, because nothing here is meant to be written.
    await userEvent.click(
      within(row('大额单')).getByRole('button', {
        name: zhCN['label.manage.delete'],
      }),
    );
    const confirm = (
      await within(document.body).findByText(zhCN['label.delete.consequence'])
    ).closest('[role="alertdialog"]') as HTMLElement;
    await userEvent.click(
      within(confirm).getByRole('button', {
        name: zhCN['label.delete.keep'],
      }),
    );
    await waitFor(() =>
      expect(
        within(document.body).queryByText(zhCN['label.delete.consequence']),
      ).toBeNull(),
    );
  },
};

export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.record.empty-hint']),
    ).toBeVisible();

    // One way out, and which one follows from what was asked: this view runs
    // under a saved condition, so the way out is to clear it.
    const clear = canvas.getByRole('button', {
      name: zhCN['label.record.empty-clear'],
    });
    await expect(clear).toBeVisible();
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.record.empty-add'] }),
    ).toBeNull();

    // And it applies as well as clears: the band above the rows stops
    // naming a condition and says "all records" instead, which is the proof
    // that a query really went out — clearing the draft alone would leave
    // these rows standing under the condition the button just removed.
    await userEvent.click(clear);
    const applied = canvasElement.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    )!;
    await waitFor(() =>
      expect(applied).toHaveTextContent(zhCN['label.applied.all']),
    );
    await expect(applied.querySelectorAll('[data-slot="badge"]')).toHaveLength(
      0,
    );
  },
};

/**
 * The card layout answers with the table's own furniture (D18 V/VI): the
 * summaries stay under the cards, and the arrangement button — the same
 * place in the toolbar — opens the card settings instead of the column
 * settings. Ticking a body field draws it on every card at once.
 */
export const CardsAreSetUpFromTheSameButton: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    // The table's footer is on screen before the switch, so the cards are
    // measured against something that was there.
    await expect(
      canvasElement.querySelector('[data-slot="record-summaries"]'),
    ).not.toBeNull();

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.layout.cards'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="record-cards"]'),
      ).not.toBeNull(),
    );
    const summaries = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-summaries"][data-layout="card"]',
    );
    await expect(summaries).not.toBeNull();
    await expect(summaries).toHaveTextContent(zhCN['label.summary.scope.page']);

    // The same button, now about cards.
    const arrange = canvasElement.querySelector<HTMLElement>(
      '[data-control="columns"]',
    )!;
    await expect(arrange).toHaveAccessibleName(zhCN['label.toolbar.card']);
    await userEvent.click(arrange);
    const dialog = await within(document.body).findByRole('dialog', {
      name: zhCN['label.card.title'],
    });
    const before = canvasElement.querySelectorAll(
      '[data-slot="card-field"][data-field="createdAt"]',
    ).length;
    await expect(before).toBe(0);
    // Awaited: the popover fades in, and its rows are not in the tree
    // until it has.
    await userEvent.click(
      await within(dialog).findByRole('checkbox', { name: '创建时间' }),
    );
    // Every card grows the field, at the end of its body.
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll(
          '[data-slot="card-field"][data-field="createdAt"]',
        ).length,
      ).toBeGreaterThan(0),
    );
    const cards = canvasElement.querySelectorAll(
      '[data-slot="record-cards"] > *',
    );
    await expect(
      canvasElement.querySelectorAll(
        '[data-slot="card-field"][data-field="createdAt"]',
      ),
    ).toHaveLength(cards.length);
    // Two in a row, said as pressed, and the grid follows.
    await userEvent.click(
      await within(dialog).findByRole('button', {
        name: formatMessage(zhCN, 'label.card.columns-option', { count: 2 }),
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="record-cards"]'),
      ).toHaveClass('sm:grid-cols-2'),
    );
  },
};

export const Loading: Story = {
  ...DisplayLoading,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await expect(
      table.querySelectorAll('[data-slot=skeleton]').length,
    ).toBeGreaterThan(0);

    // And nothing around the skeleton that counts rows there are not any of
    // yet. The columns come from a result there has not been one of, so the
    // header used to be the dead table's — one cell with a tab-reachable
    // "Select all rows" in it — and the bar below it said "0 on this page"
    // beside a live Next page.
    await expect(table.querySelector('thead')).toBeNull();
    await expect(
      canvas.queryByRole('checkbox', {
        name: zhCN['label.record.select-all'],
      }),
    ).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot=record-pagination]'),
    ).toBeNull();

    await waitFor(
      () => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
      { timeout: 5_000 },
    );
  },
};

export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // One line, saying the failure itself rather than that there was one.
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent('仓储服务暂时不可用');
    await expect(
      within(alert).getByRole('button', {
        name: zhCN['label.query.retry'],
      }),
    ).toBeVisible();
    // Only the data is gone: the view stays open under its conditions.
    await expect(
      canvas.getByRole('button', { name: /^待出库订单/ }),
    ).toHaveAttribute('aria-current', 'true');

    // And it is as wide as the room it was given, margins deducted. The
    // registry's `Alert` is `w-full` — a length, 100% of the containing
    // block with nothing taken off for the `m-3` the frame gives this strip
    // — so its right edge used to run out under the border (F-14). Only a
    // real browser lays this out.
    const frame = canvasElement.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    )!;
    await expect(alert.getBoundingClientRect().right).toBeLessThanOrEqual(
      frame.getBoundingClientRect().right,
    );
  },
};

/**
 * The summary row outliving its own query, and saying so.
 *
 * The number stays — a page total is worth having — but it stops calling
 * itself a total, and the strip above says which query failed. What this
 * guards against is the silent version: 1280 + 2450 of four rows wearing the
 * word "Total" while the conditions match forty thousand.
 */
export const TotalCoversThisPageOnly: Story = {
  ...DisplayTotalCoversThisPageOnly,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    // One row, naming the scope it really answers for and carrying it in the
    // attribute a host can style on: the row that would have covered every
    // matching record has no number, and none is invented for it.
    const footer = table.querySelector<HTMLElement>('tfoot')!;
    await expect(scopeLabels(table)).toEqual([
      zhCN['label.summary.scope.page'],
    ]);
    await expect(
      [...footer.querySelectorAll('tr')].map(row => row.dataset.scope),
    ).toEqual(['page']);
    // The rows on screen add up to exactly what that row shows.
    await expect(amountOf(readPage(table, '金额'))).toBe(6470);

    // And one line above the result says why it is only a page total. It is
    // a warning, not an alert: nothing was blocked.
    // Addressed as the strip rather than as "the status on the page": the
    // result block carries a live region of its own, which is one too.
    const strip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="status-strip"]',
      );
      if (!found) throw new Error('no status strip');
      return found;
    });
    await expect(strip).toHaveAttribute('role', 'status');
    await expect(strip).toHaveTextContent(zhCN['runtime.summary.page-only']);
  },
};

export const NeedsFixing: Story = {
  ...DisplayNeedsFixing,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    // One finding is the line itself (F-14): said outright, with no heading
    // over it and no fold under it. "这个视图需要修复才能运行 · 还有 1 项"
    // was a heading with one thing beneath it, and the one thing it hid was
    // the only sentence that said what to fix.
    await expect(alert).toHaveTextContent('removedColumn');
    await expect(alert).not.toHaveTextContent(zhCN['label.view.needs-fixing']);
    await expect(within(alert).queryByRole('button', { name: /1/ })).toBeNull();

    // A config the definition refuses is never run, so there is no result —
    // and with no result there is no result block either. What used to be
    // drawn was a frame around a toolbar with a pressable Export in it, over
    // nothing at all.
    await expect(canvas.queryByRole('table')).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot="result-block"]'),
    ).toBeNull();
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.export.title'] }),
    ).toBeNull();

    // So the way to fix it is on this line: the same panel the toolbar's
    // button opens, opened from the only thing on screen that says why
    // there is no toolbar.
    await userEvent.click(
      within(alert).getByRole('button', {
        name: zhCN['label.status.open-columns'],
      }),
    );
    await within(document.body).findByText(zhCN['label.columns.title']);
    await waitFor(() =>
      expect(
        document.body.querySelectorAll('[data-slot="column-setting"]').length,
      ).toBeGreaterThan(0),
    );

    // Left closed behind it: the panel is a layer over the page, and a
    // story that walks off leaving one open hands the next one a popup.
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        within(document.body).queryByText(zhCN['label.columns.title']),
      ).toBeNull(),
    );
  },
};

/**
 * 打开视图时屏幕上的那一块（P-13）：形状与打开后一致——标题栏、带边的结果块、
 * 工具栏那一行、底下几行行——而不是一条说"有东西在加载"的灰条。
 */
export const Opening: Story = {
  ...DisplayOpening,
  play: async ({ canvasElement }) => {
    const shape = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="opening-skeleton"]',
      );
      if (!found) throw new Error('no opening skeleton');
      return found;
    });

    await expect(shape).toHaveAttribute('aria-busy', 'true');
    // Said once, by a live region of its own: `aria-busy` on a live region
    // holds its announcements back, and this one never turns false.
    await expect(within(shape).getByRole('status')).toHaveTextContent(
      zhCN['label.workbench.opening'],
    );

    await expect(
      shape.querySelector('[data-slot="view-header-skeleton"]'),
    ).not.toBeNull();
    const result = shape.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    );
    await expect(result).toHaveAttribute('data-framed', 'true');
    await expect(result!.firstElementChild).toHaveAttribute(
      'data-slot',
      'result-toolbar',
    );
    await expect(
      result!.querySelectorAll(
        '[data-slot="result-rows-skeleton"] [data-slot="skeleton"]',
      ).length,
    ).toBe(3);

    // The shape of an answer is not an answer: nothing in it is read out,
    // and nothing in it can be pressed.
    await expect(canvasElement.querySelector('table')).toBeNull();
    await expect(shape.querySelector('button')).toBeNull();
  },
};

/**
 * From nothing to a listed view: the work area's button opens a view that
 * is on screen at once, unsaved and with its editor out; the first save
 * asks the copy's two questions under a "save" heading; what the store took
 * is then listed in the sidebar and open. Nothing was measured that jsdom
 * could not measure — this pins that the three entries and the dialog exist
 * with the real popups and the real catalogue in front of them.
 */
export const NewView: Story = {
  ...DisplayNoViews,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // "No view yet" is said twice — the sidebar and the work area — so the
    // work area is found by its slot rather than by its words.
    const workArea = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-none"]',
      );
      if (!found) throw new Error('no empty work area');
      return found;
    });
    // Three ways in: the work area's button, the sidebar's `+`. The switcher
    // item is the third, behind a fold this story keeps open.
    const sidebar = canvas.getByRole('navigation');
    await expect(
      within(sidebar).getByRole('button', { name: zhCN['label.view.new'] }),
    ).toBeVisible();

    await userEvent.click(
      within(workArea).getByRole('button', { name: zhCN['label.view.new'] }),
    );
    await expect(
      await canvas.findByRole('heading', {
        level: 2,
        name: zhCN['label.view.new-title'],
      }),
    ).toBeVisible();
    await expect(canvas.getByText(zhCN['label.header.new-view'])).toBeVisible();
    // The conditions are out: a view with nothing in it is about to be shaped.
    await expect(
      await canvas.findByRole('button', { name: zhCN['label.filter.apply'] }),
    ).toBeVisible();
    await canvas.findByRole('table');

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.save.save'] }),
    );
    const dialog = await within(document.body).findByRole('dialog', {
      name: zhCN['label.save.first-heading'],
    });
    const title = within(dialog).getByRole('textbox', {
      name: zhCN['label.save.title'],
    });
    await expect(title).toHaveValue(zhCN['label.view.new-title']);
    await userEvent.clear(title);
    await userEvent.type(title, '大额单');
    await userEvent.click(
      within(dialog).getByRole('button', { name: zhCN['label.save.save'] }),
    );

    await expect(
      await canvas.findByRole('heading', { level: 2, name: '大额单' }),
    ).toBeVisible();
    await expect(
      await within(sidebar).findByRole('button', { name: /大额单/ }),
    ).toBeVisible();
    await expect(
      canvas.queryByText(zhCN['label.header.new-view']),
    ).not.toBeInTheDocument();
  },
};

export const CannotOpen: Story = {
  ...DisplayCannotOpen,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent(zhCN['label.view.unopenable']);

    // The empty state's form rather than a block of red: an icon, what
    // happened, and one way off the screen.
    await expect(alert).toHaveAttribute('data-slot', 'view-unopenable');
    await expect(alert.querySelector('svg')).not.toBeNull();
    const back = within(alert).getByRole('button', {
      name: zhCN['label.view.open-default'],
    });

    await userEvent.click(back);
    await canvas.findByRole('table');
    await expect(canvas.queryByRole('alert')).toBeNull();
  },
};

/**
 * The English catalogue — what the package ships, and what every story here
 * now opts out of.
 *
 * The fixtures are Chinese, so the rest of this file asserts the Chinese
 * wording; this one screen is what keeps the shipped default covered. The
 * field labels and the option labels stay Chinese either way: they come from
 * the definition, which is the application's data rather than the package's
 * wording.
 */
export const English: Story = {
  ...DisplayEnglish,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The audience tag and the fold above the rows, both from the catalogue.
    await expect(
      canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]'),
    ).toHaveTextContent(defaultMessages['label.scope.tag.shared']);
    await expect(
      canvas.getByRole('button', {
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
      }),
    ).toBeVisible();
    await expect(
      canvas.getByRole('button', {
        name: defaultMessages['label.toolbar.refresh'],
      }),
    ).toBeVisible();

    // The bar under the rows, where the count and the page size are sentences
    // with numbers in them rather than numbers with words beside them.
    const bar = paginationBar(canvasElement);
    await expect(bar).toHaveTextContent(
      formatMessage(defaultMessages, 'label.pagination.total', { total: 4 }),
    );
    await expect(
      within(bar).getByRole('combobox', {
        name: defaultMessages['label.pagination.page-size'],
      }),
    ).toHaveTextContent(
      formatMessage(defaultMessages, 'label.pagination.page-size-option', {
        size: 20,
      }),
    );

    // And nothing is left in Chinese behind it.
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.toolbar.refresh'] }),
    ).toBeNull();

    // The most visible line of the result area, which each kind used to hand
    // over as a finished English sentence: field label from the definition,
    // operator from the catalogue, option label from the definition again.
    const applied = canvas.getByRole('region', {
      name: defaultMessages['label.applied.title'],
    });
    const badge = `状态 ${defaultMessages['label.operator.IN']} 待出库`;
    await expect(applied).toHaveTextContent(badge);
    await expect(applied).not.toHaveTextContent(zhCN['label.operator.IN']);

    // And it is operable: the ✕ takes the condition out of force and the
    // query runs again, which is what leaves every order on screen.
    await userEvent.click(
      within(applied).getByRole('button', {
        name: defaultMessages['label.filter.unset-of'].replace(
          '{condition}',
          badge,
        ),
      }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('region', {
          name: defaultMessages['label.applied.title'],
        }),
      ).toHaveTextContent(defaultMessages['label.applied.all']),
    );
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toHaveLength(6),
    );
  },
};

/**
 * The column settings and the sort control, driven the way a keyboard user
 * drives them, and then saved.
 *
 * Four changes in one pass — order, pinning, a summary and the sort — because
 * they are one question ("what does a row look like") and because each of
 * them has to survive the others: the pin is written onto the column the
 * reorder moved, and the summary onto a column that is now somewhere else.
 * The table is asserted for what is on screen, and the store for what was
 * actually written; the draft would satisfy the first on its own.
 */
export const TableSettings: Story = {
  ...DisplayTableSettings,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    const popover = within(document.body);

    // The key column is held on the left and says so without offering a way
    // to change it; the rest of the list is the order the table is in.
    await expect(
      popover.getByRole('button', {
        name: say('label.columns.pin', {
          field: '订单号',
          state: zhCN['label.columns.pin.left'],
        }),
      }),
    ).toBeDisabled();

    // Reorder by keyboard: 状态 up one place, past 仓库.
    const handle = popover.getByRole('button', {
      name: say('label.columns.drag', { field: '状态' }),
    });
    handle.focus();
    await userEvent.keyboard('{ArrowUp}');
    await expect(
      document.querySelector('[data-slot="column-announcement"]'),
    ).toHaveTextContent(
      say('label.columns.moved', { field: '状态', index: 2, total: 4 }),
    );

    // 金额 is the column the table draws last, so it is held on the right
    // for the user (D13) and its pin says so without offering a way to
    // change it — the same shape as the key column's, at the other end.
    await expect(
      popover.getByRole('button', {
        name: say('label.columns.pin', {
          field: '金额',
          state: zhCN['label.columns.pin.right'],
        }),
      }),
    ).toBeDisabled();

    // Pin 仓库, which is one of the two that scroll, then summarise 金额 as
    // an average rather than a sum — being held at the end costs a column
    // its pin and its handle, and nothing else.
    await userEvent.click(
      popover.getByRole('button', {
        name: say('label.columns.pin', {
          field: '仓库',
          state: zhCN['label.columns.pin.none'],
        }),
      }),
    );
    await userEvent.click(
      popover.getByRole('combobox', {
        name: say('label.columns.summary', { field: '金额' }),
      }),
    );
    await userEvent.click(
      await popover.findByRole('option', {
        name: zhCN['label.summary.fn.AVG'],
      }),
    );
    await userEvent.keyboard('{Escape}');

    // The sort button reads the sort back; turning 金额 around turns the
    // rows around, because sorting applies at once.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="sort"]')!,
    );
    await userEvent.click(
      await within(document.body).findByRole('button', {
        name: say('label.sort.direction', { field: '金额' }),
      }),
    );
    await userEvent.keyboard('{Escape}');

    // What is on screen: the areas a table draws in. `订单号` and the newly
    // pinned `仓库` are held on the left — pinning is what moves a column
    // between areas, since `sticky` only fixes an element where it already
    // is — `状态` scrolls in the middle, and `金额` is held at the right end
    // whatever the config says.
    await waitFor(() =>
      expect(readHeaders(canvas.getByRole('table'))).toEqual([
        '订单号',
        '仓库',
        '状态',
        '金额',
      ]),
    );
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(
        [...PENDING_BY_AMOUNT].reverse(),
      ),
    );

    // And what was written: all four changes, in the saved config.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.save.save'] }),
    );
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      // The config keeps the order the reorder committed; where a pinned
      // column is *drawn* is the projection's answer, not something a saved
      // view has an opinion about — the same split as the row key's pin.
      expect(saved.config as RecordViewConfig).toMatchObject({
        table: {
          columns: [
            { field: 'id' },
            { field: 'status' },
            { field: 'warehouse', pinned: 'left' },
            { field: 'amount' },
          ],
        },
        summaries: [{ field: 'amount', fn: 'AVG' }],
        sort: [{ field: 'amount', direction: 'ASC' }],
      });
    });
  },
};

/**
 * The same panel, reordered the way a mouse reorders it.
 *
 * The keyboard path above commits through `onMove`, and the drop calculation
 * has unit tests of its own — but "press the handle, move onto the third row,
 * let go" only ever ran inside the library's own suite. It cannot run in
 * jsdom: `@dnd-kit/dom` picks the drop target by measuring boxes against each
 * other, and every box there is 0×0 at the origin. So this one lives in the
 * browser project, shares the table-settings fixture with the play above, and
 * asserts both ends of the chain — the order the table draws, and the order
 * the save wrote.
 */
export const TableSettingsPointerDrag: Story = {
  ...DisplayTableSettings,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readHeaders(table)).toEqual(['订单号', '仓库', '状态', '金额']),
    );

    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    const popover = within(document.body);
    const handle = await popover.findByRole('button', {
      name: say('label.columns.drag', { field: '仓库' }),
    });

    // The rows a drag moves within: the ones that scroll *and* are shown.
    // A hidden field has no place in `table.columns` and so no order to
    // drag — its handle is refused — the row key is held on the left and
    // `金额` at the right end, and each of those is a region of its own.
    // Read by what the panel offers rather than by a list of fields, so a
    // definition that grows another field does not turn this into a test
    // about the fixture.
    const draggable = [
      ...document.querySelectorAll<HTMLElement>(
        '[data-slot="column-region"][data-region="middle"] [data-slot="column-setting"]',
      ),
    ].filter(
      row => row.querySelector('button')?.hasAttribute('disabled') === false,
    );
    await expect(draggable.map(row => row.dataset.field)).toEqual([
      'warehouse',
      'status',
    ]);

    await dragHandleOnto(handle, draggable[1]);

    // Dropped on the row below it, 仓库 takes its place and 状态 closes up
    // behind it. The table is the witness: the panel's own list would show
    // the same thing whether or not the controller heard it.
    await waitFor(() =>
      expect(readHeaders(canvas.getByRole('table'))).toEqual([
        '订单号',
        '状态',
        '仓库',
        '金额',
      ]),
    );

    await userEvent.keyboard('{Escape}');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.save.save'] }),
    );
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      expect(
        (saved.config as RecordViewConfig).table.columns.map(
          column => column.field,
        ),
      ).toEqual(['id', 'status', 'warehouse', 'amount']);
    });
  },
};

/**
 * A column switched off keeps its place, and comes back to it (D17-8).
 *
 * The whole of the member is that one round trip: untick a column in the
 * middle of the table, tick it again, and it is the same table. Before it,
 * the entry was deleted from `table.columns`, so the column came back at
 * the far end and had to be dragged home — and the config that was saved in
 * between had simply lost it.
 *
 * It walks all three places the answer has to be the same in: the panel
 * (the row stays in its slot, unticked), the table (the column goes and
 * comes back where it was) and the store (the saved config carries the
 * switch rather than a shorter list).
 */
export const HiddenColumnKeepsItsPlace: Story = {
  ...DisplayTableSettings,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readHeaders(table)).toEqual(['订单号', '仓库', '状态', '金额']),
    );

    // The view's own columns, in the order the panel lists them. The
    // definition offers more fields than this view shows, and those are
    // listed after them — the place being checked here is the place among
    // the columns the table has.
    const own = ['id', 'warehouse', 'status', 'amount'];
    const columnRows = () =>
      [...document.querySelectorAll('[data-slot="column-setting"]')]
        .map(row => row.getAttribute('data-field'))
        .filter(field => field !== null && own.includes(field));
    const open = async () =>
      userEvent.click(
        canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
      );
    const checkbox = () =>
      within(document.body).getByRole('checkbox', {
        name: say('label.columns.show', { field: '仓库' }),
      });

    await open();
    await userEvent.click(checkbox());

    // Off in the table, still second in the panel — with a handle that
    // works, because a place in the order is exactly what it kept.
    await waitFor(() =>
      expect(readHeaders(canvas.getByRole('table'))).toEqual([
        '订单号',
        '状态',
        '金额',
      ]),
    );
    expect(columnRows()).toEqual(own);
    await expect(
      within(document.body).getByRole('button', {
        name: say('label.columns.drag', { field: '仓库' }),
      }),
    ).toBeEnabled();
    await userEvent.keyboard('{Escape}');

    // And what a save writes is the switch, in place — not a list with one
    // column missing, which is what made the place impossible to keep.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.save.save'] }),
    );
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).table.columns).toEqual([
        { field: 'id', pinned: 'left' },
        { field: 'warehouse', hidden: true },
        { field: 'status' },
        { field: 'amount' },
      ]);
    });

    // Back on, and back in its own slot: second, between the key column and
    // 状态, rather than at the end of the table.
    await open();
    await userEvent.click(checkbox());
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(readHeaders(canvas.getByRole('table'))).toEqual([
        '订单号',
        '仓库',
        '状态',
        '金额',
      ]),
    );
  },
};

/**
 * The sort popover, put in order the way a mouse puts it in order.
 *
 * Which field comes first is the whole of what that list says, and until it
 * could be dragged the only way to change it was to remove an entry and add
 * it again at the end. The drop calculation has unit tests of its own; what
 * only a real browser can run is the gesture — `@dnd-kit/dom` picks its drop
 * target by measuring boxes, and in jsdom every box is 0×0 at the origin.
 *
 * Both ends of the chain are asserted, and neither of them is the popover's
 * own list: the table's `aria-sort` and the rows underneath it, because
 * sorting applies at once, and the toolbar button, whose summary follows
 * whatever is now first.
 */
export const SortEntriesPointerDrag: Story = {
  ...DisplayTableSettings,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    const button = canvasElement.querySelector<HTMLElement>(
      '[data-control="sort"]',
    )!;
    await userEvent.click(button);
    const popover = within(document.body);

    // A second field to order by, so there is an order to argue about. It
    // joins at the end, ascending, and breaks the ties of the first.
    await userEvent.click(
      await popover.findByRole('button', { name: zhCN['label.sort.add'] }),
    );
    await userEvent.click(
      await popover.findByRole('menuitem', { name: '订单号' }),
    );
    const entries = () => [
      ...document.querySelectorAll<HTMLElement>('[data-slot="sort-entry"]'),
    ];
    await waitFor(() =>
      expect(entries().map(entry => entry.dataset.field)).toEqual([
        'amount',
        'id',
      ]),
    );

    // Carry 金额 down onto 订单号: what was breaking the ties becomes what
    // the rows are ordered by, and the other one closes up above it.
    const handle = popover.getByRole('button', {
      name: say('label.sort.drag', { field: '金额' }),
    });
    await dragHandleOnto(handle, entries()[1]);
    await waitFor(() =>
      expect(entries().map(entry => entry.dataset.field)).toEqual([
        'id',
        'amount',
      ]),
    );

    // The table is the witness: an order that was not applied is an order
    // nobody can see. `aria-sort` marks the column the rows are actually in
    // the order of, and there is one of those.
    await waitFor(() =>
      expect(headerOf(canvas.getByRole('table'), '订单号')).toHaveAttribute(
        'aria-sort',
        'ascending',
      ),
    );
    const sorted = canvas.getByRole('table');
    await expect(headerOf(sorted, '金额')).not.toHaveAttribute('aria-sort');
    await expect(positionOf(sorted, '订单号')).toBe('1');
    await expect(positionOf(sorted, '金额')).toBe('2');
    await expect(readColumn(sorted, '订单号')).toEqual(
      [...PENDING_BY_AMOUNT].sort(),
    );

    // And the button under the popover reads the new first entry back — on
    // screen as the field and the count, and to a reader as the name that
    // also says what kind of control it is.
    await userEvent.keyboard('{Escape}');
    await expect(button).toHaveTextContent(
      `订单号${say('label.sort.more', { count: 1 })}`,
    );
    await expect(button).toHaveAccessibleName(
      `${say('label.sort.button', {
        field: '订单号',
        direction: zhCN['label.sort.asc'],
      })} ${say('label.sort.more', { count: 1 })}`,
    );
  },
};

/**
 * A column's width, dragged onto its header's edge.
 *
 * It cannot be measured anywhere but here. The gesture reads the header's
 * box on the way in and writes a width on the way through, and in jsdom
 * every box is 0×0 — the unit suite drives the same handle with a keyboard
 * and checks what was written, which is a different question from whether
 * the column ends up that wide. This one asks the browser: the header is
 * wider by what the pointer travelled, the rows under it are the same width
 * as the header (a width on the header alone is a suggestion an auto-laid-out
 * table sizes straight past), and the number reached the saved config.
 */
export const ColumnResize: Story = {
  ...DisplayTableSettings,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readHeaders(table)).toEqual(['订单号', '仓库', '状态', '金额']),
    );

    const edge = canvas.getByRole('separator', {
      name: say('label.columns.resize', { field: '仓库' }),
    });
    const head = edge.closest('th')!;
    const before = head.getBoundingClientRect().width;

    await dragEdgeBy(edge, 80);

    // The column, not only the cell the handle is in: the header and the
    // rows under it have to come out the same width, or the "width" is a
    // header that has parted company with its column.
    const wanted = Math.round(before + 80);
    await waitFor(() => {
      const now = canvas
        .getByRole('separator', {
          name: say('label.columns.resize', { field: '仓库' }),
        })
        .closest('th')!;
      const body = canvas.getByRole('table') as HTMLTableElement;
      const cell = body.tBodies[0].rows[0].cells[now.cellIndex];
      expect([
        Math.round(now.getBoundingClientRect().width),
        Math.round(cell.getBoundingClientRect().width),
      ]).toEqual([wanted, wanted]);
    });

    // And the number the release committed is the number a save writes.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.save.save'] }),
    );
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      const column = (saved.config as RecordViewConfig).table.columns.find(
        entry => entry.field === 'warehouse',
      );
      expect(column?.width).toBe(wanted);
    });
  },
};

/**
 * Somebody else saved this view first, and the open view says so.
 *
 * The line under the title bar is `WriteOutcome`, and it is the one place
 * this package puts a decision the user has to make about their own work. So
 * the regression walks the whole of it: that the three ways out are offered,
 * that the destructive one is put again with both ways of looking side by
 * side, and that confirming it really writes.
 */
export const SaveConflictKeepsMine: Story = {
  ...DisplaySaveConflicted,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);

    const band = await conflictBand(canvasElement);
    // Every way out of a conflict, and only the ways out: taking theirs,
    // keeping a copy, and writing over them.
    await expect(
      [...band.querySelectorAll('button')].map(button =>
        button.textContent?.trim(),
      ),
    ).toEqual([
      zhCN['label.conflict.theirs'],
      zhCN['label.conflict.copy'],
      zhCN['label.conflict.mine'],
    ]);

    // Safest first, and none of the three set apart from the others: the
    // overwrite used to be the solid primary, which made the most dangerous
    // way out the only emphasised thing on the screen (D12 Ⅰ). Which of them
    // costs least depends on what is in each config, and the screen does not
    // decide that for anyone. Painted colours, which only a browser has.
    const painted = [...band.querySelectorAll<HTMLElement>('button')].map(
      button => {
        const style = getComputedStyle(button);
        return [style.backgroundColor, style.borderTopColor, style.color].join(
          ' | ',
        );
      },
    );
    await expect(new Set(painted), painted.join('; ')).toHaveProperty(
      'size',
      1,
    );

    await pressWhenEnabled(
      within(band).getByRole('button', {
        name: zhCN['label.conflict.mine'],
      }),
    );

    // Put once more, because the button that offered it cannot show what it
    // costs: the two configs are summarised beside each other, and here they
    // differ in three of the four things the summary counts.
    const dialog = await within(document.body).findByRole('alertdialog');
    await expect(dialog).toHaveTextContent(zhCN['label.conflict.confirm-mine']);
    await expect(dialog).toHaveTextContent(MINE);
    await expect(dialog).toHaveTextContent(THEIRS);

    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.conflict.mine'],
      }),
    );

    // And the overwrite lands: the stored config is the user's again, at the
    // revision the conflict reported rather than the stale one.
    await waitFor(async () => {
      const saved = await outcomesStore.current!.get('orders-pending');
      expect(saved.config as RecordViewConfig).toMatchObject({
        pageSize: 20,
        table: {
          columns: [
            { field: 'id' },
            { field: 'warehouse' },
            { field: 'status' },
            { field: 'amount' },
          ],
        },
      });
      expect((saved.config as RecordViewConfig).sort).toHaveLength(2);
    });
    await waitFor(() =>
      expect(canvas.queryByText(zhCN['label.write.conflict'])).toBeNull(),
    );
  },
};

/**
 * The other answer to the same question: the server's copy is adopted and the
 * draft goes with it.
 *
 * Nothing is written — a reload is the one recovery that writes nothing at
 * all — so what proves it is the draft: the view is holding their config,
 * and the edit that caused the conflict is gone with it.
 */
export const SaveConflictTakesTheirs: Story = {
  ...DisplaySaveConflicted,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);

    const band = await conflictBand(canvasElement);
    await pressWhenEnabled(
      within(band).getByRole('button', {
        name: zhCN['label.conflict.theirs'],
      }),
    );
    const dialog = await within(document.body).findByRole('alertdialog');
    await expect(dialog).toHaveTextContent(
      zhCN['label.conflict.confirm-theirs'],
    );
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.conflict.theirs'],
      }),
    );

    // The line is settled, and with it the edit that caused it: the draft is
    // theirs now, so there is nothing unsaved left to mark.
    await waitFor(() =>
      expect(canvas.queryByText(zhCN['label.write.conflict'])).toBeNull(),
    );
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();

    // And the draft really is theirs: the column settings read it, and the
    // one column this view never showed is shown in it. The rows on screen
    // are still the ones the last query returned — adopting a config is not
    // running it — which is exactly the split the panel reads across.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    await expect(
      await within(document.body).findByRole('checkbox', {
        name: say('label.columns.show', { field: '创建时间' }),
      }),
    ).toBeChecked();

    // The store never heard from this view at all: it still holds theirs.
    const saved = await outcomesStore.current!.get('orders-pending');
    await expect((saved.config as RecordViewConfig).pageSize).toBe(50);
  },
};

/**
 * The request left and nothing came back.
 *
 * It is neither a success nor a failure, so the line offers neither an
 * apology nor an overwrite — only the same request again under the same
 * `requestId`, for the server to recognise, or a way to stop holding it.
 */
export const SaveResultNeverCameBack: Story = {
  ...DisplaySaveResultUnknown,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);

    const band = await outcomeBand(canvasElement, zhCN['label.write.unknown']);
    await expect(
      [...band.querySelectorAll('button')].map(button =>
        button.textContent?.trim(),
      ),
    ).toEqual([zhCN['label.unknown.retry'], zhCN['label.unknown.leave']]);

    await pressWhenEnabled(
      within(band).getByRole('button', {
        name: zhCN['label.unknown.retry'],
      }),
    );

    // The replay lands, so the line comes down and the view is saved — the
    // config the save was carrying, not whatever the draft became since.
    await waitFor(async () => {
      const saved = await outcomesStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).sort).toHaveLength(2);
    });
    await waitFor(() =>
      expect(canvas.queryByText(zhCN['label.write.unknown'])).toBeNull(),
    );
  },
};

/**
 * The store took a look and refused.
 *
 * Nothing was written, so there is nothing to retry or overwrite — the line
 * says why, in the catalogue's sentence and then the store's own words, and
 * offers only a way to have done with it. Dismissing is what frees the view:
 * the engine holds the refused write until somebody settles it.
 */
export const SaveRefusedByTheStore: Story = {
  ...DisplaySaveRefused,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);

    const band = await outcomeBand(canvasElement, zhCN['view.write.invalid']);
    // The catalogue's sentence, and the store's own reason after it: an open
    // view has room for both, and the reason is the only part that says
    // *what* was wrong.
    await expect(band).toHaveTextContent('这个视图由运维托管，不接受修改');
    await expect(
      [...band.querySelectorAll('button')].map(button =>
        button.textContent?.trim(),
      ),
    ).toEqual([zhCN['label.rejected.dismiss']]);

    await pressWhenEnabled(
      within(band).getByRole('button', {
        name: zhCN['label.rejected.dismiss'],
      }),
    );
    await waitFor(() =>
      expect(
        canvas.queryByText(zhCN['view.write.invalid'], {
          exact: false,
        }),
      ).toBeNull(),
    );

    // A refusal is a definite answer, so the draft is still there and saving
    // again is a new intent rather than a recovery — and this one lands.
    await save(canvas);
    await waitFor(async () => {
      const saved = await outcomesStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).sort).toHaveLength(2);
    });
  },
};

/**
 * A write no open view owns, reported under the row that started it.
 *
 * The manager's line is the same component in its smaller clothes, and it
 * differs in exactly two places: taking the server's copy is about the list
 * here, so it says so, and there is nowhere to put a copy so no copy is
 * offered.
 */
export const RenameConflictedInTheManager: Story = {
  ...DisplayRenameConflicted,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await openManager(canvas, '我盯的大额单');

    await userEvent.click(
      within(managerRow('我盯的大额单')).getByRole('button', {
        name: zhCN['label.manage.rename'],
      }),
    );
    const title = within(managerRow('我盯的大额单')).getByLabelText(
      say('label.manage.rename-of', { title: '我盯的大额单' }),
    );
    await userEvent.clear(title);
    await userEvent.type(title, '大额单');
    await userEvent.click(
      within(managerRow('大额单')).getByRole('button', {
        name: zhCN['label.manage.rename-confirm'],
      }),
    );

    const line = await conflictLine('大额单');
    await expect(
      [...line.querySelectorAll('button')].map(button =>
        button.textContent?.trim(),
      ),
    ).toContain(zhCN['label.manage.reload']);
    // The list is what comes back, so the row says that rather than "take
    // theirs"; and a row has nowhere to put a copy, so none is offered.
    await expect(
      within(line).queryByRole('button', {
        name: zhCN['label.conflict.theirs'],
      }),
    ).toBeNull();
    await expect(
      within(line).queryByRole('button', {
        name: zhCN['label.conflict.copy'],
      }),
    ).toBeNull();

    await pressWhenEnabled(
      within(line).getByRole('button', {
        name: zhCN['label.conflict.mine'],
      }),
    );
    await waitFor(async () =>
      expect((await outcomesStore.current!.get('orders-mine')).title).toBe(
        '大额单',
      ),
    );
  },
};

/**
 * A delete that conflicted is confirmed twice.
 *
 * The first confirmation was about the view as the list had it; what the
 * conflict reports is a view that has changed since. So "Keep mine" on the
 * line does not delete — it puts the question again with the server's copy in
 * hand, and only that second answer writes.
 */
export const DeleteConflictAsksTwice: Story = {
  ...DisplayDeleteConflicted,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await openManager(canvas, '待出库订单');

    await userEvent.click(
      within(managerRow('待出库订单')).getByRole('button', {
        name: zhCN['label.manage.delete'],
      }),
    );
    const first = await deleteDialog();
    // A shared view is somebody else's too, and the first question says so.
    await expect(first).toHaveTextContent(
      zhCN['label.delete.shared-consequence'],
    );
    await userEvent.click(
      within(first).getByRole('button', {
        name: zhCN['label.manage.delete'],
      }),
    );

    const line = await conflictLine('待出库订单');
    // The first question is off the screen before the second is asked, which
    // is what makes "twice" mean anything.
    await waitFor(() =>
      expect(within(document.body).queryByRole('alertdialog')).toBeNull(),
    );

    await pressWhenEnabled(
      within(line).getByRole('button', {
        name: zhCN['label.conflict.mine'],
      }),
    );

    // Asked again — and nothing has been deleted yet: the destructive answer
    // belongs to the dialog, not to the line.
    const second = await deleteDialog();
    await expect(
      (await outcomesStore.current!.list('orders')).map(item => item.id),
    ).toContain('orders-pending');

    await userEvent.click(
      within(second).getByRole('button', {
        name: zhCN['label.manage.delete'],
      }),
    );
    await waitFor(async () =>
      expect(
        (await outcomesStore.current!.list('orders')).map(item => item.id),
      ).not.toContain('orders-pending'),
    );
  },
};

/** The user's way of looking, as the conflict dialog summarises it. */
const MINE = say('label.conflict.summary.record', {
  pageSize: 20,
  layout: zhCN['label.layout.table'],
  columns: 4,
  sorts: 2,
});

/** And the one the store had already taken, from `competingConfig`. */
const THEIRS = say('label.conflict.summary.record', {
  pageSize: 50,
  layout: zhCN['label.layout.table'],
  columns: 5,
  sorts: 0,
});

/**
 * Something for a save to carry: one more sortable column in the order.
 *
 * Sorting is an edit that applies at once, so it is the shortest honest way
 * to a dirty draft — and it is what a reader does by hand before pressing
 * Save in these scenes.
 */
async function dirtyTheDraft(canvasElement: HTMLElement): Promise<void> {
  const canvas = within(canvasElement);
  const table = await canvas.findByRole('table');
  await waitFor(() =>
    expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
  );
  await addSort(table, '订单号');
  await waitFor(() =>
    expect(positionOf(canvas.getByRole('table'), '订单号')).toBe('2'),
  );
}

/** Presses Save, whatever it is about to come to. */
async function save(canvas: ReturnType<typeof within>): Promise<void> {
  await pressWhenEnabled(
    canvas.getByRole('button', { name: zhCN['label.save.save'] }),
  );
}

/**
 * An outcome reaches the screen one render before the command's own progress
 * clears, and every button answering an outcome is disabled while it is set.
 * A click in that gap hits a disabled button and is swallowed.
 */
async function pressWhenEnabled(button: HTMLElement): Promise<void> {
  await waitFor(() => expect(button).not.toBeDisabled());
  await userEvent.click(button);
}

/** The open view's outcome band, once it says what it came to. */
async function outcomeBand(
  canvasElement: HTMLElement,
  sentence: string,
): Promise<HTMLElement> {
  const said = await within(canvasElement).findByText(sentence, {
    exact: false,
  });
  const band = said.closest<HTMLElement>('[data-slot="write-outcome"]');
  if (!band) throw new Error(`"${sentence}" is not in an outcome band.`);
  return band;
}

/** The same band, for the outcome all three stories above start from. */
function conflictBand(canvasElement: HTMLElement): Promise<HTMLElement> {
  return outcomeBand(canvasElement, zhCN['label.write.conflict']);
}

/**
 * Opens the manager from the sidebar's gear and waits for one row of it.
 *
 * The dialog fades in over a list the engine is still reading, so the row is
 * awaited rather than read at once. It is also the only thing worth waiting
 * for here: one of these scenes opens a view whose conditions match nothing,
 * so there is no table on the page to wait on instead.
 */
async function openManager(
  canvas: ReturnType<typeof within>,
  title: string,
): Promise<HTMLElement> {
  await userEvent.click(
    await canvas.findByRole('button', {
      name: zhCN['label.manage.open'],
    }),
  );
  await within(document.body).findByRole('dialog');
  let found: HTMLElement | null = null;
  await waitFor(() => {
    found = managerRow(title);
  });
  return found!;
}

/** One row of the manager, by the title it shows or holds in its input. */
function managerRow(title: string): HTMLElement {
  const found = [
    ...document.querySelectorAll<HTMLElement>('[data-slot="view-manager-row"]'),
  ].find(
    candidate =>
      candidate.textContent?.includes(title) ||
      [...candidate.querySelectorAll('input')].some(field =>
        field.value.includes(title),
      ),
  );
  if (!found) throw new Error(`no row for ${title}`);
  return found;
}

/** The conflict reported under one manager row, once it appears. */
async function conflictLine(title: string): Promise<HTMLElement> {
  await within(document.body).findByText(zhCN['label.write.conflict']);
  return managerRow(title);
}

/**
 * Whichever delete confirmation is on screen.
 *
 * By role rather than by the question's words: the question names the view
 * it is about (`label.delete.confirm` carries `{title}`), and the two
 * confirmations of a conflicted delete may not name the same one.
 */
async function deleteDialog(): Promise<HTMLElement> {
  return within(document.body).findByRole('alertdialog');
}

/** The data slots the layout is asserted by, in the order they are drawn. */
const LAYOUT_SLOTS = [
  'view-header',
  'editor-band',
  'applied-bar',
  'result-toolbar',
  'record-pagination',
];

/** One column header, found by the label it shows. */
/**
 * Adds a column to the sort from its header: Shift held while clicking. A
 * plain click sorts by the column alone, so this is the gesture that stacks
 * one. One `setup()` instance, so the Shift the keyboard holds is on the
 * pointer too — the direct API keeps no state between calls.
 */
async function addSort(table: HTMLElement, label: string): Promise<void> {
  const user = userEvent.setup();
  await user.keyboard('{Shift>}');
  await user.click(headerOf(table, label).querySelector('button')!);
  await user.keyboard('{/Shift}');
}

function headerOf(table: HTMLElement, label: string): HTMLTableCellElement {
  const found = [
    ...table.querySelectorAll<HTMLTableCellElement>('thead th'),
  ].find(
    cell =>
      cell.querySelector('[data-slot="column-label"]')?.textContent?.trim() ===
      label,
  );
  if (!found) throw new Error(`No column is headed "${label}".`);
  return found;
}

/** The badge in the first row's cell under a column, if it wears one. */
function badgeIn(table: HTMLElement, label: string): HTMLElement | null {
  const row = (table as HTMLTableElement).tBodies[0]?.rows[0];
  const cell = row?.cells[headerOf(table, label).cellIndex];
  return cell?.querySelector<HTMLElement>('[data-slot="badge"]') ?? null;
}

/** One body cell, by the column's own header and the row's place. */
function cellAt(
  table: HTMLElement,
  label: string,
  row: number,
): HTMLTableCellElement {
  const found = (table as HTMLTableElement).tBodies[0]?.rows[row]?.cells[
    headerOf(table, label).cellIndex
  ];
  if (!found) throw new Error(`Row ${row} has no cell under "${label}".`);
  return found;
}

/** What each badge of one cell says, left to right. */
function badgeTexts(cell: HTMLElement): string[] {
  return [...cell.querySelectorAll('[data-slot="badge"]')].map(
    node => node.textContent?.trim() ?? '',
  );
}

/** Where a sorted column sits in the order, as its header shows it. */
function positionOf(table: HTMLElement, label: string): string | undefined {
  return headerOf(table, label)
    .querySelector('[data-slot="sort-position"]')
    ?.textContent?.trim();
}

/** The scope each summary row is labelled with, top to bottom. */
function scopeLabels(table: HTMLElement): string[] {
  return [...table.querySelectorAll('tfoot [data-slot="summary-scope"]')].map(
    node => node.textContent?.trim() ?? '',
  );
}

function slots(canvasElement: HTMLElement): string[] {
  return [...canvasElement.querySelectorAll('[data-slot]')]
    .map(node => node.getAttribute('data-slot') ?? '')
    .filter(slot => LAYOUT_SLOTS.includes(slot));
}

/** What one element is actually painted, as the browser resolved it. */
const paintOf = (node: Element) => getComputedStyle(node).backgroundColor;

/** One view's row in the sidebar, by the name it shows. */
function listItem(canvasElement: HTMLElement, title: string): HTMLElement {
  const found = [
    ...canvasElement.querySelectorAll<HTMLElement>(
      '[data-slot="view-list"] [data-slot="view-group"] button',
    ),
  ].find(item => item.textContent?.includes(title));
  if (!found) throw new Error(`The list has no view called "${title}".`);
  return found;
}

/** What one element's text is actually set in, as the browser resolved it. */
const sizeOf = (node: Element) => getComputedStyle(node).fontSize;

/**
 * Three rungs of type, not four.
 *
 * One screen used to carry 12, 12.8, 14 and 16px. The middle pair is the
 * problem: 0.8px is not a rank, so a sidebar view item (12.8, the registry's
 * `sm` control size) and the group label right above it (12, `text-xs`) read
 * as one size drawn badly rather than as two; and 12.8px lands off the pixel
 * grid, which is what made 中文 at that size look blurry. Both are now the
 * one step under the body size — `--text-ui`, 13px — so the scale is
 * 13 / 14 / 16 and every gap in it is one the eye can name.
 *
 * Measured rather than asserted in a class name, because the whole point is
 * what the cascade resolves: the sidebar item takes its size from a vendored
 * `Button`, which the theme reaches through the utility it names.
 */
export const TypeScaleIsThreeRungs: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');

    // The pair that was 0.8px apart, now one rung.
    const item = listItem(canvasElement, '待出库订单');
    const heading = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-group-heading"]',
    )!;
    await expect(sizeOf(item)).toBe('13px');
    await expect(sizeOf(heading)).toBe('13px');

    // And everything else on that rung: the column headers, the enum
    // badges in the cells, the pagination line.
    const head = table.querySelector<HTMLElement>('thead th')!;
    const badge = canvasElement.querySelector<HTMLElement>(
      '[data-slot="badge"]',
    )!;
    const pagination = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    )!;
    for (const node of [head, badge, pagination])
      await expect(sizeOf(node)).toBe('13px');

    // The two rungs above it, so what is asserted is a scale and not one
    // number: the rows are the body size, the definition's name is the h1.
    const cell = table.querySelector<HTMLElement>('tbody td')!;
    const title = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-list-title"]',
    )!;
    await expect(sizeOf(cell)).toBe('14px');
    await expect(sizeOf(title)).toBe('16px');

    // Nothing anywhere on the screen is still set in the step that went.
    const stray = [...canvasElement.querySelectorAll('*')].filter(
      node => sizeOf(node) === '12.8px',
    );
    await expect(stray).toHaveLength(0);
  },
};

/**
 * The sidebar is a navigation column, and the three states on it are three
 * colours.
 *
 * This is the measurement the design could not be argued into: four states
 * used to share one 3% grey — a hovered row, the open row, a selected table
 * row and a pressed segment — so the list had no "you are here" at all, and
 * the column itself was the same white as the work area beside it. The
 * ratios are tiny on purpose; what is asserted is that they are not *one*,
 * which is what a token collapse looks like from here.
 *
 * The bar down the open row's leading edge is the other half: a mark of a
 * different kind, which no theming can turn into the fill beside it.
 */
export const SidebarIsANavigationColumn: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const column = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-list"]',
    )!;
    // The work area paints nothing of its own: what shows through `main` is
    // the surface's `--background`, which is the colour to compare against.
    const work = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    const ground = paintOf(column);

    // The column has a ground of its own, and the work area is not it.
    await expect(ground).not.toBe('rgba(0, 0, 0, 0)');
    await expect(paintOf(work)).not.toBe('rgba(0, 0, 0, 0)');
    await expect(ground).not.toBe(paintOf(work));
    // And an edge between the two, drawn once.
    await expect(
      parseFloat(getComputedStyle(column).borderRightWidth),
    ).toBeGreaterThan(0);

    // Two columns, two heads, one line under both. The sidebar's header is
    // ruled off at exactly the height the title bar is, so the screen reads
    // as one page in two columns rather than as two pages side by side.
    const listHead = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-list-header"]',
    )!;
    const titleBar = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-header-block"]',
    )!;
    await expect(
      parseFloat(getComputedStyle(listHead).borderBottomWidth),
    ).toBeGreaterThan(0);
    await expect(
      Math.abs(
        listHead.getBoundingClientRect().bottom -
          titleBar.getBoundingClientRect().bottom,
      ),
    ).toBeLessThanOrEqual(1);

    // The open view: the work area's own ground on top of the column's, plus
    // the bar. Neither is another step of the same grey.
    const current = listItem(canvasElement, '待出库订单');
    await expect(current.getAttribute('aria-current')).toBe('true');
    await expect(paintOf(current)).toBe(paintOf(work));
    await expect(paintOf(current)).not.toBe(ground);
    await expect(getComputedStyle(current).boxShadow).toContain('inset');
    await expect(getComputedStyle(current).fontWeight).toBe('500');

    // A row that is not open carries no fill of its own, so what shows is
    // the column. Hovering it is a third colour: distinguishable from the
    // ground it sits on *and* from the open row beside it, which is the pair
    // that used to be identical.
    const other = listItem(canvasElement, '我盯的大额单');
    await expect(paintOf(other)).toBe('rgba(0, 0, 0, 0)');
    await expect(other.className).toContain('hover:bg-sidebar-accent');
    // Painted rather than hovered: the runner's pointer events do not put a
    // real `:hover` on the element, and what broke before was never the
    // pseudo-class — it was the two tokens resolving to one grey. So the
    // token is put on the page the way the hover would put it, in the
    // column's own cascade, and read back in the same form as the rest.
    const hovered = await waitFor(() => {
      const probe = column.appendChild(document.createElement('div'));
      probe.style.backgroundColor = 'var(--sidebar-accent)';
      const painted = paintOf(probe);
      probe.remove();
      return painted;
    });
    await expect(hovered).not.toBe(ground);
    await expect(hovered).not.toBe(paintOf(current));

    // The kind icon is on every row, because one definition holds record and
    // analysis views together and the name alone does not say which is which.
    await expect(other.querySelector('svg')).not.toBeNull();
    // And where a view came from is a word after its name rather than a
    // badge at the row's end — the end belongs to the star.
    const system = listItem(canvasElement, '全部订单');
    await expect(
      system.querySelector('[data-slot="view-system-tag"]'),
    ).not.toBeNull();
    await expect(system.querySelector('[data-slot="badge"]')).toBeNull();
  },
};

/**
 * The star on the view that opens first, read off the preference the manager
 * writes.
 *
 * Two screens showing the same fact is only worth having while they cannot
 * disagree, so this sets the default where it is set — in the manager — and
 * then looks for it where it is read: on the row in the list.
 */
export const DefaultViewWearsTheStar: Story = {
  ...DisplayManageViews,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // Nothing opens first until someone says so, so nothing wears a star.
    await expect(
      canvasElement.querySelector('[data-slot="view-default-star"]'),
    ).toBeNull();

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.manage.open'],
      }),
    );
    const dialog = await within(document.body).findByRole('dialog');
    const managed = [
      ...dialog.querySelectorAll<HTMLElement>('[data-slot="view-manager-row"]'),
    ].find(row => row.textContent?.includes('我盯的大额单'))!;
    await userEvent.click(
      within(managed).getByRole('button', {
        name: zhCN['label.manage.set-default'],
      }),
    );
    await waitFor(() =>
      expect(managed).toHaveTextContent(zhCN['label.manage.default']),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(document.body.querySelector('[role="dialog"]')).toBeNull(),
    );

    // The same preference, on the row in the list: filled, in the primary
    // colour, and at the row's end.
    const starred = listItem(canvasElement, '我盯的大额单');
    const star = starred.querySelector<HTMLElement>(
      '[data-slot="view-default-star"]',
    )!;
    await expect(star).not.toBeNull();
    // Filled and in the primary colour, so it reads as a mark rather than as
    // one more outline among the icons.
    await expect(star.classList).toContain('fill-current');
    await expect(getComputedStyle(star).color).not.toBe(
      getComputedStyle(starred).color,
    );
    // One star and no more: the fact is about one view.
    await expect(
      canvasElement.querySelectorAll('[data-slot="view-default-star"]'),
    ).toHaveLength(1);
    // And a reader hears it rather than only seeing it.
    await expect(starred.textContent).toContain(zhCN['label.manage.default']);
  },
};

/**
 * The fold the shell decides for itself, and the one filling the screen asks
 * for.
 *
 * A 375px column has no room for a 224px list beside it — below `md` the
 * list is not beside the view at all but stacked over it, and the table
 * started 204px down. Filling the screen is the same argument made by the
 * user: the gesture is about the rows, and navigation is the first thing
 * that is not the rows.
 */
export const TheListFoldsItselfAwayWhereItCannotFit: Story = {
  ...DisplayNarrowTitleBar,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;

    // Nobody passed `defaultSidebarOpen`: the shell measured the column it
    // was given and folded the list for it.
    await expect(host.getBoundingClientRect().width).toBe(375);
    await expect(sidebar()).toBeNull();
    // Which is what the fold buys: the rows start at the top of the column
    // rather than under 204px of navigation.
    await expect(
      table.getBoundingClientRect().top -
        canvasElement
          .querySelector('[data-slot="view-surface"]')!
          .getBoundingClientRect().top,
    ).toBeLessThan(260);

    // The list is still reachable, as one control in the title bar.
    const switcher = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-switcher"]',
    )!;
    await expect(switcher).not.toBeNull();
    // Sized to what it says and reading from its beginning — not a 470px
    // pill with a short name floating in the middle of it.
    await expect(getComputedStyle(switcher).justifyContent).toBe('flex-start');
    const label = switcher.querySelector<HTMLElement>('span')!;
    await expect(
      switcher.getBoundingClientRect().right -
        label.getBoundingClientRect().right,
    ).toBeLessThan(40);
    // And the right-hand group still ends the bar, on whichever line it is.
    const header = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-header"]',
    )!;
    const controls = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    await expect(
      Math.abs(
        controls.getBoundingClientRect().right -
          header.getBoundingClientRect().right,
      ),
    ).toBeLessThanOrEqual(1);
  },
};

/**
 * The same rule after arrival: the fold follows the column it is given, in
 * both directions, and stops following once the user has answered for
 * themselves.
 *
 * jsdom lays nothing out, so the unit test drives a fake observer over a
 * faked width; this is the one place a real `ResizeObserver` on a real box
 * is weighed. The width is the host's, as a host's is — a split pane dragged
 * narrower, a panel opened beside the page, a window resized — and the
 * workbench is told nothing but the box it ends up in.
 */
export const TheListFollowsTheColumnItIsGiven: Story = {
  ...DisplayNarrowTitleBar,
  // Wide enough to open with the list beside the view; the play is what
  // takes the room away and gives it back.
  args: { ...DisplayNarrowTitleBar.args, narrowWidth: 1000 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    const resizeTo = async (width: number) => {
      host.style.width = `${width}px`;
      await expect(host.getBoundingClientRect().width).toBe(width);
      // A `ResizeObserver` reports on the frame after the box changed, so
      // the answer is never the one on screen at this instant.
      await new Promise(settle => setTimeout(settle, 200));
    };

    await expect(host.getBoundingClientRect().width).toBe(1000);
    await expect(sidebar()).not.toBeNull();

    // Dragged below `md`: the list would no longer be *beside* the view but
    // stacked over it, which is the whole reason a narrow column folds.
    await resizeTo(608);
    await expect(sidebar()).toBeNull();

    // And back, because the room it was folded for is there again.
    await resizeTo(1000);
    await expect(sidebar()).not.toBeNull();

    // Then the user answers, and the measurement stops answering: folded by
    // hand in a column with room to spare, it stays folded through a trip
    // down to 608 and back. Undoing that under their hands, once per drag,
    // is worse than a list folded where it would have fitted.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.collapse-sidebar'],
      }),
    );
    await expect(sidebar()).toBeNull();
    await resizeTo(608);
    await resizeTo(1000);
    await expect(sidebar()).toBeNull();
  },
};

/**
 * Filling the screen gives the rows the room, and the list is the first
 * thing that is not the rows.
 */
export const FillingTheScreenFoldsTheList: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    await expect(sidebar()).not.toBeNull();

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-view'],
      }),
    );
    await waitFor(() => expect(sidebar()).toBeNull());
    // Not lost, only folded: the switcher is the list while it is away.
    await expect(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    ).toBeVisible();

    // And leaving reads the page's own answer again.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.collapse-view'],
      }),
    );
    await waitFor(() => expect(sidebar()).not.toBeNull());
  },
};

/**
 * The sidebar folded away, and the list still reachable.
 *
 * Folding takes the one control that opens another view off the screen, so
 * the title bar has to grow its replacement in the same gesture: the way
 * back, the definition's name, and the list as one dropdown. This walks the
 * whole round trip — fold, switch, unfold — because the failure worth
 * catching is the one where a user folds the list and cannot get back to it.
 */
export const CollapseAndSwitch: Story = {
  ...DisplayCollapsedSidebar,
  // Starts open on purpose: the fold itself is half of what is asserted.
  args: { ...DisplayCollapsedSidebar.args, collapsed: false },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    await expect(sidebar()).not.toBeNull();

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.collapse-sidebar'],
      }),
    );
    await expect(sidebar()).toBeNull();

    // What the sidebar was carrying is now in the title bar, in one group
    // with the commands that save the view.
    const identity = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-identity"]',
    )!;
    await expect(identity).toHaveTextContent('订单');
    await expect(
      within(identity).getByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    ).toBeVisible();
    // The save group moved left, next to the view's name: it changes the
    // config under that name, so it belongs to it rather than to the row's end.
    await expect(
      identity.querySelector('[data-slot="save-actions"]'),
    ).not.toBeNull();

    // The switcher opens the same views the sidebar listed, grouped the same
    // way, and choosing one opens it.
    await userEvent.click(
      within(identity).getByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    );
    const menu = await within(document.body).findByRole('menu');
    await expect(menu).toHaveTextContent(zhCN['label.scope.group.personal']);
    await expect(menu).toHaveTextContent(zhCN['label.scope.tag.system']);
    await userEvent.click(
      within(menu).getByRole('menuitemradio', { name: /我盯的大额单/ }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-title"]'),
      ).toHaveTextContent('我盯的大额单'),
    );

    // And back: the list returns, and the header gives up the switcher.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-sidebar'],
      }),
    );
    await expect(sidebar()).not.toBeNull();
    await expect(
      canvas.queryByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    ).toBeNull();
    // Nothing left open: Base UI parks focus-guard sentinels beside an open
    // popup, and axe judges the page as the play leaves it.
    await waitFor(() =>
      expect(document.body.querySelector('[role="menu"]')).toBeNull(),
    );
  },
};

/**
 * The editor's fold is driven from the title bar, and its mode from the
 * chevron beside it.
 *
 * Both moved out of the panel: the panel is the conditions, and a control
 * for *how to edit them* sitting among them was a line of chrome over every
 * filter ever written. The dot on the toggle is the one credential a folded
 * editor can still show, so it is asserted here rather than assumed.
 */
export const EditorToggleAndModes: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // A saved view opens folded, so the panel is not on the page at all.
    await expect(
      canvasElement.querySelector('[data-slot="editor-band"]'),
    ).toBeNull();

    const toggle = canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    await expect(
      canvasElement.querySelector('[data-slot="editor-band"]'),
    ).not.toBeNull();

    // The mode lives beside the toggle now, and drives the panel below it.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.editor-modes'],
      }),
    );
    const modes = await within(document.body).findByRole('menu');
    await userEvent.click(
      within(modes).getByRole('menuitemradio', {
        name: zhCN['label.filter.advanced'],
      }),
    );

    // Advanced draws the root as a framed block with its operator on it.
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="filter-group"]'),
      ).not.toBeNull(),
    );
    // The menu is gone before the story settles. Base UI parks focus-guard
    // sentinels beside an open popup, and axe judges the page as the play
    // leaves it — so a play that opened something closes it, which is what
    // a user does anyway.
    await waitFor(() =>
      expect(document.body.querySelector('[role="menu"]')).toBeNull(),
    );
    await expect(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    ).toHaveAccessibleName(
      `${zhCN['label.filter.panel']} · ${zhCN['label.filter.advanced']}`,
    );
  },
};

/**
 * 键盘上的两件事，只有真浏览器答得出来：原生按钮被 Enter／空格激活是浏览器的
 * 默认动作，漫游焦点是 Base UI 在真实 keydown 上做的事。
 *
 * 一、**结果工具栏是一条 toolbar**：整条栏只有一个 Tab 站，方向键在栏内左右
 * 走并在两端回绕，走过去只移动焦点——布局切换的档位不会被走成按下；离开这条
 * 栏的 Tab 直接落到表上。二、**折叠带的开关是 `CollapsibleTrigger`**：Enter
 * 开、空格关，`aria-expanded` 跟着翻，`aria-controls` 只在带子在页面上时存在。
 */
export const ToolbarAndFoldByKeyboard: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The fold, from the handle in the title bar. A saved view opens folded.
    const toggle = canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).not.toHaveAttribute('aria-controls');
    await tabTo(toggle);
    await userEvent.keyboard('{Enter}');
    const band = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="editor-band"]',
      );
      if (!found) throw new Error('the band did not open');
      return found;
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(toggle).toHaveAttribute('aria-controls', band.id);
    // Space closes it again, and the panel leaves the page with it — so the
    // reference the handle was making leaves too.
    await userEvent.keyboard(' ');
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="editor-band"]'),
      ).toBeNull(),
    );
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).not.toHaveAttribute('aria-controls');

    // The bar: one stop, the arrows inside it.
    const bar = canvas.getByRole('toolbar', {
      name: zhCN['label.toolbar.title'],
    });
    await expect(bar).toHaveAttribute('aria-orientation', 'horizontal');
    const controls = [...bar.querySelectorAll('button')];
    await expect(
      controls.filter(control => control.tabIndex === 0),
    ).toHaveLength(1);

    await tabTo(controls[0]!);
    for (let at = 1; at < controls.length; at += 1) {
      await userEvent.keyboard('{ArrowRight}');
      await expect(document.activeElement).toBe(controls[at]);
    }
    // Both ends wrap.
    await userEvent.keyboard('{ArrowRight}');
    await expect(document.activeElement).toBe(controls[0]);
    await userEvent.keyboard('{ArrowLeft}');
    await expect(document.activeElement).toBe(controls[controls.length - 1]);

    // Walking moves focus and nothing else: the layout switch is still on
    // the layout it was on, and the rows are still a table.
    await expect(
      canvas.getByRole('button', { name: zhCN['label.layout.table'] }),
    ).toHaveAttribute('aria-pressed', 'true');
    await expect(canvas.getByRole('table')).toBeInTheDocument();

    // Tab leaves the whole bar rather than stepping to the next control in
    // it, and comes back to the one the bar was left on.
    await userEvent.tab();
    await expect(bar.contains(document.activeElement)).toBe(false);
    await userEvent.tab({ shift: true });
    await expect(document.activeElement).toBe(controls[controls.length - 1]);

    // An item of the bar is still the popup's trigger.
    await tabTo(
      canvas.getByRole('button', { name: zhCN['label.toolbar.columns'] }),
    );
    await userEvent.keyboard('{Enter}');
    await within(document.body).findByText(zhCN['label.columns.title']);
    // One live region per surface, however many lists the popup holds: the
    // workbench's own sits in the result block and reads its queries back,
    // and the popover carries one for the arrow keys and the pin toggles
    // inside it. They answer different presses — a pinned column says where
    // it landed, the query that press started says it is running — and a
    // polite region queues rather than interrupts, so the two never read
    // over each other. `@dnd-kit` keeps a third beside them
    // (`#dnd-kit-announcement-*`) for what the library itself drives.
    const regions = (root: ParentNode) =>
      root.querySelectorAll('[aria-live]:not([id^="dnd-kit"])');
    await expect(regions(canvasElement)).toHaveLength(1);
    const popup = await waitFor(() => {
      const found = document.body.querySelector(
        '[data-slot="popover-content"]',
      );
      if (!found) throw new Error('the popover did not open');
      return found;
    });
    await expect(regions(popup)).toHaveLength(1);
    // Closed again before the story settles, as every play that opens a
    // popup does: axe judges the page as the play leaves it. Twice, because
    // a control reached by the keyboard is showing its own tooltip and that
    // is the top layer — the first Escape is the tooltip's.
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="tooltip-content"]'),
      ).toBeNull(),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="popover-content"]'),
      ).toBeNull(),
    );
  },
};

/**
 * What a query says out loud, in a browser where a live region is read
 * rather than an attribute in a tree.
 *
 * The rows change under a reader who is not looking at them, and until this
 * every `[aria-live]` on the screen stayed empty from the first press to the
 * last. What is regressed here is the pair, in order and once each: the
 * query says it is running, and then says what came back in the sentence the
 * pagination bar is showing. Sorting is the shortest honest query — it is
 * one `edit` and one `apply`, the same round trip a condition makes.
 */
export const QueryAnnouncedInTheResult: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const region = () => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="record-announcement"]',
      );
      if (!found) throw new Error('the result has no live region');
      return found;
    };
    const landed = say('label.pagination.total', { total: 4 });

    // The query that opened the view has already been read back.
    await waitFor(() => expect(region()).toHaveTextContent(landed));

    // Everything it says from here on, in order and without repeats.
    const heard: string[] = [];
    const observer = new MutationObserver(() => {
      const text = region().textContent?.trim() ?? '';
      if (text !== '' && heard[heard.length - 1] !== text) heard.push(text);
    });
    // `characterData` as well as `childList`: React writes a new sentence
    // into the text node that is already there, which is not a child list
    // changing.
    observer.observe(region(), {
      characterData: true,
      childList: true,
      subtree: true,
    });

    await addSort(table, '订单号');
    await waitFor(() => expect(heard).toContain(landed));
    observer.disconnect();

    // Two sentences for one query, each said once: nothing repeated, and
    // the running one in between is what makes a second identical result
    // audible at all.
    await expect(heard).toEqual([zhCN['label.status.querying'], landed]);

    // And the bar the sentence was taken from says the same thing, which is
    // the whole reason it is that sentence and not a second wording.
    await expect(paginationBar(canvasElement)).toHaveTextContent(landed);

    // The button that ran it can now be heard as a sort. On screen it is
    // the first field, an arrow and a count — 订单号 joined the saved sort
    // rather than replacing it — and none of that says what the control is.
    const sortButton = canvasElement.querySelector<HTMLElement>(
      '[data-control="sort"]',
    );
    await waitFor(() =>
      expect(sortButton).toHaveAccessibleName(
        `${say('label.sort.button', {
          field: '金额',
          direction: zhCN['label.sort.desc'],
        })} ${say('label.sort.more', { count: 1 })}`,
      ),
    );
    // And the words on it are unchanged by the name it was given.
    await expect(sortButton).toHaveTextContent(
      `金额${say('label.sort.more', { count: 1 })}`,
    );

    // The other control whose name is its own state: pressing the pin
    // rewrites the name under the cursor, so the panel says what changed.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    const body = within(document.body);
    await body.findByText(zhCN['label.columns.title']);
    await userEvent.click(
      body.getByRole('button', { name: /^仓库 的固定方式/ }),
    );
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="column-announcement"]'),
      ).toHaveTextContent(/^仓库 已固定/),
    );

    // Closed before the story settles, as every play that opens a popup
    // does: axe judges the page as the play leaves it. Twice, because the
    // control just pressed is showing its own tooltip on top.
    await userEvent.keyboard('{Escape}');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="popover-content"]'),
      ).toBeNull(),
    );
  },
};

/**
 * Three fields ticked in one visit to the picker, and three pills for it.
 *
 * The picker used to close on every pick, which made four conditions four
 * round trips. It stays open now, so the thing worth regressing is that a
 * second tick lands while the first is still on screen — and that the third
 * can be ticked without a pointer at all.
 */
export const PickSeveralFields: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.add'] }),
    );

    const picker = await within(document.body).findByRole('dialog');
    await expect(picker).toHaveTextContent(zhCN['label.filter.pick-fields']);
    // The view's saved condition is already a tick, which is what makes the
    // list a statement about the filter rather than a menu of things to add.
    await expect(
      within(picker).getByRole('checkbox', { name: '状态' }),
    ).toBeChecked();

    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '仓库' }),
    );
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '金额' }),
    );
    // And the third with the keyboard alone: from the search line one Tab
    // steps into the grid and Space is what a tick means. The jsdom suite
    // asserts the same thing; this one asserts it in a browser, where the
    // checkbox is a `span` carrying a role rather than an input, and Space
    // is somebody's own key handler rather than the platform's.
    await userEvent.click(
      within(picker).getByRole('textbox', { name: zhCN['label.field.search'] }),
    );
    await userEvent.keyboard('{Tab}');
    await expect(document.activeElement).toBe(
      within(picker).getByRole('checkbox', { name: '订单号' }),
    );
    await userEvent.keyboard(' ');
    await expect(
      within(picker).getByRole('checkbox', { name: '订单号' }),
    ).toBeChecked();
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );

    // Four conditions now: the saved one, the two ticked with the pointer
    // and the one ticked with the keyboard.
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="filter-condition"]'),
      ).toHaveLength(4),
    );
    // And the picker is shut, sentinels and all — see CollapseAndSwitch.
    await waitFor(() =>
      expect(document.body.querySelector('[role="dialog"]')).toBeNull(),
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
    await expect(surface.style.getPropertyValue('--fve-expanded-w')).toBe(
      `${window.innerWidth}px`,
    );

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    // And the host's element is handed back without our arithmetic on it.
    await expect(surface.style.getPropertyValue('--fve-expanded-w')).toBe('');
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
 * the four `--fve-expanded-*` are read in the element's own coordinates,
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
      surface.style.getPropertyValue('--fve-expanded-w'),
    );
    await expect(written).toBeGreaterThan(window.innerWidth);
    await expect(written * 0.75).toBeCloseTo(window.innerWidth, 0);

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await expect(surface.style.getPropertyValue('--fve-expanded-w')).toBe('');
  },
};

/**
 * 宿主的行动作抛错时，结果块换成可复原的错误态，其余部分照常可用。
 *
 * 按第一行的「弄坏」让那个动作在渲染时抛错，然后看三件事：结果块里是一条
 * `role="alert"` 加「重试」而不是白屏；标题栏与保存按钮还在；按「重试」之后行
 * 回来了——动作组件被重新挂载，它那个「坏了」的状态一起归零。
 */
export const RenderFailure: Story = {
  ...DisplayRenderFailure,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const rows = canvas.getAllByRole('row');
    await userEvent.click(
      within(rows[1]).getByRole('button', { name: '弄坏' }),
    );

    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveAttribute('data-boundary', 'result');
    await expect(alert.closest('[data-slot="result-block"]')).not.toBeNull();
    await expect(canvas.queryByRole('table')).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot="view-title"]'),
    ).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: zhCN['label.save.save'] }),
    ).toBeVisible();

    await userEvent.click(
      within(alert).getByRole('button', {
        name: zhCN['label.render.retry'],
      }),
    );
    await canvas.findByRole('table');
    await expect(canvas.queryByRole('alert')).toBeNull();
  },
};

/**
 * 冻结列的边说的是「这两端钉着」，所以它一直都在（D13）。
 *
 * 首列（主键）与末列（这里是「创建时间」，宿主的操作列坐在它外侧）各带一道
 * `--border` 发丝线加一段软阴影，静止时就有，滚到中间、滚到尽头都不变。只有
 * **边界**格子画边：最后一个左冻结列与第一个右冻结列面对着会滚的中间，两个冻
 * 结列之间从来没有东西经过；操作列此时不是边界，因为它前面已经有一列钉在右边
 * 了。表头、数据行、汇总行三层拿的是同一个类名，所以同一列在三层上同起同落。
 * jsdom 不算布局也不套样式表，`box-shadow` 的真值只有这里量得到。
 */
export const PinnedEdges: Story = {
  ...DisplayPinnedEdges,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await expect(area).toHaveAttribute('data-scrolls');

    // Two columns frozen left by the config, and the last column frozen
    // right by the projection whatever the config asked for.
    const inner = headerOf(table, '订单号');
    const left = headerOf(table, '金额');
    const end = headerOf(table, '创建时间');
    const actions = table.querySelector<HTMLTableCellElement>(
      'thead th[data-column="actions"]',
    )!;
    await expect(inner).toHaveAttribute('data-pin', 'left');
    await expect(left).toHaveAttribute('data-pin', 'left');
    // With a row-action column the host's slot is the end (D13): the last
    // data column lets go, and the actions wear the right edge.
    await expect(end).not.toHaveAttribute('data-pin');
    await expect(actions).toHaveAttribute('data-pin', 'right');

    /** Whether each cell draws an edge, on all three layers of one column. */
    const edged = (head: HTMLTableCellElement) =>
      ['thead', 'tbody', 'tfoot'].map(
        layer =>
          getComputedStyle(
            table.querySelector<HTMLTableRowElement>(`${layer} tr`)!.cells[
              head.cellIndex
            ],
          ).boxShadow !== 'none',
      );
    const edges = () => ({
      inner: edged(inner),
      left: edged(left),
      end: edged(end),
      actions: edged(actions),
    });
    const NONE = [false, false, false];
    const ALL = [true, true, true];
    const FRAMED = { inner: NONE, left: ALL, end: NONE, actions: ALL };

    // Still, and wide enough that nothing has to scroll: the frame is there
    // before anything moves, which is the whole of D13.
    await waitFor(() => expect(edges()).toEqual(FRAMED));

    // Computed is not painted: in collapsed-border mode Chromium draws no
    // outer box-shadow on a cell, and the edges above were on the page for
    // a week without a pixel of shadow. Separate borders is what paints it,
    // and the hairline between rows then has to be the cells' own.
    await expect(getComputedStyle(table).borderCollapse).toBe('separate');
    const firstRow = table.querySelector<HTMLTableRowElement>('tbody tr')!;
    await expect(getComputedStyle(firstRow.cells[1]).borderBottomWidth).toBe(
      '1px',
    );

    // A pinned cell inherits its row's colour, so the hover has to be
    // opaque: a wash over the column it holds the place of would show that
    // column's text through it — which is what the user saw.
    await userEvent.hover(firstRow.cells[1]);
    const opaque = (colour: string) =>
      !colour.startsWith('rgba(') && !/\/\s*0?\.\d+\)/.test(colour);
    await waitFor(() => {
      const hovered = getComputedStyle(firstRow.cells[1]).backgroundColor;
      expect(opaque(hovered)).toBe(true);
      expect(hovered).toBe(getComputedStyle(firstRow).backgroundColor);
    });
    await userEvent.unhover(firstRow.cells[1]);

    // Narrowed until the middle really does scroll, and then scrolled from
    // one end to the other.
    //
    // *Which* columns are still held is not this story's to say any more:
    // at 420px the held group is over half the port, so the cap lets the
    // outermost pins go until it fits (D17-4, `PinnedGroupCapped`). What
    // this story is about holds either way — the two cells facing the
    // middle wear the edge and nobody else does — so the expectation is
    // read off the pinning the table is drawing rather than naming columns.
    const boundaries = () => {
      const held = [
        ...table.querySelectorAll<HTMLTableCellElement>('thead th[data-pin]'),
      ];
      const lefts = held.filter(
        cell => cell.dataset.pin === 'left' && cell.dataset.column !== 'select',
      );
      const rights = held.filter(cell => cell.dataset.pin === 'right');
      return new Set([lefts.at(-1), rights[0]]);
    };
    const framed = () => {
      const edged = boundaries();
      return {
        inner: edged.has(inner) ? ALL : NONE,
        left: edged.has(left) ? ALL : NONE,
        end: edged.has(end) ? ALL : NONE,
        actions: edged.has(actions) ? ALL : NONE,
      };
    };

    area.style.maxWidth = '420px';
    await waitFor(() =>
      expect(area.scrollWidth).toBeGreaterThan(area.clientWidth),
    );
    // The key is what the cap may never take, so there is always a left
    // boundary to look at.
    await expect(inner).toHaveAttribute('data-pin', 'left');
    await waitFor(() => expect(edges()).toEqual(framed()));
    for (const scrolled of [40, area.scrollWidth, 0]) {
      area.scrollLeft = scrolled;
      await waitFor(() => expect(edges()).toEqual(framed()));
    }

    // And nothing on the table says where it is scrolled to any more.
    await expect(table).not.toHaveAttribute('data-scrolled-left');
    await expect(table).not.toHaveAttribute('data-scrolled-right');
  },
};

/** The 20 columns the wide view saves, left to right. */
const WIDE_COLUMNS = [
  '运单号',
  '订单号',
  '客户',
  '收件人',
  '目的城市',
  '发货仓',
  '承运商',
  '运输方式',
  '状态',
  '时效',
  '标记',
  '件数',
  '重量',
  '运费',
  '已保价',
  '已签单',
  '发运日期',
  '创建时间',
  '跟踪链接',
  '备注',
];

/**
 * 20 columns and 50 rows: the shape every sticky rule was written for and
 * none of them could be checked against.
 *
 * Until this fixture existed the widest story was five columns, so "the
 * header stays put", "both summary rows stay put" and "the two frozen edges
 * hold while the middle scrolls" were only ever exercised on a table with
 * nothing much to scroll. Here the middle really does scroll, in both
 * directions at once, and the frozen edges have 18 columns passing under
 * them.
 */
export const WideTable: Story = {
  ...DisplayWideTable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;

    // The premise, both halves of it: 20 columns and 50 rows on one page.
    await waitFor(() =>
      expect(table.querySelectorAll('tbody tr')).toHaveLength(50),
    );
    await expect(columnLabels(table)).toEqual(WIDE_COLUMNS);
    // Sorted by ship date, then by amount, then by number: the two orders
    // that left on the 20th, dearest first.
    await expect(readColumn(table, '运单号').slice(0, 2)).toEqual([
      'YD-1040',
      'YD-1020',
    ]);
    // The three summaries are over all 50 rows, not over what fits.
    await expect(amountOf(readTotal(table, '运费'))).toBe(34480);
    await expect(readTotal(table, '件数')).toContain('197');
    await expect(readTotal(table, '重量')).toContain('558.5');

    // It scrolls sideways — which is the point of the fixture, and what no
    // other story could produce.
    await expect(area).toHaveAttribute('data-scrolls');
    await waitFor(() =>
      expect(area.scrollWidth).toBeGreaterThan(area.clientWidth),
    );

    const head = (table as HTMLTableElement).tHead!;
    const foot = (table as HTMLTableElement).tFoot!;
    const key = headerOf(table, '运单号');
    const middle = headerOf(table, '状态');
    const actions = table.querySelector<HTMLTableCellElement>(
      'thead th[data-column="actions"]',
    )!;
    await expect(key).toHaveAttribute('data-pin', 'left');
    await expect(middle).not.toHaveAttribute('data-pin');
    await expect(actions).toHaveAttribute('data-pin', 'right');

    /** Whether each cell draws an edge, on all three layers of one column. */
    const edged = (head: HTMLTableCellElement) =>
      ['thead', 'tbody', 'tfoot'].map(
        layer =>
          getComputedStyle(
            table.querySelector<HTMLTableRowElement>(`${layer} tr`)!.cells[
              head.cellIndex
            ],
          ).boxShadow !== 'none',
      );
    const ALL = [true, true, true];
    const NONE = [false, false, false];
    const edges = () => ({
      key: edged(key),
      middle: edged(middle),
      actions: edged(actions),
    });
    const FRAMED = { key: ALL, middle: NONE, actions: ALL };
    await waitFor(() => expect(edges()).toEqual(FRAMED));

    /**
     * Every layer that has to keep holding while the middle moves.
     *
     * The header sticks as a `<thead>` and the summaries as a `<tfoot>` —
     * an unpinned `<th>` is `relative`, which is what lets a pinned one be
     * its own positioned ancestor — so the rows are what is read here, and
     * the cells only where the pinning is what makes them stick.
     */
    const sticky = () => ({
      head: getComputedStyle(head).position,
      foot: getComputedStyle(foot).position,
      key: getComputedStyle(key).position,
      cell: getComputedStyle(
        table.querySelector<HTMLTableRowElement>('tbody tr')!.cells[
          key.cellIndex
        ],
      ).position,
    });
    const STUCK = {
      head: 'sticky',
      foot: 'sticky',
      key: 'sticky',
      cell: 'sticky',
    };
    await expect(sticky()).toEqual(STUCK);

    /** Where the three layers actually sit, to the pixel. */
    const held = () => ({
      headTop: Math.round(head.getBoundingClientRect().top),
      areaTop: Math.round(area.getBoundingClientRect().top),
      keyLeft: Math.round(key.getBoundingClientRect().left),
    });
    const resting = held();
    await expect(resting.headTop).toBe(resting.areaTop);

    // Scrolled to each end and back: the same two edges throughout, and the
    // frozen column has not moved a pixel.
    for (const left of [40, area.scrollWidth, area.scrollWidth / 2, 0]) {
      area.scrollLeft = left;
      await waitFor(() => expect(edges()).toEqual(FRAMED));
      await expect(sticky()).toEqual(STUCK);
      await expect(held().keyLeft).toBe(resting.keyLeft);
    }

    // And down past the fortieth row, which is the half no five-column story
    // could reach: the header is still at the top of the box.
    area.scrollTop = area.scrollHeight;
    await waitFor(() => expect(held().headTop).toBe(resting.areaTop));
    await expect(sticky()).toEqual(STUCK);
    // The header is still the header: at 20 columns, a column nobody can
    // name any more is a column nobody can read.
    await expect(columnLabels(table)).toEqual(WIDE_COLUMNS);
    await expect(
      Math.round(foot.getBoundingClientRect().bottom),
    ).toBeLessThanOrEqual(Math.round(area.getBoundingClientRect().bottom));
    area.scrollTop = 0;
  },
};

/**
 * The same 20 columns in a 420px column, where the held group is capped at
 * half the result area (D17-4).
 *
 * This is the one story that can weigh the rule, because it is the only one
 * where the held columns are a large share of the port: 232px of frozen
 * chrome — checkbox, waybill number, the host's actions — against a result
 * area that measures 286. jsdom lays nothing out, so the unit test can pin
 * which pin is let go but not what it buys; the number read here is the one
 * the reader actually gets, "middle ÷ result area", and it has a floor.
 *
 * Both directions, on the host's own width: the pin goes as the column
 * narrows and comes back as it widens, from the measurement rather than from
 * anything stored — the view is never marked unsaved, because the cap is a
 * rendering decision and the config still says `pinned`.
 */
export const PinnedGroupCapped: Story = {
  ...DisplayPinnedGroupCapped,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    await expect(host.getBoundingClientRect().width).toBe(420);

    const key = headerOf(table, '运单号');
    const cell = (column: string) =>
      table.querySelector<HTMLTableCellElement>(
        `thead th[data-column="${column}"]`,
      )!;
    const actions = cell('actions');
    const select = cell('select');

    /** Everything still held, and what that leaves of the visible width. */
    const held = () =>
      [...table.querySelectorAll<HTMLElement>('thead th[data-pin]')].reduce(
        (sum, node) => sum + node.getBoundingClientRect().width,
        0,
      );
    const middleShare = () => (area.clientWidth - held()) / area.clientWidth;

    // The premise: a result area far narrower than the table it holds.
    await expect(area.clientWidth).toBeLessThan(420);
    await waitFor(() =>
      expect(area.scrollWidth).toBeGreaterThan(area.clientWidth),
    );

    // The rule itself. Before the cap this read 0.19 — 54px of middle
    // against nineteen columns, the narrowest of them 44px wide.
    await waitFor(() => expect(middleShare()).toBeGreaterThanOrEqual(0.5));

    // What was let go is the outermost of the group, and what stays is the
    // row's identity: the key, and the checkbox beside it.
    await expect(actions).not.toHaveAttribute('data-pin');
    await expect(key).toHaveAttribute('data-pin', 'left');
    await expect(select).toHaveAttribute('data-pin', 'left');
    // The whole column and not the header alone — the buttons travel with
    // their row now.
    await expect(getComputedStyle(actions).position).not.toBe('sticky');
    const firstRow = table.querySelector<HTMLTableRowElement>('tbody tr')!;
    await expect(
      getComputedStyle(firstRow.cells[actions.cellIndex]).position,
    ).not.toBe('sticky');
    await expect(getComputedStyle(key).position).toBe('sticky');

    // And nothing was written: the view is exactly as it was saved.
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();

    // Widened, the pin comes back on its own.
    host.style.width = '1200px';
    await waitFor(() => expect(actions).toHaveAttribute('data-pin', 'right'));
    await expect(getComputedStyle(actions).position).toBe('sticky');

    // Narrowed again, it goes again — and the floor holds a second time.
    host.style.width = '420px';
    await waitFor(() => expect(actions).not.toHaveAttribute('data-pin'));
    await expect(middleShare()).toBeGreaterThanOrEqual(0.5);
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
  },
};

/**
 * The column settings on a table wide enough to need them (F-18), in the
 * browser, because all three halves of the answer are geometry.
 *
 * jsdom lays nothing out, so the unit tests can pin which rows are listed
 * and which controls are refused, but not the three things that were
 * actually broken: the popup grew past the bottom of the screen with no way
 * to reach the rest of it, the search line scrolled away with the rows it
 * governs, and twenty columns read as one undifferentiated list.
 */
async function readColumnSettings(canvasElement: HTMLElement) {
  const canvas = within(canvasElement);
  await canvas.findByRole('table');
  await userEvent.click(
    canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
  );
  const popup = await waitFor(() => {
    const node = document.querySelector<HTMLElement>(
      '[data-slot="popover-content"]',
    );
    expect(node).not.toBeNull();
    return node!;
  });
  const list = popup.querySelector<HTMLElement>('[data-slot="column-list"]')!;
  const search = popup.querySelector<HTMLInputElement>(
    '[data-slot="column-search"]',
  )!;
  const fields = () =>
    [...list.querySelectorAll<HTMLElement>('[data-slot="column-setting"]')].map(
      row => row.dataset.field,
    );
  const headings = () =>
    [
      ...popup.querySelectorAll<HTMLElement>(
        '[data-slot="column-region-heading"], [data-slot="column-group-heading"]',
      ),
    ].map(heading => heading.textContent);

  // It stays on the screen. The popup is portalled to the body, so nothing
  // else scrolls it: before it had a scroll port of its own, twenty-one rows
  // simply ran off the bottom edge.
  const box = popup.getBoundingClientRect();
  await expect(Math.round(box.top)).toBeGreaterThanOrEqual(0);
  await expect(Math.round(box.bottom)).toBeLessThanOrEqual(
    Math.round(window.innerHeight),
  );

  // And the list is the part that scrolls, not the popup: the title and the
  // search line stay where they are while the rows go past them.
  await waitFor(() =>
    expect(list.scrollHeight).toBeGreaterThan(list.clientHeight),
  );
  const searchTop = Math.round(search.getBoundingClientRect().top);
  list.scrollTop = list.scrollHeight;
  await expect(list.scrollTop).toBeGreaterThan(0);
  await expect(Math.round(search.getBoundingClientRect().top)).toBe(searchTop);
  // The bottom row is reachable, which is the whole complaint.
  const last = [...list.querySelectorAll<HTMLElement>('li')].at(-1)!;
  await expect(
    Math.round(last.getBoundingClientRect().bottom),
  ).toBeLessThanOrEqual(Math.round(list.getBoundingClientRect().bottom) + 1);
  list.scrollTop = 0;

  // The areas first, the catalogue inside the middle one, in the order the
  // definition declares its groups — and the fields no group lists in front
  // of all of them, under no heading of their own.
  await expect(headings()).toEqual([
    zhCN['label.columns.pin.left'],
    zhCN['label.columns.pin.none'],
    '收发双方',
    '运输',
    '计费',
    '时间',
    zhCN['label.columns.pin.right'],
  ]);
  const whole = fields();
  await expect(whole.slice(0, 8)).toEqual([
    'id',
    'orderNo',
    'status',
    'tags',
    'signed',
    'trackingUrl',
    'note',
    'customer',
  ]);

  // A search narrows the rows, and refuses the ordering it is hiding the
  // neighbours of.
  await userEvent.type(search, '运费');
  await waitFor(() => expect(fields()).toEqual(['amount']));
  await expect(
    popup.querySelector('[data-slot="column-filtered"]')?.textContent,
  ).toBe(zhCN['label.columns.filtered']);
  await expect(
    within(list)
      .getByRole('button', {
        name: say('label.columns.drag', { field: '运费' }),
      })
      .hasAttribute('disabled'),
  ).toBe(true);

  await userEvent.clear(search);
  await userEvent.type(search, 'zzz');
  await waitFor(() => expect(fields()).toEqual([]));
  await expect(
    popup.querySelector('[data-slot="column-none"]')?.textContent,
  ).toContain(zhCN['label.field.none']);

  // Cleared, the whole list is back, in the order it was in.
  await userEvent.clear(search);
  await waitFor(() => expect(fields()).toEqual(whole));
  await userEvent.keyboard('{Escape}');
}

/** Twenty columns in a full-width workbench. */
export const WideTableColumnSettings: Story = {
  ...DisplayWideTable,
  play: async ({ canvasElement }) => {
    await readColumnSettings(canvasElement);
  },
};

/**
 * The same panel in a 420px column — phone, split screen, a host's side
 * panel. The popup is portalled to the body and placed against a trigger
 * near the right edge of a narrow host, which is where a popup that cannot
 * scroll and one that cannot fit look the same.
 */
export const NarrowHostColumnSettings: Story = {
  ...DisplayPinnedGroupCapped,
  play: async ({ canvasElement }) => {
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    await expect(host.getBoundingClientRect().width).toBe(420);
    await readColumnSettings(canvasElement);
  },
};

/**
 * The column names alone, without the sort marks beside them.
 *
 * `readHeaders` reads the whole header cell, and three sorted columns carry
 * their place in that order as text — `发运日期1` — so a wide table sorted
 * three ways cannot be read by name any other way.
 */
function columnLabels(table: HTMLElement): string[] {
  return [
    ...table.querySelectorAll<HTMLElement>('thead [data-slot="column-label"]'),
  ].map(label => label.textContent?.trim() ?? '');
}

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
 * The way in to auto refresh: the `▾` beside the refresh button.
 *
 * jsdom can say what the menu holds; only a browser can say that it opens in
 * front of the workbench and that a click on a rung lands on the rung — a
 * control added to a toolbar opens a popup portalled out of it, and where
 * that popup paints is decided by the whole page (see
 * `PopupsOverRaisedHostLayer` below).
 */
export const AutoRefresh: Story = {
  ...DisplayAutoRefresh,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const cadence = () =>
      canvasElement.querySelector<HTMLElement>('[data-slot="refresh-cadence"]');
    const seconds = say('label.refresh.seconds', { count: 30 });
    /** The number on the key, whatever second of the count it is. */
    const count = () => Number(cadence()?.textContent?.replace(/\D/g, ''));

    // The saved view already refreshes itself, so the credential is on the
    // button before anything is pressed — and it is counting down to the
    // next refresh rather than repeating the cadence the menu holds.
    await waitFor(() => expect(count()).toBeGreaterThan(0));
    const started = count();
    await expect(started).toBeLessThanOrEqual(30);
    // Real seconds, ticking: this is the one thing jsdom cannot show, since
    // a second there is whatever the test says it is.
    await waitFor(() => expect(count()).toBeLessThan(started), {
      timeout: 4_000,
    });
    // The sentence a screen reader gets is the **cadence**, not the count:
    // a number that changes every second must not be read out every second,
    // which is why the count itself is `aria-hidden`.
    await expect(
      canvasElement.querySelector('[data-slot="refresh-now"]'),
    ).toHaveAttribute(
      'aria-description',
      say('label.refresh.on', { interval: seconds }),
    );
    await expect(
      canvasElement.querySelector('[data-slot="refresh-countdown"]'),
    ).toHaveAttribute('aria-hidden', 'true');
    // The box is as wide as the widest reading this interval can produce, so
    // "10s" → "9s" never walks the `▾` beside it across the bar.
    const box = canvasElement.querySelector<HTMLElement>(
      '[data-slot="refresh-countdown"]',
    )!;
    const width = box.getBoundingClientRect().width;
    await waitFor(() => expect(count()).toBeLessThan(started - 1), {
      timeout: 4_000,
    });
    await expect(box.getBoundingClientRect().width).toBe(width);

    const chevron = canvasElement.querySelector<HTMLElement>(
      '[data-slot="refresh-interval"]',
    )!;
    await userEvent.click(chevron);
    const menu = await within(document.body).findByRole('menu');
    // Portalled out of the surface, and still the thing a click at its
    // middle reaches.
    await expect(menu.closest('[data-slot="view-surface"]')).toBeNull();
    await expect(inFrontOf(menu)).toBe(true);

    // Off, then the ladder the limits admit — nothing disabled, because an
    // interval the kernel would refuse is not offered at all.
    await expect(
      within(menu)
        .getAllByRole('menuitemradio')
        .map(item => item.textContent),
    ).toEqual([zhCN['label.refresh.off'], ...LADDER.map(cadenceOf)]);
    // The one in force is the one marked.
    await expect(
      within(menu).getByRole('menuitemradio', { name: seconds }),
    ).toHaveAttribute('aria-checked', 'true');

    // Choosing edits the view's own config and applies it, so the cadence
    // moves and the title bar says the view is now unsaved.
    const minutes = say('label.refresh.minutes', { count: 5 });
    await userEvent.click(
      within(menu).getByRole('menuitemradio', { name: minutes }),
    );
    // Counting down from the new interval — the fifth minute reads "4 min"
    // for all but its first second, so either is the right answer here.
    await waitFor(() =>
      expect([minutes, say('label.refresh.minutes', { count: 4 })]).toContain(
        cadence()?.textContent,
      ),
    );
    await expect(canvas.getByText(zhCN['label.header.unsaved'])).toBeVisible();

    // And off again, from the keyboard: the chevron opens on Enter and hands
    // focus to the options.
    chevron.focus();
    await userEvent.keyboard('{Enter}');
    const reopened = await within(document.body).findByRole('menu');
    await userEvent.click(
      within(reopened).getByRole('menuitemradio', {
        name: zhCN['label.refresh.off'],
      }),
    );
    await waitFor(() => expect(cadence()).toBeNull());
  },
};

/** The intervals the default limits admit, as the menu lists them. */
const LADDER = [30, 60, 300];

/** One interval as the control writes it: "30s", "5 min", "1 h". */
const cadenceOf = (seconds: number): string =>
  seconds >= 3600
    ? say('label.refresh.hours', { count: seconds / 3600 })
    : seconds >= 60
      ? say('label.refresh.minutes', { count: seconds / 60 })
      : say('label.refresh.seconds', { count: seconds });

/**
 * Every popup kind, as the workbench opens it.
 *
 * One entry per wrapper in `ui/popups.tsx` that a user of this workbench can
 * reach: a popover, a menu, a select's list, a tooltip and a dialog. The
 * combobox is the sixth wrapper and no surface here opens one, so it is held
 * to the same rule in `test/popups.test.tsx` instead.
 */
const POPUP_KINDS: readonly {
  name: string;
  slot: string;
  trigger: string;
  /** Opened by pointing at the trigger rather than by pressing it. */
  hover?: boolean;
}[] = [
  {
    name: 'popover',
    slot: 'popover-content',
    // The column-settings popover, which is where this first went wrong.
    trigger: '[data-slot="result-toolbar"] [data-slot="popover-trigger"]',
  },
  {
    name: 'menu',
    slot: 'dropdown-menu-content',
    // Found by what it does rather than by its slot: every icon-only trigger
    // on this bar is wrapped in a tooltip now, and the outer trigger's
    // `data-slot` replaces the menu's own on the one element they share.
    trigger: '[aria-haspopup="menu"]',
  },
  {
    name: 'select',
    slot: 'select-content',
    // The page-size control, which is why this story pages its rows.
    trigger: '[data-slot="select-trigger"]',
  },
  {
    name: 'tooltip',
    slot: 'tooltip-content',
    trigger: '[data-slot="tooltip-trigger"]',
    hover: true,
  },
  {
    name: 'dialog',
    slot: 'dialog-content',
    // The manager, behind the sidebar's gear: a dialog portals a backdrop of
    // its own and is centred on the viewport rather than on the workbench.
    trigger: `[aria-label="${zhCN['label.manage.open']}"]`,
  },
];

/**
 * The layer this package's popups paint on, and the one a host can move.
 *
 * Whatever the host raises, a popup has to come out in front of it — the fix
 * is a `z-index` on the *positioner*, written as a style in `ui/popups.tsx`
 * because the positioner is no `.fve-root` and every rule of the stylesheet
 * is pinned inside one. Before it, a positioner stayed at `z-index: auto` and
 * every popup here painted at level 0, in front of the page only because its
 * portal is last in the body: a host layer at `z-index: 1` covered the lot.
 *
 * The premise is checked as carefully as the claim. A raised layer proves
 * nothing if some ancestor trapped it in a stacking context of its own, since
 * it would then rank by document order and the popups would win without any
 * of this — so the layer is read for its level, for the absence of such an
 * ancestor, and for actually covering the view before a single popup opens.
 */
export const PopupsOverRaisedHostLayer: Story = {
  ...DisplayPopupsOverRaisedHostLayer,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const raised = document.querySelector<HTMLElement>('[data-raised-host]')!;

    // The premise, in the three parts it has.
    await expect(getComputedStyle(raised).zIndex).toBe('10');
    await expect(trappedIn(raised)).toBeNull();
    await expect(inFrontOf(raised)).toBe(true);

    /** The popup of that kind, open and placed, and what opened it. */
    async function open(kind: (typeof POPUP_KINDS)[number]) {
      // The first trigger a pointer can reach: an icon inside a button is
      // `pointer-events: none` by the button's own rule, and a tooltip on one
      // is no more reachable for the user than it is here.
      const trigger = [
        ...canvasElement.querySelectorAll<HTMLElement>(kind.trigger),
      ].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none');
      await expect(trigger, `no ${kind.name} to open`).toBeDefined();
      // The raised layer covers the trigger as it covers everything else, so
      // the event goes to the element rather than to a point on the screen.
      if (kind.hover) await userEvent.hover(trigger!);
      else await userEvent.click(trigger!);

      // Open, and laid out: a popup is in the document before it is placed,
      // and a hit test on a box of no size answers about the page behind it.
      const popup = await waitFor(() => {
        const found = document.body.querySelector<HTMLElement>(
          `[data-slot="${kind.slot}"]`,
        );
        if (!found || found.hasAttribute('data-closed'))
          throw new Error(`no open ${kind.name}`);
        const box = found.getBoundingClientRect();
        if (box.width === 0 || box.height === 0)
          throw new Error(`the ${kind.name} has no box yet`);
        return found;
      });
      // Portalled out of the surface, which is exactly why this can go wrong.
      await expect(popup.closest('[data-slot="view-surface"]')).toBeNull();
      return { popup, trigger: trigger! };
    }

    /**
     * Shut again before the next one opens. A popup on its way out stays in
     * the document for the length of its animation, so what is waited for is
     * that it is no longer open.
     */
    async function close(
      kind: (typeof POPUP_KINDS)[number],
      trigger: HTMLElement,
    ) {
      if (kind.hover) await userEvent.unhover(trigger);
      else await userEvent.keyboard('{Escape}');
      await waitFor(() => {
        const leaving = document.body.querySelector(
          `[data-slot="${kind.slot}"]`,
        );
        expect(leaving === null || leaving.hasAttribute('data-closed')).toBe(
          true,
        );
      });
    }

    /** The element the level is written on: a dialog has no positioner. */
    const layerOf = (kind: (typeof POPUP_KINDS)[number], popup: HTMLElement) =>
      kind.slot === 'dialog-content' ? popup : popup.parentElement!;

    for (const kind of POPUP_KINDS) {
      const { popup, trigger } = await open(kind);
      // The cause, and then the effect the user sees.
      await expect(getComputedStyle(layerOf(kind, popup)).zIndex).toBe('50');
      await expect(inFrontOf(popup), `the ${kind.name} is buried`).toBe(true);
      await close(kind, trigger);
    }

    // And the number really is what decides, which is what `--fve-popup-z-index`
    // offers a host whose own chrome stacks above 50. Turned *below* what this
    // host raised, the same popover goes behind it — the variable is read from
    // `:root`, where a host sets it beside the colour tokens.
    const root = document.documentElement;
    try {
      root.style.setProperty('--fve-popup-z-index', '3');
      const { popup, trigger } = await open(POPUP_KINDS[0]);
      await expect(
        getComputedStyle(layerOf(POPUP_KINDS[0], popup)).zIndex,
      ).toBe('3');
      await expect(inFrontOf(popup)).toBe(false);
      await close(POPUP_KINDS[0], trigger);
    } finally {
      root.style.removeProperty('--fve-popup-z-index');
    }
  },
};

/** What WCAG 1.4.11 asks of the visual information a control is known by. */
const NON_TEXT_CONTRAST = 3;

/**
 * The edge every unticked control is made of, measured in the browser.
 *
 * A checkbox nobody has ticked is *only* this ring — there is nothing else on
 * screen to say a control is there — and so are the outlines of an Input and
 * of a Select trigger. All three draw it with `border-input`, the theme's
 * `--input`, which is why this is measured rather than argued: a stylesheet
 * says `oklch(…)` and Tailwind's opacity modifiers say `color-mix(…)`, while
 * what reaches the eye is the cascaded colour composited over whatever is
 * behind it. jsdom paints none of that, so this regression lives here.
 *
 * The three the theme has to carry are on one screen: the table's select-all
 * Checkbox, the page-size Select under the rows, and — once a number field is
 * ticked into the conditions, the way `PickSeveralFields` does it — an Input
 * with no value in it yet.
 */
const controlBorders = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    // Two stories measuring the same theme would both pass and prove half of
    // this, so the mode is read off the surface before anything else.
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.add'] }),
    );
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '金额' }),
    );
    // A text input that stands on its own: the picker's search field. The
    // one inside a condition pill is borderless by design (D12 — the pill
    // is the field, and draws the one border), so it is not what 1.4.11
    // asks about; its edge is the pill's.
    // The picker's search field is an `InputGroupInput`, and the
    // `InputGroup` around it is what draws the border; measure that box.
    const input = measureBorderContrast(
      within(picker)
        .getByRole('textbox', { name: zhCN['label.field.search'] })
        .closest<HTMLElement>('[data-slot="input-group"]')!,
    );
    // Shut behind itself, so nothing is measured through a popup and axe
    // judges the page as a user would leave it.
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );
    // Ticked, a checkbox is a filled square and this stops being the whole
    // of it; the measurement is about the state that has nothing else.
    const checkbox = canvas.getByRole('checkbox', {
      name: zhCN['label.record.select-all'],
    });
    await expect(checkbox).not.toBeChecked();

    const controls = {
      checkbox,
      select: within(paginationBar(canvasElement)).getByRole('combobox', {
        name: zhCN['label.pagination.page-size'],
      }),
    };

    // All three are measured before anything is asserted, so a failure says
    // what every control came to rather than stopping at the first one.
    const measured = [
      { name: 'input', ...input },
      ...Object.entries(controls).map(([name, control]) => ({
        name,
        ...measureBorderContrast(control),
      })),
    ];
    const report = measured
      .map(
        ({ name, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 (${colors.border} on ${colors.fill} over ${colors.surface})`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
  },
});

/**
 * 焦点指示在两个主题里都 ≥3:1，而且一屏只有一种画法。
 *
 * vendored 的 `Button` 以 1px `border-ring` 加 3px 半透明光晕表示焦点；表头的排序
 * 按钮与已应用条上的 ✕ 从前是裸 `<button>` 各抄一份同款配方，现在就是那个
 * `Button`（`variant="ghost"`），所以一屏只有一种画法。评审量到 `--ring` 在 `0.708` 时边线只有 2.59:1、光晕
 * 1.54:1，三处又各画各的（一处还是 UA 的 `outline: auto`）。焦点由 Tab 键送到
 * 目标上：脚本调 `focus()` 不一定算 `:focus-visible`，键盘一定算。
 */
const focusIndicators = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    // A vendored `Button` that is always enabled here — the editor's toggle
    // is a Base UI toggle with an `input` border of its own, not this.
    const columnsButton = canvas.getByRole('button', {
      name: zhCN['label.toolbar.columns'],
    });
    const sortButton = table.querySelector<HTMLElement>('thead button')!;
    const unset = canvas.getAllByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.unset-of'].split(' ')[0]}`),
    })[0];

    const measured: { name: string; ratio: number; colors: object }[] = [];
    await tabTo(columnsButton);
    // The vendored button has `transition-all`: its border reaches the ring
    // colour over 150ms, so the steady state is what is measured.
    await settled(() => getComputedStyle(columnsButton).borderTopColor);
    measured.push({ name: 'button', ...measureBorderContrast(columnsButton) });
    await tabTo(unset);
    await settled(() => getComputedStyle(unset).borderTopColor);
    measured.push({ name: 'unset', ...measureBorderContrast(unset) });
    await tabTo(sortButton);
    await settled(() => getComputedStyle(sortButton).borderTopColor);
    measured.push({ name: 'sort', ...measureBorderContrast(sortButton) });

    const report = measured
      .map(
        ({ name, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 ${JSON.stringify(colors)}`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
    // The same recipe everywhere: a focused bare button wears the colour the
    // vendored button puts on its border, and the same halo.
    const border = getComputedStyle(sortButton).borderTopColor;
    const halo = getComputedStyle(sortButton).boxShadow;
    await tabTo(columnsButton);
    await settled(() => getComputedStyle(columnsButton).borderTopColor);
    await expect(getComputedStyle(columnsButton).borderTopColor).toBe(border);
    await expect(getComputedStyle(columnsButton).boxShadow).toBe(halo);
  },
});

/**
 * Resolves once a transitioning value has stopped changing: two reads 50ms
 * apart that agree. A value with no transition agrees at once.
 */
async function settled(read: () => string): Promise<void> {
  await waitFor(async () => {
    const before = read();
    await new Promise(resolve => setTimeout(resolve, 50));
    if (read() !== before) throw new Error('The value is still moving.');
  });
}

/**
 * Presses Tab until the element has focus, so `:focus-visible` holds.
 *
 * A control inside a `role="toolbar"` is not its own tab stop — the bar is
 * one stop and the arrows move along it (Base UI's `Toolbar`) — so the keys
 * pressed here are the keys a keyboard would actually press: Tab as far as
 * the bar, then ArrowRight to the control.
 */
async function tabTo(target: HTMLElement): Promise<void> {
  const toolbar = target.closest('[role="toolbar"]');
  for (let presses = 0; presses < 80; presses += 1) {
    if (document.activeElement === target) return;
    if (toolbar?.contains(document.activeElement)) break;
    await userEvent.tab();
  }
  for (let presses = 0; toolbar && presses < 20; presses += 1) {
    if (document.activeElement === target) return;
    await userEvent.keyboard('{ArrowRight}');
  }
  if (document.activeElement === target) return;
  throw new Error('Tab never reached the target.');
}

export const FocusIndicatorsInLightTheme: Story = focusIndicators('light');
export const FocusIndicatorsInDarkTheme: Story = focusIndicators('dark');

/**
 * 暗色下的行线看得见。
 *
 * 分隔线不是控件，不欠 3:1，但 10% 白在暗色卡片上量到 1.32:1——一张没有行的
 * 表。这里量的是 `tbody` 行的下边线压在它自己的底色与卡片之上的层叠色。
 */
export const DarkHairlines: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme: 'dark' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const row = table.querySelector<HTMLElement>('tbody tr')!;
    const { ratio, colors } = measureBorderContrast(row, 'bottom');
    await expect(
      ratio,
      `${colors.border} on ${colors.fill} over ${colors.surface}`,
    ).toBeGreaterThanOrEqual(1.5);
  },
};

/**
 * 一枚徽章在它所在的行上还看得见——静息、悬停、选中都算。
 *
 * 行底是会动的：选中走 `bg-muted`，悬停走同一档灰的不透明 `color-mix`，而
 * `secondary` 徽章的底色正是那一档——量到 **1.00:1**，徽章直接归零成一个词。
 * 这里量的是徽章最外那 1px 压在行底上的层叠色（`onSurface`）：没有语气的徽章
 * 靠 `border-input` 的边说话，有语气的那几枚边是透明的、靠自己的实底说话，
 * 所以同一个数对两族都成立。jsdom 不套样式表，这个数只有真浏览器给得出。
 */
const badgesOnRows = (theme: 'light' | 'dark'): Story => ({
  ...DisplayCellFamily,
  args: { ...DisplayCellFamily.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    const rows = [...(table as HTMLTableElement).tBodies[0].rows];

    /** Every badge of one row, against the ground that row is on. */
    const onRow = (row: HTMLTableRowElement, state: string) =>
      [...row.querySelectorAll<HTMLElement>('[data-slot="badge"]')].map(
        badge => ({
          name: `${state} ${badge.dataset.tone} "${badge.textContent}"`,
          ...measureBorderContrast(badge),
        }),
      );

    // Selected first: the row takes `--muted`, which is the untoned badge's
    // own fill, so this is the ground that used to erase it.
    await userEvent.click(
      within(rows[0]).getByRole('checkbox', {
        name: say('label.record.select', { key: 'SO-1001' }),
      }),
    );
    await waitFor(() =>
      expect(rows[0]).toHaveAttribute('data-state', 'selected'),
    );
    await settled(() => getComputedStyle(rows[0]).backgroundColor);
    const measured = onRow(rows[0], 'selected');

    // Hovered, which is the same grey mixed halfway into the page.
    await userEvent.hover(rows[1]);
    await settled(() => getComputedStyle(rows[1]).backgroundColor);
    measured.push(...onRow(rows[1], 'hovered'));
    await userEvent.unhover(rows[1]);

    // And at rest, so the two above are read against the one they moved from.
    measured.push(...onRow(rows[2], 'resting'));

    const report = measured
      .map(
        ({ name, onSurface, colors }) =>
          `${name} ${onSurface.toFixed(2)}:1 (${colors.border} over ${colors.surface})`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ onSurface }) => onSurface)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(BADGE_ON_ROW_CONTRAST);
  },
});

/**
 * 徽章与行底的下限。三档行底共用一档 3% 灰是设计，徽章因此只欠"还看得出是
 * 一枚徽章"，而不是 1.4.11 对控件要的 3:1。
 */
const BADGE_ON_ROW_CONTRAST = 1.5;

export const BadgesOnRowsInLightTheme: Story = badgesOnRows('light');
export const BadgesOnRowsInDarkTheme: Story = badgesOnRows('dark');

/**
 * 首尾两条灰带把数据行夹在中间，带上的列名是正文的墨色。
 *
 * 表头从前和数据行同为 `bg-background`，中间只有一根发丝线和一行灰字——用户
 * 2026-09-22 的评审说第一行读起来像表头的一部分。现在表头与汇总层是**同一档
 * 灰**（`ui/record/columns.ts` 的 `BAND`），所以这条故事量的第一件事是两条带
 * 子的层叠色**逐字节相等**，第二件事是它们都不等于行底——一档谁也夹不住的灰
 * 不是带子。
 *
 * 列名因此换回 registry 自己的 `text-foreground`：`text-muted-foreground`
 * 压在这一档灰上量到 4.34:1，跌破 1.4.3 的 4.5，而它是整个表面上最小的一号
 * 字。两条带子之间只有格子自己那 1px 发丝线——`<thead>` 上从前那条
 * `border-b-2` 写在 `<tr>` 上，而分开的边框模型里行没有自己的边，屏幕上从来
 * 就是 1px；带子的底色接手了分隔这件事。jsdom 不套样式表，这些数只有真浏览
 * 器给得出。
 */
const headerBand = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = (await canvas.findByRole('table')) as HTMLTableElement;
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    // 两条量同一主题的故事都会通过并各证一半，所以先把模式读出来。
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    const head = table.tHead!.rows[0];
    const foot = table.tFoot!.rows[0];
    const row = table.tBodies[0].rows[0];
    const band = measureFillContrast(head).colors.fill;
    const footBand = measureFillContrast(foot).colors.fill;
    const rowFill = measureFillContrast(row).colors.fill;
    await expect(band, `${theme} — 表头 ${band}，汇总 ${footBand}`).toBe(
      footBand,
    );
    await expect(band, `${theme} — 带子与行底同色 ${band}`).not.toBe(rowFill);

    // 分隔只有格子自己那一根发丝线，而不是写在行上的那 2px。
    await expect(getComputedStyle(head.cells[0]).borderBottomWidth).toBe('1px');

    // 带上的每一个列名，压在带子上。字在排序按钮里，按钮静息时没有底色，所以
    // 量的还是带子。
    const measured = [...head.cells]
      .filter(cell => (cell.textContent ?? '').trim().length > 0)
      .map(cell => ({
        name: cell.textContent!.trim(),
        ...measureTextContrast(cell.querySelector('button') ?? cell),
      }));
    const report = measured
      .map(
        ({ name, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 (${colors.text} on ${colors.background})`,
      )
      .join('; ');
    await expect(measured.length).toBeGreaterThan(0);
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);
  },
});

export const HeaderBandInLightTheme: Story = headerBand('light');
export const HeaderBandInDarkTheme: Story = headerBand('dark');

/**
 * 每一档语气的字压在它自己的底色上都读得出来，而且语气从来不是唯一的区别。
 *
 * 徽章是实底加页面底色写字（`ui/variants.tsx`），所以这里量的就是这一对搭配
 * 本身：底色是不透明的，量出来的数与徽章落在哪一档行底上无关。用户
 * 2026-09-22 的评审说「待出库」这枚白字压橙底在最小的一号字上（实测 13px）
 * 贴着 AA 的边——旧的
 * 700／600 档在亮色下量到 4.94／5.05／4.77，过线不到 6%；浅色三档因此各下
 * 一档（`styles.css`），这条故事守的是新的余量。软配方（淡底 + 深字）同一批
 * 量下来是 5.60／5.61／4.84，比实底更低，且淡底离行底只有 1.16–1.22:1，所以
 * 没有取。
 *
 * 颜色之外还要有字（WCAG 1.4.1）：四档语气都在场，每一枚都有非空的标签，而且
 * 没有两档共用同一个词。
 */
const toneBadgeInk = (theme: 'light' | 'dark'): Story => ({
  ...DisplayCellFamily,
  args: { ...DisplayCellFamily.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    const badges = [
      ...table.querySelectorAll<HTMLElement>('[data-slot="badge"][data-tone]'),
    ];
    const words = new Map<string, Set<string>>();
    for (const badge of badges) {
      const label = (badge.textContent ?? '').trim();
      await expect(label, '一枚没有字的徽章只剩颜色').not.toBe('');
      const tone = badge.dataset.tone!;
      words.set(tone, (words.get(tone) ?? new Set()).add(label));
    }
    await expect([...words.keys()].sort()).toEqual([
      'danger',
      'neutral',
      'success',
      'warning',
    ]);
    // 一个词只属于一档语气，否则读者就只能靠颜色分辨这两档。
    const all = [...words.values()].flatMap(set => [...set]);
    await expect(all.length).toBe(new Set(all).size);

    const measured = badges.map(badge => ({
      name: `${badge.dataset.tone} "${(badge.textContent ?? '').trim()}"`,
      size: getComputedStyle(badge).fontSize,
      ...measureTextContrast(badge),
    }));
    const report = measured
      .map(
        ({ name, size, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 @${size} (${colors.text} on ${colors.background})`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);
  },
});

export const ToneBadgeInkInLightTheme: Story = toneBadgeInk('light');
export const ToneBadgeInkInDarkTheme: Story = toneBadgeInk('dark');

/** The light theme's `--input`, over the card and the header it sits on. */
export const ControlBordersInLightTheme: Story = controlBorders('light');

/**
 * The same controls with the surface pinned dark, where the token is white at
 * an opacity and carries the `bg-input/30` fill with it.
 */
export const ControlBordersInDarkTheme: Story = controlBorders('dark');

/**
 * 状态条在两种主题下都读得出来，而且还是一行高。
 *
 * 它是 registry `Alert` 的紧凑变体（`ui/alerts.tsx`），tone 只改文字与边的
 * 颜色，底色是 `bg-card`——所以"读不读得出来"这一问在两种主题下是两道题：
 * 亮色下 `--warning` 压在白卡片上，暗色下同一个 token 压在 0.205 的卡片上。
 * jsdom 不套样式表，这两个数只有真浏览器给得出。一行高是 D1 的约束，这里
 * 按实测高度守着：一句话、没展开的发现、行尾的按钮，都不该把结果顶下去。
 */
const calloutTone = (
  theme: 'light' | 'dark',
  base: Story,
  tone: 'error' | 'warning',
): Story => ({
  ...base,
  args: { ...base.args, theme },
  play: async ({ canvasElement }) => {
    // Two stories measuring the same theme would both pass and prove half of
    // this, so the mode is read off the surface before anything else.
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-surface"]'),
      ).toHaveAttribute('data-theme', theme),
    );

    // A config that will not run has no table to wait for — the strip saying
    // so is the thing that arrives.
    const strip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        `[data-slot="status-strip"][data-tone="${tone}"]`,
      );
      if (!found) throw new Error(`No ${tone} status strip on screen.`);
      return found;
    });

    // The sentence, which is the element that carries the tone's colour.
    const sentence = strip.querySelector<HTMLElement>(
      '[data-slot="alert-title"]',
    )!;
    const { ratio, colors } = measureTextContrast(sentence);
    await expect(
      ratio,
      `${theme} ${tone} — ${colors.text} on ${colors.background}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);

    // D1: one line high. The icon is 16px and the toggle beside it is the
    // `xs` button (24px), so a line of it clears 24 and nothing above 40 is
    // still one line.
    await expect(
      strip.getBoundingClientRect().height,
      `${theme} ${tone} — the strip is not one line high`,
    ).toBeLessThanOrEqual(ONE_LINE_HIGH);
  },
});

/** 一行的上限（px）：16px 的图标、24px 的 `xs` 按钮，加上 4px 的上下内边距。 */
const ONE_LINE_HIGH = 40;

export const ErrorCalloutInLightTheme: Story = calloutTone(
  'light',
  DisplayNeedsFixing,
  'error',
);
export const ErrorCalloutInDarkTheme: Story = calloutTone(
  'dark',
  DisplayNeedsFixing,
  'error',
);
export const WarningCalloutInLightTheme: Story = calloutTone(
  'light',
  DisplayTotalCoversThisPageOnly,
  'warning',
);
export const WarningCalloutInDarkTheme: Story = calloutTone(
  'dark',
  DisplayTotalCoversThisPageOnly,
  'warning',
);

/**
 * 删除确认上那颗「删除」读得出来，而且它盖住的那张列表看得出被盖住了。
 *
 * registry 的 `destructive` 按钮是一抹 10% 淡彩（`bg-destructive/10` 配
 * `text-destructive`），跟 `ui/record.md` 记过的徽章是同一个陷阱：淡彩把底色
 * 朝字的那个色相挪过去，字于是压在一个已经被自己染过的底上——这颗按钮在亮色
 * 下量到 **3.97:1**（14px），够不着 1.4.3 的 4.5。修法也是同一个：拿 token 填
 * 色、拿 token 自己的 `-foreground` 写字（`ui/variants.tsx` 的
 * `DestructiveAction`）。
 *
 * 顺带量第二件事：这个对话框是从**视图管理器**（一个 `Dialog`）的某一行上抬起
 * 来的，而 Base UI 默认**根本不画**嵌套弹层的遮罩——下面那张列表一点没被压暗，
 * 确认框读起来像是掉进列表里的又一张白卡片（两张 `bg-popover` 互量正好
 * 1.00:1）。所以 `ui/popups.tsx` 的遮罩改成 `forceRender` 并夹到
 * `ALERT_DIALOG_BACKDROP_DIM`。暗色下这半还是不够：两张卡片都是
 * `oklch(0.205)`，黑纱再厚也压不出差，靠的是卡片自己那圈
 * `ALERT_DIALOG_RAISED` 的边——所以这里取「底色差」与「边线差」里大的那个。
 * jsdom 不套样式表，这些数只有真浏览器给得出。
 */
const deleteActionContrast = (theme: 'light' | 'dark'): Story => ({
  ...DisplayManageViews,
  args: { ...DisplayManageViews.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-surface"]'),
      ).toHaveAttribute('data-theme', theme),
    );
    const row = await openManager(canvas, '待出库订单');
    const manager = within(document.body).getByRole('dialog');

    await userEvent.click(
      within(row).getByRole('button', { name: zhCN['label.manage.delete'] }),
    );
    const dialog = await deleteDialog();

    // Named, so the question survives covering the row it is about.
    await expect(within(dialog).getByRole('heading').textContent).toBe(
      formatMessage(zhCN, 'label.delete.confirm', { title: '待出库订单' }),
    );

    const confirm = within(dialog).getByRole('button', {
      name: zhCN['label.manage.delete'],
    });
    await settled(() => getComputedStyle(confirm).backgroundColor);
    const { ratio, colors } = measureTextContrast(confirm);
    await expect(
      ratio,
      `${theme} — ${colors.text} on ${colors.background}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);

    // And the list under it reads as being *under* it: two `bg-popover`
    // cards on their own measure 1.00:1 against each other, which is what
    // "a white card dropped into the list" is as a number.
    const backdrop = document.querySelector<HTMLElement>(
      '[data-slot="alert-dialog-overlay"]',
    )!;
    const apart = measureLayerSeparation(dialog, backdrop, manager);
    await expect(
      apart.ratio,
      `${theme} — fill ${apart.colors.front} ${apart.onFill.toFixed(2)}:1, ring ${apart.colors.ring} ${apart.onRing.toFixed(2)}:1, over ${apart.colors.behind}`,
    ).toBeGreaterThanOrEqual(STACKED_DIALOG_SEPARATION);

    // Called off rather than carried out: this story measures, it does not
    // delete, and the row is left where the next play expects it.
    await userEvent.click(
      within(dialog).getByRole('button', { name: zhCN['label.delete.keep'] }),
    );
  },
});

/**
 * 叠起来的两张卡片之间的下限：3:1，按 1.4.11 对非文字内容那一档读——这圈边
 * 界是"这是一个盖住下面那块的问题"唯一的视觉凭据。
 */
const STACKED_DIALOG_SEPARATION = 3;

export const DeleteActionContrastInLightTheme: Story =
  deleteActionContrast('light');
export const DeleteActionContrastInDarkTheme: Story =
  deleteActionContrast('dark');

/**
 * The nearest ancestor that would trap this element in a stacking context of
 * its own, or `null` when it ranks against the page directly.
 *
 * Not every property that makes one is listed — this is the handful a story
 * frame or a Storybook wrapper plausibly sets, which is all it has to catch
 * to keep the premise above honest.
 */
function trappedIn(element: HTMLElement): HTMLElement | null {
  for (
    let parent = element.parentElement;
    parent && parent !== element.ownerDocument.documentElement;
    parent = parent.parentElement
  ) {
    const style = getComputedStyle(parent);
    if (
      (style.position !== 'static' && style.zIndex !== 'auto') ||
      style.transform !== 'none' ||
      style.filter !== 'none' ||
      style.perspective !== 'none' ||
      style.isolation === 'isolate' ||
      style.mixBlendMode !== 'normal' ||
      style.contain
        .split(' ')
        .some(
          part =>
            part === 'paint' ||
            part === 'layout' ||
            part === 'strict' ||
            part === 'content',
        ) ||
      Number.parseFloat(style.opacity) < 1
    )
      return parent;
  }
  return null;
}

/**
 * Whether the browser would hand a click at the middle of this box to the box
 * itself.
 *
 * The one question `z-index` cannot be read off a stylesheet to answer: what
 * paints in front depends on every stacking context between here and the
 * root, and this asks the engine that decides it.
 */
function inFrontOf(element: HTMLElement): boolean {
  const box = element.getBoundingClientRect();
  const hit = element.ownerDocument.elementFromPoint(
    Math.round(box.left + box.width / 2),
    Math.round(box.top + box.height / 2),
  );
  return hit !== null && element.contains(hit);
}

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

/**
 * The export window: one button, one window, the whole journey (D14).
 *
 * Nothing is actually exported here. The file itself — its name, its header
 * and every value in it — is asserted in jsdom, where the browser's half is
 * a stub (`test/recordExportUi.test.tsx`); a real click would hand this
 * browser a download for nothing. What only a browser can answer is what the
 * window says at each step, and that it says it in the language the data is
 * in.
 */
export const ExportWindow: Story = {
  ...DisplayExportResult,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    // No menu anywhere: the toolbar's part in this is the one button.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const unpicked = await within(document.body).findByRole('dialog');
    await expect(within(document.body).queryByRole('menu')).toBeNull();
    // Nothing picked, so there is no choice to draw — only what the file
    // will hold.
    await expect(within(unpicked).queryByRole('radio')).toBeNull();
    await expect(unpicked.textContent).toContain(
      say('label.export.rows', { count: 4 }),
    );
    await expect(unpicked.textContent).toContain(
      say('label.export.columns', {
        count: 4,
        names: ['订单号', '仓库', '状态', '金额'].join(
          zhCN['label.filter.join'],
        ),
      }),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );

    // The picked scope exists only once something is picked (D4), and it is
    // the one the window opens on.
    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const picked = await within(document.body).findByRole('dialog');
    await expect(
      within(picked)
        .getAllByRole('radio')
        .map(radio => radio.getAttribute('aria-checked')),
    ).toEqual(['true', 'false']);
    await expect(picked.textContent).toContain(
      say('label.export.selected', { count: 4 }),
    );
    // The one scope whose rows are not the ones on screen says so.
    await expect(picked.textContent).toContain(
      say('label.export.all', { count: 4 }),
    );
    await userEvent.keyboard('{Escape}');
  },
};

/**
 * The window while the pages come in, and Escape as the answer it is.
 *
 * The menu this replaced had to refuse both Escape and a click outside,
 * because the cancel lived inside it; a window may be dismissed, and being
 * dismissed *is* the cancel.
 */
export const ExportRunningWindow: Story = {
  ...DisplayExportRunning,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.export.confirm'],
      }),
    );

    const bar = await within(dialog).findByRole('progressbar', {
      name: zhCN['label.export.running'],
    });
    await expect(bar.getAttribute('aria-valuemax')).toBe('4');
    await expect(within(dialog).getByRole('status').textContent).toBe(
      say('label.export.progress', { fetched: 0, total: 4 }),
    );

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );
    // A cancel says nothing: it is the answer the user gave.
    await expect(
      canvasElement.querySelector('[data-slot="status-strip"]'),
    ).toBeNull();
  },
};

/**
 * The ceiling, said before the button and again after the file: the four
 * orders the saved condition matches, against a ceiling of two.
 */
export const ExportCappedWindow: Story = {
  ...DisplayExportCapped,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog');

    await expect(dialog.textContent).toContain(
      say('label.export.over-limit', { max: 2 }),
    );
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.export.confirm'],
      }),
    );

    await within(dialog).findByText(say('label.export.done', { count: 2 }));
    await expect(dialog.textContent).toContain(
      say('label.export.done-capped', { max: 2, total: 4 }),
    );
  },
};

/**
 * A failed export, reported where it happened and offered again from there.
 */
export const ExportFailedWindow: Story = {
  ...DisplayExportFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.export.confirm'],
      }),
    );

    await within(dialog).findByText(/导出失败/);
    // The way out is in the window, not back through the toolbar.
    await expect(
      within(dialog).getByRole('button', { name: zhCN['label.export.retry'] }),
    ).toBeTruthy();
    // And nothing was said above the rows about it.
    await expect(
      canvasElement.querySelector('[data-slot="status-strip"]'),
    ).toBeNull();
  },
};

/**
 * D12 puts every function into an icon button, so hovering one is how a
 * pointer learns what it is. The name the reader hears and the label the
 * pointer sees are one string (`src/ui/IconButton.tsx`), and this asks the
 * question a jsdom suite cannot: is it actually on screen, and does it say
 * what the button says *now* rather than what it said before it was pressed?
 */
export const IconButtonsSayTheirNameOnHover: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;

    const toggle = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view'],
    });
    await userEvent.hover(toggle);
    await waitFor(() =>
      // One message, two channels: the tooltip is the accessible name said
      // out loud to a pointer, never a second wording of it.
      expect(tooltipOn(doc)?.textContent).toBe(
        toggle.getAttribute('aria-label'),
      ),
    );
    await expect(tooltipOn(doc)).toHaveTextContent(
      zhCN['label.workbench.expand-view'],
    );

    // The label follows the state. It is the same button — what pressing it
    // does has changed, so what it is called changes with it.
    await userEvent.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-expanded', 'true'),
    );
    await userEvent.unhover(toggle);
    await waitFor(() => expect(tooltipOn(doc)).toBeNull());
    await userEvent.hover(toggle);
    await waitFor(() =>
      expect(tooltipOn(doc)?.textContent).toBe(
        zhCN['label.workbench.collapse-view'],
      ),
    );

    // Back out the way it came, so the story leaves the screen as it found
    // it — `FillTheScreen` is where the Escape route is held to account.
    await userEvent.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-expanded', 'false'),
    );
  },
};

/**
 * 侧栏每一行的种类图标，指上去要说得出自己是什么（D-2）。
 *
 * 从前 `TooltipTrigger` 直接挂在那个 `<svg>` 上，而它在 `Button` 里——
 * `[&_svg]:pointer-events-none` 让这个 svg 根本收不到指针，标签永远打不开：
 * 源码里写着的名字，屏幕上谁也拿不到。现在挂在外面那层 `span` 上，指针落在
 * 图标上会穿到父元素，于是它就是触发器。jsdom 量不出这一条——`pointer-events`
 * 要真的做命中测试才算数——所以钉在浏览器里。
 */
export const AViewRowSaysItsKindOnHover: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;

    const row = listItem(canvasElement, '待出库订单');
    const kind = row.querySelector<HTMLElement>('[data-slot="view-kind"]')!;
    // The premise: the glyph itself still refuses the pointer, which is why
    // it cannot be the trigger and the wrapper is.
    await expect(
      getComputedStyle(kind.querySelector('svg')!).pointerEvents,
    ).toBe('none');
    await expect(getComputedStyle(kind).pointerEvents).not.toBe('none');

    await userEvent.hover(kind);
    await waitFor(() =>
      expect(tooltipOn(doc)).toHaveTextContent(zhCN['label.kind.record']),
    );

    // And the row is still called by the view it opens, not by its kind:
    // a list where every name starts with the same two syllables is a list
    // that has stopped distinguishing its items.
    await expect(row).toHaveTextContent('待出库订单');
    await expect(row.textContent).not.toContain(zhCN['label.kind.record']);

    await userEvent.unhover(kind);
    await waitFor(() => expect(tooltipOn(doc)).toBeNull());
  },
};

/**
 * The tooltip that is showing, if one is.
 *
 * Base UI leaves the popup in the document while it animates away, so
 * "showing" is the `data-open` on it rather than its presence — otherwise
 * "no tooltip here" would be true only after the fade.
 */
function tooltipOn(doc: Document): HTMLElement | null {
  return doc.querySelector<HTMLElement>(
    '[data-slot="tooltip-content"][data-open]',
  );
}

/** Whether two boxes share any of the screen. */
function overlapping(one: HTMLElement, other: HTMLElement): boolean {
  const a = one.getBoundingClientRect();
  const b = other.getBoundingClientRect();
  return (
    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
  );
}

/**
 * A tooltip round a menu's trigger must not fight the menu.
 *
 * The two hang off one button and the pointer that opened the menu is still
 * sitting on it, which is the one arrangement where a tooltip can end up
 * over the thing it was meant to explain. What is held here is the part the
 * user can see: whether the label is still up or not, every option is the
 * thing a click at its middle reaches, and nothing black is lying over the
 * list. Whether Base UI keeps the tooltip shut after the click or lets the
 * resting pointer bring it back is its own business, and it does both
 * depending on how the press is timed — the label sits above the button,
 * where the menu is not, so neither way costs the user anything.
 */
export const AMenuIsNotCoveredByItsOwnTooltip: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const body = within(doc.body);
    const chevron = canvasElement.querySelector<HTMLElement>(
      '[data-slot="refresh-interval"]',
    )!;

    await userEvent.hover(chevron);
    await waitFor(() =>
      expect(tooltipOn(doc)).toHaveTextContent(zhCN['label.refresh.auto']),
    );

    await userEvent.click(chevron);
    const menu = await body.findByRole('menu');
    await userEvent.hover(chevron);
    // A beat for the tooltip to do whatever it is going to do: with the
    // provider's zero delay, anything it has in mind has happened by now.
    await new Promise(settle => setTimeout(settle, 200));

    const tip = tooltipOn(doc);
    if (tip) await expect(overlapping(tip, menu)).toBe(false);
    for (const item of within(menu).getAllByRole('menuitemradio'))
      await expect(inFrontOf(item)).toBe(true);

    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(body.queryByRole('menu')).toBeNull());
  },
};

/**
 * F-15: a column name the header cannot hold gives the whole of it back on
 * hover.
 *
 * Measured in Chromium on this fixture at 1280: 运单号 was drawn in 17px of
 * the 37px it asks for — «运..» — 订单号 in 21px and 件数 in 16px of 25,
 * and the span carried neither a `title` nor a tooltip, so the rest of the
 * name was reachable by no input device at all. It is a `Tooltip` rather
 * than the native `title` (D16-6): `title` opens for a mouse and for nothing
 * else, and these headers are buttons a keyboard reaches. Only a real
 * browser truncates, so the pixels are checked here and the structure in
 * jsdom (`test/recordTable.test.tsx`).
 *
 * **Those three names now fit** (P-11): the sort button was capped at the
 * cell's content box while its negative margins reach the padding box, so
 * every header was 16px short of the room its own cell had. What is left is
 * the case the tooltip is actually for — a column the *user* narrowed, down
 * to the 48px floor the handle stops at, where the name cannot fit however
 * the cell is measured. So the premise is made rather than found.
 */
export const ATruncatedColumnNameIsOneHoverAway: Story = {
  ...DisplayWideTable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const nameOf = (label: HTMLElement) =>
      label.closest('th')!.getAttribute('data-field');
    const labelFor = (field: string) =>
      table.querySelector<HTMLElement>(
        `thead th[data-field="${field}"] [data-slot="column-label"]`,
      )!;

    // Nothing is cut before a reader asks for it to be: every header holds
    // its own name at the width the columns settle on (P-11).
    const labels = [
      ...table.querySelectorAll<HTMLElement>(
        'thead [data-slot="column-label"]',
      ),
    ];
    await expect(
      labels.filter(label => label.scrollWidth > label.offsetWidth).map(nameOf),
    ).toEqual([]);

    // Now take one down to the floor, which is the reader's own doing and
    // the one width at which a name has nowhere to go.
    const edge = canvas.getByRole('separator', {
      name: say('label.columns.resize', { field: '订单号' }),
    });
    const head = edge.closest('th')!;
    await dragEdgeBy(edge, 48 - head.getBoundingClientRect().width);
    await waitFor(() =>
      expect(labelFor('orderNo').scrollWidth).toBeGreaterThan(
        labelFor('orderNo').offsetWidth,
      ),
    );

    const name = labelFor('orderNo');
    const whole = name.textContent?.trim();
    // Cut on screen and whole in the DOM, which is what a reader hears.
    await expect(name.scrollWidth).toBeGreaterThan(name.offsetWidth);
    await expect(whole).toBeTruthy();
    // And not the one only a mouse can open.
    await expect(name).not.toHaveAttribute('title');

    await userEvent.hover(name);
    const tip = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="tooltip-content"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(tip.textContent?.trim()).toBe(whole);
    // Themed from `ui/popups.tsx` like every other popup here: it is drawn
    // outside the surface, where the tokens do not reach on their own.
    await expect(tip.className).toContain('fve-root');

    await userEvent.unhover(name);
  },
};

/**
 * 这块面的动效让给 `prefers-reduced-motion: reduce`，而会说话的那两种动画不让。
 *
 * 屏幕上会动的东西没有一件是调用处写的：弹层由 vendored 组件带着
 * `data-open:animate-in zoom-in-95 slide-in-from-top-2` 进场，对话框带着遮罩
 * 淡入，按钮、徽章与行普遍带 `transition-all`——评审当时量到菜单弹层
 * `animation-name: enter`、`animation-duration: 0.1s`，中途 `transform` 缩在
 * 0.986、`opacity` 0.727。所以让步只能在主题里做一次（`styles.css`，与两处
 * vendored 字号钉在同一个地方、同一个理由），而不是去改三百处 class。
 *
 * **reduce 不等于 remove**：spinner 说的是「还在写／还在查」，skeleton 的脉动
 * 说的是「屏幕上这些还不是数据」；停掉它们是把「进行中」画成「卡住了」，所以
 * 这两个 slot 被排除在外。
 *
 * 媒体查询本身在故事里开不动（Playwright 的 `reducedMotion` 只在测试进程里有，
 * 浏览器矩阵也不是每种都给得出），所以这里量的是**规则本身**：它在不在、作用
 * 域有没有同时点到两个边界、屏幕上真实的弹层匹不匹配它、被排除的那两个 slot
 * 匹不匹配，以及它此刻要削掉的是多长的一段动画。
 */
export const ReducedMotionIsHonoured: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;

    // 本包发的那一条：条件是 reduce，作用域点到两个边界。宿主页面与 vendored
    // 的 `.shimmer` 各有各的一条，按边界认出自己这条。
    const reduced = [...doc.styleSheets]
      .flatMap(sheet => {
        try {
          return [...sheet.cssRules];
        } catch {
          return [];
        }
      })
      .filter(
        (rule): rule is CSSMediaRule =>
          rule instanceof CSSMediaRule &&
          rule.conditionText.includes('prefers-reduced-motion'),
      )
      .flatMap(media => [...media.cssRules])
      .filter(
        (rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule &&
          // 两个边界**本身**各是它的一个选择器分支，而不是某个类恰好落在边界
          // 里——vendored 的 `.shimmer` 也有一条 reduce 规则，作用域同样点到
          // 两个边界，说的却只是它自己那一个类。
          ['.fve-root', '.fve-tokens'].every(boundary =>
            rule.selectorText.split(',').some(part => part.trim() === boundary),
          ),
      );
    // 一条，或者同一条被加载了不止一次（dev 的 HMR 与测试进程各挂一份），所以
    // 数的是「有」而不是「恰好一份」，而每一份都得说同一件事。
    await expect(reduced.length, '包级的 reduce 规则').toBeGreaterThan(0);
    const rule = reduced[0];

    // 削到察觉不到，而不是削到零：Base UI 的弹层靠自己退场动画结束的那一下
    // 卸载，时长拿掉就没有那一下了。
    const declared = (one: CSSStyleRule, property: string) => [
      one.style.getPropertyValue(property),
      one.style.getPropertyPriority(property),
    ];
    for (const one of reduced) {
      await expect(declared(one, 'animation-duration')).toEqual([
        '0.01ms',
        'important',
      ]);
      await expect(declared(one, 'transition-duration')).toEqual([
        '0.01ms',
        'important',
      ]);
    }

    // 真实的弹层：它匹配这条规则，而它此刻的动画正是规则要削的那 100ms。
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>(
        '[data-slot="refresh-interval"]',
      )!,
    );
    const menu = await within(doc.body).findByRole('menu');
    const popup = menu.closest<HTMLElement>(
      '[data-slot="dropdown-menu-content"]',
    )!;
    await expect(popup.matches(rule.selectorText)).toBe(true);
    await expect(getComputedStyle(popup).animationDuration).toBe('0.1s');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(doc.body).queryByRole('menu')).toBeNull(),
    );

    // 会说话的那两种不让。这一屏已经查完、也没在写，spinner 与 skeleton 都不
    // 在场（它们分别是 `RefreshControl` 与 `ViewList` 的在途状态），所以问的是
    // 选择器本身：探针戴上上游给它们的 slot，放进这块面里问一句再拿走。
    const excludes = (slot: string) => {
      const probe = doc.createElement('div');
      probe.dataset.slot = slot;
      surface.append(probe);
      const matched = probe.matches(rule.selectorText);
      probe.remove();
      return matched;
    };
    await expect({
      spinner: excludes('spinner'),
      skeleton: excludes('skeleton'),
      badge: excludes('badge'),
    }).toEqual({ spinner: false, skeleton: false, badge: true });
  },
};

/**
 * 没有底色的徽章靠边活着，所以那圈边是 `--input`。
 *
 * 主题把两个 token 分在这条线上：`--border` 是**东西之间**的线，可以淡；
 * `--input` 是**边就是那个东西**的那一档，两个主题都钉在 ≥3:1，因为一个没勾
 * 的复选框除了那圈边什么都没有。没有底色的徽章是同一种情形——把边拿掉就没有
 * 徽章了，只剩一个词——而 registry 给它的是 `border-border`：量在标题栏上是
 * **1.26:1**（暗色 1.77:1）。表内那几枚有语气的徽章早就换过了
 * （`ui/variants.tsx` 的 `neutral`），这一条是剩下的那些。
 *
 * 钉的那一枚正是最难够着的一枚：「已修改」这颗徽章上的 `data-slot` 被调用处
 * 写成了 `view-unsaved`（`useRender` 的 state 拗不过调用处的 prop），所以
 * `styles.css` 里那条规则问的是 `group/badge` 与 `data-variant`——两样都是
 * registry 自己写在每一枚徽章上的。
 */
const outlineBadgeEdges = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    // 改一下，好让「已修改」那枚徽章上台。
    await userEvent.click(canvas.getAllByRole('button', { name: /订单号/ })[0]);
    const mark = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-unsaved"]',
      );
      if (!found) throw new Error('the "edited" mark did not appear');
      return found;
    });

    // registry 自己的两样东西：徽章的组名与它的 variant。
    await expect(mark.getAttribute('data-variant')).toBe('outline');
    await expect(mark.getAttribute('class')).toContain('group/badge');

    const { ratio, colors } = measureBorderContrast(mark);
    await expect(
      ratio,
      `${theme} — ${colors.border} on ${colors.surface}`,
    ).toBeGreaterThanOrEqual(3);
  },
});

export const OutlineBadgeEdgesInLightTheme: Story = outlineBadgeEdges('light');
export const OutlineBadgeEdgesInDarkTheme: Story = outlineBadgeEdges('dark');
