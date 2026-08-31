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
import { formatAge, formatDate } from "./dates";

describe("dates", () => {
  describe("formatDate", () => {
    it("should format timestamp correctly with default template", () => {
      const timestamp = new Date("2023-01-01 12:00:00").getTime();
      const formatted = formatDate(timestamp);
      expect(formatted).toBe("2023-01-01 12:00:00");
    });

    it("should format timestamp with custom template", () => {
      const timestamp = new Date("2023-01-01 12:00:00").getTime();
      const formatted = formatDate(timestamp, "YYYY-MM-DD");
      expect(formatted).toBe("2023-01-01");
    });

    it('should return "-" when timeAt is undefined', () => {
      const formatted = formatDate(undefined);
      expect(formatted).toBe("-");
    });

    it('should return "-" when timeAt is 0', () => {
      const formatted = formatDate(0);
      expect(formatted).toBe("-");
    });

    it("should format timestamp correctly with different time values", () => {
      const timestamp = new Date("2022-12-25 08:30:45").getTime();
      const formatted = formatDate(timestamp, "YYYY/MM/DD HH:mm");
      expect(formatted).toBe("2022/12/25 08:30");
    });

    it("formats full dates in Chinese", () => {
      const timestamp = new Date("2023-01-01 12:00:00").getTime();
      expect(formatDate(timestamp, undefined, "zh-CN")).toBe(
        "2023年1月1日 12:00:00",
      );
      expect(formatDate(timestamp, "MM-DD", "zh-CN")).toBe("1月1日");
      expect(formatDate(timestamp, "MM-DD HH:mm", "zh-CN")).toBe(
        "1月1日 12:00",
      );
    });
  });

  describe("formatAge", () => {
    const now = new Date("2026-08-02T10:00:00.000Z").getTime();

    it("keeps recent ages compact without losing seconds", () => {
      expect(formatAge(now - 4 * 60_000 - 28_000, now)).toBe("4m 28s");
    });

    it("formats hours and days without overflowing the table column", () => {
      expect(formatAge(now - 2 * 60 * 60_000 - 12 * 60_000, now)).toBe(
        "2h 12m",
      );
      expect(formatAge(now - 3 * 24 * 60 * 60_000 - 5 * 60 * 60_000, now)).toBe(
        "3d 5h",
      );
    });

    it("clamps future timestamps to zero", () => {
      expect(formatAge(now + 5_000, now)).toBe("0s");
    });

    it("formats compact ages in Chinese", () => {
      expect(formatAge(now - 2 * 60 * 60_000 - 12 * 60_000, now, "zh-CN")).toBe(
        "2小时 12分",
      );
    });
  });
});
