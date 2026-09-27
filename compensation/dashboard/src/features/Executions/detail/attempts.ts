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

import type { RecoverableType } from "@ahoo-wang/wow-client";
import type { RecordData } from "@ahoo-wang/wow-view-engine";

/** One line of an execution's story, as its event streams tell it. */
export type Moment =
  /** The failure the execution was recorded for. */
  | { kind: "created"; at: number; errorCode: string }
  /** A retry: prepared, then failed, succeeded, or not yet answered. */
  | {
      kind: "attempt";
      number: number;
      at: number;
      endedAt: number | null;
      outcome: "failed" | "succeeded" | "running";
      errorCode: string;
    }
  | { kind: "functionChanged"; at: number }
  | { kind: "retrySpecApplied"; at: number }
  | { kind: "recoverableMarked"; at: number; recoverable: RecoverableType };

interface StreamEvent {
  name?: string;
  body?: {
    error?: { errorCode?: string };
    recoverable?: RecoverableType;
  };
}

interface Stream {
  version?: number;
  createTime?: number;
  body?: StreamEvent[];
}

function codeOf(event: StreamEvent): string {
  return event.body?.error?.errorCode ?? "";
}

/**
 * An execution's story, the newest first, out of its event streams in any
 * order: each `compensation_prepared` opens an attempt that the next
 * `execution_failed_applied` or `execution_success_applied` closes; the
 * changes an operator made stand between them, where they happened.
 */
export function momentsOf(streams: readonly RecordData[]): Moment[] {
  const ordered = ([...streams] as Stream[]).sort(
    (one, other) => (one.version ?? 0) - (other.version ?? 0),
  );
  const moments: Moment[] = [];
  let open: Extract<Moment, { kind: "attempt" }> | null = null;
  let attempts = 0;
  for (const stream of ordered) {
    const at = stream.createTime ?? 0;
    for (const event of stream.body ?? []) {
      switch (event.name) {
        case "execution_failed_created":
          moments.push({ kind: "created", at, errorCode: codeOf(event) });
          break;
        case "compensation_prepared":
          attempts += 1;
          open = {
            kind: "attempt",
            number: attempts,
            at,
            endedAt: null,
            outcome: "running",
            errorCode: "",
          };
          moments.push(open);
          break;
        case "execution_failed_applied":
        case "execution_success_applied":
          if (open) {
            open.endedAt = at;
            open.outcome =
              event.name === "execution_success_applied"
                ? "succeeded"
                : "failed";
            open.errorCode = codeOf(event);
            open = null;
          }
          break;
        case "function_changed":
          moments.push({ kind: "functionChanged", at });
          break;
        case "retry_spec_applied":
          moments.push({ kind: "retrySpecApplied", at });
          break;
        case "recoverable_marked":
          if (event.body?.recoverable)
            moments.push({
              kind: "recoverableMarked",
              at,
              recoverable: event.body.recoverable,
            });
          break;
      }
    }
  }
  return moments.reverse();
}

/**
 * How many of the latest attempts failed with `errorCode`, one after the
 * other, since the function last changed — the sign that retrying as it is
 * will not help. A change of function starts the count again: the next
 * attempt runs something else.
 */
export function repeatedFailures(
  moments: readonly Moment[],
  errorCode: string,
): number {
  if (errorCode === "") return 0;
  let count = 0;
  for (const moment of moments) {
    if (moment.kind === "functionChanged") break;
    if (moment.kind !== "attempt") continue;
    if (moment.outcome === "running") continue;
    if (moment.outcome !== "failed" || moment.errorCode !== errorCode) break;
    count += 1;
  }
  return count;
}
