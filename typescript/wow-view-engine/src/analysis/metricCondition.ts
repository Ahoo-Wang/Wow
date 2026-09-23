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
  type AnalysisMetric,
  type FieldDefinition,
} from '../model/index.js';
import {
  describeFilter,
  type FieldKindRegistry,
  type FilterSummaryItem,
} from '../filter/index.js';

/**
 * What a metric's own condition (D20 屏 H, 「只算满足条件的记录」) does to
 * the way the metric reads.
 *
 * A number counted over some of the records is a different number from the
 * same summary over all of them, and a header that says 「金额的合计」 over
 * both reads a region with no shipped orders as a region with no sales. So
 * the condition travels with the metric's name (D20 显示名):
 *
 * - `value` — the condition keeps **one value of one field**, and that value
 *   names itself: 「金额的合计 · 已发运」, as Metabase says "Sum of Total where
 *   Status is Shipped". Only a value that reads on its own after a 「·」 —
 *   the label the definition gave an option, or a piece of text — is one: a
 *   bare 「· 100」 or 「· 是」 does not say what it is about;
 * - otherwise the metric is **conditioned** without a word for it, and says
 *   so — 「金额的合计 · 有条件」 — until the analyst names it.
 *
 * `items` is the whole condition as the applied bar reads one
 * (`describeFilter`), for the places with room to say it: the header's
 * description, the card's 「只算 …」 line.
 */
export interface MetricCondition {
  /** The whole condition, one item per child of its root. */
  items: FilterSummaryItem[];
  /** The one value it keeps, as its field names it, when it is one. */
  value?: string;
}

/**
 * The condition a metric counts under, or `undefined` when it counts every
 * record: no filter, a derived metric (which reads no records), or a filter
 * that says nothing yet — every condition in it blank, which never reaches
 * the query as a condition and is refused before it runs.
 *
 * `fields` are the ones the metric's filter may name — the analysis scope —
 * and `kinds` read them; without kinds nothing can be read, and a condition
 * nobody can read is left unsaid rather than guessed at.
 */
export function metricCondition(
  metric: AnalysisMetric,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry | undefined,
): MetricCondition | undefined {
  if (metric.type === 'DERIVED' || !metric.filter || !kinds) return undefined;
  const items = describeFilter(fields, metric.filter, kinds);
  if (items.length === 0) return undefined;
  const value = oneValue(items);
  return value === undefined ? { items } : { items, value };
}

/**
 * The one value a condition keeps, when that is all it says: a single
 * condition, on a field still known, that the value *is* — `EQ`, or `IN`
 * over one candidate. Any other operator keeps something the value alone
 * does not say (「不等于 已发运」 is not 「已发运」), and a group, a negation
 * or a second condition is more than one value.
 */
function oneValue(items: readonly FilterSummaryItem[]): string | undefined {
  if (items.length !== 1) return undefined;
  const [item] = items;
  if (
    item.field === undefined ||
    item.items !== undefined ||
    item.unresolved ||
    item.relation !== undefined
  )
    return undefined;
  const value = item.value;
  if (item.operator === 'EQ' && value?.kind === 'text')
    return named(value.value, value.label, item);
  if (item.operator === 'IN' && value?.kind === 'list')
    return value.values.length === 1
      ? named(value.values[0], value.labels?.[0], item)
      : undefined;
  return undefined;
}

/**
 * A value as a name: the label the definition gave it, else the text it is.
 * A number, a yes or a moment is left out — after a 「·」 it would not say
 * what it is about — and so is a date written as text, which is a moment
 * whatever it is stored as.
 */
function named(
  value: string | number | boolean,
  label: string | undefined,
  item: FilterSummaryItem,
): string | undefined {
  if (label !== undefined && label !== '') return label;
  if (typeof value !== 'string' || value.trim() === '') return undefined;
  return isDateCell(item.cell ?? item.kind) ? undefined : value;
}
