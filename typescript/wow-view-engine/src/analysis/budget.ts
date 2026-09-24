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

import type { Issue, IssuePath, RuntimeLimits } from '../model/index.js';
import { issue } from '../filter/index.js';

/**
 * The budget of an expression, derived or having tree, checked iteratively
 * before anything recurses into it.
 *
 * These trees arrive from a store like a filter does, and the walks below are
 * plain recursion, so without this a nested `BINARY` chain would exhaust the
 * stack before any rule ran. The filter budget is reused as the ceiling: Wow
 * caps an expression at the same depth 8 and 256 nodes it uses for nothing
 * else, and one pair of limits keeps the caller's override in one place.
 * Like Wow, depth is per tree and the node count is one budget over every
 * tree of one kind, so a config cannot slip past by spreading a large tree
 * across many metrics.
 */
export type BudgetOverrun = 'too-deep' | 'too-many-nodes';

const BUDGET_ISSUE_CODES = {
  expression: {
    'too-deep': 'analysis.expression.too-deep',
    'too-many-nodes': 'analysis.expression.too-many-nodes',
  },
  having: {
    'too-deep': 'analysis.having.too-deep',
    'too-many-nodes': 'analysis.having.too-many-nodes',
  },
} as const;

/** One node counter per tree kind, shared across the metrics of a config. */
export interface BudgetCounter {
  nodes: number;
}

export function checkTreeBudget(
  root: unknown,
  children: (node: unknown) => readonly unknown[],
  limits: RuntimeLimits,
  counted: BudgetCounter,
): BudgetOverrun | undefined {
  const pending: { node: unknown; depth: number }[] = [
    { node: root, depth: 1 },
  ];
  while (pending.length > 0) {
    const { node, depth } = pending.pop() as { node: unknown; depth: number };
    if (depth > limits.maxFilterDepth) return 'too-deep';
    counted.nodes += 1;
    if (counted.nodes > limits.maxFilterNodes) return 'too-many-nodes';
    for (const child of children(node))
      pending.push({ node: child, depth: depth + 1 });
  }
  return undefined;
}

export function budgetIssues(
  kind: keyof typeof BUDGET_ISSUE_CODES,
  overrun: BudgetOverrun | undefined,
  path: IssuePath,
  limits: RuntimeLimits,
): Issue[] {
  if (overrun === undefined) return [];
  const max =
    overrun === 'too-deep' ? limits.maxFilterDepth : limits.maxFilterNodes;
  return [issue(BUDGET_ISSUE_CODES[kind][overrun], path, { max })];
}

/** The two operands of a BINARY node; anything else is a leaf, sound or not. */
export function binaryChildren(node: unknown): readonly unknown[] {
  const shaped = node as
    { type?: unknown; left?: unknown; right?: unknown } | null | undefined;
  return shaped?.type === 'BINARY' ? [shaped.left, shaped.right] : [];
}

/** The operands of a having group; a non-array is the walk's to report. */
export function havingChildren(node: unknown): readonly unknown[] {
  const operands = (node as { operands?: unknown } | null | undefined)
    ?.operands;
  return Array.isArray(operands) ? operands : [];
}
