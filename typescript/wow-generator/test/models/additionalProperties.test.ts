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
 * Required properties beside additional properties: which ones stay in an
 * interface, which ones force the intersection form, and what a value may
 * hold either way.
 */

import { describe, expect, it } from 'vitest';
import type { Schema } from '@ahoo-wang/fetcher-openapi';
import { generateModel } from '../support/models';

describe('required additional property constraints', () => {
  it.each([false, true])(
    'keeps required-only objects open by default (nested: %s)',
    nested => {
      const schema: Schema = {
        type: 'object',
        properties: {},
        required: ['id'],
      };
      expect(
        generateModel(
          nested
            ? {
                type: 'object',
                required: ['value'],
                properties: { value: schema },
              }
            : schema,
          nested
            ? `
      const valid: Model = { value: { id: 1, extra: 2 } };
      // @ts-expect-error allowing extras does not make id optional
      const missing: Model = { value: { extra: 2 } };
    `
            : `
      const valid: Model = { id: 1, extra: 2 };
      // @ts-expect-error allowing extras does not make id optional
      const missing: Model = { extra: 2 };
    `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each([false, true])(
    'escapes required-only property names (nested: %s)',
    nested => {
      const schema: Schema = {
        type: 'object',
        properties: {},
        required: ["owner's", 'path\\name', 'line\nbreak'],
      };
      expect(
        generateModel(
          nested
            ? {
                type: 'object',
                required: ['value'],
                properties: { value: schema },
              }
            : schema,
          nested
            ? `
      const valid: Model = { value: { "owner's": 1, 'path\\\\name': 2, 'line\\nbreak': 3 } };
      // @ts-expect-error the escaped keys remain required
      const missing: Model = { value: {} };
    `
            : `
      const valid: Model = { "owner's": 1, 'path\\\\name': 2, 'line\\nbreak': 3 };
      // @ts-expect-error the escaped keys remain required
      const missing: Model = {};
    `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each([false, true])(
    'emits own required declarations for prototype names (nested: %s)',
    nested => {
      const schema: Schema = {
        type: 'object',
        properties: {},
        required: ['constructor', 'toString'],
        additionalProperties: { type: 'string' },
      };
      expect(
        generateModel(
          nested
            ? {
                type: 'object',
                required: ['value'],
                properties: { value: schema },
              }
            : schema,
          nested
            ? `
      const valid: Model = { value: { constructor: '1', toString: '2' } };
      // @ts-expect-error inherited functions cannot satisfy required string values
      const missing: Model = { value: {} };
    `
            : `
      const valid: Model = { constructor: '1', toString: '2' };
      // @ts-expect-error inherited functions cannot satisfy required string values
      const missing: Model = {};
    `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each([false, true])(
    'preserves custom map keys when required keys exist (nested: %s)',
    nested => {
      const map: Schema = {
        type: 'object',
        required: ['id'],
        additionalProperties: { type: 'string' },
        'x-map-key-schema': { type: 'string', enum: ['id', 'name'] },
      };
      const { diagnostics } = generateModel(
        nested
          ? {
              type: 'object',
              required: ['map'],
              properties: { map },
            }
          : map,
        nested
          ? `
      const valid: Model = { map: { id: '1', name: 'one' } };
      // @ts-expect-error unknown map keys remain invalid
      const extra: Model = { map: { id: '1', name: 'one', other: 'two' } };
      // @ts-expect-error the map still requires id
      const missing: Model = { map: { name: 'one' } };
    `
          : `
      const valid: Model = { id: '1', name: 'one' };
      // @ts-expect-error unknown map keys remain invalid
      const extra: Model = { id: '1', name: 'one', other: 'two' };
      // @ts-expect-error the map still requires id
      const missing: Model = { name: 'one' };
      // @ts-expect-error values still follow additionalProperties
      const wrong: Model = { id: 1, name: 'one' };
    `,
      );
      expect(diagnostics).toEqual([]);
    },
  );

  it.each([true, false])(
    'uses the additional-property schema for required keys (properties present: %s)',
    withProperties => {
      const schema: Schema = {
        type: 'object',
        ...(withProperties
          ? { properties: { name: { type: 'string' as const } } }
          : {}),
        required: ['id'],
        additionalProperties: { type: 'string' },
      };
      const name = withProperties ? "name: 'name', " : '';
      const assignments = `const valid: Model = { ${name}id: '1' };\n// @ts-expect-error id is required\nconst missing: Model = {};\n// @ts-expect-error undeclared required id obeys additionalProperties\nconst wrong: Model = { ${name}id: 1 };`;
      expect(generateModel(schema, assignments).diagnostics).toEqual([]);
      expect(
        generateModel(
          {
            type: 'object',
            required: ['nested'],
            properties: { nested: schema },
          },
          `const valid: Model = { nested: { ${name}id: '1' } };\n// @ts-expect-error nested id follows additionalProperties too\nconst wrong: Model = { nested: { ${name}id: 1 } };`,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each([false, true])(
    'keeps required properties out of a conflicting index signature (nested: %s)',
    nested => {
      // An interface may not carry a `string` property beside a `number` index
      // signature (TS2411), and two plain primitives of different types are
      // the one clash provable without a type checker - so this schema takes
      // the intersection however the document declares the property.
      const schema: Schema = {
        type: 'object',
        required: ['name'],
        properties: { name: { type: 'string' } },
        additionalProperties: { type: 'number' },
      };
      const value = nested ? 'model.value' : 'model';
      const { file, diagnostics } = generateModel(
        nested
          ? {
              type: 'object',
              required: ['value'],
              properties: { value: schema },
            }
          : schema,
        `
          declare const model: Model;
          const name: string = ${value}.name;
          const extra: number = ${value}.other;
          // @ts-expect-error TypeScript cannot exempt a named property from the
          // index signature, so no literal satisfies both halves of a schema
          // whose additionalProperties contradict it
          const literal: Model = ${nested ? "{ value: { name: 'name' } }" : "{ name: 'name' }"};
        `,
      );
      expect(diagnostics).toEqual([]);
      expect(file.getFullText()).toContain('globalThis.Record<string, number>');
    },
  );

  it.each([false, true])(
    'admits values when a required property agrees with the index signature (nested: %s)',
    nested => {
      const schema: Schema = {
        type: 'object',
        required: ['name'],
        properties: { name: { type: 'string' } },
        additionalProperties: { type: 'string' },
      };
      const wrap = (object: string) =>
        nested ? `{ value: ${object} }` : object;
      expect(
        generateModel(
          nested
            ? {
                type: 'object',
                required: ['value'],
                properties: { value: schema },
              }
            : schema,
          `
            const valid: Model = ${wrap("{ name: 'name', extra: 'value' }")};
            // @ts-expect-error name stays required
            const missing: Model = ${wrap("{ extra: 'value' }")};
            // @ts-expect-error additional properties still reject numbers
            const wrong: Model = ${wrap("{ name: 'name', extra: 1 }")};
          `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each([
    ['an array', { type: 'array', items: { type: 'string' } } as Schema],
    [
      'an inline object',
      { type: 'object', properties: { id: { type: 'string' } } } as Schema,
    ],
  ])(
    'moves a required property out of a primitive index signature: %s',
    (_, property) => {
      // Against a primitive index an object and an array are as incompatible
      // as a different primitive is (TS2411), and the index type stays a
      // primitive, so the alias cannot reach itself through `Record`.
      const schema: Schema = {
        type: 'object',
        required: ['value'],
        properties: { value: property },
        additionalProperties: { type: 'string' },
      };
      const { file, diagnostics } = generateModel(
        schema,
        "const valid: Model = { value: undefined as any, extra: 'text' };",
        { schemas: { Model: schema } },
      );
      expect(diagnostics).toEqual([]);
      expect(file.getTypeAliasOrThrow('Model').getText()).toContain(
        'globalThis.Record<string, string>',
      );
    },
  );

  it('keeps a self-referential property beside a primitive index signature', () => {
    // The property may reference the model, an object member defers - it is
    // the index type that must not lead back to the alias.
    const schema: Schema = {
      type: 'object',
      required: ['child'],
      properties: { child: { $ref: '#/components/schemas/Model' } },
      additionalProperties: { type: 'string' },
    };
    const { file, diagnostics } = generateModel(schema, '', {
      schemas: { Model: schema },
    });
    expect(diagnostics).toEqual([]);
    expect(file.getTypeAliasOrThrow('Model').getText()).toContain(
      'globalThis.Record<string, string>',
    );
  });

  it.each([
    [
      'a null property against a nullable dictionary of itself',
      { type: 'null' } as Schema,
      {
        oneOf: [{ $ref: '#/components/schemas/Model' }, { type: 'null' }],
      } as Schema,
    ],
    [
      'an enum property against the primitive it narrows',
      { type: 'string', enum: ['a', 'b'] } as Schema,
      { type: 'string' } as Schema,
    ],
  ])(
    'leaves an assignable required property in the interface: %s',
    (_, property, additionalProperties) => {
      // Textual inequality is not non-assignability. Calling either of these a
      // clash would move a schema the interface expresses perfectly well to an
      // alias - and the first one would then reach itself through `Record`
      // (TS2456).
      const schema: Schema = {
        type: 'object',
        required: ['value'],
        properties: { value: property },
        additionalProperties,
      };
      const { file, diagnostics } = generateModel(schema, '', {
        schemas: { Model: schema },
      });
      expect(diagnostics).toEqual([]);
      expect(
        file.getInterfaceOrThrow('Model').getIndexSignatures(),
      ).toHaveLength(1);
    },
  );

  it('keeps a dictionary of its own type an interface', () => {
    // Only an interface may reference itself through an index signature: a type
    // alias that reaches itself through `Record` is circular (TS2456). A
    // reference is never a provable clash, so the dictionary keeps the
    // interface and stays expressible.
    const schema: Schema = {
      type: 'object',
      required: ['child'],
      properties: { child: { $ref: '#/components/schemas/Model' } },
      additionalProperties: { $ref: '#/components/schemas/Model' },
    };
    const { file, diagnostics } = generateModel(
      schema,
      `
        declare const model: Model;
        const child: Model = model.child;
        const other: Model = model.other;
      `,
      { schemas: { Model: schema } },
    );
    expect(diagnostics).toEqual([]);
    expect(file.getInterfaceOrThrow('Model').getIndexSignatures()).toHaveLength(
      1,
    );
  });

  it.each([false, true, undefined])(
    'respects boolean/default additionalProperties: %s',
    additionalProperties => {
      const schema: Schema = {
        type: 'object',
        properties: {},
        required: ['id'],
        additionalProperties,
      };
      const assignments =
        additionalProperties === false
          ? "// @ts-expect-error a forbidden required field makes the schema impossible\nconst invalid: Model = { id: '1' };"
          : "const valid: Model[] = [{ id: null }, { id: 1 }, { id: '1' }];\n// @ts-expect-error id cannot be missing\nconst missing: Model = {};";
      expect(generateModel(schema, assignments).diagnostics).toEqual([]);
    },
  );
});
