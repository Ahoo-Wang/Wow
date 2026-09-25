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
import { expect, test, type Page } from "@playwright/test";
import {
  executions,
  stubExecutionFailedCommands,
  stubExecutionFailedService,
  type Snapshot,
} from "./support/executionFailedService.ts";

// The compensation commands on 「失败执行（预览）」 (rebuild proposal, batch 3):
// a row's commands in its row, a selection's in the toolbar, both through the
// engine's `useBulkCommand`. The service is stubbed; a command it takes
// changes the documents the next page reads.

const IN_PROGRESS = "Execution is in progress; wait until it times out.";
const REFUSED = "ExecutionFailed can not retry.";

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
});

/** `count` executions, the newest first, in the given status. */
function documents(
  count: number,
  status: "FAILED" | "PREPARED",
  { from = 1, timeoutAt }: { from?: number; timeoutAt?: number } = {},
): Snapshot[] {
  return executions(count).map((document, index) => {
    const id = `EF-${String(from + index).padStart(2, "0")}`;
    const state = document.state as Record<string, unknown> & {
      retryState: Record<string, number>;
    };
    return {
      ...document,
      aggregateId: id,
      // Newest first: the order the Active view sorts in is the order here.
      eventTime: Date.parse("2026-09-20T00:00:00Z") - (from + index) * 60_000,
      state: {
        ...state,
        id,
        status,
        recoverable: "RECOVERABLE",
        isRetryable: true,
        isBelowRetryThreshold: true,
        retryState: {
          ...state.retryState,
          retries: 1,
          timeoutAt: timeoutAt ?? state.retryState.timeoutAt,
        },
      },
    };
  });
}

function rowOf(page: Page, id: string) {
  return page.getByRole("row").filter({
    has: page.getByRole("checkbox", { name: `Select ${id}`, exact: true }),
  });
}

function box(page: Page, id: string) {
  return page.getByRole("checkbox", { name: `Select ${id}`, exact: true });
}

async function openActive(page: Page, rows: number) {
  await page.goto("/executions");
  const workbench = page.getByRole("region", { name: "Active" });
  await expect(workbench).toBeVisible();
  await expect(page.getByRole("checkbox", { name: /^Select EF-/ })).toHaveCount(
    rows,
  );
  return workbench;
}

test("prepares twenty in bulk; the three the server refuses stay selected", async ({
  page,
}) => {
  const all = documents(20, "FAILED");
  await stubExecutionFailedService(page, all);
  let release!: () => void;
  const hold = new Promise<void>((resolve) => (release = resolve));
  const refused = ["EF-03", "EF-08", "EF-15"];
  const sent = await stubExecutionFailedCommands(page, all, {
    refuse: new Map(refused.map((id) => [id, REFUSED])),
    hold,
  });
  const workbench = await openActive(page, 20);

  // The page never scrolls sideways: a wide table scrolls in its own box.
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth,
    ),
  ).toBeLessThanOrEqual(0);
  // On a desktop the commands column is pinned, so a row's commands are in
  // view beside the console's sidebar; a phone scrolls the table to them.
  const width = page.viewportSize()?.width ?? 0;
  if (width >= 1024) {
    const right = await rowOf(page, "EF-01")
      .getByRole("button", { name: "Prepare", exact: true })
      .evaluate((button) => button.getBoundingClientRect().right);
    expect(right).toBeLessThanOrEqual(width);
  }

  // Three presses: pick the page, prepare it, confirm how many.
  await workbench
    .getByRole("checkbox", { name: "Select all rows", exact: true })
    .check();
  await workbench.getByRole("button", { name: "Prepare 20" }).click();
  const dialog = page.getByRole("alertdialog", {
    name: "Prepare 20 executions?",
  });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "Prepare", exact: true }).click();
  await expect(dialog).toBeHidden();

  // Progress while the service holds the answers, with the way to stop.
  const status = workbench.locator('[data-slot="bulk-status"]');
  await expect(status).toHaveAttribute("data-state", "running");
  await expect(status).toContainText("Prepare · Running 0 of 20");
  await expect(status.getByRole("button", { name: "Stop" })).toBeVisible();
  // One command at a time: the toolbar's commands wait for this one.
  await expect(
    workbench.getByRole("button", { name: "Prepare 20" }),
  ).toBeDisabled();
  release();

  // The outcome: what was done, the service's reason for what was not, and
  // the refused rows still picked — only them.
  await expect(status).toHaveAttribute("data-state", "settled");
  await expect(status).toContainText(
    `Prepare · 17 done, 3 failed · ${REFUSED} (3) · the rest stay selected`,
  );
  for (const document of all) {
    const id = document.aggregateId;
    if (refused.includes(id)) await expect(box(page, id)).toBeChecked();
    else await expect(box(page, id)).not.toBeChecked();
  }
  await expect(
    workbench.getByRole("button", { name: "Prepare 3" }),
  ).toBeVisible();

  // Every execution went to the service once, waiting for its snapshot.
  expect(sent.map(({ id }) => id).sort()).toEqual(
    all.map(({ aggregateId }) => aggregateId).sort(),
  );
  expect(new Set(sent.map(({ command }) => command))).toEqual(
    new Set(["prepare_compensation"]),
  );
  expect(new Set(sent.map(({ waitStage }) => waitStage))).toEqual(
    new Set(["SNAPSHOT"]),
  );

  // The page was read again: a prepared row now says so, and is in
  // progress, so it cannot be prepared again until it times out.
  const prepared = rowOf(page, "EF-01");
  await expect(prepared).toContainText("Prepared");
  await expect(
    prepared.getByRole("button", { name: "Prepare", exact: true }),
  ).toBeDisabled();
  await expect(rowOf(page, "EF-03")).toContainText("Failed");

  await status.getByRole("button", { name: "Dismiss" }).click();
  await expect(status).toHaveCount(0);
});

test("an execution still in progress cannot be prepared, and says why", async ({
  page,
}) => {
  const later = Date.now() + 3_600_000;
  const all = [
    ...documents(2, "PREPARED", { from: 1, timeoutAt: later }),
    ...documents(3, "FAILED", { from: 3 }),
  ];
  await stubExecutionFailedService(page, all);
  const sent = await stubExecutionFailedCommands(page, all);
  const workbench = await openActive(page, 5);

  // In its row: the button is disabled and described by the reason, which
  // a pointer reads off a tooltip and a keyboard atop the row's menu.
  const running = rowOf(page, "EF-01");
  const prepare = running.getByRole("button", {
    name: "Prepare",
    exact: true,
  });
  await expect(prepare).toBeDisabled();
  await expect(prepare).toHaveAccessibleDescription(IN_PROGRESS);
  await prepare.hover({ force: true });
  await expect(page.locator('[data-slot="tooltip-content"]')).toHaveText(
    IN_PROGRESS,
  );
  await running.getByRole("button", { name: "Actions for EF-01" }).click();
  const menu = page.getByRole("menu");
  await expect(menu.getByText(IN_PROGRESS)).toBeVisible();
  await expect(
    menu.getByRole("menuitem", { name: "Force prepare" }),
  ).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();

  // A failure beside it takes the command at once, from its row.
  const failed = rowOf(page, "EF-03");
  await failed.getByRole("button", { name: "Prepare", exact: true }).click();
  const status = workbench.locator('[data-slot="bulk-status"]');
  await expect(status).toContainText("Prepare · 1 done");
  await expect(failed).toContainText("Prepared");
  await expect(
    failed.getByRole("button", { name: "Prepare", exact: true }),
  ).toBeDisabled();
  expect(sent.map(({ id }) => id)).toEqual(["EF-03"]);
  await status.getByRole("button", { name: "Dismiss" }).click();

  // In bulk: the confirmation says which will not be sent, and why; those
  // are not sent, and stay selected with the reason.
  await workbench
    .getByRole("checkbox", { name: "Select all rows", exact: true })
    .check();
  await workbench.getByRole("button", { name: "Prepare 5" }).click();
  const dialog = page.getByRole("alertdialog", {
    name: "Prepare 5 executions?",
  });
  await expect(dialog.getByText("Not sent, and left selected:")).toBeVisible();
  await expect(dialog.getByText(`${IN_PROGRESS} (3)`)).toBeVisible();
  await dialog.getByRole("button", { name: "Prepare", exact: true }).click();
  await expect(status).toContainText(
    `Prepare · 2 done, 3 failed · ${IN_PROGRESS} (3) · the rest stay selected`,
  );
  expect(sent.map(({ id }) => id).sort()).toEqual(["EF-03", "EF-04", "EF-05"]);
  for (const id of ["EF-01", "EF-02", "EF-03"])
    await expect(box(page, id)).toBeChecked();
  for (const id of ["EF-04", "EF-05"])
    await expect(box(page, id)).not.toBeChecked();
});

test("force prepare and recoverability ask first, from a row", async ({
  page,
}) => {
  const all = documents(3, "FAILED");
  await stubExecutionFailedService(page, all);
  const sent = await stubExecutionFailedCommands(page, all);
  const workbench = await openActive(page, 3);

  const row = rowOf(page, "EF-02");
  await row.getByRole("button", { name: "Actions for EF-02" }).click();
  // Its current recoverability is not offered again.
  await expect(
    page.getByRole("menuitem", { name: "Recoverable", exact: true }),
  ).toBeDisabled();
  await page.getByRole("menuitem", { name: "Unrecoverable" }).click();
  const dialog = page.getByRole("alertdialog", {
    name: "Mark 1 execution as Unrecoverable?",
  });
  await expect(
    dialog.getByText("The scheduler stops retrying unrecoverable executions."),
  ).toBeVisible();
  // Cancelled: nothing is sent.
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  expect(sent).toEqual([]);

  await row.getByRole("button", { name: "Actions for EF-02" }).click();
  await page.getByRole("menuitem", { name: "Force prepare" }).click();
  const force = page.getByRole("alertdialog", {
    name: "Force prepare 1 execution?",
  });
  await force.getByRole("button", { name: "Force prepare" }).click();
  const status = workbench.locator('[data-slot="bulk-status"]');
  await expect(status).toContainText("Force prepare · 1 done");
  expect(sent).toMatchObject([
    { id: "EF-02", command: "force_prepare_compensation" },
  ]);
  await expect(row).toContainText("Prepared");
});
