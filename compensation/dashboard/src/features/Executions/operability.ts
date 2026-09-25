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

import { RecoverableType } from "@ahoo-wang/wow-client";
import type { RecordKey, RecordRow } from "@ahoo-wang/wow-view-engine";
import type { BulkRun } from "@ahoo-wang/wow-view-engine/react";
import {
  getCompensationCapabilities,
  type CompensationCapabilities,
  type OperableState,
} from "@/features/Failed/compensationCapabilities.ts";
import type { Message, Translate } from "@/i18n.tsx";
import type { ExecutionCommands } from "./executionCommands.ts";

/** One command the workbench sends, as a run and a confirmation name it. */
export type ExecutionCommand =
  | { kind: "prepare" }
  | { kind: "forcePrepare" }
  | { kind: "markRecoverable"; recoverable: RecoverableType };

/** The recoverabilities an operator marks, in the order a menu lists them. */
export const RECOVERABILITY: readonly [RecoverableType, Message][] = [
  [RecoverableType.RECOVERABLE, "Recoverable"],
  [RecoverableType.UNRECOVERABLE, "Unrecoverable"],
  [RecoverableType.UNKNOWN, "Unknown"],
];

export function recoverabilityLabel(
  value: RecoverableType | undefined,
): Message {
  return RECOVERABILITY.find(([each]) => each === value)?.[1] ?? "Unknown";
}

/**
 * What a workbench row holds of an execution's state: the fields the
 * definition fetches on every page for these commands (`record.rowFields`),
 * whichever columns the open view shows.
 */
interface RowState extends OperableState {
  recoverable?: RecoverableType;
}

function stateOf(row: RecordRow): RowState {
  const state = (row.data.state ?? {}) as Partial<RowState>;
  return {
    status: state.status as RowState["status"],
    isBelowRetryThreshold: state.isBelowRetryThreshold === true,
    retryState: { timeoutAt: Number(state.retryState?.timeoutAt) },
    recoverable: state.recoverable,
  };
}

/** Which commands the execution in a row takes now, and why not. */
export function rowCapabilities(
  row: RecordRow,
  now: number,
): CompensationCapabilities {
  return getCompensationCapabilities(stateOf(row), now);
}

/** The recoverability the execution in a row already has. */
export function rowRecoverable(row: RecordRow): RecoverableType | undefined {
  return stateOf(row).recoverable;
}

/**
 * When a row's capabilities next change on their own: a prepared execution
 * that has not timed out becomes preparable the millisecond after its
 * `timeoutAt` (`now > timeoutAt` is the command side's timeout). `null` when
 * nothing changes without a new state.
 */
export function capabilitiesChangeAt(
  row: RecordRow,
  now: number,
): number | null {
  const { status, retryState } = stateOf(row);
  return status === "PREPARED" && retryState.timeoutAt >= now
    ? retryState.timeoutAt + 1
    : null;
}

/**
 * Why the execution in a row does not take a command now, or `undefined`
 * when it may. The server keeps the last word: a row that passes here can
 * still be refused, and the outcome says so. Marking recoverability is left
 * to the server alone, as the old queues leave it.
 */
export function refusalOf(
  command: ExecutionCommand,
  row: RecordRow | undefined,
  now: number,
): Message | undefined {
  if (!row || command.kind === "markRecoverable") return undefined;
  const capabilities = rowCapabilities(row, now);
  const allowed =
    command.kind === "prepare"
      ? capabilities.canPrepare
      : capabilities.canForcePrepare;
  return allowed ? undefined : capabilities.unavailableReason;
}

/**
 * The reasons some of the picked rows will not be sent, and how many rows
 * give each, most common first — what a confirmation says before the run.
 */
export function refusedReasons(
  command: ExecutionCommand,
  rows: readonly RecordRow[],
  keys: readonly RecordKey[],
  now: number,
): { reason: Message; count: number }[] {
  const picked = new Set(keys);
  const counts = new Map<Message, number>();
  for (const row of rows) {
    if (!picked.has(row.key)) continue;
    const reason = refusalOf(command, row, now);
    if (reason) counts.set(reason, (counts.get(reason) ?? 0) + 1);
  }
  return [...counts]
    .map(([reason, count]) => ({ reason, count }))
    .sort((left, right) => right.count - left.count);
}

/** What the outcome line calls a command. */
export function titleOf(command: ExecutionCommand, t: Translate): string {
  if (command.kind === "prepare") return t("Prepare");
  if (command.kind === "forcePrepare") return t("Force prepare");
  return t("Mark as {value}", {
    value: t(recoverabilityLabel(command.recoverable)),
  });
}

/**
 * One command over some executions, for `useBulkCommand`: each is checked
 * against its row first, so an execution the console already knows cannot
 * take it is reported with the reason, and left selected, without a
 * request; the rest go to the service, whose refusals the outcome reads.
 */
export function bulkRun(
  command: ExecutionCommand,
  rows: readonly RecordRow[],
  commands: ExecutionCommands,
  t: Translate,
): BulkRun {
  const byKey = new Map(rows.map((row) => [row.key, row]));
  return {
    title: titleOf(command, t),
    async each(key) {
      const refusal = refusalOf(command, byKey.get(key), Date.now());
      if (refusal) throw new Error(t(refusal));
      const id = String(key);
      if (command.kind === "markRecoverable")
        await commands.markRecoverable(id, command.recoverable);
      else await commands[command.kind](id);
    },
  };
}
