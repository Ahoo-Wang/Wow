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

import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { filter } from '@ahoo-wang/fetcher-wow';
import { useViewEngine } from '../../src/react/useViewEngine.js';
import { definition, instance } from '../engine/fixtures.js';
import { globalFilter } from './runtimeFixtures.js';
import type { DashboardViewInstance } from '../../src/dashboard/dashboardModel.js';
afterEach(cleanup);
it('pairs a fixed dashboard transform and editor registry with the access lifetime', async () => {
  const paged = vi.fn().mockResolvedValue({ total: 0, list: [] });
  const host = {
    instance: { load: async () => instance('child') },
    definition: { load: async () => definition },
    resolveSource: () => ({ paged }),
  };
  const root = {
    id: 'root',
    title: 'Root',
    fields: definition.fields,
    dashboard: true as const,
  };
  const saved: DashboardViewInstance = {
    id: 'dashboard',
    definitionId: 'root',
    title: 'Dashboard',
    kind: 'dashboard',
    revision: '1',
    scope: { type: 'personal' },
    config: {
      schemaVersion: 1,
      panels: [
        {
          kind: 'view' as const,
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 12, h: 18 },
        },
      ],
      filters: [
        {
          ...globalFilter(),
          bindings: [{ panelId: 'a', kind: 'transform', name: 'mapped' }],
        },
      ],
    },
  };
  const view = renderHook(
    ({ scopeKey, value }) =>
      useViewEngine({
        scopeKey,
        definitionId: 'root',
        definition: root,
        instances: { instances: [saved], defaultInstanceId: 'dashboard' },
        host,
        dashboardTransforms: { mapped: () => filter.eq('state.amount', value) },
        extensions: {
          dashboard: { transforms: { mapped: { label: String(value) } } },
        },
      }),
    { initialProps: { scopeKey: 'one', value: 42 } },
  );
  await waitFor(() => expect(paged).toHaveBeenCalledOnce());
  expect(JSON.stringify(paged.mock.lastCall![0])).toContain('42');
  view.rerender({ scopeKey: 'one', value: 43 });
  expect(
    view.result.current.extensions?.dashboard?.transforms?.mapped.label,
  ).toBe('42');
  await view.result.current.engine!.dashboard('dashboard').refresh();
  expect(JSON.stringify(paged.mock.lastCall![0])).toContain('42');
  view.rerender({ scopeKey: 'two', value: 43 });
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(3));
  expect(JSON.stringify(paged.mock.lastCall![0])).toContain('43');
});
