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

import { useId } from 'react';
import type { FieldOption } from '../../model/index.js';
import type {
  AnalysisEditorController,
  FilterEditorController,
} from '../../react/index.js';
import { cn } from 'cn';
import { Checkbox } from '../components/checkbox.js';
import { Field, FieldLabel } from '../components/field.js';
import { crossesBoundary, leavesEditor } from '../FilterPanel.js';
import { FilterActions } from '../filter/FilterActions.js';
import { SPACE } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DimensionSlot } from './DimensionCard.js';
import { ElementsSlot } from './ElementsSlot.js';
import { MetricSlot } from './MetricCard.js';
import { RangeSlot } from './RangeSlot.js';

export interface TrayProps {
  filter: FilterEditorController;
  analysis: AnalysisEditorController;
  optionsFor?(remote: string): FieldOption[] | undefined;
  disabled?: boolean;
  /** 「改了就跑」: the preference and the way to change it (`WorkbenchController`). */
  autoRun?: { on: boolean; set(on: boolean): void };
}

/**
 * The analysis view's editor: the question itself, in the analyst's order
 * (D20) — the range, then the dimensions beside the metrics. Each slot is a
 * named `section`; the range takes the first row because it is the record
 * view's own condition panel, applied as it is there, and the other two
 * share the row below. Narrow, the slots stack.
 *
 * One footer runs the whole draft: the range's conditions and the question
 * are one config, and `runtime.apply` runs it once, so the one primary on
 * the screen is Apply (D17-3). How the result is looked at — table or
 * chart, and which chart — is not in here: it is the result's, on its
 * toolbar and in the visualization panel (D20).
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
  return (
    <section
      data-slot="analysis-tray"
      aria-label={messages.label('label.analysis.editor')}
      className={cn('flex flex-col', SPACE.ROWS)}
      // Auto-refresh holds while a control in here has focus, as in the
      // filter panel; a move between two controls inside is neither.
      onFocus={event => {
        if (crossesBoundary(event)) analysis.focus();
      }}
      onBlur={event => {
        if (leavesEditor(event)) analysis.blur();
      }}
    >
      <RangeSlot filter={filter} optionsFor={optionsFor} disabled={disabled} />
      {/* Between the range and the question, because it changes what the
          question is about (D20 屏 G); absent where nothing can be expanded. */}
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
        />
      </div>
      {/* The pair that runs the query, once for everything above: Clear
          empties the range, Apply runs the whole draft, and the dot says
          the draft holds something the last run did not — whichever slot
          it is in. */}
      <div
        data-slot="analysis-tray-actions"
        className="flex flex-wrap items-center justify-end gap-3"
      >
        {/* 「改了就跑」 (D20): the question runs on its own a moment after
            it changes; the range still waits for Apply. A preference of the
            user's, not of the view, so it is not in the config. */}
        {autoRun && (
          <Field
            orientation="horizontal"
            className="mr-auto w-auto"
            data-slot="auto-run"
          >
            <Checkbox
              id={autoRunId}
              checked={autoRun.on}
              disabled={disabled}
              onCheckedChange={checked => autoRun.set(checked === true)}
            />
            <FieldLabel htmlFor={autoRunId}>
              {messages.label('label.analysis.auto-run')}
            </FieldLabel>
          </Field>
        )}
        <FilterActions
          filter={filter}
          disabled={disabled}
          overBudget={false}
          pending={filter.pending || analysis.pending}
        />
      </div>
    </section>
  );
}
