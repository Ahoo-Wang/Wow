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
 * An edit that only changes how the rows on screen are drawn — a column's
 * width, its place, its pin — lands without the page being fetched again,
 * and a summary changed asks for the aggregation alone (the accessibility
 * review's 「只改外观的编辑也重跑查询」).
 */

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordViewRuntime, ViewSource } from '../src/index.js';
import { useOpenView, useRecordTable } from '../src/react/index.js';
import { restyledOnly } from '../src/runtime/restyle.js';
import { recordConfig, testSource } from './fixtures.js';
import { engineWith } from './fixtures/hooks.js';

afterEach(cleanup);

async function openTable(source: ViewSource) {
  const { engine } = engineWith({ source });
  const { result } = renderHook(() => {
    const opened = useOpenView(engine, 'orders-1');
    return {
      runtime: opened.runtime as RecordViewRuntime | null,
      table: useRecordTable(opened.runtime as RecordViewRuntime | null),
    };
  });
  await waitFor(() => expect(result.current.table.status).toBe('success'));
  return result;
}

describe('restyledOnly', () => {
  const ran = recordConfig({
    table: {
      columns: [{ field: 'id' }, { field: 'amount' }, { field: 'note' }],
    },
  });

  it('calls a width, an order, a pin or the layout drawn', () => {
    expect(
      restyledOnly(ran, {
        ...ran,
        layout: 'card',
        table: {
          columns: [
            { field: 'amount', width: 120, pinned: true },
            { field: 'id' },
            { field: 'note' },
          ],
        },
      }),
    ).toBe('drawn');
  });

  it('calls a summary changed a summary', () => {
    expect(
      restyledOnly(ran, {
        ...ran,
        summaries: [{ field: 'amount', fn: 'SUM' }],
      }),
    ).toBe('summaries');
  });

  it('calls a column shown or hidden, a sort or a page size a question', () => {
    expect(
      restyledOnly(ran, {
        ...ran,
        table: {
          columns: [
            { field: 'id' },
            { field: 'amount' },
            { field: 'note', hidden: true },
          ],
        },
      }),
    ).toBeNull();
    expect(
      restyledOnly(ran, { ...ran, sort: [{ field: 'id', direction: 'ASC' }] }),
    ).toBeNull();
    expect(restyledOnly(ran, { ...ran, pageSize: 50 })).toBeNull();
  });
});

describe('a presentation edit on a record view', () => {
  it('draws a width, an order and a pin without asking for the page', async () => {
    const source = testSource();
    const result = await openTable(source);
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.table.setColumnWidth('amount', 180));
    act(() => result.current.table.setColumnOrder(['amount', 'id']));
    act(() => result.current.table.setPinned('amount', true));

    const { table, runtime } = result.current;
    expect(vi.mocked(source.paged).mock.calls).toHaveLength(asked);
    expect(table.status).toBe('success');
    expect(table.columns.find(column => column.field === 'amount')).toEqual(
      expect.objectContaining({ width: 180, pinned: 'left' }),
    );
    // Landed, not waiting: nothing is left for Apply to run.
    expect(runtime?.getSnapshot().applied).toEqual(
      runtime?.getSnapshot().draft,
    );
  });

  it('keeps the page and the selection it drew them on', async () => {
    const source = testSource({
      paged: vi.fn(() =>
        Promise.resolve({
          total: 200,
          list: [
            { id: 'o-1', amount: 10 },
            { id: 'o-2', amount: 20 },
          ],
        }),
      ),
    });
    const result = await openTable(source);
    act(() => result.current.table.next());
    await waitFor(() =>
      expect(result.current.table.paging).toMatchObject({ index: 2 }),
    );
    act(() => result.current.table.toggle('o-1'));

    act(() => result.current.table.setColumnWidth('id', 96));

    expect(result.current.table.paging).toMatchObject({ index: 2 });
    expect(result.current.table.selection).toEqual(['o-1']);
  });

  it('asks for the aggregation alone when a summary changes', async () => {
    const source = testSource();
    const result = await openTable(source);
    const pages = vi.mocked(source.paged).mock.calls.length;
    const aggregates = vi.mocked(source.aggregate).mock.calls.length;

    act(() => result.current.table.setSummary('amount', 'SUM'));
    await waitFor(() => expect(result.current.table.status).toBe('success'));
    await waitFor(() =>
      expect(result.current.table.summaries?.cells).toHaveLength(1),
    );

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(pages);
    expect(vi.mocked(source.aggregate).mock.calls).toHaveLength(aggregates + 1);
  });

  it('still runs the page for a column switched on', async () => {
    const source = testSource();
    const result = await openTable(source);
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.table.setColumns(['id']));
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(asked + 1);
  });
});
