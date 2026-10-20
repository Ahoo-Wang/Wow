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
import { WowProblemError } from "@ahoo-wang/wow-client/v10";
import { afterEach, describe, expect, it, vi } from "vitest";
import { catalogCommands, problemText } from "./catalogCommands.ts";

interface Sent {
  url: string;
  method: string;
  headers: Headers;
  body: unknown;
}

/** The catalog as `fetch` answers it, and what was sent to it. */
function service(answer: () => Response): Sent[] {
  const sent: Sent[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(input, init);
      sent.push({
        url: decodeURIComponent(new URL(request.url).pathname),
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
      ref: {
        context: "analytics-catalog",
        aggregate: "source",
        tenantId: "(platform)",
        id: "x",
      },
      version: 2,
    }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );

function commands() {
  return catalogCommands(new Fetcher({ baseURL: "http://catalog.test" }));
}

afterEach(() => vi.unstubAllGlobals());

describe("the data owner's commands", () => {
  it("send each command of a source to its route, waiting for its snapshot", async () => {
    const sent = service(OK);
    const id = "default:shop:orders";
    await commands().draftSource("shop", "orders", id);
    await commands().reviseSource(id, {
      tenantColumn: "tenant_id",
      platform: false,
    });
    await commands().reviewSensitivity(id, [
      { path: "buyer", level: "DISPLAY" },
    ]);
    await commands().markReviewed(id);
    await commands().approveSource(id);
    await commands().approveSensitivityChange(id);
    expect(
      sent.map(({ method, url }) => [method, url.split("/").pop()]),
    ).toEqual([
      ["POST", "draft-source"],
      ["POST", "revise-source"],
      ["POST", "review-source-sensitivity"],
      ["POST", "mark-source-reviewed"],
      ["POST", "approve-source"],
      ["POST", "approve-sensitivity-change"],
    ]);
    expect(sent[0].url).toContain(id);
    expect(sent[0].body).toEqual({ database: "shop", table: "orders" });
    expect(sent[2].body).toEqual({
      fields: [{ path: "buyer", level: "DISPLAY" }],
    });
    expect(sent[0].headers.get("Wow-Wait")).toContain("snapshot");
  });

  it("send each command of a dataset to its route, a blank title left out", async () => {
    const sent = service(OK);
    const definition = { subject: "o", sources: [], columns: [] };
    await commands().draftDataset("shop", "order_wide", definition, "  ");
    await commands().reviseDataset("shop.order_wide", definition, " Orders ");
    await commands().publish("shop.order_wide");
    await commands().approvePublication("shop.order_wide");
    await commands().refusePublication("shop.order_wide", "Not yet.");
    await commands().rollback("shop.order_wide", 1);
    await commands().retire("shop.order_wide");
    expect(sent.map(({ url }) => url.split("/").pop())).toEqual([
      "draft-dataset",
      "revise-dataset",
      "publish-dataset",
      "approve-dataset-publication",
      "refuse-dataset-publication",
      "rollback-dataset",
      "retire-dataset",
    ]);
    expect(sent[0].url).toContain("shop.order_wide");
    expect(sent[0].body).toEqual({
      context: "shop",
      name: "order_wide",
      definition,
    });
    expect(sent[1].body).toEqual({ definition, title: "Orders" });
    expect(sent[4].body).toEqual({ reason: "Not yet." });
    expect(sent[5].body).toEqual({ version: 1 });
  });

  it("say the catalog's refusal in its words and its code", async () => {
    service(
      () =>
        new Response(
          JSON.stringify({
            type: "about:blank",
            title: "Bad Request",
            status: 400,
            detail: "nothing is not a column.",
            code: "analytics.catalog.definition-invalid",
          }),
          {
            status: 400,
            headers: { "Content-Type": "application/problem+json" },
          },
        ),
    );
    const error = await commands()
      .publish("shop.order_wide")
      .catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(WowProblemError);
    expect(problemText(error)).toBe(
      "nothing is not a column. (analytics.catalog.definition-invalid)",
    );
    expect(problemText(new Error("offline"))).toBe("offline");
    expect(problemText("odd")).toBe("odd");
  });
});
