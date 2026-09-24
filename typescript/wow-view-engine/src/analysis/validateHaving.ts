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
  AnalysisHavingExpression,
  AnalysisViewConfig,
  DataViewDefinition,
  Issue,
  IssuePath,
  RuntimeLimits,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { budgetIssues, checkTreeBudget, havingChildren } from './budget.js';
import { aliasesOf } from './validateAliases.js';

export function validateHaving(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  limits: RuntimeLimits,
  /**
   * The metrics that are moments (`momentMetrics`). A having compares a
   * metric with a number, and the earliest of a date is no number anybody
   * types, so it is refused as a sample value is.
   */
  moments: ReadonlySet<string> = new Set(),
): Issue[] {
  if (!config.having) return [];
  // Having is a declared capability like expressions; an undeclared one is
  // refused before its shape is even walked.
  if (capability.having !== true)
    return [issue('analysis.having.undeclared', ['having'])];
  // Having filters the grouped rows, so it needs rows to filter: an ungrouped
  // aggregation is one row, and Wow refuses a having over it.
  if (config.groups.length === 0)
    return [issue('analysis.having.requires-group', ['having'])];

  // The budget first, iteratively, so the recursive walk below never sees a
  // tree that could exhaust the stack.
  const overrun = checkTreeBudget(config.having, havingChildren, limits, {
    nodes: 0,
  });
  if (overrun) return budgetIssues('having', overrun, ['having'], limits);

  const { nonAnyMetrics } = aliasesOf(config);

  const walk = (
    expression: AnalysisHavingExpression,
    path: IssuePath,
  ): Issue[] => {
    // A having arrives from a store: a number, or a null inside a group's
    // operands, is a finding at its own depth rather than a crash.
    if (typeof expression !== 'object' || expression === null)
      return [issue('analysis.having.malformed', path)];
    if ('operands' in expression) {
      // A group whose operands are not an array cannot be walked; report it
      // rather than crash on a shape this version does not know.
      if (!Array.isArray(expression.operands))
        return [issue('analysis.having.malformed', path)];
      return expression.operands.flatMap((operand, index) =>
        walk(operand, [...path, 'operands', index]),
      );
    }
    return nonAnyMetrics.has(expression.metric) &&
      !moments.has(expression.metric)
      ? []
      : [
          issue('analysis.having.unknown-metric', [...path, 'metric'], {
            metric: expression.metric,
          }),
        ];
  };

  return walk(config.having, ['having']);
}
