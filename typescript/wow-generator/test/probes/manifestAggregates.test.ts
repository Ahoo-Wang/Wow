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
 * Probes of the aggregates a Manifest's clients describe: a small Manifest
 * through the CLI into a cold directory, type-checked, for the states,
 * access attributes, events and internal commands the eShop does not have.
 */

import { describe, expect, it } from 'vitest';
import { readdirSync } from 'fs';
import { generateCompiling } from '../support/probes';
import { manifest, schema } from '../support/manifestProbes';

describe('Manifest clients: aggregates', () => {
  it('generates an aggregate with nothing but its state', async () => {
    const { read } = await generateCompiling(
      manifest({}, {}, { description: 'Probes' }),
      `import { ThingCommandClient, ThingStateClient, probeWait, ProbeContract, ProbeClient, type ThingEvent } from './out/index.js';
       const events: ThingEvent[] = [];
       const name: 'probe' = ProbeContract.NAME;
       void [new ThingCommandClient(), new ThingStateClient(), probeWait.committed(), ProbeContract.Thing.path, new ProbeClient().thing.state, name, events];`,
    );
    expect(read('probe/thing/aggregate.ts')).toContain(
      'export type ThingEvent = SystemEvent;',
    );
    expect(read('probe/thing/queryClient.ts')).not.toContain(
      'SnapshotQueryClient',
    );
    expect(read('probe/contract.ts')).not.toContain('handled');
    // An aggregate without query models has no query clients in its context's client.
    expect(read('probe/client.ts')).not.toContain('snapshots');
    // Nor access attributes: its records' access is empty, and no query names one.
    expect(read('probe/thing/aggregate.ts')).toContain(
      'export type ThingAccess = NoAccess;',
    );
  });
  it("types an aggregate's access attributes, and reads an integer one as its exact text (D390)", async () => {
    const queries = [
      { model: 'snapshot', routes: [] },
      { model: 'events', routes: [] },
    ];
    const { read } = await generateCompiling(
      manifest({
        queries,
        access: [
          { name: 'region', type: 'string' },
          { name: 'teams', type: 'string', multiple: true },
          { name: 'level', type: 'integer' },
          { name: 'grades', type: 'integer', multiple: true },
          { name: 'open', type: 'boolean' },
          // A type this generator does not know yet takes any scalar.
          { name: 'scores', type: 'decimal', multiple: true },
        ],
      }),
      `import { ThingSnapshotQueryClient, ThingEventQueryClient, ThingStateClient, type ThingAccess } from './out/index.js';
       import { filter } from '@ahoo-wang/wow-client/dsl';
       import type { Snapshot } from '@ahoo-wang/wow-client';
       const access: ThingAccess = { region: null, teams: ['a'], level: '9007199254740993', grades: ['1'], open: true };
       const snapshots = new ThingSnapshotQueryClient();
       const streams = new ThingEventQueryClient();
       const read = async () => (await new ThingStateClient().get('t1')).access.level;
       // A typed record is also an untyped one.
       const untyped = async (): Promise<Snapshot<unknown>> => new ThingStateClient().get('t1');
       void [
         access, read, untyped,
         snapshots.list({ filter: filter.eq('access.region', 'east') }),
         streams.list({ filter: filter.and([filter.eq('access.owner', 'o'), filter.eq('access.level', '1')]) }),
         // @ts-expect-error the thing declares no such attribute
         snapshots.list({ filter: filter.eq('access.unknown', 'x') }),
       ];`,
    );
    const aggregate = read('probe/thing/aggregate.ts');
    expect(aggregate).toContain(
      [
        'export type ThingAccess = {',
        '  readonly region?: string | null;',
        '  readonly teams?: readonly string[];',
        '  readonly level?: string | null;',
        '  readonly grades?: readonly string[];',
        '  readonly open?: boolean | null;',
        '  readonly scores?: readonly (string | number | boolean)[];',
        '};',
      ].join('\n'),
    );
    expect(aggregate).toContain(
      "    access: { level: true, grades: { '*': true } },",
    );
    const clients = read('probe/thing/queryClient.ts');
    expect(clients).toContain(
      'export class ThingSnapshotQueryClient extends SnapshotQueryClient<ThingState, ThingStateField, ThingAccess> {',
    );
    expect(clients).toContain(
      'export class ThingEventQueryClient extends EventQueryClient<ThingEvent, ThingAccess> {',
    );
    expect(clients).toContain(
      'export class ThingStateClient extends StateClient<ThingState, ThingEvent, ThingAccess> {',
    );
  });
  it('leaves an internal command out, and the types only it reaches', async () => {
    const { read, output } = await generateCompiling(
      manifest(
        {
          commands: [
            {
              name: 'Ship',
              schema: schema('a.Ship'),
              routes: [{ method: 'POST', path: '/probe/thing/{id}/ship' }],
            },
            {
              name: 'Settle',
              schema: schema('a.Settle'),
              routes: [{ method: 'POST', path: '/probe/thing/{id}/settle' }],
              internal: true,
            },
          ],
        },
        {
          'a.Ship': {
            type: 'object',
            properties: { carrier: { type: 'string' } },
          },
          'a.Settle': {
            type: 'object',
            properties: { receipt: { $ref: schema('a.Receipt') } },
          },
          'a.Receipt': {
            type: 'object',
            properties: { n: { type: 'integer' } },
          },
        },
      ),
      `import { ThingCommandClient } from './out/index.js';
       const commands = new ThingCommandClient();
       void commands.ship('t1', { carrier: 'c' });
       // @ts-expect-error an internal command has no method
       void commands.settle('t1', {});`,
    );
    const generated = readdirSync(output, { recursive: true, encoding: 'utf8' })
      .filter(file => file.endsWith('.ts'))
      .map(read)
      .join('\n');
    expect(read('probe/thing/commandClient.ts')).toContain('ship(id: string');
    expect(generated).not.toContain('Settle');
    expect(generated).not.toContain('Receipt');
    expect(generated).not.toContain('/probe/thing/{id}/settle');
  });
  it('names each event by the type the server sends, qualified by its context and aggregate', async () => {
    // The server names an event `context.aggregate.EventName` in a state
    // trace and an event stream (08-data-protocols §2; edge/http's
    // HistoryRoutesTest reads `shop.cart.ItemAdded` from a trace), while the
    // Manifest declares it inside its aggregate by the short name. A name the
    // Manifest already qualifies is kept as it is, as the server's
    // ManifestCheck does. The union adds the system events every aggregate
    // has (wow-client's SystemEvent).
    const { read } = await generateCompiling(
      manifest(
        {
          events: [
            { name: 'Shaped', schema: schema('b.Shape') },
            {
              name: 'probe.thing.Reshaped',
              revision: 2,
              schema: schema('b.Shape'),
            },
          ],
        },
        {
          'b.Shape': { type: 'object', properties: { s: { type: 'string' } } },
        },
      ),
      `import type { ThingEvent } from './out/index.js';
       const traced: ThingEvent = { name: 'probe.thing.Shaped', revision: 1, body: { s: 'x' } };
       const reshaped: ThingEvent = { name: 'probe.thing.Reshaped', revision: 2, body: {} };
       // @ts-expect-error the server never sends the short name
       const short: ThingEvent = { name: 'Shaped', revision: 1, body: {} };
       // The kernel's lifecycle and schedule events come in the same trace.
       const owner: ThingEvent = { name: 'wow.lifecycle.OwnerTransferred', revision: 1, body: { ownerId: 'bob' } };
       function body(event: ThingEvent): string | undefined {
         if (event.name === 'wow.ScheduleCancelled') return event.body.key;
         return event.name === 'probe.thing.Shaped' ? event.body.s : undefined;
       }
       void [traced, reshaped, short, owner, body];`,
    );
    expect(read('probe/thing/aggregate.ts')).toContain(
      "export type ThingEvent = CommittedEvent<'probe.thing.Shaped', Shape> | CommittedEvent<'probe.thing.Reshaped', Shape> | SystemEvent;",
    );
  });
});
