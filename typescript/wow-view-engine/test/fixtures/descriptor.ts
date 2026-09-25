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
  AggregationDatePart,
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  FilterOperator,
  PagingMode,
  QueryValueKind,
  type FieldDescriptor,
  type QueryDescriptorResult,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';

/** Every operator Wow has: a field listing these admits whatever a kind offers. */
export const ALL_OPERATORS = Object.values(FilterOperator);

/** Every numeric function Wow has. */
export const ALL_FUNCTIONS = Object.values(AggregationFunction);

/**
 * One queryable field that admits everything: every operator, both sorts,
 * every group and function and metric type. A suite takes away what it is
 * about, so a narrowing it did not ask for never shows up in its findings.
 */
export function describedField(
  path: string,
  overrides: Partial<FieldDescriptor> = {},
): FieldDescriptor {
  return {
    path,
    types: ['STRING'],
    kind: QueryValueKind.SCALAR,
    nullable: true,
    project: true,
    filter: { operators: [...ALL_OPERATORS] },
    sort: { paged: true, cursor: true },
    aggregate: {
      groups: Object.values(AggregationGroupType),
      missingKey: true,
      functions: [...ALL_FUNCTIONS],
      distinctCount: true,
      percentile: true,
      any: true,
      expressionInput: true,
      inMetricFilter: true,
    },
    aliases: [],
    ...overrides,
  };
}

/**
 * The descriptor of `ordersDefinition`'s source that admits everything the
 * definition declares, so narrowing against it changes no field and finds
 * nothing. `version` is what a suite changes to stand for a new one.
 */
export function ordersDescriptor(
  overrides: Partial<QueryModelDescriptor> = {},
): QueryModelDescriptor {
  return {
    model: 'SNAPSHOT',
    version: 'sha256:orders-1',
    timeZone: 'UTC',
    record: {
      identity: 'id',
      paging: [PagingMode.LIST, PagingMode.PAGED, PagingMode.CURSOR],
      rootOperators: [
        FilterOperator.ID,
        FilterOperator.IDS,
        FilterOperator.AGGREGATE_ID,
        FilterOperator.AGGREGATE_IDS,
        FilterOperator.TENANT_ID,
        FilterOperator.OWNER_ID,
        FilterOperator.SPACE_ID,
        FilterOperator.DELETION,
      ],
    },
    limits: {
      maxListSize: 1000,
      defaultListSize: 10,
      maxPageSize: 100,
      maxPageWindow: 10_000,
      maxFilterNodes: 256,
      maxFilterValues: 1000,
      maxSortFields: 8,
      aggregation: {
        maxGroups: 8,
        maxMetrics: 16,
        maxElements: 4,
        maxLimit: 1000,
        maxExpressionDepth: 8,
        maxExpressionNodes: 64,
      },
    },
    analysis: {
      metrics: Object.values(AggregationMetricType),
      expressions: true,
      having: { metrics: Object.values(AggregationMetricType) },
      sort: { groups: true, metrics: true },
      dense: true,
      approximate: [],
      dateUnits: Object.values(AggregationDateUnit),
      dateParts: Object.values(AggregationDatePart),
    },
    fields: ['id', 'warehouse', 'status', 'amount'].map(path =>
      describedField(path),
    ),
    elements: [],
    dynamic: [],
    constraints: [],
    ...overrides,
  };
}

/** A descriptor as `describe` answers it the first time, or after a change. */
export function read(descriptor: QueryModelDescriptor): QueryDescriptorResult {
  return { notModified: false, descriptor, version: descriptor.version };
}

/** The answer to a `describe` sent the version still current. */
export function notModified(version: string): QueryDescriptorResult {
  return { notModified: true, version };
}
