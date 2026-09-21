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
  EmptyResult as DisplayEmptyResult,
  FillTheScreen as DisplayFillTheScreen,
  FillTheScreenInScaledHost as DisplayFillTheScreenInScaledHost,
  FillTheScreenInTransformedHost as DisplayFillTheScreenInTransformedHost,
  FillTheScreenWithPopups as DisplayFillTheScreenWithPopups,
  Loading as DisplayLoading,
  Localized as DisplayLocalized,
  ManageViews as DisplayManageViews,
  NeedsFixing as DisplayNeedsFixing,
  Paged as DisplayPaged,
  PinnedEdges as DisplayPinnedEdges,
  RenderFailure as DisplayRenderFailure,
  PopupsOverRaisedHostLayer as DisplayPopupsOverRaisedHostLayer,
  QueryFailed as DisplayQueryFailed,
  TableSettings as DisplayTableSettings,
  TotalCoversThisPageOnly as DisplayTotalCoversThisPageOnly,
  WithActions as DisplayWithActions,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { measureBorderContrast } from './contrast.js';
import { tableSettingsStore } from './fixtures.js';
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
  formatMessage(defaultMessages, key, params);

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
      defaultMessages['label.summary.scope.page'],
      defaultMessages['label.summary.scope.total'],
    ]);
    // The function is named rather than left as the config's token.
    await expect(readTotal(table, '金额')).toContain(
      defaultMessages['label.summary.fn.SUM'],
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
    await expect(header).toHaveTextContent(
      defaultMessages['label.scope.tag.shared'],
    );

    // A saved view opens folded, and the bar above the rows says what they
    // were fetched under rather than what the editor now holds.
    const band = canvas.getByRole('button', {
      name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
    });
    await expect(band).toHaveAttribute('aria-expanded', 'false');
    await expect(
      canvas.getByRole('region', {
        name: defaultMessages['label.applied.title'],
      }),
    ).toHaveTextContent('待出库');

    // Paging is under the rows it pages, not in the toolbar, and it counts
    // what the conditions select rather than what fitted on the screen.
    const paging = paginationBar(canvasElement);
    await expect(paging).toHaveTextContent(
      say('label.pagination.total', { total: 4 }),
    );
    // Four rows at twenty a page is the whole of it, said as such.
    await expect(paging).toHaveTextContent(
      say('label.toolbar.page-of', { index: 1, pages: 1 }),
    );

    // Opening the fold brings the editor back, in its own block between the
    // title bar and the result, with the one way out of it.
    await userEvent.click(band);
    await expect(
      await canvas.findByRole('button', {
        name: defaultMessages['label.filter.apply'],
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
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
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

    // And a block's own rows are the step below it, not above.
    const result = canvasElement.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    )!;
    await expect(getComputedStyle(result).rowGap).toBe('12px');
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
        name: defaultMessages['label.pagination.page-size'],
      }),
    ).toHaveTextContent(say('label.pagination.page-size-option', { size: 2 }));

    // Page one has nowhere to go back to.
    const previous = within(bar).getByRole('button', {
      name: defaultMessages['label.toolbar.previous'],
    });
    const next = within(bar).getByRole('button', {
      name: defaultMessages['label.toolbar.next'],
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
        name: defaultMessages['label.toolbar.previous'],
      }),
    ).toBeEnabled();
    await userEvent.click(
      within(paginationBar(canvasElement)).getByRole('button', {
        name: defaultMessages['label.toolbar.previous'],
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
        name: defaultMessages['label.toolbar.actions'],
      }).className,
    ).toContain('sticky');

    await userEvent.click(
      canvas.getByLabelText(defaultMessages['label.record.select-all']),
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
        name: defaultMessages['label.manage.open'],
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
        name: defaultMessages['label.manage.delete'],
      }),
    ).toBeNull();

    // A column of icons looks like a column, so the same action has to sit at
    // the same x on every row. The cluster used to be sized to its contents
    // and pushed right, and rows do not all carry the same actions: the
    // system row's three icons landed under the other rows' last three, so
    // "Move up" sat exactly where "Set default" sits above it and "Set
    // default" where "Delete" does. It is left-aligned in a slot as wide as
    // the fullest row now — and the missing actions are still missing rather
    // than drawn greyed out, which is why this is worth measuring at all.
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
      shared
        .map(([name]) => name)
        .includes(defaultMessages['label.manage.move-up']),
    ).toBe(true);
    await expect(
      drift.get(defaultMessages['label.manage.delete'])!.length,
    ).toBeLessThan(drift.get(defaultMessages['label.manage.move-up'])!.length);

    // Renaming happens in the row, and the list follows it.
    await userEvent.click(
      within(row('我盯的大额单')).getByRole('button', {
        name: defaultMessages['label.manage.rename'],
      }),
    );
    const title = within(row('我盯的大额单')).getByLabelText(
      defaultMessages['label.save.title'],
    );
    await userEvent.clear(title);
    await userEvent.type(title, '大额单');
    await userEvent.click(
      within(row('大额单')).getByRole('button', {
        name: defaultMessages['label.manage.rename-confirm'],
      }),
    );
    await waitFor(() => expect(row('大额单').textContent).toContain('大额单'));

    // Which view opens first is the list's to choose, and it is marked where
    // it is set.
    await userEvent.click(
      within(row('待出库订单')).getByRole('button', {
        name: defaultMessages['label.manage.set-default'],
      }),
    );
    await waitFor(() =>
      expect(row('待出库订单')).toHaveTextContent(
        defaultMessages['label.manage.default'],
      ),
    );

    // And the order is the user's, one step at a time.
    await expect(
      within(row('大额单')).getByRole('button', {
        name: defaultMessages['label.manage.move-up'],
      }),
    ).toBeDefined();

    // Deleting asks first, and says what it costs — then the scene backs out
    // of it, because nothing here is meant to be written.
    await userEvent.click(
      within(row('大额单')).getByRole('button', {
        name: defaultMessages['label.manage.delete'],
      }),
    );
    const confirm = (
      await within(document.body).findByText(
        defaultMessages['label.delete.consequence'],
      )
    ).closest('[role="dialog"]') as HTMLElement;
    await userEvent.click(
      within(confirm).getByRole('button', {
        name: defaultMessages['label.delete.keep'],
      }),
    );
    await waitFor(() =>
      expect(
        within(document.body).queryByText(
          defaultMessages['label.delete.consequence'],
        ),
      ).toBeNull(),
    );
  },
};

export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(
        defaultMessages['label.record.empty-hint'],
      ),
    ).toBeVisible();
  },
};

export const Loading: Story = {
  ...DisplayLoading,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await expect(
      table.querySelectorAll('[data-slot=skeleton]').length,
    ).toBeGreaterThan(0);
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
        name: defaultMessages['label.query.retry'],
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
      defaultMessages['label.summary.scope.page'],
    ]);
    await expect(
      [...footer.querySelectorAll('tr')].map(row => row.dataset.scope),
    ).toEqual(['page']);
    // The rows on screen add up to exactly what that row shows.
    await expect(amountOf(readPage(table, '金额'))).toBe(6470);

    // And one line above the result says why it is only a page total. It is
    // a warning, not an alert: nothing was blocked.
    const strip = await canvas.findByRole('status');
    await expect(strip).toHaveTextContent(
      defaultMessages['runtime.summary.page-only'],
    );
  },
};

export const NeedsFixing: Story = {
  ...DisplayNeedsFixing,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent(
      defaultMessages['label.view.needs-fixing'],
    );
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
        name: defaultMessages['label.record.select-all'],
      }),
    ).toBeNull();
  },
};

export const CannotOpen: Story = {
  ...DisplayCannotOpen,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByRole('alert'),
    ).toHaveTextContent(defaultMessages['label.view.unopenable']);
  },
};

/**
 * The shipped Chinese catalogue, handed to the workbench the way a host does.
 * The stories above assert the English wording, so this one is separate: it
 * says the same screen reads in Chinese when `messages` says so.
 */
export const Localized: Story = {
  ...DisplayLocalized,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The audience tag and the fold above the rows, both from the catalogue.
    await expect(
      canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]'),
    ).toHaveTextContent(zhCN['label.scope.tag.shared']);
    await expect(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    ).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: zhCN['label.toolbar.refresh'] }),
    ).toBeVisible();

    // The bar under the rows, where the measure word has to ride with the
    // number: `共 4 条记录`, and `每页` beside `20 条` rather than `每页 20`.
    const bar = paginationBar(canvasElement);
    await expect(bar).toHaveTextContent(
      formatMessage(zhCN, 'label.pagination.total', { total: 4 }),
    );
    await expect(
      within(bar).getByRole('combobox', {
        name: zhCN['label.pagination.page-size'],
      }),
    ).toHaveTextContent(
      formatMessage(zhCN, 'label.pagination.page-size-option', { size: 20 }),
    );

    // And nothing is left in English behind it.
    await expect(
      canvas.queryByRole('button', {
        name: defaultMessages['label.toolbar.refresh'],
      }),
    ).toBeNull();

    // The most visible line of the result area, which each kind used to hand
    // over as a finished English sentence: field label from the definition,
    // operator from the catalogue, option label from the definition again.
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title'],
    });
    const badge = `状态 ${zhCN['label.operator.IN']} 待出库`;
    await expect(applied).toHaveTextContent(badge);
    await expect(applied).not.toHaveTextContent(/Status|IN Pending/);

    // And it is operable: the ✕ takes the condition out of force and the
    // query runs again, which is what leaves every order on screen.
    await userEvent.click(
      within(applied).getByRole('button', {
        name: zhCN['label.filter.unset-of'].replace('{condition}', badge),
      }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('region', { name: zhCN['label.applied.title'] }),
      ).toHaveTextContent(zhCN['label.applied.all']),
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
        name: `Pinning of 订单号: ${defaultMessages['label.columns.pin.left']}`,
      }),
    ).toBeDisabled();

    // Reorder by keyboard: 状态 up one place, past 仓库.
    const handle = popover.getByRole('button', { name: 'Reorder 状态' });
    handle.focus();
    await userEvent.keyboard('{ArrowUp}');
    await expect(
      document.querySelector('[data-slot="column-announcement"]'),
    ).toHaveTextContent('状态 moved to position 2 of 4');

    // Pin 金额, then summarise it as an average rather than a sum.
    await userEvent.click(
      popover.getByRole('button', {
        name: `Pinning of 金额: ${defaultMessages['label.columns.pin.none']}`,
      }),
    );
    await userEvent.click(
      popover.getByRole('combobox', { name: 'Summary under 金额' }),
    );
    await userEvent.click(
      await popover.findByRole('option', {
        name: defaultMessages['label.summary.fn.AVG'],
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
        name: 'Direction of 金额',
      }),
    );
    await userEvent.keyboard('{Escape}');

    // What is on screen: the areas a table draws in. `订单号` and the newly
    // pinned `金额` are held on the left — pinning is what moves a column
    // between areas, since `sticky` only fixes an element where it already
    // is — and the two that scroll follow in the order just set.
    await waitFor(() =>
      expect(readHeaders(canvas.getByRole('table'))).toEqual([
        '订单号',
        '金额',
        '状态',
        '仓库',
      ]),
    );
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(
        [...PENDING_BY_AMOUNT].reverse(),
      ),
    );

    // And what was written: all four changes, in the saved config.
    await userEvent.click(
      canvas.getByRole('button', { name: defaultMessages['label.save.save'] }),
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
            { field: 'warehouse' },
            { field: 'amount', pinned: 'left' },
          ],
        },
        summaries: [{ field: 'amount', fn: 'AVG' }],
        sort: [{ field: 'amount', direction: 'ASC' }],
      });
    });
  },
};

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
        name: defaultMessages['label.workbench.collapse-sidebar'],
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
        name: defaultMessages['label.workbench.switch-view'],
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
        name: defaultMessages['label.workbench.switch-view'],
      }),
    );
    const menu = await within(document.body).findByRole('menu');
    await expect(menu).toHaveTextContent(
      defaultMessages['label.scope.group.personal'],
    );
    await expect(menu).toHaveTextContent(
      defaultMessages['label.scope.tag.system'],
    );
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
        name: defaultMessages['label.workbench.expand-sidebar'],
      }),
    );
    await expect(sidebar()).not.toBeNull();
    await expect(
      canvas.queryByRole('button', {
        name: defaultMessages['label.workbench.switch-view'],
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
      name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    await expect(
      canvasElement.querySelector('[data-slot="editor-band"]'),
    ).not.toBeNull();

    // The mode lives beside the toggle now, and drives the panel below it.
    await userEvent.click(
      canvas.getByRole('button', {
        name: defaultMessages['label.workbench.editor-modes'],
      }),
    );
    const modes = await within(document.body).findByRole('menu');
    await userEvent.click(
      within(modes).getByRole('menuitemradio', {
        name: defaultMessages['label.filter.advanced'],
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
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
      }),
    ).toHaveAccessibleName(
      `${defaultMessages['label.filter.panel']} · ${defaultMessages['label.filter.advanced']}`,
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
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
      }),
    );
    await userEvent.click(
      await canvas.findByRole('button', {
        name: defaultMessages['label.filter.add'],
      }),
    );

    const picker = await within(document.body).findByRole('dialog');
    await expect(picker).toHaveTextContent(
      defaultMessages['label.filter.pick-fields'],
    );
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
        name: defaultMessages['label.filter.pick-done'],
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
      name: defaultMessages['label.workbench.expand-view'],
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
      name: defaultMessages['label.workbench.collapse-view'],
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
        name: defaultMessages['label.workbench.expand-view'],
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
        name: defaultMessages['label.workbench.expand-view'],
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
      canvas.getByRole('button', { name: defaultMessages['label.save.save'] }),
    ).toBeVisible();

    await userEvent.click(
      within(alert).getByRole('button', {
        name: defaultMessages['label.render.retry'],
      }),
    );
    await canvas.findByRole('table');
    await expect(canvas.queryByRole('alert')).toBeNull();
  },
};

/**
 * 冻结列的边说的是「有行正从我下面经过」，所以它只在那是真的时候才在。
 *
 * `usePinnedEdges` 把滚动位置写成表上的两个属性，边界格子经 group 变体读它：
 * 滚动条在起点时左边界没有边、在终点时右边界没有、中间两边都有；两个冻结列之
 * 间永远没有——那道缝下面没有东西经过。表头、数据行、汇总行三层读的是同一份
 * 属性，所以同一列在三层上要么都有边、要么都没有。jsdom 不算布局也不套样式
 * 表，`box-shadow` 的真值只有这里量得到。
 */
export const PinnedEdges: Story = {
  ...DisplayPinnedEdges,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await expect(area).toHaveAttribute('data-scrolls');

    // The premise: two columns frozen left and the actions frozen right, and
    // a middle that has to scroll — a table that fits has nothing under any
    // of its columns, so the area is narrowed until it does not.
    const inner = headerOf(table, '订单号');
    const left = headerOf(table, '金额');
    const right = table.querySelector<HTMLTableCellElement>(
      'thead th[data-column="actions"]',
    )!;
    await expect(inner).toHaveAttribute('data-pin', 'left');
    await expect(left).toHaveAttribute('data-pin', 'left');
    area.style.maxWidth = '420px';
    await waitFor(() =>
      expect(area.scrollWidth).toBeGreaterThan(area.clientWidth),
    );

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
      right: edged(right),
    });
    const NONE = [false, false, false];
    const ALL = [true, true, true];

    // At the start nothing is under the left column and rows are under the
    // right one; between the two frozen columns there is never anything.
    await waitFor(() =>
      expect(edges()).toEqual({ inner: NONE, left: NONE, right: ALL }),
    );
    area.scrollLeft = 40;
    await waitFor(() =>
      expect(edges()).toEqual({ inner: NONE, left: ALL, right: ALL }),
    );
    area.scrollLeft = area.scrollWidth;
    await waitFor(() =>
      expect(edges()).toEqual({ inner: NONE, left: ALL, right: NONE }),
    );
    area.scrollLeft = 0;
    await waitFor(() =>
      expect(edges()).toEqual({ inner: NONE, left: NONE, right: ALL }),
    );
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
      name: defaultMessages['label.workbench.expand-view'],
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
    ).toEqual([defaultMessages['label.refresh.off'], ...LADDER.map(cadenceOf)]);
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
    await expect(
      canvas.getByText(defaultMessages['label.header.unsaved']),
    ).toBeVisible();

    // And off again, from the keyboard: the chevron opens on Enter and hands
    // focus to the options.
    chevron.focus();
    await userEvent.keyboard('{Enter}');
    const reopened = await within(document.body).findByRole('menu');
    await userEvent.click(
      within(reopened).getByRole('menuitemradio', {
        name: defaultMessages['label.refresh.off'],
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
    trigger: `[aria-label="${defaultMessages['label.manage.open']}"]`,
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
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
      }),
    );
    await userEvent.click(
      await canvas.findByRole('button', {
        name: defaultMessages['label.filter.add'],
      }),
    );
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '金额' }),
    );
    // Shut behind itself, so nothing is measured through a popup and axe
    // judges the page as a user would leave it.
    await userEvent.click(
      within(picker).getByRole('button', {
        name: defaultMessages['label.filter.pick-done'],
      }),
    );
    const band = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="editor-band"]',
      );
      if (!found) throw new Error('The editor band has not opened.');
      return found;
    });

    // Ticked, a checkbox is a filled square and this stops being the whole
    // of it; the measurement is about the state that has nothing else.
    const checkbox = canvas.getByRole('checkbox', {
      name: defaultMessages['label.record.select-all'],
    });
    await expect(checkbox).not.toBeChecked();

    const controls = {
      checkbox,
      input: within(band).getByRole('spinbutton'),
      select: within(paginationBar(canvasElement)).getByRole('combobox', {
        name: defaultMessages['label.pagination.page-size'],
      }),
    };

    // All three are measured before anything is asserted, so a failure says
    // what every control came to rather than stopping at the first one.
    const measured = Object.entries(controls).map(([name, control]) => ({
      name,
      ...measureBorderContrast(control),
    }));
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
