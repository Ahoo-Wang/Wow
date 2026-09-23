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

import type { NumberFormat } from '../model/index.js';
import {
  compactFormat,
  numberFormatter,
  type DisplayContext,
} from './display.js';
import type { MessageFormatters } from './MessagesProvider.js';

/**
 * A number histogram's bucket key as the band it starts: 「¥0～500」 in
 * Chinese, `¥0–500` in English — or `undefined` when the column is not such a
 * group, or the key is not a number (a bucket with no key reads as it always
 * did).
 *
 * A key alone is the band's lower bound, and a column of 「¥0.00」「¥500.00」
 * does not say which band a row is, or how wide. The upper bound is the key
 * plus the group's `interval`, and the two are joined in the catalogue's
 * words (`label.analysis.band`): the dash is the language's, not Intl's —
 * ICU writes a Chinese range with a hyphen.
 *
 * The bounds are written short, the way an axis writes a number
 * (`compactFormat`: 万 and 亿, K and M), because a band is a name and
 * 「¥10,000.00～¥20,000.00」 buries it; but only when short is exact. A
 * band of 1,250 wide would read `1.3–2.5K` in the compact notation, a band
 * nobody asked for, so when either bound would be rounded both are written
 * in full with the decimals the band needs. A field whose numbers are names
 * (`useGrouping: false`, a year) is never compacted: 2K is not a year.
 *
 * What both bounds say alike is said once, as Intl's own ranges do: the
 * currency in front stays on the lower bound and a unit, a percent sign or
 * a compact suffix behind stays on the upper — 「¥1～2万」, `10–20%`.
 */
export function bandText(
  value: unknown,
  column: { interval?: number; numberFormat?: NumberFormat },
  messages: MessageFormatters,
  context: DisplayContext,
): string | undefined {
  const { interval } = column;
  if (interval === undefined || !(interval > 0) || !Number.isFinite(interval))
    return undefined;
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  const upper = settled(
    value + interval,
    Math.max(places(value), places(interval)),
  );
  const [from, to] = boundTexts(
    value,
    upper,
    column.numberFormat,
    context.locale,
  );
  return messages.label('label.analysis.band', { from, to });
}

/**
 * The two bounds of a band, each as text, with what they share said once.
 * Compact when both are exact in compact notation, full otherwise.
 */
function boundTexts(
  lower: number,
  upper: number,
  format: NumberFormat | undefined,
  locale: string | undefined,
): [string, string] {
  const short = bandCompact(format);
  const compact =
    format?.useGrouping !== false &&
    [lower, upper].every(bound => writesExactly(bound, short, locale));
  const used = compact ? short : fullFormat(format, lower, upper);
  const formatter = numberFormatter(used, locale);
  if (!formatter) return [String(lower), String(upper)];
  const [from, to] = [lower, upper].map(bound =>
    affixed(formatter.formatToParts(bound)),
  );
  // Said once: the currency in front on the lower bound, a unit or a
  // compact suffix behind on the upper.
  const shared = {
    prefix: from.prefix === to.prefix,
    suffix: from.suffix === to.suffix,
  };
  return [
    `${from.prefix}${from.core}${shared.suffix ? '' : from.suffix}`,
    `${shared.prefix ? '' : to.prefix}${to.core}${to.suffix}`,
  ];
}

/**
 * The compact notation a bound is tried in: the axis's (`compactFormat`), but
 * with at most one decimal and no significant digits. 「1.25K」 is exact, yet
 * it is a figure to work out; a band edge short enough to read at a glance
 * is written short, any other in full.
 */
function bandCompact(format: NumberFormat | undefined): NumberFormat {
  const short: NumberFormat & { roundingPriority?: string } = {
    ...compactFormat(format),
    minimumFractionDigits: 0,
    maximumFractionDigits: 1,
  };
  delete short.minimumSignificantDigits;
  delete short.maximumSignificantDigits;
  delete short.roundingPriority;
  return short;
}

/**
 * The format a band is written in full with: the field's own, with as many
 * decimals as the bounds carry and no more — a currency's two zeros on a
 * band of whole yuan are noise — and none of the significant-digit rounding
 * that could merge two bounds into one.
 */
function fullFormat(
  format: NumberFormat | undefined,
  lower: number,
  upper: number,
): NumberFormat {
  const full: NumberFormat = { ...format };
  delete full.minimumSignificantDigits;
  delete full.maximumSignificantDigits;
  delete full.notation;
  // A percentage prints its value a hundred times over: two of its places
  // are the percent's, not the printed number's.
  const shift = format?.style === 'percent' ? 2 : 0;
  const digits = Math.min(
    20,
    Math.max(0, places(lower) - shift, places(upper) - shift),
  );
  return { ...full, minimumFractionDigits: 0, maximumFractionDigits: digits };
}

/**
 * Whether a number reads back as itself in a format: what is printed, times
 * a power of ten (the compact notation's 万 or K, a percentage's hundred),
 * is the number. A rounded figure — 1,250 as 「1.3K」 — is not; nor is
 * anything written in digits this cannot read, which then prints in full.
 */
function writesExactly(
  value: number,
  format: NumberFormat,
  locale: string | undefined,
): boolean {
  const formatter = numberFormatter(format, locale);
  if (!formatter) return false;
  const digits = formatter
    .formatToParts(value)
    .map(part => {
      if (part.type === 'integer' || part.type === 'fraction')
        return part.value;
      return part.type === 'decimal' ? '.' : '';
    })
    .join('');
  const printed = /^\d+(\.\d+)?$/.test(digits) ? Number(digits) : Number.NaN;
  if (Number.isNaN(printed)) return false;
  if (printed === 0) return value === 0;
  const power = Math.log10(Math.abs(value) / printed);
  return Math.abs(power - Math.round(power)) < 1e-9;
}

/** A formatted number, cut into what is in front of its digits, the digits and what follows. */
function affixed(parts: Intl.NumberFormatPart[]): {
  prefix: string;
  core: string;
  suffix: string;
} {
  const inCore = (part: Intl.NumberFormatPart) => CORE_PARTS.has(part.type);
  const first = parts.findIndex(inCore);
  if (first < 0) return { prefix: '', core: joined(parts), suffix: '' };
  const last = parts.length - 1 - [...parts].reverse().findIndex(inCore);
  return {
    prefix: joined(parts.slice(0, first)),
    core: joined(parts.slice(first, last + 1)),
    suffix: joined(parts.slice(last + 1)),
  };
}

/** The parts that write the number itself, its sign included. */
const CORE_PARTS = new Set<string>([
  'minusSign',
  'plusSign',
  'integer',
  'group',
  'decimal',
  'fraction',
  'nan',
  'infinity',
  'exponentSeparator',
  'exponentMinusSign',
  'exponentInteger',
]);

function joined(parts: readonly Intl.NumberFormatPart[]): string {
  return parts.map(part => part.value).join('');
}

/** How many decimal places a number is written with: 0.25 has two, 1e-7 seven. */
function places(value: number): number {
  const [mantissa, exponent = '0'] = String(Math.abs(value)).split('e');
  const fraction = mantissa.split('.')[1]?.length ?? 0;
  return Math.max(0, fraction - Number(exponent));
}

/**
 * A sum rounded to the places its terms carry: 0.1 + 0.2 is 0.3, not the
 * 0.30000000000000004 binary arithmetic hands back, which would print as a
 * band edge nobody set.
 */
function settled(value: number, digits: number): number {
  return digits > 20 ? value : Number(value.toFixed(digits));
}
