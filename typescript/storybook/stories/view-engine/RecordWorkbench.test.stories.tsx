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
import { defaultMessages } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  CannotOpen as DisplayCannotOpen,
  EmptyResult as DisplayEmptyResult,
  Loading as DisplayLoading,
  ManageViews as DisplayManageViews,
  NeedsFixing as DisplayNeedsFixing,
  QueryFailed as DisplayQueryFailed,
  WithActions as DisplayWithActions,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/Record 工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The shared view's condition and sort, as the source answered them. */
const PENDING_BY_AMOUNT = ['SO-1003', 'SO-1005', 'SO-1001', 'SO-1006'];

export const WithData: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    // The total covers what the conditions select, not every order.
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);

    // The page reads from what the view is down to the rows and their paging.
    await expect(slots(canvasElement)).toEqual([
      'view-header',
      'editor-band',
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

    // Paging is under the rows it pages, not in the toolbar.
    const paging = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    );
    await expect(paging).toHaveTextContent('4');

    // Opening the fold brings back the one way out of the editor.
    await userEvent.click(band);
    await expect(
      await canvas.findByRole('button', {
        name: defaultMessages['label.filter.apply'],
      }),
    ).toBeVisible();
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
