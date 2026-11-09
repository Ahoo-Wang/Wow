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
import {
  MemoryViewStore,
  ViewEngine,
  type RecordSummary,
  type RecordViewRuntime,
  type ViewSource,
} from '../src/index.js';
import { pageSummaries } from '../src/record/index.js';
import { memorySource } from '../src/testing/source.js';
import { useOpenView, useRecordTable } from '../src/react/index.js';
import { restyledOnly } from '../src/runtime/restyle.js';
import {
  nextTask,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { engineWith } from './fixtures/hooks.js';
import { AggregationFunction, SortDirection } from '@ahoo-wang/wow-client/dsl';

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
      columns: [{ field: 'id' }, { field: 'amount' }, { field: 'warehouse' }],
    },
  });

  it('calls a width, an order, a pin or the layout drawn', () => {
    expect(
      restyledOnly(ordersDefinition(), ran, {
        ...ran,
        layout: 'card',
        table: {
          columns: [
            { field: 'amount', width: 120, pinned: true },
            { field: 'id' },
            { field: 'warehouse' },
          ],
        },
      }),
    ).toBe('drawn');
  });

  it('calls a summary changed a summary', () => {
    expect(
      restyledOnly(ordersDefinition(), ran, {
        ...ran,
        summaries: [{ field: 'amount', fn: AggregationFunction.SUM }],
      }),
    ).toBe('summaries');
  });

  it('calls a column shown, a sort or a page size a question', () => {
    const fewer = recordConfig({
      table: {
        columns: [
          { field: 'id' },
          { field: 'amount' },
          { field: 'warehouse', hidden: true },
        ],
      },
    });
    expect(
      restyledOnly(ordersDefinition(), fewer, {
        ...fewer,
        table: ran.table,
      }),
    ).toBeNull();
    expect(
      restyledOnly(ordersDefinition(), ran, {
        ...ran,
        sort: [{ field: 'id', direction: SortDirection.ASC }],
      }),
    ).toBeNull();
    expect(
      restyledOnly(ordersDefinition(), ran, { ...ran, pageSize: 50 }),
    ).toBeNull();
  });

  it('calls a column hidden drawn: the rows already hold more than it shows', () => {
    expect(
      restyledOnly(ordersDefinition(), ran, {
        ...ran,
        table: {
          columns: [
            { field: 'id' },
            { field: 'amount' },
            { field: 'warehouse', hidden: true },
          ],
        },
      }),
    ).toBe('drawn');
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
          exact: true,
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

    act(() =>
      result.current.table.setSummary('amount', AggregationFunction.SUM),
    );
    await waitFor(() => expect(result.current.table.status).toBe('success'));
    await waitFor(() =>
      expect(result.current.table.summaries?.cells).toHaveLength(1),
    );

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(pages);
    expect(vi.mocked(source.aggregate).mock.calls).toHaveLength(aggregates + 1);
  });

  it('runs the query again for an apply that changes nothing', async () => {
    // Apply with nothing pending, Enter in the search box, a host's
    // `runtime.apply()`: each asks again, and starts over from page one.
    const source = testSource({
      paged: vi.fn(() =>
        Promise.resolve({
          total: 200,
          exact: true,
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
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.runtime!.apply());
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(asked + 1);
    expect(result.current.table.paging).toMatchObject({ index: 1 });
    expect(result.current.table.selection).toEqual([]);
  });

  it('asks again after a width landed and nothing else changed', async () => {
    const source = testSource();
    const result = await openTable(source);
    act(() => result.current.table.setColumnWidth('amount', 180));
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.runtime!.apply());
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(asked + 1);
  });

  it('keeps the table settled while the summary row alone is asked', async () => {
    let answer: (rows: Record<string, unknown>[]) => void = () => {};
    const source = testSource();
    const result = await openTable(source);
    vi.mocked(source.aggregate).mockImplementationOnce(
      () => new Promise(resolve => (answer = resolve)),
    );

    act(() =>
      result.current.table.setSummary('amount', AggregationFunction.SUM),
    );

    expect(result.current.table.status).toBe('success');
    expect(result.current.runtime?.getSnapshot().query.status).toBe('success');
    await act(async () => answer([{ amount: 30 }]));
    await waitFor(() =>
      expect(result.current.table.summaries?.cells).toHaveLength(1),
    );
  });

  it('runs whole a width set while the summary row is still out', async () => {
    let answer: (rows: Record<string, unknown>[]) => void = () => {};
    const source = testSource();
    const result = await openTable(source);
    vi.mocked(source.aggregate).mockImplementationOnce(
      () => new Promise(resolve => (answer = resolve)),
    );
    act(() =>
      result.current.table.setSummary('amount', AggregationFunction.SUM),
    );
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.table.setColumnWidth('amount', 180));
    await act(async () => answer([{ amount: 30 }]));
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(asked + 1);
    expect(result.current.table.summaries?.cells).toHaveLength(1);
    expect(
      result.current.table.columns.find(column => column.field === 'amount'),
    ).toEqual(expect.objectContaining({ width: 180 }));
  });

  it('still runs the page for a column switched on', async () => {
    const source = testSource();
    const result = await openTable(source);
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.table.setColumns(['id', 'amount', 'warehouse']));
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(asked + 1);
  });

  it('draws a column switched off from the rows it has', async () => {
    const source = testSource();
    const result = await openTable(source);
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.table.setColumns(['id']));
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    expect(vi.mocked(source.paged).mock.calls).toHaveLength(asked);
    expect(result.current.table.columns.map(column => column.field)).toEqual([
      'id',
    ]);
  });
});

/**
 * #5566: a summary reads its field off the page's rows (the page scope, and
 * the fallback when the totals fail), so the page is kept only while its
 * rows hold every field the new config asks for — by the query's own
 * projection, not by which columns happen to show.
 */
describe('a summary over a field the page did not ask for', () => {
  const amountHidden = () =>
    recordConfig({
      table: { columns: [{ field: 'id' }, { field: 'amount', hidden: true }] },
      card: { title: 'id', fields: [] },
    });
  const summaries: RecordSummary[] = [
    { field: 'amount', fn: AggregationFunction.SUM },
  ];

  function open(failTotals: boolean, config = amountHidden()) {
    const source = memorySource([
      { id: 'a', amount: 10 },
      { id: 'b', amount: 20 },
    ]);
    const paged = vi.spyOn(source, 'paged');
    if (failTotals)
      vi.spyOn(source, 'aggregate').mockRejectedValue(
        new Error('aggregation temporarily unavailable'),
      );
    const engine = new ViewEngine({
      store: new MemoryViewStore(),
      resources: resourcesOf([ordersDefinition()], () => source),
      environment: testEnvironment().environment,
    });
    const runtime = engine.create('orders', {
      title: 'Orders',
      scope: 'personal',
      config,
    });
    return { engine, runtime, paged };
  }

  const recordData = (runtime: RecordViewRuntime) => {
    const data = runtime.getSnapshot().result?.data;
    if (data?.kind !== 'record') throw new Error('Expected records');
    return data;
  };

  it('asks for the page again with the field, and adds up this page', async () => {
    const { engine, runtime, paged } = open(false, {
      ...amountHidden(),
      layout: 'card',
      pageSize: 1,
    });
    await nextTask();
    expect(paged.mock.calls[0][0].projection?.include).toEqual(['id']);

    runtime.edit({ summaries });
    runtime.apply();
    await nextTask();

    expect(paged).toHaveBeenCalledTimes(2);
    expect(paged.mock.calls[1][0].projection?.include).toEqual([
      'id',
      'amount',
    ]);
    const data = recordData(runtime);
    expect(data.summaries).toMatchObject({
      scope: 'total',
      cells: [{ field: 'amount', value: 30 }],
    });
    expect(pageSummaries(data.summaries!.cells, data.view.rows)).toMatchObject({
      scope: 'page',
      cells: [{ field: 'amount', value: 10 }],
    });
    engine.dispose();
  });

  it('falls back to the page’s own sum when the totals fail', async () => {
    const { engine, runtime, paged } = open(true);
    await nextTask();

    runtime.edit({ summaries });
    runtime.apply();
    await nextTask();

    expect(paged).toHaveBeenCalledTimes(2);
    expect(recordData(runtime).summaries).toMatchObject({
      scope: 'page',
      cells: [{ field: 'amount', value: 30 }],
    });
    engine.dispose();
  });

  it('keeps the page for a summary over a field it already holds', async () => {
    const { engine, runtime, paged } = open(false, {
      ...amountHidden(),
      table: { columns: [{ field: 'id' }, { field: 'amount' }] },
    });
    await nextTask();

    runtime.edit({ summaries });
    runtime.apply();
    await nextTask();

    expect(paged).toHaveBeenCalledTimes(1);
    expect(recordData(runtime).summaries).toMatchObject({
      scope: 'total',
      cells: [{ field: 'amount', value: 30 }],
    });
    engine.dispose();
  });

  it('keeps the page when a summary goes and takes its field along', async () => {
    const { engine, runtime, paged } = open(false, {
      ...amountHidden(),
      summaries,
    });
    await nextTask();

    runtime.edit({ summaries: [] });
    runtime.apply();
    await nextTask();

    expect(paged).toHaveBeenCalledTimes(1);
    expect(recordData(runtime).summaries).toBeNull();
    engine.dispose();
  });
});
