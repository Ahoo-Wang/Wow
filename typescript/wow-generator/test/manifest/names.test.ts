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

import { describe, expect, it } from 'vitest';
import { analyzeManifest } from '../../src/manifest/analyze';
import type { ManifestDocument } from '../../src/manifest/document';

describe("a service route's parameters", () => {
  it('name a path variable apart from the method parameters only, not from a query parameter listed before it', () => {
    const manifest = {
      format: 1,
      contexts: [
        {
          name: 'shop',
          aggregates: [],
          routes: [
            {
              name: 'item',
              route: { method: 'GET', path: '/shop/items/{id}/{options}' },
              parameters: [
                {
                  name: 'id',
                  in: 'query',
                  schema: { type: 'string' },
                  required: false,
                },
                {
                  name: 'id',
                  in: 'path',
                  schema: { type: 'string' },
                  required: true,
                },
                {
                  name: 'options',
                  in: 'path',
                  schema: { type: 'string' },
                  required: true,
                },
              ],
            },
          ],
        },
      ],
      $defs: {},
    } as unknown as ManifestDocument;
    const route = analyzeManifest(manifest).model.contexts[0]!.routes[0]!;
    expect(
      route.parameters.map(({ name, location, identifier }) => [
        name,
        location,
        identifier,
      ]),
    ).toEqual([
      ['id', 'query', 'id'],
      ['id', 'path', 'id'],
      ['options', 'path', 'options2'],
    ]);
  });
});
