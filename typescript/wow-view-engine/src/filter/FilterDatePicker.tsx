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
import { CalendarIcon } from 'lucide-react';
import { zhCN } from 'react-day-picker/locale';
import { Calendar } from '../components/ui/calendar.js';
import { Button } from '../components/ui/button.js';
import { InputGroupButton } from '../components/ui/input-group.js';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../components/ui/popover.js';

export interface FilterDatePickerProps {
  value?: Date;
  onValueChange: (date: Date | undefined) => void;
  label: string;
  inline?: boolean;
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
}

export function FilterDatePicker({
  value,
  onValueChange,
  label,
  inline = false,
  disabled = false,
  invalid,
  errorId,
}: FilterDatePickerProps) {
  const [open, setOpen] = useOverlayOpen();
  const selected =
    value && Number.isFinite(value.getTime()) ? value : undefined;
  const display = selected
    ? [
        selected.getFullYear(),
        String(selected.getMonth() + 1).padStart(2, '0'),
        String(selected.getDate()).padStart(2, '0'),
      ].join('-')
    : value
      ? '无效日期'
      : '选择日期';
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={
          inline ? <InputGroupButton size="sm" /> : <Button variant="outline" />
        }
        aria-label={`${label}：${display}`}
        aria-invalid={invalid || (!!value && !selected)}
        aria-describedby={invalid ? errorId : undefined}
        disabled={disabled}
      >
        <CalendarIcon data-icon="inline-start" aria-hidden="true" />
        {display}
      </PopoverTrigger>
      <PopoverContent align="start" className="fve:w-auto fve:p-0">
        <PopoverTitle className="fve:sr-only">{label}</PopoverTitle>
        <Calendar
          locale={zhCN}
          mode="single"
          selected={selected}
          defaultMonth={selected}
          disabled={disabled}
          onSelect={date => {
            onValueChange(date);
            setOpen(false);
          }}
        />
      </PopoverContent>
    </Popover>
  );
}
