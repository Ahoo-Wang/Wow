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
 * Declared properties and enums of a generated model: what a value of the
 * model may hold, checked by type-checking assignments against it.
 */

import { describe, expect, it } from 'vitest';
import type { Schema } from '@ahoo-wang/fetcher-openapi';
import { generateModel, runModels } from '../support/models';

describe('schema constraints in generated models', () => {
  it.each([false, true])(
    'retains readonly properties in nullable object aliases (documented: %s)',
    documented => {
      expect(
        generateModel(
          {
            type: 'object',
            nullable: true,
            required: ['id'],
            properties: {
              id: {
                type: 'string',
                readOnly: true,
                ...(documented ? { description: 'Stable identifier' } : {}),
              },
              name: { type: 'string' },
            },
          },
          `
      let value: Model = { id: '1', name: 'name' };
      value.name = 'updated';
      // @ts-expect-error id is readonly even when the object can be null
      value.id = '2';
      value = null;
    `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each([
    [{ type: 'integer', enum: [0, 1] }, 'const value: Model = 0;'],
    [
      { enum: [0, 'one', false, null, ''] },
      "const values: Model[] = [0, 'one', false, null, ''];",
    ],
    [
      {
        type: 'object',
        required: ['status'],
        properties: { status: { type: 'integer', enum: [0, 1] } },
      },
      'const value: Model = { status: 0 };',
    ],
  ] satisfies [Schema, string][])(
    'preserves numeric and mixed enum values %#',
    (schema, assignment) => {
      const { diagnostics, file } = generateModel(schema, assignment);
      expect(file.getText()).not.toMatch(/enum Model\s*\{\s*\}/);
      expect(diagnostics).toEqual([]);
    },
  );

  it('keeps string enum members available when an empty value is present', () => {
    const { diagnostics, file } = generateModel(
      { type: 'string', enum: ['', 'ON'] },
      "const values: Model[] = [Model[''], Model.ON];",
    );
    expect(file.getEnum('Model')).toBeDefined();
    expect(diagnostics).toEqual([]);
  });

  it.each(['allOf', 'oneOf', 'anyOf'] as const)(
    'keeps string enum values and constraints with %s',
    composition => {
      const { diagnostics, file } = generateModel(
        {
          type: 'string',
          enum: ['', 'ON', 'OFF'],
          'x-enum-text': { ON: 'Enabled' },
          [composition]: [{ type: 'string', enum: ['', 'ON'] }],
        },
        `
          const valid: Model[] = [Model[''], Model.ON];
          // @ts-expect-error OFF is excluded by the composition
          const excluded: Model = Model.OFF;
          // @ts-expect-error values outside the enum stay invalid
          const unknown: Model = 'UNKNOWN';
        `,
      );
      expect(diagnostics).toEqual([]);
      expect(runModels(file)).toMatchObject({
        Model: { '': '', ON: 'ON', OFF: 'OFF' },
        ModelEnumText: { ON: 'Enabled' },
      });
    },
  );

  it('keeps Model.ON available for a string enum with a string allOf member', () => {
    const { diagnostics, file } = generateModel(
      { type: 'string', enum: ['ON'], allOf: [{ type: 'string' }] },
      'export const value: Model = Model.ON;',
    );
    expect(diagnostics).toEqual([]);
    expect(runModels(file)).toMatchObject({ Model: { ON: 'ON' }, value: 'ON' });
  });

  it('generates every declared property as required, at every depth', () => {
    const { diagnostics, file } = generateModel(
      {
        type: 'object',
        required: ['id'],
        properties: {
          id: { type: 'string' },
          declared: { type: 'string' },
          nested: {
            type: 'object',
            properties: { declared: { type: 'string' } },
          },
        },
      },
      "const value: Model = { id: '1', declared: 'x', nested: { declared: 'y' } };\n// @ts-expect-error a declared property is never optional\nconst invalid: Model = { id: '1', nested: { declared: 'y' } };",
    );
    expect(diagnostics).toEqual([]);
    expect(
      file
        .getInterfaceOrThrow('Model')
        .getPropertyOrThrow('declared')
        .hasQuestionToken(),
    ).toBe(false);
  });

  // Every property is required now, so the optional-property escape that used
  // to send these schemas to the intersection form is gone. A property whose
  // kind cannot be read off the schema has to take it instead, or the named
  // property sits beside an index signature it does not satisfy (TS2411).
  it.each([
    ['the 3.0 nullable flag', { type: 'string', nullable: true }],
    ['a null entry in a type array', { type: ['string', 'null'] }],
    ['an enum of another primitive', { type: 'string', enum: ['a'] }],
  ] as [string, Schema][])(
    'keeps a property beside a primitive index legal: %s',
    (_, property) => {
      const { diagnostics, file } = generateModel(
        {
          type: 'object',
          properties: { name: property },
          additionalProperties: { type: 'number' },
        },
        '',
      );
      expect(diagnostics).toEqual([]);
      // The intersection carries it; an interface could not.
      expect(file.getInterface('Model')).toBeUndefined();
      expect(
        file.getTypeAliasOrThrow('Model').getTypeNodeOrThrow().getText(),
      ).toContain('globalThis.Record<string, number>');
    },
  );

  it.each([
    ['an enum', { type: 'string', enum: ['a', 'b'] }],
    ['an allOf its branches agree on', { allOf: [{ type: 'string' }] }],
  ] as [string, Schema][])(
    'keeps a property the index accepts in the interface: %s',
    (_, property) => {
      const { diagnostics, file } = generateModel(
        {
          type: 'object',
          properties: { name: property },
          additionalProperties: { type: 'string' },
        },
        "const value: Model = { name: 'a', extra: 'x' };",
      );
      expect(diagnostics).toEqual([]);
      expect(file.getInterface('Model')).toBeDefined();
    },
  );

  it('keeps an enum beside the primitive it narrows in the interface', () => {
    const { diagnostics, file } = generateModel(
      {
        type: 'object',
        properties: { name: { type: 'string', enum: ['a', 'b'] } },
        additionalProperties: { type: 'string' },
      },
      "const value: Model = { name: 'a', extra: 'x' };",
    );
    expect(diagnostics).toEqual([]);
    expect(
      file.getInterfaceOrThrow('Model').getPropertyOrThrow('name').getText(),
    ).toContain("'a' | 'b'");
  });

  it('requires declared properties alongside additional properties', () => {
    const schema: Schema = {
      type: 'object',
      properties: { name: { type: 'string' } },
      additionalProperties: { type: 'string' },
    };
    expect(
      generateModel(schema, "const value: Model = { name: 'name' };")
        .diagnostics,
    ).toEqual([]);
    expect(
      generateModel(
        { type: 'object', properties: { nested: schema } },
        "const value: Model = { nested: { name: 'name' } };",
      ).diagnostics,
    ).toEqual([]);
  });

  it.each(['named', 'nested', 'allOf'] as const)(
    'rejects undefined additional properties beside declared properties (%s)',
    placement => {
      const objectSchema: Schema = {
        type: 'object',
        properties: { name: { type: 'string', readOnly: true } },
        additionalProperties: { type: 'string' },
      };
      const schema: Schema =
        placement === 'nested'
          ? {
              type: 'object',
              required: ['nested'],
              properties: { nested: objectSchema },
            }
          : placement === 'allOf'
            ? { allOf: [objectSchema] }
            : objectSchema;
      const value = (object: string) =>
        placement === 'nested' ? `{ nested: ${object} }` : object;
      expect(
        generateModel(
          schema,
          `
            const valid: Model = ${value("{ name: 'name', extra: 'value' }")};
            // @ts-expect-error additional properties only accept string values
            const undefinedExtra: Model = ${value("{ name: 'name', extra: undefined }")};
            // @ts-expect-error additional properties still reject numbers
            const numberExtra: Model = ${value("{ name: 'name', extra: 1 }")};
            // @ts-expect-error declared properties keep their readonly modifier
            valid${placement === 'nested' ? '.nested' : ''}.name = 'updated';
          `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it('preserves OpenAPI 3.0 nullable on properties and named schemas', () => {
    expect(
      generateModel(
        { type: 'string', nullable: true },
        'const value: Model = null;',
      ).diagnostics,
    ).toEqual([]);
    expect(
      generateModel(
        {
          type: 'object',
          required: ['value'],
          properties: { value: { type: 'string', nullable: true } },
        },
        'const value: Model = { value: null };',
      ).diagnostics,
    ).toEqual([]);
  });
});
