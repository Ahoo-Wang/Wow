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

import type { Locale } from "@/i18n.tsx";

export function formatSeconds(
  totalSeconds: number,
  locale: Locale = "en",
): string {
  if (locale === "zh-CN") {
    const hours = Math.floor(totalSeconds / 3_600);
    const minutes = Math.floor((totalSeconds % 3_600) / 60);
    const seconds = totalSeconds % 60;
    if (totalSeconds < 60) {
      return `${totalSeconds.toLocaleString()}秒`;
    }
    return [
      hours > 0 ? `${hours.toLocaleString()}小时` : "",
      minutes > 0 ? `${minutes.toLocaleString()}分钟` : "",
      seconds > 0 ? `${seconds.toLocaleString()}秒` : "",
    ]
      .filter(Boolean)
      .join(" ");
  }

  if (totalSeconds < 60) {
    return `${totalSeconds.toLocaleString()} ${totalSeconds === 1 ? "second" : "seconds"}`;
  }

  const hours = Math.floor(totalSeconds / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  const parts: string[] = [];
  if (hours > 0) {
    parts.push(`${hours.toLocaleString()} ${hours === 1 ? "hour" : "hours"}`);
  }
  if (minutes > 0) {
    parts.push(
      `${minutes.toLocaleString()} ${minutes === 1 ? "minute" : "minutes"}`,
    );
  }
  if (seconds > 0) {
    parts.push(
      `${seconds.toLocaleString()} ${seconds === 1 ? "second" : "seconds"}`,
    );
  }
  return parts.join(" ");
}
