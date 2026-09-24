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
  FilterGroupOperator,
  FilterOperatorName,
} from '../model/index.js';
import {
  isDeletionState,
  isGroupItem,
  type FilterSummaryItem,
  type FilterSummaryValue,
} from '../filter/index.js';
import { segmentText } from './band.js';
import { displayValue, valueText, type DisplayContext } from './display.js';
import type { MessageFormatters } from './MessagesProvider.js';

/**
 * The catalogue key for one deletion reading. The stored value is Wow's
 * `DeletionState` token; a boolean's words are chosen the same way. Spelt
 * as the tokens rather than the enum, because `/ui` does not import the
 * protocol package — the kind that admits the token is the one that does.
 */
const DELETION_LABELS = {
  ACTIVE: 'label.deletion.active',
  DELETED: 'label.deletion.deleted',
  ALL: 'label.deletion.all',
} as const;

export function deletionLabel(
  state: keyof typeof DELETION_LABELS,
): (typeof DELETION_LABELS)[keyof typeof DELETION_LABELS] {
  return DELETION_LABELS[state];
}

/**
 * One applied condition as the bar says it, in the wording in force.
 *
 * `FilterSummaryItem.text` is the kind's own English line and stays what a
 * host reading it gets; this is what the badge shows. The kernel hands over
 * parts — a field, an operator and a closed union of value shapes — and the
 * words come from the catalogue, the values from `display.ts`: option labels
 * the kind already resolved, dates in the surface's language and zone,
 * numbers in their field's format.
 */
export function summaryText(
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  if (isGroupItem(item))
    return groupText(item.group ?? 'and', item.items ?? [], messages, context);

  const said: string[] = [];
  if (item.label !== undefined) said.push(item.label);

  // A predicate reads its own conditions out where a value would stand.
  if (item.items !== undefined) {
    if (item.items.length === 0) {
      said.push(messages.label('label.filter.any-entry'));
      return said.join(' ');
    }
    pushWord(said, conditionWord(item, messages));
    said.push(groupText(item.group ?? 'and', item.items, messages, context));
    return said.join(' ');
  }

  // A value this kind cannot read is no condition to report, so the field's
  // name is all that is true of it — unless the field itself is gone, where
  // the question stands and only its answer is unreadable.
  const value = item.value;
  if (value === undefined || (value.kind === 'blank' && !item.unresolved))
    return said.join(' ');
  // One period is said as the period, relation and all: 「在 2026年9月22日」
  // rather than 「介于」 two instants — the words a date bucket was pressed
  // under, so the records opened from it and their applied bar read alike.
  if (value.kind === 'period')
    return periodText(value, item, messages, context);
  // A segment likewise: 「单价 在 ¥0～500」, the band a number histogram
  // printed, rather than two chips, 「≥ ¥0.00」 and 「< ¥500.00」.
  if (value.kind === 'segment')
    return messages.label('label.filter.segment', {
      field: item.label ?? '',
      segment: segmentValue(value, item, messages, context),
    });

  pushWord(said, conditionWord(item, messages));
  const shown = summaryValue(value, item, messages, context);
  if (shown !== '') said.push(shown);
  return said.join(' ');
}

/**
 * The whole of a metric's own condition as one sentence — 「只算 状态 是
 * 已发运」 — in the words the applied bar uses for a condition. The card says
 * it at rest under the metric, and a conditioned column's header says it as
 * its description, so the header's short 「· 已发运」 and the full condition
 * are one reading.
 */
export function onlyWhereText(
  items: readonly FilterSummaryItem[],
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  return messages.label('label.analysis.only-where', {
    conditions: items
      .map(item => summaryText(item, messages, context))
      .join(' · '),
  });
}

/**
 * The conditions of one group, joined and prefixed by the word for its own
 * operator. A group inside a group is parenthesised, as it is in `text`.
 *
 * One condition under "all of" or "any of" is said plainly: neither word adds
 * anything to a single condition. "None of" is not a joiner but a negation,
 * so it is said whatever it holds — and over one condition it is the pill's
 * own switch (D18-7), said as that condition's negation rather than as a
 * group of one.
 */
function groupText(
  op: FilterGroupOperator,
  items: readonly FilterSummaryItem[],
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  const parts = items.map(child =>
    isGroupItem(child)
      ? `(${summaryText(child, messages, context)})`
      : summaryText(child, messages, context),
  );
  if (parts.length === 1)
    return op === 'nor'
      ? messages.label('label.filter.not-of', { condition: parts[0] })
      : parts[0];
  const joined = parts.join(messages.label('label.filter.join'));
  return `${groupWord(op, messages)} ${joined}`;
}

function groupWord(
  op: FilterGroupOperator,
  messages: MessageFormatters,
): string {
  if (op === 'or') return messages.label('label.filter.any-of');
  if (op === 'nor') return messages.label('label.filter.none-of');
  return messages.label('label.filter.all-of');
}

/** A word nobody has is not a gap in the sentence. */
function pushWord(said: string[], word: string): void {
  if (word !== '') said.push(word);
}

/**
 * How this condition reads: the relation the kind named, or the operator's
 * own word. `IN` over an array asks whether the array contains any of the
 * candidates, and "is any of" would say the opposite thing about a scalar,
 * so a kind that knows better says so and this prefers it.
 *
 * A condition over one value reads 「是」／「不是」 whichever way it was
 * stored: `EQ` said 「等于」 and an `IN` of one said 「属于」, so 「状态 属于
 * 待出库」 and 「仓库 等于 华东」 on one bar read as two different relations
 * for the same thing (the 2026-09-23 audit, P2-2).
 */
function conditionWord(
  item: FilterSummaryItem,
  messages: MessageFormatters,
): string {
  if (item.relation) return messages.label(`label.relation.${item.relation}`);
  const one = oneValueRelation(item);
  if (one) return messages.label(`label.relation.${one}`);
  return item.operator ? operatorWord(item.operator, messages) : '';
}

/** 「是」 or 「不是」 for a condition over one value, else nothing. */
function oneValueRelation(item: FilterSummaryItem): 'is' | 'is-not' | null {
  const { operator, value } = item;
  const one =
    value?.kind === 'text' ||
    (value?.kind === 'list' && value.values.length === 1);
  if (!one) return null;
  if (operator === 'EQ' || operator === 'IN') return 'is';
  if (operator === 'NE' || operator === 'NOT_IN') return 'is-not';
  return null;
}

/**
 * The catalogue names every `FilterOperator`; the derived spelling is the
 * fallback for one a host's own kind offers, as it is in the condition pill.
 */
function operatorWord(
  operator: FilterOperatorName,
  messages: MessageFormatters,
): string {
  return messages.label(
    `label.operator.${operator}`,
    undefined,
    operator.split('_').join(' ').toLowerCase(),
  );
}

function summaryValue(
  value: FilterSummaryValue,
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  switch (value.kind) {
    // The operator is the whole condition, or there is nothing readable
    // beside it; either way it has already been said.
    case 'none':
    case 'blank':
      return '';
    case 'text':
      // The three readings of a deletion condition are the catalogue's words,
      // as a boolean's are: the stored `ACTIVE` is a protocol token.
      if (item.kind === 'deletion' && isDeletionState(value.value))
        return messages.label(deletionLabel(value.value));
      return value.label ?? asField(value.value, item, messages, context);
    case 'list':
      return value.values
        .map(
          (raw, index) =>
            value.labels?.[index] ?? asField(raw, item, messages, context),
        )
        .join(messages.label('label.filter.join'));
    case 'range': {
      const from = asField(value.from, item, messages, context);
      const to = asField(value.to, item, messages, context);
      // The same separator the editor draws between the two boxes: the tilde
      // was typed in here and nowhere else, so a catalogue that wanted
      // another one could change the summary and not the pill.
      return `${from} ${messages.label('label.filter.range-join')} ${to}`;
    }
    case 'relative':
      // A span, or the moment at the end of it — the same stored value, two
      // different conditions, and the phrase has to say which.
      return messages.label(
        `label.relative.${value.bound}.${value.direction}`,
        {
          amount: valueText(value.amount, messages, undefined, context.locale),
          unit: messages.label(`label.relative.unit.${value.unit}`),
        },
      );
    case 'preset':
      return messages.label(`label.relative.preset.${value.preset}`);
    case 'period':
      return periodValue(value, context);
    case 'segment':
      return segmentValue(value, item, messages, context);
  }
}

/**
 * A segment's two bounds joined as a band: in the band's short notation
 * (`segmentText`), unless the field shows its numbers some other way — a
 * millisecond instant under `cell: 'date'` — where each bound is shown as
 * the field shows it and only the joining is the band's.
 */
function segmentValue(
  value: Extract<FilterSummaryValue, { kind: 'segment' }>,
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  const field = { kind: item.kind, cell: item.cell };
  if (displayValue(value.from, field, context) === undefined)
    return segmentText(
      value.from,
      value.to,
      item.numberFormat,
      messages,
      context,
    );
  return messages.label('label.analysis.band', {
    from: asField(value.from, item, messages, context),
    to: asField(value.to, item, messages, context),
  });
}

/**
 * 「事件时间 在 2026年9月22日」: a range that is one period, read as the
 * period printed the way a date bucket of that unit prints (`displayValue`
 * with its `dateUnit`, in the period's own zone). A week's value is only the
 * day it starts, so it says it is one.
 */
function periodText(
  value: Extract<FilterSummaryValue, { kind: 'period' }>,
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  return messages.label(
    value.unit === 'WEEK' ? 'label.filter.period-week' : 'label.filter.period',
    { field: item.label ?? '', period: periodValue(value, context) },
  );
}

function periodValue(
  value: Extract<FilterSummaryValue, { kind: 'period' }>,
  context: DisplayContext,
): string {
  return (
    displayValue(
      value.from,
      { dateUnit: value.unit, timeZone: value.timeZone },
      context,
    ) ?? value.from
  );
}

/**
 * One raw value of a condition, shown the way its field shows it — by the
 * same `cell ?? kind` rule the table follows, so a number carrying a
 * millisecond instant under `cell: 'date'` is a date in both places.
 */
function asField(
  value: string | number | boolean,
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  return (
    displayValue(value, { kind: item.kind, cell: item.cell }, context) ??
    valueText(value, messages, item.numberFormat, context.locale)
  );
}
