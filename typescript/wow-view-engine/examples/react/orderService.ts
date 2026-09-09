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
  FilterOperator,
  type FilterExpression,
  type PagedList,
  type PagedQueryRequest,
} from '@ahoo-wang/fetcher-wow';
import type {
  RecordData,
  RecordKey,
  RecordQuerySource,
  ViewHost,
} from '@ahoo-wang/fetcher-view-engine';
import { initialOrders, orderDefinition, type Order } from './orders.js';

export type OrderEvent =
  | { type: 'query'; filter: FilterExpression }
  | { type: 'created'; id: string }
  | { type: 'processed'; ids: string[] };
export interface OrderServiceOptions {
  failFirstRead?: boolean;
  failFirstWrite?: boolean;
  failRefreshAfterWrite?: boolean;
  onEvent?(event: OrderEvent): void;
}
function delay(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, milliseconds);
    function abort() {
      clearTimeout(timer);
      reject(signal?.reason);
    }
    signal?.addEventListener('abort', abort, { once: true });
  });
}
// ponytail: only the advertised status EQ/AND/MATCH_ALL paged queries are needed here; replace this service with a real QueryApi for more operators.
function predicate(node: FilterExpression): (order: Order) => boolean {
  if (node.op === FilterOperator.MATCH_ALL && Object.keys(node).length === 1)
    return () => true;
  if (
    node.op === FilterOperator.AND &&
    Object.keys(node).every(key => key === 'op' || key === 'operands')
  ) {
    const operands = node.operands.map(predicate);
    return order => operands.every(matches => matches(order));
  }
  if (
    node.op === FilterOperator.EQ &&
    node.field === 'status' &&
    (node.value === 'pending' || node.value === 'processed') &&
    Object.keys(node).every(key => ['op', 'field', 'value'].includes(key))
  ) {
    const status = node.value;
    return order => order.status === status;
  }
  throw new Error('本地订单服务仅支持状态 EQ、AND 与 MATCH_ALL 查询。');
}
export function createOrderService(options: OrderServiceOptions = {}) {
  let orders: Order[] = structuredClone([...initialOrders]);
  let nextId = 4;
  let failRead = options.failFirstRead ?? false;
  let failWrite = options.failFirstWrite ?? false;
  let failRefresh = options.failRefreshAfterWrite ?? false;
  const source: RecordQuerySource = {
    async paged<T extends Partial<RecordData> = RecordData>(
      query: PagedQueryRequest,
      _attributes?: Record<string, unknown>,
      controller?: AbortController,
    ): Promise<PagedList<T>> {
      if (
        !('filter' in query) ||
        Object.keys(query).some(
          key => !['filter', 'pagination', 'sort', 'projection'].includes(key),
        ) ||
        query.sort?.length ||
        Object.keys(query.projection ?? {}).length
      )
        throw new Error('本地订单服务不支持条件 DSL、排序或字段投影。');
      const matches = predicate(query.filter);
      const { index = 1, size = 10 } = query.pagination ?? {};
      if (
        Object.keys(query.pagination ?? {}).some(
          key => key !== 'index' && key !== 'size',
        ) ||
        !Number.isSafeInteger(index) ||
        index < 1 ||
        !Number.isSafeInteger(size) ||
        size < 1
      )
        throw new Error('分页参数无效。');
      options.onEvent?.({
        type: 'query',
        filter: structuredClone(query.filter),
      });
      await delay(50, controller?.signal);
      if (failRead) {
        failRead = false;
        throw new Error('订单查询暂时失败，请重试。');
      }
      const matched: RecordData[] = orders.filter(matches);
      return {
        list: structuredClone(
          matched.slice((index - 1) * size, index * size),
        ) as T[],
        total: matched.length,
      };
    },
  };
  const host: ViewHost = {
    resolveSource(sourceId) {
      if (sourceId !== orderDefinition.sourceId)
        throw new Error('未知订单数据源。');
      return source;
    },
    permission: {
      getInstance: () => ({
        save: false,
        saveAsPersonal: false,
        saveAsShared: false,
      }),
    },
  };
  async function beforeWrite() {
    await delay(350);
    if (failWrite) {
      failWrite = false;
      throw new Error('订单处理暂时失败，请重试。');
    }
  }
  function afterWrite(event: OrderEvent) {
    if (failRefresh) {
      failRead = true;
      failRefresh = false;
    }
    options.onEvent?.(event);
  }
  return {
    host,
    source,
    async createOrder() {
      await beforeWrite();
      const order: Order = {
        id: `DEMO-${nextId++}`,
        customer: '新客户',
        amount: 99.5,
        status: 'pending',
      };
      orders.push(order);
      afterWrite({ type: 'created', id: order.id });
      return { ...order };
    },
    async saveOrder(draft: RecordData) {
      if (
        typeof draft.id !== 'string' ||
        typeof draft.customer !== 'string' ||
        typeof draft.amount !== 'number' ||
        !Number.isFinite(draft.amount) ||
        (draft.status !== 'pending' && draft.status !== 'processed')
      )
        throw new Error('订单数据无效。');
      const index = orders.findIndex(order => order.id === draft.id);
      if (index < 0) throw new Error('订单不存在。');
      const order: Order = {
        id: draft.id,
        customer: draft.customer,
        amount: draft.amount,
        status: draft.status,
      };
      await beforeWrite();
      orders[index] = order;
      afterWrite({ type: 'processed', ids: [order.id] });
    },
    async processOrders(keys: RecordKey[]) {
      if (
        !keys.length ||
        new Set(keys).size !== keys.length ||
        keys.some(key => !orders.some(order => order.id === key))
      )
        throw new Error('请选择有效且不重复的订单。');
      await beforeWrite();
      orders = orders.map(order =>
        keys.includes(order.id) ? { ...order, status: 'processed' } : order,
      );
      afterWrite({ type: 'processed', ids: keys.map(String) });
    },
  };
}
export type OrderService = ReturnType<typeof createOrderService>;
