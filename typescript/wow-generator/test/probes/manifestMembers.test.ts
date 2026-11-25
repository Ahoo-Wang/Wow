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
 * A name of the Manifest that meets a member the generator declares beside
 * it: each command, aggregate, route and error code gets a member of its own,
 * and the output compiles (#6892, #6893, #6900).
 */

import { describe, expect, it } from 'vitest';
import { manifest, schema } from '../support/manifestProbes';
import { generateCompiling } from '../support/probes';

const EMPTY = { 'a.Empty': { type: 'object', properties: {} } };

/** A command of probe.thing sent by id, its body `key`. */
const command = (name: string, key = 'a.Empty') => ({
  name,
  schema: schema(key),
  routes: [{ method: 'POST', path: `/probe/thing/{id}/${name.toLowerCase()}` }],
});

/** A lifecycle command of probe.thing, as a system command with its route. */
const lifecycle = (name: string) => ({
  name,
  schema: schema(name),
  system: true,
  routes: [
    name === 'wow.lifecycle.DeleteAggregate'
      ? { method: 'DELETE', path: '/probe/thing/{id}' }
      : {
          method: 'POST',
          path: `/probe/thing/{id}/${name.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase()}`,
        },
  ],
});

/** A Manifest of probe whose context declares `errors`. */
const withErrors = (...errors: { code: string; status: number }[]) =>
  manifest({}, {}, { errors });

describe('the members a Manifest names', () => {
  it('gives a command and one named after its stream twin each a method and a twin of their own', async () => {
    const { read } = await generateCompiling(
      manifest({ commands: [command('Start'), command('StartStream')] }, EMPTY),
      `import { ThingCommandClient } from './out/index.js';
       const commands = new ThingCommandClient();
       void [commands.start('t1'), commands.startStream('t1'), commands.startStreamCommand('t1'), commands.startStreamCommandStream('t1')];`,
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain("command: 'probe.thing.Start'");
    expect(client).toContain("command: 'probe.thing.StartStream'");
  });

  it("names a command after the client's own members apart from them: Client and Constructor", async () => {
    const { read } = await generateCompiling(
      manifest(
        { commands: [command('Client'), command('Constructor')] },
        EMPTY,
      ),
      `import { ThingCommandClient } from './out/index.js';
       const commands = new ThingCommandClient();
       void [commands.clientCommand('t1'), commands.constructorCommand('t1'), commands.client.wait];`,
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain("command: 'probe.thing.Client'");
    expect(client).toContain("command: 'probe.thing.Constructor'");
  });

  it('numbers a name its first suffix also meets: DeleteAggregateCommand before DeleteAggregate', async () => {
    await generateCompiling(
      manifest(
        {
          commands: [
            lifecycle('wow.lifecycle.DeleteAggregate'),
            command('DeleteAggregateCommand'),
            command('DeleteAggregate'),
          ],
        },
        EMPTY,
      ),
      `import { ThingCommandClient } from './out/index.js';
       const commands = new ThingCommandClient();
       void [commands.deleteAggregate('t1'), commands.deleteAggregateCommand('t1'), commands.deleteAggregateCommand2('t1')];`,
    );
  });

  it("gives an aggregate named commands or routes a member of its context's client apart from the context's own", async () => {
    const document = manifest(
      {},
      {},
      {
        routes: [
          {
            name: 'ping',
            route: { method: 'GET', path: '/probe/ping' },
            response: { type: 'string' },
          },
        ],
      },
    );
    for (const name of ['commands', 'routes', 'commands-aggregate']) {
      document.contexts[0].aggregates.push({
        name,
        state: { schema: schema('a.ThingState') },
      });
    }
    await generateCompiling(
      document,
      `import { ProbeClient } from './out/index.js';
       const probe = new ProbeClient();
       // An aggregate of the name the suffix gives keeps it; the one named commands is numbered.
       void [probe.commands.wait, probe.routes.ping, probe.commandsAggregate2.commands, probe.routesAggregate.state, probe.commandsAggregate.state];`,
    );
  });

  it("gives a service route named routes a method apart from the routes client's routes field", async () => {
    const { read } = await generateCompiling(
      manifest(
        {},
        {},
        {
          routes: [
            {
              name: 'routes',
              route: { method: 'GET', path: '/probe/routes' },
              response: { type: 'string' },
            },
          ],
        },
      ),
      `import { ProbeRoutes } from './out/index.js';
       const routes = new ProbeRoutes();
       const answer: Promise<string> = routes.routesRoute();
       void [answer, routes.routes.call];`,
    );
    expect(read('probe/routes.ts')).toContain("'probe', 'routes'");
  });

  it('types a domain command named TransferOwner by its own body beside the lifecycle body of wow-client', async () => {
    // $defs holds no wow.lifecycle.TransferOwner: the lifecycle method takes wow-client's body.
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            command('TransferOwner', 'a.TransferOwner'),
            lifecycle('wow.lifecycle.TransferOwner'),
          ],
        },
        {
          'a.TransferOwner': {
            type: 'object',
            required: ['to'],
            properties: { to: { type: 'string' } },
          },
        },
      ),
      `import { ThingCommandClient } from './out/index.js';
       const commands = new ThingCommandClient();
       void commands.transferOwner('t1', { ownerId: 'u2' });
       void commands.transferOwnerCommand('t1', { to: 'u2' });
       // @ts-expect-error The domain command's body is its own.
       void commands.transferOwnerCommand('t1', { ownerId: 'u2' });`,
    );
    expect(read('probe/thing/commandClient.ts')).toContain(
      "command: 'probe.thing.TransferOwner'",
    );
  });
});

describe("the constants of a Manifest's error codes, as the Kotlin contract names them", () => {
  it('writes `_` before a leading digit', async () => {
    const { read } = await generateCompiling(
      withErrors({ code: 'probe.thing.404-not-found', status: 404 }),
    );
    // ContractSources.errorName.
    expect(read('probe/thing/aggregate.ts')).toContain(
      "_404_NOT_FOUND: 'probe.thing.404-not-found',",
    );
  });

  it("splits words as QueryFieldNames.upperSnake does, a dotted name's segments joined by __", async () => {
    const { read } = await generateCompiling(
      withErrors(
        { code: 'probe.thing.invalidURLValue', status: 422 },
        { code: 'probe.thing.item2Count', status: 422 },
        { code: 'probe.thing.out_of-stock', status: 409 },
        { code: 'probe.thing.items.missing', status: 404 },
        { code: 'probe.billing.paymentDeclined', status: 402 },
      ),
    );
    const aggregate = read('probe/thing/aggregate.ts');
    expect(aggregate).toContain(
      "INVALID_URL_VALUE: 'probe.thing.invalidURLValue',",
    );
    expect(aggregate).toContain("ITEM2_COUNT: 'probe.thing.item2Count',");
    expect(aggregate).toContain("OUT_OF_STOCK: 'probe.thing.out_of-stock',");
    // A code with a dot after its aggregate's name is the context's, as aggregateCodeOf reads it.
    expect(aggregate).not.toContain('probe.thing.items.missing');
    const contract = read('probe/contract.ts');
    expect(contract).toContain(
      "THING__ITEMS__MISSING: 'probe.thing.items.missing',",
    );
    expect(contract).toContain(
      "BILLING__PAYMENT_DECLINED: 'probe.billing.paymentDeclined',",
    );
  });

  it('names a code with nothing after its aggregate ERROR', async () => {
    const { read } = await generateCompiling(
      withErrors({ code: 'probe.thing.', status: 409 }),
    );
    expect(read('probe/thing/aggregate.ts')).toContain(
      "ERROR: 'probe.thing.',",
    );
  });

  it('keeps the first of two codes whose constants coincide, as distinctBy does, and both codes in the error code type', async () => {
    const { read } = await generateCompiling(
      withErrors(
        { code: 'probe.thing.wrong-amount', status: 409 },
        { code: 'probe.thing.wrong_amount', status: 422 },
      ),
      `import { ThingAggregate, type ProbeErrorCode } from './out/index.js';
       const code: 'probe.thing.wrong-amount' = ThingAggregate.Errors.WRONG_AMOUNT;
       const other: ProbeErrorCode = 'probe.thing.wrong_amount';
       void [code, other];`,
    );
    expect(read('probe/thing/aggregate.ts')).not.toContain(
      "'probe.thing.wrong_amount',",
    );
  });

  it("keeps an aggregate named errors apart from the context contract's Errors", async () => {
    const document = withErrors({ code: 'probe.closed', status: 409 });
    document.contexts[0].aggregates.push({
      name: 'errors',
      state: { schema: schema('a.ThingState') },
    });
    await generateCompiling(
      document,
      `import { ProbeContract } from './out/index.js';
       const closed: 'probe.closed' = ProbeContract.Errors.CLOSED;
       const name: 'errors' = ProbeContract.ErrorsAggregate.name;
       void [closed, name, ProbeContract.Thing.name];`,
    );
  });
});
