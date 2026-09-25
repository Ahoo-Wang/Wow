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

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PagedList } from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RuntimeLimits,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { mine, setup } from './fixtures/ui.js';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/**
 * The whole chain of an export, from the window to the blob: what it says it
 * will hand over, the reading each cell has, the serializer, the file name
 * and what the browser is finally given.
 *
 * The window's own controls against a stubbed controller are
 * `test/resultToolbar.test.tsx`, the scopes and the outcome are
 * `test/useRecordExport.test.tsx` and the fetching is
 * `test/exportRows.test.ts`; this is the one place all of them are the same
 * export (D14).
 */
describe('DataWorkbench export', () => {
  /** The browser's half, which jsdom has none of. */
  function stubObjectUrls() {
    const createObjectURL = vi.fn(() => 'blob:orders');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    return { createObjectURL, revokeObjectURL };
  }

  /** One workbench over this source, with the ceiling a test needs. */
  function engineFor(source: ViewSource, limits?: Partial<RuntimeLimits>) {
    return new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => source,
      ...(limits ? { limits: { ...DEFAULT_RUNTIME_LIMITS, ...limits } } : {}),
    });
  }

  /**
   * A source that answers the view's own first query at once and holds every
   * request after it, which are the export's — a long export, stopped where
   * a test wants it.
   */
  function gatedSource(): { source: ViewSource; held: (() => void)[] } {
    const held: (() => void)[] = [];
    let opened = false;
    const answer = { total: 40, list: orders(2) };
    return {
      held,
      source: testSource({
        paged: vi.fn(() => {
          if (!opened) {
            opened = true;
            return Promise.resolve(answer);
          }
          return new Promise<PagedList<RecordData>>(resolve =>
            held.push(() => resolve(answer)),
          );
        }),
      }),
    };
  }

  function orders(count: number): RecordData[] {
    return Array.from({ length: count }, (_row, index) => ({
      id: `o-${index + 1}`,
      warehouse: 'CN',
      amount: (index + 1) * 10,
      status: 'PENDING',
    }));
  }

  /** The window, opened from the one button at the end of the toolbar. */
  async function openWindow() {
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.click(screen.getByRole('button', { name: 'Export' }));
    return await screen.findByRole('dialog');
  }

  it('says what the file will hold, then hands it over under that name', async () => {
    const { createObjectURL, revokeObjectURL } = stubObjectUrls();
    const onExported = vi.fn();
    const { engine } = setup(testSource());
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        record={{ onExported: onExported }}
      />,
    );

    const dialog = await openWindow();
    // What is about to happen, before it happens: how many rows, under what
    // conditions, which columns and what the file will be called.
    expect(dialog.textContent).toContain('2 records');
    expect(dialog.textContent).toContain('Conditions: All records');
    expect(dialog.textContent).toContain('2 columns: Order, Amount');
    const named = /File: (Mine-\d{4}-\d{2}-\d{2}\.csv)/.exec(
      dialog.textContent ?? '',
    );
    expect(named).not.toBeNull();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));

    await waitFor(() => expect(onExported).toHaveBeenCalledTimes(1));
    const file = onExported.mock.calls[0][0] as {
      name: string;
      text: string;
      rows: number;
      scope: string;
    };
    expect(file.scope).toBe('all');
    expect(file.rows).toBe(2);
    // The name the window promised is the name the browser was given.
    expect(file.name).toBe(named?.[1]);
    // The header is the columns the table draws, by their labels, and every
    // value is the reading the cell above it had.
    expect(file.text).toBe('﻿Order,Amount\r\no-1,10\r\no-2,20\r\n');
    // And it really went to the browser: one blob, made as a CSV, and its
    // URL released again rather than held for the session.
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect((createObjectURL.mock.calls[0] as unknown as [Blob])[0].type).toBe(
      'text/csv;charset=utf-8',
    );
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:orders');

    // The same window reports the outcome; nothing is said in the strip
    // above the rows any more.
    await within(dialog).findByText('2 records exported');
    expect(document.querySelector('[data-slot="status-strip"]')).toBeNull();
  });

  it('keeps the promised name when the day turns over under the window', async () => {
    // Only the clock, not the timers: the queries and `waitFor` below run on
    // the real ones, and what this is about is the date the name carries.
    vi.useFakeTimers({ toFake: ['Date'] });
    // Local times rather than UTC, so the day turns over here whatever zone
    // the machine running this is in — `isoDay` reads the same zone.
    vi.setSystemTime(new Date('2026-09-21T23:59:00'));
    const { createObjectURL } = stubObjectUrls();
    const onExported = vi.fn();
    const { engine } = setup(testSource());
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        record={{ onExported: onExported }}
      />,
    );

    const dialog = await openWindow();
    expect(dialog.textContent).toContain('File: Mine-2026-09-21.csv');

    // A minute and a half of reading what the file will hold, and it is
    // tomorrow. The name was settled when the window opened, so it is still
    // the one on screen — and still the one the browser is handed.
    vi.setSystemTime(new Date('2026-09-22T00:00:30'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));

    await waitFor(() => expect(onExported).toHaveBeenCalledTimes(1));
    const file = onExported.mock.calls[0][0] as { name: string };
    expect(file.name).toBe('Mine-2026-09-21.csv');
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    // The outcome names the same file the choice did — one journey, one
    // shell, one promise (D14).
    await within(dialog).findByText('2 records exported');
    expect(dialog.textContent).toContain('File: Mine-2026-09-21.csv');

    // The next opening is a new promise, and it is today's.
    fireEvent.click(
      dialog.querySelector('[data-slot="export-close"]') as HTMLElement,
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'Export' }));
    const reopened = await screen.findByRole('dialog');
    expect(reopened.textContent).toContain('File: Mine-2026-09-22.csv');
  });

  it('exports the picked rows when that is what was picked', async () => {
    stubObjectUrls();
    const onExported = vi.fn();
    const { engine } = setup(testSource());
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        record={{ onExported: onExported }}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.click(screen.getByLabelText('Select o-2'));

    const dialog = await openWindow();
    // The picked rows are the default once there are any (D4).
    expect(
      within(dialog)
        .getByRole('radio', { name: 'Selected (1)' })
        .getAttribute('aria-checked'),
    ).toBe('true');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));

    await waitFor(() => expect(onExported).toHaveBeenCalledTimes(1));
    const file = onExported.mock.calls[0][0] as { text: string; scope: string };
    expect(file.scope).toBe('selected');
    expect(file.text).toBe('﻿Order,Amount\r\no-2,20\r\n');
  });

  it('neutralizes a formula in the file, unless the host turned that off', async () => {
    stubObjectUrls();
    // A value somebody typed, and a negative amount: the one a spreadsheet
    // would evaluate, the other a number it only reads.
    const rows = [{ id: '=HYPERLINK("http://x")', amount: -5 }];
    const exported = async (limits?: Partial<RuntimeLimits>) => {
      const onExported = vi.fn();
      const source = testSource({
        paged: vi.fn(() => Promise.resolve({ total: 1, list: rows })),
      });
      render(
        <DataWorkbench
          engine={engineFor(source, limits)}
          definitionId="orders"
          instanceId="orders-1"
          record={{ onExported }}
        />,
      );
      await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2));
      fireEvent.click(screen.getByRole('button', { name: 'Export' }));
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));
      await waitFor(() => expect(onExported).toHaveBeenCalledTimes(1));
      cleanup();
      return (onExported.mock.calls[0][0] as { text: string }).text;
    };

    expect(await exported()).toBe(
      '﻿Order,Amount\r\n"\'=HYPERLINK(""http://x"")",-5\r\n',
    );
    expect(await exported({ exportNeutralizeFormulas: false })).toBe(
      '﻿Order,Amount\r\n"=HYPERLINK(""http://x"")",-5\r\n',
    );
  });

  it('warns about the ceiling first, and says afterwards what it left out', async () => {
    stubObjectUrls();
    const source = testSource({
      paged: vi.fn(() => Promise.resolve({ total: 10, list: orders(5) })),
    });
    render(
      <DataWorkbench
        engine={engineFor(source, { exportMax: 3 })}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6));
    fireEvent.click(screen.getByRole('button', { name: 'Export' }));
    const dialog = await screen.findByRole('dialog');

    expect(dialog.textContent).toContain(
      'That is more than the 3 one export carries; the file will hold the first 3.',
    );

    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));

    await within(dialog).findByText('3 records exported');
    expect(dialog.textContent).toContain(
      'The file holds the first 3 of the 10 records that match.',
    );
  });

  it('shows the pages coming in, and Escape stops them', async () => {
    stubObjectUrls();
    const onExported = vi.fn();
    const { source, held } = gatedSource();
    render(
      <DataWorkbench
        engine={engineFor(source)}
        definitionId="orders"
        instanceId="orders-1"
        record={{ onExported: onExported }}
      />,
    );
    const dialog = await openWindow();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));

    const bar = await within(dialog).findByRole('progressbar', {
      name: 'Exporting',
    });
    expect(bar.getAttribute('aria-valuemax')).toBe('40');
    expect(within(dialog).getByRole('status').textContent).toBe(
      '0 of 40 fetched',
    );

    // Escape is the user's own answer while the pages come in: it stops the
    // run rather than being refused, and the run says nothing about it.
    fireEvent.keyDown(dialog, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await act(async () => {
      held[0]?.();
    });
    expect(onExported).not.toHaveBeenCalled();
    expect(document.querySelector('[data-slot="status-strip"]')).toBeNull();
  });

  it('reports a failure in the window, and offers it again', async () => {
    stubObjectUrls();
    const onExported = vi.fn();
    const paged = vi
      .fn()
      .mockResolvedValueOnce({ total: 2, list: orders(2) })
      .mockRejectedValueOnce(new Error('gateway down'))
      .mockResolvedValue({ total: 2, list: orders(2) });
    render(
      <DataWorkbench
        engine={engineFor(testSource({ paged }))}
        definitionId="orders"
        instanceId="orders-1"
        record={{ onExported: onExported }}
      />,
    );
    const dialog = await openWindow();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));

    await within(dialog).findByText('The export failed. gateway down');
    expect(onExported).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(onExported).toHaveBeenCalledTimes(1));
    await within(dialog).findByText('2 records exported');
  });

  it('asks again the next time it is opened', async () => {
    stubObjectUrls();
    const { engine } = setup(testSource());
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    const dialog = await openWindow();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));
    await within(dialog).findByText('2 records exported');

    fireEvent.click(
      dialog.querySelector('[data-slot="export-close"]') as HTMLElement,
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'Export' }));

    // A window that reopened on the last outcome would be reporting an
    // export the user has already read.
    const reopened = await screen.findByRole('dialog');
    expect(reopened.textContent).toContain('The result leaves as a CSV file.');
    expect(reopened.textContent).not.toContain('records exported');
  });
});
