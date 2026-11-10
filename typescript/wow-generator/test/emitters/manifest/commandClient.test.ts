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
 * The command client of an aggregate, as `emitters/manifest/commandClient.ts`
 * writes it.
 */

import { describe, expect, it } from 'vitest';
import { generateCompiling } from '../../support/probes';

describe('the command client of an aggregate', () => {
  it('reads the exact numbers of a command whose name is no identifier by its quoted name', async () => {
    const { read } = await generateCompiling({
      format: 1,
      contexts: [
        {
          name: 'probe',
          aggregates: [
            {
              name: 'thing',
              state: { schema: '#/$defs/probe.ThingState' },
              commands: [
                {
                  name: 'set-price',
                  schema: '#/$defs/probe.SetPrice',
                  routes: [{ method: 'POST', path: '/probe/thing/{id}/price' }],
                },
              ],
            },
          ],
        },
      ],
      $defs: {
        'probe.ThingState': { type: 'object', properties: {} },
        'probe.SetPrice': {
          type: 'object',
          required: ['price'],
          properties: { price: { type: 'number', format: 'decimal' } },
        },
      },
    });
    expect(read('probe/thing/commandClient.ts')).toContain(
      "'probe.thing.set-price': ThingAggregate.textNumbers.commands['set-price'],",
    );
  });
});
