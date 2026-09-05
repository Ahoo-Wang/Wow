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
import { expect, it } from 'vitest';
import { Project } from 'ts-morph';
import type { Components, Schema } from '@ahoo-wang/fetcher-openapi';
import { TypeGenerator } from '../src/model';

function model(schema: Schema, components?: Components) {
  const project = new Project({
    useInMemoryFileSystem: true,
    compilerOptions: {
      strict: true,
      skipLibCheck: true,
      lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
    },
  });
  const file = project.createSourceFile('/types.ts', '');
  const generator = new TypeGenerator(
    { name: 'Model', path: '/' },
    file,
    { key: 'Model', schema },
    '/',
    components,
  );
  return { project, file, generator };
}

it.each([
  {
    schema: {
      type: ['object', 'null'],
      oneOf: [
        {
          type: 'object',
          properties: { name: { type: 'string' } },
          required: ['name'],
        },
        { type: 'null' },
      ],
    },
    assignments: `
      const valid: Model[] = [{name: 'value'}, null];
      // @ts-expect-error a nullable object union does not admit booleans
      const invalid: Model = false;
      // @ts-expect-error object property constraints remain effective
      const wrong: Model = {name: 1};
    `,
  },
  {
    schema: {
      type: ['array', 'null'],
      anyOf: [{ type: 'array', items: { type: 'string' } }, { type: 'null' }],
    },
    assignments: `
      const valid: Model[] = [['value'], null];
      // @ts-expect-error a nullable array union does not admit booleans
      const invalid: Model = false;
      // @ts-expect-error item constraints remain effective
      const wrong: Model = [1];
    `,
  },
] satisfies { schema: Schema; assignments: string }[])(
  'does not let any erase type-array composition constraints: $schema.type',
  ({ schema, assignments }) => {
    const { generator, project, file } = model(schema);
    generator.generate();
    file.addStatements(assignments);
    expect(
      project.getPreEmitDiagnostics().map(d => d.getMessageText()),
    ).toEqual([]);
  },
);

it('excludes primitive branches whenever an allOf member requires objects', () => {
  const { generator, project, file } = model({
    allOf: [
      { type: 'object', properties: { id: { type: 'string' } } },
      {
        oneOf: [
          { type: 'string' },
          { type: 'object', properties: { name: { type: 'string' } } },
        ],
      },
    ],
  });
  generator.generate();
  file.addStatements(`
    const valid: Model = {};
    // @ts-expect-error an explicit object constraint excludes a string branch
    const invalid: Model = 'text';
  `);
  expect(project.getPreEmitDiagnostics().map(d => d.getMessageText())).toEqual(
    [],
  );
});

it('excludes readonly arrays from object intersections', () => {
  const { generator, project, file } = model({
    allOf: [
      { type: 'object', properties: { length: { type: 'number' } } },
      { required: ['length'] },
    ],
  });
  generator.generate();
  file.addStatements(`
    const valid: Model = {length: 0};
    interface PlainObject { length: number }
    const plain: PlainObject = {length: 0};
    const fromInterface: Model = plain;
    const readonlyTuple = [] as const;
    // @ts-expect-error an array remains an array even when it is readonly
    const invalid: Model = readonlyTuple;
  `);
  expect(project.getPreEmitDiagnostics().map(d => d.getMessageText())).toEqual(
    [],
  );
});

it.each([false, true])(
  'visits shared composition DAG nodes a bounded number of times (recursive leaf: %s)',
  recursiveLeaf => {
    const depth = 12;
    let reads = 0;
    const schemas: NonNullable<Components['schemas']> = {
      Layer0: recursiveLeaf
        ? {
            get allOf() {
              reads++;
              return [{ $ref: '#/components/schemas/Layer0' }];
            },
          }
        : { type: 'object' },
    };
    for (let level = 1; level <= depth; level++) {
      const reference = { $ref: `#/components/schemas/Layer${level - 1}` };
      schemas[`Layer${level}`] = {
        get allOf() {
          reads++;
          return [reference, reference];
        },
      };
    }
    const schema = schemas[`Layer${depth}`] as Schema;
    const { generator } = model(schema, { schemas });
    generator.resolveType(schema);
    expect(reads).toBeLessThanOrEqual((depth + 1) * 4);
  },
);

it('does not reuse a cycle-truncated constraint result on a different path', () => {
  const schema: Schema = {
    oneOf: [
      { $ref: '#/components/schemas/A' },
      { $ref: '#/components/schemas/B' },
    ],
  };
  const { generator } = model(schema, {
    schemas: {
      A: { type: 'object', allOf: [{ $ref: '#/components/schemas/B' }] },
      B: { allOf: [{ $ref: '#/components/schemas/A' }] },
    },
  });
  expect(generator.resolveType(schema)).toMatch(/^globalThis\.Exclude</);
});

it.each(['anyOf', 'oneOf'] as const)(
  'keeps a primitive alternative through mutually recursive %s constraints',
  keyword => {
    const schema: Schema = {
      [keyword]: [
        { $ref: '#/components/schemas/A' },
        { $ref: '#/components/schemas/B' },
      ],
    };
    const { generator } = model(schema, {
      schemas: {
        A: {
          [keyword]: [{ $ref: '#/components/schemas/B' }, { type: 'string' }],
        },
        B: { allOf: [{ $ref: '#/components/schemas/A' }, { type: 'object' }] },
      },
    });
    expect(generator.resolveType(schema)).not.toContain('globalThis.Exclude<');
  },
);

it('keeps legal recursive properties in generated object compositions', () => {
  const schema: Schema = {
    type: 'object',
    properties: {
      name: { type: 'string' },
      next: { $ref: '#/components/schemas/Model' },
    },
    allOf: [{ required: ['name'] }],
  };
  const { generator, project, file } = model(schema, {
    schemas: { Model: schema },
  });
  generator.generate();
  file.addStatements(`
    const valid: Model = {name: 'one', next: {name: 'two'}};
    // @ts-expect-error recursive object properties retain their constraint
    const invalid: Model = {name: 'one', next: 1};
  `);
  expect(project.getPreEmitDiagnostics().map(d => d.getMessageText())).toEqual(
    [],
  );
});
