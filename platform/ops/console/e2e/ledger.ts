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

import { expect, type APIRequestContext } from "@playwright/test";

/*
 * The owning services' ledgers, as the server's ledger simulator publishes
 * them (`LedgerSimulatorRoutes`, in the ops server's test classes): an
 * entry parked, an entry resolved, and the ledger's system commands the ops
 * saga sent back to the owning context.
 */

export const SAGA = "PaymentGatewaySaga.onPaymentRequested";
const HOUR = 3_600_000;
/** A few hours ago: inside the overview's default window, the last 7 days. */
export const FAILED_AT = Date.now() - 6 * HOUR;

/** The failure an entry is parked with: the runtime's `<class>: <message>`. */
export function connectionRefused(aggregateId: string): string {
  return `java.net.ConnectException: The gateway is unreachable for ${aggregateId}.`;
}

export type Status = "SCHEDULED" | "EXHAUSTED" | "UNRECOVERABLE";

export interface Parked {
  /** The entry's ops id (`Ledger.parkedId`). */
  id: string;
  context: string;
  aggregateId: string;
}

function aggregateOf(context: string, id: string) {
  return { context, name: "payment", tenantId: "(0)", id };
}

/** Publishes an entry of `context`'s payment `aggregateId`, parked as `status`. */
export async function publish(
  request: APIRequestContext,
  context: string,
  aggregateId: string,
  status: Status,
  hoursLater = 0,
  reason = connectionRefused(aggregateId),
): Promise<Parked> {
  const failedAt = FAILED_AT + hoursLater * HOUR;
  const answer = await request.post("/testing/ledger/parked", {
    data: {
      service: `${context}-service`,
      context,
      subscription: SAGA,
      aggregate: aggregateOf(context, aggregateId),
      fromVersion: 2,
      reason,
      failedAt,
      retries: 3,
      retrySpec: {
        maxRetries: 3,
        minBackoff: "PT1M",
        executionTimeout: "PT2M",
      },
      status,
      nextRetryAt: status === "SCHEDULED" ? failedAt + HOUR : null,
      recordedAt: failedAt + 1,
    },
  });
  expect(answer.ok()).toBe(true);
  const { id } = (await answer.json()) as { id: string };
  return { id, context, aggregateId };
}

/** Publishes that the owning service resolved `entry` with `outcome`. */
export async function resolve(
  request: APIRequestContext,
  entry: Parked,
  outcome: "RESUMED" | "SKIPPED",
): Promise<void> {
  const answer = await request.post("/testing/ledger/resolved", {
    data: {
      service: `${entry.context}-service`,
      context: entry.context,
      subscription: SAGA,
      aggregate: aggregateOf(entry.context, entry.aggregateId),
      failedAt: FAILED_AT,
      outcome,
      resolvedAt: Date.now(),
    },
  });
  expect(answer.ok()).toBe(true);
}

/** The ledger's system commands the ops saga sent to `context`. */
export async function ledgerCommands(
  request: APIRequestContext,
  context: string,
): Promise<{ type: string; id: string; body: Record<string, unknown> }[]> {
  const answer = await request.get(`/testing/ledger/commands/${context}`);
  expect(answer.ok()).toBe(true);
  return answer.json();
}

/** Waits until the ops service lists `entry` to an operator. */
export async function listed(
  request: APIRequestContext,
  entry: Parked,
): Promise<void> {
  await expect
    .poll(async () => {
      const answer = await request.get(
        `/ops/parked/${encodeURIComponent(entry.id)}/state`,
        { headers: { "X-User": "e2e" } },
      );
      return answer.status();
    })
    .toBe(200);
}
