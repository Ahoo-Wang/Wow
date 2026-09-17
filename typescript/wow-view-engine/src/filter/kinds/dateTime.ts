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

import { filter, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import type { FieldKindId } from '../../model/index.js';
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import { isValidTimeZone, resolveDateTimeRange } from '../time.js';
import { isDateTimeFilterValue, type DateTimeFilterValue } from '../values.js';
import {
  compilePresence,
  describePresence,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

function isParsableInstant(text: string): boolean {
  return !Number.isNaN(Date.parse(text));
}

function describeValue(value: DateTimeFilterValue): string {
  switch (value.type) {
    case 'absolute':
      return value.to === undefined
        ? `from ${value.from}`
        : `${value.from} ~ ${value.to}`;
    case 'relative':
      return `last ${value.amount} ${value.unit}`;
    case 'preset':
      return value.preset;
  }
}

/**
 * Dates are the reason configs store intent rather than compiled values: a
 * saved "last 7 days" must mean the last seven days on every later run, so the
 * window is resolved at compile time against the injected moment and zone.
 */
function createDateKind(id: FieldKindId, withTime: boolean): FieldKind {
  return {
    id,
    operators: ['BETWEEN', 'GTE', 'LTE', ...PRESENCE_OPERATORS],
    defaultOperator: 'BETWEEN',

    emptyValue() {
      return { type: 'preset', preset: 'today' } satisfies DateTimeFilterValue;
    },

    validate({ value, operator, path }) {
      if (isPresenceOperator(operator)) return [];
      if (!isDateTimeFilterValue(value))
        return [issue('filter.value.expected-date', path)];
      if (value.type === 'absolute') {
        if (!isParsableInstant(value.from))
          return [issue('filter.value.unparsable-date', path)];
        if (value.to !== undefined && !isParsableInstant(value.to))
          return [issue('filter.value.unparsable-date', path)];
        if (
          value.to !== undefined &&
          Date.parse(value.to) < Date.parse(value.from)
        )
          return [issue('filter.value.inverted-range', path)];
        // A zone no runtime knows makes the compiler throw rather than
        // produce a query, so it is refused here where it can be reported.
        if (value.timeZone !== undefined && !isValidTimeZone(value.timeZone))
          return [
            issue('filter.value.unknown-time-zone', path, {
              timeZone: value.timeZone,
            }),
          ];
      }
      return [];
    },

    compile({ leaf, field, now, timeZone }): FilterExpression {
      const presence = compilePresence(field.name, leaf.operator);
      if (presence) return presence;

      const value = readValue<DateTimeFilterValue>(leaf.value);
      // A condition's own zone is applied inside; what arrives here is the
      // runtime's, used for everything relative to the evaluation moment.
      const range = resolveDateTimeRange(value, now, timeZone);

      if (leaf.operator === 'GTE') return filter.gte(field.name, range.from);
      if (leaf.operator === 'LTE')
        return filter.lte(field.name, range.to ?? range.from);
      return range.to === undefined
        ? filter.gte(field.name, range.from)
        : filter.between(field.name, range.from, range.to);
    },

    editor(operator, _field, value) {
      if (isPresenceOperator(operator)) return { input: 'none' };
      if (isDateTimeFilterValue(value) && value.type === 'relative')
        return { input: 'relativeDate', withTime };
      if (operator === 'BETWEEN')
        return { input: 'dateRange', range: true, withTime };
      return { input: 'date', withTime };
    },

    describe({ leaf, field }) {
      const presence = describePresence(leaf.operator);
      if (presence) return `${field.label} ${presence}`;
      return `${field.label} ${describeValue(readValue<DateTimeFilterValue>(leaf.value))}`;
    },
  };
}

/** A calendar day, without a time of day. */
export const dateFieldKind: FieldKind = createDateKind('date', false);

/** An instant, with a time of day. */
export const dateTimeFieldKind: FieldKind = createDateKind('datetime', true);
