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

import { useState } from 'react';
import { PlusIcon, XIcon } from 'lucide-react';
import { rankable } from '../../analysis/index.js';
import type { AnalysisTop } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { cn } from 'cn';
import { NumberInput } from '../filter/FilterValueEditor.js';
import { IconButton } from '../kit/IconButton.js';
import { TEXT_UI } from '../kit/layout.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { AddButton, EditorCard } from '../kit/variants.js';
import { CompactSelect } from './CompactSelect.js';
import { groupReference, metricReference } from './editing.js';

/** How many a new top N keeps: 「每个省前三的城市」. */
const FIRST_COUNT = 3;

/**
 * 「每个〈维度〉只留前 N 个」: what the per-group top N can be made
 * of here — the dimensions to rank within and the metrics to rank by — and
 * the one a new top N starts as, `null` where none can be had: the source
 * does not rank, there is one dimension (within it every group is first),
 * or no metric is a number to rank by. A stored one is shown either way,
 * so what admission refuses can be changed or taken away.
 */
export function useTop(analysis: AnalysisEditorController) {
  const messages = useViewMessages();
  const within = analysis.groups.map(group => ({
    value: group.alias,
    label: groupReference(analysis, group, messages),
  }));
  const metrics = analysis.metrics
    .filter(metric => rankable(metric, analysis.moments))
    .map(metric => ({
      value: metric.alias,
      label: metricReference(analysis, metric, messages),
    }));
  const [firstWithin] = within;
  const [firstMetric] = metrics;
  const start: AnalysisTop | null =
    analysis.topAllowed && within.length >= 2 && firstWithin && firstMetric
      ? {
          within: firstWithin.value,
          metric: firstMetric.value,
          count: Math.min(FIRST_COUNT, Math.max(1, analysis.limit)),
        }
      : null;
  return { analysis, within, metrics, start };
}

type Top = ReturnType<typeof useTop>;

/** The way to a top N, on the result's line beside 「只保留…」. */
export function AddTop({ top, disabled }: { top: Top; disabled?: boolean }) {
  const messages = useViewMessages();
  const { analysis, start } = top;
  if (start === null || analysis.top !== undefined) return null;
  return (
    <AddButton
      data-slot="add-top"
      disabled={disabled}
      onClick={() => analysis.setTop(start)}
    >
      <PlusIcon data-icon="inline-start" />
      {messages.label('label.analysis.top-first')}
    </AddButton>
  );
}

/**
 * The top N as the sentence it is: 「每个 [省] 只留前 [3] 个，按 [GMV]
 * [从高到低]」, one card, its controls named for what they are. The count
 * keeps what the draft cannot hold — not a whole number from 1 to the
 * result's 「前 N 组」 — in the box, marked, as 「前 N 组」 itself does.
 */
export function TopRow({ top, disabled }: { top: Top; disabled?: boolean }) {
  const messages = useViewMessages();
  const [typed, setTyped] = useState<{
    value: number | null;
    over: number;
  } | null>(null);
  const current = top.analysis.top;
  if (current === undefined) return null;
  const { analysis } = top;
  const max = analysis.limit;
  const held = typed?.over === current.count ? typed : null;
  const count = held ? held.value : current.count;
  const invalid = count === null || !withinCount(count, max);
  const write = (next: Partial<AnalysisTop>) =>
    analysis.setTop({ ...current, ...next });
  const directions = (['DESC', 'ASC'] as const).map(direction => ({
    value: direction,
    label: messages.label(`label.analysis.top-direction.${direction}`),
  }));
  // A stored top N may name a dimension or a metric the lists no longer
  // offer; it is shown as stored, and admission says what is wrong.
  const listed = <V extends string>(
    items: readonly { value: V; label: string }[],
    value: V,
  ) =>
    items.some(item => item.value === value)
      ? items
      : [...items, { value, label: value }];
  return (
    <EditorCard
      data-slot="analysis-top"
      role="group"
      aria-label={messages.label('label.analysis.top-title')}
      className={cn('fve:flex-wrap', TEXT_UI)}
    >
      <span>{messages.label('label.analysis.top-each')}</span>
      <CompactSelect
        label={messages.label('label.analysis.top-within')}
        items={listed(top.within, current.within)}
        value={current.within}
        disabled={disabled}
        onChange={within => write({ within })}
      />
      <span>{messages.label('label.analysis.top-keep')}</span>
      <NumberInput
        label={messages.label('label.analysis.top-count')}
        chrome="box"
        className="fve:w-16"
        disabled={disabled}
        invalid={invalid}
        value={count}
        onNumber={next => {
          if (next !== null && withinCount(next, max)) {
            write({ count: next });
            setTyped(null);
          } else setTyped({ value: next, over: current.count });
        }}
      />
      <span>{messages.label('label.analysis.top-by')}</span>
      <CompactSelect
        label={messages.label('label.analysis.top-metric')}
        items={listed(top.metrics, current.metric)}
        value={current.metric}
        disabled={disabled}
        onChange={metric => write({ metric })}
      />
      <CompactSelect
        label={messages.label('label.analysis.top-direction')}
        items={directions}
        value={current.direction ?? 'DESC'}
        disabled={disabled}
        onChange={direction =>
          analysis.setTop(withDirection(current, direction))
        }
      />
      <IconButton
        label={messages.label('label.analysis.remove-top')}
        variant="ghost"
        size="icon-xs"
        className="fve:ml-auto"
        disabled={disabled}
        onClick={() => analysis.setTop(undefined)}
      >
        <XIcon />
      </IconButton>
      <span
        data-slot={invalid ? 'top-invalid' : 'top-note'}
        className="fve:basis-full fve:text-muted-foreground"
      >
        {invalid
          ? messages.label('label.analysis.top-invalid', { max })
          : messages.label('label.analysis.top-note')}
      </span>
    </EditorCard>
  );
}

/** The top N ranked the other way; highest first is stored as no word. */
function withDirection(
  top: AnalysisTop,
  direction: 'ASC' | 'DESC',
): AnalysisTop {
  const { within, metric, count } = top;
  return direction === 'ASC'
    ? { within, metric, count, direction }
    : { within, metric, count };
}

/** Whether a count is one the draft holds: 1 to the result's row limit. */
function withinCount(value: number, max: number): boolean {
  return Number.isInteger(value) && value >= 1 && value <= max;
}
