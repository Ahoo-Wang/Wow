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
import { Project, ModuleKind } from 'ts-morph';
import { runInNewContext } from 'node:vm';
import type { Components, Schema } from '@ahoo-wang/fetcher-openapi';
import { TypeGenerator } from '../src/model';

function generateModel(
  schema: Schema,
  assignments: string,
  components?: Components,
) {
  const project = new Project({
    useInMemoryFileSystem: true,
    compilerOptions: {
      strict: true,
      skipLibCheck: true,
      lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
    },
  });
  const file = project.createSourceFile('/types.ts', '');
  new TypeGenerator(
    { name: 'Model', path: '/' },
    file,
    { key: 'Model', schema },
    '/',
    components,
  ).generate();
  file.addStatements(assignments);
  return {
    file,
    diagnostics: project.getPreEmitDiagnostics().map(d => d.getMessageText()),
  };
}

describe('review regressions', () => {
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
      file.getProject().compilerOptions.set({ module: ModuleKind.CommonJS });
      const exports = {};
      runInNewContext(file.getEmitOutput().getOutputFiles()[0].getText(), {
        exports,
      });
      expect(exports).toMatchObject({
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
    file.getProject().compilerOptions.set({ module: ModuleKind.CommonJS });
    const exports = {};
    runInNewContext(file.getEmitOutput().getOutputFiles()[0].getText(), {
      exports,
    });
    expect(exports).toMatchObject({ Model: { ON: 'ON' }, value: 'ON' });
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

describe('allOf required and nullable interactions', () => {
  it.each([false, true])(
    'retains nested allOf object constraints (referenced: %s)',
    referenced => {
      const project = new Project({
        useInMemoryFileSystem: true,
        compilerOptions: {
          strict: true,
          skipLibCheck: true,
          lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
        },
      });
      const file = project.createSourceFile('/types.ts', '');
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
      for (const [name, schema] of Object.entries(schemas)) {
        new TypeGenerator(
          { name, path: '/' },
          file,
          { key: name, schema },
          '/',
          { schemas },
        ).generate();
      }
      file.addStatements(`
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
      `);
      expect(
        project.getPreEmitDiagnostics().map(d => d.getMessageText()),
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
    const project = new Project({ useInMemoryFileSystem: true });
    const file = project.createSourceFile('/types.ts', '');
    const schema: Schema = {
      allOf: [{ $ref: '#/components/schemas/Recursive' }, { required: ['id'] }],
    };
    const generator = new TypeGenerator(
      { name: 'Model', path: '/' },
      file,
      { key: 'Model', schema },
      '/',
      {
        schemas: {
          Recursive: { allOf: [{ $ref: '#/components/schemas/Recursive' }] },
        },
      },
    );
    expect(() => generator.generate()).not.toThrow();
    expect(file.getTypeAlias('Model')).toBeDefined();
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
    const project = new Project({
      useInMemoryFileSystem: true,
      compilerOptions: {
        strict: true,
        skipLibCheck: true,
        lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
      },
    });
    const file = project.createSourceFile('/types.ts', '');
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
    for (const [name, schema] of Object.entries(schemas)) {
      new TypeGenerator({ name, path: '/' }, file, { key: name, schema }, '/', {
        schemas,
      }).generate();
    }
    file.addStatements(
      "const valid: Derived = { id: '1', name: 'test' };\n// @ts-expect-error the non-null object sibling excludes null\nconst invalid: Derived = null;\nconst nullable: Required = null;\nconst required: Required = { id: '1' };\n// @ts-expect-error required-only sibling rejects an object missing id\nconst missing: Required = {};",
    );
    file.addStatements(`
      // @ts-expect-error the referenced object still excludes primitives
      const primitive: Required = 'x';
      // @ts-expect-error the referenced object still excludes arrays
      const array: Required = [];
    `);
    expect(
      project.getPreEmitDiagnostics().map(d => d.getMessageText()),
    ).toEqual([]);
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
      const project = new Project({
        useInMemoryFileSystem: true,
        compilerOptions: {
          strict: true,
          skipLibCheck: true,
          lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
        },
      });
      const file = project.createSourceFile('/types.ts', '');
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
      for (const [name, schema] of Object.entries(schemas)) {
        new TypeGenerator(
          { name, path: '/' },
          file,
          { key: name, schema },
          '/',
          { schemas },
        ).generate();
      }
      file.addStatements(
        "const valid: Derived = { id: '1', other: 'other' };\n// @ts-expect-error inherited id remains required\nconst missing: Derived = {};\n// @ts-expect-error inherited id must be a string\nconst wrong: Derived = { id: 1 };\n// @ts-expect-error required does not permit undefined\nconst unset: Derived = { id: undefined };",
      );
      expect(
        project.getPreEmitDiagnostics().map(d => d.getMessageText()),
      ).toEqual([]);
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

describe('simultaneous composition and declaration collisions', () => {
  it.each(['oneOf', 'anyOf'] as const)(
    'intersects allOf with the complete %s union',
    composition => {
      expect(
        generateModel(
          {
            allOf: [
              {
                type: 'object',
                required: ['id'],
                properties: { id: { type: 'string' } },
              },
            ],
            [composition]: ['a', 'b'].map(kind => ({
              type: 'object',
              required: ['kind'],
              properties: { kind: { const: kind } },
            })),
          },
          `
            const valid: Model[] = [{ id: '1', kind: 'a' }, { id: '2', kind: 'b' }];
            // @ts-expect-error allOf still requires id
            const missingId: Model = { kind: 'a' };
            // @ts-expect-error the union still requires kind
            const missingKind: Model = { id: '1' };
            // @ts-expect-error allOf still constrains the id type
            const wrongId: Model = { id: 1, kind: 'a' };
          `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it('intersects simultaneous oneOf and anyOf unions', () => {
    expect(
      generateModel(
        {
          oneOf: [{ const: 'a' }, { const: 'b' }],
          anyOf: [{ const: 'b' }, { const: 'c' }],
        },
        `
          const valid: Model = 'b';
          // @ts-expect-error a does not satisfy anyOf
          const excludedByAnyOf: Model = 'a';
          // @ts-expect-error c does not satisfy oneOf
          const excludedByOneOf: Model = 'c';
        `,
      ).diagnostics,
    ).toEqual([]);
  });

  it('keeps the global Exclude available beside a model of the same name', () => {
    const project = new Project({
      useInMemoryFileSystem: true,
      compilerOptions: {
        strict: true,
        skipLibCheck: true,
        lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
      },
    });
    const file = project.createSourceFile('/types.ts', '');
    const schemas: Record<string, Schema> = {
      Exclude: { type: 'string' },
      Model: {
        allOf: [
          { type: 'object', properties: { id: { type: 'string' } } },
          { required: ['id'] },
        ],
      },
    };
    for (const [name, schema] of Object.entries(schemas)) {
      new TypeGenerator({ name, path: '/' }, file, { key: name, schema }, '/', {
        schemas,
      }).generate();
    }
    file.addStatements(`
      const component: Exclude = 'local model';
      const valid: Model = { id: '1' };
      // @ts-expect-error id remains required
      const missing: Model = {};
      // @ts-expect-error required-only branches cannot admit a string
      const primitive: Model = 'x';
      // @ts-expect-error required-only branches cannot admit an array
      const array: Model = [];
    `);
    expect(
      project.getPreEmitDiagnostics().map(d => d.getMessageText()),
    ).toEqual([]);
  });

  it.each([false, true])(
    'keeps empty and separator enum members distinct (composed: %s)',
    composed => {
      const { diagnostics, file } = generateModel(
        {
          type: 'string',
          enum: ['', '-', '_', 'ON'],
          'x-enum-text': {
            '': 'Empty',
            '-': 'Dash',
            _: 'Underscore',
            ON: 'On',
          },
          ...(composed ? { allOf: [{ type: 'string' as const }] } : {}),
        },
        `
          export const values: Model[] = [Model[''], Model['-'], Model['_'], Model.ON];
          export const labels = [ModelEnumText[''], ModelEnumText['-'], ModelEnumText['_'], ModelEnumText.ON];
        `,
      );
      expect(diagnostics).toEqual([]);
      file.getProject().compilerOptions.set({ module: ModuleKind.CommonJS });
      const exports = {};
      runInNewContext(file.getEmitOutput().getOutputFiles()[0].getText(), {
        exports,
      });
      expect(exports).toMatchObject({
        Model: { '': '', '-': '-', _: '_', ON: 'ON' },
        ModelEnumText: { '': 'Empty', '-': 'Dash', _: 'Underscore', ON: 'On' },
        values: ['', '-', '_', 'ON'],
        labels: ['Empty', 'Dash', 'Underscore', 'On'],
      });
    },
  );
});
