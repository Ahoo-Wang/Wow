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
import { DroppedNotice } from './DroppedNotice.js';
import { ElementsSlot } from './ElementsSlot.js';
import { MetricSlot } from './MetricSlot.js';
import { isEmptyTree } from './MetricCondition.js';
import { RangeSlot } from './RangeSlot.js';
import { ResultSlot } from './ResultSlot.js';
import { TermTip } from './TermTip.js';

export interface TrayProps {
  filter: FilterEditorController;
  analysis: AnalysisEditorController;
  optionsFor?(remote: string): FieldOption[] | undefined;
  disabled?: boolean;
  /** Auto-run: the preference and the way to change it (`WorkbenchController`). */
  autoRun?: { on: boolean; set(on: boolean): void };
}

/**
 * The analysis view's editor: the question itself, one row a step, in the
 * order each step depends on the one before (D71, revising D20) —
 *
 * 1. **展开** (only where the capability declares a chain): what one
 *    counted thing is, which decides the fields every row after it names;
 * 2. **指标**: the numbers, which a derived metric reads in order;
 * 3. **维度**: what they are compared by;
 * 4. **结果** (only with a dimension): which groups are kept, in what
 *    order, how many — it names the metrics and dimensions above it;
 * 5. **范围**: the records it runs over, last because it depends on nothing
 *    above it — it narrows the outermost records whatever is expanded.
 *
 * Each row is a named `section` headed by the analyst's term with an ⓘ
 * that says it in a sentence (`EditorSlot`, `TermTip`).
 *
 * One footer runs the whole draft: the range's conditions and the question
 * are one config, and `runtime.apply` runs it once, so the one primary on
 * the screen is Apply (D17-3), at the footer's end, resting quiet while
 * nothing waits for it. How the result is looked at — table or chart, and
 * which chart — is not in here: it is the result's, on its toolbar and in
 * the visualization panel (D20).
 *
 * The rows scroll and the footer does not. The band the tray folds into is
 * capped at half the work column (`styles.css`, "The editor takes at most
 * half"), and what scrolls inside that cap is the question: Apply is how a
 * draft gets run, and a footer scrolled out of sight under a long range is
 * a button the analyst has to go looking for. The notice of what an edit
 * took out stands just above it for the same reason (`DroppedNotice`).
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
  const heldId = useId();
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
        // From `md` up, a grid of two columns every row is a subgrid of
        // (`EditorSlot`): the terms, as wide as the widest, and the rows.
        className={cn(
          '-mx-1 flex min-h-0 flex-col overflow-y-auto px-1 py-0.5',
          'md:grid md:grid-cols-[max-content_minmax(0,1fr)] md:content-start md:gap-x-3',
          SPACE.ROWS,
        )}
      >
        {/* What one counted thing is; absent where nothing can be
            expanded. */}
        {analysis.expansible && (
          <ElementsSlot
            analysis={analysis}
            disabled={disabled}
            optionsFor={optionsFor}
          />
        )}
        <MetricSlot
          analysis={analysis}
          disabled={disabled}
          optionsFor={optionsFor}
          conditioning={conditioning}
          setConditioning={setConditioning}
        />
        <DimensionSlot analysis={analysis} disabled={disabled} />
        {/* About the answer's groups, so after what names them: which are
            kept, in what order, how many (2026-09-23 audit). */}
        <ResultSlot analysis={analysis} disabled={disabled} />
        <RangeSlot
          filter={filter}
          optionsFor={optionsFor}
          disabled={disabled}
        />
      </div>
      <DroppedNotice analysis={analysis} />
      {/* The footer, once for everything above: auto-run's switch at its
          start, Apply at its end, and between them what holds the run.

          At rest the switch says what it does in its ⓘ, and the row is the
          switch and a quiet Apply. **While the range holds conditions not
          applied** (`held`) nothing runs on its own, however the question
          changes — so the sentence saying so, 「放弃范围修改」, 「清空范围」
          and an emphasised Apply stand in the row itself, never only in a
          tip (the 2026-09 review fixed a run that paused without a word;
          D71 keeps it said). The sentence takes a row of its own where the
          row wraps (`order-last basis-full`). */}
      <div
        data-slot="analysis-tray-actions"
        className="flex shrink-0 flex-wrap items-center justify-end gap-x-3 gap-y-1"
      >
        {/* Auto-run (D20): the question runs on its own a moment after it
            changes; the range still waits for Apply, which the ⓘ says,
            because the label alone promises more than the switch does. A
            preference of the user's, not of the view, so it is not in the
            config. */}
        {autoRun && (
          <Field
            orientation="horizontal"
            className="mr-auto w-auto items-center gap-1.5"
            data-slot="auto-run"
          >
            <Checkbox
              id={autoRunId}
              checked={autoRun.on}
              disabled={disabled}
              aria-describedby={held ? heldId : autoRunHintId}
              onCheckedChange={checked => autoRun.set(checked === true)}
            />
            <FieldLabel htmlFor={autoRunId}>
              {messages.label('label.analysis.auto-run')}
            </FieldLabel>
            <TermTip
              label={messages.label('label.analysis.tip-of', {
                term: messages.label('label.analysis.auto-run'),
              })}
              tip={messages.label('label.analysis.auto-run-hint')}
              describedBy={autoRunHintId}
              slot="auto-run-tip"
            />
          </Field>
        )}
        {held && (
          <FieldDescription
            id={heldId}
            data-slot="auto-run-hint"
            data-held
            className="order-last basis-full md:order-none md:flex-1 md:basis-auto"
          >
            {messages.label('label.analysis.auto-run-held')}
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
          words="range"
          onApply={apply}
        />
      </div>
    </section>
  );
}
