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
import {
  HashRoutedRecordWorkbench,
  PlainRecordWorkbench,
} from '../examples/PlainRecordWorkbench.js';
import {
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RecordViewConfig,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

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
        instanceId="system:orders:all"
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
    // The row key ends the query's sort, so tied amounts page stably.
    await waitFor(() =>
      expect(last().sort).toEqual([
        { field: 'amount', direction: 'ASC' },
        { field: 'id', direction: 'ASC' },
      ]),
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
        instanceId={summary.id}
      />,
    );
    await waitFor(() => expect(value('status value')).toBe('PENDING'));
    await waitFor(() => expect(cells()).toHaveLength(3));
    expect(last().sort).toEqual([
      { field: 'amount', direction: 'ASC' },
      { field: 'id', direction: 'ASC' },
    ]);
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

/**
 * Closed loop two: the address bar.
 *
 * A view somebody opened is a link they can send, and a link somebody opens
 * is a view — so the choice lives in one place, the route, and the workbench
 * is the two directions in and out of it. This drives
 * `HashRoutedRecordWorkbench`, which is the reference for a host that routes.
 */
describe('closed loop two', () => {
  const mine: ViewInstance = {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'Mine',
    scope: 'personal',
    revision: '1',
    config: recordConfig(),
  };
  const theirs: ViewInstance = { ...mine, id: 'orders-2', title: 'Theirs' };

  afterEach(() => {
    window.location.hash = '';
  });

  /** What the nav marks as the view on screen. */
  function current(): string | null {
    return (
      screen
        .getAllByRole('button')
        .find(button => button.ariaCurrent === 'true')?.textContent ?? null
    );
  }

  /** A route change from outside the page: a pasted link, the back button. */
  function navigate(hash: string): void {
    window.location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  }

  it('opens what the link named, and puts the next choice back in the link', async () => {
    window.location.hash = '#/orders/orders-2';
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine, theirs] }),
      resolveSource: () => testSource(),
    });
    render(<HashRoutedRecordWorkbench engine={engine} definitionId="orders" />);

    // 1. The link is what opened, not the user's default.
    await waitFor(() => expect(current()).toBe('Theirs'));

    // 2. The user's own switch is written back out, so the link they copy
    // now is the view they are looking at.
    fireEvent.click(screen.getByRole('button', { name: 'Mine' }));
    await waitFor(() => expect(window.location.hash).toBe('#/orders/orders-1'));
    expect(current()).toBe('Mine');

    // 3. And the back button is a route change like any other.
    navigate('#/orders/orders-2');
    await waitFor(() => expect(current()).toBe('Theirs'));

    // 4. A route that names no view is the effective default — which is what
    // `null` means on the way in and on the way out alike.
    navigate('#/orders');
    await waitFor(() => expect(current()).toBe('All orders'));
  });
});
