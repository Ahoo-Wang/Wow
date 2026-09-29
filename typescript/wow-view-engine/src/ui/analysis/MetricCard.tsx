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
  useId,
  useRef,
  useState,
  type MouseEvent,
  type ReactNode,
} from 'react';
import { XIcon } from 'lucide-react';
import {
  isDateCell,
  without,
  type AnalysisMetric,
  type FieldOption,
} from '../../model/index.js';
import type {
  AnalysisEditorController,
  MetricRemoval,
} from '../../react/index.js';
import { Button } from '../components/button.js';
import { DropdownMenuItem } from '../components/dropdown-menu.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { NumberInput } from '../FilterValueEditor.js';
import { useViewMessages } from '../MessagesProvider.js';
import { TooltipContent } from '../popups.js';
import { summaryFunctionKey } from '../display.js';
import { EditorCard } from '../variants.js';
import {
  isDuration,
  isFormula,
  metricOfSummary,
  summaryChoices,
  summaryOf,
  type SummaryChoice,
} from '../../analysis/index.js';
import { CardMenu, CardName } from './CardMenu.js';
import { CompactSelect } from './CompactSelect.js';
import {
  DerivedControls,
  DurationControls,
  FormulaControls,
} from './FormulaCard.js';
import type { ListFocus } from './listFocus.js';
import {
  ConditionBlock,
  ConditionButton,
  ConditionLine,
} from './MetricCondition.js';
import {
  conditionOf,
  fieldOfMetric,
  metricFallbackName,
  metricReference,
} from './editing.js';

/**
 * One metric: the field and how it is summarised, in the one list Wow's
 * six ways of measuring a field collapse to (D20 汇总方式); a percentile
 * grows a third control. The record count is the first card and names no
 * field. A change of summary swaps the whole metric (`replaceMetric`).
 */
export function MetricCard({
  analysis,
  metric,
  index,
  handle,
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
  /** The drag handle the card is carried by, first on it (D71). */
  handle?: ReactNode;
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
  const condition = conditionOf(analysis, metric);
  // A condition with no one value to name the metric by leaves it called
  // 「金额的总和 · 有条件」, which D20 asks the analyst to replace: the way
  // to is offered beside the condition that caused it.
  const unnamed =
    metric.label === undefined &&
    condition !== undefined &&
    condition.value === undefined;
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
  const name =
    metric.label === undefined ? fallback : messages.say(metric.label);
  // The title says the bare field, because the summary sits next to it and
  // says the rest — and so do the controls that *are* the summary and its
  // operands, whose own value is the other half ("Summary for Amount",
  // reading "Sum"). What names the card among the others has no such
  // neighbour — two cards over one field would both be «金额» — so it says
  // the summary too, in the words the result column is headed with.
  const reference = metricReference(analysis, metric, messages);
  const choices = field ? summaryChoices(field) : [];
  // What an opening or closing value can be ordered by: the times the
  // counting unit holds (FIRST / LAST).
  const times = analysis.fields.filter(entry => isDateCell(entry.cell));
  // A field measured by its lowest, highest and percentiles can be drawn as
  // a box, and the five are added in one go (D41); a date's are moments.
  const boxable =
    choices.includes('MIN') &&
    choices.includes('MAX') &&
    choices.includes('PERCENTILE') &&
    !analysis.moments?.has(metric.alias) &&
    (metric.type === 'PERCENTILE' ||
      (metric.type === 'NUMERIC' && metric.expression.type === 'FIELD'));
  // A field with an opening and a closing value, a highest and a lowest can
  // be drawn as a candle, and the four are added in one go (N1).
  const candled =
    choices.includes('FIRST') &&
    choices.includes('LAST') &&
    choices.includes('MIN') &&
    choices.includes('MAX') &&
    !analysis.moments?.has(metric.alias) &&
    (metric.type === 'FIRST' ||
      metric.type === 'LAST' ||
      (metric.type === 'NUMERIC' &&
        metric.expression.type === 'FIELD' &&
        (metric.function === 'MIN' || metric.function === 'MAX')));
  const choice = summaryOf(metric);
  // The menu picks one of six, so «任一值» carries its caveat in the item
  // itself; a column header composes the bare word through `label.summary.of`
  // and would repeat the parenthesis on every reading of the table.
  // 「最早／最晚」 over a date, 「最小／最大」 over a number: the one rule a
  // record column's summary is named by (`summaryFunctionKey`).
  const word = (entry: SummaryChoice) =>
    entry === 'ANY'
      ? messages.label('label.summary.fn.ANY.item')
      : messages.label(
          summaryFunctionKey(entry, field?.cell),
          undefined,
          entry.toLowerCase(),
        );
  return (
    <EditorCard data-slot="metric-card" data-metric={metric.type}>
      {handle}
      {/* The controls wrap among themselves and the card's own actions keep
          the first line's end: a formula's four selects used to push the
          menu and ✕ onto a line of their own at 1440 (the 2026-09-23 audit,
          P2-6), where they read as belonging to nothing. */}
      <div
        data-slot="metric-controls"
        className="fve:flex fve:min-w-0 fve:flex-1 fve:flex-wrap fve:items-center fve:gap-2"
      >
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
        {isDuration(metric) && (
          <DurationControls
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
        {field &&
          choice !== null &&
          choices.length > 0 &&
          !isFormula(metric) && (
            <CompactSelect
              label={messages.label('label.analysis.function-of', { name })}
              items={choices.map(entry => ({
                value: entry,
                label: word(entry),
              }))}
              value={choice}
              disabled={disabled}
              onChange={next =>
                analysis.replaceMetric(
                  index,
                  withOrder(
                    metricOfSummary(field, next, metric.alias),
                    metric,
                    times,
                    analysis.elements.length > 0,
                  ),
                )
              }
            />
          )}
        {(metric.type === 'FIRST' || metric.type === 'LAST') && (
          <CompactSelect
            label={messages.label('label.analysis.order-by-of', { name })}
            items={[
              ...(analysis.elements.length > 0
                ? []
                : [
                    {
                      value: EVENT_ORDER,
                      label: messages.label('label.analysis.order-by-default'),
                    },
                  ]),
              ...times.map(entry => ({
                value: entry.field,
                label: messages.label('label.analysis.order-by', {
                  field: entry.label,
                }),
              })),
            ]}
            value={metric.orderBy ?? EVENT_ORDER}
            disabled={disabled}
            onChange={next =>
              analysis.replaceMetric(
                index,
                next === EVENT_ORDER
                  ? without(metric, 'orderBy')
                  : { ...metric, orderBy: next },
              )
            }
          />
        )}
        {metric.type === 'PERCENTILE' && (
          <NumberInput
            label={messages.label('label.analysis.percentile')}
            chrome="box"
            className="fve:w-16"
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
            // Opening writes nothing: a condition with nothing in it yet is
            // no condition, and writing one used to make the draft refuse
            // itself the moment the block opened — red under the card, Save
            // greyed out, before the analyst had done anything (2026-09-23
            // audit). The block edits `metric.filter ?? {}` and writes on the
            // first condition; Apply over a block still empty says so then.
            onToggle={() => onConditioning(!conditioning)}
          />
        )}
      </div>
      <div
        data-slot="metric-actions"
        className="fve:flex fve:shrink-0 fve:items-center fve:gap-2 fve:self-start"
      >
        <CardMenu
          ref={menu}
          name={reference}
          disabled={disabled}
          onRename={() => setRenaming(true)}
        >
          {boxable && (
            <DropdownMenuItem onClick={() => analysis.addFiveNumbers(index)}>
              {messages.label('label.analysis.five-numbers')}
            </DropdownMenuItem>
          )}
          {candled && (
            <DropdownMenuItem onClick={() => analysis.addOhlc(index)}>
              {messages.label('label.analysis.ohlc')}
            </DropdownMenuItem>
          )}
          {isDuration(metric) && (
            <DropdownMenuItem onClick={() => analysis.groupByDuration(index)}>
              {messages.label('label.analysis.group-by-duration')}
            </DropdownMenuItem>
          )}
          {metric.type !== 'DERIVED' && (
            <DropdownMenuItem onClick={onDuplicate}>
              {messages.label('label.analysis.copy-with-condition', {
                name: reference,
              })}
            </DropdownMenuItem>
          )}
        </CardMenu>
        <RemoveMetric
          label={messages.label('label.analysis.remove-metric', {
            name: reference,
          })}
          removal={analysis.metricRemoval(index)}
          disabled={disabled}
          onRemove={event => {
            focus.removing(event, index);
            analysis.removeMetric(index);
          }}
        />
      </div>
      {/* The caveat in full, under the summary, at rest: the parenthesis on
          the menu item is only read while the menu is open, and by then the
          choice is already being made. `w-full` breaks the card's flex row,
          so the note is a line of its own rather than a third control. */}
      {metric.type === 'ANY' && (
        <span
          data-slot="metric-note"
          className="fve:text-muted-foreground fve:contain-inline-size fve:w-full"
        >
          {messages.label('label.analysis.any-note')}
        </span>
      )}
      {(metric.type === 'FIRST' || metric.type === 'LAST') && (
        <span
          data-slot="metric-note"
          className="fve:text-muted-foreground fve:contain-inline-size fve:w-full"
        >
          {messages.label('label.analysis.first-last-note')}
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
        <ConditionLine
          items={condition?.items ?? []}
          disabled={disabled}
          {...(unnamed && !renaming ? { onName: () => setRenaming(true) } : {})}
        />
      )}
    </EditorCard>
  );
}

/**
 * The value an opening or closing value's order select takes for the
 * source's own default, the model's event time: no `orderBy` at all.
 */
const EVENT_ORDER = '(event-time)';

/**
 * A summary switched to an opening or closing value keeps the order the
 * metric had, and inside expanded entries — which have no event time — starts
 * ordered by their first time field (`analysis.first-last.order-by-required`).
 */
function withOrder(
  next: AnalysisMetric,
  previous: AnalysisMetric,
  times: readonly { field: string }[],
  expanded: boolean,
): AnalysisMetric {
  if (next.type !== 'FIRST' && next.type !== 'LAST') return next;
  const kept =
    previous.type === 'FIRST' || previous.type === 'LAST'
      ? previous.orderBy
      : undefined;
  const orderBy = kept ?? (expanded ? times[0]?.field : undefined);
  return orderBy === undefined ? next : { ...next, orderBy };
}

/**
 * A metric's ✕, and why it is off when it is (D71). Taking out the only
 * metric, or the one every other metric is calculated from — whose cascade
 * would leave none, the edit the tray used to make as asked and admission
 * then refused — asks nothing Wow can answer, so the ✕ says so rather than
 * letting the press through.
 *
 * Off is `aria-disabled`, not `disabled`: a disabled button takes no
 * pointer and no focus, and the reason it is off would then be said to
 * nobody. So it stays reachable, the reason is its description and its
 * tooltip, and a press does nothing. Otherwise it is an icon button over
 * one name, said to a reader and shown to a pointer (`IconButton`).
 */
function RemoveMetric({
  label,
  removal,
  disabled,
  onRemove,
}: {
  label: string;
  removal: MetricRemoval;
  disabled?: boolean;
  onRemove(event: MouseEvent<HTMLButtonElement>): void;
}) {
  const messages = useViewMessages();
  const described = useId();
  const reason =
    removal === 'ok'
      ? null
      : messages.label(
          removal === 'last'
            ? 'label.analysis.remove-last'
            : 'label.analysis.remove-cascade',
        );
  return (
    <Tooltip>
      <TooltipTrigger
        aria-label={label}
        aria-describedby={reason ? described : undefined}
        aria-disabled={reason ? true : undefined}
        render={
          <Button
            variant="ghost"
            size="icon-xs"
            data-slot="remove-metric"
            data-blocked={reason ? removal : undefined}
            disabled={disabled}
            className="fve:aria-disabled:cursor-not-allowed fve:aria-disabled:opacity-50"
            onClick={(event: MouseEvent<HTMLButtonElement>) => {
              if (!reason) onRemove(event);
            }}
          />
        }
      >
        <XIcon />
      </TooltipTrigger>
      <TooltipContent>{reason ?? label}</TooltipContent>
      {reason && (
        <span id={described} hidden>
          {reason}
        </span>
      )}
    </Tooltip>
  );
}
