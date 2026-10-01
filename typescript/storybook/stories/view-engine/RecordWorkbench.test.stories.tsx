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
import { en, formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  CannotOpen as DisplayCannotOpen,
  EmptyResult as DisplayEmptyResult,
  English as DisplayEnglish,
  Loading as DisplayLoading,
  NeedsFixing as DisplayNeedsFixing,
  NoViews as DisplayNoViews,
  Opening as DisplayOpening,
  RenderFailure as DisplayRenderFailure,
  QueryFailed as DisplayQueryFailed,
  TotalCoversThisPageOnly as DisplayTotalCoversThisPageOnly,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { ordersDefinition } from './fixtures.js';
import { amountOf, readColumn, readPage, readTotal } from './readTable.js';
import {
  PENDING_BY_AMOUNT,
  addSort,
  badgeIn,
  headerOf,
  paginationBar,
  positionOf,
  say,
  scopeLabels,
} from './recordWorkbenchTest.js';

/**
 * States: one story per state of the display file — data, empty, loading,
 * failed, needs fixing, opening, a new view, cannot open, English, a render
 * failure. One of the record workbench's regression files, split by concern;
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

export const WithData: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    // The total covers what the conditions select rather than every order.
    // The four rows it covers are all on this one page, so there is one
    // summary row, not two: 「本页」 would repeat 「全部」 under another name
    // (D26 Q40). `Paged` below is where both scopes stand side by side.
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
    await expect(scopeLabels(table)).toEqual([
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

    // There are rows, so there is something to take away: Export is at the
    // end of the bar (U4 is the other half of this — with no result it is
    // not there at all, see `QueryFailed`).
    await expect(
      canvas.getByRole('button', { name: zhCN['label.export.title'] }),
    ).toBeVisible();

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
      say('label.pagination.total', { count: 4 }),
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

export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // The saved view asks exactly what it was saved to ask, so what it says
    // is that the view is empty right now — not that "the conditions" match
    // nothing, as though they were something to take away.
    await expect(
      await canvas.findByText(zhCN['label.record.empty-view']),
    ).toBeVisible();

    // One way out, and not the one that would turn 「待出库订单」 into every
    // order under its name: its condition is what it is, so the way on is to
    // ask something else, and the button opens the conditions.
    const edit = canvas.getByRole('button', {
      name: zhCN['label.record.empty-edit'],
    });
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.record.empty-clear'] }),
    ).toBeNull();
    await userEvent.click(edit);
    await expect(
      await canvas.findByRole('button', { name: /^应用/ }),
    ).toBeVisible();

    // Nothing was taken away: the band still names the view's condition.
    const applied = canvasElement.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    )!;
    await expect(applied.querySelectorAll('[data-slot="badge"]')).toHaveLength(
      1,
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

    // And nothing in the bar above offers to take rows away that are not
    // there (U4, user 2026-09-22): the frame stands because the strip is
    // what it holds, but Export over no result opened a window onto an
    // empty file. A control that cannot apply is absent, not disabled
    // (P-17) — the two that say how the result is drawn stay, because the
    // view is still a view and still worth setting up.
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.export.title'] }),
    ).toBeNull();
    await expect(
      canvas.getByRole('button', { name: zhCN['label.toolbar.columns'] }),
    ).toBeVisible();

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
    // over it and no fold under it. "这个视图需要修复才能运行 · 展开 1 项"
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
    // The sentence names both rooms — the sidebar's line and the work
    // area's title — so the work area is found by its slot, not its words.
    const workArea = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-none"]',
      );
      if (!found) throw new Error('no empty work area');
      return found;
    });
    // Three ways in: the work area's button, the sidebar's `+`. The switcher
    // item is the third, behind a fold this story keeps open.
    // The view list, not the host application's navigation beside it: both
    // are navigation landmarks, and the list is the one named after the
    // definition it lists.
    const sidebar = canvas.getByRole('navigation', {
      name: ordersDefinition.title,
    });
    await expect(
      within(sidebar).getByRole('button', { name: zhCN['label.view.new'] }),
    ).toBeVisible();
    // And the sidebar draws that one control and no more: where the views
    // would be there is a line and nothing to press (user, 2026-09-22) —
    // the state is stated in full once, in the room the view will fill.
    const body = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-list-body"]',
    )!;
    await expect(
      body.querySelector('[data-slot="view-list-empty"]'),
    ).toHaveTextContent(zhCN['label.view.none']);
    await expect(within(body).queryByRole('button')).toBeNull();
    await expect(
      within(body).queryByText(zhCN['label.view.none-hint']),
    ).toBeNull();

    await userEvent.click(
      within(workArea).getByRole('button', { name: zhCN['label.view.new'] }),
    );
    // Both kinds may be made here, so the button is a menu of the two (D20
    // Ⅱ): the kind decides the kernel, and is asked before the view opens.
    await userEvent.click(
      await within(document.body).findByRole('menuitem', {
        name: zhCN['label.kind.record'],
      }),
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
    ).toHaveTextContent(en['label.scope.tag.shared']);
    await expect(
      canvas.getByRole('button', {
        name: new RegExp(`^${en['label.filter.panel']}`),
      }),
    ).toBeVisible();
    await expect(
      canvas.getByRole('button', {
        name: en['label.toolbar.refresh'],
      }),
    ).toBeVisible();

    // The bar under the rows, where the count and the page size are sentences
    // with numbers in them rather than numbers with words beside them.
    const bar = paginationBar(canvasElement);
    await expect(bar).toHaveTextContent(
      formatMessage(en, 'label.pagination.total', { count: 4 }),
    );
    await expect(
      within(bar).getByRole('combobox', {
        name: en['label.pagination.page-size'],
      }),
    ).toHaveTextContent(
      formatMessage(en, 'label.pagination.page-size-option', {
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
      name: en['label.applied.title'],
    });
    // One value reads 'is' however it was stored (`label.relation.is`).
    const badge = `状态 ${en['label.relation.is']} 待出库`;
    await expect(applied).toHaveTextContent(badge);
    await expect(applied).not.toHaveTextContent(zhCN['label.relation.is']);

    // And it is operable: the ✕ takes the condition out of force and the
    // query runs again, which is what leaves every order on screen.
    await userEvent.click(
      within(applied).getByRole('button', {
        name: en['label.filter.unset-of'].replace('{condition}', badge),
      }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('region', {
          name: en['label.applied.title'],
        }),
      ).toHaveTextContent(en['label.applied.all']),
    );
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toHaveLength(6),
    );
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

function slots(canvasElement: HTMLElement): string[] {
  return [...canvasElement.querySelectorAll('[data-slot]')]
    .map(node => node.getAttribute('data-slot') ?? '')
    .filter(slot => LAYOUT_SLOTS.includes(slot));
}

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
