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

import type { FilterPagedQuery } from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PlainRecordWorkbench } from '../examples/PlainRecordWorkbench.js';
import {
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RecordViewConfig,
  type ViewSource,
} from '../src/index.js';
import { ordersDefinition } from './fixtures.js';

afterEach(cleanup);

const SHIPMENTS: RecordData[] = [
  { id: 'o-1', warehouse: 'CN', amount: 30, status: 'PENDING' },
  { id: 'o-2', warehouse: 'CN', amount: 10, status: 'PENDING' },
  { id: 'o-3', warehouse: 'JP', amount: 20, status: 'SHIPPED' },
];

function setup() {
  const store = new MemoryViewStore();
  const queries: FilterPagedQuery[] = [];
  let rows = SHIPMENTS;

  const source: ViewSource = {
    paged: vi.fn((query: FilterPagedQuery) => {
      queries.push(query);
      return Promise.resolve({ total: rows.length, list: rows });
    }),
    cursor: vi.fn(() => Promise.resolve({ nextCursor: null, list: rows })),
    aggregate: vi.fn(() => Promise.resolve([])),
  };

  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => source,
  });

  return {
    engine,
    store,
    queries,
    last: () => queries[queries.length - 1],
    setRows: (next: RecordData[]) => {
      rows = next;
    },
  };
}

/** Plain DOM assertions; this package registers no jest-dom matchers. */
function text(testId: string): string {
  return screen.getByTestId(testId).textContent ?? '';
}

function value(label: string): string {
  return (screen.getByLabelText(label) as HTMLInputElement).value;
}

function cells(): string[] {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map(row => row.textContent ?? '');
}

/**
 * Closed loop one, as `docs/design/README.md` defines it and step 5 exits on: the
 * user filters, adjusts columns and sorting, saves a personal view, reopens it
 * and gets the configuration back without the browsing state, then refreshes
 * and sees new data.
 *
 * It drives `examples/PlainRecordWorkbench.tsx`, so what passes here is the
 * contract the `/ui` step will render differently, not a bespoke harness.
 */
describe('closed loop one', () => {
  it('filters, adjusts, saves, reopens and refreshes', async () => {
    const { engine, store, last, setRows } = setup();

    const view = render(
      <PlainRecordWorkbench
        engine={engine}
        definitionId="orders"
        initialInstanceId="system:orders:all"
      />,
    );
    await waitFor(() => expect(cells()).toHaveLength(3));

    // 1. Filter down to what is waiting to ship. Every assertion after an
    // interaction is a `findBy`: React commits when it commits, and a test
    // that assumes otherwise flakes rather than fails.
    fireEvent.click(screen.getByRole('button', { name: 'Filter by Status' }));
    fireEvent.change(await screen.findByLabelText('status value'), {
      target: { value: 'PENDING' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Apply filter' }));
    await waitFor(() =>
      expect(last().filter).toMatchObject({
        field: 'status',
        value: 'PENDING',
      }),
    );

    // 2. Sort by amount and drop a column.
    fireEvent.click(screen.getByRole('button', { name: 'Amount' }));
    await waitFor(() =>
      expect(last().sort).toEqual([{ field: 'amount', direction: 'ASC' }]),
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Toggle column Amount' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(2),
    );

    // 3. Browsing state exists but is not part of the view.
    fireEvent.click(await screen.findByLabelText('select o-1'));
    await waitFor(() => expect(text('selection')).toBe('o-1'));

    // 4. Save it as a personal view.
    fireEvent.change(await screen.findByLabelText('view title'), {
      target: { value: 'Pending shipments' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Save as personal view' }),
    );
    await waitFor(() => expect(text('dirty')).toBe('clean'));

    const [summary] = (await store.list('orders')).filter(
      item => item.scope === 'personal',
    );
    expect(summary).toMatchObject({
      title: 'Pending shipments',
      scope: 'personal',
    });
    const saved = await store.get(summary.id);
    const config = saved.config as RecordViewConfig;
    expect(config.sort).toEqual([{ field: 'amount', direction: 'ASC' }]);
    // The column that was switched off is still in the list, switched off:
    // its entry is its place in the order, so reopening the view and
    // switching it on again puts it back where it was (D17-8).
    expect(config.table.columns).toEqual([
      { field: 'id' },
      { field: 'amount', hidden: true },
    ]);

    // 5. Reopening restores the configuration, and nothing else.
    view.unmount();
    render(
      <PlainRecordWorkbench
        engine={engine}
        definitionId="orders"
        initialInstanceId={summary.id}
      />,
    );
    await waitFor(() => expect(value('status value')).toBe('PENDING'));
    await waitFor(() => expect(cells()).toHaveLength(3));
    expect(last().sort).toEqual([{ field: 'amount', direction: 'ASC' }]);
    expect(text('selection')).toBe('');
    expect(text('paging')).toBe('page 1 of 3');
    expect(text('dirty')).toBe('clean');

    // 6. New data arrives on refresh, with the same configuration.
    setRows([{ id: 'o-9', warehouse: 'CN', amount: 5, status: 'PENDING' }]);
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(cells()).toHaveLength(1));
    expect(cells()[0]).toContain('o-9');
  });
});
