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

import { useId, useState } from 'react';
import type { FieldOption } from '../../model/index.js';
import type {
  AnalysisEditorController,
  FilterEditorController,
} from '../../react/index.js';
import { cn } from 'cn';
import { Checkbox } from '../components/checkbox.js';
import { Field, FieldDescription, FieldLabel } from '../components/field.js';
import { crossesBoundary, leavesEditor } from '../FilterPanel.js';
import { FilterActions } from '../filter/FilterActions.js';
import { SPACE } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DimensionSlot } from './DimensionCard.js';
import { ElementsSlot } from './ElementsSlot.js';
import { MetricSlot } from './MetricCard.js';
import { isEmptyTree } from './MetricCondition.js';
import { RangeSlot } from './RangeSlot.js';
import { ResultSlot } from './ResultSlot.js';

export interface TrayProps {
  filter: FilterEditorController;
  analysis: AnalysisEditorController;
  optionsFor?(remote: string): FieldOption[] | undefined;
  disabled?: boolean;
  /** Auto-run: the preference and the way to change it (`WorkbenchController`). */
  autoRun?: { on: boolean; set(on: boolean): void };
}

/**
 * The analysis view's editor: the question itself, in the analyst's order
 * (D20) — the range, then the dimensions beside the metrics, then what the
 * result keeps of the groups. Each slot is a named `section`; the range
 * takes the first row because it is the record view's own condition panel,
 * applied as it is there, and the two after it share the row below. Narrow,
 * the slots stack.
 *
 * One footer runs the whole draft: the range's conditions and the question
 * are one config, and `runtime.apply` runs it once, so the one primary on
 * the screen is Apply (D17-3). How the result is looked at — table or
 * chart, and which chart — is not in here: it is the result's, on its
 * toolbar and in the visualization panel (D20).
 *
 * The slots scroll and the footer does not. The band the tray folds into is
 * capped at half the work column (`styles.css`, "The editor takes at most
 * half"), and what scrolls inside that cap is the question: Apply is how a
 * draft gets run, and a footer scrolled out of sight under a long range is
 * a button the analyst has to go looking for.
 */
export function Tray({
  filter,
  analysis,
  optionsFor,
  disabled,
  autoRun,
}: TrayProps) {
  const messages = useViewMessages();
  const autoRunId = useId();
  const autoRunHintId = useId();
  // Which metric card has its conditions open (`MetricSlot`), held here so
  // Apply can see a condition the analyst opened and left empty.
  const [conditioning, setConditioning] = useState<string | null>(null);
  // What Apply still has to do. With auto-run on, a question that is due to
  // run on its own (`stale`) waits for nothing, so the button rests; the
  // range's conditions, or a draft auto-run declines, still wait for it.
  const waiting = (filter.pending || analysis.pending) && !analysis.stale;
  // Auto-run on, and the range's conditions edited and not applied — a
  // condition just added and not yet filled in is the common case: nothing
  // runs on its own while they wait, however the question changes (Apply
  // runs the whole draft, `autoApplyDue`), and it used to say nothing about
  // why (2026-09-23 audit). Filling the condition in does not change that —
  // the range is applied by Apply — so the sentence says so rather than
  // promising a run the switch will not make.
  const held = autoRun?.on === true && filter.conditionsPending;
  /**
   * Apply, with one thing settled first: a metric whose conditions are open
   * with nothing in them. Opening them writes nothing (`MetricCard`), so an
   * analyst who opened the block meaning to narrow the number and pressed
   * Apply would get the whole number back without a word. The empty group
   * is written now, which is the moment it becomes true that it is empty
   * (`analysis.metricFilter.empty`): the draft refuses itself, the block
   * says why, and the analyst either fills it in or takes it away.
   */
  const apply = () => {
    const index = analysis.metrics.findIndex(
      metric => metric.alias === conditioning,
    );
    const open = analysis.metrics[index];
    if (open && open.type !== 'DERIVED' && isEmptyTree(open.filter))
      analysis.setMetricFilter(index, { op: 'and', children: [] });
    filter.submit();
  };
  return (
    <section
      data-slot="analysis-tray"
      aria-label={messages.label('label.analysis.editor')}
      className={cn('flex min-h-0 flex-col', SPACE.ROWS)}
      // Auto-refresh holds while a control in here has focus, as in the
      // filter panel; a move between two controls inside is neither.
      onFocus={event => {
        if (crossesBoundary(event)) analysis.focus();
      }}
      onBlur={event => {
        if (leavesEditor(event)) analysis.blur();
      }}
    >
      <div
        data-slot="analysis-tray-slots"
        // The gutter is room for a focus ring: a scroll port clips what
        // stands past its edge, and the cards run to it.
        className={cn(
          '-mx-1 flex min-h-0 flex-col overflow-y-auto px-1 py-0.5',
          SPACE.ROWS,
        )}
      >
        <RangeSlot
          filter={filter}
          optionsFor={optionsFor}
          disabled={disabled}
        />
        {/* Between the range and the question, because it changes what the
            question is about (D20 屏 G); absent where nothing can be
            expanded. */}
        {analysis.expansible && (
          <ElementsSlot
            analysis={analysis}
            disabled={disabled}
            optionsFor={optionsFor}
          />
        )}
        <div className={cn('grid grid-cols-1 md:grid-cols-2', SPACE.BLOCKS)}>
          <DimensionSlot analysis={analysis} disabled={disabled} />
          <MetricSlot
            analysis={analysis}
            disabled={disabled}
            optionsFor={optionsFor}
            conditioning={conditioning}
            setConditioning={setConditioning}
          />
        </div>
        {/* After the question, because it is about the answer's groups:
            which are kept, in what order, how many (2026-09-23 audit). */}
        <ResultSlot analysis={analysis} disabled={disabled} />
      </div>
      {/* The pair that runs the query, once for everything above: Clear
          empties the range, Apply runs the whole draft, and the dot says
          the draft holds something the last run did not — whichever slot
          it is in.

          Auto-run's switch shares their row, and the sentence under it takes
          a row of its own only where the two do not fit beside each other:
          on a phone the switch, its sentence and the buttons were three
          stacked rows, 103px of a tray capped at half the screen. So the
          sentence is a sibling rather than the switch's child, ordered after
          the buttons when the row wraps (`order-last basis-full`) and between
          the switch and the buttons where there is room (`md:`). */}
      <div
        data-slot="analysis-tray-actions"
        className="flex shrink-0 flex-wrap items-center justify-end gap-x-3 gap-y-1"
      >
        {/* Auto-run (D20): the question runs on its own a moment after it
            changes; the range still waits for Apply, which the description
            says, because the label alone promises more than the switch
            does. A preference of the user's, not of the view, so it is not
            in the config. */}
        {autoRun && (
          <Field
            orientation="horizontal"
            className="w-auto"
            data-slot="auto-run"
          >
            <Checkbox
              id={autoRunId}
              checked={autoRun.on}
              disabled={disabled}
              aria-describedby={autoRunHintId}
              onCheckedChange={checked => autoRun.set(checked === true)}
            />
            <FieldLabel htmlFor={autoRunId}>
              {messages.label('label.analysis.auto-run')}
            </FieldLabel>
          </Field>
        )}
        {autoRun && (
          // While the range holds an unfinished condition the switch is on
          // and nothing runs: the sentence says why instead of what the
          // switch does in general, since that is the one thing the reader
          // needs to know right now.
          <FieldDescription
            id={autoRunHintId}
            data-slot="auto-run-hint"
            data-held={held || undefined}
            className="order-last basis-full md:order-none md:flex-1 md:basis-auto"
          >
            {messages.label(
              held
                ? 'label.analysis.auto-run-held'
                : 'label.analysis.auto-run-hint',
            )}
          </FieldDescription>
        )}
        <FilterActions
          filter={filter}
          disabled={disabled}
          overBudget={false}
          pending={waiting}
          // Primary only while something waits for it: with auto-run on and
          // the question already running itself, a filled Apply is the
          // loudest thing on the screen asking for a press that does
          // nothing new.
          quiet={autoRun?.on === true}
          onApply={apply}
        />
      </div>
    </section>
  );
}
