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

/*
 * The example server, and the standalone view store server the
 * `WowViewStore` suites use beside it (test/view-store/), are ready before the first test starts, so no test
 * pays for a cold server and none needs a budget of its own for it.
 *
 * Ready is two things, in this order:
 *
 * 1. `/actuator/health` answers `UP`. A test started before that fails on a
 *    refused connection, or on a context still being built.
 * 2. Each aggregate the suite commands has taken one command through to its
 *    snapshot. Healthy is not warm: the first command to an aggregate pays
 *    for its processor, its event store and snapshot collections and the
 *    code paths the JIT has not seen yet — about 20 times the cost of the
 *    second on an in-memory server, and more on MongoDB. Paid here, once,
 *    it no longer lands on whichever test happens to send that first
 *    command under its own 5 s timeout.
 *
 * Each wait has a deadline, and a server that misses it fails the run with
 * what it last answered, before any test runs.
 */

import { CommandHeaders, CommandStage } from '@ahoo-wang/wow-client';
import { exampleServerURL } from '../src/wow/exampleFetcher';
import {
  viewStoreServerURL,
  viewStoreServers,
} from './view-store/viewStoreServer';

/** How long a server may take to come up: the CI job's own wait. */
const HEALTH_DEADLINE_MS = 5 * 60_000;
const HEALTH_POLL_MS = 1_000;
/** How long one warm-up command may take to reach its snapshot. */
const COMMAND_DEADLINE_MS = 2 * 60_000;

function url(path: string, server = exampleServerURL): string {
  return new URL(path, server).toString();
}

async function healthy(
  name: string,
  server: string,
  start: string,
): Promise<void> {
  const deadline = Date.now() + HEALTH_DEADLINE_MS;
  let last = 'no answer yet';
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url('actuator/health', server), {
        signal: AbortSignal.timeout(HEALTH_POLL_MS * 5),
      });
      const body = (await response.json()) as { status?: string };
      if (response.ok && body.status === 'UP') return;
      last = `HTTP ${response.status}, status ${body.status}`;
    } catch (error) {
      last = String(error);
    }
    await new Promise(resolve => setTimeout(resolve, HEALTH_POLL_MS));
  }
  throw new Error(
    `The ${name} at ${server} was not UP after ${
      HEALTH_DEADLINE_MS / 1000
    } s (last: ${last}). Start it first: typescript/integration-test/README.md${start}.`,
  );
}

/** One command, waited for until its snapshot is written. */
async function command(
  name: string,
  path: string,
  body: unknown,
  headers: Record<string, string> = {},
  { server = exampleServerURL, method = 'POST' } = {},
): Promise<void> {
  const response = await fetch(url(path, server), {
    method,
    headers: {
      'Content-Type': 'application/json',
      [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
      ...headers,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(COMMAND_DEADLINE_MS),
  });
  if (!response.ok)
    throw new Error(
      `Warming up ${name} answered HTTP ${
        response.status
      }: ${await response.text()}`,
    );
}

export default async function setup(): Promise<void> {
  await Promise.all([
    healthy('example server', exampleServerURL, ''),
    healthy(
      'view store server',
      viewStoreServerURL,
      '#wowviewstore-against-the-view-store-server',
    ),
  ]);
  const run = `warmup-${Date.now()}`;
  // One command per aggregate the suite drives; the ids are this run's own,
  // so nothing a test reads is touched.
  await command('cart', `owner/${run}/cart/add_cart_item`, {
    productId: run,
    quantity: 1,
  });
  await command(
    'order',
    `tenant/${run}/owner/${run}/sales-order`,
    {
      // The example prices every product at 10 and refuses another price.
      items: [{ productId: 'product-1', price: 10, quantity: 1 }],
      address: {
        country: 'China',
        province: 'Shanghai',
        city: 'Shanghai',
        district: 'Pudong',
        detail: 'Road 1',
      },
      fromCart: false,
    },
    { [CommandHeaders.SPACE_ID]: run },
  );
  // The view store's two aggregates on each server that serves them (the
  // example server embeds the starter), in a tenant of this run's own.
  const app = { 'CoSec-App-Id': 'warmup' };
  for (const { name, url: server } of viewStoreServers) {
    await command(
      `view on ${name}`,
      `view-store/tenant/${run}/owner/${run}/view`,
      { definitionId: run, title: run, config: { kind: 'record' } },
      app,
      { server },
    );
    await command(
      `view preferences on ${name}`,
      `view-store/tenant/${run}/owner/${run}/definitions/${run}/preferences`,
      { order: [] },
      { ...app, [CommandHeaders.AGGREGATE_VERSION]: '0' },
      { server, method: 'PUT' },
    );
  }
}
