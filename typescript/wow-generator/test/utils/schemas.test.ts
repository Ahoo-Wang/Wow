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
  quoteStringLiteral,
  resolveOptionalFields,
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

describe('resolveOptionalFields', () => {
  it('lists the declared properties no required list names', () => {
    expect(
      resolveOptionalFields({
        type: 'object',
        properties: { a: { type: 'string' }, b: { type: 'string' } },
        required: ['a'],
      }),
    ).toEqual(['b']);
  });

  it('reads properties inherited through allOf', () => {
    expect(
      resolveOptionalFields({
        allOf: [
          {
            type: 'object',
            properties: { a: { type: 'string' }, b: { type: 'string' } },
            required: ['a'],
          },
          { type: 'object', properties: { c: { type: 'string' } } },
        ],
      }),
    ).toEqual(['b', 'c']);
  });

  it('treats a property any branch requires as required', () => {
    expect(
      resolveOptionalFields({
        allOf: [
          { type: 'object', properties: { a: { type: 'string' } } },
          { required: ['a'] },
        ],
      }),
    ).toEqual([]);
  });

  it('follows references when components are supplied', () => {
    const components = {
      schemas: {
        Base: {
          type: 'object' as const,
          properties: {
            a: { type: 'string' as const },
            b: { type: 'string' as const },
          },
          required: ['a'],
        },
      },
    };
    expect(
      resolveOptionalFields(
        { allOf: [{ $ref: '#/components/schemas/Base' }] },
        components,
      ),
    ).toEqual(['b']);
    expect(
      resolveOptionalFields({ $ref: '#/components/schemas/Base' }, components),
    ).toEqual(['b']);
  });

  it('contributes nothing for a reference it cannot resolve', () => {
    expect(
      resolveOptionalFields({ $ref: '#/components/schemas/Missing' }),
    ).toEqual([]);
    expect(
      resolveOptionalFields(
        { $ref: '#/components/schemas/Missing' },
        { schemas: {} },
      ),
    ).toEqual([]);
  });

  it('stops at a schema it has already visited', () => {
    const cyclic: Record<string, unknown> = {
      type: 'object',
      properties: { a: { type: 'string' } },
    };
    cyclic.allOf = [cyclic];
    expect(resolveOptionalFields(cyclic as never)).toEqual(['a']);
  });

  // `keyof ({ … } | null)` is `never`, so naming a field of a nullable command
  // would make PartialBy violate `K extends keyof T` (TS2344).
  it.each([
    [
      'a 3.1 type array',
      { type: ['object', 'null'], properties: { note: { type: 'string' } } },
    ],
    [
      'the 3.0 nullable flag',
      {
        type: 'object',
        nullable: true,
        properties: { note: { type: 'string' } },
      },
    ],
    [
      'a nullable allOf branch',
      {
        allOf: [
          {
            type: 'object',
            nullable: true,
            properties: { note: { type: 'string' } },
          },
        ],
      },
    ],
  ] as [string, never][])(
    'names no field of a schema admitting null: %s',
    (_, schema) => {
      expect(resolveOptionalFields(schema)).toEqual([]);
    },
  );

  it('returns nothing for a schema that declares no properties', () => {
    expect(resolveOptionalFields({ type: 'string' })).toEqual([]);
  });
});

describe('quoteStringLiteral', () => {
  it('leaves an ordinary name as a plain literal', () => {
    expect(quoteStringLiteral('quantity')).toBe("'quantity'");
  });

  it('escapes a quote that would close the literal', () => {
    expect(quoteStringLiteral("owner'sName")).toBe("'owner\\'sName'");
  });

  it('escapes a backslash before it escapes anything else', () => {
    expect(quoteStringLiteral('a\\b')).toBe("'a\\\\b'");
  });
});
