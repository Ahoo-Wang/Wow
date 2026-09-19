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

import type { FilterValue } from '../../../model/index.js';
import {
  DATE_TIME_PRESETS,
  RELATIVE_DATE_UNITS,
  writeValue,
  type DateTimePreset,
  type PresetDateTimeValue,
  type RelativeDateDirection,
  type RelativeDateTimeValue,
  type RelativeDateUnit,
} from '../../../filter/index.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { ChoiceValue } from './shared.js';
import { NumberInput } from './number.js';

/**
 * Which side of now a relative window lies on. It reads as the sentence the
 * row makes — "in the last 7 days", "in the next 7 days" — so the direction
 * carries the wording and the shape above stays neutral.
 */
const DATE_DIRECTIONS: readonly RelativeDateDirection[] = ['past', 'future'];

/** A window measured from now: which way, how much, and of what. */
export function RelativeDate({
  value,
  onChange,
  label,
  disabled,
}: {
  value: RelativeDateTimeValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  return (
    <div className="flex items-center gap-2">
      <ChoiceValue
        label={messages.label('label.date.direction-of', {
          field: label,
        })}
        disabled={disabled}
        value={value.direction ?? 'past'}
        items={DATE_DIRECTIONS.map(direction => ({
          label: messages.label(`label.date.${direction}`),
          value: direction,
        }))}
        onChange={next =>
          onChange(
            writeValue({
              ...value,
              direction: next as RelativeDateDirection,
            }),
          )
        }
      />
      <NumberInput
        label={messages.label('label.date.amount-of', { field: label })}
        disabled={disabled}
        className="w-20"
        value={value.amount}
        onNumber={next =>
          // An emptied amount is a row still being written, not a window
          // of zero days: the leaf goes blank and compiles to nothing.
          onChange(
            next === null ? null : writeValue({ ...value, amount: next }),
          )
        }
      />
      <ChoiceValue
        label={messages.label('label.date.unit-of', { field: label })}
        disabled={disabled}
        value={value.unit}
        items={RELATIVE_DATE_UNITS.map(unit => ({
          label: unit,
          value: unit,
        }))}
        onChange={next =>
          onChange(writeValue({ ...value, unit: next as RelativeDateUnit }))
        }
      />
    </div>
  );
}

/** A named period — today, this month — resolved against the engine's clock. */
export function PresetDate({
  value,
  onChange,
  label,
  disabled,
}: {
  value: PresetDateTimeValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  return (
    <ChoiceValue
      label={messages.label('label.date.period-of', { field: label })}
      disabled={disabled}
      value={value.preset}
      items={DATE_TIME_PRESETS.map(preset => ({
        label: preset,
        value: preset,
      }))}
      onChange={next =>
        onChange(writeValue({ type: 'preset', preset: next as DateTimePreset }))
      }
    />
  );
}
