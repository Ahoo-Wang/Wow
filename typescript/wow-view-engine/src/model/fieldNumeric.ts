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

/**
 * What a number field's value is beyond a number (`FieldNumeric`, Wow's
 * numeric `QuerySemanticType`): a fixed-point decimal, or money in one
 * currency or in one each record holds; and the two places that meaning is
 * read — `numberFormatOf`, how such a number is written, and
 * `currencyPathOf`, where a record holds its currency.
 */

import type { FieldDefinition } from './field.js';

/**
 * What a number field's value is, beyond a number — Wow's numeric
 * `QuerySemanticType` (#3552), named as the engine names things: how many
 * digits it is kept to, and for an amount of money, whose money it is.
 *
 * - `decimal` is Wow's `DECIMAL`: a fixed-point number with `scale` digits
 *   after the point, written with exactly that many.
 * - `money` is Wow's `MONEY`: an amount in one currency, `scale` digits
 *   after the point, and the currency either fixed for the whole field (an
 *   ISO 4217 `currency`) or held per record by a sibling string field
 *   (`currencyField`, a property of the same object or element) — so an
 *   amount reads in its own currency, and amounts in different currencies
 *   are never added up as if they were one.
 *
 * Narrowing writes it from the source's descriptor where the definition
 * says nothing; a definition may declare it itself. It decides how the
 * value reads only where the field declares no `numberFormat` — the
 * author's own format wins (`numberFormatOf`).
 */
export type FieldNumeric = DecimalNumeric | FixedMoneyNumeric | RowMoneyNumeric;

export interface DecimalNumeric {
  type: 'decimal';
  /** Digits after the decimal point, 0 or more. */
  scale: number;
}

export interface FixedMoneyNumeric {
  type: 'money';
  /** Digits after the decimal point, 0 or more. */
  scale: number;
  /** The ISO 4217 code every value of the field is in, such as `CNY`. */
  currency: string;
  currencyField?: undefined;
}

export interface RowMoneyNumeric {
  type: 'money';
  /** Digits after the decimal point, 0 or more. */
  scale: number;
  currency?: undefined;
  /**
   * The sibling property holding each value's ISO 4217 code: its name
   * beside the amount, not a path from the root — `currency` beside
   * `price.amount` is `price.currency` (`currencyPathOf`).
   */
  currencyField: string;
}

export const NUMERIC_TYPES: readonly FieldNumeric['type'][] = [
  'decimal',
  'money',
];

/** The most fraction digits Intl writes, which `scale` is held under. */
export const MAX_NUMERIC_SCALE = 20;

/** An ISO 4217 currency code: three letters. */
export const CURRENCY_CODE_PATTERN = /^[A-Za-z]{3}$/;

/**
 * How a number of this field is written: the author's `numberFormat` when
 * the field declares one, otherwise what its `numeric` says — `scale`
 * fraction digits, and for money in one fixed currency, that currency.
 * Money whose currency is held per record gets the digits here and its
 * currency where the record is read (`currencyPathOf`), since no one
 * format is true of every value.
 *
 * It is the one place the two meet, so every reader — a cell, a column, a
 * metric, an axis — prints what the others print.
 */
export function numberFormatOf(
  field: Pick<FieldDefinition, 'numberFormat' | 'numeric'> | undefined,
): NumberFormat | undefined {
  if (!field) return undefined;
  if (field.numberFormat !== undefined) return field.numberFormat;
  const numeric = field.numeric;
  if (!numeric) return undefined;
  const digits = {
    minimumFractionDigits: numeric.scale,
    maximumFractionDigits: numeric.scale,
  };
  return numeric.type === 'money' && numeric.currency !== undefined
    ? { style: 'currency', currency: numeric.currency.toUpperCase(), ...digits }
    : digits;
}

/**
 * Where the currency of this field's values is held, for money whose
 * currency is per record: the sibling `currencyField` beside the field's
 * own name — `price.currency` for `price.amount`, `currency` for `amount`.
 * `undefined` for every other field, the author's own `numberFormat`
 * included: a field formatted by its author reads as the author said.
 * `path` is where the field is named from, when that is not its own name —
 * an element's field as an analysis spells it from the root.
 */
export function currencyPathOf(
  field: Pick<FieldDefinition, 'name' | 'numberFormat' | 'numeric'>,
  path: string = field.name,
): string | undefined {
  const numeric = field.numeric;
  if (
    field.numberFormat !== undefined ||
    numeric?.type !== 'money' ||
    numeric.currencyField === undefined
  )
    return undefined;
  const dot = path.lastIndexOf('.');
  return dot < 0
    ? numeric.currencyField
    : `${path.slice(0, dot + 1)}${numeric.currencyField}`;
}

/** Formatting shared by cells, summaries and chart axes. */
export type NumberFormat = Intl.NumberFormatOptions & { locale?: string };
