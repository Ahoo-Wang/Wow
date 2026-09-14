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
import { formatCompactNumber } from "./numbers.ts";

describe("formatCompactNumber", () => {
  it("keeps chart axis labels short for large counts", () => {
    expect(formatCompactNumber(6_000, "en")).toBe("6K");
    expect(formatCompactNumber(550_776, "en")).toBe("550.8K");
    expect(formatCompactNumber(1_200_000, "en")).toBe("1.2M");
  });

  it("keeps small axis values exact", () => {
    expect(formatCompactNumber(0, "en")).toBe("0");
    expect(formatCompactNumber(12, "en")).toBe("12");
    expect(formatCompactNumber(999, "en")).toBe("999");
  });

  it("follows the active locale", () => {
    expect(formatCompactNumber(6_000, "zh-CN")).toBe("6000");
  });
});
