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

import { describe, expect, it } from "vitest";
import {
  createClusterHref,
  createExecutionWindowHref,
  executionsHref,
  parseClusterScope,
  parseExecutionWindow,
  readLinkScope,
} from "./linkScope.ts";

const window = { end: 1_787_932_800_000, start: 1_787_328_000_000 };

const cluster = {
  errorCode: "E & 1",
  contextName: "billing",
  processorName: "Processor",
  functionName: "run",
  functionKind: "EVENT",
  start: 1000,
  end: 2000,
};

describe("executionsHref", () => {
  it("names the view first and keeps every other parameter", () => {
    expect(
      executionsHref("v", new URLSearchParams("id=EF-1&view=old&start=1")),
    ).toBe("/executions?view=v&id=EF-1&start=1");
  });
});

describe("cluster links", () => {
  it("open the Active view with the full cluster identity", () => {
    const url = new URL(
      createClusterHref(cluster, cluster),
      "http://localhost",
    );
    expect(url.pathname).toBe("/executions");
    expect(url.searchParams.get("view")).toBe("system:execution-failed:active");
    expect(parseClusterScope(url.searchParams.get("cluster"))).toEqual(cluster);
  });

  it("rejects a malformed or inverted cluster instead of querying everything", () => {
    expect(parseClusterScope(null)).toBeNull();
    for (const value of [
      "{",
      "null",
      "{}",
      JSON.stringify({ ...cluster, end: 500 }),
      JSON.stringify({ ...cluster, start: null }),
      JSON.stringify({ ...cluster, errorCode: 1 }),
    ])
      expect(parseClusterScope(value)).toBeUndefined();
  });
});

describe("execution window links", () => {
  it("keep the applied range on the view they open", () => {
    expect(createExecutionWindowHref("next-retry", window)).toBe(
      `/executions?view=system%3Aexecution-failed%3Anext-retry&start=${window.start}&end=${window.end}`,
    );
  });

  it("read a valid window back", () => {
    const href = createExecutionWindowHref("active", window);
    expect(
      parseExecutionWindow(new URL(href, "http://localhost").searchParams),
    ).toEqual(window);
  });

  it("treat absent parameters as no window", () => {
    expect(parseExecutionWindow(new URLSearchParams("id=abc"))).toBeUndefined();
  });

  it.each([
    ["missing end", "start=1787328000000"],
    ["missing start", "end=1787932800000"],
    ["non numeric", "start=abc&end=1787932800000"],
    ["fractional", "start=1787328000000.5&end=1787932800000"],
    ["negative start", "start=-1&end=1787932800000"],
    ["empty start", "start=&end=1787932800000"],
    ["whitespace start", "start=%20&end=1787932800000"],
    ["empty window", "start=1787328000000&end=1787328000000"],
    ["inverted window", "start=1787932800000&end=1787328000000"],
  ])("rejects a malformed window (%s)", (_label, query) => {
    expect(parseExecutionWindow(new URLSearchParams(query))).toBeNull();
  });
});

describe("readLinkScope", () => {
  it("is nothing without a cluster or a window", () => {
    expect(readLinkScope(new URLSearchParams("id=EF-1"))).toEqual({
      kind: "none",
    });
  });

  it("reads a window as executeAt up to the millisecond before its end", () => {
    const params = new URLSearchParams({
      start: String(window.start),
      end: String(window.end),
    });
    expect(readLinkScope(params)).toEqual({
      kind: "scoped",
      filter: {
        op: "and",
        children: [
          {
            field: "state.executeAt",
            operator: "BETWEEN",
            value: {
              type: "absolute",
              from: new Date(window.start).toISOString(),
              to: new Date(window.end - 1).toISOString(),
            },
          },
        ],
      },
    });
  });

  it("reads a cluster as its five fields and its window", () => {
    const scope = readLinkScope(
      new URLSearchParams({ cluster: JSON.stringify(cluster) }),
    );
    expect(scope.kind).toBe("scoped");
    if (scope.kind !== "scoped") return;
    expect(scope.filter.children).toEqual([
      { field: "state.error.errorCode", operator: "EQ", value: "E & 1" },
      { field: "state.function.contextName", operator: "EQ", value: "billing" },
      {
        field: "state.function.processorName",
        operator: "EQ",
        value: "Processor",
      },
      { field: "state.function.name", operator: "EQ", value: "run" },
      {
        field: "state.function.functionKind",
        operator: "IN",
        value: ["EVENT"],
      },
      {
        field: "state.executeAt",
        operator: "BETWEEN",
        value: {
          type: "absolute",
          from: new Date(1000).toISOString(),
          to: new Date(1999).toISOString(),
        },
      },
    ]);
  });

  it("says which parameter it cannot read", () => {
    expect(readLinkScope(new URLSearchParams("cluster=%7B"))).toEqual({
      kind: "invalid",
      parameter: "cluster",
    });
    expect(readLinkScope(new URLSearchParams("start=2&end=1"))).toEqual({
      kind: "invalid",
      parameter: "window",
    });
  });
});
