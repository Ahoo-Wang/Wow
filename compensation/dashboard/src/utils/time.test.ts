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
import { formatRelative } from "./time.ts";

const NOW = 1_790_000_000_000;

describe("formatRelative", () => {
  it("says the largest whole unit, ahead or behind", () => {
    expect(formatRelative(NOW + 52 * 60_000, NOW, "en")).toBe("in 52 minutes");
    expect(formatRelative(NOW - 4 * 3_600_000 - 59 * 60_000, NOW, "en")).toBe(
      "4 hours ago",
    );
    expect(formatRelative(NOW - 2 * 86_400_000, NOW, "en")).toBe("2 days ago");
    expect(formatRelative(NOW + 52 * 60_000, NOW, "zh-CN")).toBe("52分钟后");
  });

  it("says now under a minute", () => {
    expect(formatRelative(NOW + 20_000, NOW, "en")).toBe("now");
  });
});
