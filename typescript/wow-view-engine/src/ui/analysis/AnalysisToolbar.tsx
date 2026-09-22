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

import { useId, type RefObject } from 'react';
import { ChartColumnIcon } from 'lucide-react';
import type { AnalysisView } from '../../analysis/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { Checkbox } from '../components/checkbox.js';
import { Field, FieldLabel } from '../components/field.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { columnTitle } from '../display.js';
import { SPACE, TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { Toolbar } from '../toolbar.js';

export interface AnalysisToolbarProps {
  analysis: AnalysisEditorController;
  /** The result on screen, which the reading names. */
  view: AnalysisView;
  /**
   * Whether the visualization panel is open, and the press that opens or
   * closes it. Left out where the host switched the panel off
   * (`WorkbenchFeatures.visualization`): the button is then not on the bar
   * at all, as every feature turned off is absent rather than disabled (D4).
   */
  visualizing?: boolean;
  onVisualize?(open: boolean): void;
  /**
   * The press that opened the panel, so the panel can hand the keyboard
   * back to it when it closes (A1). Held by `AnalysisParts`, which owns the
   * level the panel is at.
   */
  visualizeRef?: RefObject<HTMLButtonElement | null>;
  disabled?: boolean;
}

/**
 * The first row of the analysis result (D12 Ⅳ): on the left, what the
 * numbers below are — the dimensions and the metrics, in one line, as the
 * result was actually shaped; on the right, how they are looked at. Looking
 * is the result's business, not the question's, so the layout switch and
 * the way into the visualization panel live here rather than in the tray
 * (D20). Table or chart redraws the same rows; the totals row is a query
 * of its own and runs at once.
 */
export function AnalysisToolbar({
  analysis,
  view,
  visualizing,
  onVisualize,
  visualizeRef,
  disabled,
}: AnalysisToolbarProps) {
  const messages = useViewMessages();
  const id = useId();
  const columns = view.schema ?? view.columns;
  // The separator is the catalogue's, as it is wherever this package lists
  // names in a sentence (`charts/reading.ts`): 「、」 in Chinese, ", " in
  // English.
  const join = messages.label('label.filter.join');
  const dimensions = columns
    .filter(column => column.role === 'group')
    .map(column => column.label)
    .join(join);
  const metrics = columns
    .filter(column => column.role === 'metric')
    .map(column => columnTitle(column, messages))
    .join(join);
  const reading =
    dimensions === ''
      ? messages.label('label.analysis.reading-flat', { metrics })
      : messages.label('label.analysis.reading', { dimensions, metrics });
  const apply = (change: () => void) => {
    change();
    analysis.submit();
  };
  return (
    <Toolbar
      data-slot="result-toolbar"
      aria-label={messages.label('label.toolbar.title')}
      className={`flex flex-wrap items-center ${SPACE.GROUPS}`}
    >
      <span
        data-slot="analysis-reading"
        className={`text-muted-foreground min-w-0 truncate ${TEXT_UI}`}
      >
        {reading}
      </span>
      <div className={`ml-auto flex flex-wrap items-center ${SPACE.GROUPS}`}>
        <ToggleGroup
          value={[analysis.layout]}
          onValueChange={value => {
            const next = value[0];
            if (next === 'table' || next === 'chart') analysis.setLayout(next);
          }}
          variant="outline"
          size="sm"
          spacing={0}
          aria-label={messages.label('label.analysis.layout')}
        >
          <ToggleGroupItem value="table">
            {messages.label('label.layout.table')}
          </ToggleGroupItem>
          <ToggleGroupItem value="chart">
            {messages.label('label.layout.chart')}
          </ToggleGroupItem>
        </ToggleGroup>
        {onVisualize && (
          <Button
            ref={visualizeRef}
            variant="outline"
            size="sm"
            aria-pressed={visualizing === true}
            data-slot="visualize"
            disabled={disabled}
            onClick={() => onVisualize(visualizing !== true)}
          >
            <ChartColumnIcon data-icon="inline-start" />
            {messages.label('label.analysis.visualize')}
          </Button>
        )}
        {analysis.layout === 'table' && (
          <Field orientation="horizontal" className="w-auto">
            <Checkbox
              id={`${id}-totals`}
              checked={analysis.totals}
              disabled={disabled}
              onCheckedChange={checked =>
                apply(() => analysis.setTotals(checked === true))
              }
            />
            <FieldLabel htmlFor={`${id}-totals`}>
              {messages.label('label.analysis.totals')}
            </FieldLabel>
          </Field>
        )}
      </div>
    </Toolbar>
  );
}
