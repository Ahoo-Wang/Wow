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

import { afterEach, expect, it, vi } from 'vitest';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  validateRuntimeLimits,
  assertConfigSize,
  withDeadline,
  QueryBudget,
} from '../src/lib/runtimeLimits.js';
import { AnalysisCommands } from '../src/analysis/AnalysisCommands.js';
import { SessionStore } from '../src/engine/SessionStore.js';
import { EngineScope } from '../src/engine/EngineScope.js';
import { InstanceWork } from '../src/engine/InstanceWork.js';
import { createSession, deriveSession } from '../src/engine/sessionState.js';
import type {
  ViewDefinition,
  AnalysisViewInstance,
} from '../src/contracts/viewModel.js';
import type { ViewSource } from '../src/contracts/ViewHost.js';
const definition: ViewDefinition = {
  id: 'd',
  title: 'private title',
  sourceId: 's',
  fields: [],
  analysis: { count: true, fields: [] },
};
const instance: AnalysisViewInstance = {
  id: 'a',
  definitionId: 'd',
  kind: 'analysis',
  title: 'private title',
  scope: { type: 'personal' },
  revision: '1',
  config: {
    filters: {
      mode: 'simple',
      root: {
        id: 'all',
        component: { name: 'builtin' },
        operator: FilterOperator.MATCH_ALL,
        props: {},
      },
    },
    dimensions: [],
    metrics: [
      {
        id: 'n',
        alias: 'n',
        title: 'private count',
        component: { name: 'count' },
        props: {},
      },
    ],
    sort: [],
    limit: 100,
    presentation: { layout: 'table', columns: [] },
  },
};
function setup(
  resolveSource: () => ViewSource | Promise<ViewSource>,
  limits = {},
  onDiagnostic = vi.fn(),
) {
  const scope = new EngineScope();
  const store = new SessionStore(scope, {}, {}, validateRuntimeLimits(limits));
  store.publish({
    status: 'ready',
    definition,
    selectedInstanceId: 'c',
    instanceIds: ['a', 'b', 'c'],
    sessions: Object.fromEntries(
      ['a', 'b', 'c'].map(id => [
        id,
        createSession({ ...instance, id }, definition, {}),
      ]),
    ),
  });
  const commands = new AnalysisCommands(
    store,
    scope,
    { resolveSource },
    new InstanceWork(),
    {},
    validateRuntimeLimits(limits),
    onDiagnostic,
  );
  return { scope, store, commands, onDiagnostic };
}
afterEach(() => vi.useRealTimers());
it('validates budgets and measures serialized UTF8 bytes', () => {
  expect(validateRuntimeLimits().queryTimeoutMs).toBe(30000);
  expect(() => validateRuntimeLimits({ maxConcurrentQueries: 0 })).toThrow();
  expect(() => assertConfigSize('中', 4)).toThrow();
  expect(() => assertConfigSize('中', 5)).not.toThrow();
  const budget = new QueryBudget(1);
  const old = budget.acquire('a', {});
  const release = budget.acquire('a', {});
  old();
  expect(() => budget.acquire('b', {})).toThrow();
  release();
  expect(() => budget.acquire('b', {})).not.toThrow();
});
it('deadline settles even if the host ignores abort and cleans timer on completion', async () => {
  vi.useFakeTimers();
  const controller = new AbortController();
  const pending = withDeadline(() => new Promise(() => {}), 10, controller);
  const failure = expect(pending).rejects.toMatchObject({ code: 'TIMEOUT' });
  await vi.advanceTimersByTimeAsync(10);
  await failure;
  expect(controller.signal.aborted).toBe(true);
  expect(vi.getTimerCount()).toBe(0);
  await expect(withDeadline(() => 1, 10, new AbortController())).resolves.toBe(
    1,
  );
  expect(vi.getTimerCount()).toBe(0);
});
it('covers source resolution timeout, rejects overflow, ignores late completion and isolates diagnostics', async () => {
  vi.useFakeTimers();
  let resolve!: (value: ViewSource) => void;
  const source = new Promise<ViewSource>(done => {
    resolve = done;
  });
  const aggregate = vi.fn().mockResolvedValue([{ n: 1 }]);
  const diagnostic = vi.fn(() => {
    throw new Error('observer');
  });
  const { commands, store } = setup(
    () => source,
    { queryTimeoutMs: 10, maxConcurrentQueries: 1 },
    diagnostic,
  );
  const pending = commands.run('a');
  const failure = expect(pending).rejects.toMatchObject({ code: 'TIMEOUT' });
  await expect(commands.run('b')).rejects.toMatchObject({ code: 'BUSY' });
  await vi.advanceTimersByTimeAsync(10);
  await failure;
  expect(store.analysisSession('a').queryStatus).toBe('error');
  resolve({ aggregate });
  await Promise.resolve();
  await Promise.resolve();
  expect(aggregate).not.toHaveBeenCalled();
  expect(store.analysisSession('a').result).toBeNull();
  expect(JSON.stringify(diagnostic.mock.calls)).not.toContain('private');
  expect(vi.getTimerCount()).toBe(0);
});
it('cancels ignored-abort aggregate without an error or later result', async () => {
  let resolve!: (value: { n: number }[]) => void;
  const aggregate = vi.fn(
    () =>
      new Promise<{ n: number }[]>(done => {
        resolve = done;
      }),
  );
  const { commands, store } = setup(() => ({ aggregate }));
  const pending = commands.run('a');
  await Promise.resolve();
  await Promise.resolve();
  commands.cancel('a');
  await pending;
  resolve([{ n: 1 }]);
  await Promise.resolve();
  expect(store.analysisSession('a').queryStatus).toBe('idle');
  expect(store.analysisSession('a').result).toBeNull();
});
it('evicts only noncurrent results and keeps working configurations', async () => {
  const { commands, store } = setup(
    () => ({ aggregate: async () => [{ n: 1 }] }),
    { maxRetainedResults: 2 },
  );
  const working = store.analysisSession('a').instance;
  await commands.run('a');
  await commands.run('b');
  await commands.run('c');
  expect(store.analysisSession('a').result).toBeNull();
  expect(store.analysisSession('a').instance).toBe(working);
  expect(store.analysisSession('b').result).not.toBeNull();
  expect(store.analysisSession('c').result).not.toBeNull();
});
it('blocks oversized run without discarding the recoverable working edit', async () => {
  const source = vi.fn(() => ({ aggregate: async () => [{ n: 1 }] }));
  const { commands, store } = setup(source, { maxConfigBytes: 100 });
  commands.edit('a', config => ({
    ...config,
    metrics: config.metrics.map(metric => ({
      ...metric,
      title: 'large'.repeat(100),
    })),
  }));
  await expect(commands.run('a')).rejects.toMatchObject({
    code: 'RESOURCE_LIMIT',
  });
  expect(source).not.toHaveBeenCalled();
  expect(
    store.analysisSession('a').instance.config.metrics[0].title,
  ).toHaveLength(500);
});
it('preserves every rejected host outcome and removes its deadline', async () => {
  vi.useFakeTimers();
  await expect(
    withDeadline(() => Promise.reject(undefined), 10, new AbortController()),
  ).rejects.toBeUndefined();
  expect(vi.getTimerCount()).toBe(0);
});
it('registers the deadline before dispatching the host synchronously', async () => {
  const operation = vi.fn(() => 1);
  const result = withDeadline(operation, 100, new AbortController());
  expect(operation).toHaveBeenCalledTimes(1);
  await result;
});
it('times out an aggregate that ignores abort and emits only one terminal event', async () => {
  vi.useFakeTimers();
  let complete!: (rows: { n: number }[]) => void;
  const aggregate = vi.fn(
    () =>
      new Promise<{ n: number }[]>(resolve => {
        complete = resolve;
      }),
  );
  const { commands, store, onDiagnostic } = setup(() => ({ aggregate }), {
    queryTimeoutMs: 10,
  });
  const pending = commands.run('a');
  const failure = expect(pending).rejects.toMatchObject({ code: 'TIMEOUT' });
  await vi.advanceTimersByTimeAsync(10);
  await failure;
  complete([{ n: 1 }]);
  await Promise.resolve();
  await Promise.resolve();
  expect(store.analysisSession('a').result).toBeNull();
  expect(onDiagnostic.mock.calls.map(call => call[0].phase)).toEqual([
    'started',
    'failed',
  ]);
  expect(vi.getTimerCount()).toBe(0);
});
it('restoring analysis preserves invalid editor validity until recovery is reported', () => {
  const { commands, store } = setup(() => ({
    aggregate: async () => [{ n: 1 }],
  }));
  store.patch('a', { filterValid: false });
  expect(store.analysisSession('a').validation.length).toBeGreaterThan(0);
  commands.restore('a');
  expect(store.analysisSession('a').filterValid).toBe(false);
  expect(store.analysisSession('a').validation).toContainEqual({
    id: 'filters',
    message: '筛选输入无效',
  });
});

it('derives resource and query validity for pending-create recovery as well as active sessions', () => {
  const { store } = setup(() => ({ aggregate: async () => [] }), {
    maxConfigBytes: 1024,
  });
  const large = createSession(
    {
      ...instance,
      id: 'pending',
      config: {
        ...instance.config,
        metrics: [{ ...instance.config.metrics[0], title: 'x'.repeat(2048) }],
      },
    },
    definition,
    {},
  );
  store.publish({ pendingCreates: { pending: large } });
  expect(store.findPendingCreate('pending')?.validation).toContainEqual(
    expect.objectContaining({ id: 'config-size' }),
  );
  store.patch('pending', { filterValid: false });
  store.patch('pending', { filterValid: true });
  const pending = store.findPendingCreate('pending');
  expect(pending?.kind === 'analysis' && pending.queryValid).toBe(false);
});

it('separates presentation admission from query validity at the published boundary', () => {
  const { store } = setup(() => ({ aggregate: async () => [] }));
  const session = store.analysisSession('a');
  store.patch('a', {
    instance: {
      ...session.instance,
      config: {
        ...session.instance.config,
        presentation: { layout: 'table', columns: [{ alias: 'missing' }] },
      },
    },
  });
  expect(store.analysisSession('a').validation.length).toBeGreaterThan(0);
  expect(store.analysisSession('a').queryValid).toBe(true);
  store.patch('a', { filterValid: false });
  expect(store.analysisSession('a').queryValid).toBe(false);
});

it('re-evaluates final validation when the same snapshot is checked under a different resource budget', () => {
  const base = createSession(instance, definition, {});
  const relaxed = deriveSession(base, definition, {}, undefined, {}, 4096);
  const strict = deriveSession(relaxed, definition, {}, relaxed, {}, 10);
  expect(strict.validation).toContainEqual(
    expect.objectContaining({ id: 'config-size' }),
  );
  const recovered = deriveSession(strict, definition, {}, strict, {}, 4096);
  expect(recovered.validation).not.toContainEqual(
    expect.objectContaining({ id: 'config-size' }),
  );
});

it('treats undefined runtime overrides as omitted without accepting null or zero', () => {
  expect(validateRuntimeLimits({ queryTimeoutMs: undefined })).toEqual(
    validateRuntimeLimits(),
  );
  for (const queryTimeoutMs of [null, 0, -1])
    expect(() =>
      validateRuntimeLimits({ queryTimeoutMs: queryTimeoutMs as never }),
    ).toThrow();
});
