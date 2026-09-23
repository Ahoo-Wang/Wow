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
import type { FieldDefinition } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { Field, FieldError } from '../components/field.js';
import { formatNumber } from '../display.js';
import { NumberInput } from '../FilterValueEditor.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SortSettings } from '../SortSettings.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { metricReference } from './editing.js';

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
      label: metricReference(analysis, metric, messages),
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
      // `items-start`: the limit's refusal hangs under its box, and the sort
      // button stays level with the box rather than centring on the pair.
      className={`mt-auto flex flex-wrap items-start gap-2 ${TEXT_UI}`}
    >
      <SortSettings table={owner} fields={fields} />
      <LimitField analysis={analysis} disabled={disabled} />
    </div>
  );
}

/**
 * 「前 N 组」: the N, typed.
 *
 * **The draft only ever holds an N Wow takes** — a whole number from 1 to
 * `limitBounds.max` — the rule the having rows keep for a row with no value
 * yet. What the box holds that is not one stays in the box, as typed, marked
 * `aria-invalid` with the range it must fall in beside it, and is not the
 * question: Apply runs the last N that was. It used to go straight into the
 * draft, which is how three defects came about at once — an emptied box
 * wrote nothing and sprang back to the -3 before it, 2.5 was refused as "not
 * positive" by admission, and a -3 left behind by removing the last
 * dimension (which takes this row off the screen) went on refusing Apply
 * with nothing on screen to fix.
 *
 * **Empty is the N a view starts at** (`limitBounds.fallback`), because the
 * model has no "no limit": Wow answers at most its own `MAX_LIMIT` rows
 * whatever is asked. So a blank writes that N and shows it as the
 * placeholder, and the box stays blank rather than filling itself in under
 * the cursor.
 *
 * What the box holds apart from the draft is its own state, so it goes with
 * the box: when the row leaves the screen, the text and its refusal leave
 * with it. It is remembered against the draft's N it was typed over, so an N
 * that moves underneath — a discard, a follow-up, another view — takes the
 * box back. A stored N out of range is marked the same way; admission refuses
 * it too, so the status line says so as well.
 */
function LimitField({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const errorId = useId();
  const { max, fallback } = analysis.limitBounds;
  const [typed, setTyped] = useState<{
    value: number | null;
    over: number;
  } | null>(null);
  const held = typed?.over === analysis.limit ? typed : null;
  const value = held ? held.value : analysis.limit;
  const invalid = value !== null && !withinLimit(value, max);
  return (
    <Field
      data-invalid={invalid || undefined}
      data-slot="analysis-limit"
      className="w-auto gap-1"
    >
      <NumberInput
        label={messages.label('label.analysis.row-limit')}
        chrome="box"
        className="w-20"
        disabled={disabled}
        invalid={invalid}
        describedBy={invalid ? errorId : undefined}
        placeholder={formatNumber(fallback, undefined, locale)}
        value={value}
        onNumber={next => {
          if (next === null) {
            analysis.setLimit(fallback);
            setTyped({ value: null, over: fallback });
          } else if (withinLimit(next, max)) {
            analysis.setLimit(next);
            setTyped(null);
          } else setTyped({ value: next, over: analysis.limit });
        }}
      />
      {invalid && (
        <FieldError id={errorId}>
          {messages.label('label.analysis.row-limit-invalid', { max })}
        </FieldError>
      )}
    </Field>
  );
}

/** Whether Wow takes this N: the one rule admission holds the draft to. */
function withinLimit(value: number, max: number): boolean {
  return Number.isInteger(value) && value >= 1 && value <= max;
}
