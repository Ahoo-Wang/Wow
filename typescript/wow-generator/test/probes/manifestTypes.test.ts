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
 * Probes of the types a Manifest's clients are written with: a small Manifest
 * through the CLI into a cold directory, type-checked, for the schemas, the
 * names and the documents the eShop does not have.
 */

import { describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import { generateCold } from '../support/generation';
import { generateCompiling } from '../support/probes';
import { afterAll } from 'vitest';
import { removeDirectories } from '../support/generation';
import { manifest, schema } from '../support/manifestProbes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

describe('Manifest clients', () => {
  it('types every kind of schema', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          snapshot: { enabled: false },
          queries: [{ model: 'events' }],
          commands: [
            {
              name: 'Shape',
              schema: schema('a.Shape'),
              routes: [{ method: 'POST', path: '/probe/thing/shape' }],
            },
          ],
          events: [{ name: 'Shaped', schema: schema('b.Shape') }],
        },
        {
          'a.Shape': {
            description: 'A shape of every kind',
            type: 'object',
            required: ['kind', 'odd name'],
            properties: {
              kind: { const: 'shape' },
              'odd name': { type: 'string', description: 'Quoted */ safely' },
              level: { enum: [1, 2, 'three'] },
              none: { enum: [] },
              choice: { oneOf: [{ type: 'string' }, { type: 'integer' }] },
              either: {
                anyOf: [{ $ref: schema('b.Shape') }, { type: 'null' }],
              },
              both: {
                allOf: [
                  { type: 'object', properties: { x: { type: 'number' } } },
                  { type: 'object', additionalProperties: { type: 'boolean' } },
                ],
              },
              list: { type: 'array', items: { type: ['string', 'null'] } },
              anyList: { type: 'array' },
              map: { type: 'object', additionalProperties: true },
              open: {
                type: 'object',
                properties: { a: { type: 'string' } },
                additionalProperties: { type: 'integer' },
              },
              never: false,
              anything: true,
              untyped: {},
              weird: { type: 'tuple' },
              bare: { properties: { b: { type: 'boolean' } } },
              missing: { $ref: schema('a.Missing') },
              named: {
                $ref: schema('b.Shape'),
                title: 'Its shape',
                description: 'Which of the two it is',
              },
              titled: { $ref: schema('c.Titled') },
              maybeNamed: {
                anyOf: [{ type: 'null' }, { $ref: schema('b.Shape') }],
                title: 'Its other shape',
              },
              foreign: { $ref: 'https://example.com/schema' },
            },
            additionalProperties: { type: 'string' },
          },
          'c.Titled': { title: 'Only a summary', type: 'string' },
          'b.Shape': {
            title: 'A or B',
            description: 'One of two shapes',
            type: 'string',
            enum: ['A', 'B'],
          },
        },
      ),
      `import type { AShape, BShape } from './out/probe/types.js';
       const shape: AShape = { kind: 'shape', 'odd name': 'x', level: 'three', choice: 1, list: [null] };
       const named: BShape = 'A';
       void [shape, named];`,
    );
    const types = read('probe/types.ts');
    expect(types).toContain("kind: 'shape';");
    expect(types).toContain("'odd name': string;");
    expect(types).toContain('Quoted *\\/ safely');
    expect(types).toContain("level?: 1 | 2 | 'three';");
    expect(types).toContain('none?: never;');
    expect(types).toContain('choice?: string | number;');
    expect(types).toContain('either?: BShape | null;');
    expect(types).toContain('list?: (string | null)[];');
    expect(types).toContain('anyList?: unknown[];');
    expect(types).toContain('map?: Record<string, unknown>;');
    expect(types).toContain('open?: { a?: string;[key: string]: unknown; };');
    expect(types).toContain('missing?: unknown;');
    expect(types).toContain('[key: string]: unknown;');
    expect(types).toContain("export type BShape = 'A' | 'B';");
    // A property's own words, beside the reference to its type (#7262); the
    // type's own stay on its model, and a bare reference says nothing.
    expect(types).toContain(
      '  /**\n   * Its shape\n   * Which of the two it is\n   */\n  named?: BShape;',
    );
    expect(types).toContain(
      '  /** Its other shape */\n  maybeNamed?: null | BShape;',
    );
    expect(types).toContain(
      '/**\n * A or B\n * One of two shapes\n * - key: `b.Shape`\n */\nexport type BShape',
    );
    expect(types).toContain(';\n  missing?: unknown;');
    // A type with a summary alone is documented as it was when the summary
    // was its description.
    expect(types).toContain(
      '/**\n * Only a summary\n * - key: `c.Titled`\n */\nexport type Titled = string;',
    );
    expect(read('probe/thing/commandClient.ts')).toContain(
      'shape(body: AShape, options?: CommandOptions)',
    );
  });
  it('warns of a schema $defs does not hold', async () => {
    const result = await generateCold(
      manifest({
        commands: [
          {
            name: 'Ghost',
            schema: schema('a.Ghost'),
            routes: [{ method: 'POST', path: '/probe/thing/{id}/ghost' }],
          },
        ],
      }),
      directories,
    );
    expect(result.exitCode).toBe(EXIT_CODES.success);
    expect(result.logger.warnings).toEqual([
      "probe.thing.Ghost refers to the schema a.Ghost, which the Manifest's $defs does not hold; it is typed as unknown.",
    ]);
  });
  it('qualifies type names that clash', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          state: { schema: schema('x.one.Item') },
          events: [
            { name: 'A', schema: schema('x.two.Item') },
            { name: 'B', schema: schema('Item') },
            { name: 'C', schema: schema('y.one.Item') },
            { name: 'D', schema: schema('z.Outer$Inner') },
          ],
        },
        {
          'x.one.Item': {
            type: 'object',
            properties: {
              self: { $ref: schema('x.one.Item') },
              nested: {
                type: 'object',
                properties: { deep: { type: 'object', properties: { d: {} } } },
              },
              tags: { type: 'array', items: { $ref: schema('x.two.Item') } },
            },
          },
          'x.two.Item': { type: 'object', properties: { b: {} } },
          Item: { type: 'string' },
          'y.one.Item': { type: 'integer' },
          'z.Outer$Inner': { type: 'boolean' },
        },
      ),
    );
    const types = read('probe/types.ts');
    expect(types).toContain('export interface XOneItem');
    expect(types).toContain('export interface TwoItem');
    expect(types).toContain('export type Item = string;');
    expect(types).toContain('export type YOneItem = number;');
    expect(types).toContain('export type OuterInner = boolean;');
    expect(read('probe/thing/aggregate.ts')).toContain(
      "export type ThingStateField = 'state.self' | 'state.nested' | 'state.nested.deep' | 'state.nested.deep.d' | 'state.tags' | 'state.tags.b';",
    );
  });
  it('writes a parameterized type in place, never as a type that shadows a global', async () => {
    const map = 'java.util.Map(java.lang.String,java.lang.Object)';
    const list = 'java.util.List(s.Shared)';
    const parameterized = {
      format: 1,
      contexts: [
        {
          name: 'first',
          aggregates: [{ name: 'a', state: { schema: schema('s.Shared') } }],
        },
        {
          name: 'second',
          aggregates: [{ name: 'b', state: { schema: schema('s.Holder') } }],
        },
      ],
      $defs: {
        's.Shared': {
          type: 'object',
          properties: {
            config: { $ref: schema(map) },
            fields: { $ref: schema(`${map}-nullable`) },
            labels: {
              $ref: schema('java.util.Map(java.lang.String,java.lang.String)'),
            },
          },
          required: ['config'],
        },
        's.Holder': {
          type: 'object',
          properties: { items: { $ref: schema(list) } },
        },
        [map]: { type: 'object' },
        [`${map}-nullable`]: {
          anyOf: [{ type: 'null' }, { $ref: schema(map) }],
        },
        'java.util.Map(java.lang.String,java.lang.String)': {
          type: 'object',
          additionalProperties: { type: 'string' },
        },
        [list]: { type: 'array', items: { $ref: schema('s.Shared') } },
      },
    };
    const { read } = await generateCompiling(
      parameterized,
      `import type { Shared } from './out/first/types.js';
       import type { Holder } from './out/second/types.js';
       const shared: Shared = { config: { any: 1 }, fields: null, labels: { a: 'b' } };
       const holder: Holder = { items: [shared] };
       void [holder, Object.keys(shared)];`,
    );
    const first = read('first/types.ts');
    expect(first).toContain('config: Record<string, unknown>;');
    expect(first).toContain('fields?: null | Record<string, unknown>;');
    expect(first).toContain('labels?: Record<string, string>;');
    expect(first).not.toMatch(/export (type|interface) (Object|String)\b/);
    const second = read('second/types.ts');
    expect(second).toContain('items?: Shared[];');
    expect(second).toContain(
      "import type { Shared } from '../first/types.js';",
    );
  });
  it('declares a schema two contexts reach in the first', async () => {
    const shared = {
      format: 1,
      contexts: [
        {
          name: 'first',
          aggregates: [{ name: 'a', state: { schema: schema('s.Shared') } }],
        },
        {
          name: 'second',
          aggregates: [{ name: 'b', state: { schema: schema('s.Shared') } }],
        },
        { name: 'empty' },
      ],
      $defs: { 's.Shared': { type: 'object', properties: { v: {} } } },
    };
    const { read } = await generateCompiling(shared);
    expect(read('second/b/queryClient.ts')).toContain(
      "import type { Shared } from '../../first/types.js';",
    );
  });
  it('refuses a Manifest of another format or without what it needs', async () => {
    const other = await generateCold({ format: 2, contexts: [] }, directories);
    expect(other.exitCode).toBe(EXIT_CODES.input);
    // A marker of requires the generator does not understand is refused too.
    const requiring = await generateCold(
      { format: 1, requires: ['routes-v2'], contexts: [] },
      directories,
    );
    expect(requiring.exitCode).toBe(EXIT_CODES.input);
    const broken = await generateCold(
      {
        format: 1,
        contexts: [
          {},
          {
            name: 'c',
            aggregates: [
              {},
              { name: 'x' },
              {
                name: 'y',
                state: { schema: '#/$defs/S' },
                commands: [{}],
                events: [{}],
              },
            ],
          },
        ],
        $defs: [],
      },
      directories,
    );
    expect(broken.exitCode).toBe(EXIT_CODES.specification);
    expect(broken.errors).toEqual([
      expect.stringContaining(
        'contexts[0] has no name; c.aggregates[0] has no name; c.x has no state schema; a command of c.y has no name or schema; an event of c.y has no name or schema; $defs is not an object.',
      ),
    ]);
  });
});

describe('Manifest types a plain type cannot be', () => {
  it('declares a recursive parameterized type once, named by its class and its argument, and refers to it by name', async () => {
    const tree = 'a.Node(java.lang.String)';
    const { read } = await generateCompiling(
      manifest(
        {},
        {
          'a.ThingState': {
            type: 'object',
            required: ['tree'],
            properties: { tree: { $ref: schema(tree) } },
          },
          // Node<String>, referred to by the state and by its children.
          [tree]: {
            type: 'object',
            required: ['value', 'children'],
            properties: {
              value: { type: 'string' },
              children: { type: 'array', items: { $ref: schema(tree) } },
            },
          },
        },
      ),
      `import type { NodeOfString, ThingState } from './out/index.js';
       const tree: NodeOfString = { value: 'root', children: [{ value: 'leaf', children: [] }] };
       const state: ThingState = { tree };
       void state;`,
    );
    const types = read('probe/types.ts');
    expect(types).toContain('export interface NodeOfString {');
    expect(types).toContain('children: NodeOfString[];');
    expect(types).toContain('tree: NodeOfString;');
  });

  it("declares a service route parameter's $defs type and types the parameter by it", async () => {
    const { read, logger } = await generateCompiling(
      manifest(
        {},
        { 'a.Status': { type: 'string', enum: ['OPEN', 'CLOSED'] } },
        {
          routes: [
            {
              name: 'search',
              route: { method: 'GET', path: '/probe/search' },
              parameters: [
                {
                  name: 'status',
                  in: 'query',
                  schema: { $ref: schema('a.Status') },
                  required: false,
                },
              ],
              response: { type: 'string' },
            },
          ],
        },
      ),
      `import { ProbeRoutes } from './out/index.js';
       const routes = new ProbeRoutes();
       void routes.search({ status: 'OPEN' });
       // @ts-expect-error No such status.
       void routes.search({ status: 'LOST' });`,
    );
    expect(logger.warnings).toEqual([]);
    expect(read('probe/types.ts')).toContain(
      "export type Status = 'OPEN' | 'CLOSED';",
    );
    expect(read('probe/routes.ts')).toContain(
      'query?: { readonly status?: Status; }',
    );
  });
});

describe('a Manifest of a malformed shape', () => {
  it("is refused as a specification error naming the member, not as the generator's defect", async () => {
    const listed = manifest();
    (listed.contexts[0] as Record<string, unknown>).aggregates = {
      thing: listed.contexts[0].aggregates[0],
    };
    const routeless = manifest({}, {}, { routes: [{ name: 'ping' }] });
    const results = [];
    for (const document of [listed, routeless]) {
      results.push(await generateCold(document, directories));
    }
    expect(results.map(result => result.exitCode)).toEqual([
      EXIT_CODES.specification,
      EXIT_CODES.specification,
    ]);
    const errors = results.map(result => result.errors.join('\n'));
    // Not "Code generation failed: … report it": the run's defect message.
    expect(errors.join('\n')).not.toContain('Code generation failed');
    expect(errors[0]).toContain('probe.aggregates is not a list');
    expect(errors[1]).toContain(
      'the route ping of probe has no route of a method and a path',
    );
  });
});
