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

import type { FieldDefinition } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { NumberInput } from '../FilterValueEditor.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SortSettings } from '../SortSettings.js';
import { metricName } from './editing.js';

/**
 * What the first N groups are the first N of: the sort, and the N, on one
 * row at the bottom of the metrics slot. The record view's own editor takes
 * as many entries as there are aliases, so "first by one, then by the next"
 * is sayable here too. A sort needs a dimension, so the row waits for one.
 */
export function SortRow({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  if (analysis.groups.length === 0) return null;
  // The record view's own sort editor, over the aliases as if they were
  // fields (D20: one control for one thing): every dimension and metric
  // may order the groups, first by one, then by the next.
  const fields: FieldDefinition[] = [
    ...analysis.groups.map(group => ({
      name: group.alias,
      label:
        group.label ??
        analysis.fields.find(entry => entry.field === group.field)?.label ??
        group.field,
      kind: 'string',
      sortable: true,
    })),
    ...analysis.metrics.map(metric => ({
      name: metric.alias,
      label: metricName(analysis, metric, messages),
      kind: 'number',
      sortable: true,
    })),
  ];
  const owner = {
    sort: analysis.sort.map(entry => ({
      field: entry.alias,
      direction: entry.direction,
    })),
    setSort: (sort: { field: string; direction: 'ASC' | 'DESC' }[]) =>
      analysis.setSort(
        sort.map(entry => ({ alias: entry.field, direction: entry.direction })),
      ),
    maxSortFields: fields.length,
  };
  return (
    <div
      data-slot="analysis-sort"
      className={`mt-auto flex flex-wrap items-center gap-2 ${TEXT_UI}`}
    >
      <SortSettings table={owner} fields={fields} />
      <NumberInput
        label={messages.label('label.analysis.row-limit')}
        chrome="box"
        className="w-20"
        disabled={disabled}
        value={analysis.limit}
        onNumber={next => {
          if (next !== null) analysis.setLimit(next);
        }}
      />
    </div>
  );
}
