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

import { FilterOperator, type QueryApi } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  DataViewRuntime,
  DEFAULT_RUNTIME_LIMITS,
  RequestRunner,
  defaultRuntimeEnvironment,
  firstPageOf,
  type DataViewConfig,
  type DataViewDefinition,
  type ProjectedRecord,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  analysisConfig,
  deferred,
  ordersDefinition,
  recordConfig,
  ROWS,
  testEnvironment,
  testSource,
  type TestEnvironment,
} from './fixtures.js';

/** Lets every queued microtask run, which is when a query has landed. */
function flush(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

interface Harness {
  runtime: DataViewRuntime;
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
  } = {},
): Harness {
  const clock = testEnvironment();
  const source = options.source ?? testSource();
  const definition = options.definition ?? ordersDefinition();
  const runtime = new DataViewRuntime({
    id: 'runtime-1',
    definition,
    config: options.config ?? recordConfig(),
    title: 'Mine',
    scope: 'personal',
    saved: options.saved ?? null,
    kinds: builtinFieldKinds,
    limits: DEFAULT_RUNTIME_LIMITS,
    environment: clock.environment,
    source,
    runner: options.runner ?? new RequestRunner(),
  });
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

    expect(state.draft.pageSize).toBe(0);
    expect(state.applied.pageSize).toBe(20);
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
  it('queries the first page and keeps the config that produced the result', async () => {
    const { runtime, source } = harness();

    runtime.apply();
    expect(runtime.getSnapshot().query.status).toBe('loading');
    await flush();

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
    await flush();

    expect(source.cursor).toHaveBeenCalledWith(
      expect.objectContaining({ cursor: null }),
      undefined,
      expect.any(AbortController),
    );
    expect(recordData(runtime).view.paging).toEqual({
      mode: 'cursor',
      nextCursor: 'cursor-2',
    });
  });

  it('runs the summary aggregation alongside the page', async () => {
    const { runtime, source } = harness({
      config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
    });

    runtime.apply();
    await flush();

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
    await flush();

    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(recordData(runtime).summaries).toMatchObject({
      scope: 'page',
      cells: [{ value: 30 }],
    });
  });

  it('leaves summaries out when the config asks for none', async () => {
    const { runtime, source } = harness();
    runtime.apply();
    await flush();

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
    await flush();

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
    await flush();

    expect(runtime.getSnapshot().query.status).toBe('success');
  });

  it('reports a failed query as an issue without losing the applied config', async () => {
    const { runtime } = harness({
      source: testSource({
        paged: vi.fn(() => Promise.reject(new Error('gateway down'))),
      }),
    });

    runtime.apply();
    await flush();

    const state = runtime.getSnapshot();
    expect(state.query.status).toBe('error');
    expect(state.query.error).toMatchObject({
      code: 'runtime.query.failed',
      params: { reason: 'gateway down' },
    });
    expect(state.result).toBeNull();
  });

  it('reports a full queue as its own issue', async () => {
    const { runtime } = harness({
      runner: new RequestRunner({
        ...DEFAULT_RUNTIME_LIMITS,
        maxQueuedQueries: 0,
      }),
    });

    runtime.apply();
    await flush();

    expect(runtime.getSnapshot().query.error).toMatchObject({
      code: 'runtime.query.queue-full',
    });
  });

  it('describes a rejection that is not an Error', async () => {
    const { runtime } = harness({
      source: testSource({ paged: vi.fn(() => Promise.reject('offline')) }),
    });

    runtime.apply();
    await flush();

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
    await flush();

    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(recordData(runtime).view.rows).toHaveLength(1);
  });
});

describe('DataViewRuntime paging and selection', () => {
  it('reads the page it is asked for and clears the selection', async () => {
    const { runtime, source } = harness();
    runtime.apply();
    await flush();
    runtime.select(['o-1']);

    runtime.page({ index: 3 });
    await flush();

    expect(source.paged).toHaveBeenLastCalledWith(
      expect.objectContaining({ pagination: { index: 3, size: 20 } }),
      undefined,
      expect.any(AbortController),
    );
    expect(runtime.getSnapshot().selection).toEqual([]);
    expect(recordData(runtime).view.paging).toEqual({
      mode: 'paged',
      index: 3,
      total: 2,
    });
  });

  it('keeps only keys present in the current result', async () => {
    const { runtime } = harness();
    runtime.apply();
    await flush();

    runtime.select(['o-1', 'missing']);

    expect(runtime.getSnapshot().selection).toEqual(['o-1']);
  });

  it('accepts a selection before any result has arrived', () => {
    const { runtime } = harness();
    runtime.select(['o-1']);
    expect(runtime.getSnapshot().selection).toEqual(['o-1']);
  });

  it('returns to the first page on refresh and keeps the rows that survive', async () => {
    const paged = vi
      .fn()
      .mockResolvedValueOnce({ total: 2, list: [...ROWS] })
      .mockResolvedValueOnce({ total: 1, list: [ROWS[1]] });
    const { runtime } = harness({ source: testSource({ paged }) });

    runtime.apply();
    await flush();
    runtime.select(['o-1', 'o-2']);
    runtime.page({ index: 2 });
    await flush();
    runtime.refresh();
    await flush();

    expect(paged).toHaveBeenLastCalledWith(
      expect.objectContaining({ pagination: { index: 1, size: 20 } }),
      undefined,
      expect.any(AbortController),
    );
    expect(runtime.getSnapshot().selection).toEqual([]);
  });

  it('intersects the selection with the rows a refresh returned', async () => {
    const paged = vi
      .fn()
      .mockResolvedValueOnce({ total: 2, list: [...ROWS] })
      .mockResolvedValueOnce({ total: 1, list: [ROWS[1]] });
    const { runtime } = harness({ source: testSource({ paged }) });

    runtime.apply();
    await flush();
    runtime.select(['o-1', 'o-2']);
    runtime.refresh();
    await flush();

    expect(runtime.getSnapshot().selection).toEqual(['o-2']);
  });

  it('clears the selection when a new condition is applied', async () => {
    const { runtime } = harness();
    runtime.apply();
    await flush();
    runtime.select(['o-1']);

    runtime.edit({ pageSize: 10 });
    runtime.apply();
    await flush();

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
    await flush();

    const state = runtime.getSnapshot();
    expect(issues).toEqual([]);
    expect(state.draft.filter.children).toEqual([]);
    expect(state.applied.filter.children).toEqual([]);
    expect(state.dirty).toBe(false);
    expect(state.result?.config.filter).toEqual(scope);
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
    await flush();

    runtime.setScopeFilter(null);
    await flush();

    expect(runtime.getSnapshot().result?.config.filter.children).toEqual([]);
  });
});

describe('DataViewRuntime auto refresh', () => {
  const refreshing = recordConfig({ refresh: { interval: 30 } });

  it('re-runs the applied config when the interval elapses', async () => {
    const { runtime, source, clock } = harness({ config: refreshing });
    runtime.apply();
    await flush();

    clock.advance(30_000);
    await flush();

    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  it('holds the timer while an editor has focus', async () => {
    const { runtime, source, clock } = harness({ config: refreshing });
    runtime.apply();
    await flush();

    runtime.setEditing(true);
    clock.advance(30_000);
    await flush();
    expect(source.paged).toHaveBeenCalledTimes(1);

    runtime.setEditing(false);
    clock.advance(30_000);
    await flush();
    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  it('holds the timer while the page is hidden', async () => {
    const { runtime, source, clock } = harness({ config: refreshing });
    runtime.apply();
    await flush();

    clock.setVisible(false);
    expect(clock.timers).toBe(0);
    clock.advance(30_000);
    await flush();
    expect(source.paged).toHaveBeenCalledTimes(1);

    clock.setVisible(true);
    clock.advance(30_000);
    await flush();
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
    await flush();
    expect(clock.timers).toBe(1);

    runtime.edit({ pageSize: 0 });

    expect(clock.timers).toBe(0);
  });

  it('keeps one timer however many times the state changes', async () => {
    const { runtime, clock } = harness({ config: refreshing });
    runtime.apply();
    await flush();

    runtime.select(['o-1']);
    runtime.select([]);

    expect(clock.timers).toBe(1);
  });

  it('runs no timer when the config asks for none', async () => {
    const { runtime, clock } = harness();
    runtime.apply();
    await flush();
    expect(clock.timers).toBe(0);
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
    expect(state.draft.pageSize).toBe(50);
    expect(state.dirty).toBe(false);
    expect(state.saved?.revision).toBe('4');
  });

  it('carries the pending write outcome the engine hands it', () => {
    const { runtime } = harness();
    const write = {
      kind: 'unknown' as const,
      requestId: 'r-1',
      payload: { action: 'delete' as const, id: 'orders-1', revision: '1' },
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
    await flush();
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
    await flush();

    expect(clock.timers).toBe(0);
    expect(listener).not.toHaveBeenCalled();
    expect(source.paged).toHaveBeenCalledTimes(1);
    expect(runtime.setScopeFilter(null)).toEqual([]);
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
    await new Promise(resolve => setTimeout(resolve, 1));

    expect(callback).not.toHaveBeenCalled();
  });
});
