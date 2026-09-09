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

import { ClockIcon } from 'lucide-react';
import { FilterSelect } from './FilterSelect.js';
import { timeToSeconds } from './filterDateTimeValue.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
  InputGroupText,
} from '../components/ui/input-group.js';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../components/ui/popover.js';

const timePattern = /^([01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?$/;
const hours = Array.from({ length: 24 }, (_, value) => ({
  value: String(value).padStart(2, '0'),
  label: String(value).padStart(2, '0'),
}));
const minutes = Array.from({ length: 60 }, (_, value) => ({
  value: String(value).padStart(2, '0'),
  label: String(value).padStart(2, '0'),
}));

export interface FilterTimeInputProps {
  value?: string;
  onValueChange: (value: string) => void;
  label: string;
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
  inline?: boolean;
}

export function FilterTimeInput({
  value = '',
  onValueChange,
  label,
  disabled = false,
  invalid,
  errorId,
  inline = false,
}: FilterTimeInputProps) {
  const time = timeToSeconds(value);
  const parts = time === '' ? ['00', '00'] : time.split(':');
  const second = (parts[2] ?? '00').split('.')[0];
  function changePart(index: number, next: string) {
    const result = [...parts];
    result[index] = next;
    onValueChange(result.join(':'));
  }
  const controls = (
    <>
      <InputGroupInput
        value={time}
        onChange={event => onValueChange(timeToSeconds(event.target.value))}
        aria-label={label}
        aria-invalid={invalid || (time !== '' && !timePattern.test(time))}
        aria-describedby={invalid ? errorId : undefined}
        placeholder="HH:mm:ss"
        disabled={disabled}
        className="fve:w-24 fve:tabular-nums"
      />
      <InputGroupAddon align="inline-end">
        <PopoverTrigger
          aria-label={`${label}选择时间`}
          disabled={disabled}
          render={<InputGroupButton size="icon-xs" />}
        >
          <ClockIcon aria-hidden="true" />
        </PopoverTrigger>
      </InputGroupAddon>
    </>
  );
  return (
    <Popover>
      {inline ? (
        <span className="fve:inline-flex fve:items-center">{controls}</span>
      ) : (
        <InputGroup className="fve:w-auto">{controls}</InputGroup>
      )}
      <PopoverContent align="start" className="fve:w-auto">
        <PopoverTitle>{label}</PopoverTitle>
        <InputGroup aria-label="24 小时制" className="fve:w-auto">
          <FilterSelect
            label={`${label}小时`}
            value={
              hours.some(option => option.value === parts[0]) ? parts[0] : null
            }
            options={hours}
            onValueChange={next => changePart(0, next)}
            inline
            disabled={disabled}
          />
          <InputGroupText>:</InputGroupText>
          <FilterSelect
            label={`${label}分钟`}
            value={
              minutes.some(option => option.value === parts[1])
                ? parts[1]
                : null
            }
            options={minutes}
            onValueChange={next => changePart(1, next)}
            inline
            disabled={disabled}
          />
          <InputGroupText>:</InputGroupText>
          <FilterSelect
            label={`${label}秒`}
            value={
              minutes.some(option => option.value === second) ? second : null
            }
            options={minutes}
            onValueChange={next => changePart(2, next)}
            inline
            disabled={disabled}
          />
        </InputGroup>
      </PopoverContent>
    </Popover>
  );
}
