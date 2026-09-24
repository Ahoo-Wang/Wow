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

import { FilterOperator, type QueryApi } from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import type { DataViewRuntime } from '../src/runtime/viewRuntime.js';
import type { RecordDataViewRuntime } from '../src/runtime/recordRuntime.js';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  defaultRuntimeEnvironment,
  type DataViewConfig,
  type DataViewDefinition,
  type FilterTree,
  type ProjectedRecord,
  type RecordData,
  type RuntimeLimits,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { dataViewRuntime } from '../src/runtime/recordRuntime.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import { firstPageOf } from '../src/runtime/execute.js';
import {
  analysisConfig,
  deferred,
  nextTask,
  ordersDefinition,
  recordConfig,
  requireRecordConfig,
  NOW,
  ROWS,
  testEnvironment,
  testSource,
  type TestEnvironment,
} from './fixtures.js';

interface Harness {
  // Record API on hand; the analysis cases in this file never call it.
  runtime: RecordDataViewRuntime;
  source: ViewSource;
  clock: TestEnvironment;
}

function harness(
  options: {
    definition?: DataViewDefinition;
    config?: DataViewConfig;
    source?: ViewSource;
    saved?: ViewInstance | null;
    runner?: RequestRunner;
    limits?: RuntimeLimits;
    scopeFilter?: FilterTree | null;
  } = {},
): Harness {
  const clock = testEnvironment();
  const source = options.source ?? testSource();
  const definition = options.definition ?? ordersDefinition();
  const runtime = dataViewRuntime({
    id: 'runtime-1',
    definition,
    config: options.config ?? recordConfig(),
    title: 'Mine',
    scope: 'personal',
    saved: options.saved ?? null,
    kinds: builtinFieldKinds,
    limits: options.limits ?? DEFAULT_RUNTIME_LIMITS,
    environment: clock.environment,
    source,
    runner: options.runner ?? new RequestRunner(),
    scopeFilter: options.scopeFilter,
  }) as RecordDataViewRuntime;
  return { runtime, source, clock };
}

function recordData(runtime: DataViewRuntime): ProjectedRecord {
  const data = runtime.getSnapshot().result?.data;
  if (data?.kind !== 'record') throw new Error('expected a record result');
  return data;
}

const savedInstance: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

describe('DataViewRuntime state', () => {
  it('starts idle with the config as both draft and applied', () => {
    const { runtime } = harness();
    const state = runtime.getSnapshot();

    expect(runtime.kind).toBe('record');
    expect(state.draft).toEqual(state.applied);
    expect(state.query).toEqual({ status: 'idle' });
    expect(state.result).toBeNull();
    expect(state.issues).toEqual([]);
  });

  it('counts an unsaved view as dirty and a saved one as clean', () => {
    expect(harness().runtime.getSnapshot().dirty).toBe(true);
    expect(harness({ saved: savedInstance }).runtime.getSnapshot().dirty).toBe(
      false,
    );
  });

  it('notifies subscribers and stops after they unsubscribe', () => {
    const { runtime } = harness();
    const listener = vi.fn();
    const unsubscribe = runtime.subscribe(listener);

    runtime.edit({ pageSize: 30 });
    unsubscribe();
    runtime.edit({ pageSize: 40 });

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('edits the draft only, and reports what the edit broke', () => {
    const { runtime } = harness({ saved: savedInstance });

    runtime.edit({ pageSize: 0 });
    const state = runtime.getSnapshot();

    expect(requireRecordConfig(state.draft).pageSize).toBe(0);
    expect(requireRecordConfig(state.applied).pageSize).toBe(20);
    expect(state.dirty).toBe(true);
    expect(state.issues.map(found => found.severity)).toContain('error');
  });

  it('refuses to apply a draft that still has an error', () => {
    const { runtime, source } = harness();

    runtime.edit({ pageSize: -1 });
    runtime.apply();

    expect(source.paged).not.toHaveBeenCalled();
    expect(runtime.getSnapshot().query.status).toBe('idle');
  });
});

describe('DataViewRuntime execution', () => {
  /**
   * How long the answer took is part of the answer: the analysis result's
   * footer says it. Measured on the environment's clock, from asking to
   * landing — a source that takes 400ms is a result that says 400.
   */
  it('says how long the answer took', async () => {
    let answer: (
      rows: Awaited<ReturnType<ViewSource['paged']>>,
    ) => void = () => {};
    const source = testSource({
      paged: vi.fn(
        () =>
          new Promise<Awaited<ReturnType<ViewSource['paged']>>>(resolve => {
            answer = resolve;
          }),
      ),
    });
    const { runtime, clock } = harness({ source });

    runtime.apply();
    await nextTask();
    clock.advance(400);
    answer({ total: 0, list: [] });
    await nextTask();

    expect(runtime.getSnapshot().result?.elapsedMs).toBe(400);
  });

  it('queries the first page and keeps the config that produced the result', async () => {
    const { runtime, source } = harness();

    runtime.apply();
    expect(runtime.getSnapshot().query.status).toBe('loading');
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.query.status).toBe('success');
    expect(state.result?.config).toEqual(state.applied);
    expect(state.result?.receivedAt).toBe(Date.parse('2026-09-16T10:30:00Z'));
    expect(recordData(runtime).view.rows).toHaveLength(2);
    expect(source.paged).toHaveBeenCalledWith(
      expect.objectContaining({ pagination: { index: 1, size: 20 } }),
      undefined,
      expect.any(AbortController),
    );
  });

  it('asks a cursor source for the first page with a null cursor', async () => {
    const definition = ordersDefinition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });
    const { runtime, source } = harness({ definition });

    runtime.apply();
    await nextTask();

    expect(source.cursor).toHaveBeenCalledWith(
      expect.objectContaining({ cursor: null }),
      undefined,
      expect.any(AbortController),
    );
    expect(recordData(runtime).view.paging).toEqual({
      mode: 'cursor',
      nextCursor: 'cursor-2',
      hasNext: true,
    });
  });

  it('runs the summary aggregation alongside the page', async () => {
    const { runtime, source } = harness({
      config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
    });

    runtime.apply();
    await nextTask();

    expect(source.aggregate).toHaveBeenCalledTimes(1);
    expect(recordData(runtime).summaries).toMatchObject({
      scope: 'total',
      cells: [{ field: 'amount', value: 30 }],
    });
  });

  it('falls back to page summaries when their query fails', async () => {
    const source = testSource({
      aggregate: vi.fn(() => Promise.reject(new Error('offline'))),
    });
    const { runtime } = harness({
      config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
      source,
    });

    runtime.apply();
    await nextTask();

    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(recordData(runtime).summaries).toMatchObject({
      scope: 'page',
      cells: [{ value: 30 }],
    });
  });

  it('leaves summaries out when the config asks for none', async () => {
    const { runtime, source } = harness();
    runtime.apply();
    await nextTask();

    expect(source.aggregate).not.toHaveBeenCalled();
    expect(recordData(runtime).summaries).toBeNull();
  });

  it('runs an analysis and its totals', async () => {
    const source = testSource();
    const { runtime } = harness({
      config: analysisConfig({ table: { columns: [], totals: true } }),
      source,
    });

    runtime.apply();
    await nextTask();

    expect(source.aggregate).toHaveBeenCalledTimes(2);
    const data = runtime.getSnapshot().result?.data;
    expect(data?.kind).toBe('analysis');
  });

  it('keeps the result when the totals query fails', async () => {
    const aggregate = vi
      .fn()
      .mockResolvedValueOnce([{ warehouse: 'CN', orders: 2 }])
      .mockRejectedValueOnce(new Error('offline'));
    const { runtime } = harness({
      config: analysisConfig({ table: { columns: [], totals: true } }),
      source: testSource({ aggregate }),
    });

    runtime.apply();
    await nextTask();

    expect(runtime.getSnapshot().query.status).toBe('success');
  });

  it('reports a failed query as an issue without losing the applied config', async () => {
    const { runtime } = harness({
      source: testSource({
        paged: vi.fn(() => Promise.reject(new Error('gateway down'))),
      }),
    });

    runtime.apply();
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.query.status).toBe('error');
    expect(state.query.error).toMatchObject({
      code: 'runtime.query.failed',
      params: { reason: 'gateway down' },
    });
    expect(state.result).toBeNull();
  });

  it('reports what the service said when it refused the query', async () => {
    const refused = Object.assign(
      new Error('Request failed with status code 400 for http://svc/paged'),
      {
        exchange: {
          response: { status: 400 },
          extractResult: () =>
            Promise.resolve({
              errorCode: 'IllegalArgument',
              errorMsg: 'HTTP page window[12000] must not exceed 10000.',
            }),
        },
      },
    );
    const { runtime } = harness({
      source: testSource({ paged: vi.fn(() => Promise.reject(refused)) }),
    });

    runtime.apply();
    await nextTask();

    expect(runtime.getSnapshot().query.error).toMatchObject({
      code: 'runtime.query.failed',
      params: { reason: 'HTTP page window[12000] must not exceed 10000.' },
    });
  });

  it('reports a full queue as its own issue', async () => {
    const { runtime } = harness({
      runner: new RequestRunner({
        ...DEFAULT_RUNTIME_LIMITS,
        maxQueuedQueries: 0,
      }),
    });

    runtime.apply();
    await nextTask();

    expect(runtime.getSnapshot().query.error).toMatchObject({
      code: 'runtime.query.queue-full',
    });
  });

  it('describes a rejection that is not an Error', async () => {
    const { runtime } = harness({
      source: testSource({ paged: vi.fn(() => Promise.reject('offline')) }),
    });

    runtime.apply();
    await nextTask();

    expect(runtime.getSnapshot().query.error).toMatchObject({
      params: { reason: 'offline' },
    });
  });

  it('has no page target for a definition with no record capability', () => {
    expect(
      firstPageOf(ordersDefinition({ record: undefined })),
    ).toBeUndefined();
    expect(firstPageOf(ordersDefinition())).toEqual({ index: 1 });
  });

  it('drops the response of a request a newer one replaced', async () => {
    const first = deferred<{ total: number; list: typeof ROWS }>();
    const paged = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockResolvedValue({ total: 1, list: [ROWS[0]] });
    const { runtime } = harness({ source: testSource({ paged }) });

    runtime.apply();
    runtime.refresh();
    first.resolve({ total: 2, list: [...ROWS] });
    await nextTask();

    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(recordData(runtime).view.rows).toHaveLength(1);
  });
});

describe('DataViewRuntime paging and selection', () => {
  it('reads the page it is asked for and clears the selection', async () => {
    const { runtime, source } = harness();
    runtime.apply();
    await nextTask();
    runtime.select(['o-1']);

    runtime.page({ index: 3 });
    await nextTask();

    expect(source.paged).toHaveBeenLastCalledWith(
      expect.objectContaining({ pagination: { index: 3, size: 20 } }),
      undefined,
      expect.any(AbortController),
    );
    expect(runtime.getSnapshot().selection).toEqual([]);
    expect(recordData(runtime).view.paging).toMatchObject({
      mode: 'paged',
      index: 3,
      total: 2,
      hasNext: false,
    });
  });

  it('keeps only keys present in the current result', async () => {
    const { runtime } = harness();
    runtime.apply();
    await nextTask();

    runtime.select(['o-1', 'missing']);

    expect(runtime.getSnapshot().selection).toEqual(['o-1']);
  });

  it('accepts a selection before any result has arrived', () => {
    const { runtime } = harness();
    runtime.select(['o-1']);
    expect(runtime.getSnapshot().selection).toEqual(['o-1']);
  });

  /**
   * A refresh — the button, the timer, the one a command asks for after it
   * wrote — reads the page the reader is on again. It used to go back to
   * page 1, which lost an operator's place in a list they were working
   * through page by page.
   */
  it('reads the page it is on again on refresh', async () => {
    const paged = vi.fn().mockResolvedValue({ total: 45, list: [...ROWS] });
    const { runtime } = harness({ source: testSource({ paged }) });

    runtime.apply();
    await nextTask();
    runtime.page({ index: 3 });
    await nextTask();
    runtime.refresh();
    await nextTask();

    expect(paged).toHaveBeenLastCalledWith(
      expect.objectContaining({ pagination: { index: 3, size: 20 } }),
      undefined,
      expect.any(AbortController),
    );
  });

  it('steps back to the last page there is when the result shrank under it', async () => {
    const paged = vi
      .fn()
      .mockResolvedValueOnce({ total: 45, list: [...ROWS] })
      .mockResolvedValueOnce({ total: 45, list: [...ROWS] })
      // The rows of page 3 left the result: 30 remain, two pages.
      .mockResolvedValueOnce({ total: 30, list: [] })
      .mockResolvedValueOnce({ total: 30, list: [...ROWS] });
    const { runtime } = harness({ source: testSource({ paged }) });

    runtime.apply();
    await nextTask();
    runtime.page({ index: 3 });
    await nextTask();
    runtime.refresh();
    await nextTask();

    expect(paged).toHaveBeenLastCalledWith(
      expect.objectContaining({ pagination: { index: 2, size: 20 } }),
      undefined,
      expect.any(AbortController),
    );
    const { result, query } = runtime.getSnapshot();
    expect(query.status).toBe('success');
    expect(
      result?.data.kind === 'record' && result.data.view.rows,
    ).toHaveLength(ROWS.length);
  });

  it('intersects the selection with the rows a refresh returned', async () => {
    const paged = vi
      .fn()
      .mockResolvedValueOnce({ total: 2, list: [...ROWS] })
      .mockResolvedValueOnce({ total: 1, list: [ROWS[1]] });
    const { runtime } = harness({ source: testSource({ paged }) });

    runtime.apply();
    await nextTask();
    runtime.select(['o-1', 'o-2']);
    runtime.refresh();
    await nextTask();

    expect(runtime.getSnapshot().selection).toEqual(['o-2']);
  });

  it('clears the selection when a new condition is applied', async () => {
    const { runtime } = harness();
    runtime.apply();
    await nextTask();
    runtime.select(['o-1']);

    runtime.edit({ pageSize: 10 });
    runtime.apply();
    await nextTask();

    expect(runtime.getSnapshot().selection).toEqual([]);
  });
});

describe('DataViewRuntime scope filter', () => {
  const scope = {
    op: 'and' as const,
    children: [
      {
        field: 'warehouse',
        operator: `${FilterOperator.EQ}` as const,
        value: 'CN',
      },
    ],
  };

  it('ANDs the injected condition into the executed config only', async () => {
    const { runtime } = harness({ saved: savedInstance });

    const issues = runtime.setScopeFilter(scope);
    await nextTask();

    const state = runtime.getSnapshot();
    expect(issues).toEqual([]);
    expect(state.draft.filter.children).toEqual([]);
    expect(state.applied.filter.children).toEqual([]);
    expect(state.dirty).toBe(false);
    // The scope rides along as a nested group of its own.
    expect(state.result?.config.filter).toEqual({
      op: 'and',
      children: [scope],
    });
  });

  it('refuses a condition the definition does not admit', () => {
    const { runtime, source } = harness();

    const issues = runtime.setScopeFilter({
      op: 'and',
      children: [
        { field: 'nope', operator: `${FilterOperator.EQ}`, value: 'x' },
      ],
    });

    expect(issues.some(found => found.severity === 'error')).toBe(true);
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('drops the condition again when it is cleared', async () => {
    const { runtime } = harness();
    runtime.setScopeFilter(scope);
    await nextTask();

    runtime.setScopeFilter(null);
    await nextTask();

    expect(runtime.getSnapshot().result?.config.filter.children).toEqual([]);
  });
});

describe('DataViewRuntime admission', () => {
  const unknownField: FilterTree = {
    op: 'and',
    children: [{ field: 'nope', operator: `${FilterOperator.EQ}`, value: 'x' }],
  };

  /** A condition every definition here admits, for the scope that takes. */
  const warehouseCN: FilterTree = {
    op: 'and',
    children: [
      { field: 'warehouse', operator: `${FilterOperator.EQ}`, value: 'CN' },
    ],
  };

  it('runs no command on a config that was never admitted', async () => {
    // A stored view whose config the definition now refuses: it waits for a
    // fix, and neither Refresh nor a page turn runs it as it stands.
    const { runtime, source } = harness({
      config: recordConfig({ pageSize: 5000 }),
      saved: savedInstance,
    });

    runtime.apply();
    runtime.refresh();
    runtime.page({ index: 2 });
    await nextTask();

    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'record.pageSize.too-large',
    );
    expect(runtime.getSnapshot().query.status).toBe('idle');
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('survives a stored config with no refresh until it is fixed', async () => {
    // Admission reports the missing part; the runtime must still let the
    // user edit their way out rather than throw on the way to the timer.
    const broken = recordConfig();
    delete (broken as Partial<typeof broken>).refresh;
    const { runtime, source } = harness({
      config: broken,
      saved: savedInstance,
    });

    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'config.refresh.missing',
    );
    expect(() => runtime.edit({ pageSize: 10 })).not.toThrow();
    runtime.edit({ refresh: { interval: null } });
    runtime.apply();
    await nextTask();

    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('runs again once a fixed draft has been applied', async () => {
    const { runtime, source } = harness({
      config: recordConfig({ pageSize: 5000 }),
      saved: savedInstance,
    });

    runtime.edit({ pageSize: 20 });
    runtime.apply();
    await nextTask();
    runtime.refresh();
    await nextTask();

    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  /**
   * A scope in force is part of every later judgement. An edit that
   * recomputed the issues from the draft alone would let `apply` run a merged
   * condition admission never saw — here, one over the node budget.
   */
  it('keeps judging the draft with the injected scope after an edit', async () => {
    const { runtime, source } = harness({
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxFilterNodes: 3 },
      scopeFilter: warehouseCN,
    });
    expect(runtime.getSnapshot().issues).toEqual([]);
    expect(runtime.scopeFilter).toEqual(warehouseCN);

    runtime.edit({
      filter: {
        op: 'and',
        children: [
          { field: 'warehouse', operator: `${FilterOperator.EQ}`, value: 'EU' },
          { field: 'status', operator: `${FilterOperator.EQ}`, value: 'OPEN' },
        ],
      },
    });
    runtime.apply();
    await nextTask();

    // Three nodes of its own is within the budget; the two the scope adds
    // are not, and they are in force whether the editor shows them or not.
    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'filter.tree.too-many-nodes',
    );
    expect(source.paged).not.toHaveBeenCalled();
  });

  /**
   * D17-5: a condition the definition cannot take is the host's and not this
   * view's, so it does not become an error of the config. The view runs as
   * its author saved it, un-narrowed, and says what did not take.
   */
  it('leaves out a scope it refuses and runs the view without it', async () => {
    const { runtime, source } = harness({ scopeFilter: unknownField });

    expect(runtime.getSnapshot().issues).toEqual([]);
    expect(runtime.scopeFilter).toBeNull();
    expect(runtime.refusedScope.map(found => found.code)).toEqual([
      'filter.field.unknown',
    ]);

    runtime.apply();
    await nextTask();

    expect(source.paged).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(runtime.getSnapshot().result?.config)).not.toContain(
      'nope',
    );
  });

  /**
   * A view already waiting to be fixed is not fixed by refusing the host's
   * condition too: only what the condition alone breaks is a refusal.
   */
  it('keeps a view own errors out of what it refuses the host', () => {
    const { runtime } = harness({
      config: recordConfig({ pageSize: 5000 }),
      scopeFilter: unknownField,
    });

    expect(runtime.refusedScope.map(found => found.code)).toEqual([
      'filter.field.unknown',
    ]);
    expect(runtime.getSnapshot().issues.map(found => found.code)).toEqual([
      'record.pageSize.too-large',
    ]);
  });

  /**
   * The same two apart on a later injection. The scope takes — nothing about
   * it is refused — and the config it lands on is still the one that has to
   * be fixed before anything runs.
   */
  it('takes a scope onto a config that is waiting to be fixed, and runs nothing', async () => {
    const { runtime, source } = harness({
      config: recordConfig({ pageSize: 5000 }),
    });

    expect(runtime.setScopeFilter(warehouseCN)).toEqual([]);
    await nextTask();

    expect(runtime.scopeFilter).toEqual(warehouseCN);
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('answers a disposed view with what it last refused', () => {
    const { runtime } = harness({ scopeFilter: unknownField });
    runtime.dispose();

    expect(runtime.setScopeFilter(null)).toBe(runtime.refusedScope);
    expect(runtime.refusedScope).toHaveLength(1);
  });

  /**
   * A host that builds its condition in render hands over a new object every
   * time. The answer it gets back has to be the same object while it says the
   * same thing, or a screen bound to it never stops re-rendering.
   */
  it('keeps the refusal it has while the answer says the same', () => {
    const { runtime } = harness({ scopeFilter: unknownField });
    const first = runtime.refusedScope;
    const listener = vi.fn();
    runtime.subscribe(listener);

    runtime.setScopeFilter({
      ...unknownField,
      children: [...unknownField.children],
    });

    expect(runtime.refusedScope).toBe(first);
    expect(listener).not.toHaveBeenCalled();
  });

  it('reports a stored filter that lost its shape even under a scope', async () => {
    const scope: FilterTree = {
      op: 'and',
      children: [
        { field: 'warehouse', operator: `${FilterOperator.EQ}`, value: 'CN' },
      ],
    };
    const { runtime, source } = harness({
      config: recordConfig({
        filter: { op: 'and', children: [null as never] },
      }),
      saved: savedInstance,
      scopeFilter: scope,
    });

    runtime.apply();
    await nextTask();

    // The malformed entry is neither dropped by the merge nor run around.
    expect(runtime.getSnapshot().issues).toContainEqual(
      expect.objectContaining({
        code: 'filter.node.invalid',
        path: ['children', 0],
      }),
    );
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('leaves a root that is not a group to admission, scope or not', () => {
    const { runtime } = harness({
      config: recordConfig({
        filter: { field: 'warehouse', operator: 'EQ', value: 'CN' } as never,
      }),
      scopeFilter: {
        op: 'and',
        children: [{ field: 'status', operator: 'EQ', value: 'open' }],
      },
    });

    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'config.filter.invalid',
    );
  });

  it('addresses the draft own nodes unchanged when its root is not "all of"', () => {
    // A root that is `or` rides in the merged tree as its first child; a
    // finding on the draft still reads as a path into the draft.
    const { runtime } = harness({
      config: recordConfig({
        filterMode: 'advanced',
        filter: {
          op: 'or',
          children: [
            { field: 'warehouse', operator: 'EQ', value: 'CN' },
            { field: 'warehouse', operator: 'EQ', value: 'EU' },
          ],
        },
      }),
      scopeFilter: {
        op: 'and',
        children: [{ field: 'status', operator: 'EQ', value: 'open' }],
      },
    });

    expect(runtime.getSnapshot().issues).toContainEqual(
      expect.objectContaining({
        code: 'filter.field.duplicate-in-group',
        path: ['children', 1],
      }),
    );
  });

  it('rejudges the draft when the scope is cleared or replaced', async () => {
    const { runtime, source } = harness({
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxFilterNodes: 3 },
      config: recordConfig({
        filter: {
          op: 'and',
          children: [
            {
              field: 'warehouse',
              operator: `${FilterOperator.EQ}`,
              value: 'EU',
            },
            {
              field: 'status',
              operator: `${FilterOperator.EQ}`,
              value: 'OPEN',
            },
          ],
        },
      }),
      scopeFilter: warehouseCN,
    });
    // Three of its own plus two: refused, so the draft is judged without it.
    expect(runtime.refusedScope.map(found => found.code)).toEqual([
      'filter.tree.too-many-nodes',
    ]);
    expect(runtime.getSnapshot().issues).toEqual([]);

    // Clearing it takes the refusal with it: what is asked for is in force.
    expect(runtime.setScopeFilter(null)).toEqual([]);
    await nextTask();

    expect(runtime.refusedScope).toEqual([]);
    expect(runtime.getSnapshot().issues).toEqual([]);
    runtime.apply();
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('addresses the draft own nodes unchanged when a scope is in force', () => {
    const scope: FilterTree = {
      op: 'and',
      children: [
        { field: 'warehouse', operator: `${FilterOperator.EQ}`, value: 'CN' },
      ],
    };
    const { runtime } = harness({ scopeFilter: scope });

    runtime.edit({ filter: unknownField });

    // The scope is appended after the draft's conditions, so the path into
    // the draft's tree is what an editor expects: its first child.
    expect(runtime.getSnapshot().issues).toMatchObject([
      { code: 'filter.field.unknown', path: ['children', 0] },
    ]);
  });
});

describe('DataViewRuntime revert', () => {
  it('does nothing on a view that was never saved', async () => {
    const { runtime, source } = harness();
    runtime.edit({ pageSize: 10 });

    runtime.revert();
    await nextTask();

    // There is no baseline to go back to, so the edits are all there is.
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(10);
    expect(runtime.getSnapshot().dirty).toBe(true);
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('restores the saved config and puts it back in force', async () => {
    const { runtime, source } = harness({ saved: savedInstance });
    runtime.apply();
    await nextTask();
    runtime.edit({ pageSize: 10 });
    runtime.apply();
    await nextTask();

    runtime.revert();
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.draft).toEqual(savedInstance.config);
    expect(state.applied).toEqual(savedInstance.config);
    expect(state.dirty).toBe(false);
    // The rows on screen answered the edited config; leaving them there
    // would show them under the saved config's name.
    expect(source.paged).toHaveBeenCalledTimes(3);
  });

  it('runs nothing when the edits were never applied', async () => {
    const { runtime, source } = harness({ saved: savedInstance });
    runtime.apply();
    await nextTask();
    runtime.edit({ pageSize: 10 });

    runtime.revert();
    await nextTask();

    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(20);
    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('restores a saved config the definition now refuses without running it', async () => {
    const stored: ViewInstance = {
      ...savedInstance,
      config: recordConfig({ pageSize: 5000 }),
    };
    const { runtime, source } = harness({
      config: stored.config as DataViewConfig,
      saved: stored,
    });
    runtime.edit({ pageSize: 20 });
    runtime.apply();
    await nextTask();

    runtime.revert();
    await nextTask();

    // Refusing to revert would strand the user on edits they asked to be
    // rid of; the draft goes back and waits to be fixed.
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(
      5000,
    );
    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'record.pageSize.too-large',
    );
    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('is a no-op once disposed', () => {
    const { runtime } = harness({ saved: savedInstance });
    runtime.edit({ pageSize: 10 });
    runtime.dispose();

    runtime.revert();

    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(10);
  });
});

describe('DataViewRuntime auto refresh', () => {
  const refreshing = recordConfig({ refresh: { interval: 30 } });

  it('re-runs the applied config when the interval elapses', async () => {
    const { runtime, source, clock } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();

    clock.advance(30_000);
    await nextTask();

    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  it('holds the timer while an editor has focus', async () => {
    const { runtime, source, clock } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();

    runtime.setEditing(true);
    clock.advance(30_000);
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);

    runtime.setEditing(false);
    clock.advance(30_000);
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  it('holds the timer while the page is hidden', async () => {
    const { runtime, source, clock } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();

    clock.setVisible(false);
    expect(clock.timers).toBe(0);
    clock.advance(30_000);
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);

    clock.setVisible(true);
    clock.advance(30_000);
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  it('holds the timer while a request is still in flight', () => {
    const gate = deferred<{ total: number; list: typeof ROWS }>();
    const { runtime, clock } = harness({
      config: refreshing,
      source: testSource({ paged: vi.fn(() => gate.promise) }),
    });

    runtime.apply();

    expect(clock.timers).toBe(0);
  });

  it('holds the timer while the draft has an error', async () => {
    const { runtime, clock } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();
    expect(clock.timers).toBe(1);

    runtime.edit({ pageSize: 0 });

    expect(clock.timers).toBe(0);
  });

  it('keeps one timer however many times the state changes', async () => {
    const { runtime, clock } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();

    runtime.select(['o-1']);
    runtime.select([]);

    expect(clock.timers).toBe(1);
  });

  /**
   * Picked rows are rows someone is about to act on; a refresh can move them
   * to another page or out of the result. The clock waits until the
   * selection is let go of.
   */
  it('holds the timer while rows are selected', async () => {
    const { runtime } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();
    expect(runtime.getSnapshot().nextRefreshAt).not.toBeNull();

    runtime.select(['o-1']);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();

    runtime.select([]);
    expect(runtime.getSnapshot().nextRefreshAt).not.toBeNull();
  });

  it('runs no timer when the config asks for none', async () => {
    const { runtime, clock } = harness();
    runtime.apply();
    await nextTask();
    expect(clock.timers).toBe(0);
  });

  /**
   * The due time is the timer's own, published so a control can count down
   * to it instead of starting a second clock beside it. It is read off the
   * environment, which is what keeps a countdown and a refresh from
   * disagreeing about when the data moves.
   */
  it('publishes when the next refresh is due, on the injected clock', async () => {
    const { runtime, clock } = harness({ config: refreshing });
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();

    runtime.apply();
    await nextTask();

    expect(runtime.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 30_000);

    // And it moves with the timer: the refresh fires, the next one is armed
    // from where the answer landed.
    clock.advance(30_000);
    await nextTask();
    expect(runtime.getSnapshot().nextRefreshAt).toBe(
      NOW.getTime() + 30_000 + 30_000,
    );
  });

  /**
   * Every reason the timer is held is a reason there is nothing to count to:
   * one field, so a control cannot show a countdown to a refresh that is not
   * scheduled. A request in flight is one of them, and the next due time is
   * only known once it lands.
   */
  it('has nothing due while the timer is held, and again once it is armed', async () => {
    const gate = deferred<{ total: number; list: typeof ROWS }>();
    const paged = vi.fn(() => gate.promise);
    const { runtime, clock } = harness({
      config: refreshing,
      source: testSource({ paged }),
    });

    runtime.apply();
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();

    gate.resolve({ total: ROWS.length, list: ROWS });
    await nextTask();
    expect(runtime.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 30_000);

    // The other three hold it the same way, and each one clears the due time
    // rather than freezing it at a number nothing is counting to.
    runtime.setEditing(true);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();
    runtime.setEditing(false);
    expect(runtime.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 30_000);

    clock.setVisible(false);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();
    clock.setVisible(true);
    expect(runtime.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 30_000);

    runtime.edit({ pageSize: 0 });
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();
  });

  /**
   * Hiding the page is no state change of this runtime's own, so nothing but
   * this tells a subscriber the countdown it is drawing has stopped.
   */
  it('notifies when visibility moves the due time', async () => {
    const { runtime, clock } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();
    const listener = vi.fn();
    runtime.subscribe(listener);

    clock.setVisible(false);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();

    clock.setVisible(true);
    expect(listener).toHaveBeenCalledTimes(2);
  });

  /**
   * A refresh by hand makes the data fresh now, so the wait starts again
   * from now: the runtime re-arms on the answer rather than letting the old
   * timer fire seconds after the user pressed the button themselves.
   */
  it('restarts the wait after a refresh the user asked for', async () => {
    const { runtime, clock } = harness({ config: refreshing });
    runtime.apply();
    await nextTask();

    clock.advance(20_000);
    runtime.refresh();
    await nextTask();

    expect(runtime.getSnapshot().nextRefreshAt).toBe(
      NOW.getTime() + 20_000 + 30_000,
    );
  });
});

describe('DataViewRuntime lifecycle', () => {
  it('advances the baseline when the engine reports a saved instance', () => {
    const { runtime } = harness();

    runtime.markSaved({ ...savedInstance, title: 'Saved' });
    const state = runtime.getSnapshot();

    expect(state.saved?.revision).toBe('1');
    expect(state.title).toBe('Saved');
    expect(state.dirty).toBe(false);
  });

  it('adopts the store state, replacing the draft', () => {
    const { runtime } = harness({ saved: savedInstance });
    runtime.edit({ pageSize: 99 });

    runtime.adoptSaved({
      ...savedInstance,
      revision: '4',
      config: recordConfig({ pageSize: 50 }),
    });

    const state = runtime.getSnapshot();
    expect(requireRecordConfig(state.draft).pageSize).toBe(50);
    expect(state.dirty).toBe(false);
    expect(state.saved?.revision).toBe('4');
  });

  it('carries the pending write outcome the engine hands it', () => {
    const { runtime } = harness();
    const write = {
      kind: 'unknown' as const,
      requestId: 'r-1',
      payload: {
        action: 'delete' as const,
        id: 'orders-1',
        definitionId: 'orders',
        revision: '1',
      },
    };

    runtime.setWrite(write);
    expect(runtime.getSnapshot().write).toEqual(write);

    runtime.setWrite(null);
    expect(runtime.getSnapshot().write).toBeNull();
  });

  it('stops timers, listeners and commands once disposed', async () => {
    const { runtime, source, clock } = harness({
      config: recordConfig({ refresh: { interval: 30 } }),
    });
    const listener = vi.fn();
    runtime.subscribe(listener);
    runtime.apply();
    await nextTask();
    listener.mockClear();

    runtime.dispose();
    runtime.dispose();
    runtime.edit({ pageSize: 10 });
    runtime.apply();
    runtime.refresh();
    runtime.page({ index: 2 });
    runtime.select(['o-1']);
    runtime.setEditing(true);
    runtime.markSaved(savedInstance);
    runtime.adoptSaved(savedInstance);
    runtime.setWrite(null);
    clock.advance(60_000);
    await nextTask();

    expect(clock.timers).toBe(0);
    // Disposal is the last notification, so a subscriber reading `disposed`
    // learns of it at once; nothing after it notifies again.
    expect(listener).toHaveBeenCalledTimes(1);
    expect(source.paged).toHaveBeenCalledTimes(1);
    expect(runtime.setScopeFilter(null)).toEqual([]);
  });
});

describe('RequestRunner', () => {
  /**
   * A task that throws before returning a promise would otherwise unwind
   * through `pump` with its slot still counted, and the scheduler would run
   * one fewer query for the rest of the engine's life — a leak that gets
   * worse with every occurrence and never recovers.
   */
  it('keeps its slot when a task throws synchronously', async () => {
    const runner = new RequestRunner({
      ...DEFAULT_RUNTIME_LIMITS,
      maxConcurrentQueries: 1,
    });

    await expect(
      runner.run('a', () => {
        throw new Error('bad source');
      }),
    ).rejects.toThrow('bad source');

    expect(runner.active).toBe(0);
    // The slot is free, so the next request still runs.
    await expect(runner.run('b', () => Promise.resolve('ok'))).resolves.toBe(
      'ok',
    );
  });
});

describe('ViewSource', () => {
  it('is satisfied by a Wow query client as it is', () => {
    // The assignment is the assertion: three of `QueryApi`'s methods, with the
    // query types the kernels compile to.
    const api = {} as QueryApi<RecordData>;
    const source: ViewSource = api;

    expect(source).toBe(api);
  });
});

describe('defaultRuntimeEnvironment', () => {
  it('uses the ambient clock, timers and an always-visible page', async () => {
    const environment = defaultRuntimeEnvironment();
    const fired = deferred<string>();

    expect(environment.visibility.isVisible()).toBe(true);
    expect(environment.visibility.subscribe(() => {})).toBeInstanceOf(Function);
    expect(environment.now()).toBeInstanceOf(Date);
    expect(environment.timeZone.length).toBeGreaterThan(0);

    const handle = environment.setTimeout(() => fired.resolve('fired'), 0);
    await expect(fired.promise).resolves.toBe('fired');
    environment.clearTimeout(handle);
  });

  it('takes the overrides a host provides', () => {
    const environment = defaultRuntimeEnvironment({
      timeZone: 'Asia/Shanghai',
    });
    expect(environment.timeZone).toBe('Asia/Shanghai');
  });

  it('clears a timer before it fires', async () => {
    const environment = defaultRuntimeEnvironment();
    const callback = vi.fn();

    environment.clearTimeout(environment.setTimeout(callback, 0));
    // Timers of equal delay fire in the order they were set, so the cleared
    // one would have fired before this turn ends.
    await nextTask();

    expect(callback).not.toHaveBeenCalled();
  });
});
