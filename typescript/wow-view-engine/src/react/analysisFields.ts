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

import type { AnalysisScope } from '../analysis/index.js';
import type { FieldKindRegistry } from '../filter/index.js';
import {
  datePartsOf,
  isSingleStringField,
  type AnalysisDatePart,
  type AnalysisDateUnit,
  type AnalysisFunction,
  type AnalysisGroupType,
  type FieldDefinition,
} from '../model/index.js';

/** One field and what the definition allows doing with it. */
export interface AnalysisFieldOption {
  field: string;
  label: string;
  /** Group types this field offers; empty when it cannot be grouped. */
  groups: AnalysisGroupType[];
  /** Aggregation functions it offers; empty when it cannot be measured. */
  functions: AnalysisFunction[];
  dateUnits: AnalysisDateUnit[];
  /** The calendar parts a `DATE_PART` dimension on it may take (`datePartsOf`). */
  dateParts: AnalysisDatePart[];
  distinctCount: boolean;
  percentile: boolean;
  any: boolean;
  /** Whether it offers an opening and a closing value (FIRST / LAST). */
  firstLast: boolean;
  /** Whether a dimension on it may keep records missing the value as a group of their own. */
  missingKey: boolean;
  /** Whether its values are the steps of one process (`steps`): a funnel's. */
  steps?: boolean;
  /**
   * `false` where a formula may not take it as an operand
   * (`expressionInput`); absent or `true` where it may.
   */
  expressionInput?: boolean;
  /**
   * How its values read (`cell ?? kind`): the earliest of a `datetime` is
   * worded 「最早」 where a number's smallest is 「最小」, and a date is no
   * operand of a formula. Absent when not known.
   */
  cell?: string;
}

/** What the definition allows doing with each field of a scope. */
export function fieldOptions(
  scope: AnalysisScope,
  kinds: FieldKindRegistry | undefined,
): AnalysisFieldOption[] {
  return [...scope.fields.values()].map((field: FieldDefinition) => {
    const aggregation = scope.aggregations.get(field.name);
    return {
      field: field.name,
      label: field.label,
      groups: aggregation?.groups ?? [],
      functions: aggregation?.functions ?? [],
      dateUnits: aggregation?.dateUnits ?? [],
      dateParts: datePartsOf(aggregation),
      distinctCount: aggregation?.distinctCount === true,
      percentile: aggregation?.percentile === true,
      any: aggregation?.any === true,
      firstLast: aggregation?.firstLast === true,
      missingKey:
        aggregation?.missingKey !== false &&
        isSingleStringField(field, kinds?.get(field.kind)),
      expressionInput: aggregation?.expressionInput !== false,
      steps: aggregation?.steps === true,
      cell: field.cell ?? field.kind,
    };
  });
}
