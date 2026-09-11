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
  AggregationGroupType as Group,
  AggregationFunction as Fn,
  FilterOperator as Op,
} from '@ahoo-wang/fetcher-wow';
import { adaptWowAnalysisSchema } from '../src/analysis/wowAnalysis.js';
const scalar = (valueTypes: string[], capabilities: string[], extra = {}) => ({
  kind: 'SCALAR',
  masked: false,
  valueTypes,
  capabilities,
  ...extra,
});
const object = (properties: Record<string, unknown>) => ({
  kind: 'OBJECT',
  masked: false,
  properties,
});
it('maps explicit backend capabilities and host labels/units, excluding masked, ambiguous and unsupported time values', () => {
  const mapped = adaptWowAnalysisSchema(
    {
      model: 'SNAPSHOT',
      root: object({
        amount: scalar(
          ['DECIMAL'],
          ['AGGREGATE_NUMERIC', 'EXACT_MATCH', 'RANGE'],
        ),
        state: scalar(['STRING'], ['AGGREGATE_TERMS']),
        secret: scalar(['STRING'], ['AGGREGATE_TERMS'], { masked: true }),
        unknownMask: {
          kind: 'SCALAR',
          valueTypes: ['STRING'],
          capabilities: ['AGGREGATE_TERMS'],
        },
        timestamp: scalar(['INTEGER'], ['AGGREGATE_TEMPORAL'], {
          semanticType: { type: 'TEMPORAL_EPOCH', timeUnit: 'MILLISECONDS' },
        }),
        seconds: scalar(['INTEGER'], ['AGGREGATE_TEMPORAL'], {
          semanticType: { type: 'TEMPORAL_EPOCH', timeUnit: 'SECONDS' },
        }),
        mixed: scalar(['STRING', 'INTEGER'], ['AGGREGATE_TERMS']),
      }),
    },
    { labels: { amount: '金额' }, units: { amount: 'CNY' } },
  );
  expect(mapped.model).toBe('SNAPSHOT');
  expect(mapped.fields.map(f => f.field)).toEqual([
    'amount',
    'state',
    'timestamp',
  ]);
  expect(mapped.fields[0]).toMatchObject({
    label: '金额',
    type: 'number',
    operators: [
      Op.EQ,
      Op.NE,
      Op.IN,
      Op.NOT_IN,
      Op.GT,
      Op.GTE,
      Op.LT,
      Op.LTE,
      Op.BETWEEN,
    ],
  });
  expect(mapped.capability.fields[0]).toMatchObject({
    groups: [Group.HISTOGRAM],
    functions: Object.values(Fn),
    unit: 'CNY',
    any: false,
  });
  expect(mapped.capability.fields[1].any).toBe(true);
  expect(mapped.fields[2].type).toBe('datetime');
});
it('creates predefined object element chains without treating arrays as scalar root fields', () => {
  const price = scalar(['DECIMAL'], ['AGGREGATE_NUMERIC']);
  const array = (items: unknown) => ({
    kind: 'ARRAY',
    masked: false,
    capabilities: ['ELEMENT_SCOPE'],
    items,
  });
  const result = adaptWowAnalysisSchema({
    model: 'EVENT_STREAM',
    root: object({
      rows: array(object({ price, details: array(object({ price })) })),
      hidden: { ...array(object({ price })), masked: true },
    }),
  });
  expect(result.fields).toEqual([]);
  expect(result.capability.scopes?.map(s => s.id)).toEqual([
    'rows',
    'rows.details',
  ]);
  expect(result.capability.scopes?.[1].elements.map(e => e.path)).toEqual([
    'rows',
    'details',
  ]);
  expect(result.capability.scopes?.[1].fields[0].field).toBe('price');
});
it('rejects malformed envelopes instead of publishing invented capabilities', () => {
  for (const input of [null, {}, { model: 'SNAPSHOT', root: [] }])
    expect(() => adaptWowAnalysisSchema(input)).toThrow();
});
it('does not inherit host overrides for legal prototype-like field names', () => {
  const result = adaptWowAnalysisSchema(
    {
      model: 'SNAPSHOT',
      root: object({ constructor: scalar(['STRING'], ['AGGREGATE_TERMS']) }),
    },
    { labels: {}, units: {} },
  );
  expect(result.fields[0].label).toBe('constructor');
  expect(result.capability.fields[0].unit).toBeUndefined();
});
it('maps the real scalar enumValues shape with typed values and leaves invalid enum lists unpublished', () => {
  const result = adaptWowAnalysisSchema({
    model: 'SNAPSHOT',
    root: object({
      state: object({
        status: scalar(['STRING'], ['EXACT_MATCH', 'AGGREGATE_TERMS'], {
          enumValues: ['FAILED', 'PREPARED', 'SUCCEEDED'],
        }),
        attempt: scalar(['INTEGER'], ['EXACT_MATCH', 'AGGREGATE_NUMERIC'], {
          enumValues: [0, 1, 2],
        }),
        recoverable: scalar(['BOOLEAN'], ['EXACT_MATCH', 'AGGREGATE_TERMS'], {
          enumValues: [false, true],
        }),
        nullable: scalar(['STRING'], ['EXACT_MATCH'], {
          enumValues: ['FAILED', null],
        }),
        mixed: scalar(['INTEGER'], ['EXACT_MATCH'], { enumValues: [1, '2'] }),
      }),
    }),
  });
  expect(result.fields[0].options).toEqual(
    ['FAILED', 'PREPARED', 'SUCCEEDED'].map(value => ({ value, label: value })),
  );
  expect(result.fields[1].options).toEqual(
    [0, 1, 2].map(value => ({ value, label: String(value) })),
  );
  expect(result.fields[2].options).toEqual(
    [false, true].map(value => ({ value, label: String(value) })),
  );
  expect(result.fields[3].options).toBeUndefined();
  expect(result.fields[4].options).toBeUndefined();
});

it('limits nested element scopes to the backend five-element chain', () => {
  let nested: Record<string, unknown> = object({
    state: scalar(['STRING'], ['AGGREGATE_TERMS']),
  });
  for (let i = 0; i < 7; i++)
    nested = object({
      lines: {
        kind: 'ARRAY',
        masked: false,
        capabilities: ['ELEMENT_SCOPE'],
        items: nested,
      },
    });
  const result = adaptWowAnalysisSchema({ model: 'SNAPSHOT', root: nested });
  expect(result.capability.scopes?.map(scope => scope.elements.length)).toEqual(
    [1, 2, 3, 4, 5],
  );
});
