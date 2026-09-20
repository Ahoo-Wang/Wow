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
import { Project } from 'ts-morph';
import type { Components, OpenAPI, Schema } from '@ahoo-wang/fetcher-openapi';
import { AggregateResolver } from '../src/aggregate';
import { GenerateContext } from '../src/generateContext';
import { ModelGenerator, TypeGenerator } from '../src/model';
import type { GeneratorConfiguration, Logger } from '../src/types';
import demoSpec from './demo.spec.json';

function generateModel(
  schema: Schema,
  nonNullRequired: boolean,
  components?: Components,
): { text: string; diagnostics: unknown[] } {
  const project = new Project({
    useInMemoryFileSystem: true,
    compilerOptions: {
      strict: true,
      skipLibCheck: true,
      lib: ['lib.es2020.d.ts'],
    },
  });
  const file = project.createSourceFile('/types.ts', '');
  new TypeGenerator(
    { name: 'Model', path: '/' },
    file,
    { key: 'Model', schema },
    '/',
    components,
    nonNullRequired,
  ).generate();
  return {
    text: file.getFullText(),
    diagnostics: project.getPreEmitDiagnostics().map(d => d.getMessageText()),
  };
}

function generate(
  schema: Schema,
  nonNullRequired: boolean,
  components?: Components,
): string {
  return generateModel(schema, nonNullRequired, components).text;
}

const silentLogger: Logger = {
  info: () => undefined,
  success: () => undefined,
  error: () => undefined,
  progress: () => undefined,
  progressWithCount: () => undefined,
};

function generateDemoModels(config: GeneratorConfiguration): {
  order: string;
  cart: string;
  logs: string[];
} {
  const logs: string[] = [];
  const openAPI = demoSpec as unknown as OpenAPI;
  const project = new Project({ useInMemoryFileSystem: true });
  const context = new GenerateContext({
    openAPI,
    project,
    outputDir: '/out',
    contextAggregates: new AggregateResolver(openAPI).resolve(),
    logger: {
      ...silentLogger,
      info: (message: string) => {
        logs.push(message);
      },
    },
    config,
  });
  new ModelGenerator(context).generate();
  const readGenerated = (filePath: string) =>
    project.getSourceFileOrThrow(filePath).getFullText();
  return {
    order: readGenerated('/out/example/order/types.ts'),
    cart: readGenerated('/out/example/cart/types.ts'),
    logs,
  };
}

describe('read-model required properties', () => {
  const state: Schema = {
    type: 'object',
    required: ['id'],
    properties: {
      id: { type: 'string' },
      status: { type: 'string' },
      payable: { type: 'number', readOnly: true },
    },
  };

  it('honours the declared required list by default', () => {
    expect(generate(state, false)).toContain('status?: string;');
  });

  it('requires non-nullable properties of a read model', () => {
    const generated = generate(state, true);
    expect(generated).toContain('status: string;');
    expect(generated).toContain('readonly payable: number;');
    expect(generated).not.toContain('?:');
  });

  it.each([
    ['the 3.0 nullable flag', { type: 'string', nullable: true }],
    ['a 3.1 null type entry', { type: ['string', 'null'] }],
    ['a null anyOf branch', { anyOf: [{ type: 'null' }, { type: 'string' }] }],
    ['a null oneOf branch', { oneOf: [{ type: 'null' }, { type: 'string' }] }],
    ['a null enum member', { enum: ['a', null] }],
    ['a null const', { const: null }],
    [
      'an allOf whose every branch admits null',
      { allOf: [{ type: ['string', 'null'] }, { enum: ['x', null] }] },
    ],
  ] satisfies [string, Schema][])(
    'keeps a property optional when it admits null through %s',
    (_, propSchema) => {
      expect(
        generate(
          {
            type: 'object',
            required: ['id'],
            properties: { id: { type: 'string' }, nullish: propSchema },
          },
          true,
        ),
      ).toContain('nullish?:');
    },
  );

  it.each([
    [
      'a sibling type rejects the null enum member',
      { type: 'string', enum: ['a', null] },
    ],
    ['a sibling type rejects the null const', { type: 'string', const: null }],
    [
      'an allOf branch rejects null',
      { allOf: [{ type: ['string', 'null'] }, { type: 'string' }] },
    ],
    [
      'no anyOf branch admits null',
      { anyOf: [{ type: 'string' }, { type: 'number' }] },
    ],
  ] satisfies [string, Schema][])(
    'requires a property that only looks nullable when %s',
    (_, propSchema) => {
      expect(
        generate(
          {
            type: 'object',
            properties: { value: propSchema },
          },
          true,
        ),
      ).not.toContain('value?:');
    },
  );

  it('leaves a write-only property optional', () => {
    // writeOnly belongs to the request side, so a response may omit it however
    // its type reads.
    expect(
      generate(
        {
          type: 'object',
          properties: {
            secret: { type: 'string', writeOnly: true },
            name: { type: 'string' },
          },
        },
        true,
      ),
    ).toContain('secret?: string;');
  });

  it.each([
    ['a not that constrains nothing', { not: {} }],
    ['an empty enum', { enum: [] }],
  ] satisfies [string, Schema][])(
    'leaves a property optional when no value satisfies it: %s',
    (_, propSchema) => {
      // A response must omit such a property, so claiming the server always
      // returns it would be a lie in the other direction.
      expect(
        generate({ type: 'object', properties: { removed: propSchema } }, true),
      ).toContain('removed?:');
    },
  );

  it('leaves a write-only property optional through a reference', () => {
    const components: Components = {
      schemas: { Secret: { type: 'string', writeOnly: true } },
    };
    expect(
      generate(
        {
          type: 'object',
          properties: { secret: { $ref: '#/components/schemas/Secret' } },
        },
        true,
        components,
      ),
    ).toContain('secret?: Secret;');
  });

  it('keeps the intersection form when additionalProperties are typed', () => {
    // An interface may only carry a named property assignable to its index
    // signature, so promoting `name` must not switch the model to a form that
    // no longer compiles (TS2411).
    const schema: Schema = {
      type: 'object',
      properties: { name: { type: 'string' } },
      additionalProperties: { type: 'number' },
    };
    const promoted = generateModel(schema, true);
    expect(promoted.diagnostics).toEqual([]);
    expect(promoted.text).toContain('name: string');
    expect(promoted.text).toContain('globalThis.Record<string, number>');
    expect(generateModel(schema, false).diagnostics).toEqual([]);
  });

  it('follows references when deciding nullability', () => {
    const components: Components = {
      schemas: {
        Nullish: { type: 'string', nullable: true },
        Present: { type: 'string' },
      },
    };
    const generated = generate(
      {
        type: 'object',
        properties: {
          nullish: { $ref: '#/components/schemas/Nullish' },
          present: { $ref: '#/components/schemas/Present' },
        },
      },
      true,
      components,
    );
    expect(generated).toContain('nullish?:');
    expect(generated).toContain('present: Present;');
  });

  it('survives a reference cycle', () => {
    const components: Components = {
      schemas: {
        Node: {
          type: 'object',
          properties: { parent: { $ref: '#/components/schemas/Node' } },
        },
      },
    };
    expect(
      generate(
        {
          type: 'object',
          properties: { node: { $ref: '#/components/schemas/Node' } },
        },
        true,
        components,
      ),
    ).toContain('node: Node;');
  });

  it('applies to inline nested objects of a read model', () => {
    expect(
      generate(
        {
          type: 'object',
          properties: {
            nested: {
              type: 'object',
              properties: { name: { type: 'string' } },
            },
          },
        },
        true,
      ),
    ).toContain('name: string');
  });

  it('leaves required keys outside properties alone', () => {
    const generated = generate(
      {
        type: 'object',
        required: ['extra'],
        properties: { id: { type: 'string' } },
      },
      true,
    );
    expect(generated).toContain('id: string;');
    expect(generated).toContain('extra:');
  });
});

describe('read-model required wiring', () => {
  it('leaves generated models untouched when disabled', () => {
    expect(generateDemoModels({}).order).toContain('status?: OrderStatus;');
  });

  it('requires aggregate state and event properties when enabled', () => {
    const { order } = generateDemoModels({
      readModel: { nonNullRequired: true },
    });
    expect(order).toContain('status: OrderStatus;');
    expect(order).toContain('readonly payable: number;');
    expect(order).toContain('readonly totalPrice: number;');
  });

  it('keeps command schemas as the document declares them', () => {
    const { cart } = generateDemoModels({
      readModel: { nonNullRequired: true },
    });
    // quantity is a non-nullable optional property of the AddCartItem command.
    // The read-model rule never reaches a command schema, so it stays optional
    // - promoting it would reject a request the client may legitimately send.
    expect(cart).toMatch(/export interface AddCartItem \{[^}]*quantity\?:/s);
  });

  it('reports nothing when no shared schema is contested', () => {
    const { logs } = generateDemoModels({
      readModel: { nonNullRequired: true },
    });
    expect(
      logs.filter(message =>
        message.startsWith('Keeping declared optionality'),
      ),
    ).toEqual([]);
  });
});
