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
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PagedList } from '@ahoo-wang/wow-client';
import {
  defaultRuntimeEnvironment,
  isViewWriteError,
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type Issue,
  type RecordData,
  type RecordViewRuntime,
  type RuntimeEnvironment,
  type ViewErrorEvent,
  type ViewSource,
} from '../src/index.js';
import {
  useOpenView,
  useRecordExport,
  useRecordTable,
} from '../src/react/index.js';
import { DataWorkbench, RenderBoundary, ViewSurface } from '../src/ui/index.js';
import type { RenderFailure } from '../src/ui/index.js';
import { EChart } from '../src/ui/charts/EChart.js';
import { watchSize } from '../src/ui/charts/sizes.js';
import { FailureSink } from '../src/ui/failureSink.js';
import {
  deferred,
  mine,
  ordersDefinition,
  recordConfig,
  ROWS,
  testEnvironment,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/**
 * An engine whose environment tells `events` of every failure (D40), over a
 * store holding `mine` and whatever source a test hands it.
 */
function watched(
  options: {
    source?: ViewSource;
    store?: MemoryViewStore;
    environment?: RuntimeEnvironment;
  } = {},
) {
  const events: ViewErrorEvent[] = [];
  const issues: Issue[] = [];
  const store = options.store ?? new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => options.source ?? testSource(),
    environment: {
      ...(options.environment ?? testEnvironment().environment),
      onError: event => events.push(event),
    },
    onIssue: found => issues.push(found),
  });
  return { engine, store, events, issues };
}

async function opened(engine: ViewEngine): Promise<RecordViewRuntime> {
  const runtime = (await engine.open('orders-1')) as RecordViewRuntime;
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().query.status).not.toBe('loading'),
  );
  return runtime;
}

describe('query failures reach onError', () => {
  it('tells the host once of the view’s query, naming the view', async () => {
    const failure = new Error('service down');
    const { engine, events } = watched({
      source: testSource({ paged: vi.fn(() => Promise.reject(failure)) }),
    });

    const runtime = await opened(engine);

    expect(runtime.getSnapshot().query.status).toBe('error');
    expect(events).toEqual([
      {
        kind: 'query',
        error: failure,
        context: {
          operation: 'query',
          definitionId: 'orders',
          instanceId: 'orders-1',
          runtimeId: runtime.id,
        },
      },
    ]);
  });

  it('says nothing of a query the next one took the place of', async () => {
    const first = deferred<PagedList<RecordData>>();
    const second = deferred<PagedList<RecordData>>();
    const answers = [first, second];
    const { engine, events } = watched({
      source: testSource({ paged: vi.fn(() => answers.shift()!.promise) }),
    });
    const runtime = (await engine.open('orders-1')) as RecordViewRuntime;

    runtime.refresh();
    first.reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
    second.reject(new Error('the one that counts'));
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('error'),
    );

    expect(events.map(event => (event.error as Error).message)).toEqual([
      'the one that counts',
    ]);
  });

  it('tells the host of a summary query the row fell back from', async () => {
    const failure = new Error('no totals today');
    const store = new MemoryViewStore({
      instances: [
        {
          ...mine,
          config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
        },
      ],
    });
    const { engine, events } = watched({
      store,
      source: testSource({ aggregate: vi.fn(() => Promise.reject(failure)) }),
    });

    const runtime = await opened(engine);

    // The page answered; only the summary scope was lost.
    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(events).toEqual([
      {
        kind: 'query',
        error: failure,
        context: expect.objectContaining({
          operation: 'summaries',
          runtimeId: runtime.id,
        }),
      },
    ]);
  });

  it('carries on when the host’s hook throws, and logs nothing', async () => {
    const quiet = [
      vi.spyOn(console, 'error'),
      vi.spyOn(console, 'warn'),
      vi.spyOn(console, 'log'),
    ];
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () =>
        testSource({ paged: vi.fn(() => Promise.reject(new Error('down'))) }),
      environment: {
        ...testEnvironment().environment,
        onError: () => {
          throw new Error('the monitor is down too');
        },
      },
    });

    const runtime = await opened(engine);

    expect(runtime.getSnapshot().query).toMatchObject({
      status: 'error',
      error: { code: 'runtime.query.failed' },
    });
    for (const spy of quiet) expect(spy).not.toHaveBeenCalled();
  });

  it('leaves no rejection behind when the host’s hook is async and fails', async () => {
    const told = vi.fn(() => Promise.reject(new Error('monitor offline')));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () =>
        testSource({ paged: vi.fn(() => Promise.reject(new Error('down'))) }),
      environment: { ...testEnvironment().environment, onError: told },
    });

    const runtime = await opened(engine);
    // An unhandled rejection would fail the run; a turn lets one surface.
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(runtime.getSnapshot().query.status).toBe('error');
    expect(told).toHaveBeenCalledOnce();
  });

  it('logs nothing anywhere when the host gave no hook', async () => {
    const quiet = [
      vi.spyOn(console, 'error'),
      vi.spyOn(console, 'warn'),
      vi.spyOn(console, 'log'),
    ];
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () =>
        testSource({ paged: vi.fn(() => Promise.reject(new Error('down'))) }),
      environment: testEnvironment().environment,
    });

    const runtime = await opened(engine);

    expect(runtime.getSnapshot().query.status).toBe('error');
    for (const spy of quiet) expect(spy).not.toHaveBeenCalled();
  });
});

describe('store failures reach onError', () => {
  it('tells the host once of a list the store could not read', async () => {
    const { engine, store, events, issues } = watched();
    const failure = new ViewStoreError('UNAVAILABLE', 'store down');
    vi.spyOn(store, 'list').mockRejectedValue(failure);

    const listing = await engine.list('orders');

    expect(listing.failed?.code).toBe('view.list.failed.unavailable');
    expect(events).toEqual([
      {
        kind: 'store',
        error: failure,
        context: { operation: 'list', definitionId: 'orders' },
      },
    ]);
    // A failure is said once, through `onError`; `onIssue` is for findings.
    expect(issues).toEqual([]);
  });

  it('tells the host once of a view the store could not open', async () => {
    const { engine, store, events } = watched();
    const failure = new ViewStoreError('NOT_FOUND', 'gone');
    vi.spyOn(store, 'get').mockRejectedValue(failure);

    await expect(engine.open('orders-1')).rejects.toBe(failure);

    expect(events).toEqual([
      {
        kind: 'store',
        error: failure,
        context: { operation: 'get', instanceId: 'orders-1' },
      },
    ]);
  });

  it('tells the host of each attempt of a write, under one requestId', async () => {
    const { engine, store, events } = watched();
    const runtime = await opened(engine);
    const failure = new ViewStoreError('UNAVAILABLE', 'try later');
    vi.spyOn(store, 'save').mockRejectedValue(failure);
    runtime.edit({ pageSize: 50 });

    const first = await engine.save(runtime).catch((error: unknown) => error);
    expect(isViewWriteError(first)).toBe(true);
    await engine.retryWrite(runtime).catch(() => undefined);

    expect(events).toHaveLength(2);
    expect(events[0]).toEqual({
      kind: 'store',
      error: failure,
      context: {
        operation: 'save',
        instanceId: 'orders-1',
        requestId: expect.any(String),
      },
    });
    // A retry is the same logical write: the host can group the two.
    expect(events[1].context.requestId).toBe(events[0].context.requestId);
  });
});

describe('export failures reach onError', () => {
  /** Answers the view's own query, then fails every page an export asks. */
  function failingExport(failure: Error): ViewSource {
    let opened = false;
    return testSource({
      paged: vi.fn(() => {
        if (opened) return Promise.reject(failure);
        opened = true;
        return Promise.resolve({ total: 2, list: [...ROWS] });
      }),
    });
  }

  it('tells the host once of rows an export could not fetch', async () => {
    const failure = new Error('page 1 refused');
    const { engine, events } = watched({ source: failingExport(failure) });
    const runtime = await opened(engine);

    await expect(runtime.exportRows()).rejects.toBe(failure);

    expect(events).toEqual([
      {
        kind: 'export',
        error: failure,
        context: {
          operation: 'fetch',
          definitionId: 'orders',
          instanceId: 'orders-1',
          runtimeId: runtime.id,
        },
      },
    ]);
  });

  it('says nothing of an export the user cancelled', async () => {
    const { engine, events } = watched({
      source: failingExport(new Error('never asked')),
    });
    const runtime = await opened(engine);
    const cancel = new AbortController();
    cancel.abort();

    await expect(
      runtime.exportRows({ signal: cancel.signal }),
    ).rejects.toBeDefined();

    expect(events).toEqual([]);
  });

  it('tells the host once of a file that could not be handed over', async () => {
    const { engine, events } = watched({
      environment: defaultRuntimeEnvironment(),
    });
    const failure = new Error('disk full');
    const { result } = renderHook(() => {
      const view = useOpenView(engine, 'orders-1');
      const runtime = view.runtime as RecordViewRuntime | null;
      const table = useRecordTable(runtime);
      return {
        runtime,
        table,
        exporter: useRecordExport(runtime, table, {
          deliver: () => {
            throw failure;
          },
        }),
      };
    });
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    act(() => result.current.table.toggle('o-1'));
    act(() => result.current.exporter.run('selected', 'Mine.csv'));
    await waitFor(() => expect(result.current.exporter.error).not.toBeNull());

    expect(events).toEqual([
      {
        kind: 'export',
        error: failure,
        context: {
          operation: 'deliver',
          definitionId: 'orders',
          instanceId: 'orders-1',
          runtimeId: result.current.runtime!.id,
        },
      },
    ]);
  });
});

describe('render and chart failures reach onError', () => {
  it('tells the host once of a part that failed to draw, and onRenderFailure too', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const failure = new Error('row action failed');
    const { engine, events } = watched({
      environment: defaultRuntimeEnvironment(),
    });
    const failures: RenderFailure[] = [];

    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        onRenderFailure={caught => failures.push(caught)}
        record={{
          actions: {
            row: () => {
              throw failure;
            },
          },
        }}
      />,
    );
    await screen.findByRole('alert');

    expect(events).toHaveLength(1);
    expect(events[0]).toEqual({
      kind: 'render',
      error: failure,
      context: {
        operation: 'render',
        boundary: 'result',
        componentStack: expect.any(String),
        definitionId: 'orders',
        instanceId: 'orders-1',
        runtimeId: expect.any(String),
      },
    });
    // The surface's own handler hears of the same failure, once.
    expect(failures).toHaveLength(1);
    expect(failures[0].error).toBe(failure);
  });

  it('tells the host of a chart the library threw drawing, as a chart', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const failure = new Error('bad option');
    const events: ViewErrorEvent[] = [];
    const failures: RenderFailure[] = [];

    render(
      <ViewSurface>
        <FailureSink
          environment={{
            ...testEnvironment().environment,
            onError: event => events.push(event),
          }}
          runtime={null}
        >
          <RenderBoundary
            name="result"
            onFailure={caught => failures.push(caught)}
          >
            <EChart
              name="orders by warehouse"
              option={() => {
                throw failure;
              }}
            />
          </RenderBoundary>
        </FailureSink>
      </ViewSurface>,
    );

    expect(
      await screen.findByText('This part could not be drawn'),
    ).toBeDefined();
    expect(screen.getByText('bad option')).toBeDefined();
    expect(events).toEqual([
      {
        kind: 'chart',
        error: failure,
        context: {
          operation: 'draw',
          boundary: 'result',
          componentStack: expect.any(String),
        },
      },
    ]);
    expect(failures.map(caught => caught.error)).toEqual([failure]);
  });

  it('keeps a chart that throws in the size observer from the rest of its frame', () => {
    let fire: ResizeObserverCallback | undefined;
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: ResizeObserverCallback) {
          fire = callback;
        }
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    const broken = document.createElement('div');
    const fine = document.createElement('div');
    const created = new Error('init threw');
    const drawn = new Error('setOption threw');
    const failed: unknown[] = [];
    const drawings: string[] = [];
    let first = true;
    const stops = [
      watchSize(
        broken,
        error => failed.push(error),
        () => {
          if (first) {
            first = false;
            throw created;
          }
          return () => {
            throw drawn;
          };
        },
      ),
      watchSize(fine, undefined, () => () => drawings.push('fine')),
    ];
    const frame = (...targets: Element[]) =>
      fire!(
        targets.map(target => ({
          target,
          contentRect: { width: 100, height: 100 },
        })) as unknown as ResizeObserverEntry[],
        {} as ResizeObserver,
      );

    frame(broken, fine);
    frame(broken, fine);

    // Each throw went to the chart that threw it, and the other chart in the
    // same frame drew both times.
    expect(failed).toEqual([created, drawn]);
    expect(drawings).toEqual(['fine', 'fine']);
    for (const stop of stops) stop();
    vi.unstubAllGlobals();
  });
});
