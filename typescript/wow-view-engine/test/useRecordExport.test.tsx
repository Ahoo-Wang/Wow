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

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FilterPagedQuery, PagedList } from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RecordViewRuntime,
  type RuntimeLimits,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  useOpenView,
  useRecordExport,
  useRecordTable,
  type RecordExportScope,
} from '../src/react/index.js';
import {
  ordersDefinition,
  recordConfig,
  ROWS,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  // Two rows a page, so a test may hold `maxPageSize` low enough to make an
  // export take several requests without the config itself being refused.
  config: recordConfig({ pageSize: 2 }),
};

/**
 * A source that answers the view's own first query at once and holds every
 * request after it, which are the export's.
 *
 * The alternative — one deferred promise for everything — never lets the view
 * open, and an export cannot run on a view with no result.
 */
function gatedSource(): { source: ViewSource; held: (() => void)[] } {
  const held: (() => void)[] = [];
  let opened = false;
  const answer = { total: 2, list: [...ROWS] };
  const source = testSource({
    paged: vi.fn(() => {
      if (!opened) {
        opened = true;
        return Promise.resolve(answer);
      }
      return new Promise<PagedList<RecordData>>(resolve =>
        held.push(() => resolve(answer)),
      );
    }),
  });
  return { source, held };
}

/** What the delivery was handed, in the order it was handed over. */
interface Delivered {
  rows: readonly RecordData[];
  scope: RecordExportScope;
}

function orders(count: number, from = 1): RecordData[] {
  return Array.from({ length: count }, (_row, index) => ({
    id: `o-${from + index}`,
    amount: (from + index) * 10,
  }));
}

async function openExport(
  options: {
    source?: ViewSource;
    limits?: Partial<RuntimeLimits>;
    deliver?(rows: readonly RecordData[], scope: RecordExportScope): void;
  } = {},
) {
  const delivered: Delivered[] = [];
  const engine = new ViewEngine({
    // The definition's own system view is admitted under the same limits, so
    // it pages small too — a `maxPageSize` below its size would refuse the
    // whole definition and nothing would open at all.
    definitions: [
      ordersDefinition({
        views: [
          {
            id: 'all',
            title: 'All orders',
            config: recordConfig({ pageSize: 2 }),
          },
        ],
      }),
    ],
    store: new MemoryViewStore({ instances: [mine] }),
    resolveSource: () => options.source ?? testSource(),
    ...(options.limits
      ? { limits: { ...DEFAULT_RUNTIME_LIMITS, ...options.limits } }
      : {}),
  });
  const { result, unmount } = renderHook(() => {
    const opened = useOpenView(engine, 'orders-1');
    const runtime = opened.runtime as RecordViewRuntime | null;
    const table = useRecordTable(runtime);
    return {
      runtime,
      table,
      exporter: useRecordExport(runtime, table, {
        deliver: (rows, scope) => {
          options.deliver?.(rows, scope);
          delivered.push({ rows, scope });
        },
      }),
    };
  });
  await waitFor(() => expect(result.current.table.status).toBe('success'));
  return { result, delivered, unmount };
}

describe('useRecordExport scopes', () => {
  it('counts this page and the whole result, and offers no selection', async () => {
    const { result } = await openExport();

    expect(result.current.exporter.scopes).toEqual({ page: 2, all: 2 });
    expect(result.current.exporter.scopes.selected).toBeUndefined();
  });

  it('offers the selection once there is one', async () => {
    const { result } = await openExport();

    act(() => result.current.table.toggle('o-1'));

    expect(result.current.exporter.scopes.selected).toBe(1);
  });

  it('has no count for a result whose total nobody reports', async () => {
    const { result } = await openExport({
      source: testSource({
        // The protocol declares a total; a service that withholds it is a
        // thing that happens, and the count is then nobody's to claim.
        paged: vi.fn(() =>
          Promise.resolve({
            list: [...ROWS],
          } as unknown as PagedList<RecordData>),
        ),
      }),
    });

    expect(result.current.exporter.scopes.all).toBeNull();
  });
});

describe('useRecordExport runs', () => {
  it('hands over the rows on screen for this page', async () => {
    const { result, delivered } = await openExport();

    act(() => result.current.exporter.run('page'));

    await waitFor(() => expect(delivered).toHaveLength(1));
    expect(delivered[0].scope).toBe('page');
    expect(delivered[0].rows).toEqual(ROWS);
    expect(result.current.exporter.error).toBeNull();
  });

  it('hands over the picked rows, in result order', async () => {
    const { result, delivered } = await openExport();

    act(() => result.current.table.toggle('o-2'));
    act(() => result.current.exporter.run('selected'));

    await waitFor(() => expect(delivered).toHaveLength(1));
    expect(delivered[0].rows).toEqual([ROWS[1]]);
  });

  it('pages the source for everything, and reports as it goes', async () => {
    const rows = orders(5);
    const source = testSource({
      paged: vi.fn((query: FilterPagedQuery) => {
        const { index, size } = query.pagination ?? { index: 1, size: 20 };
        return Promise.resolve({
          total: rows.length,
          list: rows.slice((index - 1) * size, index * size),
        });
      }),
    });
    const { result, delivered } = await openExport({
      source,
      limits: { maxPageSize: 3 },
    });

    act(() => result.current.exporter.run('all'));

    await waitFor(() => expect(delivered).toHaveLength(1));
    expect(delivered[0].scope).toBe('all');
    expect(delivered[0].rows).toHaveLength(5);
    expect(result.current.exporter.running).toBeNull();
    expect(result.current.exporter.progress).toBeNull();
  });

  it('says it is running while the pages come in', async () => {
    const { source, held } = gatedSource();
    const { result, delivered } = await openExport({ source });

    act(() => result.current.exporter.run('all'));

    await waitFor(() => expect(result.current.exporter.running).toBe('all'));
    expect(result.current.exporter.progress).toEqual({
      scope: 'all',
      fetched: 0,
      total: 2,
    });
    await act(async () => {
      held[0]();
    });
    await waitFor(() => expect(delivered).toHaveLength(1));
    expect(result.current.exporter.running).toBeNull();
  });

  it('refuses a second run while one is in flight', async () => {
    const { source, held } = gatedSource();
    const { result, delivered } = await openExport({ source });

    act(() => result.current.exporter.run('all'));
    await waitFor(() => expect(result.current.exporter.running).toBe('all'));
    act(() => result.current.exporter.run('page'));

    expect(result.current.exporter.running).toBe('all');
    await act(async () => {
      held[0]();
    });
    await waitFor(() => expect(delivered).toHaveLength(1));
    expect(delivered[0].scope).toBe('all');
  });
});

describe('useRecordExport limits and failures', () => {
  it('asks before fetching more rows than the ceiling carries', async () => {
    const source = testSource({
      paged: vi.fn(() => Promise.resolve({ total: 40, list: [...ROWS] })),
    });
    const { result, delivered } = await openExport({
      source,
      limits: { exportMax: 10 },
    });
    const fetched = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.exporter.run('all'));

    expect(result.current.exporter.overLimit).toEqual({ count: 40, max: 10 });
    expect(delivered).toHaveLength(0);
    // Nothing is asked of the source until the question is answered.
    expect(source.paged).toHaveBeenCalledTimes(fetched);
  });

  it('exports the first rows once the question is answered', async () => {
    const rows = orders(40);
    const source = testSource({
      paged: vi.fn((query: FilterPagedQuery) => {
        const { index, size } = query.pagination ?? { index: 1, size: 20 };
        return Promise.resolve({
          total: rows.length,
          list: rows.slice((index - 1) * size, index * size),
        });
      }),
    });
    const { result, delivered } = await openExport({
      source,
      limits: { exportMax: 10, maxPageSize: 10 },
    });

    act(() => result.current.exporter.run('all'));
    act(() => result.current.exporter.run('all', { force: true }));

    await waitFor(() => expect(delivered).toHaveLength(1));
    expect(delivered[0].rows).toHaveLength(10);
    expect(result.current.exporter.overLimit).toBeNull();
    // The file is short, and the view says so rather than passing it off as
    // everything the conditions match.
    expect(result.current.exporter.error).toMatchObject({
      code: 'export.capped',
      severity: 'warning',
      params: { count: 10 },
    });
  });

  it('drops the question when it is cancelled', async () => {
    const source = testSource({
      paged: vi.fn(() => Promise.resolve({ total: 40, list: [...ROWS] })),
    });
    const { result, delivered } = await openExport({
      source,
      limits: { exportMax: 10 },
    });

    act(() => result.current.exporter.run('all'));
    act(() => result.current.exporter.cancel());

    expect(result.current.exporter.overLimit).toBeNull();
    expect(delivered).toHaveLength(0);
  });

  it('stops a run that is cancelled, and says nothing about it', async () => {
    const { source, held } = gatedSource();
    const { result, delivered } = await openExport({ source });

    act(() => result.current.exporter.run('all'));
    await waitFor(() => expect(result.current.exporter.running).toBe('all'));
    act(() => result.current.exporter.cancel());
    await act(async () => {
      held[0]();
    });

    expect(result.current.exporter.running).toBeNull();
    expect(result.current.exporter.error).toBeNull();
    expect(delivered).toHaveLength(0);
  });

  it('reports a failed fetch as one Issue', async () => {
    const { result } = await openExport({
      source: testSource({
        paged: vi
          .fn()
          .mockResolvedValueOnce({ total: 2, list: [...ROWS] })
          .mockRejectedValue(new Error('gateway down')),
      }),
    });

    act(() => result.current.exporter.run('all'));

    await waitFor(() =>
      expect(result.current.exporter.error).toMatchObject({
        code: 'export.failed',
        params: { reason: 'gateway down' },
      }),
    );
    expect(result.current.exporter.running).toBeNull();
  });

  it('reports a delivery that throws the same way', async () => {
    const { result } = await openExport({
      deliver: () => {
        throw new Error('no file system');
      },
    });

    act(() => result.current.exporter.run('page'));

    await waitFor(() =>
      expect(result.current.exporter.error).toMatchObject({
        code: 'export.failed',
        params: { reason: 'no file system' },
      }),
    );
  });

  it('is inert without a runtime', () => {
    const { result } = renderHook(() =>
      useRecordExport(null, useRecordTable(null), { deliver: () => {} }),
    );

    expect(result.current.scopes).toEqual({ page: 0, all: null });
    act(() => result.current.run('all'));
    expect(result.current.running).toBeNull();
  });
});
