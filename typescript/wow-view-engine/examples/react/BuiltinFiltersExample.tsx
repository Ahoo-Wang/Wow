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
import { useState } from 'react';
import {
  Fetcher,
  ResultExtractors,
  URL_RESOLVE_INTERCEPTOR_NAME,
} from '@ahoo-wang/fetcher';
import {
  FilterOperator as Op,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import {
  MemoryViewHost,
  type MemoryViewHostOptions,
  createFilterConfiguration,
  type FilterComponentConfig,
  type FilterOptionSource,
  type ViewDefinition,
  type ViewInstanceList,
} from '@ahoo-wang/fetcher-view-engine';
import { Button, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

const candidates = [
  { value: 'u1', label: '用户甲', group: '研发' },
  { value: 'u2', label: '用户乙', group: '研发' },
  { value: 'u3', label: '用户丙', group: '运营' },
  { value: 'u4', label: '用户丁', group: '运营' },
];
const definition: ViewDefinition = {
  id: 'builtin-filters',
  title: '内置筛选器',
  sourceId: 'query-echo',
  allowedLayouts: ['table', 'card'],
  rowKey: 'id',
  timeZone: 'Asia/Shanghai',
  fields: [
    { field: 'id', label: '编号', type: 'string' },
    {
      field: 'status',
      label: '状态',
      type: 'string',
      operators: [Op.IN, Op.NOT_IN],
      options: [
        { value: 'pending', label: '待处理', group: '进行中' },
        { value: 'done', label: '已完成', group: '已结束' },
      ],
      editor: { name: 'multi-select' },
    },
    {
      field: 'owner',
      label: '负责人',
      type: 'string',
      operators: [Op.EQ, Op.NE],
      editor: {
        name: 'remote-select',
        options: { source: 'users', pageSize: 2, debounceMs: 0 },
      },
    },
    {
      field: 'users',
      label: '参与人',
      type: 'string',
      operators: [Op.IN, Op.NOT_IN],
      editor: {
        name: 'remote-multi-select',
        options: { source: 'users', pageSize: 2, debounceMs: 0 },
      },
    },
    {
      field: 'refs',
      label: '批量编号',
      type: 'string',
      operators: [Op.IN, Op.NOT_IN],
      editor: { name: 'text-values' },
    },
    {
      field: 'created',
      label: '创建时间',
      type: 'datetime',
      operators: [Op.BETWEEN],
      editor: { name: 'datetime-range' },
    },
  ],
};
const draft: FilterComponentConfig = {
  id: 'all',
  component: { name: 'builtin' },
  props: {},
  operator: Op.AND,
  operands: [
    {
      id: 'status',
      operator: Op.IN,
      field: 'status',
      props: {},
      component: { name: 'multi-select' },
    },
    {
      id: 'owner',
      operator: Op.EQ,
      field: 'owner',
      props: {},
      component: {
        name: 'remote-select',
        options: { source: 'users', pageSize: 2, debounceMs: 0 },
      },
    },
    {
      id: 'users',
      operator: Op.IN,
      field: 'users',
      props: {
        values: ['u1'],
        selectedOptions: [{ value: 'u1', label: '保存的用户甲' }],
      },
      component: {
        name: 'remote-multi-select',
        options: { source: 'users', pageSize: 2, debounceMs: 0 },
      },
    },
    {
      id: 'refs',
      operator: Op.IN,
      field: 'refs',
      props: {},
      component: { name: 'text-values' },
    },
    {
      id: 'created',
      operator: Op.BETWEEN,
      field: 'created',
      props: {},
      component: { name: 'datetime-range' },
    },
  ],
};
const instances: ViewInstanceList = {
  defaultInstanceId: 'mine',
  instances: [
    {
      id: 'mine',
      definitionId: definition.id,
      kind: 'record',
      title: '我的筛选',
      scope: { type: 'personal' },
      revision: '1',
      config: {
        filters: createFilterConfiguration(draft, 'simple'),
        sort: [],
        pagination: { mode: 'paged', size: 5 },
        presentation: {
          layout: 'table',
          table: { columns: [{ id: 'id', kind: 'field', field: 'id' }] },
        },
      },
    },
  ],
};

export interface BuiltinFiltersExampleProps {
  appearance?: 'light' | 'dark';
  scopeKey?: string;
  persist?: boolean;
  failNextPage?: boolean;
  failResolve?: boolean;
}
export function BuiltinFiltersExample(props: BuiltinFiltersExampleProps) {
  return (
    <ExampleSession
      key={`${props.scopeKey ?? 'storybook'}:${props.persist ?? false}:${props.failNextPage ?? false}:${props.failResolve ?? false}`}
      {...props}
    />
  );
}
function ExampleSession({
  appearance = 'light',
  scopeKey = 'storybook',
  persist = false,
  failNextPage = false,
  failResolve = false,
}: BuiltinFiltersExampleProps) {
  const [queries, setQueries] = useState<FilterExpression[]>([]);
  const [generation, setGeneration] = useState(0);
  const [saved, setSaved] = useState('');
  const [runtime] = useState(() => {
    const store = new Map<string, string | null>();
    const client = new Fetcher();
    // Only this deterministic data-URL fixture bypasses HTTP URL-template resolution.
    client.interceptors.request.eject(URL_RESOLVE_INTERCEPTOR_NAME);
    const read = <T,>(value: T, signal: AbortSignal) =>
      client.get<T>(
        `data:application/json,${encodeURIComponent(JSON.stringify(value))}`,
        { signal },
        { resultExtractor: ResultExtractors.Json },
      );
    let pageFailure = failNextPage,
      resolveFailure = failResolve;
    const source: FilterOptionSource = {
      search: async ({ search, cursor, size = 2 }, signal) => {
        if (cursor && pageFailure) {
          pageFailure = false;
          throw new Error('候选服务暂不可用');
        }
        const items = candidates.filter(item => item.label.includes(search));
        const offset = cursor ? Number(cursor) : 0;
        return read(
          {
            list: items.slice(offset, offset + size),
            nextCursor:
              offset + size < items.length ? String(offset + size) : null,
          },
          signal,
        );
      },
      resolve: async (values, signal) => {
        if (resolveFailure) {
          resolveFailure = false;
          throw new Error('标签服务暂不可用');
        }
        return read(
          {
            list: candidates.filter(item => values.includes(item.value)),
            missing: values.filter(
              value => !candidates.some(item => item.value === value),
            ),
          },
          signal,
        );
      },
    };
    const configuration: MemoryViewHostOptions = {
      serviceKey: 'builtin-filter-demo',
      scopeKey,
      definition,
      instances,
      resolveSource: () => ({
        paged: async query => {
          if (!('filter' in query))
            throw new Error('示例仅支持 Filter 查询协议');
          setQueries(previous => [...previous, query.filter]);
          return { list: [], total: 0 };
        },
      }),
    };
    const createHost = () =>
      persist
        ? new IndexedDBViewHost(configuration)
        : new MemoryViewHost({ ...configuration, store });
    return { source, createHost };
  });
  const [host, setHost] = useState(runtime.createHost);
  return (
    <div
      className="fve-root"
      data-theme={appearance}
      style={{ padding: 16, background: 'var(--fve-background)' }}
    >
      <p>
        候选使用确定性本地数据，经 Fetcher
        读取；记录查询仅回显表达式。视图配置由 MemoryViewHost /
        IndexedDBViewHost 保存。
      </p>
      <div className="fve:mb-3 fve:flex fve:flex-wrap fve:gap-2">
        <Button
          variant="outline"
          onClick={() => {
            setHost(runtime.createHost());
            setGeneration(value => value + 1);
          }}
        >
          重新打开已保存视图
        </Button>
        <Button
          variant="outline"
          onClick={async () =>
            setSaved(JSON.stringify(await host.instance.load('mine')))
          }
        >
          查看保存 JSON
        </Button>
        <Button
          variant="outline"
          onClick={async () => {
            await host.reset();
            setHost(runtime.createHost());
            setGeneration(value => value + 1);
          }}
        >
          重置示例
        </Button>
      </div>
      <ViewPage
        key={generation}
        definitionId={definition.id}
        host={host}
        scopeKey={scopeKey}
        initialSidebarCollapsed
        extensions={{ optionSources: { users: runtime.source } }}
      />
      <p>
        记录查询次数：
        <output data-testid="builtin-query-count">{queries.length}</output>
      </p>
      <pre
        data-testid="builtin-query"
        role="region"
        tabIndex={0}
        aria-label="已执行的查询 JSON"
        className="fve:overflow-auto"
      >
        {JSON.stringify(queries[queries.length - 1], null, 2)}
      </pre>
      <pre
        data-testid="builtin-saved"
        role="region"
        tabIndex={0}
        aria-label="保存的视图 JSON"
        className="fve:overflow-auto"
      >
        {saved}
      </pre>
    </div>
  );
}
