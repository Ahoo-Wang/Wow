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
import { commandsStub, SETTINGS } from "@/app/testing.tsx";
import {
  ADMIN_ROLE,
  APPROVER_ROLE,
  EDITOR_ROLE,
  VIEWER_ROLE,
} from "@/services/caller.ts";
import { datasetActions } from "./datasetActions.ts";
import { sourceActions } from "./sourceActions.ts";

/** A caller holding `roles`, where the catalog's roles decide. */
const holding =
  (...roles: string[]) =>
  () =>
    roles;

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
    pending: ["phone"],
  }),
  row("wow-settled", {
    kind: "WOW",
    status: "ADOPTED",
    approved: true,
    missing: false,
    pending: [],
  }),
];

const versions = (...numbers: number[]) =>
  numbers.map((version) => ({ artifact: { version } }));

const DATASETS = [
  row("draft", { status: "DRAFT", current: 0 }),
  row("published", { status: "PUBLISHED", current: 2 }),
  row("retired", { status: "RETIRED", current: 2 }),
  row("requested", {
    status: "PUBLISHED",
    current: 1,
    requestReasons: ["c.phone is sensitive: it takes an approver."],
  }),
  // As the detail reads them: the whole record, its versions with it.
  row("whole", { status: "PUBLISHED", current: 2, versions: versions(1, 2) }),
  row("only", { status: "PUBLISHED", current: 1, versions: versions(1) }),
  row("revising", {
    status: "NEEDS_REVISION",
    current: 1,
    versions: versions(1),
  }),
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
    // Nothing a row lowered awaits an approver: nothing to accept.
    expect(harness.state("approveLower", "wow-settled")).toMatchObject({
      reason: text("sourceActions.nothingPending"),
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

  it("are offered disabled, with why, where the caller's roles rule them out", () => {
    const editor = actionHarness(
      sourceActions(commandsStub(), holding(EDITOR_ROLE)),
      SOURCES,
    );
    expect(editor.state("markReviewed", "draft")).toMatchObject({
      available: false,
      reason: text("sourceActions.reviewersOnly"),
    });
    for (const [id, key] of [
      ["approve", "reviewed"],
      ["approveLower", "wow"],
    ])
      expect(editor.state(id, key)).toMatchObject({
        available: false,
        reason: text("sourceActions.approversOnly"),
      });
    // An admin reviews; only an approver approves.
    const admin = actionHarness(
      sourceActions(commandsStub(), holding(ADMIN_ROLE)),
      SOURCES,
    );
    expect(admin.state("markReviewed", "draft").available).toBe(true);
    expect(admin.state("approve", "reviewed")).toMatchObject({
      reason: text("sourceActions.approversOnly"),
    });
    const approver = actionHarness(
      sourceActions(commandsStub(), holding(APPROVER_ROLE)),
      SOURCES,
    );
    for (const [id, key] of [
      ["markReviewed", "draft"],
      ["approve", "reviewed"],
      ["approveLower", "wow"],
    ])
      expect(approver.state(id, key).available).toBe(true);
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

  it("decide only a publication that awaits an approver", () => {
    const harness = actionHarness(datasetActions(commandsStub()), DATASETS);
    for (const id of ["approve", "refuse"]) {
      expect(harness.state(id, "requested").available).toBe(true);
      expect(harness.state(id, "published")).toMatchObject({
        available: false,
        reason: text("datasetActions.nothingRequested"),
      });
    }
  });

  it("roll back to a version published that is not the one serving", () => {
    const harness = actionHarness(datasetActions(commandsStub()), DATASETS);
    expect(harness.state("rollback", "whole", { version: 1 }).available).toBe(
      true,
    );
    expect(harness.state("rollback", "whole", { version: 2 })).toMatchObject({
      reason: text("datasetActions.alreadyServing"),
    });
    expect(harness.state("rollback", "whole", { version: 9 })).toMatchObject({
      reason: text("datasetActions.neverPublished"),
    });
    // A list's row holds no versions: only the serving one is refused.
    expect(
      harness.state("rollback", "published", { version: 2 }),
    ).toMatchObject({ reason: text("datasetActions.alreadyServing") });
    expect(
      harness.state("rollback", "published", { version: 9 }).available,
    ).toBe(true);
    // Nothing to go back to; but a dataset that needs revision gets the
    // view of the version it serves created again.
    expect(harness.state("rollback", "only")).toMatchObject({
      reason: text("datasetActions.onlyVersion"),
    });
    expect(
      harness.state("rollback", "revising", { version: 1 }).available,
    ).toBe(true);
  });

  it("are offered disabled, with why, where the caller's roles rule them out", () => {
    const approver = actionHarness(
      datasetActions(commandsStub(), { roles: holding(APPROVER_ROLE) }),
      DATASETS,
    );
    for (const id of ["publish", "rollback", "retire"])
      expect(approver.state(id, "published")).toMatchObject({
        available: false,
        reason: text("datasetActions.editorsOnly"),
      });
    expect(approver.state("approve", "requested").available).toBe(true);
    for (const roles of [[EDITOR_ROLE], [ADMIN_ROLE], [VIEWER_ROLE]]) {
      const harness = actionHarness(
        datasetActions(commandsStub(), { roles: holding(...roles) }),
        DATASETS,
      );
      for (const id of ["approve", "refuse"])
        expect(harness.state(id, "requested")).toMatchObject({
          available: false,
          reason: text("datasetActions.approversOnly"),
        });
    }
    const admin = actionHarness(
      datasetActions(commandsStub(), { roles: holding(ADMIN_ROLE) }),
      DATASETS,
    );
    expect(admin.state("publish", "published").available).toBe(true);
  });

  it("say a publication may only await an approver, and that every one does where the catalog says so", () => {
    const publish = (settings = SETTINGS) =>
      datasetActions(commandsStub(), { settings }).find(
        (action) => action.id === "publish",
      );
    expect(publish()?.done).toBe(text("datasetActions.publishDone"));
    const approved = publish({ ...SETTINGS, requirePublicationApproval: true });
    expect(approved?.done).toBe(text("datasetActions.publishRequested"));
    expect(approved?.confirm).toMatchObject({
      body: text("datasetActions.publishBodyApproval"),
    });
  });

  it("send each command for the dataset, with what the form says", async () => {
    const sent = commandsStub();
    const harness = actionHarness(datasetActions(sent), DATASETS);
    await harness.run("publish", "draft", {});
    await harness.run("approve", "requested", {});
    await harness.run("refuse", "requested", { reason: " Not yet. " });
    await harness.run("refuse", "requested", {});
    await harness.run("rollback", "published", { version: 1 });
    await harness.run("retire", "published", {});
    expect(sent.publish).toHaveBeenCalledWith("draft");
    expect(sent.approvePublication).toHaveBeenCalledWith("requested");
    expect(sent.refusePublication).toHaveBeenNthCalledWith(
      1,
      "requested",
      "Not yet.",
    );
    expect(sent.refusePublication).toHaveBeenNthCalledWith(2, "requested", "");
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
