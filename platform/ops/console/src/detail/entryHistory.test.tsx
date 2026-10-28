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

import { Fetcher } from "@ahoo-wang/fetcher";
import type { EventStreamRecord } from "@ahoo-wang/wow-client";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { I18nProvider, type Locale } from "@/i18n.tsx";
import { ParkedHistory } from "./ParkedHistory.tsx";
import {
  HISTORY_LIMIT,
  historyOf,
  historySource,
} from "./entryHistory.ts";

const AT = Date.parse("2026-10-01T08:00:00Z");

const SPEC = { maxRetries: 3, initialBackoff: "PT1M", timeout: "PT2M" };

function entry(extra: Record<string, unknown> = {}) {
  return {
    service: "payment-service",
    context: "shop",
    subscription: "Saga.onEvent",
    aggregate: { context: "shop", name: "payment", tenantId: "(0)", id: "p-1" },
    fromVersion: 2,
    reason: "java.net.ConnectException: refused",
    failedAt: AT,
    retries: 0,
    retrySpec: SPEC,
    recoverable: "unknown",
    status: "scheduled",
    recordedAt: AT,
    ...extra,
  };
}

function stream(
  version: number,
  name: string,
  body: unknown,
  operator?: string,
): EventStreamRecord {
  return {
    aggregateId: "id",
    tenantId: "(0)",
    version,
    commitTime: AT + version * 60_000,
    commandId: `c-${version}`,
    requestId: `r-${version}`,
    commandType: "t",
    ...(operator
      ? { operator: { id: operator, tenantId: "(0)", crossScope: false } }
      : {}),
    events: [{ name: `ops.parked.${name}`, revision: 1, body }],
  };
}

/** One entry's life: parked, retried, an operator's requests, parked again, resolved. */
const STREAMS: EventStreamRecord[] = [
  stream(1, "ParkedRecorded", { entry: entry() }),
  stream(2, "ParkedRecorded", { entry: entry({ retries: 1 }) }),
  stream(3, "ParkedRecorded", {
    entry: entry({
      retries: 1,
      status: "exhausted",
      recoverable: "recoverable",
    }),
  }),
  stream(
    4,
    "ApplyRetrySpecRequested",
    { maxRetries: 5, initialBackoff: "2m" },
    "alice",
  ),
  stream(
    5,
    "MarkRecoverableRequested",
    { recoverable: "unrecoverable" },
    "alice",
  ),
  stream(6, "SkipRequested", {}, "bob"),
  stream(7, "ResumeRequested", { reason: "The gateway is back." }, "alice"),
  stream(8, "ParkedRecorded", {
    entry: entry({
      failedAt: AT + 1,
      reason: "java.lang.IllegalStateException: closed",
    }),
  }),
  stream(9, "ParkedResolved", { outcome: "resumed", resolvedAt: AT + 2 }),
  stream(10, "SomethingNew", {}),
];

/** A system event the kernel commits beside the entry's own (wow-client's SystemEvent). */
const TAGGED: EventStreamRecord = {
  ...stream(11, "unused", {}),
  events: [
    { name: "wow.lifecycle.TagsApplied", revision: 1, body: { tags: {} } },
  ],
};

describe("an entry's history", () => {
  it("reads each event as a step, newest first", () => {
    const items = historyOf([...STREAMS].reverse());
    expect(items.map((item) => item.kind)).toEqual([
      "unknown",
      "resolved",
      "parkedAgain",
      "resumeRequested",
      "skipRequested",
      "markRecoverableRequested",
      "applyRetrySpecRequested",
      "updated",
      "retried",
      "parked",
    ]);
    const byVersion = Object.fromEntries(
      items.map((item) => [item.version, item]),
    );
    expect(byVersion[1].facts).toEqual({
      status: "scheduled",
      retries: 0,
      maxRetries: 3,
      reason: "java.net.ConnectException: refused",
    });
    expect(byVersion[2].facts).toEqual({
      status: "scheduled",
      retries: 1,
      maxRetries: 3,
    });
    expect(byVersion[3].facts.recoverable).toBe("recoverable");
    expect(byVersion[4].facts).toEqual({ maxRetries: 5, initialBackoff: "2m" });
    expect(byVersion[4].operator).toBe("alice");
    expect(byVersion[6].facts).toEqual({});
    expect(byVersion[7].facts.reason).toBe("The gateway is back.");
    expect(byVersion[9].facts).toEqual({ outcome: "resumed" });
    expect(byVersion[10].name).toBe("ops.parked.SomethingNew");
    expect(byVersion[1].at).toBe(AT + 60_000);
  });

  it("reads an owning service's refusal of the last request (D226)", () => {
    const [refused] = historyOf([
      stream(8, "ActionRefused", {
        action: "wow.ledger.Resume",
        code: "wow.auth.forbidden",
        reason: "Taken from the ops context's saga alone.",
      }),
    ]);
    expect(refused).toMatchObject({
      kind: "actionRefused",
      facts: {
        refusedAction: "wow.ledger.Resume",
        refusalCode: "wow.auth.forbidden",
        reason: "Taken from the ops context's saga alone.",
      },
    });
  });

  it("matches the event names the server sends, system events included", () => {
    const [tagged] = historyOf([TAGGED]);
    expect(tagged).toMatchObject({
      kind: "unknown",
      name: "wow.lifecycle.TagsApplied",
    });
    // The short name is not what the server sends: it is not read as a step.
    const short = historyOf([
      {
        ...STREAMS[0],
        events: [{ ...STREAMS[0].events[0], name: "ParkedRecorded" }],
      },
    ]);
    expect(short[0].kind).toBe("unknown");
  });

  it("is read from the entry's event streams", async () => {
    const requests: Request[] = [];
    const fetcher = new Fetcher({ baseURL: "http://ops.test" });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        requests.push(new Request(input, init));
        return new Response(JSON.stringify(STREAMS.slice(0, 1)), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }),
    );
    try {
      const streams = await historySource(fetcher)("ops-id");
      expect(streams).toHaveLength(1);
      const request = requests[0];
      expect(request.url).toBe("http://ops.test/ops/parked/events/query");
      expect(await request.json()).toMatchObject({
        kind: "list",
        limit: HISTORY_LIMIT,
        filter: { field: "aggregateId", value: "ops-id" },
      });
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

function timeline(
  source: (id: string) => Promise<EventStreamRecord[]>,
  locale: Locale = "en",
) {
  render(
    <I18nProvider initial={locale}>
      <ParkedHistory id="ops-id" source={source} />
    </I18nProvider>,
  );
  return screen.getByTestId("parked-history");
}

describe("an entry's timeline", () => {
  it("says what happened, when, and who asked", async () => {
    const section = timeline(async () => STREAMS);
    expect(section).toHaveTextContent("Reading the entry's history…");
    const steps = await within(section).findAllByRole("listitem");
    expect(steps).toHaveLength(STREAMS.length);
    expect(steps[1]).toHaveTextContent("Resolved");
    expect(steps[1]).toHaveTextContent("Resumed");
    expect(steps[2]).toHaveTextContent("Parked again");
    expect(steps[2]).toHaveTextContent(
      "java.lang.IllegalStateException: closed",
    );
    expect(steps[3]).toHaveTextContent("Resume requested");
    expect(steps[3]).toHaveTextContent("by alice");
    expect(steps[5]).toHaveTextContent("Unrecoverable");
    expect(steps[6]).toHaveTextContent("Max retries 5");
    expect(steps[6]).toHaveTextContent("Initial backoff 2m");
    expect(steps[8]).toHaveTextContent("Retry recorded");
    expect(steps[8]).toHaveTextContent("Attempts 1 of 3");
    expect(steps[9]).toHaveTextContent("Parked");
    expect(steps[9]).toHaveTextContent("v1");
    expect(steps[0]).toHaveTextContent("SomethingNew");
  });

  it("says when there is none, in Chinese too", async () => {
    const section = timeline(async () => [], "zh-CN");
    expect(await within(section).findByText("没有记录历史。")).toBeVisible();
  });

  it("says why it could not be read", async () => {
    const section = timeline(async () => {
      throw new Error("403 Forbidden");
    });
    expect(await within(section).findByRole("alert")).toHaveTextContent(
      "The history could not be read: 403 Forbidden",
    );
  });

  it("says a failure that is no error as it is", async () => {
    const section = timeline(() => Promise.reject("gone"));
    expect(await within(section).findByRole("alert")).toHaveTextContent("gone");
  });
});
