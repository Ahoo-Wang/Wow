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

import { admit } from "@ahoo-wang/wow-view-engine/testing";
import { describe, expect, it } from "vitest";
import {
  EXECUTION_FAILED_DESCRIPTOR,
  EXECUTION_HISTORY_DESCRIPTOR,
} from "./descriptors.ts";
import { EXECUTION_FAILED_SOURCE, executionFailed } from "./executionFailed.ts";
import {
  EXECUTION_HISTORY_SOURCE,
  executionHistory,
} from "./executionHistory.ts";
import { overview } from "./overview.ts";
import { definitionText } from "./text.ts";

const DESCRIPTORS = {
  [EXECUTION_FAILED_SOURCE]: EXECUTION_FAILED_DESCRIPTOR,
  [EXECUTION_HISTORY_SOURCE]: EXECUTION_HISTORY_DESCRIPTOR,
};

/**
 * The console's definitions and boards, admitted as the engine admits them
 * over the committed descriptors (host-integration.md 6): every path one
 * of them names is one the service lists, every capability is one it
 * offers, every word has a key in both languages, and every view a board
 * names is there.
 */
describe("the console's definitions", () => {
  it.each(["en", "zh-CN"] as const)("are admitted in %s", (locale) => {
    const found = admit(
      [executionFailed, executionHistory, overview],
      DESCRIPTORS,
      { text: definitionText(locale) },
    );
    // The one thing a MongoDB store lacks: a text index, so no error
    // search (G15); an Elasticsearch store offers it.
    expect(found).toEqual([
      expect.objectContaining({
        definition: executionFailed.id,
        code: "capability.search.unavailable",
        params: { field: "keyword" },
        severity: "warning",
      }),
    ]);
  });
});
