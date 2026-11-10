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
 * A context's service routes, as `emitters/manifest/serviceRoutes.ts` writes
 * them.
 */

import { describe, expect, it } from 'vitest';
import { generateCompiling } from '../../support/probes';

describe('the service routes of a context', () => {
  it('imports nothing for a parameter whose type names no type', async () => {
    const { read } = await generateCompiling({
      format: 1,
      contexts: [
        {
          name: 'probe',
          aggregates: [],
          routes: [
            {
              name: 'page',
              route: { method: 'GET', path: '/probe/pages/{number}' },
              parameters: [
                {
                  name: 'number',
                  in: 'path',
                  schema: { const: 1 },
                  required: true,
                },
              ],
              response: { type: 'string' },
            },
          ],
        },
      ],
      $defs: {},
    });
    const routes = read('probe/routes.ts');
    expect(routes).toContain(
      'page(number: 1, options?: RequestOptions): Promise<string>',
    );
    expect(routes).not.toMatch(/from '\.\/types\.js'/);
  });
});
