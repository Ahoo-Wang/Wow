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
import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import { actionHarness } from "@ahoo-wang/wow-view-engine/testing";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ORDER_ACTIONS } from "@/app/testing.tsx";
import {
  bodyOf,
  fetchReferenceActions,
  formOf,
  REFERENCE_WORDS,
  referenceActions,
  referenceSend,
  type CommandInput,
} from "./referenceActions.ts";

function row(key: string, customer: string | null): RecordRow {
  return { key, data: { id: key, customer } } as RecordRow;
}

const ROWS = [row("o-1", "c-1"), row("o-2", null)];

const INPUTS: CommandInput[] = [
  { name: "reason", type: "string", required: true, description: "Why" },
  { name: "days", type: "integer", required: false },
  { name: "rate", type: "number", required: false },
  { name: "notify", type: "boolean", required: false },
  {
    name: "until",
    type: "string",
    required: true,
    nullable: true,
    options: ["WEEK", "EVER"],
  },
];

describe("the row actions on referenced aggregates", () => {
  it("draw each input as its type asks", () => {
    expect(formOf(INPUTS)).toEqual({
      reason: { label: "Why", required: true },
      days: { label: "days", required: false, input: "number" },
      rate: { label: "rate", required: false, input: "number" },
      notify: { label: "notify", required: false, input: "boolean" },
      until: {
        label: "until",
        required: true,
        options: [
          { value: "WEEK", label: "WEEK" },
          { value: "EVER", label: "EVER" },
        ],
      },
    });
  });

  it("send what the form gives, each value as its input's type", () => {
    expect(
      bodyOf(INPUTS, {
        reason: "spam",
        days: "3",
        rate: 1.5,
        notify: true,
        until: "WEEK",
      }),
    ).toEqual({
      reason: "spam",
      days: 3,
      rate: 1.5,
      notify: true,
      until: "WEEK",
    });
    expect(
      bodyOf(INPUTS, { reason: "spam", days: "", notify: "false" }),
    ).toEqual({
      reason: "spam",
      notify: false,
      until: null,
    });
  });

  it("offer a field's commands on the rows that refer to an aggregate", async () => {
    const send = vi.fn(async () => ({}));
    const harness = actionHarness(referenceActions(ORDER_ACTIONS, send), ROWS);
    expect(harness.ids).toEqual(["customer.SuspendCustomer"]);
    expect(harness.state("customer.SuspendCustomer", "o-1").available).toBe(
      true,
    );
    expect(harness.state("customer.SuspendCustomer", "o-2")).toMatchObject({
      available: false,
      reason: "This row refers to no crm.customer.",
    });
    expect(
      harness.form("customer.SuspendCustomer")?.map((field) => field.name),
    ).toEqual(["reason", "days"]);
    await harness.run("customer.SuspendCustomer", "o-1", {
      reason: "spam",
      days: 2,
    });
    expect(send).toHaveBeenCalledWith(
      "shop.order_wide",
      "customer",
      "c-1",
      "SuspendCustomer",
      {
        reason: "spam",
        days: 2,
      },
    );
  });

  it("speak the console's language, and take no form without inputs", () => {
    const actions = referenceActions(
      {
        ...ORDER_ACTIONS,
        fields: [
          {
            ...ORDER_ACTIONS.fields[0],
            commands: [{ name: "Wake", inputs: [] }],
          },
        ],
      },
      vi.fn(),
      REFERENCE_WORDS["zh-CN"],
    );
    const harness = actionHarness(actions, ROWS);
    expect(harness.form("customer.Wake")).toBeNull();
    expect(harness.state("customer.Wake", "o-2")).toMatchObject({
      reason: "此行没有引用 crm.customer。",
    });
  });

  it("say the owning service's refusal in its words", async () => {
    const send = vi.fn(async () => {
      throw new WowProblemError({
        type: "urn:wow:error:wow.auth.forbidden",
        title: "Forbidden",
        status: 403,
        detail: "mary may not suspend a customer.",
        code: "wow.auth.forbidden",
      });
    });
    const harness = actionHarness(referenceActions(ORDER_ACTIONS, send), ROWS);
    await expect(
      harness.run("customer.SuspendCustomer", "o-1", { reason: "x" }),
    ).rejects.toThrow("mary may not suspend a customer. (wow.auth.forbidden)");
  });
});

describe("the catalog's relay", () => {
  afterEach(() => vi.unstubAllGlobals());

  function service(status: number, body: unknown) {
    const sent: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        sent.push(new Request(input, init));
        return new Response(JSON.stringify(body), {
          status,
          headers: {
            "Content-Type":
              status < 300 ? "application/json" : "application/problem+json",
          },
        });
      }),
    );
    return sent;
  }

  const fetcher = () => new Fetcher({ baseURL: "http://catalog.test" });

  it("lists a dataset's row actions, and sends one of them", async () => {
    const sent = service(200, ORDER_ACTIONS);
    expect(await fetchReferenceActions(fetcher(), "shop.order_wide")).toEqual(
      ORDER_ACTIONS,
    );
    await referenceSend(fetcher())(
      "shop.order_wide",
      "customer",
      "c 1",
      "SuspendCustomer",
      { reason: "x" },
    );
    expect(
      sent.map((request) => [request.method, new URL(request.url).pathname]),
    ).toEqual([
      ["GET", "/analytics-catalog/datasets/shop.order_wide/reference-actions"],
      [
        "POST",
        "/analytics-catalog/datasets/shop.order_wide/rows/customer/c%201/SuspendCustomer",
      ],
    ]);
    expect(await sent[1].json()).toEqual({ reason: "x" });
  });

  it("reads a refusal as the problem it is", async () => {
    service(400, {
      type: "urn:wow:error:wow.command.unhandled",
      title: "Bad Request",
      status: 400,
      code: "wow.command.unhandled",
    });
    await expect(
      fetchReferenceActions(fetcher(), "shop.order_wide"),
    ).rejects.toBeInstanceOf(WowProblemError);
    await expect(
      referenceSend(fetcher())(
        "shop.order_wide",
        "customer",
        "c-1",
        "Burn",
        {},
      ),
    ).rejects.toMatchObject({ code: "wow.command.unhandled" });
  });
});
