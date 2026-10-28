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

import type {
  CommandManifest,
  Manifest,
  RouteManifest,
} from '../../src/index.js';

/** The routes of a command: by id, and without one when it may create its aggregate. */
function routes(
  base: string,
  segment: string,
  unaddressed = false,
): RouteManifest[] {
  return [
    { method: 'POST', path: `${base}/{id}/${segment}` },
    ...(unaddressed ? [{ method: 'POST', path: `${base}/${segment}` }] : []),
  ];
}

function command(
  base: string,
  name: string,
  segment: string,
  unaddressed = false,
): CommandManifest {
  return {
    name,
    schema: `#/$defs/${name}`,
    routes: routes(base, segment, unaddressed),
  };
}

function system(name: string, route: RouteManifest): CommandManifest {
  return { name, schema: `#/$defs/${name}`, routes: [route], system: true };
}

const PRODUCT = '/eshop-catalog/product';

/** The Manifest whose routes the tests' gateways send commands by, as the compiler writes them. */
export const TEST_MANIFEST: Manifest = {
  format: 1,
  contexts: [
    {
      name: 'eshop-catalog',
      aggregates: [
        {
          name: 'product',
          state: { schema: '#/$defs/Product' },
          commands: [
            command(PRODUCT, 'CreateProduct', 'create-product', true),
            command(PRODUCT, 'Rename', 'rename'),
            command(PRODUCT, 'ChangePrice', 'change-price'),
            command(PRODUCT, 'ChangeProductPrice', 'change-product-price'),
            system('wow.lifecycle.DeleteAggregate', {
              method: 'DELETE',
              path: `${PRODUCT}/{id}`,
            }),
            system('wow.lifecycle.ApplyTags', {
              method: 'POST',
              path: `${PRODUCT}/{id}/wow.lifecycle.apply-tags`,
            }),
            system('wow.lifecycle.RestoreAggregate', {
              method: 'POST',
              path: `${PRODUCT}/{id}/wow.lifecycle.restore-aggregate`,
            }),
            system('wow.snapshot.Rebuild', {
              method: 'POST',
              path: `${PRODUCT}/wow.snapshot.rebuild`,
            }),
          ],
        },
      ],
      commands: [
        system('wow.schedule.Enable', {
          method: 'POST',
          path: '/eshop-catalog/wow.schedule/{id}/enable',
        }),
      ],
    },
    {
      name: 'c',
      aggregates: [
        {
          name: 'a',
          state: { schema: '#/$defs/A' },
          commands: [command('/c/a', 'X', 'x', true)],
        },
      ],
    },
    {
      name: 'shop',
      aggregates: [
        {
          name: 'order',
          state: { schema: '#/$defs/Order' },
          commands: [command('/shop/order', 'Create', 'create', true)],
        },
      ],
    },
  ],
};
