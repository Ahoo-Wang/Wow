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

import {
  ANALYSIS_DATE_DIFF_UNITS,
  type AnalysisDateDiffUnit,
  type FilterValue,
} from '../../../model/index.js';
import {
  DURATION_COMPARISONS,
  isDurationFilterValue,
  writeValue,
  type DurationComparison,
  type DurationFilterValue,
} from '../../../filter/index.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { COMPARISON_SIGN } from '../../summary.js';
import { NumberInput } from './number.js';
import { ChoiceValue, type ValueProps } from './shared.js';

/**
 * A time since another moment (N3): 「距 [付款时间] [>] [48] [小时]」 — the
 * earlier time among the fields beside this one, a comparison, an amount and
 * a unit. Nothing is written until the earlier time is picked, which is what
 * makes the condition a condition; after that each control changes its one
 * part and keeps the rest.
 */
export function DurationValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
  times,
}: ValueProps & {
  /** The times the gap may run from: every other time field beside this one. */
  times: readonly { value: string; label: string }[];
}) {
  const messages = useViewMessages();
  const current: Partial<DurationFilterValue> =
    value !== null && typeof value === 'object' && !Array.isArray(value)
      ? value
      : {};
  const whole = (patch: Partial<DurationFilterValue>): FilterValue =>
    writeValue({
      from: current.from ?? '',
      comparison: current.comparison ?? 'GT',
      value: current.value ?? 0,
      unit: current.unit ?? 'HOUR',
      ...patch,
    } satisfies DurationFilterValue);
  const set = isDurationFilterValue(value);
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-2">
      <ChoiceValue
        label={messages.label('label.filter.duration-from')}
        disabled={disabled}
        invalid={invalid}
        value={current.from || null}
        placeholder={messages.label('label.filter.duration-pick')}
        items={[...times]}
        onChange={from => onChange(whole({ from }))}
      />
      <ChoiceValue
        label={messages.label('label.filter.duration-comparison')}
        disabled={disabled || !set}
        invalid={invalid}
        value={current.comparison ?? 'GT'}
        items={DURATION_COMPARISONS.map(comparison => ({
          value: comparison,
          label: COMPARISON_SIGN[comparison],
        }))}
        onChange={comparison =>
          onChange(whole({ comparison: comparison as DurationComparison }))
        }
      />
      <NumberInput
        label={`${label} · ${messages.label('label.filter.duration-amount')}`}
        className="w-16"
        disabled={disabled || !set}
        invalid={invalid}
        value={current.value}
        onNumber={amount => {
          if (amount !== null) onChange(whole({ value: amount }));
        }}
      />
      <ChoiceValue
        label={messages.label('label.filter.duration-unit')}
        disabled={disabled || !set}
        invalid={invalid}
        value={current.unit ?? 'HOUR'}
        items={ANALYSIS_DATE_DIFF_UNITS.map(unit => ({
          value: unit,
          label: messages.label(`label.date-diff-unit.${unit}`),
        }))}
        onChange={unit =>
          onChange(whole({ unit: unit as AnalysisDateDiffUnit }))
        }
      />
    </div>
  );
}
