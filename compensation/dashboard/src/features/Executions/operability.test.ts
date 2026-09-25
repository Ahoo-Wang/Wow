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
import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import { describe, expect, it, vi } from "vitest";
import { translate } from "@/i18n.tsx";
import type { ExecutionCommands } from "./executionCommands.ts";
import {
  bulkRun,
  capabilitiesChangeAt,
  recoverabilityLabel,
  refusalOf,
  refusedReasons,
  rowCapabilities,
  rowRecoverable,
  titleOf,
} from "./operability.ts";

const IN_PROGRESS = "Execution is in progress; wait until it times out.";
const t = (
  message: Parameters<typeof translate>[1],
  values?: Record<string, string | number>,
) => translate("en", message, values);

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
  };
}

describe("operability of a workbench row", () => {
  it("reads the row's state with the old queues' rule", () => {
    expect(rowCapabilities(row("EF-1"), 1_000)).toEqual({
      canPrepare: true,
      canForcePrepare: true,
    });
    expect(
      rowCapabilities(row("EF-1", { status: "PREPARED" }), 2_000),
    ).toMatchObject({ canPrepare: false, unavailableReason: IN_PROGRESS });
    expect(
      rowCapabilities(row("EF-1", { status: "PREPARED" }), 2_001),
    ).toMatchObject({ canPrepare: true });
    expect(
      rowCapabilities(row("EF-1", { isBelowRetryThreshold: false }), 1_000),
    ).toMatchObject({ canPrepare: false, canForcePrepare: true });
  });

  it("treats a row without a state as holding nothing it can be judged by", () => {
    const bare: RecordRow = { key: "EF-1", data: {} };
    // Below the threshold is not known, so only a forced prepare is offered.
    expect(rowCapabilities(bare, 1_000)).toMatchObject({
      canPrepare: false,
      canForcePrepare: true,
    });
    expect(rowRecoverable(bare)).toBeUndefined();
    expect(capabilitiesChangeAt(bare, 1_000)).toBeNull();
  });

  it("knows when a preparation times out, the millisecond after its deadline", () => {
    const prepared = row("EF-1", { status: "PREPARED" });
    expect(capabilitiesChangeAt(prepared, 1_000)).toBe(2_001);
    expect(capabilitiesChangeAt(prepared, 2_000)).toBe(2_001);
    expect(capabilitiesChangeAt(prepared, 2_001)).toBeNull();
    expect(capabilitiesChangeAt(row("EF-2"), 1_000)).toBeNull();
  });

  it("says why a command is refused before it is sent", () => {
    const prepared = row("EF-1", { status: "PREPARED" });
    expect(refusalOf({ kind: "prepare" }, prepared, 1_000)).toBe(IN_PROGRESS);
    expect(refusalOf({ kind: "forcePrepare" }, prepared, 1_000)).toBe(
      IN_PROGRESS,
    );
    expect(refusalOf({ kind: "prepare" }, row("EF-2"), 1_000)).toBeUndefined();
    // A row not on the page, and recoverability, are the server's to judge.
    expect(refusalOf({ kind: "prepare" }, undefined, 1_000)).toBeUndefined();
    expect(
      refusalOf(
        { kind: "markRecoverable", recoverable: RecoverableType.UNKNOWN },
        prepared,
        1_000,
      ),
    ).toBeUndefined();
  });

  it("counts the picked rows that will not be sent, by reason", () => {
    const rows = [
      row("EF-1", { status: "PREPARED" }),
      row("EF-2", { status: "PREPARED" }),
      row("EF-3", { status: "SUCCEEDED" }),
      row("EF-4"),
      row("EF-5", { status: "PREPARED" }),
    ];
    expect(
      refusedReasons(
        { kind: "prepare" },
        rows,
        ["EF-1", "EF-2", "EF-3", "EF-4"],
        1_000,
      ),
    ).toEqual([
      { reason: IN_PROGRESS, count: 2 },
      { reason: "This execution has already succeeded.", count: 1 },
    ]);
  });

  it("names each command for the outcome line", () => {
    expect(titleOf({ kind: "prepare" }, t)).toBe("Prepare");
    expect(titleOf({ kind: "forcePrepare" }, t)).toBe("Force prepare");
    expect(
      titleOf(
        { kind: "markRecoverable", recoverable: RecoverableType.UNRECOVERABLE },
        t,
      ),
    ).toBe("Mark as Unrecoverable");
    expect(recoverabilityLabel(undefined)).toBe("Unknown");
  });
});

describe("bulkRun", () => {
  it("sends what a row takes, and refuses the rest without a request", async () => {
    const sent = commands();
    const rows = [
      row("EF-1", {
        status: "PREPARED",
        retryState: { timeoutAt: Date.now() + 60_000 },
      }),
      row("EF-2"),
    ];
    const run = bulkRun({ kind: "prepare" }, rows, sent, t);
    expect(run.title).toBe("Prepare");
    await expect(run.each("EF-1")).rejects.toThrow(IN_PROGRESS);
    await run.each("EF-2");
    // Not on the page: the server decides.
    await run.each("EF-9");
    expect(sent.prepare.mock.calls).toEqual([["EF-2"], ["EF-9"]]);
  });

  it("forces a prepare and marks recoverability through their own commands", async () => {
    const sent = commands();
    await bulkRun({ kind: "forcePrepare" }, [], sent, t).each("EF-1");
    await bulkRun(
      { kind: "markRecoverable", recoverable: RecoverableType.UNRECOVERABLE },
      [],
      sent,
      t,
    ).each(7);
    expect(sent.forcePrepare).toHaveBeenCalledWith("EF-1");
    expect(sent.markRecoverable).toHaveBeenCalledWith(
      "7",
      RecoverableType.UNRECOVERABLE,
    );
  });

  it("passes the service's refusal on as it came", async () => {
    const sent = commands();
    const refusal = new Error("ExecutionFailed can not retry.");
    sent.prepare.mockRejectedValueOnce(refusal);
    await expect(
      bulkRun({ kind: "prepare" }, [row("EF-1")], sent, t).each("EF-1"),
    ).rejects.toBe(refusal);
  });
});
