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
  AggregateManifest,
  CommandManifest,
  ContextManifest,
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
    aliases: [],
    schema: `#/$defs/${name}`,
    creation: unaddressed ? 'allowed' : 'none',
    routes: routes(base, segment, unaddressed),
  };
}

function system(name: string, route: RouteManifest): CommandManifest {
  return {
    name,
    aliases: [],
    schema: `#/$defs/${name}`,
    creation: 'none',
    routes: [route],
    system: true,
  };
}

/** An aggregate of `commands`, as a Manifest writes it: none of the rest. */
export function aggregate(
  name: string,
  commands: readonly CommandManifest[],
): AggregateManifest {
  return {
    name,
    state: { schema: `#/$defs/${name}State` },
    snapshot: { enabled: true },
    commands,
    events: [],
    queries: [],
  };
}

/** A context of `aggregates` and its own `commands`, as a Manifest writes it. */
export function context(
  name: string,
  aggregates: readonly AggregateManifest[],
  commands?: readonly CommandManifest[],
): ContextManifest {
  return {
    name,
    aggregates,
    subscriptions: [],
    published: [],
    errors: [],
    ...(commands === undefined ? {} : { commands }),
  };
}

/** A Manifest of `contexts`. */
export function manifestOf(contexts: readonly ContextManifest[]): Manifest {
  return { format: 1, contexts, errors: [], $defs: {} };
}

const PRODUCT = '/eshop-catalog/product';

/** The Manifest whose routes the tests' gateways send commands by, as the compiler writes them. */
export const TEST_MANIFEST: Manifest = manifestOf([
  context(
    'eshop-catalog',
    [
      aggregate('product', [
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
      ]),
    ],
    [
      system('wow.schedule.Enable', {
        method: 'POST',
        path: '/eshop-catalog/wow.schedule/{id}/enable',
      }),
    ],
  ),
  context('c', [aggregate('a', [command('/c/a', 'X', 'x', true)])]),
  context('shop', [
    aggregate('order', [command('/shop/order', 'Create', 'create', true)]),
  ]),
]);
