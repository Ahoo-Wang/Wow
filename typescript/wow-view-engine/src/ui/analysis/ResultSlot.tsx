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
import { cn } from 'cn';
import {
  Field,
  FieldError,
  FieldLabel,
  FieldTitle,
} from '../components/field.js';
import { columnTitle, formatNumber } from '../display.js';
import { NumberInput } from '../FilterValueEditor.js';
import { SPACE } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SortSettings } from '../SortSettings.js';
import { EditorSlot } from '../variants.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { metricReference } from './editing.js';
import { HavingRows } from './HavingRows.js';

/**
 * 「结果」: the step after the question (2026-09-23 audit) — which groups
 * the result keeps, in which order, and how many of them. Wow applies the
 * three in that order (having, then sort, then limit), and the analyst says
 * them in it too: 「只保留金额的合计大于 2000 的组，按金额的合计降序，取前 10
 * 组」. So the slot reads top to bottom as that sentence, each control under
 * a visible label, with 「前 N 组」 beside the sort it is the first N of.
 *
 * They used to sit at the foot of the metrics slot with no label of their
 * own: a button that said 「排序」 at rest, a bare number box and 「只保留…」,
 * none of which said which step of the question it was. They are a step of
 * their own because none of them is a metric — each is about the groups.
 *
 * All three need a dimension (Wow refuses to sort, cut or keep an ungrouped
 * aggregation, which has one row anyway), so without one the slot is not
 * drawn rather than drawn empty.
 */
export function ResultSlot({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  if (analysis.groups.length === 0) return null;
  return (
    <EditorSlot
      name="result"
      title={messages.label('label.analysis.slot.result')}
      hint={messages.label('label.analysis.hint.result')}
    >
      <HavingRows analysis={analysis} disabled={disabled} />
      {/* `items-start`: the limit's refusal hangs under its box, and the
          sort stays level with the box rather than centring on the pair. */}
      <div
        data-slot="analysis-order"
        className={cn('flex flex-wrap items-start gap-x-6', SPACE.GROUPS)}
      >
        <SortField analysis={analysis} />
        <LimitField analysis={analysis} disabled={disabled} />
      </div>
    </EditorSlot>
  );
}

/**
 * The order of the groups: the record view's own sort editor, over the
 * aliases as if they were fields (D20: one control for one thing) — every
 * dimension and metric may order the groups, first by one, then by the
 * next. The label is a title rather than a `<label>`: the button carries a
 * name of its own that reads the sort back (「排序：金额的合计 降序」), and
 * the title names the group it stands in.
 */
function SortField({ analysis }: { analysis: AnalysisEditorController }) {
  const messages = useViewMessages();
  const titleId = useId();
  const fields: FieldDefinition[] = [
    ...analysis.groups.map(group => ({
      name: group.alias,
      // Named as the result's column is — a time dimension with its
      // granularity — so the sort says the header it orders by.
      label: columnTitle(
        group.label === undefined
          ? {
              label:
                analysis.fields.find(entry => entry.field === group.field)
                  ?.label ?? group.field,
              ...(group.type === 'DATE_HISTOGRAM'
                ? { dateUnit: group.unit }
                : {}),
            }
          : { label: group.label, named: true },
        messages,
      ),
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
    <Field
      data-slot="analysis-sort"
      aria-labelledby={titleId}
      className="w-auto gap-1"
    >
      <FieldTitle id={titleId}>{messages.label('label.sort.title')}</FieldTitle>
      {/* A box of its own: a vertical field stretches each child to its
          width, and the button is as wide as what it says. */}
      <div>
        <SortSettings table={owner} fields={fields} />
      </div>
    </Field>
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
  const inputId = useId();
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
      <FieldLabel htmlFor={inputId}>
        {messages.label('label.analysis.row-limit')}
      </FieldLabel>
      <NumberInput
        id={inputId}
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
