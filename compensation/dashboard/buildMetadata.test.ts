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

import { describe, expect, it, vi } from "vitest";
import {
  UNKNOWN_COMMIT_SHA,
  resolveCommitSha,
} from "./buildMetadata.ts";

const COMMIT_SHA = "abcdef0123456789abcdef0123456789abcdef01";

describe("resolveCommitSha", () => {
  it("prefers explicitly provided build metadata", () => {
    const readGitCommit = vi.fn();

    expect(
      resolveCommitSha({
        environment: { VITE_APP_COMMIT_SHA: COMMIT_SHA.toUpperCase() },
        readGitCommit,
      }),
    ).toBe(COMMIT_SHA);
    expect(readGitCommit).not.toHaveBeenCalled();
  });

  it("uses the Git checkout when build metadata is absent", () => {
    expect(
      resolveCommitSha({
        environment: {},
        readGitCommit: () => ` ${COMMIT_SHA}\n`,
      }),
    ).toBe(COMMIT_SHA);
  });

  it("uses an explicit fallback when Git metadata is unavailable", () => {
    expect(
      resolveCommitSha({
        environment: {},
        readGitCommit: () => {
          throw new Error("not a Git checkout");
        },
      }),
    ).toBe(UNKNOWN_COMMIT_SHA);
  });
});
