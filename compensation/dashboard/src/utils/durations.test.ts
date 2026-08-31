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
import { formatSeconds } from "./durations.ts";

describe("formatSeconds", () => {
  it("formats seconds without treating them as milliseconds", () => {
    expect(formatSeconds(1)).toBe("1 second");
    expect(formatSeconds(120)).toBe("2 minutes");
    expect(formatSeconds(3_661)).toBe("1 hour 1 minute 1 second");
  });

  it("formats durations in Chinese", () => {
    expect(formatSeconds(3_661, "zh-CN")).toBe("1小时 1分钟 1秒");
  });
});
