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

/**
 * `src/generated/` is wow-generator's output for the ops service's
 * Manifest, byte for byte. The Manifest is the service's baseline
 * (`platform/ops/ops-server/manifest/baseline.json`), which the service's
 * own `check` holds to what it serves; so a change of a route or a shape
 * there fails here until the client is generated again (`pnpm generate`,
 * after `pnpm --filter @ahoo-wang/wow-generator build`).
 */

import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { afterAll, describe, expect, it } from "vitest";

const CONSOLE = join(import.meta.dirname, "..");
const ROOT = join(CONSOLE, "../../..");
const BASELINE = join(ROOT, "platform/ops/ops-server/manifest/baseline.json");
const COMMITTED = join(CONSOLE, "src/generated");
const CLI = join(ROOT, "typescript/wow-generator/dist/cli.js");

/** Every file under `directory`, by its path relative to it. */
function files(directory: string): Map<string, string> {
  return new Map(
    readdirSync(directory, { recursive: true, withFileTypes: true })
      .filter((entry) => entry.isFile())
      .map((entry) => {
        const path = join(entry.parentPath, entry.name);
        return [relative(directory, path), readFileSync(path, "utf8")];
      }),
  );
}

describe("the generated ops client", () => {
  const output = mkdtempSync(join(tmpdir(), "wow-ops-console-generated-"));
  afterAll(() => rmSync(output, { recursive: true, force: true }));

  it("is what wow-generator writes from the ops service's Manifest", () => {
    execFileSync(
      process.execPath,
      [CLI, "generate", "-i", BASELINE, "-o", output, "--strict", "--quiet"],
      { stdio: "pipe" },
    );
    const expected = files(output);
    const actual = files(COMMITTED);
    expect([...actual.keys()].sort()).toEqual([...expected.keys()].sort());
    for (const [path, content] of expected)
      expect(actual.get(path), path).toBe(content);
  });
});
