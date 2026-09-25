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
