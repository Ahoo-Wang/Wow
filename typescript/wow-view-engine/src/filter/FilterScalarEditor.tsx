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

import type {
  FilterFieldDefinition,
  FilterScalarDraftValue,
} from './filterModel.js';
import {
  calendarDate,
  dateText,
  dateTimeValue,
  dateTimeToSeconds,
} from './filterDateTimeValue.js';
import { FilterDatePicker } from './FilterDatePicker.js';
import { FilterSelect } from './FilterSelect.js';
import { FilterTimeInput } from './FilterTimeInput.js';
import {
  InputGroupInput,
  InputGroupText,
} from '../components/ui/input-group.js';

const booleanOptions = [
  { value: true, label: '是' },
  { value: false, label: '否' },
];
const scalarTypes = [
  { value: 'string', label: '文本' },
  { value: 'number', label: '数值' },
  { value: 'boolean', label: '布尔' },
] as const;
function textValue(value: unknown): string {
  // 展示层兜底：故意对任意标量草稿值做 ToString，保持既有运行时输出不变。
  // eslint-disable-next-line @typescript-eslint/no-base-to-string
  return value === undefined || value === null ? '' : String(value);
}

function typedValue(value: unknown): value is FilterScalarDraftValue {
  return (
    typeof value === 'object' &&
    value !== null &&
    'type' in value &&
    scalarTypes.some(item => item.value === value.type)
  );
}

export function ScalarEditor({
  value,
  label,
  dateLabel = label,
  field,
  nullable = false,
  disabled,
  showTime = false,
  timeZone,
  invalid,
  errorId,
  onChange,
}: {
  value: unknown;
  label: string;
  dateLabel?: string;
  field?: FilterFieldDefinition;
  nullable?: boolean;
  disabled?: boolean;
  showTime?: boolean;
  timeZone?: string;
  invalid?: boolean;
  errorId?: string;
  onChange(value: unknown): void;
}) {
  const wrapped = typedValue(value);
  const raw = wrapped ? value.value : value;
  const freeType = !field?.type || field.type === 'array';
  const kind = freeType
    ? wrapped
      ? value.type
      : typeof value === 'number' || typeof value === 'boolean'
        ? typeof value
        : 'string'
    : field.type;
  const choices =
    field?.options ?? (kind === 'boolean' ? booleanOptions : undefined);
  const changeValue = (next: unknown) =>
    onChange(
      next === undefined && (freeType || kind === 'number') && !field?.options
        ? { type: kind, value: undefined }
        : next,
    );
  if (choices) {
    const options = [
      ...choices.map((option, index) => ({ ...option, value: String(index) })),
      ...(nullable ? [{ value: 'null', label: '空值' }] : []),
    ];
    const selected =
      raw === null && nullable
        ? 'null'
        : choices.findIndex(option => Object.is(option.value, raw));
    const incompatible = raw !== undefined && selected === -1;
    return (
      <span className="fve:inline-flex fve:max-w-full fve:flex-wrap fve:items-center">
        {freeType && !field?.options && (
          <FilterSelect
            invalid={invalid}
            errorId={errorId}
            label={`${label}类型`}
            value={kind}
            options={scalarTypes}
            inline
            disabled={disabled}
            onValueChange={type => onChange({ type, value: raw })}
          />
        )}
        <FilterSelect
          invalid={invalid}
          errorId={errorId}
          label={label}
          placeholder={incompatible ? `已有值：${textValue(raw)}` : '未设置'}
          value={
            raw === undefined ? null : incompatible ? null : String(selected)
          }
          options={options}
          inline
          disabled={disabled}
          onClear={() => changeValue(undefined)}
          onValueChange={selected =>
            onChange(
              selected === 'null' ? null : choices[Number(selected)].value,
            )
          }
        />
        {incompatible && (
          <>
            <InputGroupText role="alert">值与选项不兼容</InputGroupText>
          </>
        )}
      </span>
    );
  }
  if (kind === 'date' || kind === 'datetime') {
    const parts =
      kind === 'datetime'
        ? dateTimeValue(showTime ? dateTimeToSeconds(raw) : raw, timeZone)
        : { date: textValue(raw) || undefined };
    const updateDate = (date: string | undefined) =>
      onChange(kind === 'datetime' ? { ...parts, date } : date);
    return (
      <span className="fve:inline-flex fve:max-w-full fve:flex-wrap fve:items-center">
        <InputGroupInput
          aria-label={`${dateLabel}日期`}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
          placeholder={raw === null ? '空值' : 'YYYY-MM-DD'}
          value={parts.date ?? ''}
          disabled={disabled}
          className="fve:w-28 fve:flex-none"
          onChange={event => updateDate(event.target.value || undefined)}
        />
        <FilterDatePicker
          invalid={invalid}
          errorId={errorId}
          label={`${dateLabel}日历`}
          value={calendarDate(parts.date)}
          disabled={disabled}
          inline
          onValueChange={date => updateDate(date ? dateText(date) : undefined)}
        />
        {kind === 'datetime' && showTime && (
          <FilterTimeInput
            invalid={invalid}
            errorId={errorId}
            label={`${dateLabel}时间`}
            value={parts.time}
            disabled={disabled}
            inline
            onValueChange={time =>
              onChange({ ...parts, time: time || undefined })
            }
          />
        )}
      </span>
    );
  }
  const incompatible =
    raw !== undefined &&
    raw !== null &&
    (wrapped ? value.type !== kind : typeof raw !== kind);
  return (
    <span className="fve:inline-flex fve:max-w-full fve:flex-wrap fve:items-center">
      {freeType && (
        <FilterSelect
          invalid={invalid}
          errorId={errorId}
          label={`${label}类型`}
          value={kind}
          options={scalarTypes}
          inline
          disabled={disabled}
          onValueChange={type => onChange({ type, value: raw })}
        />
      )}
      <InputGroupInput
        aria-label={label}
        value={textValue(raw)}
        disabled={disabled}
        inputMode={kind === 'number' ? 'decimal' : undefined}
        placeholder={raw === null ? '空值' : raw === '' ? '空字符串' : '未设置'}
        aria-invalid={invalid || incompatible || undefined}
        aria-describedby={invalid ? errorId : undefined}
        className="fve:w-32 fve:flex-none"
        onChange={event => {
          const next = event.target.value || undefined;
          onChange(
            kind === 'number' || (freeType && wrapped)
              ? { type: kind, value: next }
              : next,
          );
        }}
      />
      {incompatible && (
        <InputGroupText role="alert">值与字段类型不兼容</InputGroupText>
      )}
    </span>
  );
}
