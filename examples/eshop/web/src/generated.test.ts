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

// @vitest-environment node

/*
 * src/generated/<context>/ is wow-generator's output for each eShop
 * service's Manifest baseline, byte for byte. A server's `check` holds its
 * baseline to what it serves, so a changed route or shape fails here until
 * the clients are generated again (scripts/generate.mjs, after building
 * wow-generator).
 */

import { mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { afterAll, describe, expect, it } from "vitest";
// @ts-expect-error The script is plain JavaScript.
import { CONTEXTS, generate } from "../scripts/generate.mjs";

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

/*
 * Each case runs wow-generator once, in a process of its own: about 1 s on a
 * CI runner, and 5 to 8 s on a loaded one, past vitest's 5 s default. Its own
 * bound, with room; the package's other tests keep the default.
 */
const GENERATE_TIMEOUT = 30_000;

describe("the generated clients", () => {
  const output = mkdtempSync(join(tmpdir(), "wow-eshop-web-generated-"));
  afterAll(() => rmSync(output, { recursive: true, force: true }));

  it.each(CONTEXTS as string[])(
    "of %s are what wow-generator writes from its Manifest baseline",
    (context) => {
      generate(context, join(output, context));
      const expected = files(join(output, context));
      const actual = files(join(import.meta.dirname, "generated", context));
      expect([...actual.keys()].sort()).toEqual([...expected.keys()].sort());
      for (const [path, content] of expected)
        expect(actual.get(path), path).toBe(content);
    },
    GENERATE_TIMEOUT,
  );
});
