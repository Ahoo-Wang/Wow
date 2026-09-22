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

import { useRef, useState } from 'react';
import { PlusIcon, XIcon } from 'lucide-react';
import type { AnalysisMetric } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { GroupedMenu } from '../FieldMenu.js';
import { NumberInput } from '../FilterValueEditor.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { EditorCard, EditorSlot } from '../variants.js';
import { CardMenu, CardName } from './CardMenu.js';
import { CompactSelect } from './CompactSelect.js';
import {
  aliasesOf,
  defaultMetric,
  fieldOfMetric,
  freeAlias,
  metricOfSummary,
  summaryChoices,
  summaryOf,
  type SummaryChoice,
} from './editing.js';
import { SortRow } from './SortRow.js';

/**
 * The metrics slot: one card per metric, the way to add one, and — at the
 * bottom, because "the first N groups" is only readable next to what they
 * are ordered by — the sort and the row limit.
 */
export function MetricSlot({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const measurable = analysis.fields.filter(
    field => summaryChoices(field).length > 0,
  );
  return (
    <EditorSlot
      name="metrics"
      title={messages.label('label.analysis.slot.metrics')}
      hint={messages.label('label.analysis.hint.metrics')}
    >
      {analysis.metrics.map((metric, index) => (
        <MetricCard
          key={metric.alias}
          analysis={analysis}
          metric={metric}
          index={index}
          disabled={disabled}
        />
      ))}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="ghost"
              size="sm"
              disabled={
                disabled || (measurable.length === 0 && !analysis.countable)
              }
              className="self-start"
            />
          }
        >
          <PlusIcon data-icon="inline-start" />
          {messages.label('label.analysis.add-metric')}
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {analysis.countable && (
            <DropdownMenuGroup>
              <DropdownMenuItem
                onClick={() =>
                  analysis.addMetric({
                    type: 'COUNT',
                    alias: freeAlias('count', aliasesOf(analysis)),
                  })
                }
              >
                {messages.label('label.analysis.row-count')}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          )}
          <GroupedMenu
            items={measurable}
            groups={analysis.fieldGroups}
            itemKey={field => field.field}
            render={field => (
              <DropdownMenuItem
                key={field.field}
                onClick={() =>
                  analysis.addMetric(defaultMetric(field, aliasesOf(analysis)))
                }
              >
                {field.label}
              </DropdownMenuItem>
            )}
          />
        </DropdownMenuContent>
      </DropdownMenu>
      <SortRow analysis={analysis} disabled={disabled} />
    </EditorSlot>
  );
}

/**
 * One metric: the field and how it is summarised, in the one list Wow's
 * six ways of measuring a field collapse to (D20 汇总方式); a percentile
 * grows a third control. The record count is the first card and names no
 * field. A change of summary swaps the whole metric (`replaceMetric`).
 */
function MetricCard({
  analysis,
  metric,
  index,
  disabled,
}: {
  analysis: AnalysisEditorController;
  metric: AnalysisMetric;
  index: number;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const [renaming, setRenaming] = useState(false);
  const menu = useRef<HTMLButtonElement>(null);
  const done = () => {
    setRenaming(false);
    menu.current?.focus();
  };
  const fieldName = fieldOfMetric(metric);
  const field = analysis.fields.find(entry => entry.field === fieldName);
  // What the card is called, and what every control on it is named after:
  // the name the analyst gave, else what the field composes (D20 显示名).
  const fallback =
    metric.type === 'COUNT'
      ? messages.label('label.analysis.row-count')
      : (field?.label ?? fieldName);
  const name = metric.label ?? fallback;
  const choices = field ? summaryChoices(field) : [];
  const choice = summaryOf(metric);
  const word = (entry: SummaryChoice) =>
    messages.label(`label.summary.fn.${entry}`, undefined, entry.toLowerCase());
  return (
    <EditorCard data-slot="metric-card" data-metric={metric.type}>
      <CardName
        name={fallback}
        given={metric.label}
        renaming={renaming}
        label={messages.label('label.analysis.display-name', { name })}
        onRename={label => analysis.renameMetric(index, label)}
        onDone={done}
      />
      {field && choice !== null && choices.length > 0 && (
        <CompactSelect
          label={messages.label('label.analysis.function-of', { name })}
          items={choices.map(entry => ({ value: entry, label: word(entry) }))}
          value={choice}
          disabled={disabled}
          onChange={next =>
            analysis.replaceMetric(
              index,
              metricOfSummary(field, next, metric.alias),
            )
          }
        />
      )}
      {metric.type === 'PERCENTILE' && (
        <NumberInput
          label={messages.label('label.analysis.percentile')}
          chrome="box"
          className="w-16"
          disabled={disabled}
          value={metric.percentile}
          onNumber={next => {
            // Wow's open interval: 100 is not a percentile, and 0 is none.
            if (next !== null && next > 0 && next < 100)
              analysis.updateMetric(index, { percentile: next });
          }}
        />
      )}
      <CardMenu
        ref={menu}
        name={name}
        disabled={disabled}
        onRename={() => setRenaming(true)}
      />
      <IconButton
        label={messages.label('label.analysis.remove-metric', { name })}
        variant="ghost"
        size="icon-xs"
        disabled={disabled || analysis.metrics.length <= 1}
        onClick={() => analysis.removeMetric(index)}
      >
        <XIcon />
      </IconButton>
    </EditorCard>
  );
}
