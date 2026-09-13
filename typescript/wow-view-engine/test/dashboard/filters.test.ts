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

import { expect, it } from 'vitest';
import {
  FilterOperator as Op,
  filter,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import type { FilterComponentConfig } from '../../src/filter/filterModel.js';
import {
  parseFilterOutput,
  type ProtocolNode,
} from '../../src/filter/filterProtocol.js';
import { createFilterConfiguration } from '../../src/filter/filterConfiguration.js';
import {
  compileDashboardScope,
  validateDashboardExpression,
} from '../../src/dashboard/dashboardFilters.js';
import { definition, instance } from '../engine/fixtures.js';
import type { DashboardFilter } from '../../src/dashboard/dashboardModel.js';

const source = {
  ...definition,
  fields: [
    { field: 'region', label: 'Region', type: 'string' as const },
    { field: 'status', label: 'Status', type: 'number' as const },
    {
      field: 'items',
      label: 'Items',
      type: 'array' as const,
      fields: [{ field: 'sku', label: 'SKU', type: 'string' as const }],
    },
  ],
};
const target = {
  ...definition,
  fields: [
    { field: 'state.region', label: 'Region', type: 'string' as const },
    { field: 'state.status', label: 'Status', type: 'number' as const },
    {
      field: 'lines',
      label: 'Lines',
      type: 'array' as const,
      fields: [{ field: 'code', label: 'Code', type: 'string' as const }],
    },
  ],
};
const panel = {
  kind: 'view' as const,
  id: 'orders',
  instanceId: 'mine',
  layout: { x: 0, y: 0, w: 6, h: 18 },
};
function configured(expression: FilterExpression): DashboardFilter {
  let id = 0;
  function node({
    op,
    field,
    operands,
    predicate,
    id: _id,
    ...props
  }: ProtocolNode): FilterComponentConfig {
    void _id;
    return {
      id: String(++id),
      operator: op,
      component: { name: 'builtin' },
      ...(field ? { field } : {}),
      props,
      ...(operands ? { operands: operands.map(node) } : {}),
      ...(predicate ? { predicate: node(predicate) } : {}),
    } as FilterComponentConfig;
  }
  return {
    id: 'global',
    filters: createFilterConfiguration(node(parseFilterOutput(expression))),
    excludedPanelIds: [],
    bindings: [
      {
        panelId: panel.id,
        kind: 'fields',
        semanticCompatibility: true,
        fields: {
          region: 'state.region',
          status: 'state.status',
          items: 'lines',
          'items.sku': 'lines.code',
        },
      },
    ],
  };
}
it('maps every OR branch and refuses incomplete field decisions', () => {
  const input = configured(
    filter.or([filter.eq('region', 'east'), filter.eq('status', 1)]),
  );
  expect(
    compileDashboardScope(input, panel, source, target, instance(), {}),
  ).toEqual(
    filter.or([
      filter.eq('state.region', 'east'),
      filter.eq('state.status', 1),
    ]),
  );
  const binding = input.bindings[0];
  if (binding.kind !== 'fields') throw new Error('fields expected');
  delete binding.fields.status;
  expect(() =>
    compileDashboardScope(input, panel, source, target, instance(), {}),
  ).toThrow();
});
it('maps search fields and element-relative paths without changing literal values', () => {
  expect(
    compileDashboardScope(
      configured(filter.search('region', { fields: ['region'] })),
      panel,
      source,
      target,
      instance(),
      {},
    ),
  ).toEqual(filter.search('region', { fields: ['state.region'] }));
  expect(
    compileDashboardScope(
      configured(filter.elementMatch('items', filter.eq('sku', 'region'))),
      panel,
      source,
      target,
      instance(),
      {},
    ),
  ).toEqual(filter.elementMatch('lines', filter.eq('code', 'region')));
  expect(() =>
    compileDashboardScope(
      configured(filter.search('text')),
      panel,
      source,
      target,
      instance(),
      {},
    ),
  ).toThrow('转换');
});
it('uses explicit host transformations and validates their full output', () => {
  const input = configured(filter.eq('status', 1));
  input.bindings = [{ panelId: panel.id, kind: 'transform', name: 'paid-v1' }];
  expect(() =>
    compileDashboardScope(input, panel, source, target, instance(), {}),
  ).toThrow();
  expect(
    compileDashboardScope(input, panel, source, target, instance(), {
      'paid-v1': () => filter.eq('state.status', 2),
    }),
  ).toEqual(filter.eq('state.status', 2));
  expect(() =>
    compileDashboardScope(input, panel, source, target, instance(), {
      'paid-v1': () =>
        filter.or([
          filter.eq('state.region', 'east'),
          filter.eq('missing', 'x'),
        ]),
    }),
  ).toThrow();
});
it('rejects dangerous paths, excessive depth and incompatible field types', () => {
  expect(() =>
    validateDashboardExpression(
      { op: Op.EQ, field: '__proto__.x', value: 1 },
      target,
    ),
  ).toThrow();
  let expression = filter.eq('state.status', 1);
  for (let index = 0; index < 33; index++)
    expression = filter.and([expression]);
  expect(() => validateDashboardExpression(expression, target)).toThrow();
  const input = configured(filter.eq('region', 'east'));
  expect(() =>
    compileDashboardScope(
      input,
      panel,
      source,
      {
        ...target,
        fields: [{ field: 'state.region', label: 'Region', type: 'number' }],
      },
      instance(),
      {},
    ),
  ).toThrow();
});
