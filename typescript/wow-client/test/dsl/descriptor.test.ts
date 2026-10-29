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
  type DynamicFieldDescriptor,
  type FieldDescriptor,
  type QuerySemanticType,
} from '../../src/dsl.js';

/**
 * A source file of the Kotlin query model, by its path under
 * `query/query/src/main/kotlin/me/ahoo/wow/query/`.
 */
function kotlinSource(path: string): string {
  return readFileSync(
    new URL(
      `../../../../query/query/src/main/kotlin/me/ahoo/wow/query/${path}`,
      import.meta.url,
    ),
    'utf8',
  );
}

/** The constraint types the Kotlin ConstraintDescriptor names, in source order. */
function kotlinConstraintTypes(): Record<string, string> {
  const source = kotlinSource('descriptor/QueryModelDescriptor.kt');
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
  const spec = kotlinSource('spec/FilterOperatorSpec.kt');
  const start = spec.indexOf('{', spec.indexOf('enum class SystemField'));
  const systemFields = spec
    .slice(start + 1, spec.indexOf('}', start))
    .match(/[A-Z][A-Z_]*/g)!;
  const descriptor = kotlinSource('descriptor/QueryModelDescriptor.kt');
  const field = descriptor.slice(
    descriptor.indexOf('data class FieldDescriptor'),
    descriptor.indexOf('data class EnumValueDescriptor'),
  );
  return Object.fromEntries([
    // A system field's role is its wire name: TENANT_ID is tenant-id.
    ...systemFields.map(name => [name, name.toLowerCase().replace(/_/g, '-')]),
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

/** The properties of a Kotlin descriptor data class, in source order. */
function kotlinProperties(className: string): string[] {
  const source = kotlinSource('descriptor/QueryModelDescriptor.kt');
  const start = source.indexOf(`data class ${className}(`);
  const body = source.slice(start, source.indexOf('\n)', start));
  return [...body.matchAll(/^ {4}val (\w+):/gm)].map(([, name]) => name);
}

const FIELD_MEMBERS = [
  'path',
  'role',
  'types',
  'kind',
  'nullable',
  'semantic',
  'enum',
  'description',
  'sensitivity',
  'project',
  'filter',
  'sort',
  'aggregate',
  'scope',
  'deprecated',
  'aliases',
] as const;

const DYNAMIC_FIELD_MEMBERS = [
  'pattern',
  'types',
  'kind',
  'nullable',
  'semantic',
  'filter',
  'sort',
  'aggregate',
  'excludedKeys',
] as const;

describe('FieldDescriptor and DynamicFieldDescriptor', () => {
  it('have the members of the Kotlin data classes', () => {
    expectTypeOf<(typeof FIELD_MEMBERS)[number]>().toEqualTypeOf<
      keyof FieldDescriptor
    >();
    expectTypeOf<(typeof DYNAMIC_FIELD_MEMBERS)[number]>().toEqualTypeOf<
      keyof DynamicFieldDescriptor
    >();
    expect([...FIELD_MEMBERS]).toEqual(kotlinProperties('FieldDescriptor'));
    expect([...DYNAMIC_FIELD_MEMBERS]).toEqual(
      kotlinProperties('DynamicFieldDescriptor'),
    );
  });

  it('say whether a value may be null and what it means alike', () => {
    expectTypeOf<DynamicFieldDescriptor['nullable']>().toEqualTypeOf<
      FieldDescriptor['nullable']
    >();
    expectTypeOf<DynamicFieldDescriptor['semantic']>().toEqualTypeOf<
      FieldDescriptor['semantic']
    >();
  });
});

describe('QuerySemanticType', () => {
  it('holds a money field to exactly one of currency and currencyField', () => {
    expectTypeOf({
      type: 'money',
      currency: 'CNY',
      scale: 2,
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'money',
      currencyField: 'currency',
      scale: 2,
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'decimal',
      scale: 4,
    } as const).toMatchTypeOf<QuerySemanticType>();
    const invalid = () => {
      const both: QuerySemanticType = {
        type: 'money',
        currency: 'CNY',
        // @ts-expect-error A money field names one currency source, not both.
        currencyField: 'currency',
        scale: 2,
      };
      // @ts-expect-error A money field names a currency source.
      const neither: QuerySemanticType = { type: 'money', scale: 2 };
      // @ts-expect-error scale is always sent.
      const unscaled: QuerySemanticType = { type: 'decimal' };
      return [both, neither, unscaled];
    };
    expect(typeof invalid).toBe('function');
  });

  it('holds a duration to its unit and a reference to one way of naming its aggregate', () => {
    expectTypeOf({
      type: 'duration',
      timeUnit: TimeUnit.SECONDS,
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'reference',
      contextName: 'example-service',
      aggregateName: 'product',
    } as const).toMatchTypeOf<QuerySemanticType>();
    expectTypeOf({
      type: 'reference',
      contextNameField: 'contextName',
      aggregateNameField: 'aggregateName',
    } as const).toMatchTypeOf<QuerySemanticType>();
    const invalid = () => {
      // @ts-expect-error a duration always names its unit.
      const unitless: QuerySemanticType = { type: 'duration' };
      const both: QuerySemanticType = {
        type: 'reference',
        contextName: 'example-service',
        aggregateName: 'product',
        // @ts-expect-error a reference names its aggregate one way, not both.
        contextNameField: 'contextName',
      };
      // @ts-expect-error a fixed reference names its context too.
      const contextless: QuerySemanticType = {
        type: 'reference',
        aggregateName: 'product',
      };
      return [unitless, both, contextless];
    };
    expect(typeof invalid).toBe('function');
  });
});
