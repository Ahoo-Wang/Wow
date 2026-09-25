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
 * allOf: every member's constraint - required, nullable, referenced or
 * inline - still holds in the generated model.
 */

import { describe, expect, it } from 'vitest';
import type { Schema } from '@ahoo-wang/fetcher-openapi';
import type { SourceFile } from 'ts-morph';
import {
  diagnosticsOf,
  generateModel,
  generateModels,
  writeModels,
} from '../support/models';

describe('allOf required and nullable interactions', () => {
  it.each([false, true])(
    'retains nested allOf object constraints (referenced: %s)',
    referenced => {
      const base: Schema = {
        allOf: [{ type: 'object', properties: { id: { type: 'string' } } }],
      };
      const schemas: Record<string, Schema> = {
        Base: base,
        Model: {
          allOf: [
            referenced ? { $ref: '#/components/schemas/Base' } : base,
            { required: ['id'] },
          ],
        },
      };
      const statements = `
        const valid: Model = { id: '1' };
        // @ts-expect-error nested object constraints still exclude strings
        const text: Model = 'x';
        // @ts-expect-error nested object constraints still exclude numbers
        const number: Model = 1;
        // @ts-expect-error nested object constraints still exclude booleans
        const boolean: Model = true;
        // @ts-expect-error nested object constraints still exclude arrays
        const array: Model = [];
        // @ts-expect-error required id must remain present
        const missing: Model = {};
      `;
      expect(
        generateModels(schemas, statements, { schemas }).diagnostics,
      ).toEqual([]);
    },
  );

  it('retains object constraints when the required-only sibling is nested', () => {
    expect(
      generateModel(
        {
          allOf: [
            { type: 'object', properties: { id: { type: 'string' } } },
            { allOf: [{ required: ['id'] }] },
          ],
        },
        `
      const valid: Model = { id: '1' };
      // @ts-expect-error the nested required-only union cannot admit strings
      const text: Model = 'x';
      // @ts-expect-error the nested required-only union cannot admit arrays
      const array: Model = [];
      // @ts-expect-error id must remain present
      const missing: Model = {};
    `,
      ).diagnostics,
    ).toEqual([]);
  });

  it.each(['oneOf', 'anyOf'] as const)(
    'retains object constraints when %s allows only objects or null',
    composition => {
      expect(
        generateModel(
          {
            allOf: [
              {
                [composition]: [
                  { type: 'object', properties: { id: { type: 'string' } } },
                  { type: 'object', properties: { id: { type: 'number' } } },
                  { type: 'null' },
                ],
              },
              { required: ['id'] },
            ],
          },
          `
        const valid: Model[] = [{ id: '1' }, { id: 1 }, null];
        // @ts-expect-error no branch permits a string value
        const text: Model = 'x';
        // @ts-expect-error no branch permits an array value
        const array: Model = [];
        // @ts-expect-error an object still needs id
        const missing: Model = {};
      `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each(['oneOf', 'anyOf'] as const)(
    'retains non-object alternatives allowed by nested %s',
    composition => {
      expect(
        generateModel(
          {
            allOf: [
              {
                allOf: [
                  {
                    [composition]: [
                      {
                        type: 'object',
                        properties: { id: { type: 'string' } },
                      },
                      { type: 'string' },
                      { type: 'number' },
                      { type: 'boolean' },
                      { type: 'array', items: { type: 'string' } },
                      { type: 'null' },
                    ],
                  },
                ],
              },
              { required: ['id'] },
            ],
          },
          `
        const valid: Model[] = ['x', 1, true, [], null, { id: '1' }];
        // @ts-expect-error an object still needs id
        const missing: Model = {};
      `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it('terminates constraint discovery through recursive allOf references', () => {
    const schema: Schema = {
      allOf: [{ $ref: '#/components/schemas/Recursive' }, { required: ['id'] }],
    };
    let file: SourceFile | undefined;
    expect(() => {
      file = writeModels(
        { Model: schema },
        {
          schemas: {
            Recursive: { allOf: [{ $ref: '#/components/schemas/Recursive' }] },
          },
        },
      );
    }).not.toThrow();
    expect(file!.getTypeAlias('Model')).toBeDefined();
  });

  it.each([false, true])(
    'keeps required-only allOf intersections object-constrained (nullable: %s)',
    nullable => {
      expect(
        generateModel(
          {
            allOf: [
              {
                type: 'object',
                nullable,
                properties: { id: { type: 'string' } },
              },
              { required: ['id'] },
            ],
          },
          `
      const valid: Model = { id: '1' };
      ${nullable ? '' : '// @ts-expect-error the object schema rejects null'}
      const nullValue: Model = null;
      // @ts-expect-error allOf still rejects primitives
      const text: Model = 'x';
      // @ts-expect-error allOf still rejects primitives
      const number: Model = 1;
      // @ts-expect-error allOf still rejects primitives
      const boolean: Model = true;
      // @ts-expect-error JSON arrays are not objects
      const array: Model = [];
      // @ts-expect-error id must still be present
      const missing: Model = {};
    `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it('allows non-object JSON values for standalone required-only schemas', () => {
    expect(
      generateModel(
        { required: ['id'] },
        `
      const valid: Model[] = ['x', 1, true, null, [], { id: '1' }];
      // @ts-expect-error required applies when the value is an object
      const missing: Model = {};
    `,
      ).diagnostics,
    ).toEqual([]);
  });

  it.each(['oneOf', 'anyOf', 'allOf'] as const)(
    'preserves %s constraints with typeless root properties',
    composition => {
      expect(
        generateModel(
          {
            [composition]:
              composition === 'allOf'
                ? [{ type: 'string' }]
                : [{ type: 'string' }, { type: 'number' }],
            properties: {},
          },
          `
      const valid: Model = 'one';
      // @ts-expect-error empty properties do not erase composition restrictions
      const invalid: Model = true;
    `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each(['fixed', '', 0, false])(
    'does not widen const %j with nullable',
    value => {
      const type =
        typeof value === 'boolean'
          ? 'boolean'
          : typeof value === 'number'
            ? 'number'
            : 'string';
      expect(
        generateModel(
          { type, const: value, nullable: true },
          `
      const valid: Model = ${JSON.stringify(value)};
      // @ts-expect-error const excludes null even when nullable expands the type
      const invalid: Model = null;
    `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it.each([false, true])(
    'combines required fields across siblings (required-only: %s)',
    requiredOnly => {
      const schema: Schema = {
        allOf: [
          { type: 'object', properties: { id: { type: 'string' } } },
          requiredOnly
            ? { required: ['id'] }
            : {
                type: 'object',
                required: ['id'],
                properties: { id: { type: 'string' } },
              },
        ],
      };
      const { diagnostics } = generateModel(
        schema,
        "const valid: Model = { id: '1' };\n// @ts-expect-error allOf requires id\nconst invalid: Model = {};\n// @ts-expect-error the original string constraint remains\nconst wrong: Model = { id: 1 };\n// @ts-expect-error required string must not become undefined\nconst undefinedId: Model = { id: undefined };",
      );
      expect(diagnostics).toEqual([]);
    },
  );

  it('intersects a nullable reference instead of extending its union', () => {
    const schemas: Record<string, Schema> = {
      Base: {
        type: 'object',
        nullable: true,
        properties: { id: { type: 'string' } },
      },
      Derived: {
        allOf: [
          { $ref: '#/components/schemas/Base' },
          { type: 'object', properties: { name: { type: 'string' } } },
        ],
      },
      Required: {
        allOf: [{ $ref: '#/components/schemas/Base' }, { required: ['id'] }],
      },
    };
    const file = writeModels(schemas, { schemas });
    file.addStatements(
      "const valid: Derived = { id: '1', name: 'test' };\n// @ts-expect-error the non-null object sibling excludes null\nconst invalid: Derived = null;\nconst nullable: Required = null;\nconst required: Required = { id: '1' };\n// @ts-expect-error required-only sibling rejects an object missing id\nconst missing: Required = {};",
    );
    file.addStatements(`
      // @ts-expect-error the referenced object still excludes primitives
      const primitive: Required = 'x';
      // @ts-expect-error the referenced object still excludes arrays
      const array: Required = [];
    `);
    expect(diagnosticsOf(file)).toEqual([]);
  });

  it('intersects nullable type with non-null allOf constraints', () => {
    expect(
      generateModel(
        {
          type: 'object',
          nullable: true,
          allOf: [{ type: 'object', properties: { id: { type: 'string' } } }],
        },
        "const valid: Model = { id: '1' };\n// @ts-expect-error the allOf object constraint excludes null\nconst invalid: Model = null;",
      ).diagnostics,
    ).toEqual([]);
  });

  it('retains runtime members of a nullable string enum that excludes null', () => {
    expect(
      generateModel(
        { type: 'string', enum: ['ON'], nullable: true },
        'const valid: Model = Model.ON;\n// @ts-expect-error enum excludes null\nconst invalid: Model = null;',
      ).diagnostics,
    ).toEqual([]);
  });

  it('does not make null valid when enum excludes it', () => {
    expect(
      generateModel(
        { type: 'integer', enum: [0, 1], nullable: true },
        'const valid: Model = 0;\n// @ts-expect-error nullable does not remove enum restrictions\nconst invalid: Model = null;',
      ).diagnostics,
    ).toEqual([]);
    expect(
      generateModel(
        { type: 'integer', enum: [0, 1, null], nullable: true },
        'const valid: Model = null;',
      ).diagnostics,
    ).toEqual([]);
  });
});

describe('allOf preserves every referenced and inline constraint', () => {
  it.each([
    [false, false],
    [true, false],
    [false, true],
    [true, true],
  ])(
    'retains inherited required properties (reverse: %s, two refs: %s)',
    (reverse, twoReferences) => {
      const members: (Schema | { $ref: string })[] = [
        { $ref: '#/components/schemas/Base' },
        twoReferences
          ? { $ref: '#/components/schemas/OptionalBase' }
          : {
              type: 'object',
              properties: { id: { type: 'string' }, other: { type: 'string' } },
            },
      ];
      const schemas: Record<string, Schema> = {
        Base: {
          type: 'object',
          required: ['id'],
          properties: { id: { type: 'string' } },
        },
        OptionalBase: {
          type: 'object',
          properties: { id: { type: 'string' }, other: { type: 'string' } },
        },
        Derived: { allOf: reverse ? [...members].reverse() : members },
      };
      const file = writeModels(schemas, { schemas });
      file.addStatements(
        "const valid: Derived = { id: '1', other: 'other' };\n// @ts-expect-error inherited id remains required\nconst missing: Derived = {};\n// @ts-expect-error inherited id must be a string\nconst wrong: Derived = { id: 1 };\n// @ts-expect-error required does not permit undefined\nconst unset: Derived = { id: undefined };",
      );
      expect(diagnosticsOf(file)).toEqual([]);
    },
  );

  it.each([false, true])(
    'intersects incompatible properties instead of taking the last type: %s',
    reverse => {
      const members: Schema[] = [
        {
          type: 'object',
          required: ['id'],
          properties: { id: { type: 'string' } },
        },
        {
          type: 'object',
          required: ['id'],
          properties: { id: { type: 'number' } },
        },
      ];
      expect(
        generateModel(
          { allOf: reverse ? [...members].reverse() : members },
          "// @ts-expect-error string violates the number constraint\nconst stringId: Model = { id: '1' };\n// @ts-expect-error number violates the string constraint\nconst numberId: Model = { id: 1 };\n// @ts-expect-error id is still required\nconst missing: Model = {};",
        ).diagnostics,
      ).toEqual([]);
    },
  );
});

it('requires keys absent from an object schema properties map', () => {
  expect(
    generateModel(
      {
        type: 'object',
        properties: { name: { type: 'string' } },
        required: ['id'],
      },
      "const valid: Model = { id: 1, name: 'name' };\n// @ts-expect-error required is independent from properties\nconst missing: Model = {};",
    ).diagnostics,
  ).toEqual([]);
});
