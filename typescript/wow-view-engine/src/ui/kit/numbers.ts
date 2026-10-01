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

import type { NumberFormat } from '../../model/index.js';

/**
 * A number as this surface prints it: in the format its field declared, and
 * grouped in the surface's language when it declared none.
 *
 * It is the one fallback for every number the UI writes — a record cell, a
 * summary, an analysis cell, a chart's axis, a count inside a sentence — so a
 * total reads 「534,897」 in the record view and in the analysis view alike,
 * where the record side used to print 「534897」. A field whose numbers are
 * names rather than quantities (a year, an employee number) says so with
 * `numberFormat: { useGrouping: false }`. A format Intl refuses to build gives
 * way to the same plain grouping.
 *
 * `locale` is the surface's language, and it is not optional in spirit: a
 * number left to `toLocaleString()` is grouped for whatever machine the page
 * happens to run on, which is the one language nobody chose. It stays optional
 * in the signature because a format may pin its own.
 */
export function formatNumber(
  value: number | bigint,
  format?: NumberFormat,
  locale?: string,
): string {
  // NaN and the infinities are no amount: a dash, as a missing one reads.
  if (typeof value === 'number' && !Number.isFinite(value)) return '—';
  const formatter =
    (format && numberFormatter(format, locale)) ?? numberFormatter({}, locale);
  if (!formatter) return String(value);
  return typeof value === 'number' && FLOORED.has(format?.style ?? '')
    ? floored(value, formatter, format?.style === 'percent' ? 100 : 1)
    : formatter.format(value);
}

/**
 * A difference of two percentages as the points between them, signed —
 * -0.144 is 「-14.4」 — to the decimals the percentage is written with, and
 * at least one; one too small for them 「<+0.1」, as a percentage is.
 */
export function formatPoints(
  delta: number,
  format: NumberFormat | undefined,
  locale: string | undefined,
): string {
  if (!Number.isFinite(delta)) return '—';
  const written =
    (format && numberFormatter(format, locale))?.resolvedOptions()
      .maximumFractionDigits ?? 0;
  const digits = Math.min(4, Math.max(1, written));
  const formatter = numberFormatter(
    {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
      signDisplay: 'exceptZero',
    },
    locale,
  );
  return formatter ? floored(delta * 100, formatter, 1) : String(delta * 100);
}

/**
 * The styles a number too small for its decimals is written as a bound
 * rather than rounded to nothing: a share and a measure in a unit.
 */
const FLOORED = new Set(['percent', 'unit']);

/**
 * A number that is not zero and not the whole, written so it never reads
 * as either. Rounded to its format's last decimal, 14 retries out of
 * 180,970 printed 「0.0%」 — 「重试从不成功」 — and 179,999 out of 180,000
 * 「100.0%」 — 「从不失败」 (the second review, R2-18); a recovery of 0.04
 * minutes printed 「0 min」. Below that last step it is 「<0.1%」, 「<0.1
 * min」 — 「>-0.1%」 below zero, and 「<+0.1%」 for a change written with
 * its sign — and short of the whole by less, 「>99.9%」. A format that
 * rounds by significant digits or writes short (an axis tick, a band edge)
 * never rounds a small number away, and is written as it is. `scale` is
 * what the style multiplies by: a hundred for a percentage.
 */
function floored(
  value: number,
  formatter: Intl.NumberFormat,
  scale: number,
): string {
  const shown = formatter.format(value);
  const options = formatter.resolvedOptions();
  const digits = options.maximumFractionDigits;
  if (
    value === 0 ||
    digits === undefined ||
    options.maximumSignificantDigits !== undefined ||
    options.notation === 'compact'
  )
    return shown;
  const step = 10 ** -digits / scale;
  if (shown === formatter.format(0))
    return value > 0
      ? `<${formatter.format(step)}`
      : `>${formatter.format(-step)}`;
  if (scale === 100 && value < 1 && shown === formatter.format(1))
    return `>${formatter.format(1 - step)}`;
  return shown;
}

/**
 * A number format written short: the same style, currency or unit, in the
 * language's own compact notation — 万 and 亿 in Chinese, K, M and B in
 * English, which is what Intl's `compact` already knows — to three
 * significant digits, as Metabase writes it: 10,230 reads 「1.02万」 and
 * `10.2K`, and 49,818 beside 50,000 reads 「4.98万」 beside 「5万」 — one
 * decimal made them 「1万」 and 「5万」, and a week of 4.9万s hid its ups
 * and downs (audit P1-4). No zero is added to reach three (「5万」, not
 * 「5.00万」), and none is invented either: a whole part longer than three
 * digits is written whole (`morePrecision`), so 4,885 reads 「4,885」 and
 * 12,345,678 「1,235万」 rather than 「4,890」 and 「1,230万」. The whole
 * part is grouped as every other number on the page is — Chinese has no
 * short word below 万, and 4,880 was the one 「4880」 beside the table's
 * 「4,880」 (audit P2-3) — unless the format says its numbers are names
 * that take no grouping. What the format said about decimals is dropped,
 * since it was said about the whole number: two fraction digits on a
 * compact figure would ask for 「1110.00万」.
 */
export function compactFormat(format: NumberFormat | undefined): NumberFormat {
  const short: NumberFormat = { ...format };
  delete short.minimumSignificantDigits;
  delete short.maximumSignificantDigits;
  // `roundingPriority` is ES2023's; the Intl types this builds against
  // predate it, and every engine the package runs on reads it.
  const compact: NumberFormat & { roundingPriority: 'morePrecision' } = {
    ...short,
    notation: 'compact',
    // `true` is 「always」: compact's own default groups nothing under five
    // digits.
    useGrouping: format?.useGrouping ?? true,
    minimumSignificantDigits: 1,
    maximumSignificantDigits: 3,
    // Both fraction ends said, and said as none: what follows the point is
    // the significant digits' to give. An older ICU (Node 20's) keeps a
    // currency's two minimum decimals unless the minimum is spelled out —
    // it wrote 「¥1110.0万」. An engine without `roundingPriority` reads
    // the significant digits alone: the same figure, short of the rare
    // whole part longer than three.
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
    roundingPriority: 'morePrecision',
  };
  return compact;
}

const numberFormatters = new Map<string, Intl.NumberFormat | null>();

/**
 * The formatter a field's `numberFormat` asks for, built once, or `null` when
 * Intl will not build it. The type admits what Intl refuses — a locale such
 * as `zh_CN`, a currency style with no currency — and the throw used to take
 * the whole table down mid-render. The language gives way first, as a date's
 * does; a format that still fails is dropped, and the number shows unformatted.
 */
export function numberFormatter(
  format: NumberFormat,
  surfaceLocale?: string,
): Intl.NumberFormat | null {
  // The format's own language first, then the surface's: a definition that
  // names one has a reason, and a surface in zh-CN showing `CN¥` where the
  // page around it says 「¥」 is a number formatted for somebody else.
  const key = JSON.stringify([format, surfaceLocale ?? null]);
  let found = numberFormatters.get(key);
  if (found === undefined) {
    const { locale, ...options } = format;
    found =
      buildNumber(locale ?? surfaceLocale, options) ??
      buildNumber(surfaceLocale, options) ??
      buildNumber(undefined, options) ??
      null;
    numberFormatters.set(key, found);
  }
  return found;
}

function buildNumber(
  locale: string | undefined,
  options: Intl.NumberFormatOptions,
): Intl.NumberFormat | undefined {
  try {
    // Never 「-0」 or 「-¥0.00」: a value that rounds to nothing has no
    // sign, unless the format asks for one of its own.
    return new Intl.NumberFormat(locale, {
      signDisplay: 'negative',
      ...options,
    } as Intl.NumberFormatOptions);
  } catch {
    return undefined;
  }
}
