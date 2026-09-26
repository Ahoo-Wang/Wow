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

import {
  isDateCell,
  type AnalysisDerivedExpression,
  type AnalysisExpression,
  type Issue,
  type IssuePath,
  type RuntimeLimits,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { unknownOrOutside, type AnalysisScope } from './capability.js';
import {
  binaryChildren,
  budgetIssues,
  checkTreeBudget,
  type BudgetCounter,
} from './budget.js';

function expressionIssues(
  expression: AnalysisExpression,
  scope: AnalysisScope,
  path: IssuePath,
  expressionsAllowed: boolean,
  /** The units a time between two moments may be measured in (`dateDiffUnitsOf`). */
  units: readonly string[],
  /** Whether this is an operand of arithmetic rather than the metric's field. */
  operand = false,
): Issue[] {
  // A config arrives from a store, so a metric may carry no expression at all
  // or one of a shape this version does not know. Both are findings, not
  // crashes: the kernel's contract is a definition and a config in, a result
  // or an Issue out.
  if (!isExpression(expression))
    return [issue('analysis.expression.malformed', path)];

  // An expression is computed inside the counting unit, so its fields are the
  // innermost element's; one from outside exists but is out of reach.
  if (expression.type === 'FIELD') {
    if (!scope.aggregations.has(expression.field))
      return [
        issue(unknownOrOutside(scope, expression.field), [...path, 'field'], {
          field: expression.field,
        }),
      ];
    // A moment is no operand: a sum of two dates is nothing, and a difference
    // of two is a duration, which this engine has no reading for — it would
    // print as a count of milliseconds nobody asked for.
    const field = scope.fields.get(expression.field);
    if (operand && field && isDateCell(field.cell ?? field.kind))
      return [
        issue('analysis.expression.date-operand', [...path, 'field'], {
          field: expression.field,
        }),
      ];
    // The source may not take this field into arithmetic (its descriptor's
    // `expressionInput`).
    return operand &&
      scope.aggregations.get(expression.field)?.expressionInput === false
      ? [
          issue('analysis.expression.operand-unsupported', [...path, 'field'], {
            field: expression.field,
          }),
        ]
      : [];
  }

  if (expression.type === 'CONSTANT')
    return Number.isFinite(expression.value)
      ? []
      : [issue('analysis.constant.not-finite', [...path, 'value'])];

  if (expression.type === 'DATE_DIFF')
    return dateDiffIssues(expression, scope, path, expressionsAllowed, units);

  const issues: Issue[] = [];
  if (!expressionsAllowed)
    issues.push(issue('analysis.expressions.undeclared', path));
  if (
    expression.operator === 'DIVIDE' &&
    expression.right?.type === 'CONSTANT' &&
    expression.right.value === 0
  )
    issues.push(
      issue('analysis.expression.divide-by-zero', [...path, 'right']),
    );

  issues.push(
    ...expressionIssues(
      expression.left,
      scope,
      [...path, 'left'],
      expressionsAllowed,
      units,
      true,
    ),
    ...expressionIssues(
      expression.right,
      scope,
      [...path, 'right'],
      expressionsAllowed,
      units,
      true,
    ),
  );
  return issues;
}

/** `expressionIssues` behind the budget, which decides whether it runs. */
export function budgetedExpressionIssues(
  expression: AnalysisExpression,
  scope: AnalysisScope,
  path: IssuePath,
  expressionsAllowed: boolean,
  limits: RuntimeLimits,
  counted: BudgetCounter,
  /** The units a time between two moments may be measured in (`dateDiffUnitsOf`). */
  units: readonly string[] = [],
): Issue[] {
  const overrun = checkTreeBudget(expression, binaryChildren, limits, counted);
  return overrun
    ? budgetIssues('expression', overrun, path, limits)
    : expressionIssues(expression, scope, path, expressionsAllowed, units);
}

/**
 * A time between two moments (N3): a computed expression, so the capability
 * must offer them (`expressions`); measured in a unit it offers; each end a
 * time the counting unit holds and may bucket by date — which is how Wow
 * knows a field holds a moment — and takes into arithmetic.
 */
function dateDiffIssues(
  expression: Extract<AnalysisExpression, { type: 'DATE_DIFF' }>,
  scope: AnalysisScope,
  path: IssuePath,
  expressionsAllowed: boolean,
  units: readonly string[],
): Issue[] {
  const issues: Issue[] = [];
  if (!expressionsAllowed)
    issues.push(issue('analysis.expressions.undeclared', path));
  else if (!units.includes(expression.unit))
    issues.push(
      issue('analysis.date-diff.unit-unsupported', [...path, 'unit'], {
        unit: String(expression.unit),
      }),
    );
  for (const end of ['from', 'to'] as const) {
    const field = expression[end];
    const at: IssuePath = [...path, end];
    const offered =
      typeof field === 'string' ? scope.aggregations.get(field) : undefined;
    if (typeof field !== 'string')
      issues.push(issue('analysis.expression.malformed', at));
    else if (!offered)
      issues.push(issue(unknownOrOutside(scope, field), at, { field }));
    else if (!offered.groups.includes('DATE_HISTOGRAM' as never))
      issues.push(issue('analysis.date-diff.not-time', at, { field }));
    else if (offered.expressionInput === false)
      issues.push(
        issue('analysis.expression.operand-unsupported', at, { field }),
      );
  }
  return issues;
}

/** `derivedIssues` behind the budget, which decides whether it runs. */
export function budgetedDerivedIssues(
  expression: AnalysisDerivedExpression | undefined,
  available: ReadonlySet<string>,
  path: IssuePath,
  limits: RuntimeLimits,
  counted: BudgetCounter,
  /** The metrics that are moments (`momentMetrics`): no operand is one. */
  moments: ReadonlySet<string> = new Set(),
): Issue[] {
  const overrun = checkTreeBudget(expression, binaryChildren, limits, counted);
  return overrun
    ? budgetIssues('expression', overrun, path, limits)
    : derivedIssues(expression, available, path, moments);
}

/** Whether a value can be read as one of the three expression shapes. */
function isExpression(value: AnalysisExpression | undefined): boolean {
  const type = (value as { type?: unknown } | undefined)?.type;
  return (
    type === 'FIELD' ||
    type === 'CONSTANT' ||
    type === 'BINARY' ||
    type === 'DATE_DIFF'
  );
}

/** The derived counterpart: one side going missing is a finding, not a crash. */
function isDerivedExpression(
  value: AnalysisDerivedExpression | undefined,
): value is AnalysisDerivedExpression {
  const type = (value as { type?: unknown } | undefined)?.type;
  return type === 'METRIC_REF' || type === 'CONSTANT' || type === 'BINARY';
}

function derivedIssues(
  expression: AnalysisDerivedExpression | undefined,
  available: ReadonlySet<string>,
  path: IssuePath,
  moments: ReadonlySet<string>,
): Issue[] {
  // A DERIVED expression arrives from a store like any other part of the
  // config, so it is admitted before it is walked.
  if (!isDerivedExpression(expression))
    return [issue('analysis.expression.malformed', path)];

  if (expression.type === 'METRIC_REF') {
    if (!available.has(expression.metric))
      return [
        issue('analysis.derived.unknown-metric', [...path, 'metric'], {
          metric: expression.metric,
        }),
      ];
    // The earliest or the latest of a date is no operand, for the reason a
    // date field is none in a formula.
    return moments.has(expression.metric)
      ? [
          issue('analysis.derived.moment-operand', [...path, 'metric'], {
            metric: expression.metric,
          }),
        ]
      : [];
  }
  if (expression.type === 'CONSTANT')
    return Number.isFinite(expression.value)
      ? []
      : [issue('analysis.constant.not-finite', [...path, 'value'])];
  return [
    ...derivedIssues(expression.left, available, [...path, 'left'], moments),
    ...derivedIssues(expression.right, available, [...path, 'right'], moments),
  ];
}
