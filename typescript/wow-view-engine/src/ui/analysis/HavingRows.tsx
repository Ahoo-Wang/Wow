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
import { isValueMetric } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { cn } from 'cn';
import { Button } from '../components/button.js';
import { FieldLegend, FieldSet } from '../components/field.js';
import { NumberInput } from '../FilterValueEditor.js';
import { IconButton } from '../IconButton.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { EditorCard } from '../variants.js';
import { CompactSelect } from './CompactSelect.js';
import { metricReference } from './editing.js';
import { useListFocus } from './listFocus.js';

/**
 * 「只保留」 (D20 屏 B; Wow `having`): which groups the result keeps, as
 * rows of one comparison each, first in the result slot — 「金额的总和
 * 大于 10,000」 — all of which must hold. It exists only where the capability
 * declares `having` and there is something to keep (a dimension); a
 * having over one row is refused by Wow. A row being filled in is the
 * editor's until it has a value, so the config on disk always holds
 * something Wow accepts. A stored having of another shape is shown as
 * present and clearable, not pretended to be editable.
 *
 * The state is a hook rather than the rows' own, because the way in is not
 * always beside them: with no row there is no 「只保留」 block at all, only a
 * 「只保留…」 button on the result's one line (`ResultSlot`) — an empty
 * block with a legend and a lone button under it was two rows of the tray
 * saying that nothing is kept (2026-09-23 audit).
 */
export function useHaving(analysis: AnalysisEditorController) {
  const messages = useViewMessages();
  // Rows the config cannot hold yet — no value — live here; the config
  // holds the complete ones. The key ties the local rows to the config's,
  // so a revert or an applied change starts the editor over.
  const stored = analysis.having;
  const [pending, setPending] = useState<HavingRow[]>([]);
  // A row taken out leaves the keyboard on the row that took its place, or
  // on the way to add one once the last one goes (`listFocus.ts`). The list
  // is the result slot, which stays when the block goes with its last row.
  const focus = useListFocus({
    list: '[data-slot="analysis-slot-result"]',
    item: '[data-slot="having-row"]',
    add: '[data-slot="add-having"]',
  });
  const rows = stored === null ? [] : [...stored, ...pending];
  const write = (next: HavingRow[]) => {
    setPending(next.filter(row => row.value === null));
    analysis.setHaving(withHavingRows(next));
  };
  // A group is kept by a number it is compared with; a sample value and a
  // moment (`analysis.moments`) have none a reader would type.
  const keepable = analysis.metrics
    .filter(
      metric =>
        !isValueMetric(metric) &&
        !analysis.moments.has(metric.alias) &&
        (analysis.havingMetrics === null ||
          analysis.havingMetrics.includes(metric.type)),
    )
    .map(metric => ({
      value: metric.alias,
      label: metricReference(analysis, metric, messages),
    }));
  const first = keepable[0];
  return {
    analysis,
    /** Whether 「只保留」 exists here at all. */
    allowed: analysis.havingAllowed && analysis.groups.length > 0,
    /** `null` while the stored having is a shape the rows cannot say. */
    stored,
    rows,
    keepable,
    focus,
    write,
    /** Adds a row, or is absent where no metric can keep a group. */
    add:
      stored !== null && first
        ? () =>
            write([
              ...rows,
              { metric: first.value, operator: 'GT', value: null },
            ])
        : undefined,
  };
}

export type Having = ReturnType<typeof useHaving>;

/**
 * The way to a first row, where there is none: 「只保留…」 on the result's
 * line, beside the sort and 「前 N 组」. The rows' own block adds the next
 * one under its legend instead (`HavingRows`).
 */
export function AddHaving({
  having,
  disabled,
}: {
  having: Having;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  if (!having.allowed || !having.add || having.rows.length > 0) return null;
  return (
    <Button
      variant="ghost"
      size="sm"
      data-slot="add-having"
      disabled={disabled}
      onClick={having.add}
    >
      <PlusIcon data-icon="inline-start" />
      {messages.label('label.analysis.having-first')}
    </Button>
  );
}

/** The rows of 「只保留」, under their legend; nothing while there are none. */
export function HavingRows({
  having,
  disabled,
}: {
  having: Having;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const { analysis, stored, rows, keepable, focus, write } = having;
  if (!having.allowed) return null;
  if (stored !== null && rows.length === 0) return null;
  const operators = HAVING_OPERATORS.map(operator => ({
    value: operator,
    label: messages.label(`label.having.op.${operator}`),
  }));
  return (
    // A set of rows under one visible label, which is the fieldset's
    // legend: the rows' own controls are named for what they are (metric,
    // comparison, value), and the legend is what says they keep groups.
    <FieldSet data-slot="analysis-having" className={cn('gap-2', TEXT_UI)}>
      <FieldLegend variant="label" className="mb-0">
        {messages.label('label.analysis.having-title')}
      </FieldLegend>
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
            {/* The legend above says 「只保留」 once; a second row reads on
                from the first, since every row has to hold. */}
            {index > 0 && (
              <span className="text-muted-foreground">
                {messages.label('label.analysis.having-and')}
              </span>
            )}
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
              onClick={event => {
                focus.removing(event, index);
                write(rows.filter((_row, at) => at !== index));
              }}
            >
              <XIcon />
            </IconButton>
          </EditorCard>
        ))
      )}
      {having.add && (
        <Button
          variant="ghost"
          size="sm"
          className="self-start"
          data-slot="add-having"
          disabled={disabled}
          onClick={having.add}
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
    </FieldSet>
  );
}
