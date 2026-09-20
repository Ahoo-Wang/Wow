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
import displayMeta, {
  CannotOpen as DisplayCannotOpen,
  CollapsedSidebar as DisplayCollapsedSidebar,
  EmptyResult as DisplayEmptyResult,
  Loading as DisplayLoading,
  Localized as DisplayLocalized,
  ManageViews as DisplayManageViews,
  NeedsFixing as DisplayNeedsFixing,
  Paged as DisplayPaged,
  QueryFailed as DisplayQueryFailed,
  TotalCoversThisPageOnly as DisplayTotalCoversThisPageOnly,
  WithActions as DisplayWithActions,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { amountOf, readColumn, readPage, readTotal } from './readTable.js';

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
    // A config the definition refuses is never run.
    await expect(
      canvas.getByRole('table').querySelectorAll('tbody tr'),
    ).toHaveLength(0);
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
