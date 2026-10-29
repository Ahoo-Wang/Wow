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
 * Elements and values a Manifest says may be null, as manifest-tools writes
 * a Kotlin `List<Long?>`, `Map<String, String?>` and
 * `List<Map<String, Reading?>>`: the generated model admits null there, and
 * only there.
 */

import { describe, expect, it } from 'vitest';
import type { Schema } from '@ahoo-wang/fetcher-openapi';
import { generateModels } from '../support/models';

const READING: Schema = {
  type: 'object',
  required: ['value'],
  properties: { value: { type: 'integer', format: 'int64' } },
};

const STATE = {
  type: 'object',
  required: ['nullableItems', 'nullableValues', 'values', 'readings'],
  properties: {
    nullableItems: {
      type: 'array',
      items: { type: ['integer', 'null'], format: 'int64' },
    },
    nullableValues: {
      type: 'object',
      additionalProperties: { type: ['string', 'null'] },
    },
    values: {
      type: 'object',
      additionalProperties: { type: 'integer', format: 'int64' },
    },
    readings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: {
          anyOf: [{ type: 'null' }, { $ref: '#/components/schemas/Reading' }],
        },
      },
    },
  },
} as unknown as Schema;

describe('nullable elements and values', () => {
  it('admits null in an element or a value whose schema accepts it', () => {
    expect(
      generateModels(
        { Reading: READING, Model: STATE },
        `
      const valid: Model = {
        nullableItems: [1, null],
        nullableValues: { a: null, b: 'b' },
        values: { a: 1 },
        readings: [{ a: null, b: { value: 1 } }],
      };
      const items: (number | null)[] = valid.nullableItems;
      const value: string | null | undefined = valid.nullableValues.a;
      // @ts-expect-error a value of a Map<String, Long> is never null
      const nullValue: Model = { ...valid, values: { a: null } };
    `,
        { schemas: { Reading: READING } },
      ).diagnostics,
    ).toEqual([]);
  });
});
