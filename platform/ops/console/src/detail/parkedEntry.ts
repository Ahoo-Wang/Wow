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
import { PARKED_WORDS } from "@/views/parked.ts";
import type { Locale } from "@/views/words.ts";

export type ParkedField = keyof (typeof PARKED_WORDS)["en"];

const OPTION_WORDS: Record<string, Record<string, ParkedField>> = {
  status: {
    scheduled: "statusScheduled",
    exhausted: "statusExhausted",
    unrecoverable: "statusUnrecoverable",
    resolved: "statusResolved",
  },
  recoverable: {
    recoverable: "recoverableYes",
    unrecoverable: "recoverableNo",
    unknown: "recoverableUnknown",
  },
  requested: {
    retry: "requestedRetry",
    skip: "requestedSkip",
    "mark-recoverable": "requestedMark",
    "apply-retry-spec": "requestedSpec",
  },
  /** The ledger's system command an owning service refused (decision D226). */
  refusedAction: {
    "wow.ledger.Retry": "requestedRetry",
    "wow.ledger.Skip": "requestedSkip",
    "wow.ledger.MarkRecoverable": "requestedMark",
    "wow.ledger.ApplyRetrySpec": "requestedSpec",
  },
  outcome: {
    retried: "outcomeRetried",
    skipped: "outcomeSkipped",
    resolved: "outcomeResolved",
    "not-parked": "outcomeNotParked",
  },
};

/** An enumerated value of `kind` (status, outcome, ...) in `locale`, else as it is. */
export function optionWord(
  locale: Locale,
  kind: string,
  value: string,
): string {
  const field = OPTION_WORDS[kind]?.[value];
  return field ? PARKED_WORDS[locale][field] : value;
}

/**
 * A recorded failure split as the runtime writes it, `<type>: <message>`:
 * the type the entry is clustered by (`state.errorType`) and the message;
 * a reason naming no type is all message, under itself as the type.
 */
export function failureOf(reason: string): { type: string; message: string } {
  const at = reason.indexOf(": ");
  return at < 0
    ? { type: reason.trim(), message: "" }
    : { type: reason.slice(0, at).trim(), message: reason.slice(at + 2) };
}

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

/** Seconds per unit of a duration as the ops service writes one. */
const UNIT_SECONDS: Record<string, number> = {
  d: 86_400,
  h: 3_600,
  m: 60,
  s: 1,
  ms: 0.001,
  us: 0.000_001,
  ns: 0.000_000_001,
};

/** The seconds a duration names, ISO-8601 (`PT3M`) or Kotlin's (`3m`, `1h 30m`), else `null`. */
export function durationSeconds(text: string): number | null {
  const value = text.trim();
  const iso =
    /^P(?=\d|T\d)(?:(\d+(?:\.\d+)?)D)?(?:T(?=\d)(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(
      value,
    );
  if (iso) {
    const [, d, h, m, s] = iso.map((part) => Number(part ?? 0));
    return d * 86_400 + h * 3_600 + m * 60 + s;
  }
  if (
    !/^\d+(?:\.\d+)?(?:d|h|m|s|ms|us|ns)(?: \d+(?:\.\d+)?(?:d|h|m|s|ms|us|ns))*$/.test(
      value,
    )
  )
    return null;
  return value.split(" ").reduce((total, part) => {
    const [, amount, unit] = /^(\d+(?:\.\d+)?)([a-z]+)$/.exec(part)!;
    return total + Number(amount) * UNIT_SECONDS[unit];
  }, 0);
}

/** The words of each unit a duration is said in, per language. */
const DURATION_UNITS: Record<Locale, [number, string][]> = {
  en: [
    [86_400, "d"],
    [3_600, "h"],
    [60, "min"],
    [1, "s"],
  ],
  "zh-CN": [
    [86_400, "天"],
    [3_600, "小时"],
    [60, "分钟"],
    [1, "秒"],
  ],
};

/**
 * A duration said for a reader — `PT1M` and `1m` alike as 「1 分钟」/"1 min",
 * `PT1H30M` as "1 h 30 min" — rather than as the service stores it; the
 * text as written when it is not one.
 */
export function formatDuration(text: string, locale: Locale): string {
  const seconds = durationSeconds(text);
  if (seconds === null) return text;
  const parts: string[] = [];
  let left = seconds;
  for (const [size, unit] of DURATION_UNITS[locale]) {
    const whole =
      size === 1 ? Math.round(left * 1000) / 1000 : Math.floor(left / size);
    if (whole > 0) parts.push(`${whole} ${unit}`);
    left -= whole * size;
  }
  return parts.length > 0
    ? parts.join(" ")
    : `0 ${DURATION_UNITS[locale][3][1]}`;
}
