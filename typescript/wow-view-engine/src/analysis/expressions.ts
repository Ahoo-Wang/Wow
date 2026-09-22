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
  AnalysisDerivedExpression,
  AnalysisExpression,
  Issue,
  IssuePath,
  RuntimeLimits,
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
): Issue[] {
  // A config arrives from a store, so a metric may carry no expression at all
  // or one of a shape this version does not know. Both are findings, not
  // crashes: the kernel's contract is a definition and a config in, a result
  // or an Issue out.
  if (!isExpression(expression))
    return [issue('analysis.expression.malformed', path)];

  // An expression is computed inside the counting unit, so its fields are the
  // innermost element's; one from outside exists but is out of reach.
  if (expression.type === 'FIELD')
    return scope.aggregations.has(expression.field)
      ? []
      : [
          issue(unknownOrOutside(scope, expression.field), [...path, 'field'], {
            field: expression.field,
          }),
        ];

  if (expression.type === 'CONSTANT')
    return Number.isFinite(expression.value)
      ? []
      : [issue('analysis.constant.not-finite', [...path, 'value'])];

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
    ),
    ...expressionIssues(
      expression.right,
      scope,
      [...path, 'right'],
      expressionsAllowed,
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
): Issue[] {
  const overrun = checkTreeBudget(expression, binaryChildren, limits, counted);
  return overrun
    ? budgetIssues('expression', overrun, path, limits)
    : expressionIssues(expression, scope, path, expressionsAllowed);
}

/** `derivedIssues` behind the budget, which decides whether it runs. */
export function budgetedDerivedIssues(
  expression: AnalysisDerivedExpression | undefined,
  available: ReadonlySet<string>,
  path: IssuePath,
  limits: RuntimeLimits,
  counted: BudgetCounter,
): Issue[] {
  const overrun = checkTreeBudget(expression, binaryChildren, limits, counted);
  return overrun
    ? budgetIssues('expression', overrun, path, limits)
    : derivedIssues(expression, available, path);
}

/** Whether a value can be read as one of the three expression shapes. */
function isExpression(value: AnalysisExpression | undefined): boolean {
  const type = (value as { type?: unknown } | undefined)?.type;
  return type === 'FIELD' || type === 'CONSTANT' || type === 'BINARY';
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
): Issue[] {
  // A DERIVED expression arrives from a store like any other part of the
  // config, so it is admitted before it is walked.
  if (!isDerivedExpression(expression))
    return [issue('analysis.expression.malformed', path)];

  if (expression.type === 'METRIC_REF')
    return available.has(expression.metric)
      ? []
      : [
          issue('analysis.derived.unknown-metric', [...path, 'metric'], {
            metric: expression.metric,
          }),
        ];
  if (expression.type === 'CONSTANT')
    return Number.isFinite(expression.value)
      ? []
      : [issue('analysis.constant.not-finite', [...path, 'value'])];
  return [
    ...derivedIssues(expression.left, available, [...path, 'left']),
    ...derivedIssues(expression.right, available, [...path, 'right']),
  ];
}
