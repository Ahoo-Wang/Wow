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
 * Round 1 of the deep review before the public API freezes: each test holds
 * the behaviour a Manifest's generated clients should have, and fails on main
 * for the defect it names.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import type { ManifestDocument } from '../src/manifest/document';
import { analyzeManifest } from '../src/manifest/analyze';
import { parseContent } from '../src/input/parsers';
import {
  BUNDLER_OPTIONS,
  generateCold,
  PACKAGE_ROOT,
  removeDirectories,
  runGenerated,
  typeCheck,
} from './support/generation';
import { manifest, schema } from './support/manifestProbes';
import { generateCompiling } from './support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const decimal = { type: 'number', format: 'decimal' };
const ref = (key: string) => ({ $ref: schema(key) });

/** A command of probe.thing sent by id, its body `key`. */
const command = (name: string, key = 'a.Empty') => ({
  name,
  schema: schema(key),
  routes: [
    {
      method: 'POST',
      path: `/probe/thing/{id}/${name.toLowerCase()}`,
    },
  ],
});

/** Generates a Manifest through the CLI into a cold directory, and type-checks what it wrote. */
async function generateAndCheck(document: unknown) {
  const result = await generateCold(document, directories);
  return {
    ...result,
    diagnostics:
      result.exitCode === 0 ? typeCheck(result.output, BUNDLER_OPTIONS) : [],
  };
}

describe('r1 review: the clients a Manifest generates', () => {
  it('r1 a command and one named after its stream twin each get a method of their own', async () => {
    const { read } = await generateCompiling(
      manifest(
        { commands: [command('Start'), command('StartStream')] },
        { 'a.Empty': { type: 'object', properties: {} } },
      ),
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain("command: 'probe.thing.Start'");
    expect(client).toContain("command: 'probe.thing.StartStream'");
  });

  it("r1 a command named Client gets a method apart from the command client's client field", async () => {
    const { read } = await generateCompiling(
      manifest(
        { commands: [command('Client')] },
        { 'a.Empty': { type: 'object', properties: {} } },
      ),
    );
    expect(read('probe/thing/commandClient.ts')).toContain(
      "command: 'probe.thing.Client'",
    );
  });

  it("r1 an aggregate named commands gets a member of its context's client apart from the context's command client", async () => {
    const document = manifest();
    document.contexts[0].aggregates.push({
      name: 'commands',
      state: { schema: schema('a.ThingState') },
    });
    const { read } = await generateCompiling(document);
    expect(read('probe/client.ts')).toContain('CommandsCommandClient');
  });

  it("r1 a service route named routes gets a method apart from the routes client's routes field", async () => {
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
    );
    expect(read('probe/routes.ts')).toContain("'probe', 'routes'");
  });

  it('r1 an error code whose local name starts with a digit is a constant, as the Kotlin contract names it', async () => {
    const { read } = await generateCompiling(
      manifest(
        {},
        {},
        { errors: [{ code: 'probe.thing.404-not-found', status: 404 }] },
      ),
    );
    // ContractSources.errorName: `_` before a leading digit.
    expect(read('probe/thing/aggregate.ts')).toContain(
      "_404_NOT_FOUND: 'probe.thing.404-not-found',",
    );
  });

  it("r1 an error code's constant splits its words as the Kotlin contract's constant does", async () => {
    const { read } = await generateCompiling(
      manifest(
        {},
        {},
        {
          errors: [
            { code: 'probe.thing.invalidURLValue', status: 422 },
            { code: 'probe.thing.item2Count', status: 422 },
          ],
        },
      ),
    );
    // QueryFieldNames.upperSnake: `URLValue` is `URL_VALUE`, `2C` is `2_C`.
    const aggregate = read('probe/thing/aggregate.ts');
    expect(aggregate).toContain(
      "INVALID_URL_VALUE: 'probe.thing.invalidURLValue',",
    );
    expect(aggregate).toContain("ITEM2_COUNT: 'probe.thing.item2Count',");
  });

  it('r1 two error codes of one constant name never leave a contract that does not compile', async () => {
    const result = await generateAndCheck(
      manifest(
        {},
        {},
        {
          errors: [
            { code: 'probe.thing.wrong-amount', status: 409 },
            { code: 'probe.thing.wrong_amount', status: 422 },
          ],
        },
      ),
    );
    if (result.exitCode === 0) {
      expect(result.diagnostics).toEqual([]);
    } else {
      // Refused as the Manifest's problem, naming both codes.
      expect(result.exitCode).toBe(4);
      expect(result.errors.join('\n')).toContain('probe.thing.wrong_amount');
    }
  });

  it("r1 an aggregate named errors keeps the context contract's Errors apart", async () => {
    const document = manifest(
      {},
      {},
      { errors: [{ code: 'probe.closed', status: 409 }] },
    );
    document.contexts[0].aggregates.push({
      name: 'errors',
      state: { schema: schema('a.ThingState') },
    });
    const result = await generateAndCheck(document);
    expect(result.exitCode).toBe(0);
    expect(result.diagnostics).toEqual([]);
  });

  it('r1 a sealed command body keeps every subtype and is never defaulted to an empty object', async () => {
    const subtype = (
      kind: string,
      properties: Record<string, unknown>,
      required: string[],
    ) => ({
      allOf: [
        { type: 'object', properties, required },
        {
          type: 'object',
          properties: { kind: { const: kind } },
          required: ['kind'],
        },
      ],
    });
    const { read } = await generateCompiling(
      manifest(
        { commands: [command('Pay', 'a.Pay')] },
        {
          // A sealed class with @JsonTypeInfo, as SchemaIntrospector writes it.
          'a.Pay': {
            anyOf: [
              subtype('card', { amount: decimal, card: { type: 'string' } }, [
                'amount',
                'card',
              ]),
              subtype('cash', { amount: decimal }, ['amount']),
            ],
          },
        },
      ),
      `import { ThingCommandClient } from './out/index.js';
       const commands = new ThingCommandClient();
       void commands.pay('t1', { kind: 'cash', amount: '1.50' });
       // @ts-expect-error A payment of neither kind.
       void commands.pay('t1', {});`,
    );
    expect(read('probe/thing/commandClient.ts')).toContain(
      'pay(id: string, body: Pay, options?: CommandOptions)',
    );
  });

  it('r1 a recursive parameterized type is written in place without recursing forever', async () => {
    const tree = 'a.Node(java.lang.String)';
    const { read } = await generateCompiling(
      manifest(
        {},
        {
          'a.ThingState': {
            type: 'object',
            required: ['tree'],
            properties: { tree: ref(tree) },
          },
          // Node<String>, referred to twice (the state and its children): a definition of its own.
          [tree]: {
            type: 'object',
            required: ['value', 'children'],
            properties: {
              value: { type: 'string' },
              children: { type: 'array', items: ref(tree) },
            },
          },
        },
      ),
    );
    expect(read('probe/types.ts')).toContain('tree:');
  });

  it("r1 an aggregate's state fields name the paths within its arrays, as the Kotlin contract does", () => {
    const ordering = JSON.parse(
      readFileSync(
        join(PACKAGE_ROOT, 'test', 'manifests', 'eshop-ordering.json'),
        'utf8',
      ),
    ) as ManifestDocument;
    const order = analyzeManifest(ordering).model.contexts[0].aggregates[0];
    // EshopOrderingContract.Order.STATE__ITEMS__PRODUCT_ID, …__QUANTITY, …__UNIT_PRICE.
    expect(order.stateFields).toEqual(
      expect.arrayContaining([
        'state.items',
        'state.items.productId',
        'state.items.quantity',
        'state.items.unitPrice',
      ]),
    );
  });

  it("r1 an aggregate's state fields name the paths within a nullable object, as the Kotlin contract does", () => {
    const thing = analyzeManifest(
      manifest(
        {},
        {
          'a.ThingState': {
            type: 'object',
            properties: {
              address: { anyOf: [{ type: 'null' }, ref('a.Address')] },
            },
          },
          'a.Address': {
            type: 'object',
            required: ['city'],
            properties: { city: { type: 'string' } },
          },
        },
      ) as unknown as ManifestDocument,
    ).model.contexts[0].aggregates[0];
    // StateFields.of reads a nullable alternative as the value it holds.
    expect(thing.stateFields).toEqual(['state.address', 'state.address.city']);
  });

  it("r1 the context client's command client writes a command's exact numbers unquoted, as the aggregate's does", async () => {
    const generation = await generateCompiling(
      manifest(
        { commands: [command('Pay', 'a.Pay')] },
        {
          'a.Pay': {
            type: 'object',
            required: ['amount'],
            properties: { amount: decimal },
          },
        },
      ),
      `import { ProbeClient, type Pay } from './out/index.js';
       declare const probe: ProbeClient;
       const body: Pay = { amount: '1.50' };
       void probe.commands.wait({ command: 'probe.thing.Pay', id: 't1', body });`,
    );
    directories.push(generation.dir);
    const printed = runGenerated(
      generation.output,
      `const fetcher = new Fetcher({ baseURL: 'http://probe', headers: {} });
const { ProbeClient } = await import('./probe/index.js');
const probe = new ProbeClient({ fetcher });
await probe.thing.commands.pay('t1', { amount: '12345678901234567890.25' });
await probe.commands.wait({ command: 'probe.thing.Pay', id: 't1', body: { amount: '12345678901234567890.25' } });
console.log(JSON.stringify(requests.map(request => request.body)));`,
    );
    expect(JSON.parse(printed)).toEqual([
      '{"amount":12345678901234567890.25}',
      '{"amount":12345678901234567890.25}',
    ]);
  });

  it("r1 the context client's command client sends what the Manifest's routing does: a command by a former name, and a system command the aggregate lists", async () => {
    const generation = await generateCompiling(
      manifest(
        {
          commands: [
            { ...command('Pay', 'a.Empty'), aliases: ['Settle'] },
            {
              name: 'wow.snapshot.RebuildSnapshot',
              schema: schema('wow.snapshot.RebuildSnapshot'),
              system: true,
              routes: [
                {
                  method: 'POST',
                  path: '/probe/thing/wow.snapshot.rebuild-snapshot',
                },
              ],
            },
          ],
        },
        { 'a.Empty': { type: 'object', properties: {} } },
      ),
    );
    directories.push(generation.dir);
    const printed = runGenerated(
      generation.output,
      `const fetcher = new Fetcher({ baseURL: 'http://probe', headers: {} });
const { ProbeClient } = await import('./probe/index.js');
const probe = new ProbeClient({ fetcher });
const sent = async request => {
  try {
    await probe.commands.wait(request);
    return new URL(requests.at(-1).url).pathname;
  } catch (error) {
    return String(error);
  }
};
console.log(JSON.stringify([
  await sent({ command: 'probe.thing.Settle', id: 't1' }),
  await sent({ command: 'wow.snapshot.RebuildSnapshot', type: { context: 'probe', name: 'thing' } }),
]));`,
    );
    // As a CommandClient given the whole Manifest sends them.
    expect(JSON.parse(printed)).toEqual([
      '/probe/thing/t1/pay',
      '/probe/thing/wow.snapshot.rebuild-snapshot',
    ]);
  });

  it("r1 a service route parameter typed by a $defs entry is declared and typed by it, as the route's request is", async () => {
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
                  schema: ref('a.Status'),
                  required: false,
                },
              ],
              response: { type: 'string' },
            },
          ],
        },
      ),
    );
    expect(logger.warnings).toEqual([]);
    expect(read('probe/routes.ts')).toContain(
      'query?: { readonly status?: Status; }',
    );
  });

  it("r1 a domain command named TransferOwner keeps its own body type beside the lifecycle body of wow-client's", async () => {
    // A Manifest whose $defs holds no wow.lifecycle.TransferOwner: the lifecycle method takes wow-client's body.
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            command('TransferOwner', 'a.TransferOwner'),
            {
              name: 'wow.lifecycle.TransferOwner',
              schema: schema('wow.lifecycle.TransferOwner'),
              system: true,
              routes: [
                {
                  method: 'POST',
                  path: '/probe/thing/{id}/wow.lifecycle.transfer-owner',
                },
              ],
            },
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
       void commands.transferOwnerCommand('t1', { to: 'u2' });`,
    );
    expect(read('probe/thing/commandClient.ts')).toContain(
      "command: 'probe.thing.TransferOwner'",
    );
  });

  it('r1 a Manifest of a malformed shape is refused as a specification error, not as a defect of the generator', async () => {
    const aggregates = manifest();
    const listed = aggregates.contexts[0] as Record<string, unknown>;
    listed.aggregates = { thing: aggregates.contexts[0].aggregates[0] };
    const routeless = manifest({}, {}, { routes: [{ name: 'ping' }] });
    for (const document of [aggregates, routeless]) {
      const result = await generateCold(document, directories);
      // Not "Code generation failed: … report it": the run's defect message.
      expect(result.errors.join('\n')).not.toContain('Code generation failed');
      expect(result.exitCode).toBe(4);
    }
  });

  it('r1 an exact number whose property is named __proto__ keeps its mark on the aggregate', async () => {
    const generation = await generateCompiling(
      manifest(
        {},
        {
          'a.ThingState': {
            type: 'object',
            required: ['__proto__'],
            // A computed key: a plain `__proto__:` would set the prototype.
            properties: { ['__proto__']: decimal },
          },
        },
      ),
    );
    directories.push(generation.dir);
    // The generated type says string: the client reads it as its text.
    expect(generation.read('probe/types.ts')).toContain('__proto__: string;');
    const printed = runGenerated(
      generation.output,
      `const { ThingAggregate } = await import('./probe/thing/aggregate.js');
console.log(JSON.stringify(Object.getOwnPropertyNames(ThingAggregate.textNumbers?.state ?? {})));`,
    );
    expect(JSON.parse(printed)).toEqual(['__proto__']);
  });
});

describe('r1 review: reading the input', () => {
  it('r1 a Manifest saved with a UTF-8 byte order mark is read', () => {
    const document = manifest();
    expect(parseContent(`﻿${JSON.stringify(document)}`)).toEqual(document);
  });
});
