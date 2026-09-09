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

import type { AggregationQuery } from '@ahoo-wang/fetcher-wow';
import {
  ViewServiceError,
  type ViewHost,
  type ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import type { DemoQuery, ScenarioOptions } from './demoTypes.js';
import { definition, makeInstances, pause } from './fixtures.js';
import { createOrderSource } from './querySource.js';

export function createHost(
  options: ScenarioOptions,
  onQuery: (method: 'paged' | 'cursor', request: DemoQuery) => void,
  onWrite: (
    operation: 'save' | 'create' | 'delete' | 'rename',
    instance: ViewInstance,
  ) => void,
  onSummary: (query: AggregationQuery) => void,
  onOrder: (ids: string[]) => void,
) {
  const {
    mode = 'paged',
    failFirstDelete = false,
    summaries = false,
    saveOnly = false,
    pageSize = 5,
    local,
  } = options;
  const { source, customerOptions, createOrder, processOrders } =
    createOrderSource(options, onQuery, onSummary);
  const initialInstances = makeInstances(
    mode,
    summaries,
    pageSize,
    options.initialFilter,
  );
  const saved = new Map(
    initialInstances.instances.map(instance => [
      instance.id,
      structuredClone(instance),
    ]),
  );
  let instanceOrder = [...saved.keys()];
  let failNextDelete = failFirstDelete;
  let nextInstance = 1;
  const createReceipts = new Map<
    string,
    { body: string; result: ViewInstance }
  >();

  function loadInstance(id: string) {
    const instance = saved.get(id);
    if (!instance)
      throw new ViewServiceError('NOT_FOUND', `视图 ${id} 不存在。`);
    return structuredClone(instance);
  }
  const host: ViewHost = {
    ...(!local && {
      definition: {
        async load(id: string) {
          if (id !== definition.id) throw new Error('订单视图定义不存在。');
          return structuredClone(definition);
        },
      },
    }),
    resolveSource(id) {
      if (id !== definition.sourceId) throw new Error('订单数据源不存在。');
      return source;
    },
    permission: {
      getInstance(instance) {
        return {
          save:
            instance.scope.type === 'personal' ||
            instance.scope.source === 'shared',
          saveAsPersonal: !saveOnly,
          saveAsShared: !saveOnly,
          rename:
            !saveOnly &&
            (instance.scope.type === 'personal' ||
              instance.scope.source === 'shared'),
          delete:
            !saveOnly &&
            (instance.scope.type === 'personal' ||
              instance.scope.source === 'shared'),
        };
      },
    },
    instance: {
      ...(!local && {
        async list(id: string) {
          if (id !== definition.id) throw new Error('订单视图定义不存在。');
          return {
            instances: instanceOrder
              .filter(id => saved.has(id))
              .map(loadInstance),
            defaultInstanceId: saved.has(
              initialInstances.defaultInstanceId ?? '',
            )
              ? initialInstances.defaultInstanceId
              : (instanceOrder[0] ?? null),
          };
        },
        async load(id: string) {
          return loadInstance(id);
        },
      }),
      async rename(id, title, revision) {
        await pause();
        const previous = loadInstance(id);
        if (
          previous.scope.type === 'public' &&
          previous.scope.source === 'system'
        )
          throw new ViewServiceError('FORBIDDEN', '系统视图不能编辑名称。');
        if (revision !== previous.revision)
          throw new ViewServiceError(
            'REVISION_CONFLICT',
            '视图已被更新，请重新加载。',
          );
        const updated = {
          ...previous,
          title,
          revision: String(Number(previous.revision) + 1),
        };
        saved.set(id, updated);
        onWrite('rename', structuredClone(updated));
        return structuredClone(updated);
      },
      async delete(id, revision) {
        await pause();
        const previous = saved.get(id);
        if (!previous) return;
        if (
          previous.scope.type === 'public' &&
          previous.scope.source === 'system'
        )
          throw new ViewServiceError('FORBIDDEN', '系统视图不能删除。');
        if (revision !== previous.revision)
          throw new ViewServiceError(
            'REVISION_CONFLICT',
            '视图已被更新，请重新加载后再删除。',
          );
        if (failNextDelete) {
          failNextDelete = false;
          throw new ViewServiceError('CONFLICT', '删除失败，请重试。');
        }
        saved.delete(id);
        instanceOrder = instanceOrder.filter(value => value !== id);
        onWrite('delete', structuredClone(previous));
      },
      async save(instance) {
        await pause();
        const previous = loadInstance(instance.id);
        if (instance.revision !== previous.revision)
          throw new ViewServiceError(
            'REVISION_CONFLICT',
            '视图已被更新，请重新加载后再保存。',
          );
        const updated = {
          ...structuredClone(instance),
          revision: String(Number(previous.revision) + 1),
        };
        saved.set(updated.id, updated);
        onWrite('save', structuredClone(updated));
        return structuredClone(updated);
      },
      async create(instance, { requestId }) {
        await pause();
        const previous = createReceipts.get(requestId);
        if (previous) {
          if (previous.body !== JSON.stringify(instance))
            throw new ViewServiceError(
              'CONFLICT',
              '创建请求标识已用于不同内容',
            );
          return structuredClone(previous.result);
        }
        const created = {
          ...structuredClone(instance),
          id: `orders-copy-${nextInstance++}`,
          revision: '1',
        };
        saved.set(created.id, created);
        instanceOrder.push(created.id);
        createReceipts.set(requestId, {
          body: JSON.stringify(instance),
          result: structuredClone(created),
        });
        onWrite('create', structuredClone(created));
        return structuredClone(created);
      },
    },
    preference: {
      async saveOrder(definitionId, ids) {
        await pause();
        if (
          definitionId !== definition.id ||
          ids.length !== saved.size ||
          new Set(ids).size !== saved.size ||
          ids.some(id => !saved.has(id))
        )
          throw new Error('可用视图已变化，请重新加载。');
        instanceOrder = [...ids];
        onOrder([...ids]);
      },
    },
  };
  return {
    host,
    initialInstances,
    customerOptions,
    createOrder,
    processOrders,
  };
}
