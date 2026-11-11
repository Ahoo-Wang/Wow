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
 * Reading a descriptor's semantic: whether a time field's declared unit
 * matches the one the model keeps (`checkTemporal`), how a semantic is
 * named in a finding (`semanticText`), and the numeric format a decimal or
 * money semantic gives a field (`numericOf`).
 */

import type { QuerySemanticType } from '@ahoo-wang/wow-client/dsl';
import {
  CURRENCY_CODE_PATTERN,
  isFieldName,
  MAX_NUMERIC_SCALE,
  temporalOf,
  TEMPORAL_FIELD_KIND_IDS,
  QuerySemanticTypes,
  TimeUnits,
  type FieldDefinition,
  type FieldNumeric,
  type FieldTemporal,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';

import type { FieldContext } from './fields.js';

/**
 * A time condition is written in the unit the definition declares, so a
 * descriptor that keeps the time another way means every date condition
 * would be sent wrong. That is the definition's mistake, not a capability
 * the deployment lacks, so it is an error.
 */
export function checkTemporal(
  field: FieldDefinition,
  path: string,
  semantic: QuerySemanticType | undefined,
  at: IssuePath,
  context: FieldContext,
): void {
  if (!semantic || !TEMPORAL_FIELD_KIND_IDS.includes(field.kind)) return;
  const declared = temporalOf(field);
  if (sameTemporal(declared, semantic)) return;
  context.findings.push(
    issue('capability.field.temporal-mismatch', at, {
      field: path,
      declared: temporalText(declared),
      described: semanticText(semantic),
    }),
  );
}

function sameTemporal(
  declared: FieldTemporal,
  semantic: QuerySemanticType,
): boolean {
  if (semantic.type === QuerySemanticTypes.TEMPORAL_DATE)
    return declared.type === 'date';
  if (
    semantic.type !== QuerySemanticTypes.TEMPORAL_EPOCH ||
    declared.type !== 'epoch'
  )
    return false;
  return (
    (declared.timeUnit ?? TimeUnits.MILLISECONDS) ===
    (semantic.timeUnit ?? TimeUnits.MILLISECONDS)
  );
}

function temporalText(temporal: FieldTemporal): string {
  return temporal.type === 'date'
    ? 'date'
    : `epoch ${temporal.timeUnit ?? TimeUnits.MILLISECONDS}`;
}

/**
 * The descriptor's semantic as the mismatch names it — every kind of it,
 * so a time field the model keeps as money says `money CNY`, never `date`.
 */
export function semanticText(semantic: QuerySemanticType): string {
  switch (semantic.type) {
    case QuerySemanticTypes.TEMPORAL_DATE:
      return 'date';
    case QuerySemanticTypes.TEMPORAL_EPOCH:
      return `epoch ${semantic.timeUnit ?? TimeUnits.MILLISECONDS}`;
    case QuerySemanticTypes.TEMPORAL_FORMATTED:
      return `text ${semantic.pattern}`;
    case QuerySemanticTypes.DECIMAL:
      return `decimal scale ${semantic.scale}`;
    case QuerySemanticTypes.MONEY:
      return semantic.currency !== undefined
        ? `money ${semantic.currency} scale ${semantic.scale}`
        : `money by ${semantic.currencyField} scale ${semantic.scale}`;
    default:
      return (semantic as { type: string }).type;
  }
}

/**
 * A numeric semantic as the definition keeps it (`FieldNumeric`), or
 * `undefined` for a temporal one, and for one whose scale or currency the
 * engine could not write — a descriptor is data from another service, so
 * it is read rather than trusted, and a number left plain is better than a
 * format Intl refuses.
 */
export function numericOf(
  semantic: QuerySemanticType | undefined,
): FieldNumeric | undefined {
  if (
    !semantic ||
    (semantic.type !== QuerySemanticTypes.DECIMAL &&
      semantic.type !== QuerySemanticTypes.MONEY)
  )
    return undefined;
  const scale = semantic.scale;
  if (!Number.isInteger(scale) || scale < 0 || scale > MAX_NUMERIC_SCALE)
    return undefined;
  if (semantic.type === QuerySemanticTypes.DECIMAL)
    return { type: 'decimal', scale };
  if (typeof semantic.currency === 'string')
    return CURRENCY_CODE_PATTERN.test(semantic.currency)
      ? { type: 'money', scale, currency: semantic.currency.toUpperCase() }
      : undefined;
  return typeof semantic.currencyField === 'string' &&
    isFieldName(semantic.currencyField)
    ? { type: 'money', scale, currencyField: semantic.currencyField }
    : undefined;
}
