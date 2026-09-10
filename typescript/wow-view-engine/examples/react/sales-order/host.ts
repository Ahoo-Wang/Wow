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

import { IndexedDBViewHost } from '@ahoo-wang/fetcher-view-engine/react';
import {
  MemoryViewHost,
  type MemoryViewHostOptions,
  type FilterOptionSource,
} from '@ahoo-wang/fetcher-view-engine';
import { createOrderSource, type QueryOptions } from './querySource.js';
import {
  createOrderViews,
  createProtocolViews,
  orderDefinition,
} from './views.js';
import { customers } from './fixtures.js';
import type { OrderService } from './service.js';
import type { Role, Stage } from './model.js';
export function createOrderHost(
  service: OrderService,
  role: Role,
  stage: Stage = 'all',
  options: QueryOptions & {
    definition?: MemoryViewHostOptions['definition'];
    instances?: MemoryViewHostOptions['instances'];
    store?: MemoryViewHostOptions['store'];
    source?: ReturnType<typeof createOrderSource>;
    personal?: boolean;
    persist?: boolean;
    scopeKey?: string;
  } = {},
) {
  const configuration: MemoryViewHostOptions = {
    serviceKey: 'sales-demo',
    scopeKey: options.scopeKey ?? role,
    definition: options.definition ?? orderDefinition,
    instances:
      options.instances ??
      (options.personal ? createProtocolViews() : createOrderViews(stage)),
    resolveSource: () =>
      options.source ?? createOrderSource(service.read, options),
    instancePermissions: instance => ({
      save: instance.scope.type === 'personal' || role === 'manager',
      delete: instance.scope.type === 'personal' || role === 'manager',
      rename: instance.scope.type === 'personal' || role === 'manager',
      saveAsPersonal: true,
      saveAsShared: role === 'manager',
    }),
  };
  return options.persist
    ? new IndexedDBViewHost(configuration)
    : new MemoryViewHost({ ...configuration, store: options.store });
}
export const customerOptions: FilterOptionSource = {
  async search({ search, cursor, size = 5 }, signal) {
    signal.throwIfAborted();
    const list = customers
      .map((label, i) => ({ value: `customer-${i}`, label }))
      .filter(o => o.label.includes(search));
    const offset = Number(cursor ?? 0);
    if (!Number.isSafeInteger(offset) || offset < 0)
      throw new Error('客户游标无效');
    return {
      list: list.slice(offset, offset + size),
      nextCursor: offset + size < list.length ? String(offset + size) : null,
    };
  },
  async resolve(values, signal) {
    signal.throwIfAborted();
    const list = customers
      .map((label, i) => ({ value: `customer-${i}`, label }))
      .filter(o => values.includes(o.value));
    return {
      list,
      missing: values.filter(v => !list.some(o => o.value === v)),
    };
  },
};
