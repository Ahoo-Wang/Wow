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
import { WowProblemError } from "@ahoo-wang/wow-client";
import { afterEach, describe, expect, it, vi } from "vitest";
import { parkedCommands, problemText } from "./parkedCommands.ts";

const ID = "payment-service:Saga.onEvent:eshop-payment.payment:(0):p-1";

interface Sent {
  url: string;
  method: string;
  headers: Headers;
  body: unknown;
}

/** The service as `fetch` answers it, and what was sent to it. */
function service(answer: () => Response): Sent[] {
  const sent: Sent[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(input, init);
      sent.push({
        url: new URL(request.url).pathname,
        method: request.method,
        headers: request.headers,
        body: await request.json().catch(() => undefined),
      });
      return answer();
    }),
  );
  return sent;
}

const OK = () =>
  new Response(
    JSON.stringify({
      id: "c-1",
      requestId: "r-1",
      outcome: "SUCCEEDED",
      stage: "SNAPSHOT",
      ref: { context: "ops", aggregate: "parked", tenantId: "(0)", id: ID },
      version: 2,
    }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );

function commands() {
  return parkedCommands(new Fetcher({ baseURL: "http://ops.test" }));
}

afterEach(() => vi.unstubAllGlobals());

describe("the operator's commands", () => {
  it("resume and skip the entry with a reason, waiting for its snapshot", async () => {
    const sent = service(OK);
    await commands().resume(ID, "  The gateway is back. ");
    await commands().skip(ID, "   ");
    expect(
      sent.map(({ url, method }) => [method, decodeURIComponent(url)]),
    ).toEqual([
      ["POST", `/ops/parked/${ID}/resume`],
      ["POST", `/ops/parked/${ID}/skip`],
    ]);
    expect(sent[0].body).toEqual({ reason: "The gateway is back." });
    expect(sent[1].body).toEqual({});
    expect(sent[0].headers.get("Wow-Wait")).toContain("snapshot");
  });

  it("mark the entry recoverable and apply a retry spec", async () => {
    const sent = service(OK);
    await commands().markRecoverable(ID, "UNRECOVERABLE");
    await commands().applyRetrySpec(ID, { maxRetries: 5, initialBackoff: "1m" });
    expect(sent.map(({ url }) => url.split("/").pop())).toEqual([
      "mark-recoverable",
      "apply-retry-spec",
    ]);
    expect(sent[0].body).toEqual({ recoverable: "UNRECOVERABLE" });
    expect(sent[1].body).toEqual({ maxRetries: 5, initialBackoff: "1m" });
  });

  it("reject with the service's problem", async () => {
    service(
      () =>
        new Response(
          JSON.stringify({
            type: "urn:wow:error:ops.parked.resolved",
            title: "Conflict",
            status: 409,
            detail: "Saga.onEvent no longer parks p-1.",
            code: "ops.parked.resolved",
          }),
          {
            status: 409,
            headers: { "Content-Type": "application/problem+json" },
          },
        ),
    );
    const error = await commands()
      .skip(ID)
      .catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(WowProblemError);
    expect(problemText(error)).toBe(
      "Saga.onEvent no longer parks p-1. (ops.parked.resolved)",
    );
  });
});

describe("a problem's words", () => {
  it("are its detail, its violations and its code", () => {
    const error = new WowProblemError({
      type: "urn:wow:error:ops.parked.invalid",
      title: "Unprocessable",
      status: 422,
      detail: "Not a duration.",
      code: "ops.parked.invalid",
      violations: [
        { path: "initialBackoff", code: "INVALID", message: "PT3M or 3m." },
      ],
    });
    expect(problemText(error)).toBe(
      "Not a duration. PT3M or 3m. (ops.parked.invalid)",
    );
  });

  it("are the title, or the status, without a detail or a code", () => {
    expect(
      problemText(
        new WowProblemError({
          type: "about:blank",
          title: "Bad Gateway",
          status: 502,
        }),
      ),
    ).toBe("Bad Gateway");
  });

  it("are an error's message otherwise", () => {
    expect(problemText(new TypeError("Failed to fetch"))).toBe(
      "Failed to fetch",
    );
    expect(problemText("refused")).toBe("refused");
  });
});
