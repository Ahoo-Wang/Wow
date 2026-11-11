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
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import type { FilterPagedQuery } from '@ahoo-wang/wow-client/dsl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { detailSections } from '../src/record/index.js';
import { DataWorkbench, ViewHost } from '../src/ui/index.js';
import { useOpenView, useRecordDetail } from '../src/react/index.js';
import { MemoryViewStore, ViewEngine, type RecordData } from '../src/index.js';
import {
  ROWS,
  mine,
  ordersDefinition,
  testSource,
  resourcesOf,
} from './fixtures.js';

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
              exact: true,
              list: whole ? [whole] : [],
            });
      return Promise.resolve({ total: 2, exact: true, list: [...ROWS] });
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

async function open(source = wholeSource(), characterKeys?: boolean) {
  const engine = new ViewEngine({
    resources: resourcesOf([withNote()], () => source),
    store: new MemoryViewStore({ instances: [mine] }),
  });
  const workbench = (
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
    />
  );
  render(
    characterKeys === undefined ? (
      workbench
    ) : (
      <ViewHost characterKeys={characterKeys}>{workbench}</ViewHost>
    ),
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
        section.fields.map(field => field.field),
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

describe('detailSections, read the way a card reads', () => {
  it('resolves an array of objects to the element title it is read by', () => {
    const [section] = detailSections({
      fields: [
        {
          name: 'lines',
          label: 'Lines',
          kind: 'elementMatch',
          elementTitle: 'sku',
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' },
            { name: 'qty', label: 'Qty', kind: 'number' },
          ],
        },
      ],
    });
    expect(section!.fields[0]).toMatchObject({
      field: 'lines',
      label: 'Lines',
      elementTitle: { name: 'sku', kind: 'string' },
    });
  });
});

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

  it('steps to the record before and after without closing, by button and by K and J (R2-97)', async () => {
    await open();
    const total = dataRows().length;
    fireEvent.click(within(dataRows()[0]!).getAllByRole('cell')[2]!);
    const panel = await screen.findByRole('dialog');
    const heading = (name: string) =>
      within(panel).findByRole('heading', { name });
    const step = () =>
      panel.querySelector<HTMLElement>('[data-slot="record-detail-step"]')!;

    // The first of the page: no way back, and it says why.
    expect(step().textContent).toContain(`1 of ${total}`);
    const back = within(step()).getByRole('button', {
      name: 'The first record on this page',
    });
    expect(back.getAttribute('aria-disabled')).toBe('true');
    expect(back.getAttribute('aria-keyshortcuts')).toBe('K');
    fireEvent.click(back);
    expect(await heading('o-1')).toBeTruthy();

    fireEvent.click(
      within(step()).getByRole('button', { name: 'Next record' }),
    );
    expect(await heading('o-2')).toBeTruthy();
    expect(step().textContent).toContain(`2 of ${total}`);

    // K and J, from anywhere in the panel but a field.
    fireEvent.keyDown(panel, { key: 'k' });
    expect(await heading('o-1')).toBeTruthy();
    fireEvent.keyDown(panel, { key: 'J' });
    expect(await heading('o-2')).toBeTruthy();
    fireEvent.keyDown(panel, { key: 'j', ctrlKey: true });
    expect(await heading('o-2')).toBeTruthy();

    // Out to the page's last: no way on.
    for (let at = 2; at < total; at += 1)
      fireEvent.keyDown(panel, { key: 'j' });
    expect(await heading(`o-${total}`)).toBeTruthy();
    expect(
      within(step())
        .getByRole('button', { name: 'The last record on this page' })
        .getAttribute('aria-disabled'),
    ).toBe('true');
    fireEvent.keyDown(panel, { key: 'j' });
    expect(await heading(`o-${total}`)).toBeTruthy();
  });

  it('steps by neither K nor J under a host that turns the keys off, and still by its buttons (WCAG 2.1.4)', async () => {
    await open(wholeSource(), false);
    fireEvent.click(within(dataRows()[0]!).getAllByRole('cell')[2]!);
    const panel = await screen.findByRole('dialog');
    const heading = (name: string) =>
      within(panel).findByRole('heading', { name });
    const step = () =>
      panel.querySelector<HTMLElement>('[data-slot="record-detail-step"]')!;
    expect(await heading('o-1')).toBeTruthy();

    expect(fireEvent.keyDown(panel, { key: 'j' })).toBe(true);
    expect(fireEvent.keyDown(panel, { key: 'J' })).toBe(true);
    expect(await heading('o-1')).toBeTruthy();
    const next = within(step()).getByRole('button', { name: 'Next record' });
    expect(next.getAttribute('aria-keyshortcuts')).toBeNull();

    fireEvent.click(next);
    expect(await heading('o-2')).toBeTruthy();
    expect(fireEvent.keyDown(panel, { key: 'k' })).toBe(true);
    expect(await heading('o-2')).toBeTruthy();
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
                code: 'Forbidden',
                detail: 'No access.',
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

/**
 * #5513: a record is a key in one view. The same detail control kept across
 * a switch to another view (DataWorkbench keeps its record parts mounted)
 * may hold the same key there — another dataset's row — and what it read
 * in the first view is not that record.
 */
describe('a detail kept across a switch of view', () => {
  const definitionA = ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      { name: 'note', label: 'Note', kind: 'string' },
    ],
  });
  const definitionB = ordersDefinition({
    id: 'returns',
    source: 'returns',
    title: 'Returns',
    fields: definitionA.fields,
  });
  const pageA = { id: 'o-1', amount: 10, status: 'ORDER', warehouse: 'A' };
  const wholeA = { ...pageA, note: 'Order detail from A' };
  const pageB = { id: 'o-1', amount: 20, status: 'RETURN', warehouse: 'B' };
  const isDetail = (query: FilterPagedQuery) => query.pagination?.size === 1;

  function harness(failDetailOfA: () => boolean = () => false) {
    const a = testSource({
      paged: async query => {
        if (isDetail(query) && failDetailOfA())
          throw new Error('The orders detail request failed');
        return {
          total: 1,
          exact: true,
          list: [isDetail(query) ? wholeA : pageA],
        };
      },
    });
    const b = testSource({
      paged: async query => {
        if (isDetail(query))
          throw new Error('The returns detail request failed');
        return { total: 1, exact: true, list: [pageB] };
      },
    });
    const engine = new ViewEngine({
      resources: resourcesOf([definitionA, definitionB], key =>
        key === 'orders' ? a : b,
      ),
      store: new MemoryViewStore({
        instances: [
          mine,
          { ...mine, id: 'returns-1', definitionId: 'returns' },
        ],
      }),
    });
    return renderHook(
      ({ instanceId }: { instanceId: string }) => {
        const opened = useOpenView(engine, instanceId);
        const runtime =
          opened.runtime?.kind === 'record' ? opened.runtime : null;
        return { runtime, detail: useRecordDetail(runtime, { open: 'o-1' }) };
      },
      { initialProps: { instanceId: 'orders-1' } },
    );
  }

  it('shows the new view’s row, not the old view’s record of the same key', async () => {
    const view = harness();
    await waitFor(() =>
      expect(view.result.current.detail.record).toEqual(wholeA),
    );
    expect(view.result.current.detail.complete).toBe(true);

    view.rerender({ instanceId: 'returns-1' });
    await waitFor(() =>
      expect(view.result.current.detail.status).toBe('error'),
    );
    await waitFor(() =>
      expect(view.result.current.runtime?.getSnapshot().query.status).toBe(
        'success',
      ),
    );

    expect(view.result.current.runtime?.definition.id).toBe('returns');
    expect(view.result.current.detail.record).toEqual(pageB);
    expect(view.result.current.detail.complete).toBe(false);
    view.unmount();
  });

  it('keeps its own record when a read of it again fails in the same view', async () => {
    let fail = false;
    const view = harness(() => fail);
    await waitFor(() =>
      expect(view.result.current.detail.record).toEqual(wholeA),
    );

    fail = true;
    act(() => view.result.current.detail.reload());
    await waitFor(() =>
      expect(view.result.current.detail.status).toBe('error'),
    );

    expect(view.result.current.detail.record).toEqual(wholeA);
    expect(view.result.current.detail.complete).toBe(true);
    view.unmount();
  });
});
