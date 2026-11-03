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

import { readFileSync } from "node:fs";
import { join } from "node:path";

/** What the service takes and what it refuses, of one input. */
interface Verdicts<T> {
  taken: T[];
  refused: T[];
}

/**
 * What the ops service takes and refuses as an `ApplyRetrySpec`, one table
 * for both sides: the service's own `ApplyRetrySpecInputTest` reads it too,
 * so the form refuses what the service refuses and nothing else.
 */
export const RETRY_SPEC_INPUTS = JSON.parse(
  readFileSync(
    join(
      import.meta.dirname,
      "../../../../ops-server/src/test/resources/apply-retry-spec-inputs.json",
    ),
    "utf8",
  ),
) as { durations: Verdicts<string>; maxRetries: Verdicts<number> };
