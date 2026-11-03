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
import {
  ActionInputError,
  text,
  type RecordRow,
} from "@ahoo-wang/wow-view-engine";
import { actionHarness } from "@ahoo-wang/wow-view-engine/testing";
import { describe, expect, it, vi } from "vitest";
import { parkedActions, retrySpecOf } from "./parkedActions.ts";
import type { ParkedCommands } from "./parkedCommands.ts";

function row(key: string, status: string, recoverable = "unknown"): RecordRow {
  return {
    key,
    data: {
      aggregateId: key,
      state: { status, recoverable, failedAt: "1760000000000" },
    },
  } as RecordRow;
}

const ROWS = [
  row("exhausted", "exhausted"),
  row("unrecoverable", "unrecoverable", "unrecoverable"),
  row("resolved", "resolved"),
];

function commands() {
  return {
    retry: vi.fn<ParkedCommands["retry"]>(async () => ({})),
    skip: vi.fn(async () => ({})),
    markRecoverable: vi.fn(async () => ({})),
    applyRetrySpec: vi.fn(async () => ({})),
  } satisfies ParkedCommands;
}

describe("the operator's actions", () => {
  it("offer retry in the row and the rest behind its menu, everywhere", () => {
    const harness = actionHarness(parkedActions(commands()), ROWS);
    expect(harness.ids).toEqual([
      "retry",
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
        harness.state(id, "resolved", { recoverable: "recoverable" }),
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
    ).toEqual(["recoverable", "unrecoverable", "unknown"]);
    expect(
      harness.state("markRecoverable", "unrecoverable", {
        recoverable: "unrecoverable",
      }),
    ).toMatchObject({
      available: false,
      reason: text("parkedActions.alreadyMarked"),
    });
    expect(
      harness.state("markRecoverable", "unrecoverable", {
        recoverable: "recoverable",
      }).available,
    ).toBe(true);
  });

  it("ask first, an unrecoverable mark and a skip as the dangerous ones", () => {
    const harness = actionHarness(parkedActions(commands()), ROWS);
    expect(harness.asks("retry", "row").asks).toBe(true);
    expect(
      harness.asks("markRecoverable", "row", { recoverable: "unrecoverable" })
        .confirm,
    ).toMatchObject({ tone: "danger" });
    expect(
      harness.asks("markRecoverable", "row", { recoverable: "recoverable" })
        .confirm?.tone,
    ).toBeUndefined();
    expect(harness.missing("retry", {})).toEqual([]);
    expect(harness.form("applyRetrySpec")?.map((field) => field.name)).toEqual([
      "maxRetries",
      "initialBackoff",
      "claimTtl",
    ]);
  });

  it("send each command for the entry, with what the form says", async () => {
    const sent = commands();
    const harness = actionHarness(parkedActions(sent), ROWS);
    await harness.run("retry", "exhausted", { reason: " back " });
    await harness.run("skip", "exhausted", { reason: "" });
    await harness.run("markRecoverable", "exhausted", {
      recoverable: "unrecoverable",
    });
    await harness.run("applyRetrySpec", "exhausted", {
      maxRetries: 5,
      initialBackoff: " 2m ",
      claimTtl: "",
    });
    // Each names the parking the row shows, which the service refuses once
    // the entry was parked again (`ops.parked.stale`).
    expect(sent.retry).toHaveBeenCalledWith(
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
      "unrecoverable",
    );
    expect(sent.applyRetrySpec).toHaveBeenCalledWith("exhausted", {
      maxRetries: 5,
      initialBackoff: "2m",
    });
  });

  it("report the service's refusal in its own words", async () => {
    const sent = commands();
    sent.retry.mockRejectedValue(
      new WowProblemError({
        type: "urn:wow:error:wow.auth.forbidden",
        title: "Forbidden",
        status: 403,
        detail: "victor may not wow.ledger.Retry: it needs ops.operator.",
        code: "wow.auth.forbidden",
      }),
    );
    const harness = actionHarness(parkedActions(sent), ROWS);
    await expect(harness.run("retry", "exhausted")).rejects.toThrow(
      "victor may not wow.ledger.Retry: it needs ops.operator. (wow.auth.forbidden)",
    );
  });
});

describe("the retry spec of the form", () => {
  it("leaves out what was left blank", () => {
    expect(retrySpecOf({})).toEqual({});
    expect(retrySpecOf({ maxRetries: "7", claimTtl: "PT3M" })).toEqual({
      maxRetries: 7,
      claimTtl: "PT3M",
    });
    expect(
      retrySpecOf({ maxRetries: Number.NaN, initialBackoff: "  " }),
    ).toEqual({});
  });
});

describe("what the console checks before the service would refuse it", () => {
  it("refuses what the service refuses, in the form: a count past its Int, a duration its Kotlin cannot read", () => {
    const apply = parkedActions(commands()).find(
      (action) => action.id === "applyRetrySpec",
    )!;
    const { maxRetries, initialBackoff, claimTtl } = apply.form!;
    const count = text("parkedActions.wholeNumber");
    expect(maxRetries.check!(-1)).toBe(count);
    expect(maxRetries.check!(1.5)).toBe(count);
    // ApplyRetrySpec.maxRetries is an Int: one past it is a malformed body (400).
    expect(maxRetries.check!(2_147_483_648)).toBe(count);
    expect(maxRetries.check!("2147483647")).toBe(true);
    expect(maxRetries.check!("3")).toBe(true);
    const duration = text("parkedActions.duration");
    expect(initialBackoff.check!("abc")).toBe(duration);
    // Each of these the service answered 422 while the form let it through.
    expect(initialBackoff.check!("PT1.5M")).toBe(duration);
    expect(claimTtl.check!("1m 1h")).toBe(duration);
    // ...and refused this one, which the service takes.
    expect(initialBackoff.check!("1h30m")).toBe(true);
    expect(initialBackoff.check!(" 2m ")).toBe(true);
    expect(claimTtl.check!("PT5M")).toBe(true);
  });

  it("hands the form back when the service refuses what it sent, the field it names marked", async () => {
    const sent = commands();
    sent.applyRetrySpec.mockRejectedValue(
      new WowProblemError({
        type: "urn:wow:error:ops.parked.invalid",
        title: "The action's arguments are not valid.",
        status: 422,
        detail: "maxRetries is never negative: -1.",
        code: "ops.parked.invalid",
        extensions: { field: "maxRetries" },
      }),
    );
    const harness = actionHarness(parkedActions(sent), ROWS);
    const refused = await harness
      .run("applyRetrySpec", "exhausted", { maxRetries: 1 })
      .catch((error: unknown) => error);
    expect(refused).toBeInstanceOf(ActionInputError);
    const reason = "maxRetries is never negative: -1. (ops.parked.invalid)";
    expect(refused).toMatchObject({
      reason,
      fields: { maxRetries: reason },
    });
  });

  it("says an action was sent to the owning service, not done: the service runs it", () => {
    for (const action of parkedActions(commands()))
      expect(action.done).toBe(text("parkedActions.sent"));
  });
});
