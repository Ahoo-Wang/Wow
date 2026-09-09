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
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  LocalStorageViewHost,
  createFilterConfiguration,
  type RecordData,
  type ViewDefinition,
  type ViewInstanceList,
} from '@ahoo-wang/fetcher-view-engine';
import {
  Button,
  ViewPage,
  TextCell,
  TagsCell,
  StatusCell,
  LinkCell,
  DateTimeCell,
  NumberCell,
} from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

const definition: ViewDefinition = {
  id: 'builtin-cells',
  title: '内置单元格',
  sourceId: 'cell-examples',
  rowKey: 'id',
  timeZone: 'Asia/Shanghai',
  allowedOperators: [FilterOperator.MATCH_ALL],
  fields: [
    { field: 'id', label: '编号', type: 'string' },
    {
      field: 'status',
      label: '状态',
      type: 'string',
      options: [
        { value: 'done', label: '已完成' },
        { value: 'pending', label: '待处理' },
      ],
    },
    {
      field: 'tags',
      label: '标签',
      type: 'array',
      options: [
        { value: 1, label: '数字一' },
        { value: '1', label: '字符串一' },
      ],
    },
    { field: 'link', label: '链接', type: 'string' },
    {
      field: 'createdAt',
      label: '创建时间',
      type: 'datetime',
    },
    {
      field: 'amount',
      label: '金额',
      type: 'number',
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
    {
      field: 'ratio',
      label: '比例',
      type: 'number',
      numberFormat: { style: 'percent', maximumFractionDigits: 1 },
    },
  ],
};
const instances: ViewInstanceList = {
  defaultInstanceId: 'mine',
  instances: [
    {
      id: 'mine',
      definitionId: definition.id,
      title: '我的单元格',
      kind: 'record',
      scope: { type: 'personal' },
      revision: '1',
      config: {
        filters: createFilterConfiguration({
          id: 'all',
          operator: FilterOperator.MATCH_ALL,
          component: { name: 'builtin' },
          props: {},
        }),
        sort: [],
        pagination: { mode: 'paged', size: 5 },
        presentation: {
          layout: 'table',
          table: {
            columns: [
              {
                id: 'id',
                kind: 'field',
                field: 'id',
                width: 220,
                renderer: {
                  name: 'text',
                  options: { ellipsis: true, copyable: true },
                },
              },
              {
                id: 'status',
                kind: 'field',
                field: 'status',
                width: 120,
                renderer: {
                  name: 'status',
                  options: {
                    tones: [
                      { value: 'done', tone: 'success' },
                      { value: 'pending', tone: 'warning' },
                    ],
                  },
                },
              },
              {
                id: 'tags',
                kind: 'field',
                field: 'tags',
                width: 220,
                renderer: { name: 'tags', options: { maxVisible: 2 } },
              },
              {
                id: 'link',
                kind: 'field',
                field: 'link',
                width: 130,
                renderer: {
                  name: 'link',
                  options: { hrefField: 'url', newTab: true },
                },
              },
              {
                id: 'createdAt',
                kind: 'field',
                field: 'createdAt',
                width: 220,
                renderer: {
                  name: 'date-time',
                  options: { dateStyle: 'short', timeStyle: 'short' },
                },
              },
              {
                id: 'amount',
                kind: 'field',
                field: 'amount',
                width: 150,
                renderer: { name: 'number' },
              },
              {
                id: 'ratio',
                kind: 'field',
                field: 'ratio',
                width: 100,
                renderer: { name: 'number' },
              },
            ],
          },
        },
      },
    },
  ],
};
const records: RecordData[] = [
  {
    id: 'ORDER-20260908-000001',
    status: 'done',
    tags: [1, '1', '需要回访', '重点客户'],
    link: '查看订单',
    url: 'https://example.com/orders/1',
    createdAt: '2026-09-08T02:30:00Z',
    amount: 1234.5,
    ratio: 0.125,
  },
  {
    id: 'ORDER-20260908-000002',
    status: 'pending',
    tags: ['普通客户'],
    link: '联系负责人',
    url: 'mailto:demo@example.com',
    createdAt: 0,
    amount: 0,
    ratio: 0,
  },
  {
    id: 'ORDER-20260908-000003',
    status: 'archived',
    tags: [],
    link: null,
    url: null,
    createdAt: null,
    amount: null,
    ratio: null,
  },
];
export interface BuiltinCellsExampleProps {
  appearance?: 'light' | 'dark';
  persist?: boolean;
  scopeKey?: string;
  invalidData?: boolean;
}
export function BuiltinCellsExample(props: BuiltinCellsExampleProps) {
  return (
    <CellSession
      key={`${props.scopeKey ?? 'storybook'}:${props.persist ?? false}:${props.invalidData ?? false}`}
      {...props}
    />
  );
}
function CellSession({
  appearance = 'light',
  persist = false,
  scopeKey = 'storybook',
  invalidData = false,
}: BuiltinCellsExampleProps) {
  const [generation, setGeneration] = useState(0);
  const [saved, setSaved] = useState('');
  const [createHost] = useState(() => {
    const memory = new Map<string, string>();
    const storage = persist
      ? localStorage
      : {
          getItem: (key: string) => memory.get(key) ?? null,
          setItem: (key: string, value: string) => {
            memory.set(key, value);
          },
          removeItem: (key: string) => {
            memory.delete(key);
          },
        };
    const rows = invalidData
      ? [
          {
            ...records[0],
            tags: { invalid: true },
            link: '不可访问的地址',
            url: 'javascript:alert(1)',
            createdAt: '2026-02-30',
            amount: '¥1,234.50',
            ratio: 'not-a-number',
          },
        ]
      : records;
    return () =>
      new LocalStorageViewHost({
        serviceKey: 'builtin-cell-demo',
        scopeKey,
        storage,
        definition,
        instances,
        lock: (name, operation, signal) =>
          navigator.locks.request(name, { signal }, operation),
        resolveSource: () => ({
          paged: async <T extends Partial<RecordData> = RecordData>() => ({
            list: structuredClone(rows) as T[],
            total: rows.length,
          }),
        }),
      });
  });
  const [host, setHost] = useState(createHost);
  return (
    <div
      className="fve-root"
      data-theme={appearance}
      style={{ padding: 16, background: 'var(--fve-background)' }}
    >
      <p>
        固定示例数据，用于验证内置渲染和列配置恢复。可在列设置中隐藏列、保存，再重新打开。
      </p>
      <div className="fve:mb-3 fve:flex fve:flex-wrap fve:gap-2">
        <Button
          variant="outline"
          onClick={() => {
            setHost(createHost());
            setGeneration(value => value + 1);
          }}
        >
          重新打开已保存视图
        </Button>
        <Button
          variant="outline"
          onClick={async () =>
            setSaved(JSON.stringify(await host.instance.load('mine'), null, 2))
          }
        >
          查看保存 JSON
        </Button>
        <Button
          variant="outline"
          onClick={async () => {
            await host.reset();
            setHost(createHost());
            setGeneration(value => value + 1);
            setSaved('');
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
      />
      <details className="fve:mt-3">
        <summary>保存的组件配置</summary>
        <pre
          data-testid="builtin-cells-saved"
          role="region"
          tabIndex={0}
          aria-label="保存的单元格 JSON"
          className="fve:overflow-auto"
        >
          {saved}
        </pre>
      </details>
    </div>
  );
}
export function StandaloneCellsExample({
  appearance = 'light',
}: {
  appearance?: 'light' | 'dark';
}) {
  return (
    <div
      className="fve-root"
      data-theme={appearance}
      style={{ padding: 16, background: 'var(--fve-background)' }}
    >
      <dl className="fve:grid fve:grid-cols-[5rem_minmax(0,1fr)] fve:items-center fve:gap-4">
        <dt>可复制文本</dt>
        <dd className="fve:m-0 fve:min-w-0">
          <TextCell value="ORDER-20260908-000001" ellipsis copyable />
        </dd>
        <dt>连续长文本</dt>
        <dd
          className="fve:m-0 fve:min-w-0"
          style={{ maxWidth: 220 }}
          data-testid="long-text-cell"
        >
          <TextCell value={'A'.repeat(100)} copyable />
        </dd>
        <dt>标签</dt>
        <dd className="fve:m-0 fve:min-w-0">
          <TagsCell
            value={['优先', '客户回访', '需要完整展示的超长标签内容与业务备注']}
            maxVisible={2}
          />
        </dd>
        <dt>状态</dt>
        <dd className="fve:m-0">
          <StatusCell
            value="done"
            options={[{ value: 'done', label: '已完成' }]}
            tones={[{ value: 'done', tone: 'success' }]}
          />
        </dd>
        <dt>链接</dt>
        <dd className="fve:m-0">
          <LinkCell value="联系负责人" href="mailto:demo@example.com" />
        </dd>
        <dt>日期时间</dt>
        <dd className="fve:m-0">
          <DateTimeCell value="2026-09-08T02:30:00Z" timeZone="Asia/Shanghai" />
        </dd>
        <dt>金额</dt>
        <dd className="fve:m-0">
          <NumberCell
            value={1234.5}
            format={{ style: 'currency', currency: 'CNY' }}
          />
        </dd>
        <dt>百分比</dt>
        <dd className="fve:m-0">
          <NumberCell
            value={0.125}
            format={{ style: 'percent', maximumFractionDigits: 1 }}
          />
        </dd>
      </dl>
    </div>
  );
}
