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
 * Probes of the command clients a Manifest generates: a small Manifest
 * through the CLI into a cold directory, type-checked, for the owners, ids
 * and lifecycle commands the eShop does not have.
 */

import { describe, expect, it } from 'vitest';
import { generateCompiling } from '../support/probes';
import { lifecycle, manifest, schema } from '../support/manifestProbes';

describe('Manifest clients: commands', () => {
  it("sends an owner-id aggregate's commands and reads its state by id and by no id, the caller's own", async () => {
    const both = (segment: string) => [
      { method: 'POST', path: `/probe/thing/{id}/${segment}` },
      { method: 'POST', path: `/probe/thing/${segment}` },
    ];
    const { read } = await generateCompiling(
      manifest(
        {
          ownership: 'owner',
          idSource: 'owner',
          commands: [
            {
              name: 'Put',
              schema: schema('a.Put'),
              creation: 'allowed',
              routes: both('put'),
            },
            { name: 'Clear', schema: schema('a.Clear'), routes: both('clear') },
            {
              name: 'Fetch',
              schema: schema('a.Clear'),
              mounted: true,
              routes: both('fetch'),
            },
            {
              name: 'wow.lifecycle.DeleteAggregate',
              schema: schema('wow.lifecycle.DeleteAggregate'),
              system: true,
              routes: [
                { method: 'DELETE', path: '/probe/thing/{id}' },
                { method: 'DELETE', path: '/probe/thing' },
              ],
            },
            ...lifecycle('wow.lifecycle.RestoreAggregate'),
          ],
        },
        {
          'a.Put': {
            type: 'object',
            required: ['n'],
            properties: { n: { type: 'integer' } },
          },
          'a.Clear': { type: 'object' },
        },
      ),
      `import { ThingAggregate, ThingCommandClient, ThingStateClient } from './out/index.js';
       const commands = new ThingCommandClient();
       const states = new ThingStateClient();
       const source: 'owner' = ThingAggregate.idSource;
       void [
         commands.putOwn({ n: 1 }), commands.put('u1', { n: 1 }), commands.putOwnStream({ n: 1 }),
         commands.clearOwn(), commands.clear('u1'), commands.clearStream('u1'), commands.clearOwnStream(),
         commands.fetch(), commands.fetch({}, { id: 'u1' }),
         commands.deleteAggregateOwn(), commands.deleteAggregate('u1'), commands.restoreAggregate('u1'),
         states.getOwn(), states.findOwn(), states.readOwn(), states.traceOwn(), states.get('u1'), source,
       ];`,
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain(
      'clearOwn(body: Clear = {}, options?: CommandOptions): Promise<CommandResult> {',
    );
    expect(client).toContain(
      "return this.client.wait({ ...options, command: 'probe.thing.Clear', body });",
    );
    expect(client).toContain(
      "return this.client.wait({ ...options, command: 'probe.thing.Clear', body, id });",
    );
    expect(client).toContain(
      "`Clear`, to the caller's own aggregate, whose id is the caller's owner: `POST /probe/thing/clear`.",
    );
    expect(client).toContain(
      "By id, for a caller with no owner; `clearOwn` sends it to the caller's own.",
    );
    expect(client).toContain(
      'put(id: string, body: Put, options?: CommandOptions)',
    );
    // A mounted command's route by no id is the transformer's, not the caller's own.
    expect(client).not.toContain('fetchOwn');
    expect(client).toContain(
      'fetch(body: Clear = {}, options?: CreateCommandOptions)',
    );
    expect(client).not.toContain('restoreAggregateOwn');
    expect(read('probe/thing/queryClient.ts')).toContain(
      'export class ThingStateClient extends OwnStateClient<ThingState, ThingEvent, ThingAccess> {',
    );
    expect(read('probe/thing/aggregate.ts')).toContain("idSource: 'owner',");
  });
  it('sends a command whose body names its aggregate by no id alone, and takes no computed id', async () => {
    const route = (segment: string) => [
      { method: 'POST', path: `/probe/thing/${segment}` },
    ];
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            {
              name: 'Restock',
              schema: schema('a.Restock'),
              creation: 'allowed',
              routes: route('restock'),
              idProperty: 'thingId',
            },
            {
              name: 'Count',
              schema: schema('a.Count'),
              routes: route('count'),
              idProperty: 'thingId',
            },
            {
              name: 'Audit',
              schema: schema('a.Audit'),
              routes: route('audit'),
              idProperty: 'thingId',
            },
            // A schema outside $defs, and one that lists no properties: the id is none the service computes.
            {
              name: 'Tag',
              schema: '#/components/schemas/Tag',
              routes: route('tag'),
              idProperty: 'thingId',
            },
            {
              name: 'Mark',
              schema: schema('a.Mark'),
              routes: route('mark'),
              idProperty: 'thingId',
            },
          ],
        },
        {
          'a.Restock': {
            type: 'object',
            required: ['thingId', 'n'],
            properties: {
              thingId: { type: 'string' },
              n: { type: 'integer' },
            },
          },
          'a.Count': {
            type: 'object',
            required: ['sku', 'thingId'],
            properties: {
              sku: { type: 'string' },
              thingId: { type: 'string', readOnly: true },
            },
          },
          'a.Audit': {
            type: 'object',
            required: ['thingId'],
            properties: { thingId: { type: 'string', readOnly: true } },
          },
          'a.Mark': { type: 'object' },
        },
      ),
      `import { ThingCommandClient } from './out/index.js';
       const commands = new ThingCommandClient();
       void [
         commands.restock({ thingId: 't1', n: 1 }), commands.restockStream({ thingId: 't1', n: 1 }),
         commands.count({ sku: 's' }), commands.countStream({ sku: 's' }), commands.audit(),
         commands.tag({ thingId: 't1' }), commands.tag(), commands.mark({ thingId: 't1' }),
       ];
       // @ts-expect-error the body names the aggregate, and the method takes no id
       void commands.restock('t1', { thingId: 't1', n: 1 });
       // @ts-expect-error nor in its options
       void commands.restock({ thingId: 't1', n: 1 }, { id: 't1' });`,
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain(
      'restock(body: Restock, options?: CommandOptions): Promise<CommandResult> {',
    );
    expect(client).toContain(
      "return this.client.wait({ ...options, command: 'probe.thing.Restock', body });",
    );
    expect(client).toContain('Its body names its aggregate: `thingId`.');
    expect(client).toContain(
      "count(body: Omit<Count, 'thingId'>, options?: CommandOptions)",
    );
    expect(client).toContain(
      'Its body names its aggregate: `thingId`, which the service computes from the fields given.',
    );
    // A body that requires nothing but its computed id may be left out.
    expect(client).toContain(
      "audit(body: Omit<Audit, 'thingId'> = {}, options?: CommandOptions)",
    );
    expect(client).toContain(
      'tag(body: object = {}, options?: CommandOptions)',
    );
    expect(client).toContain('mark(body: Mark = {}, options?: CommandOptions)');
    expect(client).not.toContain("Omit<Mark, 'thingId'>");
    expect(read('probe/manifest.ts')).toContain(
      "{ name: 'Restock', routes: [{ method: 'POST', path: '/probe/thing/restock' }], idProperty: 'thingId' },",
    );
  });
  it('names a command after a lifecycle one apart from it', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            ...lifecycle(
              'wow.lifecycle.DeleteAggregate',
              'wow.lifecycle.TransferOwner',
              'wow.lifecycle.Unknown',
            ),
            {
              name: 'DeleteAggregate',
              schema: schema('a.Delete'),
              creation: 'allowed',
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
    // An aggregate's errors are its own, by what follows its name; the others are the context's.
    expect(read('probe/thing/aggregate.ts')).toContain(
      "GONE: 'probe.thing.gone',",
    );
    const context = read('probe/contract.ts');
    expect(context).toContain("OTHER: 'other',");
    expect(context).not.toContain("probe.thing.gone',\n  },");
    expect(context).toContain("export type ProbeSubscription = 'P.f';");
  });
  it('types lifecycle bodies from $defs and documents what the Manifest describes', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            ...lifecycle(
              'wow.lifecycle.TransferOwner',
              'wow.lifecycle.DeleteAggregate',
              'wow.lifecycle.TransferSpace',
            ),
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
          'wow.lifecycle.TransferOwner': {
            type: 'object',
            properties: { ownerId: { type: 'string' } },
            required: ['ownerId'],
            description: 'Moves it to its new owner',
          },
          'wow.lifecycle.DeleteAggregate': {
            type: 'object',
            description: 'Marks it deleted',
          },
        },
      ),
      `import { ThingCommandClient } from './out/index.js';
       import type { TransferOwner } from './out/probe/types.js';
       const body: TransferOwner = { ownerId: 'o' };
       void [new ThingCommandClient().transferOwner('1', body)];`,
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain(
      "import type { Rename, TransferOwner } from '../types.js';",
    );
    expect(client).toMatch(
      /import \{[^}]*type TransferSpace[^}]*\} from '@ahoo-wang\/wow-client'/,
    );
    expect(client).not.toMatch(
      /type TransferOwner[^}]*\} from '@ahoo-wang\/wow-client'/,
    );
    expect(client).toContain('   * Renames the thing\n   * `Rename`:');
    expect(client).toContain(
      '   * Moves it to its new owner\n   * The lifecycle command `wow.lifecycle.TransferOwner`.',
    );
    expect(client).toContain(
      '   * Marks it deleted\n   * The lifecycle command `wow.lifecycle.DeleteAggregate`.',
    );
    expect(client).toContain('deleteAggregate(id: string, options?');
    expect(client).toContain(
      '/** The lifecycle command `wow.lifecycle.TransferSpace`. */',
    );
    const types = read('probe/types.ts');
    expect(types).toContain('export interface TransferOwner {');
    expect(types).not.toContain('DeleteAggregate');
  });
});
