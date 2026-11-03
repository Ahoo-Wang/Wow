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
import { RETRY_SPEC_INPUTS } from "./fixtures/retrySpecInputs.ts";
import { isDuration } from "./kotlinDuration.ts";

/*
 * The service's own verdicts (`RetryDuration`: kotlin-stdlib 2.4.21's
 * `Duration.parseOrNull` of the trimmed text, finite and not negative), from
 * the table its `ApplyRetrySpecInputTest` holds it to.
 */
const { taken, refused } = RETRY_SPEC_INPUTS.durations;

describe("a duration as the ops service reads it", () => {
  it.each(taken)("takes %j", (value) => {
    expect(isDuration(value)).toBe(true);
  });

  it.each(refused)("refuses %j", (value) => {
    expect(isDuration(value)).toBe(false);
  });
});
