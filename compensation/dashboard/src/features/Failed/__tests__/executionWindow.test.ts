/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)]
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
  createExecutionWindowHref,
  executionWindowCondition,
  parseExecutionWindow,
} from "../executionWindow.ts";

describe("executionWindow", () => {
  const window = { end: 1_787_932_800_000, start: 1_787_328_000_000 };

  it("keeps the applied range in the queue link", () => {
    expect(createExecutionWindowHref("/next-retry", window)).toBe(
      `/next-retry?start=${window.start}&end=${window.end}`,
    );
  });

  it("reads a valid window back", () => {
    expect(
      parseExecutionWindow(new URLSearchParams(createExecutionWindowHref("", window).slice(1))),
    ).toEqual(window);
  });

  it("treats absent parameters as no window", () => {
    expect(parseExecutionWindow(new URLSearchParams("id=abc"))).toBeUndefined();
  });

  it.each([
    ["missing end", "start=1787328000000"],
    ["missing start", "end=1787932800000"],
    ["non numeric", "start=abc&end=1787932800000"],
    ["fractional", "start=1787328000000.5&end=1787932800000"],
    ["negative start", "start=-1&end=1787932800000"],
    ["empty window", "start=1787328000000&end=1787328000000"],
    ["inverted window", "start=1787932800000&end=1787328000000"],
  ])("rejects a malformed window (%s)", (_label, query) => {
    expect(parseExecutionWindow(new URLSearchParams(query))).toBeNull();
  });

  it("filters the destination by the same executeAt bounds", () => {
    expect(executionWindowCondition(window)).toEqual({
      op: "AND",
      operands: [
        { op: "GTE", field: "state.executeAt", value: window.start },
        { op: "LT", field: "state.executeAt", value: window.end },
      ],
    });
  });
});
