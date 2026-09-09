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
  createFilterConfiguration,
  newFilterNode,
} from '../../src/filter/filterCore.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { vi } from 'vitest';
import { ViewEngine } from '../../src/record/ViewEngine.js';
import type {
  ViewDefinition,
  ViewEngineOptions,
  ViewInstance,
} from '../../src/record/recordModel.js';
import type { ViewHost } from '../../src/record/ViewHost.js';

export const definition: ViewDefinition = {
  id: 'orders',
  title: 'Orders',
  sourceId: 'orders-source',
  rowKey: 'state.id',
  fields: [
    { field: 'state.id', label: 'ID', type: 'string', sortable: true },
    { field: 'state.amount', label: 'Amount', type: 'number', sortable: true },
  ],
};

export function instance(
  id = 'mine',
  mode: 'paged' | 'cursor' = 'paged',
): ViewInstance {
  return {
    id,
    definitionId: 'orders',
    title: id,
    kind: 'record',
    scope: { type: 'personal' },
    revision: 'r1',
    config: {
      filters: createFilterConfiguration({
        ...newFilterNode(FilterOperator.MATCH_ALL),
        id: 'filter-root',
      }),
      sort: [],
      pagination: { mode, size: 10 },
      presentation: {
        layout: 'table',
        table: {
          columns: [{ id: 'amount', kind: 'field', field: 'state.amount' }],
        },
      },
    },
  };
}

export function setup(options: Partial<ViewEngineOptions> = {}) {
  const paged = vi.fn().mockResolvedValue({
    total: 1,
    list: [{ state: { id: 'a', amount: 10 } }],
  });
  const cursor = vi
    .fn()
    .mockResolvedValue({ list: [{ state: { id: 'a' } }], nextCursor: 'next' });
  const host: ViewHost = {
    resolveSource: vi.fn(() => ({ paged, cursor })),
    ...options.host,
    permission: {
      getInstance: () => ({
        save: true,
        saveAsPersonal: true,
        saveAsShared: true,
      }),
      ...options.host?.permission,
    },
    instance: {
      save: vi.fn(async value => ({ ...value, revision: 'r2' })),
      create: vi.fn(async value => ({
        ...value,
        id: 'created',
        revision: 'r1',
      })),
      ...options.host?.instance,
    },
  };
  const engine = new ViewEngine({
    definitionId: 'orders',
    definition,
    instances: {
      instances: [instance(), instance('shared')],
      defaultInstanceId: 'mine',
    },
    ...options,
    host,
  });
  return { engine, host, paged, cursor };
}

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

export const selected = (
  engine: ViewEngine,
  id = engine.getSnapshot().selectedInstanceId!,
) => engine.getSnapshot().sessions[id];

export const deletionPermissions = () => ({
  save: true,
  saveAsPersonal: true,
  saveAsShared: true,
  delete: true,
});

export const managementPermissions = () => ({
  save: true,
  saveAsPersonal: true,
  saveAsShared: true,
  rename: true,
  delete: true,
});
