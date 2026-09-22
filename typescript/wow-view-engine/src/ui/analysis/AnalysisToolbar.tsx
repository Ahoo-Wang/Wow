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
import type { AnalysisView } from '../../analysis/index.js';
import { CHART_TYPES } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { Checkbox } from '../components/checkbox.js';
import { Field, FieldLabel } from '../components/field.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { columnTitle } from '../display.js';
import { SPACE, TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { Toolbar } from '../toolbar.js';
import { CompactSelect } from './CompactSelect.js';

export interface AnalysisToolbarProps {
  analysis: AnalysisEditorController;
  /** The result on screen, which the reading names. */
  view: AnalysisView;
  disabled?: boolean;
}

/**
 * The first row of the analysis result (D12 Ⅳ): on the left, what the
 * numbers below are — the dimensions and the metrics, in one line, as the
 * result was actually shaped; on the right, how they are looked at. Looking
 * is the result's business, not the question's, so the layout switch and
 * the chart type live here rather than in the tray (D20); a change here
 * runs at once, because the kernel shapes a chart only for what ran.
 */
export function AnalysisToolbar({
  analysis,
  view,
  disabled,
}: AnalysisToolbarProps) {
  const messages = useViewMessages();
  const id = useId();
  const columns = view.schema ?? view.columns;
  const dimensions = columns
    .filter(column => column.role === 'group')
    .map(column => column.label)
    .join('、');
  const metrics = columns
    .filter(column => column.role === 'metric')
    .map(column => columnTitle(column, messages))
    .join('、');
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
            // `setLayout` runs the query itself — the kernel shapes a chart
            // only for the layout that ran — so this one is not wrapped in
            // `apply`, which would send the same question twice.
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
        {analysis.layout === 'chart' && (
          <CompactSelect
            label={messages.label('label.analysis.chart-type')}
            items={CHART_TYPES.map(type => ({
              value: type,
              label: messages.label(`label.chart.type.${type}`),
            }))}
            value={analysis.chart.type}
            disabled={disabled}
            onChange={type => apply(() => analysis.setChartType(type))}
          />
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
