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
import { momentsOf, repeatedFailures } from "./attempts.ts";

function stream(version: number, name: string, body: object = {}) {
  return { version, createTime: version * 1_000, body: [{ name, body }] };
}

const failed = (code: string) => ({ error: { errorCode: code } });

describe("momentsOf", () => {
  it("pairs each preparation with its outcome, the newest first, in any order", () => {
    const moments = momentsOf([
      stream(3, "execution_failed_applied", failed("A")),
      stream(1, "execution_failed_created", failed("A")),
      stream(4, "compensation_prepared"),
      stream(2, "compensation_prepared"),
      stream(5, "execution_success_applied"),
    ]);
    expect(moments).toEqual([
      {
        kind: "attempt",
        number: 2,
        at: 4_000,
        endedAt: 5_000,
        outcome: "succeeded",
        errorCode: "",
      },
      {
        kind: "attempt",
        number: 1,
        at: 2_000,
        endedAt: 3_000,
        outcome: "failed",
        errorCode: "A",
      },
      { kind: "created", at: 1_000, errorCode: "A" },
    ]);
  });

  it("keeps an attempt still out as running, and an operator's changes in place", () => {
    const moments = momentsOf([
      stream(1, "execution_failed_created", failed("A")),
      stream(2, "function_changed"),
      stream(3, "retry_spec_applied"),
      stream(4, "recoverable_marked", { recoverable: "UNRECOVERABLE" }),
      stream(5, "compensation_prepared"),
    ]);
    expect(moments.map((moment) => moment.kind)).toEqual([
      "attempt",
      "recoverableMarked",
      "retrySpecApplied",
      "functionChanged",
      "created",
    ]);
    expect(moments[0]).toMatchObject({ outcome: "running", endedAt: null });
  });

  it("reads a stream without its events as nothing", () => {
    expect(momentsOf([{ version: 1 }, {}])).toEqual([]);
  });
});

describe("repeatedFailures", () => {
  const story = (...names: [string, object?][]) =>
    momentsOf(
      names.map(([name, body], index) => stream(index + 1, name, body)),
    );

  it("counts the latest attempts that failed the same way, one after another", () => {
    const moments = story(
      ["execution_failed_created", failed("A")],
      ["compensation_prepared"],
      ["execution_failed_applied", failed("B")],
      ["compensation_prepared"],
      ["execution_failed_applied", failed("A")],
      ["compensation_prepared"],
      ["execution_failed_applied", failed("A")],
      // One out now does not break the run.
      ["compensation_prepared"],
    );
    expect(repeatedFailures(moments, "A")).toBe(2);
    expect(repeatedFailures(moments, "B")).toBe(0);
    expect(repeatedFailures(moments, "")).toBe(0);
  });

  it("starts again once the function changed: the next attempt runs something else", () => {
    const moments = story(
      ["compensation_prepared"],
      ["execution_failed_applied", failed("A")],
      ["function_changed"],
      ["compensation_prepared"],
      ["execution_failed_applied", failed("A")],
    );
    expect(repeatedFailures(moments, "A")).toBe(1);
  });
});
