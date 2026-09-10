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

import { type AggregationQuery } from '@ahoo-wang/fetcher-wow';
import {
  type RecordKey,
  type FilterOptionSource,
} from '@ahoo-wang/fetcher-view-engine';
import { createOrderSource as createSharedSource } from '../../../packages/view-engine/examples/react/sales-order/querySource.js';
import type { DemoQuery, ScenarioOptions } from './demoTypes.js';
import {
  createOrderSnapshot,
  currentUserId,
  orders,
  pause,
} from './fixtures.js';

export function createOrderSource(
  {
    empty = false,
    failFirstQuery = false,
    failFirstSummary = false,
  }: ScenarioOptions,
  onQuery: (method: 'paged' | 'cursor', request: DemoQuery) => void,
  onSummary: (query: AggregationQuery) => void,
) {
  let records = structuredClone(empty ? [] : orders);
  let nextOrder = 1019;
  const source = createSharedSource(() => records, {
    failFirstQuery,
    failFirstSummary,
    onQuery,
    onSummary,
  });
  return {
    source,
    customerOptions: {
      async search({ search, cursor, size = 3 }, signal) {
        signal.throwIfAborted();
        await pause();
        signal.throwIfAborted();
        const values = [
          ...new Set(records.map(record => record.state.customer)),
        ];
        const filtered = values.filter(value => value.includes(search));
        const offset = cursor ? Number(cursor) : 0;
        if (!Number.isSafeInteger(offset) || offset < 0)
          throw new Error('无效的客户游标。');
        return {
          list: filtered
            .slice(offset, offset + size)
            .map(value => ({ value, label: value })),
          nextCursor:
            offset + size < filtered.length ? String(offset + size) : null,
        };
      },
      async resolve(values, signal) {
        signal.throwIfAborted();
        await pause();
        signal.throwIfAborted();
        const available = new Set<unknown>(
          records.map(record => record.state.customer),
        );
        return {
          list: values
            .filter(value => available.has(value))
            .map(value => ({ value, label: String(value) })),
          missing: values.filter(value => !available.has(value)),
        };
      },
    } satisfies FilterOptionSource,
    createOrder() {
      const order = createOrderSnapshot(
        {
          ...structuredClone(orders[16].state),
          id: `ORD-202609-${nextOrder++}`,
          customer: '新叶商贸',
          paidAmount: 0,
          status: 'pending',
          paidAt: null,
        },
        'sales-2',
        Date.parse('2026-09-06T12:30:00+08:00'),
        currentUserId,
      );
      records.push(order);
      return order;
    },
    processOrders(keys: readonly RecordKey[]) {
      let processed = 0;
      records = records.map(record => {
        if (
          !keys.includes(record.aggregateId) ||
          record.state.status !== 'pending'
        )
          return record;
        processed++;
        const eventTime = Math.max(Date.now(), record.eventTime + 1);
        return {
          ...record,
          version: record.version + 1,
          eventId: `event-${record.aggregateId}-${record.version + 1}`,
          operator: currentUserId,
          eventTime,
          snapshotTime: eventTime,
          state: { ...record.state, status: 'processing' as const },
        };
      });
      return processed;
    },
  };
}
