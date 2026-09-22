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
import type { AnalysisMetric, FieldOption } from '../../model/index.js';
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
import {
  isFormula,
  metricOfSummary,
  summaryChoices,
  summaryOf,
  type SummaryChoice,
} from '../../analysis/index.js';
import { CardMenu, CardName } from './CardMenu.js';
import { CompactSelect } from './CompactSelect.js';
import { DerivedControls, FormulaControls } from './FormulaCard.js';
import { HavingRows } from './HavingRows.js';
import { useListFocus, type ListFocus } from './listFocus.js';
import {
  ConditionBlock,
  ConditionButton,
  ConditionLine,
  conditionItems,
} from './MetricCondition.js';
import {
  usedAliases,
  defaultMetric,
  fieldOfMetric,
  freeAlias,
  metricFallbackName,
  metricReference,
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
  optionsFor,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
}) {
  const messages = useViewMessages();
  const measurable = analysis.fields.filter(
    field => summaryChoices(field).length > 0,
  );
  // Which card has its conditions open, by the alias that names it. The
  // slot holds it rather than each card, because the one gesture that opens
  // a card's conditions from *another* card is the copy: 「复制并加条件」
  // makes the copy and opens it, which is the condition it promised.
  const [conditioning, setConditioning] = useState<string | null>(null);
  // A metric taken out leaves the keyboard on this slot (`listFocus.ts`);
  // held here because the card pressed is the one that goes.
  const focus = useListFocus({
    list: '[data-slot="analysis-slot-metrics"]',
    item: '[data-slot="metric-card"]',
    add: '[data-slot="add-metric"]',
  });
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
          focus={focus}
          disabled={disabled}
          optionsFor={optionsFor}
          conditioning={conditioning === metric.alias}
          onConditioning={open => setConditioning(open ? metric.alias : null)}
          onDuplicate={() =>
            setConditioning(analysis.duplicateMetric(index) ?? null)
          }
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
              data-slot="add-metric"
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
                    alias: freeAlias('count', usedAliases(analysis)),
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
                  analysis.addMetric(
                    defaultMetric(field, usedAliases(analysis)),
                  )
                }
              >
                {field.label}
              </DropdownMenuItem>
            )}
          />
          {/* The two metrics written rather than picked, where the
              capability declares expressions (D20 屏 B). */}
          {analysis.expressionsAllowed && (
            <DropdownMenuGroup>
              <DropdownMenuItem
                disabled={measurable.length === 0}
                onClick={() => analysis.addFormula()}
              >
                {messages.label('label.analysis.add-formula')}
              </DropdownMenuItem>
              <DropdownMenuItem
                disabled={
                  !analysis.metrics.some(metric => metric.type !== 'ANY')
                }
                onClick={() => analysis.addDerived()}
              >
                {messages.label('label.analysis.add-derived')}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <SortRow analysis={analysis} disabled={disabled} />
      <HavingRows analysis={analysis} disabled={disabled} />
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
  focus,
  disabled,
  optionsFor,
  conditioning,
  onConditioning,
  onDuplicate,
}: {
  analysis: AnalysisEditorController;
  metric: AnalysisMetric;
  index: number;
  /** Where the keyboard goes when this card is the one removed. */
  focus: ListFocus;
  disabled?: boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * Whether this card's own conditions (D20 屏 H) are open under it. The
   * slot decides, because a copy opens the card it made rather than itself.
   */
  conditioning: boolean;
  onConditioning(open: boolean): void;
  onDuplicate(): void;
}) {
  const messages = useViewMessages();
  const [renaming, setRenaming] = useState(false);
  const conditions = conditionItems(analysis, metric);
  const held = metric.type !== 'DERIVED' && metric.filter !== undefined;
  const menu = useRef<HTMLButtonElement>(null);
  const done = () => {
    setRenaming(false);
    menu.current?.focus();
  };
  const fieldName = fieldOfMetric(metric);
  const field = analysis.fields.find(entry => entry.field === fieldName);
  // What the card is called, and what every control on it is named after:
  // the name the analyst gave, else what the field composes (D20 显示名).
  const fallback = metricFallbackName(analysis, metric, messages);
  const name = metric.label ?? fallback;
  // The title says the bare field, because the summary sits next to it and
  // says the rest — and so do the controls that *are* the summary and its
  // operands, whose own value is the other half ("Summary for Amount",
  // reading "Sum"). What names the card among the others has no such
  // neighbour — two cards over one field would both be «金额» — so it says
  // the summary too, in the words the result column is headed with.
  const reference = metricReference(analysis, metric, messages);
  const choices = field ? summaryChoices(field) : [];
  const choice = summaryOf(metric);
  // The menu picks one of six, so «任一值» carries its caveat in the item
  // itself; a column header composes the bare word through `label.summary.of`
  // and would repeat the parenthesis on every reading of the table.
  const word = (entry: SummaryChoice) =>
    entry === 'ANY'
      ? messages.label('label.summary.fn.ANY.item')
      : messages.label(
          `label.summary.fn.${entry}`,
          undefined,
          entry.toLowerCase(),
        );
  return (
    <EditorCard data-slot="metric-card" data-metric={metric.type}>
      <CardName
        name={fallback}
        given={metric.label}
        renaming={renaming}
        label={messages.label('label.analysis.display-name', {
          name: reference,
        })}
        onRename={label => analysis.renameMetric(index, label)}
        onDone={done}
      />
      {isFormula(metric) && (
        <FormulaControls
          analysis={analysis}
          metric={metric}
          index={index}
          name={name}
          disabled={disabled}
        />
      )}
      {metric.type === 'DERIVED' && (
        <DerivedControls
          analysis={analysis}
          metric={metric}
          index={index}
          name={name}
          disabled={disabled}
        />
      )}
      {field && choice !== null && choices.length > 0 && !isFormula(metric) && (
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
      {metric.type !== 'DERIVED' && (
        <ConditionButton
          label={messages.label('label.analysis.condition-of', {
            name: reference,
          })}
          open={conditioning}
          held={held}
          disabled={disabled}
          onToggle={() => {
            if (!conditioning && !held)
              analysis.setMetricFilter(index, { op: 'and', children: [] });
            onConditioning(!conditioning);
          }}
        />
      )}
      <CardMenu
        ref={menu}
        name={reference}
        disabled={disabled}
        onRename={() => setRenaming(true)}
      >
        {metric.type !== 'DERIVED' && (
          <DropdownMenuItem onClick={onDuplicate}>
            {messages.label('label.analysis.copy-with-condition', {
              name: reference,
            })}
          </DropdownMenuItem>
        )}
      </CardMenu>
      <IconButton
        label={messages.label('label.analysis.remove-metric', {
          name: reference,
        })}
        variant="ghost"
        size="icon-xs"
        disabled={disabled || analysis.metrics.length <= 1}
        onClick={event => {
          focus.removing(event, index);
          analysis.removeMetric(index);
        }}
      >
        <XIcon />
      </IconButton>
      {/* The caveat in full, under the summary, at rest: the parenthesis on
          the menu item is only read while the menu is open, and by then the
          choice is already being made. `w-full` breaks the card's flex row,
          so the note is a line of its own rather than a third control. */}
      {metric.type === 'ANY' && (
        <span data-slot="metric-note" className="text-muted-foreground w-full">
          {messages.label('label.analysis.any-note')}
        </span>
      )}
      {conditioning ? (
        <ConditionBlock
          analysis={analysis}
          metric={metric}
          index={index}
          name={reference}
          disabled={disabled}
          optionsFor={optionsFor}
          onClose={() => onConditioning(false)}
        />
      ) : (
        <ConditionLine items={conditions} />
      )}
    </EditorCard>
  );
}
