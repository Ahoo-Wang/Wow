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

import { describe, expect, it } from 'vitest';
import { Schema } from '@ahoo-wang/fetcher-openapi';
import {
  isPrimitive,
  isArray,
  isEnum,
  isAnyOf,
  isOneOf,
  isUnion,
  isAllOf,
  isComposition,
  toArrayType,
  isEmptyObject,
  isNullableSchema,
  isWriteOnly,
  resolvePrimitiveType,
} from '../../src/utils';

describe('schemas', () => {
  describe('isPrimitive', () => {
    it('should return true for primitive types', () => {
      expect(isPrimitive('string')).toBe(true);
      expect(isPrimitive('number')).toBe(true);
      expect(isPrimitive('integer')).toBe(true);
      expect(isPrimitive('boolean')).toBe(true);
      expect(isPrimitive('null')).toBe(true);
    });

    it('should return true for array of types', () => {
      expect(isPrimitive(['string', 'number'])).toBe(true);
      expect(isPrimitive(['boolean'])).toBe(true);
    });

    it('should return false for non-primitive types', () => {
      expect(isPrimitive('object')).toBe(false);
      expect(isPrimitive('array')).toBe(false);
    });
  });

  describe('isArray', () => {
    it('should return true for array schemas', () => {
      const schema: Schema = { type: 'array', items: { type: 'string' } };
      expect(isArray(schema)).toBe(true);
    });

    it('should return false for non-array schemas', () => {
      const schema: Schema = { type: 'object' };
      expect(isArray(schema)).toBe(false);
    });

    it('should return false for schemas without type', () => {
      const schema: Schema = {};
      expect(isArray(schema)).toBe(false);
    });
  });

  describe('isEnum', () => {
    it('should return true for schemas with non-empty enum array', () => {
      const schema: Schema = { enum: ['a', 'b', 'c'] };
      expect(isEnum(schema)).toBe(true);
    });

    it('should return false for schemas with empty enum array', () => {
      const schema: Schema = { enum: [] };
      expect(isEnum(schema)).toBe(false);
    });

    it('should return false for schemas without enum property', () => {
      const schema: Schema = { type: 'string' };
      expect(isEnum(schema)).toBe(false);
    });
  });

  describe('isAnyOf', () => {
    it('should return true for schemas with non-empty anyOf array', () => {
      const schema: Schema = {
        anyOf: [{ type: 'string' }, { type: 'number' }],
      };
      expect(isAnyOf(schema)).toBe(true);
    });

    it('should return false for schemas with empty anyOf array', () => {
      const schema: Schema = { anyOf: [] };
      expect(isAnyOf(schema)).toBe(false);
    });

    it('should return false for schemas without anyOf property', () => {
      const schema: Schema = { type: 'string' };
      expect(isAnyOf(schema)).toBe(false);
    });
  });

  describe('isOneOf', () => {
    it('should return true for schemas with non-empty oneOf array', () => {
      const schema: Schema = {
        oneOf: [{ type: 'string' }, { type: 'number' }],
      };
      expect(isOneOf(schema)).toBe(true);
    });

    it('should return false for schemas with empty oneOf array', () => {
      const schema: Schema = { oneOf: [] };
      expect(isOneOf(schema)).toBe(false);
    });

    it('should return false for schemas without oneOf property', () => {
      const schema: Schema = { type: 'string' };
      expect(isOneOf(schema)).toBe(false);
    });
  });

  describe('isUnion', () => {
    it('should return true for anyOf schemas', () => {
      const schema: Schema = {
        anyOf: [{ type: 'string' }, { type: 'number' }],
      };
      expect(isUnion(schema)).toBe(true);
    });

    it('should return true for oneOf schemas', () => {
      const schema: Schema = {
        oneOf: [{ type: 'string' }, { type: 'number' }],
      };
      expect(isUnion(schema)).toBe(true);
    });

    it('should return false for non-union schemas', () => {
      const schema: Schema = { type: 'string' };
      expect(isUnion(schema)).toBe(false);
    });
  });

  describe('isAllOf', () => {
    it('should return true for schemas with non-empty allOf array', () => {
      const schema: Schema = {
        allOf: [{ type: 'object' }, { type: 'object' }],
      };
      expect(isAllOf(schema)).toBe(true);
    });

    it('should return false for schemas with empty allOf array', () => {
      const schema: Schema = { allOf: [] };
      expect(isAllOf(schema)).toBe(false);
    });

    it('should return false for schemas without allOf property', () => {
      const schema: Schema = { type: 'string' };
      expect(isAllOf(schema)).toBe(false);
    });
  });

  describe('isComposition', () => {
    it('should return true for anyOf schemas', () => {
      const schema: Schema = {
        anyOf: [{ type: 'string' }, { type: 'number' }],
      };
      expect(isComposition(schema)).toBe(true);
    });

    it('should return true for oneOf schemas', () => {
      const schema: Schema = {
        oneOf: [{ type: 'string' }, { type: 'number' }],
      };
      expect(isComposition(schema)).toBe(true);
    });

    it('should return true for allOf schemas', () => {
      const schema: Schema = {
        allOf: [{ type: 'object' }, { type: 'object' }],
      };
      expect(isComposition(schema)).toBe(true);
    });

    it('should return false for non-composition schemas', () => {
      const schema: Schema = { type: 'string' };
      expect(isComposition(schema)).toBe(false);
    });
  });

  describe('toArrayType', () => {
    it('should wrap complex types in parentheses', () => {
      expect(toArrayType('string | number')).toBe('(string | number)[]');
      expect(toArrayType('TypeA & TypeB')).toBe('(TypeA & TypeB)[]');
    });

    it('should not wrap simple types in parentheses', () => {
      expect(toArrayType('string')).toBe('string[]');
      expect(toArrayType('number')).toBe('number[]');
      expect(toArrayType('MyType')).toBe('MyType[]');
    });
  });

  describe('isEmptyObject', () => {
    it('should return true for object schemas without properties', () => {
      const schema: Schema = { type: 'object' };
      expect(isEmptyObject(schema)).toBe(true);
    });

    it('should return true for object schemas with empty properties', () => {
      const schema: Schema = { type: 'object', properties: {} };
      expect(isEmptyObject(schema)).toBe(true);
    });

    it('should return false for object schemas with properties', () => {
      const schema: Schema = {
        type: 'object',
        properties: { name: { type: 'string' } },
      };
      expect(isEmptyObject(schema)).toBe(false);
    });

    it('should return false for non-object schemas', () => {
      const schema: Schema = { type: 'string' };
      expect(isEmptyObject(schema)).toBe(false);
    });

    it('should return false for schemas without type', () => {
      const schema: Schema = {};
      expect(isEmptyObject(schema)).toBe(false);
    });
  });

  describe('resolvePrimitiveType', () => {
    it('should resolve single primitive types', () => {
      expect(resolvePrimitiveType('string')).toBe('string');
      expect(resolvePrimitiveType('number')).toBe('number');
      expect(resolvePrimitiveType('integer')).toBe('number');
      expect(resolvePrimitiveType('boolean')).toBe('boolean');
      expect(resolvePrimitiveType('null')).toBe('null');
    });

    it('should resolve array of types to union', () => {
      expect(resolvePrimitiveType(['string', 'number'])).toBe(
        'string | number',
      );
      expect(resolvePrimitiveType(['boolean', 'null'])).toBe('boolean | null');
    });

    it('should return any for unknown types', () => {
      expect(resolvePrimitiveType('object')).toBe('any');
      expect(resolvePrimitiveType('array')).toBe('any');
    });
  });
});

describe('isNullableSchema', () => {
  it.each([
    ['an untyped schema constrains nothing', {}, true],
    ['the 3.0 nullable flag', { type: 'string', nullable: true }, true],
    ['a null entry in a type array', { type: ['string', 'null'] }, true],
    ['a bare null type', { type: 'null' }, true],
    ['an untyped null enum member', { enum: ['a', null] }, true],
    ['an untyped null const', { const: null }, true],
    ['a plain typed schema', { type: 'string' }, false],
    ['an untyped enum without null', { enum: ['a', 'b'] }, false],
    ['an untyped const that is not null', { const: 'a' }, false],
    [
      'a type that rejects the null enum member',
      { type: 'string', enum: ['a', null] },
      false,
    ],
    [
      'a type that rejects the null const',
      { type: 'string', const: null },
      false,
    ],
  ] satisfies [string, Schema, boolean][])(
    'reports %s as %s',
    (_, schema, expected) => {
      expect(isNullableSchema(schema)).toBe(expected);
    },
  );

  it.each([
    ['a not that accepts null', { not: { type: 'null' } }, false],
    ['a not that rejects null', { not: { type: 'string' } }, true],
    [
      'anyOf and oneOf that each admit null',
      { anyOf: [{ type: 'null' }], oneOf: [{ type: ['string', 'null'] }] },
      true,
    ],
    [
      'an anyOf admitting null beside a oneOf that does not',
      { anyOf: [{ type: 'null' }], oneOf: [{ type: 'string' }] },
      false,
    ],
    [
      'two oneOf branches admitting null',
      { oneOf: [{ type: 'null' }, { enum: [null] }] },
      false,
    ],
    [
      'one anyOf branch admits null',
      { anyOf: [{ type: 'null' }, { type: 'string' }] },
      true,
    ],
    [
      'no anyOf branch admits null',
      { anyOf: [{ type: 'string' }, { type: 'number' }] },
      false,
    ],
    [
      'every allOf branch admits null',
      { allOf: [{ type: ['string', 'null'] }, { enum: ['x', null] }] },
      true,
    ],
    [
      'one allOf branch rejects null',
      { allOf: [{ type: ['string', 'null'] }, { type: 'string' }] },
      false,
    ],
  ] satisfies [string, Schema, boolean][])(
    'reports that %s as %s',
    (_, schema, expected) => {
      expect(isNullableSchema(schema)).toBe(expected);
    },
  );

  it('judges allOf branches independently of one another', () => {
    const nullable: Schema = { type: ['string', 'null'] };
    // The same object appearing twice must not be dismissed as a cycle.
    expect(isNullableSchema({ allOf: [nullable, nullable] })).toBe(true);
  });

  it('follows a reference when components are supplied', () => {
    const components = {
      schemas: {
        Nullish: { type: 'string', nullable: true },
        Present: { type: 'string' },
      },
    };
    const reference = { $ref: '#/components/schemas/Nullish' };
    expect(isNullableSchema(reference, components)).toBe(true);
    expect(
      isNullableSchema({ $ref: '#/components/schemas/Present' }, components),
    ).toBe(false);
    // Without components a reference cannot be judged, so it is not nullable.
    expect(isNullableSchema(reference)).toBe(false);
    // A dangling reference is treated the same way.
    expect(
      isNullableSchema({ $ref: '#/components/schemas/Missing' }, components),
    ).toBe(false);
  });

  it('stops at a schema it has already visited', () => {
    const schema: Schema = { type: ['string', 'null'] };
    expect(isNullableSchema(schema, undefined, new Set([schema]))).toBe(false);
  });
});

describe('isWriteOnly', () => {
  it.each([
    [{ type: 'string', writeOnly: true }, true],
    [{ type: 'string', writeOnly: false }, false],
    [{ type: 'string' }, false],
  ] satisfies [Schema, boolean][])('reports %o as %s', (schema, expected) => {
    expect(isWriteOnly(schema)).toBe(expected);
  });

  it('follows a reference to the component carrying the flag', () => {
    const components = {
      schemas: {
        Secret: { type: 'string', writeOnly: true },
        Plain: { type: 'string' },
      },
    };
    expect(
      isWriteOnly({ $ref: '#/components/schemas/Secret' }, components),
    ).toBe(true);
    expect(
      isWriteOnly({ $ref: '#/components/schemas/Plain' }, components),
    ).toBe(false);
    // Without components, and for a dangling reference, nothing can be judged.
    expect(isWriteOnly({ $ref: '#/components/schemas/Secret' })).toBe(false);
    expect(
      isWriteOnly({ $ref: '#/components/schemas/Missing' }, components),
    ).toBe(false);
  });

  it('stops at a schema it has already visited', () => {
    const schema: Schema = { type: 'string', writeOnly: true };
    expect(isWriteOnly(schema, undefined, new Set([schema]))).toBe(false);
  });
});
