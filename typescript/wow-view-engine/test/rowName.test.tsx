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
 * A row named by the definition's `record.rowName` (UI walk UI-30): its
 * controls say the visible label and which record, by the name a reader
 * knows it by rather than a key nobody reads aloud, and so do an action's
 * question and its outcome — even once the refresh took the row away.
 */

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  actions,
  type RecordAction,
} from '../src/index.js';
import { rowNameOf } from '../src/record/path.js';
import { DataWorkbench } from '../src/ui/index.js';
import {
  mine,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** Orders keyed by a long id, known by their number; the second has none. */
const ROWS = [
  { id: 'tenant-0:orders:o-1', number: 'SO-1001', status: 'PENDING' },
  { id: 'tenant-0:orders:o-2', number: '  ', status: 'PENDING' },
];

function ship(run: RecordAction['run']): RecordAction {
  return { id: 'ship', label: 'Ship', primary: true, run };
}

function cancel(run: RecordAction['run']): RecordAction {
  return { id: 'void', label: 'Void', confirm: { title: 'Void it?' }, run };
}

async function open(declared: readonly RecordAction[], rowName?: string) {
  const definition = ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      { name: 'number', label: 'Number', kind: 'string' },
    ],
    record: {
      rowKey: 'id',
      paging: 'paged',
      layouts: ['table', 'card'],
      ...(rowName ? { rowName } : {}),
    },
  });
  const source = testSource({
    paged: vi.fn(() =>
      Promise.resolve({ total: 2, exact: true, list: [...ROWS] }),
    ),
  });
  const engine = new ViewEngine({
    resources: resourcesOf([definition], () => source),
    store: new MemoryViewStore({
      instances: [{ ...mine, config: recordConfig() }],
    }),
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      record={{ actions: actions(declared) }}
    />,
  );
  await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  return source;
}

const status = () =>
  document.querySelector<HTMLElement>('[data-slot="bulk-status"]');

describe('a row the definition names', () => {
  it('names its checkbox, its own button and its menu by the label and the name', async () => {
    await open([ship(vi.fn()), cancel(vi.fn())], 'number');
    expect(
      screen.getByRole('checkbox', { name: 'Select SO-1001' }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Ship SO-1001' })).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Actions for SO-1001' }),
    ).toBeTruthy();
    // A row whose name is blank is named by its key.
    expect(
      screen.getByRole('checkbox', { name: 'Select tenant-0:orders:o-2' }),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Ship tenant-0:orders:o-2' }),
    ).toBeTruthy();
  });

  it('says the name in the question and the outcome, after the refresh took the row away', async () => {
    const run = vi.fn(() => Promise.resolve());
    const source = await open([ship(vi.fn()), cancel(run)], 'number');
    await userEvent.click(
      screen.getByRole('button', { name: 'Actions for SO-1001' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Void' }),
    );
    const dialog = await screen.findByRole('dialog', { name: 'Void it?' });
    expect(within(dialog).getByText('Record SO-1001')).toBeTruthy();
    // The refresh after the command finds the order gone.
    vi.mocked(source.paged).mockResolvedValue({
      total: 1,
      exact: true,
      list: [ROWS[1]],
    });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Void' }));
    await waitFor(() =>
      expect(status()?.textContent).toContain('SO-1001 done'),
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2));
    expect(status()?.textContent).toContain('SO-1001 done');
  });

  it('names the rows of a definition that gives no name by their key, its own button by the label alone', async () => {
    await open([ship(vi.fn()), cancel(vi.fn())]);
    expect(
      screen.getByRole('checkbox', { name: 'Select tenant-0:orders:o-1' }),
    ).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Ship' })).toHaveLength(2);
    expect(
      screen.getByRole('button', { name: 'Actions for tenant-0:orders:o-1' }),
    ).toBeTruthy();
  });
});

describe('rowNameOf', () => {
  it('reads a string or a number with something in it, else the key', () => {
    const row = (data: Record<string, unknown>) => ({ key: 'k', data });
    expect(rowNameOf(row({ n: ' SO-1 ' }), 'n')).toBe('SO-1');
    expect(rowNameOf(row({ n: 42 }), 'n')).toBe('42');
    expect(rowNameOf(row({ n: Number.NaN }), 'n')).toBe('k');
    expect(rowNameOf(row({ n: '' }), 'n')).toBe('k');
    expect(rowNameOf(row({ n: { a: 1 } }), 'n')).toBe('k');
    expect(rowNameOf(row({ n: 'SO-1' }), undefined)).toBe('k');
  });
});
