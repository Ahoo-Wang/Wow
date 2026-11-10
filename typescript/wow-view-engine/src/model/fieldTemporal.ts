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
 * How a time field keeps its time (`FieldTemporal`, Wow's temporal
 * `QuerySemanticType`), how finely a table writes it (`TimePrecision`), and
 * the two readings every reader of such a field shares: `temporalOf`, its
 * declaration or the default, and `epochUnitOf`, the unit its epoch numbers
 * count in.
 */

import type { TimeUnit } from '@ahoo-wang/wow-client/dsl';
import type { FieldDefinition, FieldKindId } from './field.js';
import { TimeUnits } from './wire.js';

/**
 * How a time field keeps its time, which is how a condition on it has to
 * write one — Wow's own `QuerySemanticType` for time, named as Wow names it.
 *
 * - `epoch` is Wow's `TEMPORAL_EPOCH`: an integer count of `timeUnit` since
 *   the epoch, milliseconds when unsaid. Every time a Wow snapshot carries —
 *   `eventTime`, `firstEventTime`, an aggregate's `@QueryTemporal` longs — is
 *   one, and Wow's schema validation refuses anything but an integer there.
 * - `date` is Wow's `TEMPORAL_DATE`: the store's own date type, which a query
 *   names in ISO 8601 text.
 *
 * Wow's third, `TEMPORAL_FORMATTED`, a string under a pattern, is not
 * modelled: no definition has needed one, and a bound written under the
 * wrong pattern would be compared as text and match the wrong rows quietly.
 */
export type FieldTemporal = EpochTemporal | DateTemporal;

export interface EpochTemporal {
  type: 'epoch';
  /** Wow's `timeUnit`; milliseconds when unsaid, as Wow's own default is. */
  timeUnit?: EpochTimeUnit;
}

export interface DateTemporal {
  type: 'date';
}

/** The units of `TimeUnit` an epoch time is kept in, as Wow spells them. */
export type EpochTimeUnit = `${TimeUnit.MILLISECONDS | TimeUnit.SECONDS}`;

export const EPOCH_TIME_UNITS: readonly EpochTimeUnit[] = [
  TimeUnits.MILLISECONDS,
  TimeUnits.SECONDS,
];

export const TEMPORAL_TYPES: readonly FieldTemporal['type'][] = [
  'epoch',
  'date',
];

/** The kinds whose conditions write a time, and so may declare `temporal`. */
export const TEMPORAL_FIELD_KIND_IDS: readonly FieldKindId[] = [
  'date',
  'datetime',
];

/**
 * How finely a table cell writes a time of day (`FieldDefinition.timePrecision`).
 *
 * - `minute`, the default: a column of times is read for when something
 *   happened, and the seconds made every such column some 180 pixels wide —
 *   the largest source of sideways scrolling in a record workbench (second
 *   review R2-23). The cell drops the year within the current one, too.
 * - `second`: for a field whose seconds are the point, an event stream's
 *   times above all, where two events a few seconds apart are the story.
 *
 * Only the table's cell is short. Its title, the record's detail, a card and
 * a file keep the whole time, seconds included.
 */
export type TimePrecision = 'minute' | 'second';

export const TIME_PRECISIONS: readonly TimePrecision[] = ['minute', 'second'];

/**
 * What a time field that declares nothing is: epoch milliseconds.
 *
 * The default is the engine's audience rather than the fixtures' habit. A
 * Wow snapshot keeps every time as epoch milliseconds, and a definition
 * written from a Wow query schema should come out right without the author
 * having to remember one more member on each of them; a store that keeps a
 * native date is the case that says so.
 */
export const DEFAULT_TEMPORAL: Required<EpochTemporal> = {
  type: 'epoch',
  timeUnit: TimeUnits.MILLISECONDS,
};

/** How this field keeps its time: its declaration, or the default. */
export function temporalOf(field: FieldDefinition): FieldTemporal {
  return field.temporal ?? DEFAULT_TEMPORAL;
}

/**
 * The unit this field's epoch numbers count in, where it is not the
 * default milliseconds — `undefined` for milliseconds and for a field that
 * keeps no epoch at all. What reads a value as a time (`readInstant`) takes
 * it, so a cell, a footer and an earliest agree with the condition that
 * wrote the time in that unit.
 */
export function epochUnitOf(
  field: Pick<FieldDefinition, 'kind' | 'cell' | 'temporal'>,
): EpochTimeUnit | undefined {
  const temporal = field.temporal;
  return temporal?.type === 'epoch' && temporal.timeUnit === TimeUnits.SECONDS
    ? TimeUnits.SECONDS
    : undefined;
}
