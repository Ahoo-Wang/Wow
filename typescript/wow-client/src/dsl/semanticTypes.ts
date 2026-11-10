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

import type { TimeUnit } from './filter/index.js';

/**
 * The semantics Wow names in a field's `semantic.type`, one per member of
 * {@link QuerySemanticType}: read a descriptor's semantic by these rather
 * than by its spelling.
 */
export const QuerySemanticTypes = Object.freeze({
  /** A calendar date: {@link TemporalDate}. */
  TEMPORAL_DATE: 'temporal-date',
  /** A number of time units since the epoch: {@link TemporalEpoch}. */
  TEMPORAL_EPOCH: 'temporal-epoch',
  /** A time written as text in a pattern: {@link TemporalFormatted}. */
  TEMPORAL_FORMATTED: 'temporal-formatted',
  /** A fixed-point decimal: {@link NumericDecimal}. */
  DECIMAL: 'decimal',
  /** An amount of money: {@link NumericMoney}. */
  MONEY: 'money',
  /** A length of time: {@link DurationSemantic}. */
  DURATION: 'duration',
  /** The id of an aggregate: {@link ReferenceSemantic}. */
  REFERENCE: 'reference',
} as const);

/** A field that holds a calendar date. */
export interface TemporalDate {
  /** The discriminator. */
  type: typeof QuerySemanticTypes.TEMPORAL_DATE;
}

/** A field that holds a number of time units since the epoch. */
export interface TemporalEpoch {
  /** The discriminator. */
  type: typeof QuerySemanticTypes.TEMPORAL_EPOCH;
  /** The unit the number counts; milliseconds when absent. */
  timeUnit?: TimeUnit;
}

/** A field that holds a time written as text in a `java.time` pattern. */
export interface TemporalFormatted {
  /** The discriminator. */
  type: typeof QuerySemanticTypes.TEMPORAL_FORMATTED;
  /** The `java.time` pattern, such as `yyyy-MM-dd`. */
  pattern: string;
}

/**
 * A number field meant as a fixed-point decimal with `scale` fraction
 * digits: format and total it at that precision. Declared on the model
 * (`@QueryDecimal`), never inferred. Wow 9.2 and later.
 */
export interface NumericDecimal {
  /** The discriminator. */
  type: typeof QuerySemanticTypes.DECIMAL;
  /** The number of digits after the decimal point, 0 or more. */
  scale: number;
}

/**
 * A number field meant as an amount of money, in exactly one of a fixed
 * ISO 4217 `currency` or the currency held by the sibling string field
 * `currencyField` (a property of the same object or element). `scale` is
 * always sent: with a fixed currency the server resolves an omitted one to
 * that currency's standard fraction digits (2 for CNY, 0 for JPY).
 * Amounts in different currencies must not be totalled together. Declared
 * on the model (`@QueryMoney`), never inferred. Wow 9.2 and later.
 */
export type NumericMoney = {
  /** The discriminator. */
  type: typeof QuerySemanticTypes.MONEY;
  /** The number of digits after the decimal point, 0 or more. */
  scale: number;
} & (
  | {
      /** The fixed ISO 4217 currency code, such as `CNY`. */
      currency: string;
      currencyField?: undefined;
    }
  | {
      currency?: undefined;
      /** The sibling property that holds each value's currency code. */
      currencyField: string;
    }
);

/**
 * A number field that is a length of time counted in `timeUnit`, such as a
 * timeout in seconds: format it as a duration. Declared on the model
 * (`@QueryDuration`), never inferred. Wow 9.2 and later.
 */
export interface DurationSemantic {
  /** The discriminator. */
  type: typeof QuerySemanticTypes.DURATION;
  /** The unit the number counts; always sent. */
  timeUnit: TimeUnit;
}

/**
 * A field whose value is the id of an aggregate, to look it up and link to
 * it: either a fixed aggregate, `contextName` and `aggregateName`, or per
 * record the aggregate the sibling string fields `contextNameField` and
 * `aggregateNameField` name (as in an `AggregateId`). The id is always the
 * aggregate's own. Declared on the model (`@QueryReference`), or inferred
 * for an `AggregateId`. Wow 9.2 and later.
 */
export type ReferenceSemantic = {
  /** The discriminator. */
  type: typeof QuerySemanticTypes.REFERENCE;
} & (
  | {
      /** The referenced aggregate's bounded context. */
      contextName: string;
      /** The referenced aggregate's name. */
      aggregateName: string;
      contextNameField?: undefined;
      aggregateNameField?: undefined;
    }
  | {
      contextName?: undefined;
      aggregateName?: undefined;
      /** The sibling property that holds each value's bounded context. */
      contextNameField: string;
      /** The sibling property that holds each value's aggregate name. */
      aggregateNameField: string;
    }
);

/**
 * What a field's value means beyond its type, sent as its `semantic`: one
 * of the three temporal kinds, which the relative time filters (`TODAY`,
 * `RECENT_DAYS`, …) and date histograms need; one of the two numeric
 * formats, which say how a number is read and displayed; a duration; or a
 * reference to an aggregate. A field has one; an array field's is its
 * items'.
 *
 * The union grows in minor releases, as the server learns a semantic, and a
 * server newer than this client may send a `type` not listed here. The
 * descriptor is read as JSON, not parsed, so it arrives as it is: keep a
 * `default` branch in a `switch` over `type`, and treat a `type` you do not
 * know as no semantic at all. The union stays closed so that narrowing on
 * `type` still works.
 */
export type QuerySemanticType =
  | TemporalDate
  | TemporalEpoch
  | TemporalFormatted
  | NumericDecimal
  | NumericMoney
  | DurationSemantic
  | ReferenceSemantic;
