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

/*
 * Kotlin's `Duration.parse` (kotlin-stdlib 2.4, `kotlin/time/Duration.kt`),
 * as far as the ops service asks it: whether a text parses, and whether
 * what it parses to is finite and not negative (`Parked.isDuration`). The
 * arithmetic is the stdlib's own, in 64-bit steps that saturate at
 * `MAX_MILLIS` (infinite) rather than at the true sum, so a text the
 * service refuses as infinite is refused here too.
 */

const LONG_MAX = 2n ** 63n - 1n;
const MAX_MILLIS = LONG_MAX / 2n;
const NANOS_IN_MILLIS = 1_000_000n;
const MAX_NANOS = (LONG_MAX / 2n / NANOS_IN_MILLIS) * NANOS_IN_MILLIS - 1n;
const MAX_NANOS_IN_MILLIS = MAX_NANOS / NANOS_IN_MILLIS;
const FRACTION_LIMIT = 15;

/** Kotlin's `DurationUnit`, finest first: each component is coarser than the next. */
const NS = 0;
const US = 1;
const MS = 2;
const S = 3;
const M = 4;
const H = 5;
const D = 6;

const ISO_UNITS: Readonly<Record<string, number>> = { D, H, M, S };
const MILLIS_IN = [0n, 0n, 1n, 1_000n, 60_000n, 3_600_000n, 86_400_000n];
/** Nanoseconds in a unit ÷ 10¹⁵: a fraction read to 15 digits times this. */
const FRACTION_MULTIPLIER = [1e-15, 1e-12, 1e-9, 1e-6, 6e-5, 0.0036, 0.0864];
const FALLBACK_MULTIPLIER = [0, 0, 0, 0, 60e9, 3_600e9, 86_400e9];

/** Kotlin's two halves of a duration: nanoseconds, or milliseconds past them. */
type Duration = { nanos: bigint } | { millis: bigint };

const abs = (value: bigint) => (value < 0n ? -value : value);
const clamp = (value: bigint) =>
  value > MAX_MILLIS ? MAX_MILLIS : value < -MAX_MILLIS ? -MAX_MILLIS : value;
const infinite = (millis: bigint) => abs(millis) === MAX_MILLIS;

/** `convertDurationUnitToMilliseconds`: a non-negative count, at most `MAX_MILLIS`. */
function toMillis(value: bigint, unit: number): bigint {
  const millis = value * MILLIS_IN[unit];
  return millis > MAX_MILLIS ? MAX_MILLIS : millis;
}

/** `addMillisWithoutOverflow`: an infinity stays, two of opposite signs are no sum. */
function addMillis(total: bigint, other: bigint): bigint | null {
  if (infinite(total))
    return !infinite(other) || total >= 0n === other >= 0n ? total : null;
  if (infinite(other)) return other;
  return clamp(total + other);
}

/** `LongParser`: digits up to `limit`, the rest of an overflowing run skipped. */
function readLong(value: string, start: number, limit: bigint) {
  let index = start;
  while (value[index] === "0") index += 1;
  let result = 0n;
  while (index < value.length && isDigit(value[index])) {
    const next = result * 10n + BigInt(value.charCodeAt(index) - 48);
    if (next > limit) {
      while (index < value.length && isDigit(value[index])) index += 1;
      return { value: limit, end: index, overflow: true };
    }
    result = next;
    index += 1;
  }
  return { value: result, end: index, overflow: false };
}

/** `FractionalParser`: the first 15 digits as an integer scaled to 10¹⁵. */
function readFraction(value: string, start: number) {
  let index = start;
  let digits = "";
  while (index < value.length && isDigit(value[index])) {
    if (digits.length < FRACTION_LIMIT) digits += value[index];
    index += 1;
  }
  return { value: Number(digits.padEnd(FRACTION_LIMIT, "0")), end: index };
}

function isDigit(char: string | undefined): boolean {
  return char !== undefined && char >= "0" && char <= "9";
}

/** Kotlin's `Double.roundToLong`, for the non-negative products here. */
const rounded = (value: number) => BigInt(Math.round(value));

/** `PT1H30M`, after the `P`: each component signed, only seconds fractional. */
function parseIso(value: string, start: number): [bigint, bigint] | null {
  let index = start;
  if (index === value.length) return null;
  let totalMillis = 0n;
  let totalNanos = 0n;
  let time = false;
  let previous: number | null = null;
  while (index < value.length) {
    const char = value[index];
    if (char === "T") {
      index += 1;
      if (time || index === value.length) return null;
      time = true;
      continue;
    }
    let sign = 1n;
    let digits = index;
    if (char === "-" || char === "+") {
      if (char === "-") sign = -1n;
      digits += 1;
    }
    const long = readLong(value, digits, MAX_MILLIS);
    if (long.end === value.length || long.end === digits) return null;
    index = long.end;
    if (value[index] === ".") {
      index += 1;
      const fraction = readFraction(value, index);
      if (
        fraction.end === index ||
        fraction.end === value.length ||
        value[fraction.end] !== "S"
      )
        return null;
      index = fraction.end;
      totalNanos = sign * rounded(fraction.value * FRACTION_MULTIPLIER[S]);
    }
    const unit = ISO_UNITS[value[index]];
    if (unit === undefined) return null;
    if (previous !== null && previous <= unit) return null;
    previous = unit;
    const millis = sign * toMillis(long.value, unit);
    if (unit === D) {
      if (time) return null;
      totalMillis = millis;
    } else {
      if (!time) return null;
      const sum = addMillis(totalMillis, millis);
      if (sum === null) return null;
      totalMillis = sum;
    }
    index += 1;
  }
  return [totalMillis, totalNanos];
}

/** `1h 30m`, `1h30m`: units coarsest first, a fraction only on the last. */
function parseDefault(
  value: string,
  start: number,
  signed: boolean,
): [bigint, bigint] | null {
  let index = start;
  let length = value.length;
  let spaces = !signed;
  if (signed && value[index] === "(" && value[length - 1] === ")") {
    spaces = true;
    index += 1;
    length -= 1;
    if (index === length) return null;
  }
  let totalMillis = 0n;
  let totalNanos = 0n;
  let previous: number | null = null;
  let first = true;
  while (index < length) {
    if (!first && spaces) while (value[index] === " ") index += 1;
    first = false;
    const long = readLong(value, index, LONG_MAX);
    if (long.end === index || long.end === length || long.overflow) return null;
    index = long.end;
    const fractionStart = value[index] === "." ? index : -1;
    let fraction = 0;
    if (fractionStart >= 0) {
      index += 1;
      const read = readFraction(value, index);
      if (read.end === index || read.end === length) return null;
      index = read.end;
      fraction = read.value;
    }
    const unit = defaultUnit(value[index], value[index + 1]);
    if (unit === null) return null;
    if (previous !== null && previous <= unit) return null;
    previous = unit;
    if (unit === US) {
      totalMillis += long.value / 1_000n;
      if (totalMillis <= MAX_NANOS_IN_MILLIS)
        totalNanos = (long.value % 1_000n) * 1_000n;
    } else if (unit === NS) {
      totalMillis += long.value / NANOS_IN_MILLIS;
      totalNanos += long.value % NANOS_IN_MILLIS;
    } else {
      // Both counts are non-negative here: never two opposite infinities.
      totalMillis = addMillis(totalMillis, toMillis(long.value, unit))!;
    }
    const name = unit <= MS ? 2 : 1;
    index += name;
    if (fractionStart >= 0) {
      if (index < length) return null;
      totalNanos +=
        unit >= M && index - fractionStart > FRACTION_LIMIT
          ? rounded(
              Number(value.substring(fractionStart, index - name)) *
                FALLBACK_MULTIPLIER[unit],
            )
          : rounded(fraction * FRACTION_MULTIPLIER[unit]);
    }
  }
  return [totalMillis, totalNanos];
}

function defaultUnit(first: string | undefined, second: string | undefined) {
  switch (first) {
    case "d":
      return D;
    case "h":
      return H;
    case "s":
      return S;
    case "m":
      return second === "s" ? MS : M;
    case "u":
      return second === "s" ? US : null;
    case "n":
      return second === "s" ? NS : null;
    default:
      return null;
  }
}

/** `totalMillis.toDuration(MILLISECONDS) + totalNanos.toDuration(NANOSECONDS)`. */
function durationOf(totalMillis: bigint, totalNanos: bigint): Duration {
  const whole: Duration =
    abs(totalMillis) <= MAX_NANOS_IN_MILLIS
      ? { nanos: totalMillis * NANOS_IN_MILLIS }
      : {
          millis: totalMillis < 0n ? -clamp(-totalMillis) : clamp(totalMillis),
        };
  if ("nanos" in whole) {
    const sum = whole.nanos + totalNanos;
    return abs(sum) <= MAX_NANOS
      ? { nanos: sum }
      : { millis: sum / NANOS_IN_MILLIS };
  }
  const otherMillis = totalNanos / NANOS_IN_MILLIS;
  const millis = addMillis(whole.millis, otherMillis)!;
  return abs(millis) <= MAX_NANOS_IN_MILLIS
    ? {
        nanos:
          millis * NANOS_IN_MILLIS +
          (totalNanos - otherMillis * NANOS_IN_MILLIS),
      }
    : { millis };
}

/** A separator of Unicode's: a space, a line or a paragraph. */
const SEPARATOR = /[\p{Zs}\p{Zl}\p{Zp}]/u;

/**
 * Kotlin's `Char.isWhitespace` (Java's `isWhitespace` or `isSpaceChar`):
 * tab to carriage return, the four information separators U+001C to
 * U+001F, and Unicode's separators — no-break spaces included.
 */
function isKotlinSpace(char: string): boolean {
  const code = char.charCodeAt(0);
  return (
    (code >= 0x09 && code <= 0x0d) ||
    (code >= 0x1c && code <= 0x1f) ||
    SEPARATOR.test(char)
  );
}

/** Kotlin's `trim()`: its whitespace off both ends. */
function kotlinTrim(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && isKotlinSpace(value[start])) start += 1;
  while (end > start && isKotlinSpace(value[end - 1])) end -= 1;
  return value.slice(start, end);
}

/**
 * Whether the ops service takes `value` as a retry spec's duration: its
 * `Duration.parse` of the trimmed text — ISO-8601 (`PT3M`, `P1DT2H`) or
 * Kotlin's own (`3m`, `1h 30m`, `1h30m`, `1.5s`) — finite and not negative.
 */
export function isDuration(value: string): boolean {
  const text = kotlinTrim(value);
  const negative = text[0] === "-";
  const start = negative || text[0] === "+" ? 1 : 0;
  if (start >= text.length) return false;
  // "Infinity" parses too, and is never finite: the default format, which
  // reads digits first, refuses it as it refuses any other word.
  const totals =
    text[start] === "P"
      ? parseIso(text, start + 1)
      : parseDefault(text, start, start > 0);
  if (totals === null) return false;
  const duration = durationOf(...totals);
  const raw = "nanos" in duration ? duration.nanos : duration.millis;
  if ("millis" in duration && infinite(duration.millis)) return false;
  return negative ? raw <= 0n : raw >= 0n;
}
