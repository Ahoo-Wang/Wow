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

import { readFileSync } from 'node:fs';
import { describe, expect, expectTypeOf, it } from 'vitest';
import { QueryConstraintTypes, type QuerySemanticType } from '../../src';

/** The constraint types the Kotlin ConstraintDescriptor names, in source order. */
function kotlinConstraintTypes(): Record<string, string> {
  const source = readFileSync(
    new URL(
      '../../../../wow-api/src/main/kotlin/me/ahoo/wow/api/query/descriptor/QueryModelDescriptor.kt',
      import.meta.url,
    ),
    'utf8',
  );
  const companion = source.slice(
    source.indexOf('data class ConstraintDescriptor'),
  );
  return Object.fromEntries(
    [...companion.matchAll(/const val (\w+) = "([^"]*)"/g)].map(
      ([, name, value]) => [name, value],
    ),
  );
}

describe('QueryConstraintTypes', () => {
  it('knows every constraint type the Kotlin ConstraintDescriptor names', () => {
    expect(Object.entries(QueryConstraintTypes)).toEqual(
      Object.entries(kotlinConstraintTypes()),
    );
  });
});

describe('QuerySemanticType', () => {
  it('holds a money field to exactly one of currency and currencyField', () => {
    expectTypeOf({
      type: 'MONEY',
      currency: 'CNY',
      scale: 2,
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'MONEY',
      currencyField: 'currency',
      scale: 2,
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'DECIMAL',
      scale: 4,
    } as const).toMatchTypeOf<QuerySemanticType>();
    const invalid = () => {
      const both: QuerySemanticType = {
        type: 'MONEY',
        currency: 'CNY',
        // @ts-expect-error A money field names one currency source, not both.
        currencyField: 'currency',
        scale: 2,
      };
      // @ts-expect-error A money field names a currency source.
      const neither: QuerySemanticType = { type: 'MONEY', scale: 2 };
      // @ts-expect-error scale is always sent.
      const unscaled: QuerySemanticType = { type: 'DECIMAL' };
      return [both, neither, unscaled];
    };
    expect(typeof invalid).toBe('function');
  });
});
