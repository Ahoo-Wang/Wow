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
import { formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import type { RecordViewConfig } from '@ahoo-wang/wow-view-engine';
import displayMeta, {
  PinnedGroupCapped as DisplayPinnedGroupCapped,
  TableSettings as DisplayTableSettings,
  WideTable as DisplayWideTable,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { tableSettingsStore } from './fixtures.js';
import { dragEdgeBy, dragHandleOnto } from './pointerDrag.js';
import { readColumn, readHeaders } from './readTable.js';
import {
  PENDING_BY_AMOUNT,
  confirmSharedSave,
  headerOf,
  positionOf,
  say,
} from './recordWorkbenchTest.js';

/**
 * The column settings, the sort, column widths and the card settings. One of
 * the record workbench's regression files, split by concern; they all share one
 * title, so every story keeps its id, and the helpers more than one of them
 * needs are in `recordWorkbenchTest.ts`.
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
 * 表头排序不替人应用别的修改。
 *
 * 范围里删掉「状态」这条条件、还没按「应用」，这时按「订单号」表头：从前
 * 这一下把整份草稿都跑了，删掉的条件跟着生效。现在排序只并进待应用——行
 * 不动、表头的箭头仍说屏幕上这些行的次序（金额降序）、「应用」上亮起那颗
 * 点——按「应用」时两件事一起跑：不止待出库的那几单，按订单号升序。
 */
export const HeaderSortWaitsForApply: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const apply = await canvas.findByRole('button', {
      name: zhCN['label.filter.apply'],
    });
    await userEvent.click(
      canvas.getByRole('button', {
        name: say('label.filter.remove-of', { field: '状态' }),
      }),
    );
    await waitFor(() =>
      expect(apply.querySelector('[data-slot="pending-dot"]')).not.toBeNull(),
    );

    await userEvent.click(headerOf(table, '订单号').querySelector('button')!);

    // Nothing ran: the same rows in the same order, and the headers still
    // say that order rather than the one waiting.
    await expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT);
    await expect(headerOf(table, '金额')).toHaveAttribute(
      'aria-sort',
      'descending',
    );
    await expect(headerOf(table, '订单号')).not.toHaveAttribute('aria-sort');
    // The header's name says what its next press does from what waits, and
    // that it waits — the arrow still says what ran.
    await expect(
      headerOf(table, '订单号').querySelector('button'),
    ).toHaveAttribute(
      'aria-label',
      `${say('label.sort.descending', { field: '订单号' })} · ${zhCN['label.sort.waiting.asc']}`,
    );
    // The sort control reads the draft: it says what Apply is about to run.
    await expect(
      canvas.getByRole('button', {
        name: say('label.sort.button', {
          field: '订单号',
          direction: zhCN['label.sort.asc'],
        }),
      }),
    ).toBeVisible();
    await expect(
      apply.querySelector('[data-slot="pending-dot"]'),
    ).not.toBeNull();

    // Apply runs the two together: the orders that are not pending are in,
    // and every order is in its number's place.
    await userEvent.click(apply);
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toContain('SO-1002'),
    );
    const numbers = readColumn(table, '订单号');
    await expect(numbers.length).toBeGreaterThan(PENDING_BY_AMOUNT.length);
    await expect(numbers).toEqual([...numbers].sort());
    await expect(headerOf(table, '订单号')).toHaveAttribute(
      'aria-sort',
      'ascending',
    );
    await expect(apply.querySelector('[data-slot="pending-dot"]')).toBeNull();
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
    // One page, so one line: 「全部」 alone, as under the table (D26 Q40).
    await expect(summaries).toHaveTextContent(
      zhCN['label.summary.scope.total'],
    );
    await expect(summaries).not.toHaveTextContent(
      zhCN['label.summary.scope.page'],
    );

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
        name: formatMessage(zhCN, 'label.card.per-row-option', { count: 2 }),
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="record-cards"]'),
      ).toHaveClass('fve:sm:grid-cols-2'),
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

    // Two region headings and no row for the host's actions: a column is
    // pinned to the left or not at all (D19), and the right edge is the
    // engine's frame rather than anything a reader sets here.
    await expect(
      popover
        .getAllByRole('heading', { level: 3 })
        .map(heading => heading.textContent),
    ).toEqual([zhCN['label.columns.pin.left'], zhCN['label.columns.pin.none']]);
    await expect(
      popover.queryByRole('checkbox', {
        name: say('label.columns.show', {
          field: zhCN['label.toolbar.actions'],
        }),
      }),
    ).toBeNull();

    // The key column is held and says so — `aria-pressed`, on a toggle that
    // is refused because the projection decides this one. The rest of the
    // list is the order the table is in.
    const key = popover.getByRole('button', {
      name: say('label.columns.pin', { field: '订单号' }),
    });
    await expect(key).toBeDisabled();
    await expect(key).toHaveAttribute('aria-pressed', 'true');

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

    // 金额 is the column the table draws last, so the table holds it
    // against the right edge (D13) — and that is the frame rather than a
    // setting (D19), so its row wears the same live toggle as any other.
    const last = popover.getByRole('button', {
      name: say('label.columns.pin', { field: '金额' }),
    });
    await expect(last).toBeEnabled();
    await expect(last).toHaveAttribute('aria-pressed', 'false');

    // Pin 仓库, which is one of the three that scroll, then summarise 金额
    // as an average rather than a sum.
    const pinName = say('label.columns.pin', { field: '仓库' });
    await userEvent.click(popover.getByRole('button', { name: pinName }));
    // The press is reported as the toggle's own state, and said in the
    // panel's live region — which is where the column and the edge are
    // named, because "pressed" names neither. Read back off the panel
    // rather than off the node that was pressed: the row moves to the
    // other area's list, so React mounts it again there.
    await waitFor(() =>
      expect(popover.getByRole('button', { name: pinName })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
    await expect(
      document.querySelector('[data-slot="column-announcement"]'),
    ).toHaveTextContent(say('label.columns.pinned.left', { field: '仓库' }));
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
    // between the areas, since `sticky` only fixes an element where it
    // already is — `状态` scrolls, and `金额` is held at the right end for
    // being drawn last.
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
    await confirmSharedSave();
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
            { field: 'warehouse', pinned: true },
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
    // drag — its handle is refused — and the row key is held in the other
    // area, which is a sortable list of its own. Read by what the panel
    // offers rather than by a list of fields, so a definition that grows
    // another field does not turn this into a test about the fixture.
    const draggable = [
      ...document.querySelectorAll<HTMLElement>(
        '[data-slot="column-region"][data-region="scrolling"] [data-slot="column-setting"]',
      ),
    ].filter(
      row => row.querySelector('button')?.hasAttribute('disabled') === false,
    );
    await expect(draggable.map(row => row.dataset.field)).toEqual([
      'warehouse',
      'status',
      'amount',
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
    await confirmSharedSave();
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
    await confirmSharedSave();
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).table.columns).toEqual([
        { field: 'id', pinned: true },
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
    await confirmSharedSave();
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

  // The two areas first (D19), the catalogue inside the scrolling one, in
  // the order the definition declares its groups — and the fields no group
  // lists in front of all of them, under no heading of their own.
  await expect(headings()).toEqual([
    zhCN['label.columns.pin.left'],
    zhCN['label.columns.pin.none'],
    '收发双方',
    '运输',
    '计费',
    '时间',
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
