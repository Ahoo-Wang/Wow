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

import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import type { ParkedState } from "@/generated/index.ts";
import type { Locale } from "@/views/words.ts";

/** What a row holds of an entry, as far as it holds it. */
export function entryOf(row: RecordRow): Partial<ParkedState> {
  return (row.data.state ?? {}) as Partial<ParkedState>;
}

/** The entry's name in the detail header: its subscription on its aggregate. */
export function parkedTitle(row: RecordRow): string | undefined {
  const { subscription, aggregate } = entryOf(row);
  if (!subscription) return undefined;
  return aggregate?.id ? `${subscription} · ${aggregate.id}` : subscription;
}

/** An instant in `locale`, or nothing. */
export function formatTime(
  value: number | null | undefined,
  locale: Locale,
): string | undefined {
  if (typeof value !== "number" || !Number.isFinite(value)) return undefined;
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(value);
}
