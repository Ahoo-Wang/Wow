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
 * The names a Manifest's own names give in the generated client: as the
 * Kotlin contract writes them (#7059), apart from each other or refused as
 * the specification's (#7056), usable however a name is spelled (#7058),
 * and never code where a comment holds them (#7075); and the facts an
 * aggregate's descriptor carries, as the Kotlin contract's KDoc says
 * (#7060).
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import {
  generateCold,
  PACKAGE_ROOT,
  removeDirectories,
} from '../support/generation';
import { manifest, schema } from '../support/manifestProbes';
import { generateCompiling } from '../support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

/** The state every probe aggregate here has. */
const STATE = { schema: schema('a.ThingState') };

/** A Manifest of the contexts given, each `{ name, aggregates }`, over one state type. */
function contexts(
  ...entries: { name: string; aggregates: Record<string, unknown>[] }[]
) {
  return {
    format: 1,
    contexts: entries,
    $defs: {
      'a.ThingState': {
        type: 'object',
        properties: { n: { type: 'integer' } },
      },
    },
  };
}

describe('names a Manifest gives apart', () => {
  it('refuses two aggregates whose names give one type name (order-item, order_item) as the specification, naming both', async () => {
    const result = await generateCold(
      contexts({
        name: 'probe',
        aggregates: [
          { name: 'order-item', state: STATE },
          { name: 'order_item', state: STATE },
        ],
      }),
      directories,
    );
    expect(result.exitCode).toBe(EXIT_CODES.specification);
    expect(result.errors.join('\n')).toContain(
      'In probe, the aggregate order-item and the aggregate order_item both generate OrderItemAccess',
    );
    expect(result.errors.join('\n')).not.toContain('wow-generator bug');
  });

  it("refuses a context's declaration that is an aggregate's (context order-command, aggregate order: OrderCommandClient) as the specification", async () => {
    for (const [context, name] of [
      ['order-command', 'OrderCommandClient'],
      ['shop-state', 'ShopStateClient'],
    ]) {
      const result = await generateCold(
        contexts({
          name: context,
          aggregates: [{ name: context.split('-')[0], state: STATE }],
        }),
        directories,
      );
      expect(result.exitCode, context).toBe(EXIT_CODES.specification);
      expect(result.errors.join('\n'), context).toContain(
        `both generate ${name}`,
      );
    }
  });

  it('gives two recursive types whose names differ only in case (ABTree, Abtree) marks of their own', async () => {
    const tree = (number: Record<string, unknown>, self: string) => ({
      type: 'object',
      properties: {
        amount: number,
        children: { type: 'array', items: { $ref: schema(self) } },
      },
    });
    const { read } = await generateCompiling(
      manifest(
        {},
        {
          'a.ThingState': {
            type: 'object',
            properties: {
              x: { $ref: schema('a.ABTree') },
              y: { $ref: schema('a.Abtree') },
            },
          },
          'a.ABTree': tree({ type: 'number', format: 'decimal' }, 'a.ABTree'),
          'a.Abtree': tree({ type: 'integer', format: 'int64' }, 'a.Abtree'),
        },
      ),
    );
    const marks = read('probe/textNumbers.ts');
    expect(marks).toContain('export const aBTreeNumbers: TextNumbers');
    expect(marks).toContain('export const abtreeNumbers: TextNumbers');
  });
});

describe('names however a Manifest spells them', () => {
  it("assigns an aggregate whose member is no identifier (2fa) by its quoted name, and checks the output's syntax before writing it", async () => {
    const { read } = await generateCompiling(
      contexts({
        name: 'probe',
        aggregates: [
          { name: '2fa', state: STATE },
          { name: 'café', state: STATE },
        ],
      }),
    );
    const client = read('probe/client.ts');
    expect(client).not.toContain("this.'2fa'");
    expect(client).toContain("this['2fa'] = {");
    expect(client).toMatch(/this(\.café|\['café'\]) = \{/);
  });
});

describe('names the Kotlin contract shares', () => {
  it("names an aggregate's member of the contract and its classes as the Kotlin contract names its object, acronyms kept (orderID is OrderID, HTTPLog is HTTPLog)", async () => {
    const { read } = await generateCompiling(
      contexts({
        name: 'probe',
        aggregates: [
          { name: 'orderID', state: STATE },
          { name: 'HTTPLog', state: STATE },
        ],
      }),
    );
    const contract = read('probe/contract.ts');
    expect(contract).toContain('as the Kotlin `ProbeContract` names them');
    expect(contract).toMatch(/\n {2}OrderID: \w+,/);
    expect(contract).toMatch(/\n {2}HTTPLog: \w+,/);
    // Every class follows the type name.
    const client = read('probe/client.ts');
    expect(client).toContain('OrderIDCommandClient');
    expect(client).toContain('HTTPLogCommandClient');
  });

  it('keeps the words of a command after an acronym apart in its method (CreateURLShortcut is createURLShortcut)', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            {
              name: 'CreateURLShortcut',
              schema: schema('a.CreateURLShortcut'),
              routes: [
                {
                  method: 'POST',
                  path: '/probe/thing/{id}/create-url-shortcut',
                },
              ],
            },
          ],
        },
        {
          'a.CreateURLShortcut': {
            type: 'object',
            properties: { n: { type: 'integer' } },
          },
        },
      ),
    );
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain('body: CreateURLShortcut');
    expect(client).toMatch(/\n {2}createURLShortcut\(/);
    expect(client).toMatch(/\n {2}createURLShortcutStream\(/);
  });
});

describe('a Manifest name in a comment of the generated client', () => {
  it('never ends the comment it is written into: a $defs key holding */ stays text', async () => {
    // #7075: every description goes through escapeJsDoc; the comment of a
    // recursive type's exact numbers wrote the key as it is.
    const key = 'shop.Tree*/ export const injected = 1; /*';
    const ref = { $ref: `#/$defs/${key}` };
    const result = await generateCold(
      {
        format: 1,
        contexts: [
          {
            name: 'shop',
            aggregates: [{ name: 'tree', state: { schema: `#/$defs/${key}` } }],
          },
        ],
        $defs: {
          [key]: {
            type: 'object',
            required: ['amount', 'children'],
            properties: {
              amount: { type: 'integer', format: 'int64' },
              children: { type: 'array', items: ref },
            },
          },
        },
      },
      directories,
    );
    expect(result.errors).toEqual([]);
    const text = readFileSync(
      join(result.output, 'shop/textNumbers.ts'),
      'utf8',
    );
    expect(text).not.toContain('*/ export const injected = 1;');
    expect(text).toContain('shop.Tree*\\/ export const injected = 1; /*');
  });
});

describe("an aggregate's descriptor", () => {
  it("carries the facts the Kotlin contract's KDoc says it carries, idSource always", async () => {
    const kdoc = readFileSync(
      join(
        PACKAGE_ROOT,
        '../../compiler/manifest-tools/src/main/kotlin/me/ahoo/wow/manifest/tools/ContractSources.kt',
      ),
      'utf8',
    ).replace(/\n\s*\*\s*/g, ' ');
    const said =
      /`<Aggregate>Aggregate` constant carries .*? in camel case \(([^)]*)\)/.exec(
        kdoc,
      );
    expect(said, 'the KDoc names the facts it carries').not.toBeNull();
    const facts = [...said![1].matchAll(/`(\w+)`/g)].map(match => match[1]);
    expect(facts).toEqual([
      'context',
      'name',
      'ownership',
      'idSource',
      'snapshot',
    ]);
    const { read } = await generateCompiling(manifest({}));
    const descriptor = read('probe/thing/aggregate.ts');
    // An assigned aggregate, whose idSource was left out before.
    expect(descriptor).toContain("  idSource: 'assigned',");
    for (const fact of facts)
      expect(descriptor, fact).toMatch(new RegExp(`\\n {2}${fact}: `));
  });
});
