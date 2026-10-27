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

import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ServicesProvider } from "@/app/services.tsx";
import { I18nProvider, type Locale } from "@/i18n.tsx";
import type { OpsServices } from "@/services/ops.ts";
import { parkedDetail } from "./parkedDetail.tsx";
import { ParkedReading } from "./ParkedReading.tsx";
import { failureOf, formatTime, parkedTitle } from "./parkedEntry.ts";

const FAILED_AT = Date.parse("2026-10-01T08:00:00Z");

function rowOf(state: Record<string, unknown>): RecordRow {
  return { key: "k", data: { aggregateId: "k", state } } as RecordRow;
}

const PARKED = rowOf({
  service: "payment-service",
  context: "eshop-payment",
  subscription: "PaymentGatewaySaga.onPaymentRequested",
  aggregate: {
    context: "eshop-payment",
    name: "payment",
    tenantId: "(0)",
    id: "p-1",
  },
  fromVersion: 2,
  fromPosition: 41,
  reason: "java.net.ConnectException: the gateway is unreachable",
  failedAt: FAILED_AT,
  recordedAt: FAILED_AT + 1,
  retries: 3,
  retryAt: FAILED_AT - 60_000,
  retrySpec: {
    maxRetries: 3,
    initialBackoff: "PT1M",
    executionTimeout: "PT2M",
    applied: true,
  },
  recoverable: "RECOVERABLE",
  status: "EXHAUSTED",
  requested: "RESUME",
});

function read(row: RecordRow, locale: Locale = "en") {
  render(
    <I18nProvider initial={locale}>
      <ParkedReading row={row} />
    </I18nProvider>,
  );
  return screen.getByTestId("parked-reading");
}

describe("a parked entry's reading", () => {
  it("says why it is parked, where, how far its retries went and what was asked", () => {
    const reading = read(PARKED);
    const failure = screen.getByTestId("parked-failure");
    expect(failure).toHaveTextContent("Error typejava.net.ConnectException");
    expect(failure).toHaveTextContent("the gateway is unreachable");
    expect(reading).toHaveTextContent("payment-service");
    expect(reading).toHaveTextContent("eshop-payment.payment");
    expect(reading).toHaveTextContent("From position41");
    expect(reading).toHaveTextContent("3 of 3");
    expect(reading).toHaveTextContent("PT1M");
    expect(reading).toHaveTextContent("Applied by an operator");
    expect(reading).toHaveTextContent("Exhausted");
    expect(reading).toHaveTextContent("Recoverable");
    expect(reading).toHaveTextContent("RequestedResume");
    expect(reading).toHaveTextContent("Sent to the owning service");
    expect(reading).not.toHaveTextContent("Outcome");
  });

  it("says how a resolved entry came out", () => {
    const reading = read(
      rowOf({ status: "RESOLVED", outcome: "SKIPPED", resolvedAt: FAILED_AT }),
      "zh-CN",
    );
    expect(reading).toHaveTextContent("已解决");
    expect(reading).toHaveTextContent("已跳过");
    // What the row does not hold reads as nothing, not as a blank.
    expect(reading).toHaveTextContent("—");
  });

  it("says why the owning service refused the last request (D226)", () => {
    const reading = read(
      rowOf({
        refusedAction: "wow.ledger.Skip",
        refusalCode: "wow.auth.forbidden",
        refusalReason: "Taken from the ops context's saga alone.",
      }),
    );
    expect(reading).toHaveTextContent("Refused by the owning service");
    expect(reading).toHaveTextContent("Refused actionSkip");
    expect(reading).toHaveTextContent("Refusal codewow.auth.forbidden");
    expect(reading).toHaveTextContent("Taken from the ops context's saga alone.");
    expect(screen.getByTestId("parked-refusal")).toHaveTextContent(
      "did not run the operator's last request",
    );
  });

  it("shows no refusal when there is none", () => {
    read(PARKED);
    expect(screen.queryByTestId("parked-refusal")).toBeNull();
  });

  it("reads a cause that names no type as its own type", () => {
    read(rowOf({ reason: "The gateway is down." }));
    const failure = screen.getByTestId("parked-failure");
    expect(failure).toHaveTextContent("Error typeThe gateway is down.");
    expect(failure.querySelector("pre")).toBeNull();
  });

  it("copies the whole cause", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    read(PARKED);
    await userEvent.click(
      screen.getByRole("button", { name: "Copy the cause" }),
    );
    expect(writeText).toHaveBeenCalledWith(
      "java.net.ConnectException: the gateway is unreachable",
    );
  });

  it("reads a value it has no word for as it is", () => {
    expect(
      read(rowOf({ status: "PAUSED", outcome: "LATER" })),
    ).toHaveTextContent("LATER");
  });
});

describe("the detail's title", () => {
  it("is the subscription on the aggregate", () => {
    expect(parkedTitle(PARKED)).toBe(
      "PaymentGatewaySaga.onPaymentRequested · p-1",
    );
    expect(parkedTitle(rowOf({ subscription: "Saga" }))).toBe("Saga");
    expect(parkedTitle(rowOf({}))).toBeUndefined();
    expect(parkedDetail.title).toBe(parkedTitle);
  });

  it("renders the reading and the entry's history", async () => {
    const history = vi.fn(async () => []);
    render(
      <I18nProvider initial="en">
        <ServicesProvider services={{ history } as unknown as OpsServices}>
          {parkedDetail.render?.({
            row: PARKED,
            complete: true,
            refresh: () => {},
            runtime: undefined as never,
          })}
        </ServicesProvider>
      </I18nProvider>,
    );
    expect(screen.getByTestId("parked-reading")).toBeVisible();
    expect(await screen.findByText("No history recorded.")).toBeVisible();
    expect(history).toHaveBeenCalledWith("k");
  });
});

describe("a time", () => {
  it("is written in the language in force, or not at all", () => {
    expect(formatTime(FAILED_AT, "en")).toMatch(/2026/);
    expect(formatTime(null, "en")).toBeUndefined();
    expect(formatTime(Number.NaN, "en")).toBeUndefined();
  });
});

describe("a failure", () => {
  it("is the type before the first colon and the message after it", () => {
    expect(failureOf("a.B: c: d")).toEqual({ type: "a.B", message: "c: d" });
    expect(failureOf("plain")).toEqual({ type: "plain", message: "" });
  });
});
