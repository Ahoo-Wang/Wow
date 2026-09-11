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
  newFilterNode,
  createFilterConfiguration,
} from '../../src/filter/filterCore.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { vi } from 'vitest';
import { ViewEngine } from '../../src/engine/ViewEngine.js';
import type {
  RecordColumn,
  ViewDefinition,
  ViewInstance,
} from '../../src/contracts/viewModel.js';
import { getRecordSummaryMetrics } from '../../src/record/recordPresentation.js';

export function metricsFor(columns: RecordColumn[]) {
  return getRecordSummaryMetrics({ layout: 'table', table: { columns } });
}

export const columns: RecordColumn[] = [
  { id: 'sum', kind: 'field', field: 'amount', summary: ['SUM'] },
  { id: 'avg', kind: 'field', field: 'amount', summary: ['AVG'] },
  { id: 'min', kind: 'field', field: 'amount', summary: ['MIN'] },
  { id: 'max', kind: 'field', field: 'amount', summary: ['MAX'] },
];

export const metrics = metricsFor(columns);

export const definition: ViewDefinition = {
  id: 'orders',
  title: '订单',
  sourceId: 'orders',
  record: { allowedLayouts: ['table', 'card'], rowKey: 'id' },
  fields: [
    { field: 'id', label: '编号', type: 'string' },
    { field: 'amount', label: '金额', type: 'number', sortable: true },
  ],
};

export function setup() {
  const instance: ViewInstance = {
    id: 'mine',
    definitionId: 'orders',
    title: '我的订单',
    kind: 'record',
    revision: 'initial',
    scope: { type: 'personal' },
    config: {
      filters: createFilterConfiguration(
        newFilterNode(FilterOperator.MATCH_ALL),
      ),
      sort: [],
      pagination: { mode: 'paged', size: 2 },
      presentation: {
        layout: 'table',
        table: {
          columns: [
            { id: 'amount', kind: 'field', field: 'amount', summary: ['SUM'] },
            { id: 'id', kind: 'field', field: 'id' },
          ],
        },
      },
    },
  };
  const source = {
    paged: vi.fn().mockResolvedValue({
      list: [
        { id: 'a', amount: 2 },
        { id: 'b', amount: 3 },
      ],
      total: 3,
    }),
    cursor: vi.fn().mockResolvedValue({
      list: [{ id: 'a', amount: 2 }],
      nextCursor: 'next',
    }),
    aggregate: vi.fn().mockResolvedValue([{ summary0: 30 }]),
  };
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host: { resolveSource: () => source },
  });
  return { engine, source, instance };
}

export const session = (engine: ViewEngine) =>
  engine.getSnapshot().sessions.mine;

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
