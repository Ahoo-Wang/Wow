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
 * The example server is ready before the first test starts, so no test pays
 * for a cold server and none needs a budget of its own for it.
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

/** How long a server may take to come up: the CI job's own wait. */
const HEALTH_DEADLINE_MS = 5 * 60_000;
const HEALTH_POLL_MS = 1_000;
/** How long one warm-up command may take to reach its snapshot. */
const COMMAND_DEADLINE_MS = 2 * 60_000;

function url(path: string): string {
  return new URL(path, exampleServerURL).toString();
}

async function healthy(): Promise<void> {
  const deadline = Date.now() + HEALTH_DEADLINE_MS;
  let last = 'no answer yet';
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url('actuator/health'), {
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
    `The example server at ${exampleServerURL} was not UP after ${
      HEALTH_DEADLINE_MS / 1000
    } s (last: ${last}). Start it first: typescript/integration-test/README.md.`,
  );
}

/** One command, waited for until its snapshot is written. */
async function command(
  name: string,
  path: string,
  body: unknown,
  headers: Record<string, string> = {},
): Promise<void> {
  const response = await fetch(url(path), {
    method: 'POST',
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
      `Warming up the example server: ${name} answered HTTP ${
        response.status
      }: ${await response.text()}`,
    );
}

export default async function setup(): Promise<void> {
  await healthy();
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
      items: [{ productId: run, price: 1, quantity: 1 }],
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
}
