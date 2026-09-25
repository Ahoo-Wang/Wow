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
  HavingExpressionType,
  type AggregationQuery,
  type FilterExpression,
  type HavingExpression,
} from '@ahoo-wang/wow-client';

/**
 * What a query's conditions weigh as a Wow service's query guard weighs
 * them (`QueryBudget` in wow-query): every node of every filter the query
 * carries — its own, an element's, each metric's — then every node of its
 * 「只保留」, counted on one tally; and the longest list of values any one
 * node holds.
 */
export interface QueryWeight {
  nodes: number;
  /** The most values one node carries: an `IN` list, `IDS`, a having `IN`. */
  values: number;
}

/**
 * The weight of the filters a query sends and, for an aggregation, of its
 * having, counted as the guard counts the compiled query — not the config's
 * tree, where a date range is one condition and three nodes once compiled.
 * The caller's own scope, which the service adds from who is asking, is not
 * the engine's to see and is not in it.
 */
export function queryWeight(
  filters: readonly FilterExpression[],
  having?: HavingExpression,
): QueryWeight {
  const weight: QueryWeight = { nodes: 0, values: 0 };
  const pending: FilterExpression[] = [...filters];
  while (pending.length > 0) {
    const node = pending.pop() as FilterExpression;
    weight.nodes += 1;
    if ('operands' in node) pending.push(...node.operands);
    if ('predicate' in node) pending.push(node.predicate);
    if ('values' in node)
      weight.values = Math.max(weight.values, node.values.length);
  }
  const havings: HavingExpression[] = having ? [having] : [];
  while (havings.length > 0) {
    const node = havings.pop() as HavingExpression;
    weight.nodes += 1;
    if ('operands' in node) havings.push(...node.operands);
    weight.values = Math.max(weight.values, havingValues(node));
  }
  return weight;
}

/**
 * The weight of what an aggregation query sends; see `queryWeight`. As the
 * guard reads it, a filter left out is `MATCH_ALL` and counts as one node —
 * the query's own and each element's — while a metric's counts only when it
 * narrows anything.
 */
export function aggregationWeight(query: AggregationQuery): QueryWeight {
  return queryWeight(
    [
      query.filter ?? MATCH_ALL,
      ...(query.elements ?? []).map(element => element.filter ?? MATCH_ALL),
      ...query.metrics.flatMap(metric =>
        'filter' in metric && metric.filter && !isMatchAll(metric.filter)
          ? [metric.filter]
          : [],
      ),
    ],
    query.having,
  );
}

const MATCH_ALL = { op: 'MATCH_ALL' } as FilterExpression;

function isMatchAll(filter: FilterExpression): boolean {
  return filter.op === ('MATCH_ALL' as FilterExpression['op']);
}

function havingValues(node: HavingExpression): number {
  switch (node.type) {
    case HavingExpressionType.CONDITION:
      return 1;
    case HavingExpressionType.BETWEEN:
      return 2;
    case HavingExpressionType.IN:
      return node.values.length;
    default:
      return 0;
  }
}
