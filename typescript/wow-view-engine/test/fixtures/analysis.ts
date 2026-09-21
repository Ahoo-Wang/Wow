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

/**
 * What the analysis kernel is exercised against: one orders definition whose
 * capability names a term field, a bucketable time and a number every
 * function may reach, and the config that definition admits. The four suites
 * — defaults and validation, element scope, compilation, projection — all
 * start here, so a rule that moves shows up in each of them at once.
 */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import type {
  AnalysisCapability,
  AnalysisViewConfig,
  DataViewDefinition,
  Issue,
} from '../../src/index.js';

/** The clock and zone every kernel call resolves relative times against. */
export const analysisContext = {
  now: new Date('2026-09-16T10:30:00.000Z'),
  timeZone: 'UTC',
};

export const analysisCapability: AnalysisCapability = {
  count: true,
  fields: [
    {
      field: 'warehouse',
      groups: [AggregationGroupType.TERMS],
      functions: [],
    },
    {
      field: 'createdAt',
      groups: [AggregationGroupType.DATE_HISTOGRAM],
      functions: [],
      dateUnits: [AggregationDateUnit.MONTH],
    },
    {
      field: 'amount',
      groups: [AggregationGroupType.HISTOGRAM],
      functions: [AggregationFunction.SUM, AggregationFunction.AVG],
      distinctCount: true,
      percentile: true,
      any: true,
    },
  ],
  expressions: true,
  having: true,
};

export function analysisDefinition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return {
    id: 'orders',
    title: 'Orders',
    kind: 'data',
    source: 'orders',
    fields: [
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
    ],
    analysis: analysisCapability,
    ...overrides,
  };
}

export function analysisKernelConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }],
    metrics: [{ type: 'COUNT', alias: 'orders' }],
    sort: [{ alias: 'orders', direction: 'DESC' }],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
    },
    ...overrides,
  };
}

/** The codes of the issues that stop a config, in the order they were found. */
export const errorCodes = (issues: Issue[]) =>
  issues.filter(i => i.severity === 'error').map(i => i.code);
