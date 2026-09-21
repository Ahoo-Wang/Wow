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
  EmptyResult as DisplayEmptyResult,
  English as DisplayEnglish,
  ExportResult as DisplayExportResult,
  FillTheScreen as DisplayFillTheScreen,
  FillTheScreenInScaledHost as DisplayFillTheScreenInScaledHost,
  FillTheScreenInTransformedHost as DisplayFillTheScreenInTransformedHost,
  FillTheScreenWithPopups as DisplayFillTheScreenWithPopups,
  Loading as DisplayLoading,
  ManageViews as DisplayManageViews,
  NarrowTitleBar as DisplayNarrowTitleBar,
  NeedsFixing as DisplayNeedsFixing,
  Paged as DisplayPaged,
  PinnedEdges as DisplayPinnedEdges,
  RenderFailure as DisplayRenderFailure,
  PopupsOverRaisedHostLayer as DisplayPopupsOverRaisedHostLayer,
  QueryFailed as DisplayQueryFailed,
  RenameConflicted as DisplayRenameConflicted,
  SaveConflicted as DisplaySaveConflicted,
  SaveRefused as DisplaySaveRefused,
  SaveResultUnknown as DisplaySaveResultUnknown,
  TableSettings as DisplayTableSettings,
  TotalCoversThisPageOnly as DisplayTotalCoversThisPageOnly,
  WithActions as DisplayWithActions,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { measureBorderContrast } from './contrast.js';
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

    // The saved view orders by amount; clicking another sortable header adds
    // it, and each header then says where it sits in that order.
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

    await userEvent.click(headerOf(table, '订单号').querySelector('button')!);
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
    const title = within(row('我盯的大额单')).getByLabelText(
      zhCN['label.save.title'],
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
    ).closest('[role="dialog"]') as HTMLElement;
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
    const strip = await canvas.findByRole('status');
    await expect(strip).toHaveTextContent(zhCN['runtime.summary.page-only']);
  },
};

export const NeedsFixing: Story = {
  ...DisplayNeedsFixing,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent(zhCN['label.view.needs-fixing']);
    // The findings fold behind a count so the result keeps its room.
    await userEvent.click(within(alert).getByRole('button', { name: /1/ }));
    await expect(alert).toHaveTextContent('removedColumn');
    // A config the definition refuses is never run, so there is no result —
    // and with no result there are no columns either. What used to be drawn
    // was a header of one empty cell over no rows, with a tab-reachable
    // "Select all rows" in it that selected nothing: the strip above already
    // says what is wrong, and the table has nothing of its own to add.
    await expect(canvas.queryByRole('table')).toBeNull();
    await expect(
      canvas.queryByRole('checkbox', {
        name: zhCN['label.record.select-all'],
      }),
    ).toBeNull();
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

    // And the button under the popover reads the new first entry back.
    await userEvent.keyboard('{Escape}');
    await expect(button).toHaveTextContent(
      `订单号${zhCN['label.sort.asc']}${say('label.sort.more', { count: 1 })}`,
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

    await pressWhenEnabled(
      within(band).getByRole('button', {
        name: zhCN['label.conflict.mine'],
      }),
    );

    // Put once more, because the button that offered it cannot show what it
    // costs: the two configs are summarised beside each other, and here they
    // differ in three of the four things the summary counts.
    const dialog = await within(document.body).findByRole('dialog');
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
    const dialog = await within(document.body).findByRole('dialog');
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
      zhCN['label.save.title'],
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
      expect(
        within(document.body).queryByText(zhCN['label.delete.confirm']),
      ).toBeNull(),
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
  await userEvent.click(headerOf(table, '订单号').querySelector('button')!);
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

/** Whichever delete confirmation is on screen. */
async function deleteDialog(): Promise<HTMLElement> {
  const asked = await within(document.body).findByText(
    zhCN['label.delete.confirm'],
  );
  return asked.closest<HTMLElement>('[role="dialog"]')!;
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
 * Two fields ticked in one visit to the picker, and two pills to show for it.
 *
 * The picker used to close on every pick, which made four conditions four
 * round trips. It stays open now, so the thing worth regressing is that a
 * second tick lands while the first is still on screen.
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
      await canvas.findByRole('button', {
        name: zhCN['label.filter.add'],
      }),
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
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );

    // Three conditions now: the saved one and the two just ticked.
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="filter-condition"]'),
      ).toHaveLength(3),
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
    // one end to the other: the same two edges, unchanged throughout.
    area.style.maxWidth = '420px';
    await waitFor(() =>
      expect(area.scrollWidth).toBeGreaterThan(area.clientWidth),
    );
    await waitFor(() => expect(edges()).toEqual(FRAMED));
    area.scrollLeft = 40;
    await waitFor(() => expect(edges()).toEqual(FRAMED));
    area.scrollLeft = area.scrollWidth;
    await waitFor(() => expect(edges()).toEqual(FRAMED));
    area.scrollLeft = 0;
    await waitFor(() => expect(edges()).toEqual(FRAMED));

    // And nothing on the table says where it is scrolled to any more.
    await expect(table).not.toHaveAttribute('data-scrolled-left');
    await expect(table).not.toHaveAttribute('data-scrolled-right');
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

    // The saved view already refreshes itself, so the credential is on the
    // button before anything is pressed: it says which cadence, not only
    // that there is one.
    await expect(cadence()).toHaveTextContent(seconds);
    await expect(
      canvasElement.querySelector('[data-slot="refresh-now"]'),
    ).toHaveAttribute(
      'aria-description',
      say('label.refresh.on', { interval: seconds }),
    );

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
    await waitFor(() => expect(cadence()).toHaveTextContent(minutes));
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
const LADDER = [10, 30, 60, 300, 900, 1800, 3600];

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
    trigger: '[data-slot="dropdown-menu-trigger"]',
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
      await canvas.findByRole('button', {
        name: zhCN['label.filter.add'],
      }),
    );
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '金额' }),
    );
    // A text input that stands on its own: the picker's search field. The
    // one inside a condition pill is borderless by design (D12 — the pill
    // is the field, and draws the one border), so it is not what 1.4.11
    // asks about; its edge is the pill's.
    const input = measureBorderContrast(
      within(picker).getByRole('textbox', {
        name: zhCN['label.field.search'],
      }),
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
 * 按钮与已应用条上的 ✕ 走 `FOCUS_RING`——同一份配方（透明边聚焦时染成
 * `ring`），不另造一种。评审量到 `--ring` 在 `0.708` 时边线只有 2.59:1、光晕
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

/** Presses Tab until the element has focus, so `:focus-visible` holds. */
async function tabTo(target: HTMLElement): Promise<void> {
  for (let presses = 0; presses < 80; presses += 1) {
    if (document.activeElement === target) return;
    await userEvent.tab();
  }
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

/** The light theme's `--input`, over the card and the header it sits on. */
export const ControlBordersInLightTheme: Story = controlBorders('light');

/**
 * The same controls with the surface pinned dark, where the token is white at
 * an opacity and carries the `bg-input/30` fill with it.
 */
export const ControlBordersInDarkTheme: Story = controlBorders('dark');

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
  args: { ...DisplayNarrowTitleBar.args, withActions: true },
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
 * The export menu: three readings of "export", each with its own count.
 *
 * Nothing is actually exported here. The file itself — its name, its header
 * and every value in it — is asserted in jsdom, where the browser's half is
 * a stub (`test/recordWorkbench.test.tsx`); a real click would hand this
 * browser a download for nothing. What only a browser can answer is what the
 * menu says, and that it says it in the language the data is in.
 */
export const ExportMenuScopes: Story = {
  ...DisplayExportResult,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    // The picked scope exists only once something is picked (D4), so the
    // menu is read twice: without a selection, then with one.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const unpicked = await within(document.body).findByRole('menu');
    await expect(
      within(unpicked)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([
      say('label.export.page', { count: 4 }),
      say('label.export.all', { count: 4 }),
    ]);
    await userEvent.keyboard('{Escape}');

    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const picked = await within(document.body).findByRole('menu');

    await expect(
      within(picked)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([
      say('label.export.selected', { count: 4 }),
      say('label.export.page', { count: 4 }),
      // The one scope whose rows are not the ones on screen says so.
      say('label.export.all', { count: 4 }),
    ]);
    await userEvent.keyboard('{Escape}');
  },
};
