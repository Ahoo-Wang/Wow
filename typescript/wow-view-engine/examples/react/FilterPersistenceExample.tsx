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

import { useState } from 'react';
import {
  createFilterConfiguration,
  newFilterNode,
  type ViewDefinition,
  type ViewHost,
  type ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import { Button, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import { FilterOperator, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { orderDefinition, createProtocolViews } from './sales-order/views.js';
import { orderExtensions } from './sales-order/OrderExtensions.js';
import { createOrderSource } from './sales-order/querySource.js';
import { createOrderService } from './sales-order/service.js';

const orderViews = createProtocolViews();
const definition: ViewDefinition = {
  id: orderDefinition.id,
  title: '筛选配置持久化',
  sourceId: orderDefinition.sourceId,
  rowKey: orderDefinition.rowKey,
  allowedLayouts: ['table'],
  fields: orderDefinition.fields
    .filter(f =>
      ['aggregateId', 'state.totalAmount', 'state.lifecycle'].includes(f.field),
    )
    .map(f =>
      f.field === 'state.lifecycle'
        ? {
            ...f,
            editor: { name: 'order-status' },
            operators: [FilterOperator.EQ],
          }
        : f,
    ),
  allowedOperators: orderDefinition.allowedOperators,
};
const initial: ViewInstance = {
  ...orderViews.instances[0],
  title: '我的筛选配置',
  scope: { type: 'personal' },
  revision: '1',
  config: {
    ...orderViews.instances[0].config,
    filters: createFilterConfiguration(newFilterNode(FilterOperator.MATCH_ALL)),
    presentation: {
      layout: 'table',
      table: {
        columns: definition.fields.map(field => ({
          id: field.field,
          field: field.field,
          kind: 'field',
        })),
      },
    },
  },
};

/** A new ViewPage creates a new engine and restores the JSON, including opaque display props. */
export function FilterPersistenceExample({
  appearance = 'light',
}: {
  appearance?: 'light' | 'dark';
}) {
  const [generation, setGeneration] = useState(0);
  const [queries, setQueries] = useState<FilterExpression[]>([]);
  const [writes, setWrites] = useState(0);
  const [savedJson, setSavedJson] = useState(() => JSON.stringify(initial));
  const [host] = useState<ViewHost>(() => {
    let stored = savedJson;
    const service = createOrderService();
    const source = createOrderSource(service.read, {
      onQuery: (_method, request) => {
        if ('filter' in request)
          setQueries(value => [...value, request.filter]);
      },
    });
    return {
      resolveSource: () => source,
      definition: { load: async () => structuredClone(definition) },
      instance: {
        list: async () => ({
          instances: [JSON.parse(stored)],
          defaultInstanceId: initial.id,
        }),
        load: async id => {
          if (id !== initial.id) throw new Error('未知视图。');
          return JSON.parse(stored);
        },
        save: async instance => {
          const previous: ViewInstance = JSON.parse(stored);
          if (
            instance.id !== previous.id ||
            instance.revision !== previous.revision
          )
            throw new Error('视图版本已变化，请重新打开。');
          stored = JSON.stringify({
            ...instance,
            revision: String(Number(previous.revision) + 1),
          });
          setSavedJson(stored);
          setWrites(value => value + 1);
          return JSON.parse(stored);
        },
      },
      permission: {
        getInstance: () => ({
          save: true,
          saveAsPersonal: false,
          saveAsShared: false,
        }),
      },
    };
  });
  return (
    <div className="fve-root" data-theme={appearance} style={{ padding: 12 }}>
      <p>
        添加未设置的筛选可直接保存。显示名称不影响查询；状态值改变后需先查询。
      </p>
      <Button
        variant="outline"
        onClick={() => setGeneration(value => value + 1)}
      >
        重新打开已存视图
      </Button>
      <p>
        查询 <span data-testid="persistence-query-count">{queries.length}</span>{' '}
        次 · 保存 <span data-testid="persistence-save-count">{writes}</span> 次
      </p>
      <ViewPage
        key={generation}
        definitionId={definition.id}
        scopeKey="local-user:filter-persistence"
        host={host}
        extensions={orderExtensions}
        initialSidebarCollapsed
      />
      <details open>
        <summary>已保存的组件配置与实际查询</summary>
        <pre
          data-testid="persisted-filter-config"
          style={{ whiteSpace: 'pre-wrap' }}
        >
          {JSON.stringify(JSON.parse(savedJson).config.filters, null, 2)}
        </pre>
        <pre data-testid="persistence-query">
          {JSON.stringify(queries[queries.length - 1])}
        </pre>
      </details>
    </div>
  );
}
