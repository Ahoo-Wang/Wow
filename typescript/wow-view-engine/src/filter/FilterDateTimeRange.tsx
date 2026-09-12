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

import { useOverlayOpen } from '../lib/OverlayScope.js';
import { sameJsonState } from '../lib/snapshot.js';
import type {
  FilterFieldDefinition,
  FilterComponentProperties,
  FilterDateTimeValue,
} from './filterModel.js';
import { useId, useState } from 'react';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { CalendarIcon } from 'lucide-react';
import { zhCN } from 'react-day-picker/locale';
import { Calendar } from '../components/ui/calendar.js';
import { Button } from '../components/ui/button.js';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../components/ui/popover.js';
import { FilterTimeInput } from './FilterTimeInput.js';
import {
  calendarDate,
  dateText,
  dateTimeValue,
  dateTimeToSeconds,
} from './filterDateTimeValue.js';
import { getBuiltinFilterCompiler } from './builtinFilterCompilers.js';

import {
  InputGroup,
  InputGroupButton,
  InputGroupInput,
} from '../components/ui/input-group.js';
export interface FilterDateTimeRangeProps {
  field: FilterFieldDefinition;
  /** Show time inputs for datetime fields; defaults to date-only calendar days. */
  showTime?: boolean;
  timeZone?: string;
  invalid?: boolean;
  errorId?: string;
  value: Partial<Pick<FilterComponentProperties, 'lowerBound' | 'upperBound'>>;
  onValueChange(value: FilterDateTimeRangeProps['value']): void;
  onValidityChange?(valid: boolean, message?: string): void;
  disabled?: boolean;
}
export function FilterDateTimeRange({
  field,
  showTime = false,
  timeZone,
  invalid,
  errorId,
  value,
  onValueChange,
  onValidityChange,
  disabled,
}: FilterDateTimeRangeProps) {
  const [open, setOpen] = useOverlayOpen();
  const [draft, setDraft] = useState(value);
  const [draftError, setDraftError] = useState<string>();
  const draftErrorId = useId();
  const datetime = field.type === 'datetime';
  const timed = datetime && showTime;
  const inputs = { field, showTime, timeZone, value, disabled };
  const [previous, setPrevious] = useState(inputs);
  if (!sameJsonState(previous, inputs)) {
    setPrevious(inputs);
    if (
      timed ||
      (previous.field.type === 'datetime' && previous.showTime) ||
      previous.timeZone !== timeZone
    ) {
      setDraft(value);
      setDraftError(undefined);
      setOpen(false);
    }
  }
  const parts = (bound: unknown) =>
    datetime
      ? dateTimeValue(timed ? dateTimeToSeconds(bound) : bound, timeZone)
      : {
          date:
            // 草稿值可为任意已保存 JSON，故意 ToString 以保持既有输入框回显不变。
            // eslint-disable-next-line @typescript-eslint/no-base-to-string
            bound === undefined || bound === null ? undefined : String(bound),
        };
  const lower = parts(value.lowerBound);
  const upper = parts(value.upperBound);
  const start = timed ? parts(draft.lowerBound) : lower;
  const end = timed ? parts(draft.upperBound) : upper;
  const from = calendarDate(start.date);
  const to = calendarDate(end.date);
  const label = `${field.label}日期范围`;
  const boundText = (bound: FilterDateTimeValue, placeholder: string) =>
    [bound.date || placeholder, timed ? bound.time : undefined]
      .filter(Boolean)
      .join(' ');
  const display =
    lower.date || upper.date || (timed && (lower.time || upper.time))
      ? `${boundText(lower, '开始日期')} 至 ${boundText(upper, '结束日期')}`
      : '选择日期范围';
  const dateValue = (parts: FilterDateTimeValue, date: Date | undefined) => {
    const next = date ? dateText(date) : undefined;
    return (
      datetime ? { ...parts, date: next } : next
    ) as FilterComponentProperties['lowerBound'];
  };
  function validate(next: FilterDateTimeRangeProps['value']) {
    try {
      getBuiltinFilterCompiler('datetime-range')!.compile(next, {
        operator: FilterOperator.BETWEEN,
        field,
        fields: [field],
        timeZone,
        options: { showTime },
      });
    } catch (error) {
      return error instanceof Error ? error.message : '区间无效';
    }
  }
  function change(next: FilterDateTimeRangeProps['value']) {
    if (disabled) return;
    onValueChange(next);
    const message = validate(next);
    if (message) onValidityChange?.(false, message);
    else onValidityChange?.(true);
  }
  function edit(next: FilterDateTimeRangeProps['value']) {
    if (disabled) return;
    setDraft(next);
    setDraftError(undefined);
  }
  function editBound(
    bound: 'lowerBound' | 'upperBound',
    next: FilterDateTimeValue,
  ) {
    edit({
      ...draft,
      [bound]: { ...parts(draft[bound]), ...next },
    });
  }
  const describedBy = draftError ? draftErrorId : invalid ? errorId : undefined;
  return (
    <span
      data-slot="filter-date-range"
      className="fve-root fve:inline-flex fve:min-w-0 fve:max-w-full fve:flex-1 fve:items-center"
    >
      <Popover
        open={open}
        onOpenChange={next => {
          if (next) {
            setDraft(value);
            setDraftError(undefined);
          }
          setOpen(next);
        }}
      >
        <PopoverTrigger
          render={<InputGroupButton size="sm" />}
          aria-label={`${label}：${display}`}
          aria-invalid={
            invalid ||
            !!(
              (lower.date && !calendarDate(lower.date)) ||
              (upper.date && !calendarDate(upper.date))
            )
          }
          aria-describedby={invalid ? errorId : undefined}
          title={display}
          disabled={disabled}
          className="fve:min-w-0 fve:max-w-full"
        >
          <CalendarIcon data-icon="inline-start" aria-hidden="true" />
          <span className="fve:truncate">{display}</span>
        </PopoverTrigger>
        <PopoverContent
          align="start"
          className="fve:w-auto fve:max-w-(--available-width) fve:max-h-[min(36rem,var(--available-height))] fve:gap-0 fve:overflow-y-auto fve:p-0"
          onKeyDown={event => {
            if (event.key === 'Enter') event.stopPropagation();
          }}
        >
          <PopoverTitle className="fve:sr-only">{label}</PopoverTitle>
          <div className="fve:flex fve:flex-col fve:sm:flex-row">
            <Calendar
              locale={zhCN}
              mode="range"
              numberOfMonths={timed ? 1 : 2}
              showOutsideDays={false}
              selected={from || to ? { from, to } : undefined}
              defaultMonth={from ?? to}
              captionLayout="dropdown-months"
              resetOnSelect
              required
              disabled={disabled}
              onSelect={range => {
                const next = {
                  ...(timed ? draft : value),
                  lowerBound: dateValue(start, range.from),
                  upperBound: dateValue(end, range.to),
                };
                if (timed) edit(next);
                else {
                  change(next);
                  if (range.from && range.to) setOpen(false);
                }
              }}
            />
            {timed && (
              <div className="fve:flex fve:min-w-0 fve:flex-col fve:gap-4 fve:p-3">
                <p className="fve:m-0 fve:text-sm fve:text-muted-foreground">
                  {from && !to ? '请选择结束日期' : '选择日期后填写起止时间'}
                </p>
                {(
                  [
                    ['lowerBound', '开始', start],
                    ['upperBound', '结束', end],
                  ] as const
                ).map(([bound, name, selected]) => (
                  <fieldset
                    key={bound}
                    className="fve:m-0 fve:min-w-0 fve:border-0 fve:p-0"
                  >
                    <legend className="fve:mb-1.5 fve:p-0 fve:text-sm fve:font-medium">
                      {name}
                    </legend>
                    <InputGroup className="fve:w-auto">
                      <InputGroupInput
                        aria-label={`${field.label}${name}日期`}
                        aria-invalid={
                          !!draftError ||
                          invalid ||
                          !!(selected.date && !calendarDate(selected.date))
                        }
                        aria-describedby={describedBy}
                        value={selected.date ?? ''}
                        placeholder="YYYY-MM-DD"
                        disabled={disabled}
                        className="fve:w-28 fve:flex-none"
                        onChange={event =>
                          editBound(bound, {
                            date: event.target.value || undefined,
                          })
                        }
                      />
                      <FilterTimeInput
                        label={`${field.label}${name}时间`}
                        value={selected.time}
                        disabled={disabled}
                        inline
                        invalid={!!draftError || invalid}
                        errorId={describedBy}
                        onValueChange={time =>
                          editBound(bound, { time: time || undefined })
                        }
                      />
                    </InputGroup>
                  </fieldset>
                ))}
              </div>
            )}
          </div>
          {timed && draftError && (
            <p
              id={draftErrorId}
              role="alert"
              className="fve:m-0 fve:px-3 fve:pb-2 fve:text-sm fve:text-destructive"
            >
              {draftError}
            </p>
          )}
          <div className="fve:flex fve:items-center fve:justify-end fve:gap-2 fve:border-t fve:p-2">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={disabled}
              className="fve:mr-auto"
              onClick={() => {
                const next = {
                  ...(timed ? draft : value),
                  lowerBound: undefined,
                  upperBound: undefined,
                };
                if (timed) edit(next);
                else {
                  change(next);
                  setOpen(false);
                }
              }}
            >
              清空区间
            </Button>
            {timed && (
              <>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setOpen(false)}
                >
                  取消
                </Button>
                <Button
                  type="button"
                  size="sm"
                  disabled={disabled}
                  onClick={() => {
                    if (disabled) return;
                    const next = {
                      ...draft,
                      lowerBound: dateTimeToSeconds(
                        draft.lowerBound,
                      ) as typeof draft.lowerBound,
                      upperBound: dateTimeToSeconds(
                        draft.upperBound,
                      ) as typeof draft.upperBound,
                    };
                    const message = validate(next);
                    setDraftError(message);
                    if (message) return;
                    onValueChange(next);
                    onValidityChange?.(true);
                    setOpen(false);
                  }}
                >
                  确定
                </Button>
              </>
            )}
          </div>
        </PopoverContent>
      </Popover>
    </span>
  );
}
