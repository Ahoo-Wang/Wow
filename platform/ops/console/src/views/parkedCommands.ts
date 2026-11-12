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
  CommandRejectedError,
  type CommandOptions,
  type Violation,
  WaitPlan,
  WowProblemError,
} from "@ahoo-wang/wow-client";
import { OpsClient, opsWait, type ApplyRetrySpec } from "@/generated/index.ts";

/**
 * An operator's actions on a parked entry (D141): each is recorded on the
 * entry and sent by the ops saga to the owning service, which runs it; what
 * came of it arrives as the service's next record. Each resolves once the
 * entry's snapshot holds the request, so the view read right after shows
 * it (`state.requested`).
 */
export interface ParkedCommands {
  retry(id: string, reason?: string, failedAt?: string): Promise<unknown>;
  skip(id: string, reason?: string, failedAt?: string): Promise<unknown>;
  markRecoverable(
    id: string,
    recoverable: string,
    failedAt?: string,
  ): Promise<unknown>;
  applyRetrySpec(
    id: string,
    spec: ApplyRetrySpec,
    failedAt?: string,
  ): Promise<unknown>;
}

/** Each command waits for the snapshot of the entry (`Wow-Wait`). */
const OPTIONS: CommandOptions = { wait: WaitPlan.of(opsWait.snapshot()) };

/** A blank reason is no reason. */
function reasonOf(reason: string | undefined): { reason?: string } {
  const trimmed = reason?.trim();
  return trimmed ? { reason: trimmed } : {};
}

/**
 * The parking the operator saw (`failedAt`), which every action names: one
 * parked again since refuses it as stale (`ops.parked.stale`,
 * `wow.ledger.stale`).
 */
function parkingOf(failedAt: string | undefined): { failedAt?: string } {
  return failedAt === undefined ? {} : { failedAt };
}

/** The retry or skip of the parking the operator saw. */
function actionOf(
  reason: string | undefined,
  failedAt: string | undefined,
): { reason?: string; failedAt?: string } {
  return { ...reasonOf(reason), ...parkingOf(failedAt) };
}

/** The actions over the ops service's generated client. */
export function parkedCommands(fetcher: Fetcher): ParkedCommands {
  const client = new OpsClient({ fetcher }).parked.commands;
  return {
    retry: (id, reason, failedAt) =>
      client.retry(id, actionOf(reason, failedAt), OPTIONS),
    skip: (id, reason, failedAt) =>
      client.skip(id, actionOf(reason, failedAt), OPTIONS),
    markRecoverable: (id, recoverable, failedAt) =>
      client.markRecoverable(
        id,
        { recoverable, ...parkingOf(failedAt) },
        OPTIONS,
      ),
    applyRetrySpec: (id, spec, failedAt) =>
      client.applyRetrySpec(id, { ...spec, ...parkingOf(failedAt) }, OPTIONS),
  };
}

/**
 * What an operator reads of a failure: the problem's own words and its
 * code (RFC 9457, `application/problem+json`) — `[ops.parked.resolved]
 * … no longer parks …`, `[wow.auth.forbidden] … needs ops.operator.` —
 * else the error's message.
 */
export function problemText(error: unknown): string {
  // A rejection reads as the problem it came in, else as its result's error.
  if (error instanceof CommandRejectedError) {
    if (error.cause instanceof WowProblemError) return problemText(error.cause);
    const words = error.result.error?.message ?? error.message;
    return error.code ? `${words} (${error.code})` : words;
  }
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

/**
 * The refusals of what was sent rather than of the entry: the framework's
 * of a body it cannot read as the command (`wow.command.malformed`) or that
 * breaks one of its constraints (`wow.command.invalid`, each under its
 * member's path) — a retry spec's count and durations among them
 * (`ApplyRetrySpec`'s constraints).
 */
const INPUT_REFUSALS: ReadonlySet<string> = new Set([
  "wow.command.invalid",
  "wow.command.malformed",
]);

/** What a refusal came with: its code and its violations. */
function refusalOf(
  error: unknown,
): { code?: string; violations: readonly Violation[] } | undefined {
  if (error instanceof CommandRejectedError) {
    if (error.cause instanceof WowProblemError) return refusalOf(error.cause);
    const info = error.result.error;
    return {
      ...(info?.code ? { code: info.code } : {}),
      violations: info?.violations ?? [],
    };
  }
  if (error instanceof WowProblemError)
    return {
      ...(error.code ? { code: error.code } : {}),
      violations: error.violations,
    };
  return undefined;
}

/**
 * Whether the service refused what the form sent, and why of each member a
 * violation names by its path — the command's member, which is the form's
 * field; `null` for any other failure, which is the entry's or the
 * request's.
 */
export function refusedInput(error: unknown): Record<string, string> | null {
  const refusal = refusalOf(error);
  if (!refusal?.code || !INPUT_REFUSALS.has(refusal.code)) return null;
  const fields: Record<string, string> = {};
  for (const { path, message } of refusal.violations)
    if (path && !(path in fields)) fields[path] = message;
  return fields;
}
