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

import { RecoverableType } from "@ahoo-wang/wow-client";
import { describe, expect, it, vi } from "vitest";
import type { ExecutionFailedCommandClient } from "@/generated";
import { executionCommands } from "./executionCommands.ts";

function client(result: Record<string, unknown> = { errorCode: "Ok" }) {
  const answer = vi.fn(() => Promise.resolve(result));
  return {
    fake: {
      prepareCompensation: answer,
      forcePrepareCompensation: answer,
      markRecoverable: answer,
    } as unknown as ExecutionFailedCommandClient,
    answer,
  };
}

const SNAPSHOT = { headers: { "Command-Wait-Stage": "SNAPSHOT" } };

describe("executionCommands", () => {
  it("sends each command and waits until the snapshot reflects it", async () => {
    const { fake, answer } = client();
    const commands = executionCommands(fake);
    await commands.prepare("EF-1");
    await commands.forcePrepare("EF-2");
    await commands.markRecoverable("EF-3", RecoverableType.UNRECOVERABLE);
    expect(answer.mock.calls).toEqual([
      ["EF-1", SNAPSHOT],
      ["EF-2", SNAPSHOT],
      [
        "EF-3",
        { ...SNAPSHOT, body: { recoverable: RecoverableType.UNRECOVERABLE } },
      ],
    ]);
  });

  it("throws a result that came back refused, in its own words", async () => {
    await expect(
      executionCommands(
        client({ errorCode: "IllegalState", errorMsg: "can not retry" }).fake,
      ).prepare("EF-1"),
    ).rejects.toThrow("can not retry");
    await expect(
      executionCommands(client({ errorCode: "IllegalState" }).fake).prepare(
        "EF-1",
      ),
    ).rejects.toThrow("IllegalState");
  });
});
