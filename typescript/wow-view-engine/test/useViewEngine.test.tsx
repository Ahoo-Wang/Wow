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

import { StrictMode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { aggregation } from '@ahoo-wang/fetcher-wow';
import { useViewEngine } from '../src/react/useViewEngine.js';
import { setup } from './fixtures/viewPage.js';
afterEach(cleanup);
it('owns one same-scope engine, updates host and isolates scope and definition changes', async () => {
  const { host } = setup();
  const view = renderHook(
    ({ scopeKey, definitionId, host }) =>
      useViewEngine({ scopeKey, definitionId, host }),
    { initialProps: { scopeKey: 'one', definitionId: 'orders', host } },
  );
  await waitFor(() =>
    expect(view.result.current.engine?.getSnapshot().status).toBe('ready'),
  );
  const old = view.result.current.engine!;
  const nextHost = { ...host, resolveSource: vi.fn(host.resolveSource) };
  view.rerender({ scopeKey: 'one', definitionId: 'orders', host: nextHost });
  expect(view.result.current.engine).toBe(old);
  await old.record('mine').refresh();
  expect(nextHost.resolveSource).toHaveBeenCalled();
  view.rerender({ scopeKey: 'two', definitionId: 'orders', host: nextHost });
  expect(view.result.current.engine).not.toBe(old);
  expect(() => old.record('mine').edit(value => value)).toThrow();
  const second = view.result.current.engine;
  view.rerender({
    scopeKey: 'two',
    definitionId: 'new-definition',
    host: nextHost,
  });
  expect(view.result.current.engine).not.toBe(second);
});
it('freezes paired registrations for a lifecycle and refreshes them only on a new scope', async () => {
  const { host } = setup();
  const compile = vi.fn(value => aggregation.count(value.alias));
  const registration = {
    roles: ['metric' as const],
    compile,
    component: () => null,
  };
  const extensions = { analysis: { custom: registration } };
  const view = renderHook(
    ({ scopeKey }) =>
      useViewEngine({ scopeKey, definitionId: 'orders', host, extensions }),
    { initialProps: { scopeKey: 'one' } },
  );
  await waitFor(() => expect(view.result.current.engine).toBeTruthy());
  registration.roles.length = 0;
  registration.compile = vi.fn(value => aggregation.count(value.alias));
  view.rerender({ scopeKey: 'one' });
  expect(view.result.current.extensions?.analysis?.custom.compile).toBe(
    compile,
  );
  expect(view.result.current.engine?.analysisCompilers.custom.compile).toBe(
    compile,
  );
  expect(
    Object.isFrozen(view.result.current.extensions?.analysis?.custom),
  ).toBe(true);
  expect(view.result.current.extensions?.analysis?.custom.roles).toEqual([
    'metric',
  ]);
  expect(
    Object.isFrozen(view.result.current.extensions?.analysis?.custom.roles),
  ).toBe(true);
  expect(view.result.current.engine?.analysisCompilers.custom.roles).toEqual([
    'metric',
  ]);
  registration.roles.push('metric');
  view.rerender({ scopeKey: 'two' });
  expect(view.result.current.extensions?.analysis?.custom.compile).toBe(
    registration.compile,
  );
});
it('supports StrictMode replay and rejects missing access scope without loading', async () => {
  const { host } = setup();
  const view = renderHook(
    () => useViewEngine({ scopeKey: 'strict', definitionId: 'orders', host }),
    { wrapper: StrictMode },
  );
  await waitFor(() =>
    expect(view.result.current.engine?.getSnapshot().status).toBe('ready'),
  );
  const engine = view.result.current.engine!;
  view.unmount();
  expect(() => engine.record('mine').edit(value => value)).toThrow();
  const invalid = renderHook(() =>
    useViewEngine({ scopeKey: '', definitionId: 'orders', host }),
  );
  expect(invalid.result.current.engine).toBeNull();
  expect(invalid.result.current.error).toContain('scopeKey');
});

it.each(
  [undefined, [], ['unknown'], ['metric', 'metric'], 'metric'].map(roles => [
    roles,
  ]),
)('reports invalid analysis roles through binding error: %j', async roles => {
  const { host } = setup();
  const view = renderHook(() =>
    useViewEngine({
      scopeKey: 'invalid',
      definitionId: 'orders',
      host,
      extensions: {
        analysis: {
          custom: {
            roles,
            compile: () => aggregation.count('n'),
            component: () => null,
          },
        },
      } as never,
    }),
  );
  await waitFor(() => expect(view.result.current.error).toMatch(/roles/));
  expect(view.result.current.engine).toBeNull();
});

it.each([null, undefined])(
  'reports an empty analysis registration through binding.error: %s',
  async registration => {
    const { host } = setup();
    const view = renderHook(() =>
      useViewEngine({
        scopeKey: 'empty-registration',
        definitionId: 'orders',
        host,
        extensions: { analysis: { custom: registration } } as never,
      }),
    );
    await waitFor(() => expect(view.result.current.error).toMatch(/compiler/i));
    expect(view.result.current.engine).toBeNull();
  },
);
