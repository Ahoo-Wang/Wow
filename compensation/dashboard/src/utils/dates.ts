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

import dayjs from "dayjs";
import type { Locale } from "@/i18n.tsx";

export function formatDate(
  timeAt: number | undefined,
  template?: string,
  locale: Locale = "en",
): string {
  if (!timeAt) {
    return "-";
  }
  if (locale === "zh-CN") {
    if (!template) {
      return new Intl.DateTimeFormat(locale, {
        dateStyle: "medium",
        timeStyle: "medium",
      }).format(timeAt);
    }
    if (template === "MM-DD" || template === "MM-DD HH:mm") {
      return new Intl.DateTimeFormat(locale, {
        month: "short",
        day: "numeric",
        ...(template.includes("HH:mm")
          ? { hour: "2-digit", minute: "2-digit", hour12: false }
          : {}),
      }).format(timeAt);
    }
  }
  return dayjs(timeAt).format(template ?? "YYYY-MM-DD HH:mm:ss");
}

export function formatAge(
  timeAt: number,
  now: number = Date.now(),
  locale: Locale = "en",
): string {
  const elapsedSeconds = Math.max(0, Math.floor((now - timeAt) / 1_000));
  const seconds = elapsedSeconds % 60;
  const elapsedMinutes = Math.floor(elapsedSeconds / 60);

  if (elapsedMinutes === 0) {
    return `${seconds}${locale === "zh-CN" ? "秒" : "s"}`;
  }

  const minutes = elapsedMinutes % 60;
  const elapsedHours = Math.floor(elapsedMinutes / 60);
  if (elapsedHours === 0) {
    return locale === "zh-CN"
      ? `${minutes}分 ${seconds}秒`
      : `${minutes}m ${seconds}s`;
  }

  const hours = elapsedHours % 24;
  const days = Math.floor(elapsedHours / 24);
  if (days === 0) {
    return locale === "zh-CN"
      ? `${hours}小时 ${minutes}分`
      : `${hours}h ${minutes}m`;
  }

  return locale === "zh-CN" ? `${days}天 ${hours}小时` : `${days}d ${hours}h`;
}
