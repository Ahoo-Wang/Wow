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

import type { AnalysisHavingExpression } from '../model/index.js';

/**
 * 「只保留」 (D20 屏 B; Wow `having`): the grouped rows kept, said as rows of
 * one comparison each — 「金额总和 大于 10,000」 — all of which must hold.
 * Wow's having is a small expression language (conditions, ranges, sets,
 * null checks, and/or trees); the tray offers the one shape an analyst
 * reaches for, a conjunction of comparisons, and reads any other shape a
 * stored config holds as "not editable here" rather than flattening it
 * into something it is not.
 */

/** The comparisons one row may make: the model's, never listed again. */
export type HavingOperator = Extract<
  AnalysisHavingExpression,
  { type: 'CONDITION' }
>['operator'];

/**
 * Every comparison, in the order the select lists them. A `Record` over the
 * operator type rather than an array, so an operator Wow adds is a compile
 * error here until it is given a place — not a choice the tray silently
 * never offers.
 */
const HAVING_OPERATOR_ORDER: Record<HavingOperator, true> = {
  GT: true,
  GTE: true,
  LT: true,
  LTE: true,
  EQ: true,
  NE: true,
};

export const HAVING_OPERATORS = Object.keys(
  HAVING_OPERATOR_ORDER,
) as readonly HavingOperator[];

/** One row of the editor: keep the groups whose metric compares so. */
export interface HavingRow {
  metric: string;
  operator: HavingOperator;
  /** `null` while the row is being filled in. */
  value: number | null;
}

/**
 * The rows a having expression is made of, or `null` when it is a shape the
 * rows cannot say: a range, a set, a null check, an OR anywhere. A stored
 * config may hold those; the editor then shows the expression is there
 * and offers to clear it, but does not pretend to edit it.
 */
export function havingRows(
  having: AnalysisHavingExpression | undefined,
): HavingRow[] | null {
  if (!having) return [];
  if (having.type === 'CONDITION')
    return [
      {
        metric: having.metric,
        operator: having.operator,
        value: having.value,
      },
    ];
  if (having.type !== 'AND') return null;
  const rows: HavingRow[] = [];
  for (const operand of having.operands) {
    const inner = havingRows(operand);
    if (inner === null || operand.type === 'AND') return null;
    rows.push(...inner);
  }
  return rows;
}

/**
 * The expression the rows say: one condition on its own, several under
 * one AND, none at all as no having. A row still being filled in — no
 * value yet — is written as a condition on `NaN` is not: it is left out
 * of the query and kept only by the editor, so the config on disk is
 * always one Wow accepts.
 */
export function withHavingRows(
  rows: readonly HavingRow[],
): AnalysisHavingExpression | undefined {
  const complete = rows.filter(
    (row): row is HavingRow & { value: number } => row.value !== null,
  );
  const conditions: AnalysisHavingExpression[] = complete.map(row => ({
    type: 'CONDITION',
    metric: row.metric,
    operator: row.operator,
    value: row.value,
  }));
  if (conditions.length === 0) return undefined;
  if (conditions.length === 1) return conditions[0];
  return {
    type: 'AND',
    operands: conditions as [
      AnalysisHavingExpression,
      ...AnalysisHavingExpression[],
    ],
  };
}
