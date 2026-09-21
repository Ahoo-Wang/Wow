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
import type { FilterValue } from '../../../model/index.js';
import { writeValue, type DateTimeFilterValue } from '../../../filter/index.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { AbsoluteDate } from './daterange.js';
import { PresetDate, RelativeDate } from './relative.js';
import { ChoiceValue, type ValueProps } from './shared.js';

type DateShape = DateTimeFilterValue['type'];

/** `relative` no longer means backwards, so the shape no longer says it. */
const DATE_SHAPES: readonly DateShape[] = ['absolute', 'relative', 'preset'];

/**
 * A moment in time, said in one of three ways: off a calendar, as a window
 * measured from now, or as a named period.
 *
 * The shape is chosen first, and each one has its own controls beside it.
 * `date`, `dateRange` and `relativeDate` all arrive here — the kind's input
 * decides whether the calendar picks one bound or two, not which shapes are
 * on offer.
 */
export function DateValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
  range,
  withTime,
}: ValueProps & { range: boolean; withTime: boolean }) {
  const messages = useViewMessages();
  const stored = readDateValue(value);
  // Which shape a blank row is in. Emptying the amount of "in the last 7
  // days" blanks the leaf, and without this the editor would jump back to
  // the calendar under the user's hands.
  const [shape, setShape] = useState<DateShape>(stored?.type ?? 'absolute');
  if (stored !== null && stored.type !== shape) setShape(stored.type);
  const current = stored ?? blankDateValue(shape);

  return (
    <div className="flex flex-wrap items-center gap-2">
      <ChoiceValue
        label={messages.label('label.date.shape-of', { field: label })}
        disabled={disabled}
        invalid={invalid}
        value={current.type}
        items={DATE_SHAPES.map(shape => ({
          label: messages.label(`label.date.${shape}`),
          value: shape,
        }))}
        onChange={next => {
          const picked = next as DateShape;
          // `absolute` writes nothing, so the shape has to be remembered
          // here: the leaf stays blank and would otherwise report the
          // calendar's own default back on the next render.
          setShape(picked);
          onChange(shapeDefault(picked));
        }}
      />
      {current.type === 'absolute' && (
        <AbsoluteDate
          value={current}
          onChange={onChange}
          label={label}
          disabled={disabled}
          invalid={invalid}
          range={range}
          withTime={withTime}
        />
      )}
      {current.type === 'relative' && (
        <RelativeDate
          value={current}
          onChange={onChange}
          label={label}
          disabled={disabled}
          invalid={invalid}
        />
      )}
      {current.type === 'preset' && (
        <PresetDate
          value={current}
          onChange={onChange}
          label={label}
          disabled={disabled}
          invalid={invalid}
        />
      )}
    </div>
  );
}

/** The value as one of the three date shapes, or `null` while it is blank. */
function readDateValue(value: FilterValue): DateTimeFilterValue | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const shape = (value as { type?: unknown }).type;
    if (shape === 'absolute' || shape === 'relative' || shape === 'preset')
      return value as unknown as DateTimeFilterValue;
  }
  return null;
}

/**
 * What a blank leaf's controls stand at. It is drawn, never stored — the
 * leaf keeps whatever it holds, which for a blank one is nothing.
 *
 * `absolute` has no bound, so both ends read the pick placeholder. Seeding it
 * with the clock put "from this moment on" on screen under a dashed, blank
 * pill: the kernel refused to compile it, so applying changed no row, while
 * the condition said it had narrowed the list from now.
 */
function blankDateValue(shape: DateShape): DateTimeFilterValue {
  if (shape === 'relative') return { type: 'relative', amount: 7, unit: 'day' };
  if (shape === 'preset') return { type: 'preset', preset: 'today' };
  return { type: 'absolute', from: '' };
}

/**
 * What choosing a shape writes. A window and a period are one answer each —
 * "the last 7 days", "today" — and saying them is the whole condition; a
 * calendar date is not chosen yet, so `absolute` blanks the leaf and waits.
 */
function shapeDefault(shape: DateShape): FilterValue {
  return shape === 'absolute' ? null : writeValue(blankDateValue(shape));
}
