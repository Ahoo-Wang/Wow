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

type Shape = Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'having'>;

/** The config an edit started from, and what its metrics were read by. */
export interface DanglingBefore {
  shape: Pick<AnalysisViewConfig, 'groups' | 'metrics'>;
  facts?: DanglingFacts;
}

/**
 * The metrics and the having an edit to what is grouped or measured leaves
 * reading something: the one rule every such edit follows, the way the
 * chart, the sort and the table columns follow the aliases.
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
 * Only what the edit broke goes (`before`, the config it started from): a
 * derived metric or a rule that already read nothing stays, for admission
 * to point at, rather than vanishing under an edit about something else,
 * and so does a metric the edit itself changed (not the object `before`
 * held) — the card being typed into is not fallout.
 * Without `before`, everything that reads nothing goes. The metrics may
 * come out empty: the caller says what an empty question becomes.
 */
export function withoutDangling(
  shape: Shape,
  facts: DanglingFacts = {},
  before?: DanglingBefore,
): WithoutDangling {
  const dangling = danglingIn(shape.metrics, facts);
  const was = before && danglingIn(before.shape.metrics, before.facts ?? {});
  // Fallout is a metric this edit left as it was that reads nothing now and
  // read something before; one it changed is the author's, mid-edit, and
  // stays for admission to point at, as one broken already does.
  const untouched = (metric: AnalysisMetric) =>
    before === undefined ||
    before.shape.metrics.some(
      entry => entry === metric && !was?.has(entry.alias),
    );
  const removed = shape.metrics
    .filter(metric => dangling.has(metric.alias) && untouched(metric))
    .map(metric => metric.alias);
  const metrics =
    removed.length === 0
      ? shape.metrics
      : (shape.metrics.filter(
          metric => !removed.includes(metric.alias),
        ) as AnalysisViewConfig['metrics']);
  // A rule stands unless it compared something before and cannot now.
  const now = comparableIn(metrics, facts);
  const then = before && comparableIn(before.shape.metrics, before.facts ?? {});
  const stands = (metric: string) =>
    now.has(metric) || (then !== undefined && !then.has(metric));
  const ungrouped =
    shape.groups.length === 0 &&
    (before === undefined || before.shape.groups.length > 0);
  const having =
    shape.having === undefined || ungrouped
      ? undefined
      : kept(shape.having, stands);
  return { metrics, having, removed };
}

/** The derived metrics of a list that read nothing, in cascade. */
function danglingIn(
  metrics: readonly AnalysisMetric[],
  { moments = new Set() }: DanglingFacts,
): Set<string> {
  const readable = new Set<string>();
  const dangling = new Set<string>();
  for (const metric of metrics) {
    if (
      metric.type === 'DERIVED' &&
      !derivedRefs(metric.expression).every(alias => readable.has(alias))
    )
      dangling.add(metric.alias);
    else if (!isValueMetric(metric) && !moments.has(metric.alias))
      readable.add(metric.alias);
  }
  return dangling;
}

/**
 * The metrics a having may compare, as admission reads them
 * (`validateHaving`): any but a sample value or a moment, of a type the
 * source compares.
 */
function comparableIn(
  metrics: readonly AnalysisMetric[],
  { moments = new Set(), havingMetrics }: DanglingFacts,
): Set<string> {
  return new Set(
    metrics
      .filter(
        metric =>
          !isValueMetric(metric) &&
          !moments.has(metric.alias) &&
          (havingMetrics === undefined || havingMetrics.includes(metric.type)),
      )
      .map(metric => metric.alias),
  );
}

/** The node less the rules that no longer stand; `undefined` when it keeps all. */
function kept(
  node: AnalysisHavingExpression,
  stands: (metric: string) => boolean,
): AnalysisHavingExpression | undefined {
  // A shape admission refuses is left for admission to say.
  if (typeof node !== 'object' || node === null) return node;
  if (!('operands' in node)) return stands(node.metric) ? node : undefined;
  if (!Array.isArray(node.operands)) return node;
  const operands = node.operands.map(operand => kept(operand, stands));
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
