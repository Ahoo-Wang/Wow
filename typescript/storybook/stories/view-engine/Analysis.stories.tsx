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
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  AggregationFunction,
  AggregationGroupType,
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import {
  MemoryViewHost,
  createFilterConfiguration,
  type ViewDefinition,
  type ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import { useViewEngine, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import { createOrderSource } from '../../packages/view-engine/examples/react/sales-order/querySource.js';

const definition: ViewDefinition = {
  id: 'analysis-orders',
  title: '订单工作台',
  sourceId: 'orders',
  fields: [
    { field: 'id', label: '订单编号', type: 'string' },
    { field: 'region', label: '地区', type: 'string' },
    { field: 'amount', label: '订单金额', type: 'number' },
  ],
  record: { rowKey: 'id', allowedLayouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      { field: 'region', groups: [AggregationGroupType.TERMS], functions: [] },
      {
        field: 'amount',
        groups: [],
        functions: [AggregationFunction.SUM, AggregationFunction.AVG],
      },
    ],
  },
};
const filters = createFilterConfiguration({
  id: 'all',
  component: { name: 'builtin' },
  operator: FilterOperator.MATCH_ALL,
  props: {},
});
const instances: ViewInstance[] = [
  {
    id: 'records',
    definitionId: definition.id,
    kind: 'record',
    title: '订单记录',
    revision: '1',
    scope: { type: 'personal' },
    config: {
      filters,
      sort: [],
      pagination: { mode: 'paged', size: 10 },
      presentation: {
        layout: 'table',
        table: {
          columns: [
            { id: 'id', kind: 'field', field: 'id' },
            { id: 'region', kind: 'field', field: 'region' },
            { id: 'amount', kind: 'field', field: 'amount' },
          ],
        },
      },
    },
  },
  {
    id: 'regional-analysis',
    definitionId: definition.id,
    kind: 'analysis',
    title: '地区销售分析',
    revision: '1',
    scope: { type: 'public', source: 'system' },
    config: {
      filters,
      dimensions: [
        {
          id: 'region',
          component: { name: 'terms' },
          field: 'region',
          alias: 'region',
          title: '地区',
          props: {},
        },
      ],
      metrics: [
        {
          id: 'count',
          component: { name: 'count' },
          alias: 'orders',
          title: '订单数',
          props: {},
        },
        {
          id: 'amount',
          component: { name: 'numeric' },
          field: 'amount',
          alias: 'revenue',
          title: '销售额',
          props: { function: AggregationFunction.SUM },
        },
      ],
      sort: [],
      limit: 100,
      presentation: { layout: 'table', columns: [] },
    },
  },
];
const orders = [
  { id: 'SO-001', region: '华东', amount: 1200 },
  { id: 'SO-002', region: '华南', amount: 800 },
  { id: 'SO-003', region: '华东', amount: 600 },
];
function OrderAnalysis({ analysisOnly = false }: { analysisOnly?: boolean }) {
  const [host] = useState(() => {
    const source = createOrderSource(() => orders);
    return new MemoryViewHost({
      serviceKey: 'analysis-example',
      scopeKey: 'demo',
      definition: analysisOnly
        ? { ...definition, record: undefined }
        : definition,
      instances: {
        instances: analysisOnly
          ? instances.filter(instance => instance.kind === 'analysis')
          : instances,
        defaultInstanceId: 'regional-analysis',
      },
      resolveSource: () =>
        analysisOnly ? { aggregate: source.aggregate } : source,
      instancePermissions: instance => ({
        save: instance.scope.type === 'personal',
        saveAsPersonal: true,
        saveAsShared: false,
        rename: instance.scope.type === 'personal',
        delete: instance.scope.type === 'personal',
      }),
    });
  });
  const binding = useViewEngine({
    scopeKey: 'demo',
    definitionId: definition.id,
    host,
  });
  return <ViewPage {...binding} />;
}
const meta = {
  id: 'view-engine-分析视图',
  title: 'View Engine/分析视图/配置与执行',
  component: OrderAnalysis,
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof OrderAnalysis>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Mixed: Story = { name: '订单记录与地区分析' };
export const AnalysisOnly: Story = {
  name: '仅聚合数据源',
  args: { analysisOnly: true },
};
