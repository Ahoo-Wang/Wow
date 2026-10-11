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
  PagingMode,
  QueryModels,
  QueryValueKind,
  SensitivityLevel,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import { defineDataset } from '../src/index.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

/** A wide table as a dataset's descriptor says it: root columns only. */
function dataset(
  overrides: Partial<QueryModelDescriptor> = {},
): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    model: QueryModels.DATASET,
    version: 'sha256:order-wide-1',
    record: { ...base.record, identity: 'order_id', rootOperators: [] },
    fields: [
      describedField('order_id'),
      describedField('tenant_id', { role: 'TENANT_ID' }),
      describedField('city', { description: 'City' }),
      describedField('amount', { types: ['DECIMAL'], description: 'GMV' }),
      describedField('updated_at', { semantic: { type: 'TEMPORAL_DATE' } }),
      describedField('depts', { kind: QueryValueKind.ARRAY }),
      describedField('phone', {
        sensitivity: { level: SensitivityLevel.DISPLAY, comparable: true },
      }),
    ],
    elements: [],
    ...overrides,
  };
}

describe('defineDataset', () => {
  it('lists every column, in the descriptor order, labelled by its description or path', () => {
    const definition = defineDataset(dataset(), {
      id: 'analytics.order_wide',
      source: 'analytics.order_wide',
      title: 'Order wide table',
      recordNoun: 'order',
    });
    expect(definition.kind).toBe('data');
    expect(definition.id).toBe('analytics.order_wide');
    expect(definition.source).toBe('analytics.order_wide');
    expect(definition.title).toBe('Order wide table');
    expect(definition.recordNoun).toBe('order');
    expect(definition.fields.map(field => field.name)).toEqual([
      'order_id',
      'tenant_id',
      'city',
      'amount',
      'updated_at',
      'depts',
      'phone',
    ]);
    expect(
      Object.fromEntries(definition.fields.map(f => [f.name, f.label])),
    ).toMatchObject({ city: 'City', amount: 'GMV', order_id: 'order_id' });
    expect(definition.record?.rowKey).toBe('order_id');
    expect(
      definition.described?.findings.filter(f => f.severity === 'error'),
    ).toEqual([]);
    // A sensitive column stays out of analyses: nothing groups or measures it.
    expect(JSON.stringify(definition.analysis)).toContain('"city"');
    expect(JSON.stringify(definition.analysis)).not.toContain('"phone"');
  });

  // A dataset's columns are scalars or arrays of strings; an array of objects
  // would need its entries written (`elements`), which nothing here writes.
  it('leaves out the fields of an element, and pages by cursor where the model only does', () => {
    const definition = defineDataset(
      dataset({
        record: {
          identity: 'order_id',
          paging: [PagingMode.LIST, PagingMode.CURSOR],
          rootOperators: [],
        },
        fields: [
          describedField('order_id'),
          describedField('lines', {
            kind: QueryValueKind.ARRAY,
            types: ['OBJECT'],
          }),
          describedField('lines.sku', { scope: 'lines' }),
        ],
      }),
      { id: 'a.b', source: 'a.b', title: 'B' },
    );
    expect(definition.fields.map(field => field.name)).toEqual(['order_id']);
    expect(definition.record?.paging).toBe('cursor');
    expect(definition.recordNoun).toBeUndefined();
  });
});
