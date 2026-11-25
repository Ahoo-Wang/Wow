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
 * The routing part a generated client carries (`manifest.ts`), and the
 * context's command client that sends by it: it routes and writes a command
 * as a `CommandClient` over the whole Manifest does (#6897, #6898), a former
 * name listed as a full type name too (#7054).
 */

import { afterAll, describe, expect, it } from 'vitest';
import { removeDirectories, runGenerated } from '../support/generation';
import { manifest, schema } from '../support/manifestProbes';
import { generateCompiling } from '../support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const DECIMAL = '12345678901234567890.25';

/** A command of probe.thing sent by id, its body `a.Pay`, and its former names. */
const pay = (...aliases: string[]) => ({
  name: 'Pay',
  aliases,
  schema: schema('a.Pay'),
  routes: [{ method: 'POST', path: '/probe/thing/{id}/pay' }],
});

/** A system command of probe.thing, by no id. */
const system = (name: string, internal = false) => ({
  name,
  schema: schema('a.Empty'),
  system: true,
  ...(internal ? { internal: true } : {}),
  routes: [{ method: 'POST', path: `/probe/thing/${name}` }],
});

const DEFS = {
  'a.Pay': {
    type: 'object',
    required: ['amount'],
    properties: { amount: { type: 'number', format: 'decimal' } },
  },
  'a.Empty': { type: 'object', properties: {} },
};

/** Sends each of `requests` through the context client's `commands`, and prints the path and body sent, or the error. */
const sending = (
  requests: string,
) => `const fetcher = new Fetcher({ baseURL: 'http://probe', headers: {} });
const { ProbeClient } = await import('./probe/index.js');
const probe = new ProbeClient({ fetcher });
const sent = [];
for (const request of ${requests}) {
  try {
    await probe.commands.wait(request);
    const last = requests.at(-1);
    sent.push([new URL(last.url).pathname, last.body]);
  } catch (error) {
    sent.push(String(error));
  }
}
console.log(JSON.stringify(sent));`;

describe("the context client's command client", () => {
  it("writes a command's exact numbers unquoted, by its name and by a former one, as the aggregate's does", async () => {
    const generation = await generateCompiling(
      manifest(
        {
          commands: [
            pay('Settle'),
            // Routed by DELETE alone, which sends no body: it has no marks to carry.
            {
              name: 'Adjust',
              schema: schema('a.Pay'),
              routes: [{ method: 'DELETE', path: '/probe/thing/{id}/adjust' }],
            },
          ],
        },
        DEFS,
      ),
      `import { ProbeClient, type Pay } from './out/index.js';
       declare const probe: ProbeClient;
       const body: Pay = { amount: '1.50' };
       void probe.commands.wait({ command: 'probe.thing.Pay', id: 't1', body });`,
    );
    directories.push(generation.dir);
    const printed = runGenerated(
      generation.output,
      `${sending(`[
  { command: 'probe.thing.Pay', id: 't1', body: { amount: '${DECIMAL}' } },
  { command: 'probe.thing.Settle', id: 't1', body: { amount: '${DECIMAL}' } },
  { command: 'probe.thing.Adjust', id: 't1', body: { amount: '${DECIMAL}' } },
]`)}
await probe.thing.commands.pay('t1', { amount: '${DECIMAL}' });
console.log(JSON.stringify(requests.at(-1).body));`,
    );
    const [sent, aggregate] = printed.trim().split('\n');
    const body = `{"amount":${DECIMAL}}`;
    expect(JSON.parse(sent)).toEqual([
      ['/probe/thing/t1/pay', body],
      ['/probe/thing/t1/pay', body],
      ['/probe/thing/t1/adjust', null],
    ]);
    expect(generation.read('probe/client.ts')).not.toContain(
      "'probe.thing.Adjust'",
    );
    expect(JSON.parse(aggregate)).toBe(body);
  });

  it('routes and marks a command by a former name the Manifest lists as a full type name, as the Kotlin client does', async () => {
    // The compiler writes `@Alias("probe.thing.Settle")` as written, and the
    // Kotlin client matches it (`ManifestPeers.declared`,
    // `command.type in it.aliases`).
    const generation = await generateCompiling(
      manifest({ commands: [pay('probe.thing.Settle')] }, DEFS),
    );
    directories.push(generation.dir);
    const printed = runGenerated(
      generation.output,
      sending(
        `[{ command: 'probe.thing.Settle', id: 't1', body: { amount: '${DECIMAL}' } }]`,
      ),
    );
    expect(JSON.parse(printed)).toEqual([
      ['/probe/thing/t1/pay', `{"amount":${DECIMAL}}`],
    ]);
    expect(generation.read('probe/client.ts')).toContain(
      "'probe.thing.Settle': ThingAggregate.textNumbers.commands['probe.thing.Pay'],",
    );
  });

  it("routes a command by a former name, every system command its aggregate lists, and its context's own, but no internal one", async () => {
    const document = manifest(
      {
        commands: [
          pay('Settle'),
          system('wow.lifecycle.DeleteAggregate'),
          system('wow.snapshot.RebuildSnapshot'),
          system('wow.probe.Sweep', true),
        ],
      },
      DEFS,
      {
        commands: [
          {
            name: 'wow.schedule.EnableJob',
            schema: schema('a.Empty'),
            system: true,
            routes: [
              {
                method: 'POST',
                path: '/probe/wow.schedule/{id}/enable-job',
              },
            ],
          },
        ],
      },
    );
    const generation = await generateCompiling(document);
    directories.push(generation.dir);
    const type = "type: { context: 'probe', name: 'thing' }";
    const printed = runGenerated(
      generation.output,
      sending(`[
  { command: 'probe.thing.Settle', id: 't1', body: { amount: '1' } },
  { command: 'wow.lifecycle.DeleteAggregate', ${type} },
  { command: 'wow.snapshot.RebuildSnapshot', ${type} },
  { command: 'wow.schedule.EnableJob', id: 'nightly', type: { context: 'probe', name: 'wow.schedule' } },
  { command: 'wow.probe.Sweep', ${type} },
]`),
    );
    expect(JSON.parse(printed)).toEqual([
      ['/probe/thing/t1/pay', '{"amount":1}'],
      ['/probe/thing/wow.lifecycle.DeleteAggregate', '{}'],
      ['/probe/thing/wow.snapshot.RebuildSnapshot', '{}'],
      ['/probe/wow.schedule/nightly/enable-job', '{}'],
      'TypeError: The Manifest lists no command wow.probe.Sweep of probe.thing.',
    ]);
    expect(generation.read('probe/manifest.ts')).toContain(
      "{ name: 'Pay', aliases: ['Settle'], routes:",
    );
  });
});
