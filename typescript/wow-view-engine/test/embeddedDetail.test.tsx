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

/**
 * A record's detail from an embedded record view (G20): opened from a row in
 * the interactive tier where the host switched it on, read-only (D36) — no
 * row command in its header, nothing written — and, inside the drawer of a
 * workbench's detail, a sheet nested in that one: Escape closes the
 * innermost alone, and focus goes back to the row it was opened from.
 */

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { FilterPagedQuery } from '@ahoo-wang/wow-client';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RecordKey,
} from '../src/index.js';
import {
  DataWorkbench,
  EmbeddedView,
  type EmbedInteraction,
  type RecordDetailOptions,
} from '../src/ui/index.js';
import { mine, ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

/** Every write the `ViewStore` port has. */
const WRITES = [
  'create',
  'save',
  'rename',
  'delete',
  'setPreferences',
] as const;

const TRACE = 'java.lang.IllegalStateException: boom\n\tat Handler.on(1)';

/** The page's rows: no payload, which is not in any column. */
const PAGE: RecordData[] = [
  { id: 'o-1', warehouse: 'CN', amount: 10, status: 'PENDING' },
  { id: 'o-2', warehouse: 'CN', amount: 20, status: 'SHIPPED' },
];

/** A record read whole carries its payload: a structure, a stack inside. */
function whole(key: unknown): RecordData | null {
  const row = PAGE.find(one => one.id === key);
  return row
    ? { ...row, payload: { error: { code: 'E-42', stackTrace: TRACE } } }
    : null;
}

function keyOf(query: FilterPagedQuery): unknown {
  return JSON.stringify(query.filter).match(/"value":"([^"]+)"/)?.[1];
}

function setUp() {
  const store = new MemoryViewStore({ instances: [mine] });
  const writes = WRITES.map(name => vi.spyOn(store, name));
  const engine = new ViewEngine({
    definitions: [
      ordersDefinition({
        fields: [
          ...ordersDefinition().fields,
          { name: 'payload', label: 'Payload', kind: 'string' },
        ],
      }),
    ],
    store,
    resolveSource: () =>
      testSource({
        paged: vi.fn(async (query: FilterPagedQuery) => {
          if (query.pagination?.size !== 1)
            return { total: 2, list: [...PAGE] };
          const found = whole(keyOf(query));
          return { total: found ? 1 : 0, list: found ? [found] : [] };
        }),
      }),
  });
  return { engine, writes };
}

/** The detail panels open, the one behind included (it is inert then). */
const panels = () => [
  ...document.querySelectorAll<HTMLElement>('[data-slot="record-detail"]'),
];

/** The data row of `key` inside `scope`, once the rows are drawn. */
async function rowIn(scope: HTMLElement, key: string): Promise<HTMLElement> {
  return waitFor(() => {
    const row = scope.querySelector<HTMLElement>(`tr[data-row-key="${key}"]`);
    if (!row) throw new Error(`no row ${key}`);
    return row;
  });
}

function Embed({
  engine,
  interaction = 'interactive',
  detail,
}: {
  engine: ViewEngine;
  interaction?: EmbedInteraction;
  detail?: boolean | RecordDetailOptions;
}) {
  return (
    <div data-testid="page">
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        interaction={interaction}
        rowActions={row => (
          <button type="button">Refund {String(row.key)}</button>
        )}
        {...(detail === undefined ? {} : { detail })}
      />
    </div>
  );
}

describe('a record detail from an embedded view (G20)', () => {
  it('opens no row in the static tier, nor where the host left it off', async () => {
    const { engine } = setUp();
    const { unmount } = render(
      <Embed engine={engine} interaction="static" detail />,
    );
    const row = (await screen.findByText('o-1')).closest('tr')!;
    // Rows are read, not pressed: nothing opens, nothing is offered.
    expect(row.hasAttribute('data-openable')).toBe(false);
    await userEvent.click(within(row).getAllByRole('cell')[1]!);
    expect(screen.queryByRole('dialog')).toBeNull();
    unmount();

    render(<Embed engine={engine} />);
    const plain = (await screen.findByText('o-1')).closest('tr')!;
    expect(plain.hasAttribute('data-openable')).toBe(false);
  });

  it('reads a record whole, its payload included, and offers no command on it', async () => {
    const { engine, writes } = setUp();
    render(<Embed engine={engine} detail />);
    const row = await rowIn(screen.getByTestId('page'), 'o-1');
    expect(row.hasAttribute('data-openable')).toBe(true);
    // The row keeps the host's own command; the detail does not carry it.
    expect(
      within(row).getByRole('button', { name: 'Refund o-1' }),
    ).toBeTruthy();

    await userEvent.click(within(row).getAllByRole('cell')[1]!);
    const panel = await screen.findByRole('dialog');
    const heading = within(panel).getByRole('heading', { name: 'o-1' });
    await waitFor(() => expect(document.activeElement).toBe(heading));
    // The payload, which no column holds, read key by key; the stack inside
    // it whole, as written, copyable.
    await within(panel).findByText('E-42');
    const trace = await waitFor(() => {
      const found = panel.querySelector('[data-slot="cell-long"]');
      expect(found?.textContent).toContain('IllegalStateException');
      return found!;
    });
    expect(within(trace as HTMLElement).getByRole('button')).toBeTruthy();
    // Read-only: no row command in the header, none anywhere in the panel.
    expect(
      panel.querySelector('[data-slot="record-detail-actions"]'),
    ).toBeNull();
    expect(within(panel).queryByRole('button', { name: /Refund/ })).toBeNull();

    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(row));
    for (const write of writes) expect(write).not.toHaveBeenCalled();
  });

  it('lets the host hold the open record and add its sections', async () => {
    const { engine } = setUp();
    const told = vi.fn<(key: RecordKey | null) => void>();
    function Host() {
      const [open, setOpen] = useState<RecordKey | null>('o-2');
      return (
        <Embed
          engine={engine}
          detail={{
            open,
            onOpenChange: key => {
              told(key);
              setOpen(key);
            },
            sections: ({ row, complete }) => [
              {
                id: 'history',
                title: 'History',
                render: () => (
                  <p>
                    History of {String(row.key)}
                    {complete ? ' (whole)' : ''}
                  </p>
                ),
              },
            ],
          }}
        />
      );
    }
    render(<Host />);
    const panel = await screen.findByRole('dialog');
    expect(within(panel).getByRole('heading', { name: 'o-2' })).toBeTruthy();
    const history = await within(panel).findByRole('region', {
      name: 'History',
    });
    await within(history).findByText('History of o-2 (whole)');

    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(told).toHaveBeenLastCalledWith(null);
  });

  it('opens nested in a workbench detail: Escape closes the innermost alone', async () => {
    const { engine, writes } = setUp();
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        record={{
          detail: {
            sections: () => [
              {
                id: 'same-warehouse',
                title: 'Same warehouse',
                render: () => (
                  <EmbeddedView
                    engine={engine}
                    instanceId="orders-1"
                    interaction="interactive"
                    headingLevel={4}
                    detail
                  />
                ),
              },
            ],
          },
        }}
      />,
    );
    const outerRow = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        'tr[data-row-key="o-1"]',
      );
      if (!found) throw new Error('no row');
      return found;
    });
    await userEvent.click(within(outerRow).getAllByRole('cell')[1]!);
    const outer = await screen.findByRole('dialog');
    const embedded = await within(outer).findByRole('region', {
      name: 'Same warehouse',
    });
    const innerRow = await rowIn(embedded, 'o-2');

    // Enter on the embed's row opens a second sheet over the first.
    innerRow.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(panels()).toHaveLength(2));
    const inner = panels().find(one => one !== outer) as HTMLElement;
    const innerKey = within(inner).getByRole('heading', { name: 'o-2' });
    await waitFor(() => expect(document.activeElement).toBe(innerKey));
    await within(inner).findByText('E-42');

    // A press inside the inner sheet leaves both open.
    await userEvent.click(innerKey);
    expect(panels()).toHaveLength(2);

    // Escape: the inner one closes, the outer stays, and focus is back on
    // the embed's row inside the outer one.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(panels()).toHaveLength(1));
    expect(panels()[0]).toBe(outer);
    await waitFor(() => expect(document.activeElement).toBe(innerRow));

    // Escape again: the outer one closes, focus back on the workbench row.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(outerRow));
    for (const write of writes) expect(write).not.toHaveBeenCalled();
  });
});
