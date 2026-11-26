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
 * The shape of the generated code as D486 ③ settles it before the freeze
 * (#7022, rows 16 to 23, and #7020 F14): the names a client and its contract
 * declare, the types a caller sends, the state paths as Kotlin's, and what a
 * value this generator does not know leaves out.
 */

import { afterAll, describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import { generateCold, removeDirectories } from '../support/generation';
import { manifest, schema } from '../support/manifestProbes';
import { generateCompiling } from '../support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const EMPTY = { 'a.Empty': { type: 'object', properties: {} } };

/** A command of probe.thing sent by id, its body `key`. */
const command = (name: string, key = 'a.Empty', extra = {}) => ({
  name,
  schema: schema(key),
  routes: [{ method: 'POST', path: `/probe/thing/{id}/${name.toLowerCase()}` }],
  ...extra,
});

describe('the generated clients and contract', () => {
  it('send through `commands` and hold the hops in the contract, as Kotlin does', async () => {
    const { read } = await generateCompiling(
      manifest({ commands: [command('Start')] }, EMPTY, {
        subscriptions: [{ name: 'ThingSaga.onStarted', kind: 'saga' }],
      }),
      `import { WaitPlan } from '@ahoo-wang/wow-client';
       import { ProbeContract, ThingCommandClient } from './out/index.js';
       const things = new ThingCommandClient();
       void things.commands.wait;
       void things.start('t1', {}, {
         wait: WaitPlan.of(ProbeContract.Wait.committed(), ProbeContract.Wait.handled('ThingSaga.onStarted')),
       });`,
    );
    expect(read('probe/thing/commandClient.ts')).toContain(
      'readonly commands: CommandClient;',
    );
    const contract = read('probe/contract.ts');
    expect(contract).toContain('  Wait: {\n');
    expect(contract).toContain(
      'committed: (): Hop => Hop.committed(ProbeContract.NAME),',
    );
    expect(contract).not.toMatch(/export const probeWait\b/);
    // The descriptor states its id source always, and no path.
    const aggregate = read('probe/thing/aggregate.ts');
    expect(aggregate).toContain("idSource: 'assigned',");
    expect(aggregate).not.toContain('path:');
  });

  it('take no member `then`, which would make a client a thenable', async () => {
    const { read } = await generateCompiling(
      manifest({ commands: [command('Then')] }, EMPTY, {
        routes: [
          {
            name: 'then',
            route: { method: 'GET', path: '/probe/then' },
            parameters: [],
          },
        ],
      }),
      `import { ProbeRoutes, ThingCommandClient } from './out/index.js';
       void new ThingCommandClient().thenCommand('t1');
       void new ProbeRoutes().thenRoute();`,
    );
    expect(read('probe/thing/commandClient.ts')).not.toMatch(/^ {2}then\(/m);
  });

  it('name a service route as a command is named', async () => {
    const { read } = await generateCompiling(
      manifest(
        {},
        {},
        {
          routes: [
            {
              name: 'export-orders',
              route: { method: 'GET', path: '/probe/orders/export' },
              parameters: [],
            },
          ],
        },
      ),
      `import { ProbeRoutes } from './out/index.js';
       void new ProbeRoutes().exportOrders();`,
    );
    expect(read('probe/routes.ts')).not.toContain("'export-orders'(");
  });

  it('type a command body of no properties as one that takes none', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [command('Clear')],
          events: [{ name: 'Cleared', schema: schema('a.Cleared') }],
        },
        { ...EMPTY, 'a.Cleared': { type: 'object' } },
      ),
      `import { ThingCommandClient } from './out/index.js';
       const things = new ThingCommandClient();
       void things.clear('t1', {});
       // @ts-expect-error The server refuses a member the body does not declare.
       void things.clear('t1', { extra: 1 });`,
    );
    const types = read('probe/types.ts');
    expect(types).toContain('export type Empty = Record<string, never>;');
    // An event, which a newer service may add to, stays open.
    expect(types).toContain('export type Cleared = Record<string, unknown>;');
  });

  it('send a lifecycle body as wow-client types it, whatever $defs holds', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            {
              name: 'wow.lifecycle.TransferOwner',
              schema: schema('wow.lifecycle.TransferOwner'),
              system: true,
              routes: [
                { method: 'POST', path: '/probe/thing/{id}/transfer-owner' },
              ],
            },
          ],
        },
        {
          'wow.lifecycle.TransferOwner': {
            type: 'object',
            properties: { ownerId: { type: 'string' } },
            required: ['ownerId'],
          },
        },
      ),
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain('body: TransferOwner');
    expect(client).toMatch(
      /import \{[^}]*\bTransferOwner\b[^}]*\} from '@ahoo-wang\/wow-client'/,
    );
    expect(read('probe/types.ts')).not.toContain('TransferOwner');
  });

  it("key a command's exact numbers by its full name, as an event's are", async () => {
    const { read } = await generateCompiling(
      manifest(
        { commands: [command('Pay', 'a.Pay')] },
        {
          'a.Pay': {
            type: 'object',
            properties: { amount: { type: 'number', format: 'decimal' } },
          },
        },
      ),
    );
    expect(read('probe/thing/aggregate.ts')).toContain(
      "'probe.thing.Pay': { amount: true },",
    );
    expect(read('probe/thing/commandClient.ts')).toContain(
      "ThingAggregate.textNumbers.commands['probe.thing.Pay']",
    );
  });
});

describe('the state paths', () => {
  it('are those of the Kotlin contract: a name a query path takes, a constant once', async () => {
    const { read } = await generateCompiling(
      manifest(
        { state: { schema: schema('a.Paths') } },
        {
          'a.Paths': {
            type: 'object',
            properties: {
              'first-name': { type: 'string' },
              first_name: { type: 'string' },
              'a.b': { type: 'string' },
              '1st': { type: 'string' },
              nested: {
                type: 'object',
                properties: {
                  deep: {
                    type: 'object',
                    properties: {
                      deeper: {
                        type: 'object',
                        properties: {
                          deepest: {
                            type: 'object',
                            properties: { leaf: { type: 'string' } },
                          },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      ),
    );
    const field = /export type ThingStateField = ([^;]*);/.exec(
      read('probe/thing/aggregate.ts'),
    )![1];
    const paths = field.split(' | ').map(path => path.slice(1, -1));
    expect(paths).toEqual(
      expect.arrayContaining([
        'state.first-name',
        'state.nested.deep.deeper.deepest.leaf',
      ]),
    );
    // `first_name` is `STATE__FIRST_NAME` too: the first keeps it.
    expect(paths).not.toContain('state.first_name');
    expect(paths).not.toContain('state.a.b');
    expect(paths).not.toContain('state.1st');
  });
});

describe('a value this generator does not know', () => {
  it('leaves out what it belongs to, with a warning, and never guesses it', async () => {
    const document = manifest(
      {
        commands: [
          command('Start'),
          command('Later', 'a.Empty', { creation: 'deferred' }),
        ],
      },
      EMPTY,
      {
        routes: [
          {
            name: 'byHeader',
            route: { method: 'GET', path: '/probe/by-header' },
            parameters: [
              { name: 'X-Key', in: 'header', schema: { type: 'string' } },
            ],
          },
          {
            name: 'byQuery',
            route: { method: 'GET', path: '/probe/by-query' },
            parameters: [
              { name: 'key', in: 'query', schema: { type: 'string' } },
            ],
          },
        ],
      },
    );
    const context = document.contexts[0] as { aggregates: unknown[] };
    context.aggregates.push(
      {
        name: 'other',
        state: { schema: schema('a.ThingState') },
        idSource: 'derived',
      },
      {
        name: 'shared',
        state: { schema: schema('a.ThingState') },
        ownership: 'space',
      },
      {
        name: 'tagged',
        state: { schema: schema('a.ThingState') },
        access: [{ name: 'level', type: 'decimal' }],
      },
    );
    const result = await generateCold(document, directories);
    expect(result.exitCode).toBe(EXIT_CODES.success);
    const tail =
      'is left out: a value of it is unknown to this generator; generate with a newer @ahoo-wang/wow-generator.';
    expect(result.logger.warnings).toEqual([
      `The command probe.thing.Later ${tail}`,
      `The aggregate probe.other ${tail}`,
      `The aggregate probe.shared ${tail}`,
      `The aggregate probe.tagged ${tail}`,
      `The service route probe.byHeader ${tail}`,
    ]);
    const { readdirSync, readFileSync } = await import('node:fs');
    const { join } = await import('node:path');
    expect(readdirSync(join(result.output, 'probe')).sort()).not.toEqual(
      expect.arrayContaining(['other', 'shared', 'tagged']),
    );
    const client = readFileSync(
      join(result.output, 'probe/thing/commandClient.ts'),
      'utf8',
    );
    expect(client).toContain('start(');
    expect(client).not.toContain('later(');
    const routes = readFileSync(join(result.output, 'probe/routes.ts'), 'utf8');
    expect(routes).toContain('byQuery(');
    expect(routes).not.toContain('byHeader(');
  });
});
