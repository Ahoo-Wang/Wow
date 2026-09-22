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
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import type { FilterPagedQuery } from '@ahoo-wang/fetcher-wow';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { detailSections } from '../src/record/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { MemoryViewStore, ViewEngine, type RecordData } from '../src/index.js';
import { ROWS, mine, ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

const TRACE =
  'java.lang.IllegalStateException: stock refused\n\tat Warehouse.reserve(Warehouse.java:42)';

/** The orders fixture, where the whole record holds what the page does not. */
function wholeSource(
  whole: RecordData | null | Error = { ...ROWS[0], note: TRACE },
) {
  return testSource({
    paged: vi.fn((query: FilterPagedQuery) => {
      // A detail asks for one record by key, with no projection.
      if (query.pagination?.size === 1)
        return whole instanceof Error
          ? Promise.reject(whole)
          : Promise.resolve({
              total: whole ? 1 : 0,
              list: whole ? [whole] : [],
            });
      return Promise.resolve({ total: 2, list: [...ROWS] });
    }),
  });
}

function withNote() {
  return ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      { name: 'note', label: 'Note', kind: 'string', cell: 'text' },
    ],
    fieldGroups: [
      { id: 'order', label: 'The order', fields: ['id', 'status'] },
      { id: 'money', label: 'Money', fields: ['amount'] },
    ],
  });
}

async function open(source = wholeSource()) {
  const engine = new ViewEngine({
    definitions: [withNote()],
    store: new MemoryViewStore({ instances: [mine] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
    />,
  );
  await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  return { engine, source };
}

describe('detailSections', () => {
  it('lists the declared fields under their groups, the rest after, each once', () => {
    const sections = detailSections({
      fields: [
        ...withNote().fields,
        { name: 'keyword', label: 'Search', kind: 'search' },
      ],
      // A definition admission refuses — a field in two groups — still lays
      // out once each, under the first.
      fieldGroups: [
        { id: 'order', label: 'The order', fields: ['id', 'status'] },
        { id: 'money', label: 'Money', fields: ['amount', 'id'] },
      ],
    });
    expect(
      sections.map(section => [
        section.label,
        section.fields.map(field => field.name),
      ]),
    ).toEqual([
      ['The order', ['id', 'status']],
      // `id` is the first group's already; a field is listed once.
      ['Money', ['amount']],
      // What no group gathers, in declaration order — and never the search.
      [null, ['warehouse', 'note']],
    ]);
  });
});

/** The table's data rows, header row left out. */
function dataRows(): HTMLElement[] {
  return screen.getAllByRole('row').slice(1);
}

describe('a record read whole', () => {
  it('opens from a press on the row, with what the page had, then the whole record', async () => {
    const { source } = await open();
    // The first data row, pressed on a cell that holds nothing but its value.
    fireEvent.click(
      within(screen.getAllByRole('row')[1]).getAllByRole('cell')[2],
    );

    const panel = await screen.findByRole('dialog');
    expect(within(panel).getByRole('heading', { name: 'o-1' })).toBeTruthy();
    // The page's own fields at once…
    expect(within(panel).getByText('Warehouse')).toBeTruthy();
    // …and what only the whole record holds once it comes, read whole: kept
    // as written, in a block of its own, copyable.
    const trace = await within(panel).findByText(/stock refused/);
    expect(trace.closest('[data-slot="long-text"]')).not.toBeNull();
    expect(trace.textContent).toContain('\tat Warehouse.reserve');
    expect(within(panel).getByRole('button', { name: /Copy/ })).toBeTruthy();
    // One record, by its key, with nothing else of the page's query.
    const asked = vi
      .mocked(source.paged)
      .mock.calls.map(([query]) => query as FilterPagedQuery)
      .find(query => query.pagination?.size === 1)!;
    expect(asked.filter).toMatchObject({ field: 'id', value: 'o-1' });
    expect(asked.sort ?? []).toEqual([]);
    expect('projection' in asked).toBe(false);
    // The groups the definition declares head the sections.
    expect(
      within(panel)
        .getAllByRole('heading', { level: 3 })
        .map(heading => heading.textContent),
    ).toEqual(['The order', 'Money', 'Other']);
  });

  it('reaches the rows as one Tab stop, moves with the arrows, and opens with Enter', async () => {
    await open();
    const [first, second] = dataRows();
    // One stop for the page of rows, on the first until the keyboard moves it.
    expect(first!.getAttribute('tabindex')).toBe('0');
    expect(second!.getAttribute('tabindex')).toBe('-1');
    // What the keys do is said once, and every row points at it.
    const hint = document.getElementById(
      first!.getAttribute('aria-describedby')!,
    );
    expect(hint?.textContent).toMatch(/Enter or Space opens the record/);
    first!.focus();
    fireEvent.keyDown(first!, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(second);
    expect(second!.getAttribute('tabindex')).toBe('0');
    expect(first!.getAttribute('tabindex')).toBe('-1');
    fireEvent.keyDown(second!, { key: 'Enter' });
    const panel = await screen.findByRole('dialog');
    expect(within(panel).getByRole('heading', { name: 'o-2' })).toBeTruthy();
  });

  it('leaves the keys on something inside the row to that thing', async () => {
    await open();
    const box = screen.getByRole('checkbox', { name: 'Select o-1' });
    fireEvent.keyDown(box, { key: ' ' });
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('does not open from a press on something in the row that does something else', async () => {
    await open();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select o-1' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('says why the whole record could not be read, and keeps what the page had', async () => {
    await open(
      wholeSource(
        Object.assign(new Error('Request failed with status code 400'), {
          exchange: {
            response: { status: 400 },
            extractResult: () =>
              Promise.resolve({
                errorCode: 'Forbidden',
                errorMsg: 'No access.',
              }),
          },
        }),
      ),
    );
    fireEvent.keyDown(dataRows()[0]!, { key: 'Enter' });
    const panel = await screen.findByRole('dialog');
    expect(await within(panel).findByText(/No access\./)).toBeTruthy();
    expect(within(panel).getByText('Warehouse')).toBeTruthy();
  });

  it('says so when the record is no longer there', async () => {
    await open(wholeSource(null));
    fireEvent.keyDown(dataRows()[0]!, { key: 'Enter' });
    const panel = await screen.findByRole('dialog');
    expect(await within(panel).findByText(/no longer there/)).toBeTruthy();
  });

  it('closes, and the list is as it was', async () => {
    await open();
    fireEvent.keyDown(dataRows()[0]!, { key: 'Enter' });
    const panel = await screen.findByRole('dialog');
    fireEvent.click(within(panel).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });
});
