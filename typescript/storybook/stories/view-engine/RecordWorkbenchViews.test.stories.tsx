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
import type { RecordViewConfig } from '@ahoo-wang/wow-view-engine';
import displayMeta, {
  DeleteConflicted as DisplayDeleteConflicted,
  ManageViews as DisplayManageViews,
  RenameConflicted as DisplayRenameConflicted,
  SaveConflicted as DisplaySaveConflicted,
  SaveRefused as DisplaySaveRefused,
  SaveResultUnknown as DisplaySaveResultUnknown,
} from './RecordWorkbench.stories.js';
import { outcomesStore } from './outcomesStore.js';
import { dragHandleOnto } from './pointerDrag.js';
import { readColumn } from './readTable.js';
import {
  PENDING_BY_AMOUNT,
  addSort,
  deleteDialog,
  listItem,
  managerRow,
  openManager,
  positionOf,
  pressWhenEnabled,
  save,
  say,
} from './recordWorkbenchTest.js';

/**
 * Managing views and saving them: the manager, the default view, and every
 * write outcome. One of the record workbench's regression files, split by
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

    // Every view of the definition is here, grouped as the sidebar groups
    // them; a system view ships with the definition, so it cannot be
    // deleted. The dialog fades in, so the rows are awaited rather than read
    // at once.
    await waitFor(() => expect(row('待出库订单')).toBeDefined());
    // The definition's analysis view sits in the same list as its record
    // views (D20): one data workbench draws both kinds, so the manager
    // orders both.
    await expect(row('仓库金额分布')).toBeDefined();
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
    // Four rows: the three record views and the analysis view beside them.
    await expect(handles).toHaveLength(4);
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
    // The star is the mark, pressed in — no 「默认」 badge beside it.
    await waitFor(() =>
      expect(
        within(row('待出库订单')).getByRole('button', {
          name: zhCN['label.manage.unset-default'],
        }),
      ).toHaveAttribute('aria-pressed', 'true'),
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
    ).toContain('fve:fill-current');
    await expect(
      within(row('全部订单'))
        .getByRole('button', {
          name: zhCN['label.manage.set-default'],
        })
        .querySelector('svg')!.classList,
    ).not.toContain('fve:fill-current');

    // And the order is the user's: a row is carried by its handle rather
    // than clicked up one step at a time. This is the half jsdom cannot
    // run — `@dnd-kit/dom` picks its drop target by measuring boxes, and
    // every box there is 0×0 at the origin (see pointerDrag.ts).
    // The shared analysis view is in the same sortable list as the shared
    // record views (D20): one workbench, one order.
    const SHARED = ['全部订单', '待出库订单', '仓库金额分布'];
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
    await expect(before).toHaveLength(3);

    await dragHandleOnto(
      within(row(before[1])).getByRole('button', {
        name: say('label.manage.drag', { title: before[1] }),
      }),
      row(before[0]),
    );

    // The shared group reordered, and the personal one did not: the two
    // audiences are two sortable lists, so nothing can be carried across the
    // line between them.
    await waitFor(() =>
      expect(order()).toEqual([before[1], before[0], before[2]]),
    );
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

    // The catalogue's sentence carries the store's own reason — the only
    // part that says *what* was wrong — and says it once.
    const reason = '这个视图由运维托管，不接受修改';
    const refused = zhCN['view.write.invalid'].replace('{reason}', reason);
    const band = await outcomeBand(canvasElement, refused);
    await expect(band).toHaveTextContent(refused);
    await expect(band.textContent?.split(reason)).toHaveLength(2);
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
        canvas.queryByText(refused, {
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

/** The conflict reported under one manager row, once it appears. */
async function conflictLine(title: string): Promise<HTMLElement> {
  await within(document.body).findByText(zhCN['label.write.conflict']);
  return managerRow(title);
}

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
      expect(
        within(managed).getByRole('button', {
          name: zhCN['label.manage.unset-default'],
        }),
      ).toHaveAttribute('aria-pressed', 'true'),
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
    await expect(star.classList).toContain('fve:fill-current');
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
 * 设为共享／设为个人 (D18 item 10): the view moves to the other audience in
 * place — the same id, so a board that shows it goes on showing it. The row's
 * button says where it sends the view, the row lands under the other heading
 * of the dialog and of the sidebar, the move is said once it has landed, and
 * focus follows the row to its new place, on the button that says the way
 * back.
 */
export const ShareViewInPlace: Story = {
  ...DisplayManageViews,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const group = (title: string) =>
      listItem(canvasElement, title)
        .closest('[data-slot="view-group"]')
        ?.querySelector('[data-slot="view-group-heading"]')?.textContent;
    await expect(group('我盯的大额单')).toBe(
      zhCN['label.scope.group.personal'],
    );

    const row = await openManager(canvas, '我盯的大额单');
    const share = within(row).getByRole('button', {
      name: zhCN['label.manage.share'],
    });
    // In the same cell of every row as the way back is on a shared one:
    // one column of icons, one action per column.
    const back = within(managerRow('待出库订单')).getByRole('button', {
      name: zhCN['label.manage.make-personal'],
    });
    await expect(Math.round(share.getBoundingClientRect().x)).toBe(
      Math.round(back.getBoundingClientRect().x),
    );
    await userEvent.click(share);

    const moved = () =>
      document.querySelector<HTMLElement>(
        '[data-slot="view-manager-group"][data-audience="shared"]',
      )?.textContent ?? '';
    await waitFor(() => expect(moved()).toContain('我盯的大额单'));
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-manager-announcement"]')
          ?.textContent,
      ).toBe(say('label.manage.shared', { title: '我盯的大额单' })),
    );
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(managerRow('我盯的大额单')).getByRole('button', {
          name: zhCN['label.manage.make-personal'],
        }),
      ),
    );

    // Focus on an icon button opens its name as a tooltip, and the first
    // Escape closes that; the second closes the dialog.
    await userEvent.keyboard('{Escape}');
    if (document.body.querySelector('[role="dialog"]'))
      await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(document.body.querySelector('[role="dialog"]')).toBeNull(),
    );
    await expect(group('我盯的大额单')).toBe(zhCN['label.scope.group.shared']);
  },
};

/**
 * A screen shorter than the list: the dialog stays inside the viewport with
 * its heading in view, and the list scrolls inside it rather than running
 * off the top and bottom of the screen (the compensation console's 30-odd
 * system views did).
 */
export const ManagerFitsAShortScreen: Story = {
  ...DisplayManageViews,
  globals: { viewport: { value: 'short' } },
  parameters: {
    ...DisplayManageViews.parameters,
    viewport: {
      options: {
        short: {
          name: '1280×260',
          styles: { width: '1280px', height: '260px' },
        },
      },
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.manage.open'] }),
    );
    const dialog = await within(document.body).findByRole('dialog');
    const list = dialog.querySelector<HTMLElement>(
      '[data-slot="view-manager-body"]',
    )!;
    await waitFor(() =>
      expect(
        list.querySelectorAll('[data-slot="view-manager-row"]').length,
      ).toBeGreaterThan(3),
    );
    await waitFor(() => {
      const box = dialog.getBoundingClientRect();
      expect(box.top, 'the dialog starts on screen').toBeGreaterThanOrEqual(0);
      expect(box.bottom, 'the dialog ends on screen').toBeLessThanOrEqual(
        window.innerHeight,
      );
    });
    const heading = within(dialog).getByRole('heading', {
      name: zhCN['label.manage.heading'],
    });
    await expect(heading.getBoundingClientRect().top).toBeGreaterThanOrEqual(0);
    // The list is the scroller, and its last row can be reached.
    await expect(list.scrollHeight).toBeGreaterThan(list.clientHeight);
    list.scrollTop = list.scrollHeight;
    const rows = list.querySelectorAll<HTMLElement>(
      '[data-slot="view-manager-row"]',
    );
    const last = rows[rows.length - 1]!.getBoundingClientRect();
    await expect(last.bottom).toBeLessThanOrEqual(
      list.getBoundingClientRect().bottom + 1,
    );
    // The heading did not scroll away with it.
    await expect(heading.getBoundingClientRect().top).toBeGreaterThanOrEqual(
      dialog.getBoundingClientRect().top,
    );
  },
};
