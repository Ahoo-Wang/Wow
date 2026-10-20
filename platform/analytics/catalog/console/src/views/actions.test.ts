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
import { describe, expect, it } from "vitest";
import { commandsStub } from "@/app/testing.tsx";
import { datasetActions } from "./datasetActions.ts";
import { sourceActions } from "./sourceActions.ts";

function row(key: string, state: Record<string, unknown>): RecordRow {
  return { key, data: { aggregateId: key, state } } as RecordRow;
}

const SOURCES = [
  row("draft", {
    kind: "ANALYST",
    status: "DRAFT",
    approved: false,
    missing: false,
  }),
  row("reviewed", {
    kind: "ANALYST",
    status: "REVIEWED",
    approved: false,
    missing: false,
  }),
  row("gone", { kind: "ANALYST", status: "DRAFT", missing: true }),
  row("wow", {
    kind: "WOW",
    status: "ADOPTED",
    approved: true,
    missing: false,
  }),
];

const DATASETS = [
  row("draft", { status: "DRAFT", current: 0 }),
  row("published", { status: "PUBLISHED", current: 2 }),
  row("retired", { status: "RETIRED", current: 2 }),
];

describe("the actions on a source", () => {
  it("mark an analyst's draft reviewed, and nothing else", () => {
    const harness = actionHarness(sourceActions(commandsStub()), SOURCES);
    expect(harness.ids).toEqual(["markReviewed", "approve", "approveLower"]);
    expect(harness.state("markReviewed", "draft").available).toBe(true);
    expect(harness.state("markReviewed", "reviewed")).toMatchObject({
      available: false,
      reason: text("sourceActions.notDraft"),
    });
    expect(harness.state("markReviewed", "gone")).toMatchObject({
      reason: text("sourceActions.tableGone"),
    });
    expect(harness.state("markReviewed", "wow")).toMatchObject({
      reason: text("sourceActions.wowTable"),
    });
  });

  it("approve a reviewed or adopted source once", () => {
    const harness = actionHarness(sourceActions(commandsStub()), SOURCES);
    expect(harness.state("approve", "reviewed").available).toBe(true);
    expect(harness.state("approve", "draft")).toMatchObject({
      reason: text("sourceActions.draft"),
    });
    expect(harness.state("approve", "wow")).toMatchObject({
      reason: text("sourceActions.alreadyApproved"),
    });
  });

  it("accept a lower protection of a Wow table only, as a danger", () => {
    const harness = actionHarness(sourceActions(commandsStub()), SOURCES);
    expect(harness.state("approveLower", "wow").available).toBe(true);
    expect(harness.state("approveLower", "draft")).toMatchObject({
      reason: text("sourceActions.analystTable"),
    });
    expect(harness.asks("approveLower", "row").confirm).toMatchObject({
      tone: "danger",
    });
  });

  it("send each command for the source", async () => {
    const sent = commandsStub();
    const harness = actionHarness(sourceActions(sent), SOURCES);
    await harness.run("markReviewed", "draft", {});
    await harness.run("approve", "reviewed", {});
    await harness.run("approveLower", "wow", {});
    expect(sent.markReviewed).toHaveBeenCalledWith("draft");
    expect(sent.approveSource).toHaveBeenCalledWith("reviewed");
    expect(sent.approveSensitivityChange).toHaveBeenCalledWith("wow");
  });
});

describe("the actions on a dataset", () => {
  it("roll back and retire only what was published and is not retired", () => {
    const harness = actionHarness(datasetActions(commandsStub()), DATASETS);
    expect(harness.ids).toEqual([
      "publish",
      "approve",
      "refuse",
      "rollback",
      "retire",
    ]);
    for (const id of ["rollback", "retire"]) {
      expect(harness.state(id, "published").available).toBe(true);
      expect(harness.state(id, "draft")).toMatchObject({
        reason: text("datasetActions.notPublished"),
      });
      expect(harness.state(id, "retired")).toMatchObject({
        reason: text("datasetActions.retired"),
      });
    }
    expect(harness.form("rollback")?.map((field) => field.name)).toEqual([
      "version",
    ]);
    expect(harness.form("refuse")?.map((field) => field.name)).toEqual([
      "reason",
    ]);
  });

  it("send each command for the dataset, with what the form says", async () => {
    const sent = commandsStub();
    const harness = actionHarness(datasetActions(sent), DATASETS);
    await harness.run("publish", "draft", {});
    await harness.run("approve", "draft", {});
    await harness.run("refuse", "draft", { reason: " Not yet. " });
    await harness.run("refuse", "draft", {});
    await harness.run("rollback", "published", { version: 1 });
    await harness.run("retire", "published", {});
    expect(sent.publish).toHaveBeenCalledWith("draft");
    expect(sent.approvePublication).toHaveBeenCalledWith("draft");
    expect(sent.refusePublication).toHaveBeenNthCalledWith(
      1,
      "draft",
      "Not yet.",
    );
    expect(sent.refusePublication).toHaveBeenNthCalledWith(2, "draft", "");
    expect(sent.rollback).toHaveBeenCalledWith("published", 1);
    expect(sent.retire).toHaveBeenCalledWith("published");
  });

  it("report the catalog's refusal in its own words", async () => {
    const sent = commandsStub();
    sent.publish.mockRejectedValue(
      new WowProblemError({
        type: "about:blank",
        title: "Bad Request",
        status: 400,
        detail: "nothing is not a column.",
        code: "analytics.catalog.definition-invalid",
      }),
    );
    const harness = actionHarness(datasetActions(sent), DATASETS);
    await expect(harness.run("publish", "draft", {})).rejects.toThrow(
      "nothing is not a column. (analytics.catalog.definition-invalid)",
    );
  });
});
