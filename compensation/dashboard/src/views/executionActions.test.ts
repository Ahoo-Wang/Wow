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
import { text, type RecordRow } from "@ahoo-wang/wow-view-engine";
import {
  actionHarness,
  ActionRefused,
} from "@ahoo-wang/wow-view-engine/testing";
import { describe, expect, it, vi } from "vitest";
import type { ExecutionCommands } from "@/features/Executions/executionCommands.ts";
import { executionActions } from "./executionActions.ts";

const IN_PROGRESS = text("executionActions.inProgress");
const SUCCEEDED = text("executionActions.succeeded");

/** A row as the workbench hands it over: the row fields, nothing more. */
function row(key: string, state: Record<string, unknown> = {}): RecordRow {
  return {
    key,
    data: {
      state: {
        id: key,
        status: "FAILED",
        isBelowRetryThreshold: true,
        retryState: { timeoutAt: 2_000 },
        recoverable: "RECOVERABLE",
        ...state,
      },
    },
  };
}

function commands(): ExecutionCommands & {
  [K in keyof ExecutionCommands]: ReturnType<typeof vi.fn>;
} {
  return {
    prepare: vi.fn(() => Promise.resolve()),
    forcePrepare: vi.fn(() => Promise.resolve()),
    markRecoverable: vi.fn(() => Promise.resolve()),
    applyRetrySpec: vi.fn(() => Promise.resolve()),
    changeFunction: vi.fn(() => Promise.resolve()),
  };
}

const ROWS = [
  row("EF-1", { status: "PREPARED" }),
  row("EF-2", { status: "PREPARED" }),
  row("EF-3", { status: "SUCCEEDED" }),
  row("EF-4"),
  row("EF-5", { isBelowRetryThreshold: false }),
  { key: "EF-6", data: {} },
];

describe("the compensation commands, declared", () => {
  const harness = actionHarness(executionActions(commands()), ROWS, {
    now: 1_000,
  });

  it("offers all three in rows, over a selection and in the detail, prepare in the row", () => {
    expect(harness.at("row")).toEqual([
      "prepare",
      "forcePrepare",
      "markRecoverable",
    ]);
    expect(harness.at("bulk")).toEqual(harness.at("row"));
    expect(harness.at("detail")).toEqual(harness.at("row"));
  });

  it("reads the row's state with the old queues' rule, and says why not", () => {
    expect(harness.state("prepare", "EF-4").available).toBe(true);
    expect(harness.state("prepare", "EF-1").reason).toBe(IN_PROGRESS);
    expect(harness.state("forcePrepare", "EF-1").reason).toBe(IN_PROGRESS);
    expect(harness.state("prepare", "EF-5").reason).toBe(
      text("executionActions.retryLimit"),
    );
    expect(harness.state("forcePrepare", "EF-5").available).toBe(true);
    // Below the threshold is not known of a bare row: only a forced prepare.
    expect(harness.state("prepare", "EF-6").available).toBe(false);
    expect(harness.state("forcePrepare", "EF-6").available).toBe(true);
  });

  it("offers every recoverability but the one an execution already has", () => {
    const mark = (key: string, recoverable: RecoverableType) =>
      harness.state("markRecoverable", key, { recoverable }).available;
    expect(mark("EF-4", RecoverableType.RECOVERABLE)).toBe(false);
    expect(mark("EF-4", RecoverableType.UNRECOVERABLE)).toBe(true);
    expect(mark("EF-6", RecoverableType.UNKNOWN)).toBe(true);
    expect(harness.choice("markRecoverable")?.map((o) => o.value)).toEqual([
      RecoverableType.RECOVERABLE,
      RecoverableType.UNRECOVERABLE,
      RecoverableType.UNKNOWN,
    ]);
  });

  it("knows when a preparation times out, the millisecond after its deadline", () => {
    expect(harness.changesAt("EF-1")).toBe(2_001);
    expect(harness.changesAt("EF-4")).toBeNull();
    expect(harness.changesAt("EF-6")).toBeNull();
    expect(
      actionHarness(executionActions(commands()), ROWS, { now: 2_001 }).state(
        "prepare",
        "EF-1",
      ).available,
    ).toBe(true);
  });

  it("counts the picked rows that will not be sent, by reason", () => {
    expect(
      harness.bulk("prepare", ["EF-1", "EF-2", "EF-3", "EF-4"]).reasons,
    ).toEqual([
      { reason: IN_PROGRESS, count: 2, keys: ["EF-1", "EF-2"] },
      { reason: SUCCEEDED, count: 1, keys: ["EF-3"] },
    ]);
  });

  it("prepares one at a press and counts a selection first; forcing and marking always ask", () => {
    expect(harness.asks("prepare", "row").asks).toBe(false);
    expect(harness.asks("prepare", "bulk").confirm?.title).toBe(
      text("executionActions.prepareConfirm"),
    );
    expect(harness.asks("forcePrepare", "row").asks).toBe(true);
    const unrecoverable = harness.asks("markRecoverable", "row", {
      recoverable: RecoverableType.UNRECOVERABLE,
    }).confirm;
    expect(unrecoverable).toMatchObject({
      tone: "danger",
      body: text("executionActions.markUnrecoverableBody"),
      action: text("executionActions.markAsValue"),
    });
    expect(
      harness.asks("markRecoverable", "row", {
        recoverable: RecoverableType.UNKNOWN,
      }).confirm?.tone,
    ).toBeUndefined();
  });
});

describe("running them", () => {
  it("sends each through its own command, and refuses what a row does not take without a request", async () => {
    const sent = commands();
    const harness = actionHarness(executionActions(sent), ROWS, {
      now: 1_000,
    });
    await expect(harness.run("prepare", "EF-1")).rejects.toBeInstanceOf(
      ActionRefused,
    );
    await harness.run("prepare", "EF-4");
    await harness.run("forcePrepare", "EF-5");
    await harness.run("markRecoverable", "EF-4", {
      recoverable: RecoverableType.UNRECOVERABLE,
    });
    expect(sent.prepare.mock.calls).toEqual([["EF-4"]]);
    expect(sent.forcePrepare).toHaveBeenCalledWith("EF-5");
    expect(sent.markRecoverable).toHaveBeenCalledWith(
      "EF-4",
      RecoverableType.UNRECOVERABLE,
    );
  });

  it("passes the service's refusal on as it came", async () => {
    const sent = commands();
    const refusal = new Error("ExecutionFailed can not retry.");
    sent.prepare.mockRejectedValueOnce(refusal);
    await expect(
      actionHarness(executionActions(sent), ROWS, { now: 1_000 }).run(
        "prepare",
        "EF-4",
      ),
    ).rejects.toBe(refusal);
  });
});
