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
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { readColumn } from './readTable.js';
import { PENDING_BY_AMOUNT, headerOf, say } from './recordWorkbenchTest.js';

/**
 * The record workbench's known accessibility items, each in a real browser:
 * the toolbar's popups off the bar's roving order, a column's width without
 * a drag and without a query, the copy button kept inside a narrow cell,
 * and the table's own name. One of the record workbench's regression files,
 * split by concern; it shares their title.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The table once the shared view's rows are in it. */
async function settledTable(canvasElement: HTMLElement): Promise<HTMLElement> {
  const table = await within(canvasElement).findByRole('table');
  await waitFor(() =>
    expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
  );
  return table;
}

/** Every sentence a live region says from now on, each once, in order. */
function listen(slot: string): { heard: string[]; stop(): void } {
  const heard: string[] = [];
  const region = () =>
    document.querySelector<HTMLElement>(`[data-slot="${slot}"]`)!;
  const observer = new MutationObserver(() => {
    const text = region().textContent?.trim() ?? '';
    if (text !== '') heard.push(text);
  });
  observer.observe(region(), {
    characterData: true,
    childList: true,
    subtree: true,
  });
  return { heard, stop: () => observer.disconnect() };
}

/**
 * The column settings, from their search box to the end, by Tab alone.
 *
 * The popups of the result toolbar are drawn beside the bar now, not under
 * it (`DetachedPopover`): Base UI's toolbar handed its roving focus to its
 * whole React subtree, so every control in a popup believed it was a stop
 * of the bar, and only a `tabIndex` written by hand kept a checkbox
 * reachable. Every row's checkbox, width box, pin and summary select is a
 * stop here — and each writes its own `tabindex="0"`, which is what
 * Safari's default Tab (fields and `tabindex` only) needs: the WebKit
 * criterion, held on the structure Chromium shares with it.
 */
export const ToolbarPopupsKeepTheirTabStops: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    await settledTable(canvasElement);
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog', {
      name: zhCN['label.columns.title'],
    });
    const search = within(dialog).getByRole('textbox', {
      name: zhCN['label.field.search'],
    });
    await waitFor(() => expect(document.activeElement).toBe(search));

    // Each control reached, with the `tabindex` it wore as it was reached.
    // Read then, not after the walk: Tab past the last control closes the
    // popover, and a closing popup takes its controls out of the order.
    const reached = new Map<Element, string | null>();
    const controls = [
      ...['仓库', '状态', '金额'].flatMap(field => [
        within(dialog).getByRole('checkbox', {
          name: say('label.columns.show', { field }),
        }),
        within(dialog).getByRole('button', {
          name: say('label.columns.pin', { field }),
        }),
        within(dialog).getByRole('textbox', {
          name: say('label.columns.width', { field }),
        }),
      ]),
      within(dialog).getByRole('combobox', {
        name: say('label.columns.summary', { field: '金额' }),
      }),
    ];
    for (let step = 0; step < 80; step += 1) {
      await userEvent.tab();
      const at = document.activeElement;
      if (!at || !dialog.contains(at)) break;
      reached.set(at, at.getAttribute('tabindex'));
    }

    for (const control of controls) {
      const name = control.getAttribute('aria-label');
      await expect(reached.has(control) ? name : null).toBe(name);
      // A field is a stop by itself; every other control says it.
      if (control.tagName !== 'INPUT')
        await expect(`${name}: ${reached.get(control)}`).toBe(`${name}: 0`);
    }
  },
};

/**
 * A column's width without a drag (WCAG 2.2 2.5.7), by clicks and typing
 * alone: the box on its row in the column settings. The header's width
 * keys are written above the rows, where a sighted keyboard user reads
 * them. And nothing is fetched for it — a width is drawn, not asked.
 */
export const ColumnWidthByTyping: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const table = await settledTable(canvasElement);
    const querying = listen('record-announcement');
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog', {
      name: zhCN['label.columns.title'],
    });
    await expect(
      dialog.querySelector('[data-slot="column-resize-keys"]'),
    ).toHaveTextContent(zhCN['label.columns.resize-keys']);

    const box = within(dialog).getByRole('textbox', {
      name: say('label.columns.width', { field: '仓库' }),
    });
    await userEvent.click(box);
    await userEvent.type(box, '160{Enter}');

    await waitFor(() =>
      expect(
        Math.round(headerOf(table, '仓库').getBoundingClientRect().width),
      ).toBe(160),
    );
    await expect(
      document.querySelector('[data-slot="column-announcement"]'),
    ).toHaveTextContent(
      say('label.columns.resized', { field: '仓库', width: 160 }),
    );
    querying.stop();
    await expect(querying.heard).toEqual([]);
  },
};

/**
 * Three steps of a column's width from its header, three sentences of its
 * width, and no query: a width used to run the page again, and a reader
 * heard 「正在查询」 and the record count at every step and never the width.
 */
export const WidthStepsSayTheWidth: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const table = await settledTable(canvasElement);
    // The surface's one voice: the result block's region, which the table
    // borrows — so a query would be heard here too.
    const voice = listen('record-announcement');
    // The column's stop in the header row: its sort button, or the cell
    // itself where the column does not sort.
    const cell = headerOf(table, '仓库');
    (cell.querySelector<HTMLElement>('button') ?? cell).focus();
    const from = Math.round(cell.getBoundingClientRect().width);

    for (let step = 0; step < 3; step += 1)
      await userEvent.keyboard('{Alt>}{ArrowRight}{/Alt}');

    await waitFor(() => expect(voice.heard).toHaveLength(3));
    await expect(voice.heard).toEqual(
      [8, 16, 24].map(by =>
        say('label.columns.resized', { field: '仓库', width: from + by }),
      ),
    );
    voice.stop();
    // The rows are the ones that were there: nothing ran.
    await expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT);
  },
};

/**
 * A column narrower than its values keeps them — and the copy button — in
 * its own cells (WCAG 2.2 2.4.11): each 「复制」, focused, has all four
 * corners inside its cell and is itself at its own centre. The cell's clip
 * had lost its prefix (D66) and compiled to nothing, so the pair spilled
 * over the next column and stood under its ground.
 */
export const CopyStaysInANarrowCell: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const table = await settledTable(canvasElement);
    const cell = headerOf(table, '订单号');
    (cell.querySelector<HTMLElement>('button') ?? cell).focus();
    for (let step = 0; step < 12; step += 1)
      await userEvent.keyboard('{Alt>}{ArrowLeft}{/Alt}');
    await waitFor(() =>
      expect(
        Math.round(headerOf(table, '订单号').getBoundingClientRect().width),
      ).toBe(48),
    );

    const copies = [
      ...table.querySelectorAll<HTMLElement>('[data-slot="cell-copy"]'),
    ];
    await expect(copies).toHaveLength(PENDING_BY_AMOUNT.length);
    for (const copy of copies) {
      copy.focus();
      const box = copy.getBoundingClientRect();
      const cell = copy.closest('td')!.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(cell.left);
      await expect(box.right).toBeLessThanOrEqual(cell.right);
      await expect(box.top).toBeGreaterThanOrEqual(cell.top);
      await expect(box.bottom).toBeLessThanOrEqual(cell.bottom);
      const centre = document.elementFromPoint(
        box.left + box.width / 2,
        box.top + box.height / 2,
      );
      await expect(copy.contains(centre)).toBe(true);
    }
  },
};

/** The table is called by its view, for a reader moving by tables. */
export const TableNamedByItsView: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    await settledTable(canvasElement);
    const title = canvasElement.querySelector('[data-slot="view-title"]');
    await expect(
      within(canvasElement).getByRole('table', {
        name: title?.textContent?.trim() ?? '',
      }),
    ).toBeInTheDocument();
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('lang', 'zh-CN');
  },
};
