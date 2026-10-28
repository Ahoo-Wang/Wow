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

import type {
  DataViewDefinition,
  FieldOption,
  RecordData,
  RecordViewConfig,
  ViewInstance,
} from '../../src/index.js';
import { ordersDefinition, recordConfig } from '../fixtures.js';
import { AggregationFunction } from '@ahoo-wang/wow-client/dsl';

/**
 * Deals moving through three stages (D83): the kanban suites' definition,
 * config and documents.
 */
export const STAGES: FieldOption[] = [
  { value: 'LEAD', label: 'Lead' },
  { value: 'WON', label: 'Won', tone: 'success' },
  { value: 'LOST', label: 'Lost', tone: 'danger' },
];

export function dealsDefinition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Deal', kind: 'string', sortable: true },
      { name: 'stage', label: 'Stage', kind: 'enum', options: STAGES },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        summary: [AggregationFunction.SUM],
        sortable: true,
      },
    ],
    record: {
      rowKey: 'id',
      paging: 'cursor',
      layouts: ['table', 'card', 'kanban'],
    },
    id: 'deals',
    title: 'Deals',
    source: 'deals',
    analysis: undefined,
    views: [],
    ...overrides,
  });
}

export function kanbanConfig(
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return recordConfig({
    layout: 'kanban',
    kanban: { lane: 'stage' },
    ...overrides,
  });
}

/** Three leads, a win, one deal with no stage and one in a stage long gone. */
export const DEALS: readonly RecordData[] = [
  { id: 'd-1', stage: 'LEAD', amount: 10 },
  { id: 'd-2', stage: 'LEAD', amount: 20 },
  { id: 'd-3', stage: 'LEAD', amount: 30 },
  { id: 'd-4', stage: 'WON', amount: 50 },
  { id: 'd-5', amount: 5 },
  { id: 'd-6', stage: 'OLD', amount: 6 },
];

/** A saved kanban of the deals, two cards a lane at a time. */
export const dealsKanban: ViewInstance = {
  id: 'deals-kanban',
  definitionId: 'deals',
  title: 'Pipeline',
  scope: 'personal',
  revision: '1',
  config: kanbanConfig({ pageSize: 2 }),
};
