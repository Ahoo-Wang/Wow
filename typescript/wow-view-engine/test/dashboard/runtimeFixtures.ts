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

import { vi } from 'vitest';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { ViewEngine } from '../../src/engine/ViewEngine.js';
import type { ViewEngineOptions } from '../../src/contracts/viewModel.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import type {
  DashboardConfig,
  DashboardViewInstance,
} from '../../src/dashboard/dashboardModel.js';
import {
  createFilterConfiguration,
  newFilterNode,
} from '../../src/filter/filterCore.js';
import { definition, instance } from '../engine/fixtures.js';

export function globalFilter(value = 10, excludedPanelIds: string[] = []) {
  return {
    id: 'amount',
    filters: createFilterConfiguration({
      ...newFilterNode(FilterOperator.EQ),
      field: 'state.amount',
      props: { value },
    }),
    bindings: ['a', 'b']
      .filter(id => !excludedPanelIds.includes(id))
      .map(panelId => ({
        panelId,
        kind: 'fields' as const,
        fields: { 'state.amount': 'state.amount' },
        semanticCompatibility: true as const,
      })),
    excludedPanelIds,
  };
}
export function dashboardSetup(
  config: DashboardConfig = {
    schemaVersion: 1,
    panels: [
      {
        kind: 'view' as const,
        id: 'a',
        instanceId: 'child',
        layout: { x: 0, y: 0, w: 6, h: 18 },
      },
      {
        kind: 'view' as const,
        id: 'b',
        instanceId: 'child',
        layout: { x: 0, y: 0, w: 6, h: 18 },
      },
    ],
    filters: [],
  },
  overrides: Partial<ViewHost> = {},
  limits?: ViewEngineOptions['limits'],
) {
  const dashboard: DashboardViewInstance = {
    id: 'dashboard',
    definitionId: 'root',
    kind: 'dashboard',
    title: 'Dashboard',
    revision: 'r1',
    scope: { type: 'personal' },
    config,
  };
  const root = {
    id: 'root',
    title: 'Root',
    fields: definition.fields,
    dashboard: true as const,
  };
  const paged = vi.fn().mockResolvedValue({
    total: 30,
    list: [{ state: { id: 'row', amount: 10 } }],
  });
  const load = vi.fn(async () => instance('child'));
  const host: ViewHost = {
    instance: {
      load,
      save: vi.fn(async value => ({ ...value, revision: 'r2' })),
    },
    definition: { load: vi.fn(async () => definition) },
    resolveSource: vi.fn(() => ({ paged })),
    permission: {
      getInstance: () => ({
        save: true,
        saveAsPersonal: true,
        saveAsShared: false,
      }),
    },
    ...overrides,
  };
  const engine = new ViewEngine({
    definitionId: 'root',
    definition: root,
    instances: { instances: [dashboard], defaultInstanceId: 'dashboard' },
    host,
    limits,
  });
  return { engine, host, paged, load };
}
