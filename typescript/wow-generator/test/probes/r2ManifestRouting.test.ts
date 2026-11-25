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
 * Round two of the deep review, on #6951: a former name the Manifest lists as
 * a full type name. The compiler writes `@Alias("probe.thing.Settle")` into the
 * Manifest as written, and the kernel, the Manifest check and the Kotlin
 * client (`ManifestPeers.declared`, `command.type in it.aliases`) take it as
 * the former name `Settle`. The routing part and the context client's command
 * client should route and mark a command sent by it as they do a former name
 * written alone.
 */

import { afterAll, describe, expect, it } from 'vitest';
import { removeDirectories, runGenerated } from '../support/generation';
import { manifest, schema } from '../support/manifestProbes';
import { generateCompiling } from '../support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const DECIMAL = '12345678901234567890.25';

const DEFS = {
  'a.Pay': {
    type: 'object',
    required: ['amount'],
    properties: { amount: { type: 'number', format: 'decimal' } },
  },
};

describe("r2 the context client's command client", () => {
  it('routes and marks a command by a former name the Manifest lists as a full type name, as the Kotlin client does', async () => {
    const generation = await generateCompiling(
      manifest(
        {
          commands: [
            {
              name: 'Pay',
              aliases: ['probe.thing.Settle'],
              schema: schema('a.Pay'),
              routes: [{ method: 'POST', path: '/probe/thing/{id}/pay' }],
            },
          ],
        },
        DEFS,
      ),
    );
    directories.push(generation.dir);
    const printed = runGenerated(
      generation.output,
      `const fetcher = new Fetcher({ baseURL: 'http://probe', headers: {} });
const { ProbeClient } = await import('./probe/index.js');
const probe = new ProbeClient({ fetcher });
let sent;
try {
  await probe.commands.wait({ command: 'probe.thing.Settle', id: 't1', body: { amount: '${DECIMAL}' } });
  const last = requests.at(-1);
  sent = [new URL(last.url).pathname, last.body];
} catch (error) {
  sent = String(error);
}
console.log(JSON.stringify(sent));`,
    );
    expect(JSON.parse(printed)).toEqual([
      '/probe/thing/t1/pay',
      `{"amount":${DECIMAL}}`,
    ]);
  });
});
