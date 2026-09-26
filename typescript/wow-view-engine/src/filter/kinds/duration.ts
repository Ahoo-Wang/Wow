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

import type { ComparisonOperator, DateDiffUnit } from '@ahoo-wang/wow-client';
import {
  aggregation,
  filter,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import {
  TEMPORAL_FIELD_KIND_IDS,
  type FieldDefinition,
  type FilterOperatorName,
  type Issue,
  type IssuePath,
} from '../../model/index.js';
import type { FieldKindDescription } from '../describe.js';
import { issue, readValue } from '../fieldKind.js';
import { isDurationFilterValue, type DurationFilterValue } from '../values.js';

/**
 * A time since another moment (N3, Wow's `EXPRESSION` over a `DATE_DIFF`),
 * as a time field's own condition: the field is the later moment, the value
 * names the earlier one, how the time between them compares, and the unit.
 * Offered by the time kinds; the descriptor takes it away where the entry
 * lists no `EXPRESSION` among its root operators.
 */
export const DURATION_OPERATOR: FilterOperatorName = 'EXPRESSION';

export function isDurationOperator(operator: FilterOperatorName): boolean {
  return operator === DURATION_OPERATOR;
}

/** Whether a field holds a moment a time since could run from or to. */
export function holdsTime(field: Pick<FieldDefinition, 'kind'>): boolean {
  return (TEMPORAL_FIELD_KIND_IDS as readonly string[]).includes(field.kind);
}

/** Still being written: no earlier moment picked yet. */
export function isBlankDuration(value: unknown): boolean {
  return (
    value === null ||
    value === undefined ||
    (typeof value === 'object' &&
      !Array.isArray(value) &&
      ((value as { from?: unknown }).from ?? '') === '')
  );
}

/**
 * The value's shape, and the earlier moment it names: a field of the same
 * set, holding a time, other than the field itself — a time since itself is
 * always nothing.
 */
export function validateDuration(
  value: unknown,
  field: FieldDefinition,
  fields: readonly FieldDefinition[] | undefined,
  path: IssuePath,
): Issue[] {
  if (!isDurationFilterValue(value))
    return [issue('filter.value.expected-duration', path)];
  if (value.from === field.name)
    return [
      issue('filter.value.duration-same-time', path, { field: value.from }),
    ];
  if (fields === undefined) return [];
  const from = fields.find(entry => entry.name === value.from);
  if (!from)
    return [
      issue('filter.value.duration-from-unknown', path, { field: value.from }),
    ];
  return holdsTime(from)
    ? []
    : [
        issue('filter.value.duration-from-not-time', path, {
          field: value.from,
        }),
      ];
}

/** `DATE_DIFF(from, field, unit) comparison value`, as Wow reads it. */
export function compileDuration(
  field: FieldDefinition,
  stored: unknown,
): FilterExpression {
  const value = readValue<DurationFilterValue>(stored);
  return filter.expression(
    aggregation.dateDiff(value.from, field.name, value.unit as DateDiffUnit),
    value.comparison as ComparisonOperator,
    value.value,
  );
}

/** 「发货时间 since 付款时间 > 48 hours」: the parts `/ui` words. */
export function describeDuration(
  stored: unknown,
  field: FieldDefinition,
  fields: readonly FieldDefinition[] | undefined,
): FieldKindDescription {
  if (!isDurationFilterValue(stored))
    return { text: field.label, value: { kind: 'blank' } };
  const from =
    fields?.find(entry => entry.name === stored.from)?.label ?? stored.from;
  return {
    text: `${field.label} since ${from} ${stored.comparison} ${stored.value} ${stored.unit.toLowerCase()}`,
    value: {
      kind: 'duration',
      from,
      comparison: stored.comparison,
      amount: stored.value,
      unit: stored.unit,
    },
  };
}
