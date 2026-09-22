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
  AnalysisViewConfig,
  DataViewDefinition,
  Issue,
  IssuePath,
  RuntimeLimits,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import {
  outOfScopeNames,
  unknownOrOutside,
  withOutOfScope,
  type AnalysisScope,
} from './capability.js';
import type { BudgetCounter } from './budget.js';
import {
  budgetedDerivedIssues,
  budgetedExpressionIssues,
} from './expressions.js';
import { queryFilterIssues } from './queryFilter.js';

export function validateMetrics(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const issues: Issue[] = [];
  // Wow refuses `metrics must not be empty.`; an aggregation with nothing to
  // compute is caught here rather than by the server.
  if (config.metrics.length === 0)
    issues.push(issue('analysis.metrics.empty', ['metrics']));

  // DERIVED may only reach metrics declared before it, which rules out both
  // forward references and cycles by construction.
  const earlier = new Set<string>();
  const expressionsAllowed = capability.expressions === true;
  // One node budget over every aggregate expression and another over every
  // derived one, as Wow counts them.
  const expressionNodes: BudgetCounter = { nodes: 0 };
  const derivedNodes: BudgetCounter = { nodes: 0 };

  config.metrics.forEach((metric, index) => {
    const path: IssuePath = ['metrics', index];
    const declared = (field: string) => scope.aggregations.get(field);

    // A metric's own filter was compiled and never admitted, so it could name
    // a field that does not exist and reach `compileFilter`, which answers
    // that by throwing.
    //
    // `DERIVED` is the exception. It carries no filter in the protocol and
    // `compileMetric` never emits one, but a stored config can still hold a
    // stale `filter` from before the metric was switched to `DERIVED`.
    // Refusing it would block a config over a property that changes nothing.
    if (metric.type !== 'DERIVED' && 'filter' in metric && metric.filter)
      issues.push(
        ...queryFilterIssues({
          tree: metric.filter,
          fields: withOutOfScope(scope, scope.fields),
          outOfScope: outOfScopeNames(scope, scope.fields),
          kinds,
          limits,
          position: 'metric',
          path: [...path, 'filter'],
        }),
      );

    switch (metric.type) {
      case 'COUNT':
        if (!capability.count)
          issues.push(issue('analysis.count.undeclared', path));
        break;
      case 'NUMERIC': {
        issues.push(
          ...budgetedExpressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
            limits,
            expressionNodes,
          ),
        );
        if (metric.expression?.type === 'FIELD') {
          const entry = declared(metric.expression.field);
          if (entry && !entry.functions.includes(metric.function as never))
            issues.push(
              issue('analysis.function.unsupported', [...path, 'function'], {
                field: metric.expression.field,
                fn: metric.function,
              }),
            );
        }
        break;
      }
      case 'ANY': {
        const entry = declared(metric.field);
        if (!entry)
          issues.push(
            issue(unknownOrOutside(scope, metric.field), [...path, 'field'], {
              field: metric.field,
            }),
          );
        else if (!entry.any)
          issues.push(
            issue('analysis.any.undeclared', path, { field: metric.field }),
          );
        break;
      }
      case 'DISTINCT_COUNT': {
        issues.push(
          ...budgetedExpressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
            limits,
            expressionNodes,
          ),
        );
        if (metric.expression?.type === 'FIELD') {
          const entry = declared(metric.expression.field);
          if (entry && !entry.distinctCount)
            issues.push(
              issue('analysis.distinctCount.undeclared', path, {
                field: metric.expression.field,
              }),
            );
        }
        break;
      }
      case 'PERCENTILE': {
        issues.push(
          ...budgetedExpressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
            limits,
            expressionNodes,
          ),
        );
        if (metric.expression?.type === 'FIELD') {
          const entry = declared(metric.expression.field);
          if (entry && !entry.percentile)
            issues.push(
              issue('analysis.percentile.undeclared', path, {
                field: metric.expression.field,
              }),
            );
        }
        // Wow accepts the open interval only.
        if (
          !Number.isFinite(metric.percentile) ||
          metric.percentile <= 0 ||
          metric.percentile >= 100
        )
          issues.push(
            issue('analysis.percentile.out-of-range', [...path, 'percentile']),
          );
        break;
      }
      case 'DERIVED': {
        if (!expressionsAllowed)
          issues.push(issue('analysis.expressions.undeclared', path));
        issues.push(
          ...budgetedDerivedIssues(
            metric.expression,
            earlier,
            [...path, 'expression'],
            limits,
            derivedNodes,
          ),
        );
        break;
      }
      default:
        // A type this version does not know is a finding, not a fall-through:
        // `compileMetric` has no mapping for it and must never be reached.
        issues.push(
          issue('analysis.metric.type-unknown', [...path, 'type'], {
            type: String((metric as { type: unknown }).type),
          }),
        );
    }

    if (metric.type !== 'ANY') earlier.add(metric.alias);
  });

  return issues;
}
