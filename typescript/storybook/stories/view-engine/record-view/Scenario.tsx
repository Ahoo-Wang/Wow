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

import { useMemo, useState } from 'react';
import type { AggregationQuery } from '@ahoo-wang/fetcher-wow';
import { readRecordValue } from '@ahoo-wang/fetcher-view-engine';
import {
  Button,
  TagsCell,
  ViewPage,
  type CellRendererProps,
  type GlobalActionsRendererProps,
  type RowActionsRendererProps,
  type ViewExtensions,
} from '@ahoo-wang/fetcher-view-engine/react';
import type {
  DemoArgs,
  ScenarioOptions,
  QueryDiagnostic,
  WriteDiagnostic,
} from './demoTypes.js';
import { currentUserId, definition, orders } from './fixtures.js';
import { createHost } from './createHost.js';

function OrderItems({ value }: CellRendererProps) {
  const labels = Array.isArray(value)
    ? value.map((item: unknown) => {
        if (
          typeof item !== 'object' ||
          item === null ||
          !('productName' in item) ||
          typeof item.productName !== 'string' ||
          !('quantity' in item) ||
          typeof item.quantity !== 'number'
        )
          return '—';
        return `${item.productName} × ${item.quantity}`;
      })
    : [];
  return <TagsCell value={labels} />;
}

export const orderCells = { 'order-items': OrderItems };

function OrderActions({
  selectedRowKeys,
  querying,
  refresh,
  runtime,
  onNotice,
  action,
}: GlobalActionsRendererProps & {
  runtime: ReturnType<typeof createHost>;
  action: 'create' | 'batch';
  onNotice: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  async function run(action: () => string) {
    setBusy(true);
    try {
      const message = action();
      await refresh();
      onNotice(message);
    } catch (error) {
      onNotice(error instanceof Error ? error.message : '订单操作失败。');
    } finally {
      setBusy(false);
    }
  }
  return action === 'create' ? (
    <Button
      disabled={querying || busy}
      onClick={() =>
        void run(
          () => `已创建订单 ${runtime.createOrder().aggregateId}，列表已刷新。`,
        )
      }
    >
      创建订单
    </Button>
  ) : (
    <Button
      variant="outline"
      title="开始处理选中的待处理订单"
      disabled={querying || busy || !selectedRowKeys.length}
      onClick={() =>
        void run(() => {
          const processed = runtime.processOrders(selectedRowKeys);
          return `已处理 ${processed} 笔订单，列表已刷新。`;
        })
      }
    >
      批量处理
    </Button>
  );
}

export function Scenario({
  appearance,
  ...options
}: DemoArgs & ScenarioOptions) {
  const [query, setQuery] = useState<QueryDiagnostic>({
    calls: 0,
    method: null,
    request: null,
  });
  const [writes, setWrites] = useState<WriteDiagnostic>({
    saves: 0,
    creates: 0,
    deletes: 0,
    renames: 0,
    instance: null,
  });
  const [notice, setNotice] = useState('');
  const [order, setOrder] = useState<{ calls: number; ids: string[] }>({
    calls: 0,
    ids: [],
  });
  const [summary, setSummary] = useState<{
    calls: number;
    request: AggregationQuery | null;
  }>({ calls: 0, request: null });
  // One stable host and instance list per mounted story, including when diagnostics update.
  const [runtime] = useState(() =>
    createHost(
      options,
      (method, request) => {
        setQuery(current => ({ calls: current.calls + 1, method, request }));
      },
      (operation, instance) => {
        setWrites(current => ({
          saves: current.saves + Number(operation === 'save'),
          creates: current.creates + Number(operation === 'create'),
          deletes: current.deletes + Number(operation === 'delete'),
          renames: current.renames + Number(operation === 'rename'),
          instance,
        }));
      },
      request => setSummary(current => ({ calls: current.calls + 1, request })),
      ids => setOrder(current => ({ calls: current.calls + 1, ids })),
    ),
  );
  const extensions = useMemo<ViewExtensions>(
    () => ({
      cells: orderCells,
      optionSources: { customers: runtime.customerOptions },
      globalActions: {
        'order-actions': props => (
          <OrderActions
            {...props}
            action="create"
            runtime={runtime}
            onNotice={setNotice}
          />
        ),
      },
      toolbarActions: {
        'order-table-actions': props => (
          <OrderActions
            {...props}
            action="batch"
            runtime={runtime}
            onNotice={setNotice}
          />
        ),
      },
      rowActions: {
        'order-row-actions': ({
          record,
          rowKey,
          instance,
        }: RowActionsRendererProps) => (
          <Button
            variant="ghost"
            size="sm"
            aria-label={`查看订单 ${rowKey}`}
            onClick={() =>
              setNotice(
                `订单 ${rowKey} · ${readRecordValue(record, 'state.customer')} · 负责人 ${readRecordValue(record, 'state.owner')} · ${readRecordValue(record, 'state.region')} · 当前视图：${instance.title}`,
              )
            }
          >
            查看订单
          </Button>
        ),
      },
    }),
    [runtime],
  );
  return (
    <div
      className="fve-root"
      data-theme={appearance}
      style={{
        background: 'var(--fve-background)',
        padding: 16,
      }}
    >
      <ViewPage
        scopeKey={currentUserId}
        definitionId={definition.id}
        host={runtime.host}
        definition={options.local ? definition : undefined}
        instances={options.local ? runtime.initialInstances : undefined}
        extensions={extensions}
        initialSidebarCollapsed={options.sidebarCollapsed}
        selectable
      />
      <div
        role="status"
        aria-label="订单操作结果"
        style={{ padding: '0 16px' }}
      >
        {notice}
      </div>
      <details
        style={{
          margin: '24px 16px 0',
          fontSize: 12,
          color: 'var(--fve-muted-foreground)',
        }}
      >
        <summary>开发者：查看宿主查询与保存结果</summary>
        <p>
          当前场景使用隔离的内存宿主。筛选、排序和分页在宿主执行；保存校验
          revision 并返回完整实例。 订单查询返回 Wow MaterializedSnapshot，根层
          aggregateId 作为行标识，state
          保存业务数据。金额单位为人民币元，订单金额等于商品小计之和，实付金额包含未付、部分支付与付清场景。
          下单时间取 firstEventTime，支付时间取 state.paidAt（未支付为
          null），均使用毫秒时间戳。 firstOperator 和 operator 保留 userId，字段
          options 提供姓名映射；查询与保存仍使用 ID。
        </p>
        <p>
          查询次数：
          <output data-testid="record-query-count">{query.calls}</output> ·
          保存次数：
          <output data-testid="record-save-count">{writes.saves}</output> ·
          新建视图：
          <output data-testid="record-create-count">{writes.creates}</output> ·
          改名次数：
          <output data-testid="record-rename-count">{writes.renames}</output> ·
          排序次数：
          <output data-testid="record-order-count">{order.calls}</output> ·
          删除视图：
          <output data-testid="record-delete-count">{writes.deletes}</output> ·
          汇总次数：
          <output data-testid="record-summary-count">{summary.calls}</output>
        </p>
        <pre
          data-testid="record-query"
          style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
        >
          {JSON.stringify({ method: query.method, ...query.request }, null, 2)}
        </pre>
        <pre
          data-testid="record-write"
          style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
        >
          {JSON.stringify(writes.instance, null, 2)}
        </pre>
        <pre
          data-testid="record-order"
          style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
        >
          {JSON.stringify(order.ids, null, 2)}
        </pre>
        <pre
          data-testid="record-summary-query"
          style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
        >
          {JSON.stringify(summary.request, null, 2)}
        </pre>
        <details>
          <summary>查看初始订单快照（Wow）</summary>
          <pre
            data-testid="record-snapshot-example"
            style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
          >
            {JSON.stringify(orders[0], null, 2)}
          </pre>
        </details>
      </details>
    </div>
  );
}
