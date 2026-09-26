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

import { CURRENCY_CODE_PATTERN, type NumberFormat } from './field.js';
import type { RecordData } from './record.js';

/**
 * What currency an amount — one record's, or an aggregate over several —
 * is in, for money whose currency each record holds (`RowMoneyNumeric`):
 *
 * - `one`: every record it was read from is in `code`, so it reads in it;
 * - `mixed`: the records are in `count` currencies, so the aggregate is a
 *   sum of unlike amounts — no number at all, and said as such
 *   (「多种货币」), never printed as if it were one.
 *
 * Absent where nothing could be told: no record had a currency.
 */
export type CurrencyReading =
  { type: 'one'; code: string } | { type: 'mixed'; count: number };

/** Whether a value is an ISO 4217 code the engine can format in. */
export function isCurrencyCode(value: unknown): value is string {
  return typeof value === 'string' && CURRENCY_CODE_PATTERN.test(value);
}

/**
 * `format` in `code`: the digits it says, in that currency. What a
 * row-currency amount reads as once its currency is known.
 */
export function inCurrency(
  format: NumberFormat | undefined,
  code: string,
): NumberFormat {
  return { ...format, style: 'currency', currency: code.toUpperCase() };
}

/**
 * The currency of an amount held at `path` on each row, read off the
 * rows themselves (`currencyPath`): one when every row holding an amount
 * holds the same code, mixed when they hold several. A row with no amount
 * adds nothing to a sum, so its currency is not asked; a row with an amount
 * and no readable code makes the whole unknown — an amount in no one's
 * currency cannot be added to the others either.
 */
export function currencyOfRows(
  rows: readonly RecordData[],
  amount: (row: RecordData) => unknown,
  currency: (row: RecordData) => unknown,
): CurrencyReading | undefined {
  const codes = new Set<string>();
  for (const row of rows) {
    if (typeof amount(row) !== 'number') continue;
    const code = currency(row);
    if (!isCurrencyCode(code)) return undefined;
    codes.add(code.toUpperCase());
  }
  if (codes.size === 0) return undefined;
  return codes.size === 1
    ? { type: 'one', code: [...codes][0] }
    : { type: 'mixed', count: codes.size };
}

/**
 * Several readings as one: the same currency throughout is that currency,
 * and anything else — two currencies, or one of them mixed already — is
 * mixed. Unknown when any is: nothing can be said of an amount made of one
 * nobody could read.
 */
export function joinCurrencies(
  readings: readonly (CurrencyReading | undefined)[],
): CurrencyReading | undefined {
  const codes = new Set<string>();
  let mixed = 0;
  for (const reading of readings) {
    if (reading === undefined) return undefined;
    if (reading.type === 'mixed') mixed = Math.max(mixed, reading.count);
    else codes.add(reading.code);
  }
  if (mixed > 0 || codes.size > 1)
    return { type: 'mixed', count: Math.max(mixed, codes.size) };
  return codes.size === 1 ? { type: 'one', code: [...codes][0] } : undefined;
}

/**
 * What an aggregate's companions say about its currency: `count`, the
 * distinct count of the currency field over the records it read, and
 * `code`, one of those currencies. Asked beside a sum of money whose
 * currency each record holds (the analysis's `currencyCompanions`, the
 * record totals' `summaryCurrency`).
 */
export function companionReading(
  row: RecordData | undefined,
  companion: { code: string; count: string },
): CurrencyReading | undefined {
  const count = row?.[companion.count];
  if (typeof count !== 'number' || count < 1) return undefined;
  if (count > 1) return { type: 'mixed', count };
  const code = row?.[companion.code];
  return isCurrencyCode(code)
    ? { type: 'one', code: code.toUpperCase() }
    : undefined;
}
