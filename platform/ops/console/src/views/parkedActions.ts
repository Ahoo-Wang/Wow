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

import {
  actions,
  type ActionInput,
  type RecordActions,
  type RecordRow,
} from "@ahoo-wang/wow-view-engine";
import type { ApplyRetrySpec } from "@/generated/index.ts";
import { PARKED_WORDS } from "./parked.ts";
import type { ParkedCommands } from "./parkedCommands.ts";
import { problemText } from "./parkedCommands.ts";
import { textKeys, type Words } from "./words.ts";

/** The words of the actions, per language; a `-one` form where English counts one apart. */
export const PARKED_ACTION_WORDS = {
  en: {
    retry: "Retry",
    retryConfirm: "Retry {count} entries?",
    "retryConfirm-one": "Retry {count} entry?",
    retryBody:
      "The owning service handles each parked aggregate again from where it is parked.",
    skip: "Skip",
    skipConfirm: "Skip {count} entries?",
    "skipConfirm-one": "Skip {count} entry?",
    skipBody:
      "The owning service resolves each entry without handling what was parked. This cannot be undone.",
    reason: "Reason",
    markRecoverable: "Mark recoverability",
    markAs: "Mark as",
    markAsValue: "Mark as {value}",
    markConfirm: "Mark {count} entries as {value}?",
    "markConfirm-one": "Mark {count} entry as {value}?",
    markUnrecoverableBody:
      "The owning service stops retrying an unrecoverable entry automatically.",
    markBody: "This changes whether the owning service retries it.",
    applyRetrySpec: "Apply retry spec",
    applyConfirm: "Apply the retry spec to {count} entries?",
    "applyConfirm-one": "Apply the retry spec to {count} entry?",
    applyBody:
      "A value left blank keeps the one the entry is retried by now. Durations read PT3M or 3m.",
    maxRetries: "Max retries",
    initialBackoff: "Initial backoff",
    claimTtl: "Claim TTL",
    resolved: "This entry is resolved: nothing is parked to act on.",
    alreadyMarked: "Already marked so.",
  },
  "zh-CN": {
    retry: "重试",
    retryConfirm: "重试 {count} 个条目？",
    "retryConfirm-one": "重试 {count} 个条目？",
    retryBody: "所属服务从搁置处重新处理每个聚合。",
    skip: "跳过",
    skipConfirm: "跳过 {count} 个条目？",
    "skipConfirm-one": "跳过 {count} 个条目？",
    skipBody: "所属服务不处理搁置的内容，直接解决每个条目。此操作无法撤销。",
    reason: "原因",
    markRecoverable: "标记可恢复性",
    markAs: "标记为",
    markAsValue: "标记为{value}",
    markConfirm: "将 {count} 个条目标记为{value}？",
    "markConfirm-one": "将 {count} 个条目标记为{value}？",
    markUnrecoverableBody: "所属服务不再自动重试不可恢复的条目。",
    markBody: "这会改变所属服务是否重试它。",
    applyRetrySpec: "应用重试规格",
    applyConfirm: "对 {count} 个条目应用重试规格？",
    "applyConfirm-one": "对 {count} 个条目应用重试规格？",
    applyBody: "留空的值保持条目当前的重试规格。时长写作 PT3M 或 3m。",
    maxRetries: "最大重试次数",
    initialBackoff: "初始退避",
    claimTtl: "认领租期",
    resolved: "此条目已解决，没有可操作的搁置内容。",
    alreadyMarked: "已经是这个标记。",
  },
} satisfies Words;

const t = textKeys("parkedActions", PARKED_ACTION_WORDS.en);
const field = textKeys("parked", PARKED_WORDS.en);

/** The recoverabilities an operator marks, in the order a menu lists them. */
const RECOVERABILITY = [
  { value: "recoverable", label: field.recoverableYes },
  { value: "unrecoverable", label: field.recoverableNo },
  { value: "unknown", label: field.recoverableUnknown },
];

/** What a row holds of an entry's state for the actions (`record.rowFields`). */
function stateOf(row: RecordRow): { status?: string; recoverable?: string } {
  const state = (row.data.state ?? {}) as Record<string, unknown>;
  return {
    status: typeof state.status === "string" ? state.status : undefined,
    recoverable:
      typeof state.recoverable === "string" ? state.recoverable : undefined,
  };
}

/** A resolved entry takes no action (`ops.parked.resolved`). */
function parked(row: RecordRow): true | string {
  return stateOf(row).status === "resolved" ? t.resolved : true;
}

function idOf(row: RecordRow): string {
  return String(row.key);
}

/**
 * When the parking the row shows was parked (epoch milliseconds, as Wow's
 * JSON writes a long: a string), which a retry or a skip names.
 */
function failedAtOf(row: RecordRow): string | undefined {
  const failedAt = ((row.data.state ?? {}) as Record<string, unknown>)
    .failedAt;
  if (typeof failedAt === "number") return String(failedAt);
  return typeof failedAt === "string" && failedAt !== "" ? failedAt : undefined;
}

/** A typed text, or nothing when it was left blank. */
function textOf(input: ActionInput, name: string): string | undefined {
  const value = input[name];
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}

/** The retry spec the form asks for: what was left blank is left out. */
export function retrySpecOf(input: ActionInput): ApplyRetrySpec {
  const spec: ApplyRetrySpec = {};
  const maxRetries = input.maxRetries;
  if (typeof maxRetries === "number" && Number.isFinite(maxRetries))
    spec.maxRetries = maxRetries;
  else if (typeof maxRetries === "string" && maxRetries.trim() !== "")
    spec.maxRetries = Number(maxRetries);
  const initialBackoff = textOf(input, "initialBackoff");
  if (initialBackoff) spec.initialBackoff = initialBackoff;
  const claimTtl = textOf(input, "claimTtl");
  if (claimTtl) spec.claimTtl = claimTtl;
  return spec;
}

/**
 * The service's refusal as the engine reports it: its problem's words and
 * code rather than the client's wording of the request.
 */
async function said<T>(command: Promise<T>): Promise<T> {
  try {
    return await command;
  } catch (error) {
    throw new Error(problemText(error), { cause: error });
  }
}

/**
 * An operator's actions on a parked entry (D141), as the 9.x dashboard
 * offers them on a failed execution: retry in the row, the rest behind its
 * menu, all four over a selection and in the detail. Each asks first and
 * reports its outcome; the service keeps the last word (an entry resolved
 * meanwhile is refused with `ops.parked.resolved`, a caller without
 * `ops.operator` with `wow.auth.forbidden`).
 */
export function parkedActions(commands: ParkedCommands): RecordActions {
  return actions([
    {
      id: "retry",
      label: t.retry,
      primary: true,
      available: parked,
      form: { reason: { label: t.reason, required: false } },
      confirm: { title: t.retryConfirm, body: t.retryBody },
      run: (row, input) =>
        said(
          commands.retry(idOf(row), textOf(input, "reason"), failedAtOf(row)),
        ),
    },
    {
      id: "skip",
      label: t.skip,
      tone: "danger",
      available: parked,
      form: { reason: { label: t.reason, required: false } },
      confirm: { title: t.skipConfirm, body: t.skipBody },
      run: (row, input) =>
        said(
          commands.skip(idOf(row), textOf(input, "reason"), failedAtOf(row)),
        ),
    },
    {
      id: "markRecoverable",
      label: t.markRecoverable,
      form: { recoverable: { label: t.markAs, options: RECOVERABILITY } },
      available: (row, { input }) => {
        const open = parked(row);
        if (open !== true) return open;
        return input?.recoverable !== undefined &&
          input.recoverable === stateOf(row).recoverable
          ? t.alreadyMarked
          : true;
      },
      confirm: ({ recoverable }) => ({
        title: t.markConfirm,
        action: t.markAsValue,
        ...(recoverable === "unrecoverable"
          ? { body: t.markUnrecoverableBody, tone: "danger" as const }
          : { body: t.markBody }),
      }),
      run: (row, { recoverable }) =>
        said(commands.markRecoverable(idOf(row), String(recoverable))),
    },
    {
      id: "applyRetrySpec",
      label: t.applyRetrySpec,
      available: parked,
      form: {
        maxRetries: { label: t.maxRetries, input: "number", required: false },
        initialBackoff: { label: t.initialBackoff, required: false },
        claimTtl: { label: t.claimTtl, required: false },
      },
      confirm: { title: t.applyConfirm, body: t.applyBody },
      run: (row, input) =>
        said(commands.applyRetrySpec(idOf(row), retrySpecOf(input))),
    },
  ]);
}
