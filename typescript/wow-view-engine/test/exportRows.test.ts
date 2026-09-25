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

import { describe, expect, it, vi } from 'vitest';
import {
  FilterOperator,
  type CursorQuery,
  type FilterPagedQuery,
} from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  isRecordRuntime,
  DEFAULT_RUNTIME_LIMITS,
  isExportCancelled,
  type DataViewDefinition,
  type RecordData,
  type RecordViewRuntime,
  type RuntimeLimits,
  type ViewSource,
} from '../src/index.js';
import {
  RecordDataViewRuntime,
  dataViewRuntime,
} from '../src/runtime/recordRuntime.js';
import { exportPlan } from '../src/runtime/exportRows.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import {
  analysisConfig,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

/** As many orders as asked for, `o-1` … `o-n`. */
function orders(count: number, from = 1): RecordData[] {
  return Array.from({ length: count }, (_row, index) => ({
    id: `o-${from + index}`,
    amount: (from + index) * 10,
  }));
}

/**
 * A paged source over `rows`, answering each request the way a service does.
 * Every call is recorded, so a test can say how the pages were asked for.
 */
function pagedSource(rows: RecordData[], total = rows.length): ViewSource {
  return testSource({
    paged: vi.fn((query: FilterPagedQuery) => {
      const { index, size } = query.pagination ?? { index: 1, size: 20 };
      const start = (index - 1) * size;
      return Promise.resolve({ total, list: rows.slice(start, start + size) });
    }),
  });
}

function limits(overrides: Partial<RuntimeLimits> = {}): RuntimeLimits {
  return { ...DEFAULT_RUNTIME_LIMITS, ...overrides };
}

function openRecord(options: {
  source: ViewSource;
  definition?: DataViewDefinition;
  limits?: RuntimeLimits;
}): RecordViewRuntime {
  return new RecordDataViewRuntime({
    id: 'runtime-1',
    definition: options.definition ?? ordersDefinition(),
    config: recordConfig({ pageSize: 2 }),
    title: 'Mine',
    scope: 'personal',
    kinds: builtinFieldKinds,
    limits: options.limits ?? limits({ maxPageSize: 3 }),
    environment: testEnvironment().environment,
    source: options.source,
    runner: new RequestRunner(),
  }) as unknown as RecordViewRuntime;
}

describe('exporting every row the applied config matches', () => {
  it('pages the source at the runtime ceiling, not at the view page size', async () => {
    const source = pagedSource(orders(7));
    const runtime = openRecord({ source });

    const exported = await runtime.exportRows();

    expect(exported.rows).toHaveLength(7);
    expect(exported.total).toBe(7);
    expect(exported.capped).toBe(false);
    // Three requests of three, one of one: the view's own `pageSize: 2` has
    // nothing to do with how a file is fetched.
    expect(source.paged).toHaveBeenCalledTimes(3);
    const sizes = vi
      .mocked(source.paged)
      .mock.calls.map(([query]) => query.pagination);
    expect(sizes).toEqual([
      { index: 1, size: 3 },
      { index: 2, size: 3 },
      { index: 3, size: 3 },
    ]);
  });

  it('asks under the applied conditions, the scope filter included', async () => {
    const source = pagedSource(orders(2));
    const runtime = openRecord({ source });
    runtime.edit({
      filter: {
        op: 'and',
        children: [
          { field: 'status', operator: FilterOperator.EQ, value: 'PENDING' },
        ],
      },
    });
    runtime.apply();
    runtime.setScopeFilter({
      op: 'and',
      children: [
        { field: 'warehouse', operator: FilterOperator.EQ, value: 'CN' },
      ],
    });

    await runtime.exportRows();

    const calls = vi.mocked(source.paged).mock.calls;
    const [query] = calls[calls.length - 1];
    // Both halves of what the rows on screen were fetched under: the view's
    // own condition, and the one the host injected.
    expect(JSON.stringify(query.filter)).toContain('PENDING');
    expect(JSON.stringify(query.filter)).toContain('CN');
  });

  it('stops at the ceiling and says the file is short', async () => {
    const source = pagedSource(orders(9));
    const runtime = openRecord({ source });

    const exported = await runtime.exportRows({ max: 4 });

    expect(exported.rows.map(row => row.id)).toEqual([
      'o-1',
      'o-2',
      'o-3',
      'o-4',
    ]);
    expect(exported.capped).toBe(true);
    // Two pages of three reach the ceiling; nothing beyond it is asked for.
    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  it('takes the ceiling from the limits when the caller names none', async () => {
    const source = pagedSource(orders(6));
    const runtime = openRecord({
      source,
      limits: limits({ maxPageSize: 3, exportMax: 3 }),
    });

    const exported = await runtime.exportRows();

    expect(exported.rows).toHaveLength(3);
    expect(exported.capped).toBe(true);
  });

  /**
   * A page past the source's paging window is refused outright, so an export
   * that went on asking would fail after fetching every row before it. It
   * stops at the last whole page inside the window instead, and says the
   * file is short.
   */
  it('stops at the source paging window, below the limits', async () => {
    const source = pagedSource(orders(9));
    const runtime = openRecord({
      source,
      definition: ordersDefinition({
        record: {
          rowKey: 'id',
          paging: 'paged',
          layouts: ['table'],
          maxWindow: 7,
        },
      }),
    });

    const exported = await runtime.exportRows();

    // Two pages of three stay inside a window of seven; the third would
    // reach row nine.
    expect(exported.rows).toHaveLength(6);
    expect(exported.capped).toBe(true);
    expect(
      vi.mocked(source.paged).mock.calls.map(([query]) => query.pagination),
    ).toEqual([
      { index: 1, size: 3 },
      { index: 2, size: 3 },
    ]);
  });

  it('shrinks its page to a window smaller than one page', async () => {
    expect(
      exportPlan({ exportMax: 100, maxPageSize: 200 }, { maxWindow: 50 }),
    ).toEqual({ size: 50, max: 50 });
    expect(exportPlan({ exportMax: 100, maxPageSize: 200 })).toEqual({
      size: 200,
      max: 100,
    });
  });

  it('stops inside the window a default Wow server serves (D42)', () => {
    // Raised past it, an export would fail on the page that reaches row
    // 10,001 (`HttpQueryGuard.maxPageWindow`), having fetched every row
    // before it.
    expect(
      exportPlan({ ...DEFAULT_RUNTIME_LIMITS, exportMax: 50_000 }),
    ).toEqual({ size: 100, max: 10_000 });
    // A cursor source has no window.
    expect(
      exportPlan(
        { ...DEFAULT_RUNTIME_LIMITS, exportMax: 50_000 },
        { paging: 'cursor' },
      ),
    ).toEqual({ size: 100, max: 50_000 });
  });

  it('reports what it has after every page', async () => {
    const source = pagedSource(orders(5));
    const runtime = openRecord({ source });
    const onProgress = vi.fn();

    await runtime.exportRows({ onProgress });

    expect(onProgress.mock.calls).toEqual([
      [3, 5],
      [5, 5],
    ]);
  });

  it('follows a cursor to the end of the result', async () => {
    const pages: Record<string, RecordData[]> = {
      '': orders(3),
      'c-2': orders(2, 4),
    };
    const source = testSource({
      cursor: vi.fn((query: CursorQuery) => {
        const list = pages[query.cursor ?? ''];
        return Promise.resolve({
          list,
          nextCursor: query.cursor ? null : 'c-2',
        });
      }),
    });
    const runtime = openRecord({
      source,
      definition: ordersDefinition({
        record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
      }),
    });

    const exported = await runtime.exportRows();

    expect(exported.rows.map(row => row.id)).toEqual([
      'o-1',
      'o-2',
      'o-3',
      'o-4',
      'o-5',
    ]);
    // A cursor source reports no total, so nobody claims one.
    expect(exported.total).toBeUndefined();
    expect(exported.capped).toBe(false);
  });

  it('stops where the signal is aborted, and says so', async () => {
    const controller = new AbortController();
    const asked: number[] = [];
    const source = testSource({
      paged: vi.fn((query: FilterPagedQuery) => {
        asked.push(query.pagination?.index ?? 0);
        // Cancelled while the second page is in flight, which is the moment
        // a user presses the button.
        if (asked.length === 2) controller.abort();
        return Promise.resolve({ total: 99, list: orders(3) });
      }),
    });
    const runtime = openRecord({ source });

    const caught = await runtime
      .exportRows({ signal: controller.signal })
      .catch((error: unknown) => error);

    expect(isExportCancelled(caught)).toBe(true);
    expect(asked).toEqual([1, 2]);
  });

  it('asks for nothing at all when it is cancelled before it starts', async () => {
    const source = pagedSource(orders(4));
    const runtime = openRecord({ source });

    const caught = await runtime
      .exportRows({ signal: AbortSignal.abort() })
      .catch((error: unknown) => error);

    expect(isExportCancelled(caught)).toBe(true);
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('leaves the view exactly as it was', async () => {
    const source = pagedSource(orders(7));
    const runtime = openRecord({ source });
    const before = runtime.getSnapshot();
    const seen = vi.fn();
    runtime.subscribe(seen);

    await runtime.exportRows();

    // No request of its own in the runner, no result, no notification: the
    // rows the user is reading are untouched by a file being built.
    expect(runtime.getSnapshot()).toBe(before);
    expect(seen).not.toHaveBeenCalled();
  });

  it('ends an empty page rather than asking for the next one', async () => {
    // A source that answers a full page and then nothing: without the empty
    // page ending it, a size it disagrees with would keep it going forever.
    const source = testSource({
      paged: vi.fn((query: FilterPagedQuery) =>
        Promise.resolve({
          total: 3,
          list: query.pagination?.index === 1 ? orders(3) : [],
        }),
      ),
    });
    const runtime = openRecord({ source });

    const exported = await runtime.exportRows();

    expect(exported.rows).toHaveLength(3);
    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  /**
   * An analysis has no rows of its own to export, and its runtime carries
   * no export to refuse: the method is the Record runtime's alone.
   */
  it('gives a view with no rows of its own no export at all', () => {
    const runtime = dataViewRuntime({
      id: 'runtime-2',
      definition: ordersDefinition(),
      config: analysisConfig(),
      title: 'Mine',
      scope: 'personal',
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
      environment: testEnvironment().environment,
      source: testSource(),
      runner: new RequestRunner(),
    });

    expect(isRecordRuntime(runtime)).toBe(false);
    expect('exportRows' in runtime).toBe(false);
    runtime.dispose();
  });

  it('is a no-op once the view is closed', async () => {
    const source = pagedSource(orders(4));
    const runtime = openRecord({ source });
    runtime.dispose();

    await expect(runtime.exportRows()).rejects.toThrow(/closed/);
    expect(source.paged).not.toHaveBeenCalled();
  });
});
