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
import { fn } from 'storybook/test';
import {
  AggregationGroupType as G,
  AggregationFunction as F,
  FilterOperator as Op,
  HavingExpressionType as H,
  ComparisonOperator as C,
  DerivedExpressionType as D,
  AggregationExpressionOperator as O,
  type AggregationQuery,
} from '@ahoo-wang/fetcher-wow';
import {
  compileAnalysis,
  type AnalysisViewInstance,
  type ViewDefinition,
} from '@ahoo-wang/fetcher-view-engine';
export const extendedDefinition: ViewDefinition = {
  id: 'extended-analysis',
  title: '渠道订单分析',
  sourceId: 'fixed-fixture',
  fields: [
    { field: 'channel', label: '渠道', type: 'string' },
    { field: 'customer', label: '客户', type: 'string' },
    { field: 'status', label: '支付状态', type: 'string' },
    { field: 'amount', label: '支付金额', type: 'number' },
  ],
  analysis: {
    count: true,
    expressions: true,
    features: {
      distinctCount: true,
      percentile: true,
      metricFilters: true,
      derived: true,
      having: true,
    },
    fields: [
      { field: 'channel', groups: [G.TERMS], functions: [] },
      { field: 'customer', groups: [], functions: [], distinctCount: true },
      { field: 'status', groups: [], functions: [] },
      {
        field: 'amount',
        groups: [],
        functions: [F.SUM],
        percentile: true,
        unit: 'CNY',
      },
    ],
  },
};
export const extendedInstance: AnalysisViewInstance = {
  id: 'extended',
  definitionId: 'extended-analysis',
  kind: 'analysis',
  title: '渠道支付分析',
  revision: '1',
  scope: { type: 'personal' },
  config: {
    filters: {
      mode: 'simple',
      root: {
        id: 'all',
        component: { name: 'builtin' },
        operator: Op.MATCH_ALL,
        props: {},
      },
    },
    dimensions: [
      {
        id: 'channel',
        alias: 'channel',
        field: 'channel',
        title: '渠道',
        component: { name: 'terms' },
        props: {},
      },
    ],
    metrics: [
      {
        id: 'orders',
        alias: 'orders',
        title: '订单数',
        component: { name: 'count' },
        props: {},
      },
      {
        id: 'paid',
        alias: 'paid',
        title: '已支付订单数',
        component: { name: 'count' },
        props: {},
        filters: {
          mode: 'simple',
          root: {
            id: 'paid-filter',
            component: { name: 'builtin' },
            operator: Op.EQ,
            field: 'status',
            props: { value: 'PAID' },
          },
        },
      },
      {
        id: 'customers',
        alias: 'customers',
        field: 'customer',
        title: '去重客户数',
        component: { name: 'distinct-count' },
        props: {},
      },
      {
        id: 'p95',
        alias: 'p95',
        field: 'amount',
        title: '支付金额 P95',
        component: { name: 'percentile' },
        props: { percentile: 95 },
      },
      {
        id: 'rate',
        alias: 'rate',
        title: '支付率',
        component: { name: 'derived' },
        props: { displayFormat: 'percent' },
        derivedExpression: {
          type: D.BINARY,
          operator: O.DIVIDE,
          left: { type: D.METRIC_REF, metricId: 'paid' },
          right: { type: D.METRIC_REF, metricId: 'orders' },
        },
      },
    ],
    having: {
      id: 'minimum',
      type: H.CONDITION,
      metricId: 'orders',
      operator: C.GTE,
      value: 100,
    },
    sort: [],
    limit: 100,
    presentation: { layout: 'table', columns: [] },
  },
};
const expected = compileAnalysis(extendedInstance.config, {
  fields: extendedDefinition.fields,
  capability: extendedDefinition.analysis!,
}).plan!.query;
export const extendedAggregate = fn(async (query: AggregationQuery) => {
  if (JSON.stringify(query) !== JSON.stringify(expected))
    throw new Error('固定响应仅支持预置统计口径，请接入真实 Wow 数据源');
  return [
    {
      channel: '线上',
      orders: 200,
      paid: 150,
      customers: 120,
      p95: 980,
      rate: 0.75,
    },
  ];
});
