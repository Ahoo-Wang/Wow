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

import { ArrowDownIcon, ArrowUpIcon } from 'lucide-react';
import type { AnalysisEditorController } from '../../react/index.js';
import { NumberInput } from '../FilterValueEditor.js';
import { IconButton } from '../IconButton.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { CompactSelect } from './CompactSelect.js';
import { fieldOfMetric } from './editing.js';

const NONE = '';

/**
 * What the first N groups are the first N of: the sort, and the N, on one
 * row at the bottom of the metrics slot. One sort entry here; Wow takes
 * several, which the tray does not offer yet. A sort needs a dimension, so
 * the row waits for one.
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
  const nameOf = (alias: string) => {
    const group = analysis.groups.find(entry => entry.alias === alias);
    const metric = analysis.metrics.find(entry => entry.alias === alias);
    const field = group?.field ?? (metric && fieldOfMetric(metric));
    if (metric?.type === 'COUNT')
      return messages.label('label.analysis.row-count');
    return analysis.fields.find(entry => entry.field === field)?.label ?? alias;
  };
  const items = [
    { value: NONE, label: messages.label('label.analysis.sort-none') },
    ...[...analysis.aliases.groups, ...analysis.aliases.metrics].map(alias => ({
      value: alias,
      label: nameOf(alias),
    })),
  ];
  const first = analysis.sort[0];
  const direction = first?.direction ?? 'DESC';
  return (
    <div
      data-slot="analysis-sort"
      className={`mt-auto flex flex-wrap items-center gap-2 ${TEXT_UI}`}
    >
      <span className="text-muted-foreground">
        {messages.label('label.analysis.sort')}
      </span>
      <CompactSelect
        label={messages.label('label.analysis.sort')}
        items={items}
        value={first?.alias ?? NONE}
        disabled={disabled}
        onChange={alias =>
          analysis.setSort(alias === NONE ? [] : [{ alias, direction }])
        }
      />
      {first && (
        <IconButton
          // Named by what pressing it does: the other direction.
          label={messages.label(
            direction === 'DESC'
              ? 'label.sort.ascending'
              : 'label.sort.descending',
            { field: nameOf(first.alias) },
          )}
          variant="ghost"
          size="icon-xs"
          disabled={disabled}
          onClick={() =>
            analysis.setSort([
              {
                alias: first.alias,
                direction: direction === 'DESC' ? 'ASC' : 'DESC',
              },
            ])
          }
        >
          {direction === 'DESC' ? <ArrowDownIcon /> : <ArrowUpIcon />}
        </IconButton>
      )}
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
