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

import type {
  CurrencyReading,
  FieldNumeric,
  NumberFormat,
  RecordData,
} from '../model/index.js';
import { inCurrency, isCurrencyCode } from '../model/currency.js';
import { recordValue } from '../record/index.js';
import { formatNumber, type DisplayField } from './display.js';
import type { MessageFormatters } from './MessagesProvider.js';

/**
 * A field as it reads on this row: money whose currency each record holds
 * (`currencyPath`) written in the currency the row holds, where it holds
 * one the engine can write — else as the field reads, in its digits and no
 * currency, which is never a currency it guessed.
 */
export function inRowCurrency<F extends DisplayField>(
  field: F,
  row: RecordData | undefined,
): F {
  if (field.currencyPath === undefined || !row) return field;
  const code = recordValue(row, field.currencyPath);
  return isCurrencyCode(code)
    ? { ...field, numberFormat: inCurrency(field.numberFormat, code) }
    : field;
}

/**
 * An aggregate of money as its currency lets it read: in the one currency
 * its records are in, or — records in several — 「多种货币」, since a sum of
 * yuan and yen is no amount (the kernel has already taken the number
 * away). `undefined` where nothing is known, and the caller reads the
 * value as it would.
 */
export function currencyText(
  value: unknown,
  reading: CurrencyReading | undefined,
  format: NumberFormat | undefined,
  messages: MessageFormatters,
  locale: string | undefined,
): string | undefined {
  if (reading?.type === 'mixed')
    return messages.label('label.value.mixed-currencies', {
      count: reading.count,
    });
  if (reading?.type === 'one' && typeof value === 'number')
    return formatNumber(value, inCurrency(format, reading.code), locale);
  return undefined;
}

/**
 * The currency column a file writes beside money (`csvCellText`): the
 * field's fixed currency, or the one this row's `reading` says — the
 * record's own, or an aggregate's, which reads 「多种货币」 where its
 * records are in several. Empty where none is known.
 */
export function currencyCsvText(
  numeric: FieldNumeric | undefined,
  reading: CurrencyReading | undefined,
  messages: MessageFormatters,
): string {
  if (numeric?.type === 'money' && numeric.currency !== undefined)
    return numeric.currency.toUpperCase();
  if (reading?.type === 'mixed')
    return messages.label('label.value.mixed-currencies');
  return reading?.type === 'one' ? reading.code : '';
}
