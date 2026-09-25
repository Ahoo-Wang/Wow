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

import { AggregationDateUnit } from '@ahoo-wang/wow-client';
import { expect, test, type Locator, type Page } from '@playwright/test';
import {
  aggregate,
  stubExecutionFailedCommands,
  stubExecutionFailedService,
  type Snapshot,
  type SnapshotQueries,
} from './support/executionFailedService.ts';
import {
  createEventTrendQueries,
  createPressureQuery,
  createPressureStatusQuery,
  createRecoverabilityQuery,
  createRetryDistributionQuery,
  createSnapshotSummaryQuery,
  mapRetryDistribution,
  mergePressureRows,
  mergeTrendRows,
  summarizeTrend,
  type PressureClusterRow,
  type PressureStatusRow,
  type RetryDistributionRow,
  type SnapshotSummaryRow,
  type TrendRow,
  type TrendWindow,
} from './support/legacy/overviewQueries.ts';
import {
  OVERVIEW_NOW,
  overviewExecutions,
  overviewStreams,
  stubExecutionFailedEvents,
  type EventStream,
} from './support/overviewService.ts';

// The home page is the overview board since batch 6 (rebuild proposal, 2.3).
// Its figures are the engine's queries; the old overview's are kept as the
// oracle (`support/legacy/overviewQueries.ts`), and over the same documents
// and the same pinned moment every figure the old page showed comes out the
// same (criterion 2) — but for G9, the older and newer backlog, which the
// board says as the two numbers "in range" and "all".

// Days are cut in UTC, in the page and in the stub alike.
test.use({ timezoneId: 'UTC' });

const DAY = 86_400_000;

/** The old overview's default window: today and the six days before it. */
function lastDays(days: number): TrendWindow {
  const today = Math.floor(OVERVIEW_NOW / DAY) * DAY;
  const start = today - (days - 1) * DAY;
  return {
    buckets: Array.from({ length: days }, (_, day) => start + day * DAY),
    start,
    end: today + DAY,
    timeZone: 'UTC',
    unit: AggregationDateUnit.DAY,
  };
}

/** Every figure the old overview showed, asked of the documents its way. */
function oldOverview(
  documents: readonly Snapshot[],
  streams: readonly EventStream[],
  window: TrendWindow,
) {
  const ask = <T>(query: unknown, over: readonly object[] = documents) =>
    aggregate(
      over as never,
      OVERVIEW_NOW,
      query as Parameters<typeof aggregate>[2],
    ) as unknown as T[];
  const [summary] = ask<SnapshotSummaryRow>(
    createSnapshotSummaryQuery(OVERVIEW_NOW, window),
  );
  const clusters = ask<PressureClusterRow>(createPressureQuery(window));
  const pressure = mergePressureRows(
    clusters,
    clusters.length
      ? ask<PressureStatusRow>(createPressureStatusQuery(clusters, window))
      : [],
  );
  const recoverability = Object.fromEntries(
    ask<{ recoverable: string; count: number }>(
      createRecoverabilityQuery(window),
    ).map(({ recoverable, count }) => [recoverable, count]),
  );
  const [retries] = ask<RetryDistributionRow>(
    createRetryDistributionQuery(window),
  );
  const queries = createEventTrendQueries(window);
  const trend = summarizeTrend(
    mergeTrendRows(window, {
      newFailures: ask<TrendRow>(queries.newFailures, streams),
      prepared: ask<TrendRow>(queries.prepared, streams),
      retriedFailed: ask<TrendRow>(queries.retriedFailed, streams),
      succeeded: ask<TrendRow>(queries.succeeded, streams),
    }),
  );
  return {
    summary,
    pressure,
    recoverability,
    retries: mapRetryDistribution(retries).buckets,
    trend,
  };
}

async function stub(
  page: Page,
  documents: Snapshot[] = overviewExecutions(),
  streams: EventStream[] = overviewStreams(documents),
): Promise<SnapshotQueries> {
  await page.addInitScript(() =>
    localStorage.setItem('wow-dashboard-locale', 'en'),
  );
  await page.clock.setFixedTime(OVERVIEW_NOW);
  const queries = await stubExecutionFailedService(page, documents, {
    now: OVERVIEW_NOW,
  });
  await stubExecutionFailedEvents(page, streams, { now: OVERVIEW_NOW });
  return queries;
}

/** A panel's body, by the panel's title. */
function panel(page: Page, title: string): Locator {
  return page.getByRole('group', { name: title, exact: true });
}

/** The one number a metric card reads. */
async function figure(page: Page, title: string): Promise<string> {
  const value = panel(page, title).locator("[data-slot='metric-value']");
  await expect(value).toBeVisible();
  return (await value.innerText()).trim();
}

const count = (value: number) => value.toLocaleString('en-US');

/** A share as the old overview wrote it: one decimal at most. */
const share = (value: number | null) =>
  value === null
    ? null
    : new Intl.NumberFormat('en-US', {
        style: 'percent',
        minimumFractionDigits: 1,
        maximumFractionDigits: 1,
      }).format(value);

test("every figure is the old overview's, over the same documents", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium');
  const documents = overviewExecutions();
  const streams = overviewStreams(documents);
  const old = oldOverview(documents, streams, lastDays(7));
  await stub(page, documents, streams);
  await page.goto('/');

  // The backlog: in range and all (G9), and the three the old page split
  // out of the range.
  expect(await figure(page, 'Active in range')).toBe(
    count(old.summary.selectedInRange),
  );
  expect(await figure(page, 'All active')).toBe(count(old.summary.activeTotal));
  expect(await figure(page, 'Actionable now')).toBe(
    count(old.summary.actionableNow),
  );
  expect(await figure(page, 'Timed out')).toBe(count(old.summary.timedOut));
  expect(await figure(page, 'Unrecoverable')).toBe(
    count(old.summary.unrecoverable),
  );
  // Neither is the same as the other by accident of the data.
  expect(old.summary.selectedInRange).not.toBe(old.summary.activeTotal);

  // The outcomes, off the event streams.
  expect(await figure(page, 'New failures')).toBe(count(old.trend.newFailures));
  expect(await figure(page, 'Prepared')).toBe(count(old.trend.prepared));
  expect(await figure(page, 'Retry failed')).toBe(
    count(old.trend.retriedFailed),
  );
  expect(await figure(page, 'Retry succeeded')).toBe(
    count(old.trend.succeeded),
  );
  expect(await figure(page, 'Net backlog')).toBe(count(old.trend.netBacklog));
  expect(await figure(page, 'Retry success')).toBe(
    share(old.trend.retrySuccess),
  );

  // The recoverability of the active failures in range.
  const recoverability = panel(page, 'Recoverability of active failures');
  for (const [label, key] of [
    ['Recoverable', 'RECOVERABLE'],
    ['Unknown', 'UNKNOWN'],
    ['Unrecoverable', 'UNRECOVERABLE'],
  ])
    await expect(
      recoverability.getByRole('row', { name: new RegExp(`^${label}\\b`) }),
    ).toContainText(count(old.recoverability[key] ?? 0));

  // Their retries, in the old overview's four bands.
  const retries = panel(page, 'Retries of active failures');
  await expect(retries.getByRole('row').nth(1)).toHaveText(
    new RegExp(old.retries.map(({ count: n }) => count(n)).join('\\s*')),
  );

  // The clusters: the same five at most, in the same order, each split by
  // status as the old table split it.
  const clusters = panel(page, 'Failure clusters — top 5');
  const rows = clusters.getByRole('row');
  await expect(rows).toHaveCount(old.pressure.length + 1);
  for (const [index, cluster] of old.pressure.entries()) {
    const cells = rows.nth(index + 1).getByRole('cell');
    await expect(cells.nth(0)).toHaveText(cluster.errorCode);
    await expect(cells.nth(2)).toHaveText(cluster.processorName);
    await expect(cells.nth(5)).toHaveText(count(cluster.currentCount));
    await expect(cells.nth(6)).toHaveText(count(cluster.failedCount));
    await expect(cells.nth(7)).toHaveText(count(cluster.preparedCount));
  }

  // The due-for-retry panel holds what 「可立即处理」 counts.
  await expect(panel(page, 'Needing attention — due for retry')).toBeVisible();
  await expect(
    page.getByText(`${count(old.summary.actionableNow)} records in all`),
  ).toBeVisible();
});

test("a wider window counts as the old overview's did", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium');
  const documents = overviewExecutions();
  const streams = overviewStreams(documents);
  const old = oldOverview(documents, streams, lastDays(30));
  await stub(page, documents, streams);
  await page.goto('/');
  await expect(panel(page, 'Active in range')).toBeVisible();

  const amount = page.getByRole('textbox', { name: 'Time range amount' });
  await amount.fill('30');
  await amount.press('Enter');

  // Each panel runs again under the new window: wait for each to say so.
  const reads = (title: string) =>
    panel(page, title).locator("[data-slot='metric-value']");
  await expect(reads('Active in range')).toHaveText(
    count(old.summary.selectedInRange),
  );
  await expect(reads('Unrecoverable')).toHaveText(
    count(old.summary.unrecoverable),
  );
  await expect(reads('New failures')).toHaveText(count(old.trend.newFailures));
  await expect(reads('Retry success')).toHaveText(
    share(old.trend.retrySuccess)!,
  );
  // The window did widen: the figures are not the default window's.
  expect(old.summary.selectedInRange).not.toBe(
    oldOverview(documents, streams, lastDays(7)).summary.selectedInRange,
  );
});

test('one failed panel leaves the rest of the board', async ({ page }) => {
  const longMessage = `analytics-${'unavailable'.repeat(40)}`;
  await stub(page);
  await page.route('**/execution_failed/snapshot/aggregation', async route => {
    const query = route.request().postDataJSON() as {
      groupBy?: { alias: string }[];
    };
    if (query.groupBy?.some(({ alias }) => alias === 'recoverable'))
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          errorCode: 'Internal',
          errorMsg: longMessage,
        }),
      });
    else await route.fallback();
  });

  await page.goto('/');

  const failed = panel(page, 'Recoverability of active failures').locator(
    "[data-slot='panel-failed']",
  );
  await expect(failed).toBeVisible();
  // Every other panel still answers.
  await expect(
    panel(page, 'Failure clusters — top 5').getByRole('row').nth(1),
  ).toBeVisible();
  expect(await figure(page, 'Actionable now')).toMatch(/^\d+$/);
  // What failed stays inside its own panel, whatever the service said.
  const [card, body] = await Promise.all([
    failed.boundingBox(),
    panel(page, 'Recoverability of active failures').boundingBox(),
  ]);
  expect(card!.x + card!.width).toBeLessThanOrEqual(body!.x + body!.width + 1);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test('each panel shows it is loading until its figures come', async ({
  page,
}) => {
  let release: () => void = () => undefined;
  const pending = new Promise<void>(resolve => {
    release = resolve;
  });
  await stub(page);
  for (const pattern of [
    '**/execution_failed/snapshot/aggregation',
    '**/execution_failed/event/aggregation',
  ])
    await page.route(pattern, async route => {
      await pending;
      await route.fallback();
    });

  try {
    await page.goto('/');
    await expect(
      panel(page, 'Actionable now').locator("[data-slot='panel-loading']"),
    ).toBeVisible();
    await expect(
      panel(page, 'New failures').locator("[data-slot='panel-loading']"),
    ).toBeVisible();
  } finally {
    release();
  }
  await expect(
    panel(page, 'Actionable now').locator("[data-slot='metric-value']"),
  ).toBeVisible();
  await expect(page.locator("[data-slot='panel-loading']")).toHaveCount(0);
});

test('an empty service reads as zeros, and a rate with nothing to rate as none', async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium');
  await stub(page, [], []);
  await page.goto('/');

  for (const title of [
    'Active in range',
    'All active',
    'Actionable now',
    'Timed out',
    'Unrecoverable',
    'New failures',
    'Prepared',
    'Retry failed',
    'Retry succeeded',
    'Net backlog',
  ])
    expect(await figure(page, title), title).toBe('0');
  // No retry ended, so there is no rate: the old overview's 「—」.
  await expect(
    panel(page, 'Retry success').locator("[data-slot='metric-value']"),
  ).not.toHaveText(/\d/);
  await expect(panel(page, 'Needing attention — due for retry')).toContainText(
    'Nothing to show',
  );
});

test('the panels stack on a phone, the cards two to a row, without overlapping or scrolling sideways', async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile-chromium');
  await stub(page);
  await page.goto('/');
  await expect(
    panel(page, 'Actionable now').locator("[data-slot='metric-value']"),
  ).toBeVisible();

  const boxes = await page
    .locator("[data-slot='dashboard-panel']")
    .evaluateAll(panels =>
      panels.map(each => {
        const { top, bottom, left, right } = each.getBoundingClientRect();
        return { top, bottom, left, right };
      }),
    );
  expect(boxes.length).toBeGreaterThan(10);
  // No two panels overlap. The metric cards stand two to a row on a phone
  // (the engine's narrow reading pairs them), everything else one to a row.
  for (let one = 0; one < boxes.length; one++)
    for (let other = one + 1; other < boxes.length; other++) {
      const [a, b] = [boxes[one], boxes[other]];
      const apart =
        a.bottom <= b.top + 1 ||
        b.bottom <= a.top + 1 ||
        a.right <= b.left + 1 ||
        b.right <= a.left + 1;
      expect(apart).toBe(true);
    }
  const rows = new Set(boxes.map(box => Math.round(box.top)));
  expect(rows.size).toBeLessThan(boxes.length);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test('the old overview addresses open the board', async ({ page }) => {
  await stub(page);
  for (const path of ['/dashboard', '/analytics', '/missing-dashboard-route']) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/$/);
    await expect(panel(page, 'Actionable now')).toBeVisible();
  }
});

test('a cluster opens its active failures in the window on the workbench', async ({
  page,
}) => {
  const queries = await stub(page);
  await page.goto('/');

  await panel(page, 'Failure clusters — top 5')
    .getByRole('row')
    .nth(1)
    .getByRole('cell')
    .first()
    .click();

  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aactive$/,
  );
  await expect(page.getByRole('region', { name: 'Active' })).toBeVisible();
  await expect
    .poll(() => JSON.stringify(queries.paged.at(-1)?.filter))
    .toContain('"field":"state.error.errorCode"');
  const sent = JSON.stringify(queries.paged.at(-1)?.filter);
  expect(sent).toContain('"field":"state.function.processorName"');
  expect(sent).toContain('"field":"state.executeAt"');

  // The way back is the board, under the window it was left with.
  await page.getByRole('button', { name: /Compensation overview/ }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(panel(page, 'Actionable now')).toBeVisible();
});

test("a due execution is prepared from the board's own panel", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium');
  const documents = overviewExecutions();
  await stub(page, documents);
  const sent = await stubExecutionFailedCommands(page, documents);
  await page.goto('/');

  const attention = panel(page, 'Needing attention — due for retry');
  const first = attention.getByRole('row').nth(1);
  const id = (await first.getByRole('cell').nth(1).innerText()).trim();
  await first.getByRole('button', { name: 'Prepare' }).click();

  await expect.poll(() => sent.map(({ id: each }) => each)).toEqual([id]);
  expect(sent[0]).toMatchObject({
    command: 'prepare_compensation',
    waitStage: 'SNAPSHOT',
  });
  // The panel reads again: the service's answer is what it shows.
  await expect(
    attention.getByRole('row', { name: new RegExp(id) }),
  ).toContainText('Prepared');
});

test('the board opens in the dashboard workbench', async ({ page }) => {
  await stub(page);
  await page.goto('/');
  await page
    .getByRole('link', { name: 'Open in the dashboard workbench' })
    .click();

  await expect(page).toHaveURL(/\/boards\?view=system%3Aoverview%3Ahome$/);
  await expect(
    page.getByRole('heading', { name: 'Dashboards', level: 1 }),
  ).toBeVisible();
  await expect(panel(page, 'Actionable now')).toBeVisible();
});
