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

import { WowProblemError } from "@ahoo-wang/wow-client";
import { text, type RecordRow } from "@ahoo-wang/wow-view-engine";
import { actionHarness } from "@ahoo-wang/wow-view-engine/testing";
import { describe, expect, it, vi } from "vitest";
import { parkedActions, retrySpecOf } from "./parkedActions.ts";
import type { ParkedCommands } from "./parkedCommands.ts";

function row(key: string, status: string, recoverable = "UNKNOWN"): RecordRow {
  return {
    key,
    data: {
      aggregateId: key,
      state: { status, recoverable, failedAt: "1760000000000" },
    },
  } as RecordRow;
}

const ROWS = [
  row("exhausted", "EXHAUSTED"),
  row("unrecoverable", "UNRECOVERABLE", "UNRECOVERABLE"),
  row("resolved", "RESOLVED"),
];

function commands() {
  return {
    resume: vi.fn<ParkedCommands["resume"]>(async () => ({})),
    skip: vi.fn(async () => ({})),
    markRecoverable: vi.fn(async () => ({})),
    applyRetrySpec: vi.fn(async () => ({})),
  } satisfies ParkedCommands;
}

describe("the operator's actions", () => {
  it("offer resume in the row and the rest behind its menu, everywhere", () => {
    const harness = actionHarness(parkedActions(commands()), ROWS);
    expect(harness.ids).toEqual([
      "resume",
      "skip",
      "markRecoverable",
      "applyRetrySpec",
    ]);
    for (const place of ["row", "bulk", "detail"] as const)
      expect(harness.at(place)).toEqual(harness.ids);
  });

  it("take no resolved entry: nothing is parked to act on", () => {
    const harness = actionHarness(parkedActions(commands()), ROWS);
    for (const id of harness.ids) {
      expect(harness.state(id, "exhausted").available).toBe(true);
      expect(
        harness.state(id, "resolved", { recoverable: "RECOVERABLE" }),
      ).toMatchObject({
        available: false,
        reason: text("parkedActions.resolved"),
      });
    }
    expect(harness.bulk("skip", ["exhausted", "resolved"]).able).toEqual([
      "exhausted",
    ]);
  });

  it("do not mark an entry as it is marked already", () => {
    const harness = actionHarness(parkedActions(commands()), ROWS);
    expect(
      harness.choice("markRecoverable")?.map((option) => option.value),
    ).toEqual(["RECOVERABLE", "UNRECOVERABLE", "UNKNOWN"]);
    expect(
      harness.state("markRecoverable", "unrecoverable", {
        recoverable: "UNRECOVERABLE",
      }),
    ).toMatchObject({
      available: false,
      reason: text("parkedActions.alreadyMarked"),
    });
    expect(
      harness.state("markRecoverable", "unrecoverable", {
        recoverable: "RECOVERABLE",
      }).available,
    ).toBe(true);
  });

  it("ask first, an unrecoverable mark and a skip as the dangerous ones", () => {
    const harness = actionHarness(parkedActions(commands()), ROWS);
    expect(harness.asks("resume", "row").asks).toBe(true);
    expect(
      harness.asks("markRecoverable", "row", { recoverable: "UNRECOVERABLE" })
        .confirm,
    ).toMatchObject({ tone: "danger" });
    expect(
      harness.asks("markRecoverable", "row", { recoverable: "RECOVERABLE" })
        .confirm?.tone,
    ).toBeUndefined();
    expect(harness.missing("resume", {})).toEqual([]);
    expect(harness.form("applyRetrySpec")?.map((field) => field.name)).toEqual([
      "maxRetries",
      "initialBackoff",
      "executionTimeout",
    ]);
  });

  it("send each command for the entry, with what the form says", async () => {
    const sent = commands();
    const harness = actionHarness(parkedActions(sent), ROWS);
    await harness.run("resume", "exhausted", { reason: " back " });
    await harness.run("skip", "exhausted", { reason: "" });
    await harness.run("markRecoverable", "exhausted", {
      recoverable: "UNRECOVERABLE",
    });
    await harness.run("applyRetrySpec", "exhausted", {
      maxRetries: 5,
      initialBackoff: " 2m ",
      executionTimeout: "",
    });
    // Each names the parking the row shows, which the service refuses once
    // the entry was parked again (`ops.parked.stale`).
    expect(sent.resume).toHaveBeenCalledWith(
      "exhausted",
      "back",
      "1760000000000",
    );
    expect(sent.skip).toHaveBeenCalledWith(
      "exhausted",
      undefined,
      "1760000000000",
    );
    expect(sent.markRecoverable).toHaveBeenCalledWith(
      "exhausted",
      "UNRECOVERABLE",
    );
    expect(sent.applyRetrySpec).toHaveBeenCalledWith("exhausted", {
      maxRetries: 5,
      initialBackoff: "2m",
    });
  });

  it("report the service's refusal in its own words", async () => {
    const sent = commands();
    sent.resume.mockRejectedValue(
      new WowProblemError({
        type: "urn:wow:error:wow.auth.forbidden",
        title: "Forbidden",
        status: 403,
        detail: "victor may not wow.ledger.Resume: it needs ops.operator.",
        code: "wow.auth.forbidden",
      }),
    );
    const harness = actionHarness(parkedActions(sent), ROWS);
    await expect(harness.run("resume", "exhausted")).rejects.toThrow(
      "victor may not wow.ledger.Resume: it needs ops.operator. (wow.auth.forbidden)",
    );
  });
});

describe("the retry spec of the form", () => {
  it("leaves out what was left blank", () => {
    expect(retrySpecOf({})).toEqual({});
    expect(retrySpecOf({ maxRetries: "7", executionTimeout: "PT3M" })).toEqual({
      maxRetries: 7,
      executionTimeout: "PT3M",
    });
    expect(retrySpecOf({ maxRetries: Number.NaN, initialBackoff: "  " })).toEqual(
      {},
    );
  });
});
