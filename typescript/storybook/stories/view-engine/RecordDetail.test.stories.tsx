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
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  HostSections as DisplayHostSections,
  LinkedFailed as DisplayLinkedFailed,
  LinkedForbidden as DisplayLinkedForbidden,
  LinkedNotThere as DisplayLinkedNotThere,
  LinkedOffPage as DisplayLinkedOffPage,
  LinkedOnPage as DisplayLinkedOnPage,
  LinkedReading as DisplayLinkedReading,
  NestedEmbedDetail as DisplayNestedEmbedDetail,
} from './RecordDetail.stories.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/记录详情/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see RecordWorkbench.test.stories).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The detail panel, once it is open. */
const detail = () => screen.findByRole('dialog');

/** The data row of one order, by its key. */
async function rowOf(canvasElement: HTMLElement, key: string) {
  await within(canvasElement).findByRole('table');
  return waitFor(() => {
    const row = canvasElement.querySelector<HTMLElement>(
      `tr[data-row-key="${key}"]`,
    );
    if (!row) throw new Error(`no row ${key}`);
    return row;
  });
}

/** The section headings of the panel, in reading order. */
const sectionTitles = (panel: HTMLElement) =>
  within(panel)
    .getAllByRole('heading', { level: 3 })
    .map(heading => heading.textContent);

/** The engine's own section of the fields no group gathers. */
const fields = (panel: HTMLElement) =>
  within(panel).findByRole(
    'region',
    { name: zhCN['label.record.detail.other'] },
    { timeout: 5_000 },
  );

/** What the host's address says, as the host wrote it. */
const address = (canvasElement: HTMLElement) =>
  canvasElement
    .querySelector('[data-host-address]')
    ?.getAttribute('data-host-address');

export const HostSections: Story = {
  ...DisplayHostSections,
  play: async ({ canvasElement }) => {
    const row = await rowOf(canvasElement, 'SO-1003');
    // The keyboard's way in: the row, then Enter.
    row.focus();
    await userEvent.keyboard('{Enter}');
    const panel = await detail();
    // Focus goes into the panel, onto the record's key.
    const key = within(panel).getByRole('heading', { name: 'SO-1003' });
    await waitFor(() => expect(document.activeElement).toBe(key));
    await expect(address(canvasElement)).toBe('?id=SO-1003');

    // The host's sections stand among the engine's, each a named region.
    await waitFor(() =>
      expect(sectionTitles(panel)).toEqual([
        '处理',
        zhCN['label.record.detail.other'],
        '同仓订单',
      ]),
    );
    const handling = within(panel).getByRole('region', { name: '处理' });
    await expect(handling).toHaveAttribute('data-section', 'handling');
    // The embedded view in a section reads once the record is open: the
    // orders of this one's warehouse (华北), which is this one alone.
    const others = within(panel).getByRole('region', { name: '同仓订单' });
    await waitFor(
      () => {
        if (
          !within(others).queryByRole('table')?.textContent?.includes('SO-1003')
        )
          throw new Error(others.textContent ?? '');
      },
      { timeout: 5_000 },
    );

    // The host's own command works from inside the panel.
    await userEvent.click(
      within(handling).getByRole('button', { name: '标记为已复核' }),
    );
    await within(handling).findByText('SO-1003 已复核。');

    // Escape closes it, the host hears so, and focus is back on the row.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await expect(address(canvasElement)).toBe('');
    await waitFor(() =>
      expect(document.activeElement).toBe(
        canvasElement.querySelector('tr[data-row-key="SO-1003"]'),
      ),
    );
  },
};

export const LinkedOnPage: Story = {
  ...DisplayLinkedOnPage,
  play: async ({ canvasElement }) => {
    const panel = await detail();
    const key = within(panel).getByRole('heading', { name: 'SO-1003' });
    await waitFor(() => expect(document.activeElement).toBe(key));
    await within(panel).findByRole('region', { name: '处理' });
    // Closing asks the host, which lets go of the key; focus goes to the
    // record's row, since the page holds it.
    await userEvent.click(
      within(panel).getByRole('button', { name: zhCN['label.dialog.close'] }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await expect(address(canvasElement)).toBe('');
    const row = await rowOf(canvasElement, 'SO-1003');
    await waitFor(() => expect(document.activeElement).toBe(row));
  },
};

export const LinkedOffPage: Story = {
  ...DisplayLinkedOffPage,
  play: async ({ canvasElement }) => {
    const panel = await detail();
    await expect(
      within(panel).getByRole('heading', { name: 'SO-1002' }),
    ).toBeTruthy();
    // Not on the page, read on its own: a cancelled order, whole.
    await within(await fields(panel)).findByText('已取消');
    await expect(panel).toHaveAttribute('aria-busy', 'false');
    await within(panel).findByRole('region', { name: '处理' });
    await expect(
      canvasElement.querySelector('tr[data-row-key="SO-1002"]'),
    ).toBeNull();
    // Then a row: the host holds the key, and a press asks it for another.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const row = await rowOf(canvasElement, 'SO-1005');
    await userEvent.click(within(row).getAllByRole('cell').at(-2)!);
    const next = await detail();
    await expect(
      within(next).getByRole('heading', { name: 'SO-1005' }),
    ).toBeTruthy();
    await expect(address(canvasElement)).toBe('?id=SO-1005');
  },
};

export const LinkedReading: Story = {
  ...DisplayLinkedReading,
  play: async () => {
    const panel = await detail();
    // Saying it is reading: busy, a status for a screen reader, and the
    // shape of a section rather than an empty panel.
    await expect(panel).toHaveAttribute('aria-busy', 'true');
    await expect(within(panel).getByRole('status')).toHaveTextContent(
      zhCN['label.record.detail.loading'],
    );
    await expect(
      panel.querySelector('[data-slot="record-detail-reading"]'),
    ).not.toBeNull();
    await expect(
      within(panel).queryByRole('region', { name: '处理' }),
    ).toBeNull();
    // Then the record, and the host's sections with it.
    await within(await fields(panel)).findByText(
      '已取消',
      {},
      { timeout: 5_000 },
    );
    await expect(panel).toHaveAttribute('aria-busy', 'false');
    await within(panel).findByRole('region', { name: '处理' });
  },
};

export const LinkedNotThere: Story = {
  ...DisplayLinkedNotThere,
  play: async () => {
    const panel = await detail();
    await within(panel).findByText(zhCN['label.record.detail.missing']);
    await expect(
      within(panel).queryByRole('region', { name: '处理' }),
    ).toBeNull();
  },
};

export const LinkedForbidden: Story = {
  ...DisplayLinkedForbidden,
  play: async () => {
    const panel = await detail();
    const line = await within(panel).findByRole('alert');
    await expect(line).toHaveTextContent(zhCN['record.detail.forbidden']);
    await expect(
      within(panel).queryByRole('button', {
        name: zhCN['label.record.detail.retry'],
      }),
    ).toBeNull();
    await expect(
      within(panel).queryByRole('region', { name: '处理' }),
    ).toBeNull();
  },
};

export const LinkedFailed: Story = {
  ...DisplayLinkedFailed,
  play: async () => {
    const panel = await detail();
    const line = await within(panel).findByRole('alert');
    await expect(line).toHaveTextContent('连接被重置');
    await userEvent.click(
      within(line).getByRole('button', {
        name: zhCN['label.record.detail.retry'],
      }),
    );
    await within(await fields(panel)).findByText('已取消');
    await expect(within(panel).queryByRole('alert')).toBeNull();
    await within(panel).findByRole('region', { name: '处理' });
  },
};

/** Every record detail open, the one behind included (inert while covered). */
const panels = () => [
  ...document.querySelectorAll<HTMLElement>('[data-slot="record-detail"]'),
];

/**
 * The two widths the nested drawer is read at: a desk, where the layer
 * underneath shows its edge, and a phone, where both are the full width and
 * the header alone says which layer this is (`parameters.viewport`, read by
 * the Storybook Vitest plugin).
 */
const NESTED_VIEWPORTS = {
  viewport: {
    options: {
      desk: { name: '1280×900', styles: { width: '1280px', height: '900px' } },
      phone: { name: '375×812', styles: { width: '375px', height: '812px' } },
    },
  },
};

/** The outer detail and the embed's row of SO-1003 inside it. */
async function embeddedRow() {
  const outer = await detail();
  const embedded = await within(outer).findByRole(
    'region',
    { name: '同仓订单' },
    { timeout: 5_000 },
  );
  const row = await waitFor(
    () => {
      const found = embedded.querySelector<HTMLElement>(
        'tr[data-row-key="SO-1003"]',
      );
      if (!found) throw new Error('no row in the embed');
      return found;
    },
    { timeout: 5_000 },
  );
  return { outer, row };
}

/**
 * The second layer's header: the way back, named by the record underneath
 * and showing its key, then the section it was opened from — in place of a
 * close button, which in a stack would read as closing them all.
 */
async function wayBack(second: HTMLElement): Promise<HTMLElement> {
  const back = within(second).getByRole('button', { name: '返回 SO-1003' });
  await expect(back).toHaveTextContent('SO-1003');
  await expect(
    document.getElementById(second.getAttribute('aria-describedby')!),
  ).toHaveTextContent('同仓订单');
  await expect(
    within(second).queryByRole('button', { name: zhCN['label.dialog.close'] }),
  ).toBeNull();
  return back;
}

export const NestedEmbedDetail: Story = {
  ...DisplayNestedEmbedDetail,
  parameters: { ...DisplayNestedEmbedDetail.parameters, ...NESTED_VIEWPORTS },
  globals: { viewport: { value: 'desk' } },
  play: async () => {
    await expect(window.innerWidth).toBe(1280);
    const { outer, row } = await embeddedRow();
    // The keyboard's way in, as on any list: the row, then Enter.
    row.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(panels()).toHaveLength(2));
    const inner = panels().find(one => one !== outer)!;
    const key = within(inner).getByRole('heading', { name: 'SO-1003' });
    await waitFor(() => expect(document.activeElement).toBe(key));
    // Read-only: the embed's detail carries no row commands.
    await expect(
      inner.querySelector('[data-slot="record-detail-actions"]'),
    ).toBeNull();
    // The events read whole: each by its type, its payload key by key, the
    // stack trace as written, copyable.
    const events = await within(inner).findByRole(
      'region',
      { name: zhCN['label.record.detail.other'] },
      { timeout: 5_000 },
    );
    await within(events).findByText('出库失败');
    await within(events).findByText('WMS_TIMEOUT');
    const trace = await waitFor(() => {
      const found = [
        ...events.querySelectorAll<HTMLElement>('[data-slot="cell-long"]'),
      ].find(one => one.textContent?.includes('TimeoutException'));
      if (!found) throw new Error('no stack trace');
      return found;
    });
    await expect(within(trace).getByRole('button')).toBeTruthy();

    // Escape closes the innermost alone; focus is back on the embed's row.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(panels()).toHaveLength(1));
    await expect(panels()[0]).toBe(outer);
    await waitFor(() => expect(document.activeElement).toBe(row));

    // The second layer reads as one: its header says where it came from —
    // back to the record underneath, in that record's 「同仓订单」 — and the
    // way back, named by that record, closes it alone.
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(panels()).toHaveLength(2));
    const second = panels().find(one => one !== outer)!;
    const back = await wayBack(second);
    // Stacked, not swapped: the layer underneath keeps its edge in sight,
    // left of the one on top, and is dimmed while covered.
    await waitFor(() =>
      expect(second.getBoundingClientRect().left).toBeGreaterThan(
        outer.getBoundingClientRect().left + 16,
      ),
    );
    await expect(outer).toHaveAttribute('data-nested-dialog-open');
    await expect(getComputedStyle(outer, '::after').content).not.toBe('none');
    await userEvent.click(back);
    await waitFor(() => expect(panels()).toHaveLength(1));
    await expect(panels()[0]).toBe(outer);
    await waitFor(() => expect(document.activeElement).toBe(row));
    await expect(outer).not.toHaveAttribute('data-nested-dialog-open');

    // Open it again and leave both open, so axe reads the nested state.
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(panels()).toHaveLength(2));
    const again = panels().find(one => one !== outer)!;
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(again).getByRole('heading', { name: 'SO-1003' }),
      ),
    );
    await within(again).findByText('WMS_TIMEOUT');
  },
};

/**
 * 手机上（375 宽）两层都是整宽，第二层的头部说清它是第二层：「← SO-1003 ›
 * 同仓订单」，返回只关这一层，焦点回到嵌入里那一行。两层都开着留给 axe。
 */
export const NestedEmbedDetailOnAPhone: Story = {
  ...NestedEmbedDetail,
  globals: { viewport: { value: 'phone' } },
  play: async () => {
    await expect(window.innerWidth).toBe(375);
    const { outer, row } = await embeddedRow();
    row.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(panels()).toHaveLength(2));
    const second = panels().find(one => one !== outer)!;
    const back = await wayBack(second);
    // The whole width: no room to spare for the edge underneath.
    await waitFor(() =>
      expect(second.getBoundingClientRect().width).toBe(window.innerWidth),
    );
    // The way back fits the header beside the section's name.
    await expect(back.getBoundingClientRect().right).toBeLessThanOrEqual(
      window.innerWidth,
    );
    await userEvent.click(back);
    await waitFor(() => expect(panels()).toHaveLength(1));
    await waitFor(() => expect(document.activeElement).toBe(row));

    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(panels()).toHaveLength(2));
    const again = panels().find(one => one !== outer)!;
    await within(again).findByText('WMS_TIMEOUT', undefined, {
      timeout: 5_000,
    });
  },
};
