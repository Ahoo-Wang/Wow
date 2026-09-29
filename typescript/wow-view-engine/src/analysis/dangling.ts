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
  isValueMetric,
  type AnalysisHavingExpression,
  type AnalysisMetric,
  type AnalysisViewConfig,
} from '../model/index.js';

/** What a metric may be read by, beyond its being there. */
export interface DanglingFacts {
  /** The metrics that are moments (`momentMetrics`): no operand, no having. */
  moments?: ReadonlySet<string>;
  /** The metric types 「只保留」 may compare (`havingMetrics`); all when absent. */
  havingMetrics?: readonly string[];
}

export interface WithoutDangling {
  metrics: AnalysisViewConfig['metrics'];
  /** The same object when nothing in it moved; `undefined` when none is left. */
  having: AnalysisHavingExpression | undefined;
  /** The aliases of the derived metrics that left with what they read. */
  removed: string[];
}

/**
 * The metrics and the having that still read something, after an edit to
 * what is grouped or measured: the one rule every such edit follows, the
 * way the chart, the sort and the table columns follow the aliases.
 *
 * - **A derived metric leaves with an operand it can no longer read**: one
 *   gone, turned into a sample value or a moment, or declared after it —
 *   the rules `analysis.derived.unknown-metric` and `…moment-operand` hold
 *   it to. Walked in order, so a derived metric reading one that just left
 *   leaves too.
 * - **A having rule on a metric it can no longer compare no longer
 *   constrains**: it leaves an `AND`, and an `OR` it sits in leaves with it
 *   (one side holding for every group keeps every group). No rule left, no
 *   having; no dimension left, no having either — Wow has no groups to
 *   keep (`analysis.having.requires-group`).
 *
 * The metrics are never emptied here: when nothing is left the caller says
 * what an empty question becomes.
 */
export function withoutDangling(
  shape: Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'having'>,
  { moments = new Set(), havingMetrics }: DanglingFacts = {},
): WithoutDangling {
  const readable = new Set<string>();
  const removed: string[] = [];
  const metrics = shape.metrics.filter(metric => {
    if (
      metric.type === 'DERIVED' &&
      !derivedRefs(metric.expression).every(alias => readable.has(alias))
    ) {
      removed.push(metric.alias);
      return false;
    }
    if (!isValueMetric(metric) && !moments.has(metric.alias))
      readable.add(metric.alias);
    return true;
  });
  const comparable = new Set(
    metrics
      .filter(
        metric =>
          readable.has(metric.alias) &&
          (havingMetrics === undefined || havingMetrics.includes(metric.type)),
      )
      .map(metric => metric.alias),
  );
  const having =
    shape.having === undefined || shape.groups.length === 0
      ? undefined
      : kept(shape.having, comparable);
  return {
    metrics:
      removed.length === 0
        ? shape.metrics
        : (metrics as AnalysisViewConfig['metrics']),
    having,
    removed,
  };
}

/** The node less its rules on metrics gone; `undefined` when it keeps all. */
function kept(
  node: AnalysisHavingExpression,
  comparable: ReadonlySet<string>,
): AnalysisHavingExpression | undefined {
  // A shape admission refuses is left for admission to say.
  if (typeof node !== 'object' || node === null) return node;
  if (!('operands' in node))
    return comparable.has(node.metric) ? node : undefined;
  if (!Array.isArray(node.operands)) return node;
  const operands = node.operands.map(operand => kept(operand, comparable));
  if (operands.every((operand, at) => operand === node.operands[at]))
    return node;
  if (node.type === 'OR' && operands.includes(undefined)) return undefined;
  const left = operands.filter(
    (operand): operand is AnalysisHavingExpression => operand !== undefined,
  );
  return left.length === 0
    ? undefined
    : {
        ...node,
        operands: left as [
          AnalysisHavingExpression,
          ...AnalysisHavingExpression[],
        ],
      };
}

/** The aliases a derived expression reads. */
export function derivedRefs(
  expression: Extract<AnalysisMetric, { type: 'DERIVED' }>['expression'],
): string[] {
  switch (expression?.type) {
    case 'METRIC_REF':
      return [expression.metric];
    case 'BINARY':
      return [
        ...derivedRefs(expression.left),
        ...derivedRefs(expression.right),
      ];
    default:
      return [];
  }
}
