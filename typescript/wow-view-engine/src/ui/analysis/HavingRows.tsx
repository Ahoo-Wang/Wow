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

import { useState } from 'react';
import { PlusIcon, XIcon } from 'lucide-react';
import {
  HAVING_OPERATORS,
  withHavingRows,
  type HavingRow,
} from '../../analysis/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { cn } from 'cn';
import { Button } from '../components/button.js';
import { NumberInput } from '../FilterValueEditor.js';
import { IconButton } from '../IconButton.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { EditorCard } from '../variants.js';
import { CompactSelect } from './CompactSelect.js';
import { metricReference } from './editing.js';

/**
 * 「只保留」 (D20 屏 B; Wow `having`): which groups the result keeps, as
 * rows of one comparison each under the metrics — 「金额合计 大于
 * 10,000」 — all of which must hold. It exists only where the capability
 * declares `having` and there is something to keep (a dimension); a
 * having over one row is refused by Wow. A row being filled in is the
 * editor's until it has a value, so the config on disk always holds
 * something Wow accepts. A stored having of another shape is shown as
 * present and clearable, not pretended to be editable.
 */
export function HavingRows({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  // Rows the config cannot hold yet — no value — live here; the config
  // holds the complete ones. The key ties the local rows to the config's,
  // so a revert or an applied change starts the editor over.
  const stored = analysis.having;
  const [pending, setPending] = useState<HavingRow[]>([]);
  if (!analysis.havingAllowed || analysis.groups.length === 0) return null;
  const rows = stored === null ? [] : [...stored, ...pending];
  const write = (next: HavingRow[]) => {
    setPending(next.filter(row => row.value === null));
    analysis.setHaving(withHavingRows(next));
  };
  const keepable = analysis.metrics
    .filter(metric => metric.type !== 'ANY')
    .map(metric => ({
      value: metric.alias,
      label: metricReference(analysis, metric, messages),
    }));
  const operators = HAVING_OPERATORS.map(operator => ({
    value: operator,
    label: messages.label(`label.having.op.${operator}`),
  }));
  const first = keepable[0];
  return (
    <div
      data-slot="analysis-having"
      role="group"
      aria-label={messages.label('label.analysis.having-title')}
      className={cn('flex flex-col gap-2', TEXT_UI)}
    >
      {stored === null ? (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-muted-foreground">
            {messages.label('label.analysis.having-unreadable')}
          </span>
          <Button
            variant="outline"
            size="xs"
            disabled={disabled}
            onClick={() => analysis.setHaving(undefined)}
          >
            {messages.label('label.analysis.clear-having')}
          </Button>
        </div>
      ) : (
        rows.map((row, index) => (
          // Each row carries the same four names — metric, comparison,
          // value, remove — so on its own a control says which kind of
          // thing it is and never which row it belongs to. The row is
          // therefore a named group, numbered as the analyst counts.
          <EditorCard
            key={index}
            data-slot="having-row"
            role="group"
            aria-label={messages.label('label.analysis.having-row', {
              index: index + 1,
            })}
          >
            <span className="text-muted-foreground">
              {messages.label('label.analysis.having-keep')}
            </span>
            <CompactSelect
              label={messages.label('label.analysis.having-metric')}
              items={keepable}
              value={row.metric}
              disabled={disabled}
              onChange={metric =>
                write(
                  rows.map((entry, at) =>
                    at === index ? { ...entry, metric } : entry,
                  ),
                )
              }
            />
            <CompactSelect
              label={messages.label('label.analysis.having-operator')}
              items={operators}
              value={row.operator}
              disabled={disabled}
              onChange={operator =>
                write(
                  rows.map((entry, at) =>
                    at === index ? { ...entry, operator } : entry,
                  ),
                )
              }
            />
            <NumberInput
              label={messages.label('label.analysis.having-value')}
              chrome="box"
              className="w-24"
              disabled={disabled}
              value={row.value}
              onNumber={value =>
                write(
                  rows.map((entry, at) =>
                    at === index ? { ...entry, value } : entry,
                  ),
                )
              }
            />
            <IconButton
              label={messages.label('label.analysis.remove-having')}
              variant="ghost"
              size="icon-xs"
              className="ml-auto"
              disabled={disabled}
              onClick={() => write(rows.filter((_row, at) => at !== index))}
            >
              <XIcon />
            </IconButton>
          </EditorCard>
        ))
      )}
      {stored !== null && first && (
        <Button
          variant="ghost"
          size="sm"
          className="self-start"
          data-slot="add-having"
          disabled={disabled}
          onClick={() =>
            write([
              ...rows,
              { metric: first.value, operator: 'GT', value: null },
            ])
          }
        >
          <PlusIcon data-icon="inline-start" />
          {messages.label('label.analysis.having')}
        </Button>
      )}
      {rows.length > 0 && (
        <span data-slot="having-note" className="text-muted-foreground">
          {messages.label('label.analysis.having-note')}
        </span>
      )}
    </div>
  );
}
