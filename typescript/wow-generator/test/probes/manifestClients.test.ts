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
 * Probes of the clients a Manifest generates: a small Manifest through the
 * CLI into a cold directory, type-checked, for the shapes the eShop does not
 * have.
 */

import { describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import { generateCold } from '../support/generation';
import { generateCompiling } from '../support/probes';
import { afterAll } from 'vitest';
import { removeDirectories } from '../support/generation';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const schema = (key: string) => `#/$defs/${key}`;

/** A Manifest with one context and one aggregate, and what they are given. */
function manifest(
  aggregate: Record<string, unknown> = {},
  defs: Record<string, unknown> = {},
  context: Record<string, unknown> = {},
) {
  return {
    format: 1,
    service: { name: 'probe-service' },
    contexts: [
      {
        name: 'probe',
        aggregates: [
          {
            name: 'thing',
            state: { schema: schema('a.ThingState') },
            ...aggregate,
          },
        ],
        ...context,
      },
    ],
    $defs: {
      'a.ThingState': {
        type: 'object',
        properties: { n: { type: 'integer' } },
      },
      ...defs,
    },
  };
}

describe('Manifest clients', () => {
  it('generates an aggregate with nothing but its state', async () => {
    const { read } = await generateCompiling(
      manifest({}, {}, { description: 'Probes' }),
      `import { ThingCommandClient, ThingStateClient, probeWait, ProbeErrorCodes, type ThingEvent } from './out/index.js';
       const events: ThingEvent[] = [];
       void [new ThingCommandClient(), new ThingStateClient(), probeWait.committed(), ProbeErrorCodes, events];`,
    );
    expect(read('probe/thing/aggregate.ts')).toContain(
      'export type ThingEvent = never;',
    );
    expect(read('probe/thing/queryClient.ts')).not.toContain(
      'SnapshotQueryClient',
    );
    expect(read('probe/boundedContext.ts')).not.toContain('handled');
  });

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
              foreign: { $ref: 'https://example.com/schema' },
            },
            additionalProperties: { type: 'string' },
          },
          'b.Shape': { type: 'string', enum: ['A', 'B'] },
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

  it('names a command after a lifecycle one apart from it', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          lifecycle: [
            'wow.lifecycle.DeleteAggregate',
            'wow.lifecycle.TransferOwner',
            'wow.lifecycle.Unknown',
          ],
          commands: [
            {
              name: 'DeleteAggregate',
              schema: schema('a.Delete'),
              creation: 'ALLOWED',
              mounted: true,
              routes: [
                { method: 'POST', path: '/probe/thing/{id}/delete-aggregate' },
                { method: 'POST', path: '/probe/thing/delete-aggregate' },
              ],
            },
            { name: 'Gateway', schema: schema('a.Delete') },
          ],
        },
        { 'a.Delete': { type: 'object', properties: {} } },
        {
          errors: [
            { code: 'probe.thing.gone', status: 410 },
            { code: 'other', status: 422, description: 'Not prefixed' },
          ],
          subscriptions: [{ name: 'P.f' }, { name: 'P.f' }],
        },
      ),
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain('deleteAggregateCommand(body: Delete = {}');
    expect(client).toContain('deleteAggregate(id: string, options?');
    expect(client).toContain('transferOwner(id: string, body: TransferOwner');
    expect(client).not.toContain('unknown(');
    expect(client).not.toContain('gateway(');
    expect(client).toContain('Creates the aggregate, or acts on the one');
    const context = read('probe/boundedContext.ts');
    expect(context).toContain("THING_GONE: 'probe.thing.gone',");
    expect(context).toContain("OTHER: 'other',");
    expect(context).toContain("export type ProbeSubscription = 'P.f';");
  });

  it('types lifecycle bodies from $defs and documents what the Manifest describes', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          lifecycle: [
            'wow.lifecycle.ApplyTags',
            'wow.lifecycle.DeleteAggregate',
            'wow.lifecycle.TransferSpace',
          ],
          commands: [
            {
              name: 'Rename',
              schema: schema('a.Rename'),
              description: 'Renames the thing',
              routes: [{ method: 'POST', path: '/probe/thing/{id}/rename' }],
            },
          ],
        },
        {
          'a.Rename': {
            type: 'object',
            properties: { name: { type: 'string' } },
            required: ['name'],
            description: 'Renames the thing',
          },
          'wow.lifecycle.ApplyTags': {
            type: 'object',
            properties: {
              tags: {
                type: 'object',
                additionalProperties: {
                  type: 'array',
                  items: { type: 'string' },
                },
              },
            },
            required: ['tags'],
            description: 'Sets the tags',
          },
          'wow.lifecycle.DeleteAggregate': {
            type: 'object',
            description: 'Marks it deleted',
          },
        },
      ),
      `import { ThingCommandClient } from './out/index.js';
       import type { ApplyTags } from './out/probe/types.js';
       const tags: ApplyTags = { tags: { color: ['red'] } };
       void [new ThingCommandClient().applyTags('1', tags)];`,
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain(
      "import type { ApplyTags, Rename } from '../types.js';",
    );
    expect(client).toMatch(
      /import \{[^}]*type TransferSpace[^}]*\} from '@ahoo-wang\/wow-client\/v10'/,
    );
    expect(client).not.toMatch(
      /type ApplyTags[^}]*\} from '@ahoo-wang\/wow-client\/v10'/,
    );
    expect(client).toContain('   * Renames the thing\n   * `Rename`:');
    expect(client).toContain(
      '   * Sets the tags\n   * The lifecycle command `wow.lifecycle.ApplyTags`.',
    );
    expect(client).toContain(
      '   * Marks it deleted\n   * The lifecycle command `wow.lifecycle.DeleteAggregate`.',
    );
    expect(client).toContain('deleteAggregate(id: string, options?');
    expect(client).toContain(
      '/** The lifecycle command `wow.lifecycle.TransferSpace`. */',
    );
    const types = read('probe/types.ts');
    expect(types).toContain('export interface ApplyTags {');
    expect(types).not.toContain('DeleteAggregate');
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
      "export type ThingStateField = 'state.self' | 'state.nested' | 'state.nested.deep' | 'state.nested.deep.d' | 'state.tags';",
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
