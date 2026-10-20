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

import type { Fetcher } from "@ahoo-wang/fetcher";
import {
  WowProblemError,
  type CommandOptions,
} from "@ahoo-wang/wow-client";
import {
  opsWait,
  ParkedCommandClient,
  type ApplyRetrySpec,
} from "@/generated/index.ts";

/**
 * An operator's actions on a parked entry (D141): each is recorded on the
 * entry and sent by the ops saga to the owning service, which runs it; what
 * came of it arrives as the service's next record. Each resolves once the
 * entry's snapshot holds the request, so the view read right after shows
 * it (`state.requested`).
 */
export interface ParkedCommands {
  resume(id: string, reason?: string): Promise<unknown>;
  skip(id: string, reason?: string): Promise<unknown>;
  markRecoverable(id: string, recoverable: string): Promise<unknown>;
  applyRetrySpec(id: string, spec: ApplyRetrySpec): Promise<unknown>;
}

/** Each command waits for the snapshot of the entry (`Wow-Wait`). */
const OPTIONS: CommandOptions = { wait: { plan: [opsWait.snapshot()] } };

/** A blank reason is no reason. */
function reasonOf(reason: string | undefined): { reason?: string } {
  const trimmed = reason?.trim();
  return trimmed ? { reason: trimmed } : {};
}

/** The actions over the ops service's generated command client. */
export function parkedCommands(fetcher: Fetcher): ParkedCommands {
  const client = new ParkedCommandClient({ fetcher });
  return {
    resume: (id, reason) => client.resume(id, reasonOf(reason), OPTIONS),
    skip: (id, reason) => client.skip(id, reasonOf(reason), OPTIONS),
    markRecoverable: (id, recoverable) =>
      client.markRecoverable(id, { recoverable }, OPTIONS),
    applyRetrySpec: (id, spec) => client.applyRetrySpec(id, spec, OPTIONS),
  };
}

/**
 * What an operator reads of a failure: the problem's own words and its
 * code (RFC 9457, `application/problem+json`) — `[ops.parked.resolved]
 * … no longer parks …`, `[wow.auth.forbidden] … needs ops.operator.` —
 * else the error's message.
 */
export function problemText(error: unknown): string {
  if (error instanceof WowProblemError) {
    const { problem } = error;
    const words = problem.detail ?? problem.title ?? `HTTP ${problem.status}`;
    const violations = error.violations
      .map((violation) => violation.message)
      .filter(Boolean);
    const said = [words, ...violations].join(" ");
    return error.code ? `${said} (${error.code})` : said;
  }
  return error instanceof Error ? error.message : String(error);
}
