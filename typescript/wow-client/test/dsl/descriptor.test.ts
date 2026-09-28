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
import {
  QueryConstraintTypes,
  QueryFieldRoles,
  TimeUnit,
  type QuerySemanticType,
} from '../../src';

/** A source file of the Kotlin wow-api, by its path under `me/ahoo/wow/api/`. */
function kotlinSource(path: string): string {
  return readFileSync(
    new URL(
      `../../../../wow-api/src/main/kotlin/me/ahoo/wow/api/${path}`,
      import.meta.url,
    ),
    'utf8',
  );
}

/** The constraint types the Kotlin ConstraintDescriptor names, in source order. */
function kotlinConstraintTypes(): Record<string, string> {
  const source = kotlinSource('query/descriptor/QueryModelDescriptor.kt');
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

/**
 * The roles Kotlin names in a field's `role`: every `SystemField`, the
 * system fields a root operator targets, then the model's times the
 * FieldDescriptor companion names, in source order.
 */
function kotlinFieldRoles(): Record<string, string> {
  const spec = kotlinSource('query/spec/FilterOperatorSpec.kt');
  const start = spec.indexOf('{', spec.indexOf('enum class SystemField'));
  const systemFields = spec
    .slice(start + 1, spec.indexOf('}', start))
    .match(/[A-Z][A-Z_]*/g)!;
  const descriptor = kotlinSource('query/descriptor/QueryModelDescriptor.kt');
  const field = descriptor.slice(
    descriptor.indexOf('data class FieldDescriptor'),
    descriptor.indexOf('data class EnumValueDescriptor'),
  );
  return Object.fromEntries([
    ...systemFields.map(name => [name, name]),
    ...[...field.matchAll(/const val (\w+) = "([^"]*)"/g)].map(
      ([, name, value]) => [name, value],
    ),
  ]);
}

describe('QueryFieldRoles', () => {
  it('knows every role the Kotlin model names', () => {
    expect(Object.entries(QueryFieldRoles)).toEqual(
      Object.entries(kotlinFieldRoles()),
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

  it('holds a duration to its unit and a reference to one way of naming its aggregate', () => {
    expectTypeOf({
      type: 'DURATION',
      timeUnit: TimeUnit.SECONDS,
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'REFERENCE',
      contextName: 'example-service',
      aggregateName: 'product',
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'REFERENCE',
      contextNameField: 'contextName',
      aggregateNameField: 'aggregateName',
    } as const).toMatchTypeOf<QuerySemanticType>();
    const invalid = () => {
      // @ts-expect-error a duration always names its unit.
      const unitless: QuerySemanticType = { type: 'DURATION' };
      const both: QuerySemanticType = {
        type: 'REFERENCE',
        contextName: 'example-service',
        aggregateName: 'product',
        // @ts-expect-error a reference names its aggregate one way, not both.
        contextNameField: 'contextName',
      };
      // @ts-expect-error a fixed reference names its context too.
      const contextless: QuerySemanticType = {
        type: 'REFERENCE',
        aggregateName: 'product',
      };
      return [unitless, both, contextless];
    };
    expect(typeof invalid).toBe('function');
  });
});
