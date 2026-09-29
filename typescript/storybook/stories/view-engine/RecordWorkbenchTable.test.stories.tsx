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
  CellFamily as DisplayCellFamily,
  EarliestAndLatest as DisplayEarliestAndLatest,
  ElementColumns as DisplayElementColumns,
  Paged as DisplayPaged,
  PagedWindow as DisplayPagedWindow,
  PinnedEdges as DisplayPinnedEdges,
  PinnedGroupCapped as DisplayPinnedGroupCapped,
  WideTable as DisplayWideTable,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { dragEdgeBy } from './pointerDrag.js';
import {
  amountOf,
  readColumn,
  readHeaders,
  readPage,
  readTotal,
} from './readTable.js';
import {
  badgeIn,
  headerOf,
  paginationBar,
  say,
  scopeLabels,
} from './recordWorkbenchTest.js';

/**
 * The table: cells, element columns, summaries, paging, pinned columns and the
 * wide table. One of the record workbench's regression files, split by concern;
 * they all share one title, so every story keeps its id, and the helpers more
 * than one of them needs are in `recordWorkbenchTest.ts`.
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
 * Every reading a definition can declare, in a real browser.
 *
 * The three that jsdom cannot answer for are here: whether a link really
 * carries the two attributes that keep the opened document from reaching
 * back, whether the tone reaches the badge as a variant rather than only as
 * an attribute, and whether an undeclared column is still exactly what it
 * always was.
 */
/**
 * The selection boxes have room (WCAG 2.2 2.5.8; second review R1-P1-9,
 * R3-P1-7). A box is 16px, under the 24px a target should be, so it holds
 * by the exception for room: nothing else a pointer can press lies within a
 * 24px circle centred on it. Here the table lays the selection column out
 * at its content, and the header's first sort button used to begin 8px
 * from the box's centre (axe `target-size`, serious — a WCAG 2.2 rule the
 * default axe run in these stories leaves out, so it is measured here).
 */
export const SelectionBoxesHaveRoom: Story = {
  ...DisplayCellFamily,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    const targets = [
      ...table.querySelectorAll<HTMLElement>(
        'button, a[href], [role="checkbox"], [role="separator"][tabindex]',
      ),
    ];
    const boxes = [...table.querySelectorAll<HTMLElement>('[role="checkbox"]')];
    await expect(boxes.length).toBe(7);
    for (const box of boxes) {
      const at = box.getBoundingClientRect();
      const x = at.left + at.width / 2;
      const y = at.top + at.height / 2;
      for (const other of targets) {
        if (other === box) continue;
        const near = other.getBoundingClientRect();
        const dx = Math.max(near.left - x, 0, x - near.right);
        const dy = Math.max(near.top - y, 0, y - near.bottom);
        await expect(
          Math.hypot(dx, dy),
          `${other.getAttribute('aria-label') ?? other.textContent} beside ${box.getAttribute('aria-label')}`,
        ).toBeGreaterThanOrEqual(12);
      }
      // The column is the 2.5rem the pinned offsets assume, and the box's
      // own larger hit area stays inside it.
      await expect(
        box.closest('td, th')!.getBoundingClientRect().width,
      ).toBeGreaterThanOrEqual(40);
    }
  },
};

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

    // A note takes one line in a table, with the whole of it one hover
    // away — the one assertion that needs a browser to lay the box out.
    // Three lines are a card's, where there is no column to read down and
    // a taller row costs nothing (`test/recordCells.test.tsx` holds that
    // half — it is a class either way, and only this side needs a layout).
    const note = cellAt(table, '备注', 4).querySelector<HTMLElement>(
      '[data-slot="cell-text"]',
    )!;
    await expect(note).toHaveAttribute('title', note.textContent!);
    await expect(getComputedStyle(note).whiteSpace).toBe('nowrap');
    await expect(getComputedStyle(note).textOverflow).toBe('ellipsis');
    await expect(note.scrollWidth).toBeGreaterThan(note.clientWidth);

    // And the copyable column is the text it always was — no pill around it
    // and nothing to click — with one button beside it, named after the
    // value it would take away. What that button does is the next story.
    const key = cellAt(table, '订单号', 0);
    await expect(key).toHaveTextContent('SO-1001');
    await expect(key.querySelector('[data-slot="badge"]')).toBeNull();
    await expect(key.querySelector('a')).toBeNull();
    const copy = key.querySelector<HTMLElement>('[data-slot="cell-copy"]')!;
    await expect(copy).toHaveAccessibleName(
      say('label.copy-of', { value: 'SO-1001' }),
    );
  },
};

/**
 * An array of objects reads as its elements in a real table: each order's
 * lines by their SKU, one line high, the fifth line of a five-line order
 * counted rather than drawn, and the parcels — which name no title — as how
 * many there are. Nowhere a brace: the JSON these cells used to hold is what
 * pushed a real event stream's table off the screen.
 */
export const ElementColumns: Story = {
  ...DisplayElementColumns,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    const row = (id: string) =>
      readColumn(table, '订单号').findIndex(text => text.startsWith(id));

    await expect(badgeTexts(cellAt(table, '明细', row('SO-1001')))).toEqual([
      'TEA-01',
      'CUP-12',
    ]);
    // Three lines are three badges: an event stream of three reads whole.
    await expect(badgeTexts(cellAt(table, '明细', row('SO-1003')))).toEqual([
      'CARD-07',
      'TEA-01',
      'BOX-02',
    ]);

    // Five are the first two and a count, on one line, with the whole list
    // one hover away and the hidden three read out rather than drawn.
    const five = cellAt(table, '明细', row('SO-1004'));
    await expect(badgeTexts(five)).toEqual(['CUP-12', 'PLATE-05']);
    const more = five.querySelector('[data-slot="cell-elements-more"]')!;
    await expect(more).toHaveTextContent('+3');
    await expect(more).toHaveAttribute('aria-hidden', 'true');
    const list = five.querySelector<HTMLElement>(
      '[data-slot="cell-elements"]',
    )!;
    await expect(list).toHaveAttribute(
      'title',
      'CUP-12、PLATE-05、BOWL-04、SPOON-09、TRAY-01',
    );
    await expect(getComputedStyle(list).flexWrap).toBe('nowrap');
    await expect(five).toHaveTextContent(/BOWL-04、SPOON-09、TRAY-01/);

    // A table row is one line: the five-line order's row is as tall as the
    // one-line order's.
    const height = (id: string) =>
      (table as HTMLTableElement).tBodies[0].rows[
        row(id)
      ].getBoundingClientRect().height;
    await expect(height('SO-1004')).toBe(height('SO-1006'));

    // No title declared: counted, and an empty list says nothing at all.
    await expect(cellAt(table, '包裹', row('SO-1005'))).toHaveTextContent(
      '3 项',
    );
    await expect(cellAt(table, '包裹', row('SO-1001'))).toHaveTextContent(
      '1 项',
    );
    await expect(cellAt(table, '包裹', row('SO-1002'))).toHaveTextContent('');
    await expect(table.textContent).not.toMatch(/[{}]/);
  },
};

/**
 * Taking a document number away, in a real browser (user request
 * 2026-09-22).
 *
 * jsdom can be told what `navigator.clipboard` is; only a browser has one.
 * So the button is pressed here and the clipboard is asked what it now
 * holds. That read is the part a browser may refuse — `clipboard-read` is a
 * permission of its own, and Chromium grants it to the story context but
 * nothing promises it will — so it is wrapped: what has to hold either way
 * is that the button answered, in the words the catalogue gives it, and then
 * stood down again.
 */
export const CopyADocumentNumber: Story = {
  ...DisplayCellFamily,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));

    const key = cellAt(table, '订单号', 0);
    const copy = key.querySelector<HTMLElement>('[data-slot="cell-copy"]')!;
    // Resting invisible, which is only true where there is a pointer to
    // reveal it with: the runner reports `(hover: hover)`, so the utility
    // that hides it is in force here and the one that would show it hangs
    // off the groups the row and the cell carry. The reveal itself cannot be
    // driven from a play — the runner's pointer events put no real `:hover`
    // on an element (see 「侧栏当前行」 below) — so what is measured is that
    // the button is hidden *without being taken away*: laid out, in the tab
    // order, and one keyboard focus from showing itself.
    await expect(getComputedStyle(copy).opacity).toBe('0');
    await expect(getComputedStyle(copy).display).not.toBe('none');
    await expect(copy.tabIndex).toBeGreaterThanOrEqual(0);
    await expect(key.parentElement!.className).toContain('fve:group/row');
    await expect(
      key.querySelector('[data-slot="cell-copyable"]')!.className,
    ).toContain('fve:group/copyable');

    // The clipboard this press writes to is user-event's: `setup()` puts
    // its own stub on `navigator.clipboard`, and the runner's page, which
    // is not focused and holds no `clipboard-write`, refuses the real one.
    // This play used to get the stub from the `setup()` in `addSort` of a
    // story that ran before it in the same file (`WithData`); on its own,
    // or after other stories, the write was refused and the button said
    // so. It now asks for the stub itself.
    await userEvent.setup().click(copy);

    // The tick, and the word said to whoever cannot see it.
    const copied = say('label.copied', {});
    await waitFor(() => expect(copy).toHaveAccessibleName(copied));
    await expect(
      canvasElement.querySelector('[data-slot="cell-copy-announcement"]'),
    ).toHaveTextContent(copied);

    try {
      await waitFor(async () =>
        expect(await navigator.clipboard.readText()).toBe('SO-1001'),
      );
    } catch {
      // Reading the clipboard was refused, which is the browser's right:
      // the state above is then all this play can pin, and it is pinned.
    }

    // And the answer stands down again, so the row stops claiming it.
    await waitFor(
      () =>
        expect(copy).toHaveAccessibleName(
          say('label.copy-of', { value: 'SO-1001' }),
        ),
      { timeout: 4_000 },
    );
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

    // All six are one page, so the footer is the one 「全部」 row (D26 Q40);
    // the page row reads dates the same way (test/recordSummaries.test.tsx
    // 「reads a date summary the way the column reads its cells」).
    await expect(scopeLabels(table)).toEqual([
      zhCN['label.summary.scope.total'],
    ]);
    const cell = readTotal(table, '创建时间');
    await expect(cell).toContain(zhCN['label.summary.fn.date.MIN']);
    await expect(cell).toContain(zhCN['label.summary.fn.date.MAX']);
    await expect(cell).toContain(earliest);
    await expect(cell).toContain(latest);
    // Not the stored value, and not the number it was compared as.
    await expect(cell).not.toContain('2026-09-15T02:10');
    await expect(cell).not.toContain(zhCN['label.summary.fn.MIN']);
    // The money beside it is unchanged: a sum, in its own format.
    await expect(amountOf(readTotal(table, '金额'))).toBe(10230);
    await expect(readTotal(table, '金额')).toContain(
      zhCN['label.summary.fn.SUM'],
    );
  },
};

/**
 * No band without a column to sit under (review P1-3).
 *
 * The view sums 金额, but its table no longer shows 金额: the numbers have no
 * column to go under, and a band of nothing but 「全部」 is a grey strip that
 * says nothing. So the table draws none. The cards, which list a summary by
 * its own name, still carry it — which is also how this play knows the
 * totals had landed before it looked for the band. `WithData` holds the
 * other side: a summarised column on screen, and the band under it.
 */
export const NoBandWithoutSummarisedColumn: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, summaryOffTable: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // No condition: all six orders.
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    await expect(
      readHeaders(table).some(header => header.includes('金额')),
    ).toBe(false);

    // Under the cards the sum is there, by name.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.layout.cards'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector(
          '[data-slot="record-summaries"][data-layout="card"]',
        ),
      ).toHaveTextContent(zhCN['label.summary.scope.total']),
    );

    // Back on the table, with the totals known: no band at all.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.layout.table'] }),
    );
    const back = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(back, '订单号')).toHaveLength(6));
    await expect(
      canvasElement.querySelector('[data-slot="record-summaries"]'),
    ).toBeNull();
    await expect(scopeLabels(back)).toEqual([]);
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
      say('label.pagination.total', { count: 6 }),
    );
    await expect(bar).toHaveTextContent(
      say('label.toolbar.page-of', { index: 1, pages: 3 }),
    );
    // Three pages, so the page is a part of the result and both summary rows
    // stand: the two rows on screen, and every order the conditions select.
    await expect(scopeLabels(table)).toEqual([
      zhCN['label.summary.scope.page'],
      zhCN['label.summary.scope.total'],
    ]);
    await expect(amountOf(readTotal(table, '金额'))).toBe(10230);
    await expect(amountOf(readPage(table, '金额'))).toBeLessThan(10230);
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
      say('label.pagination.total', { count: 6 }),
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
 * A source with a paging window (Wow over Elasticsearch refuses a page past
 * row 10 000): six orders two at a time under a window of four. The bar
 * counts the pages the window lets it reach, stops Next on the last of them,
 * lands a jump past it on that page, and says in one line why — and the line
 * is the bar's accessible description, so a screen reader hears it too.
 */
export const PagedWindow: Story = {
  ...DisplayPagedWindow,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1002']),
    );

    const bar = paginationBar(canvasElement);
    // The total is still every order; the pages are the ones within reach.
    await expect(bar).toHaveTextContent(
      say('label.pagination.total', { count: 6 }),
    );
    await expect(bar).toHaveTextContent(
      say('label.toolbar.page-of', { index: 1, pages: 2 }),
    );
    const line = say('label.pagination.window', { count: 4 });
    await expect(bar).toHaveTextContent(line);
    await expect(bar).toHaveAccessibleDescription(line);

    // A jump past the window lands on its last page rather than failing.
    const goTo = within(bar).getByRole('textbox', {
      name: zhCN['label.pagination.go-to'],
    });
    await userEvent.clear(goTo);
    await userEvent.type(goTo, '3{Enter}');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1003', 'SO-1004']),
    );
    await expect(goTo).toHaveValue('2');
    await expect(paginationBar(canvasElement)).toHaveTextContent(
      say('label.toolbar.page-of', { index: 2, pages: 2 }),
    );

    // And the last reachable page is the last page: Next is spent.
    await expect(
      within(paginationBar(canvasElement)).getByRole('button', {
        name: zhCN['label.toolbar.next'],
      }),
    ).toBeDisabled();
  },
};

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
  // The story opens on a table that just fits its port — 691px of columns
  // in 728 — and then narrows it. A preset that recommends comfortable rows
  // (porcelain's +1) pads every column, and the same columns come to 747:
  // the table overflows before anything is narrowed and the cap lets the
  // outer pins go, which the story then meets rather than sets up. Its
  // subject is the edge, not the density, so it holds the default one.
  globals: { fveDensity: 'default' },
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
    const UNFRAMED = { inner: NONE, left: NONE, end: NONE, actions: NONE };

    // Still, and wide enough that nothing has to scroll: no edge at all
    // (P-23, D13 as amended). The edge says "the middle scrolls under here",
    // and there is no middle to scroll yet — it appears the moment there
    // is, below, before anything has moved.
    await waitFor(() =>
      expect(area.hasAttribute('data-overflowing')).toBe(false),
    );
    await waitFor(() => expect(edges()).toEqual(UNFRAMED));

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
    // Overflow is said on the port, and the frame is there before anything
    // has been scrolled — which is the whole of D13.
    await waitFor(() =>
      expect(area.hasAttribute('data-overflowing')).toBe(true),
    );
    // The key is what the cap may never take, so there is always a left
    // boundary to look at.
    await expect(inner).toHaveAttribute('data-pin', 'left');
    await waitFor(() => expect(edges()).toEqual(framed()));
    for (const scrolled of [40, area.scrollWidth, 0]) {
      area.scrollLeft = scrolled;
      await waitFor(() => expect(edges()).toEqual(framed()));
    }

    // A column that slid under the frozen header stays under it, handle
    // and all. The resize handle is `absolute z-20`; in a header cell that
    // was merely `relative` that 20 competed at the row's level and beat
    // the pinned cells' 10, so the scrolled column's edge line painted
    // through the frozen header (user, 2026-09-22). Every point across the
    // frozen key header — short of its own handle at the right — must
    // resolve to that header.
    area.scrollLeft = 60;
    await waitFor(() => expect(area.scrollLeft).toBe(60));
    const box = inner.getBoundingClientRect();
    for (const at of [0.1, 0.3, 0.5, 0.7, 0.85]) {
      const hit = document.elementFromPoint(
        box.left + box.width * at,
        box.top + box.height / 2,
      );
      await expect(
        inner.contains(hit),
        `at ${at}: ${hit?.tagName} ${(hit as HTMLElement | null)?.dataset.slot ?? ''}`,
      ).toBe(true);
    }
    area.scrollLeft = 0;

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

    /**
     * **Every row is the same height** — one line each, over 20 columns and
     * 50 rows (U3, user's 2026-09-22 review).
     *
     * A table is read down a column, and a row that is two lines tall
     * wherever a note is long or a second tag appears turns that straight
     * line into a staircase. Measured here, the 50 rows came in at 41, 61
     * and 77 pixels: a `text` cell clamped to three lines, and a pair of
     * tags wrapped by a column three characters wide. Both now take one
     * line in a table and keep the room they had on a card, so the whole
     * page is one height (`ui/record/cells.tsx`, `docs/design/ui/record.md`
     * 「一列怎么读」). Only a browser lays text out, so this assertion can
     * only live here.
     */
    const rows = [...table.querySelectorAll<HTMLTableRowElement>('tbody tr')];
    await waitFor(() => {
      const first = rows[0].getBoundingClientRect().height;
      // A whole pixel of slack and no more: the rows are laid out from the
      // same font at the same size, and a second line is 20 of them.
      for (const row of rows)
        expect(
          Math.abs(row.getBoundingClientRect().height - first),
        ).toBeLessThan(1);
    });
    // And the note that used to be three lines is still all there, on the
    // hover — cut on screen, whole in the tooltip.
    const note = [
      ...table.querySelectorAll<HTMLElement>('tbody [data-slot="cell-text"]'),
    ].find(found => found.textContent!.includes('\n'))!;
    await expect(note).toHaveAttribute('title', note.textContent!);
    await expect(note.getBoundingClientRect().height).toBeLessThan(24);

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

    // The premise: a result area far narrower than the table it holds — no
    // wider than the 420px host, which the result band now runs edge to edge.
    await expect(area.clientWidth).toBeLessThanOrEqual(420);
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
