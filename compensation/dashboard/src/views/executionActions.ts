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
import {
  actions,
  type RecordActions,
  type RecordRow,
} from "@ahoo-wang/wow-view-engine";
import {
  getCompensationCapabilities,
  type CompensationCapabilities,
  type OperableState,
} from "@/features/Failed/compensationCapabilities.ts";
import type { ExecutionCommands } from "@/features/Executions/executionCommands.ts";
import { EXECUTION_FAILED_WORDS } from "./executionFailed.ts";
import { textKeys } from "./textKeys.ts";

/**
 * The words of the compensation commands, per language, under the keys the
 * declarations write (`text(key)`); a `-one` form where English counts one
 * apart.
 */
export const EXECUTION_ACTION_WORDS = {
  en: {
    prepare: "Prepare",
    prepareConfirm: "Prepare {count} executions?",
    "prepareConfirm-one": "Prepare {count} execution?",
    prepareBody: "Each one is prepared within its retry spec.",
    forcePrepare: "Force prepare",
    forcePrepareConfirm: "Force prepare {count} executions?",
    "forcePrepareConfirm-one": "Force prepare {count} execution?",
    forcePrepareBody:
      "This bypasses the retry limit. The server still validates each execution's state.",
    markRecoverability: "Mark recoverability",
    markAs: "Mark as",
    markAsValue: "Mark as {value}",
    markConfirm: "Mark {count} executions as {value}?",
    "markConfirm-one": "Mark {count} execution as {value}?",
    markUnrecoverableBody:
      "The scheduler stops retrying unrecoverable executions.",
    markBody: "This changes whether the scheduler retries them.",
    succeeded: "This execution has already succeeded.",
    inProgress: "Execution is in progress; wait until it times out.",
    retryLimit: "Retry limit reached; force prepare remains available.",
    alreadyMarked: "Already marked so.",
  },
  "zh-CN": {
    prepare: "准备",
    prepareConfirm: "准备 {count} 条执行记录？",
    "prepareConfirm-one": "准备 {count} 条执行记录？",
    prepareBody: "每条都在其重试规格内准备。",
    forcePrepare: "强制准备",
    forcePrepareConfirm: "强制准备 {count} 条执行记录？",
    "forcePrepareConfirm-one": "强制准备 {count} 条执行记录？",
    forcePrepareBody: "这会绕过重试上限，服务端仍会校验每条执行记录的状态。",
    markRecoverability: "标记可恢复性",
    markAs: "标记为",
    markAsValue: "标记为{value}",
    markConfirm: "将 {count} 条执行记录标记为{value}？",
    "markConfirm-one": "将 {count} 条执行记录标记为{value}？",
    markUnrecoverableBody: "调度器不再重试不可恢复的执行记录。",
    markBody: "这会改变调度器是否重试它们。",
    succeeded: "此执行记录已成功。",
    inProgress: "执行尚未超时，请等待当前执行结果。",
    retryLimit: "已达到重试上限；仍可使用强制准备。",
    alreadyMarked: "已经是这个标记。",
  },
};

const t = textKeys("executionActions", EXECUTION_ACTION_WORDS.en);
const field = textKeys("executionFailed", EXECUTION_FAILED_WORDS.en);

/** The recoverabilities an operator marks, in the order a menu lists them. */
export const RECOVERABILITY = [
  { value: RecoverableType.RECOVERABLE, label: field.recoverableYes },
  { value: RecoverableType.UNRECOVERABLE, label: field.recoverableNo },
  { value: RecoverableType.UNKNOWN, label: field.recoverableUnknown },
];

/** Why an execution does not take a command, by the rule's own reason. */
const REASONS: Partial<
  Record<NonNullable<CompensationCapabilities["unavailableReason"]>, string>
> = {
  "This execution has already succeeded.": t.succeeded,
  "Execution is in progress; wait until it times out.": t.inProgress,
  "Retry limit reached; force prepare remains available.": t.retryLimit,
};

interface RowState extends OperableState {
  recoverable?: RecoverableType;
}

/**
 * What a workbench row holds of an execution's state: the fields the
 * definition fetches on every page for these commands (`record.rowFields`),
 * whichever columns the open view shows.
 */
function stateOf(row: RecordRow): RowState {
  const state = (row.data.state ?? {}) as Partial<RowState>;
  return {
    status: state.status as RowState["status"],
    isBelowRetryThreshold: state.isBelowRetryThreshold === true,
    retryState: { timeoutAt: Number(state.retryState?.timeoutAt) },
    recoverable: state.recoverable,
  };
}

/** `true` where the rule allows it, else the rule's reason. */
function allowed(
  row: RecordRow,
  now: number,
  which: "canPrepare" | "canForcePrepare",
): true | string {
  const capabilities = getCompensationCapabilities(stateOf(row), now);
  const reason = capabilities.unavailableReason;
  return capabilities[which] || !reason ? true : (REASONS[reason] ?? reason);
}

/**
 * When a row's capabilities next change on their own: a prepared execution
 * that has not timed out becomes preparable the millisecond after its
 * `timeoutAt` (`now > timeoutAt` is the command side's timeout).
 */
function changesAt(row: RecordRow, { now }: { now: number }): number | null {
  const { status, retryState } = stateOf(row);
  return status === "PREPARED" && retryState.timeoutAt >= now
    ? retryState.timeoutAt + 1
    : null;
}

/**
 * The compensation commands on a failed execution (host-integration.md 5):
 * which the old queues' rule lets it take (`getCompensationCapabilities`),
 * and what each asks first. The engine places them — prepare in the row,
 * the rest behind its menu, all three over a selection and in the detail —
 * runs them a few at a time and reads the page again once each execution's
 * snapshot reflects it (`executionCommands` waits for it). The server keeps
 * the last word: an execution the rule passes can still be refused, and the
 * outcome says why in the service's words.
 */
export function executionActions(commands: ExecutionCommands): RecordActions {
  return actions([
    {
      id: "prepare",
      label: t.prepare,
      primary: true,
      available: (row, { now }) => allowed(row, now, "canPrepare"),
      changesAt,
      // One execution prepares at a press; a selection is counted first.
      confirm: { title: t.prepareConfirm, body: t.prepareBody, ask: "bulk" },
      run: (row) => commands.prepare(String(row.key)),
    },
    {
      id: "forcePrepare",
      label: t.forcePrepare,
      tone: "danger",
      available: (row, { now }) => allowed(row, now, "canForcePrepare"),
      changesAt,
      confirm: { title: t.forcePrepareConfirm, body: t.forcePrepareBody },
      run: (row) => commands.forcePrepare(String(row.key)),
    },
    {
      id: "markRecoverable",
      label: t.markRecoverability,
      form: { recoverable: { label: t.markAs, options: RECOVERABILITY } },
      // Its current recoverability is not offered again.
      available: (row, { input }) =>
        input?.recoverable !== undefined &&
        input.recoverable === stateOf(row).recoverable
          ? t.alreadyMarked
          : true,
      confirm: ({ recoverable }) => ({
        title: t.markConfirm,
        action: t.markAsValue,
        ...(recoverable === RecoverableType.UNRECOVERABLE
          ? { body: t.markUnrecoverableBody, tone: "danger" as const }
          : { body: t.markBody }),
      }),
      run: (row, { recoverable }) =>
        commands.markRecoverable(
          String(row.key),
          recoverable as RecoverableType,
        ),
    },
  ]);
}
