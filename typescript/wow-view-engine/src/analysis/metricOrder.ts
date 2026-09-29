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

import type { AnalysisMetric } from '../model/index.js';
import { derivedRefs } from './dangling.js';

/**
 * Why a metric could not go as far as it was asked: the metric it reads,
 * which it must stay after (`after`), or the derived metric that reads it,
 * which it must stay before (`before`).
 */
export interface MetricMoveStop {
  side: 'after' | 'before';
  /** The alias of the metric it stopped at. */
  at: string;
}

/** A metric moved in the list, as far as the rule lets it go. */
export interface MetricMove {
  metrics: AnalysisMetric[];
  /** Where it landed; its old index when it did not move at all. */
  to: number;
  /** Set when it landed short of where it was asked to go. */
  stop?: MetricMoveStop;
}

/**
 * The metric at `from` moved to `to`, where the order allows it (D71).
 *
 * A derived metric is read in list order — admission refuses one that reads
 * a metric declared after it (`analysis.derived.unknown-metric`), and the
 * editor's cascade takes it with that reading — so the order is part of the
 * question, not only of the table. A move is therefore **clamped**: a
 * derived metric goes no earlier than just after the last metric it reads,
 * and a metric goes no later than just before the first derived metric that
 * reads it. The move still happens as far as it can, and `stop` says which
 * metric it stopped at, so the editor can say why.
 *
 * `to` is the index the metric ends at in the new list. A metric that
 * would have to stand both after one and before another that come the
 * other way round has no place at all, and does not move.
 */
export function moveMetric(
  metrics: readonly AnalysisMetric[],
  from: number,
  to: number,
): MetricMove {
  const moving = metrics[from];
  const unchanged = { metrics: [...metrics], to: from };
  if (!moving || from === to) return unchanged;
  const others = metrics.filter((_metric, at) => at !== from);
  const reads = moving.type === 'DERIVED' ? derivedRefs(moving.expression) : [];
  // After the last metric it reads…
  let lowest = 0;
  let after: string | undefined;
  others.forEach((metric, at) => {
    if (reads.includes(metric.alias) && at + 1 > lowest) {
      lowest = at + 1;
      after = metric.alias;
    }
  });
  // …and before the first derived metric that reads it.
  let highest = others.length;
  let before: string | undefined;
  others.forEach((metric, at) => {
    if (
      at < highest &&
      metric.type === 'DERIVED' &&
      derivedRefs(metric.expression).includes(moving.alias)
    ) {
      highest = at;
      before = metric.alias;
    }
  });
  if (lowest > highest) return unchanged;
  const target = Math.min(Math.max(to, 0), others.length);
  const landed = Math.min(Math.max(target, lowest), highest);
  const stop: MetricMoveStop | undefined =
    landed > target && after !== undefined
      ? { side: 'after', at: after }
      : landed < target && before !== undefined
        ? { side: 'before', at: before }
        : undefined;
  if (landed === from) return { ...unchanged, ...(stop ? { stop } : {}) };
  const next = [...others];
  next.splice(landed, 0, moving);
  return { metrics: next, to: landed, ...(stop ? { stop } : {}) };
}
