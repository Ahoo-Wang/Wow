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

import { Select as SelectPrimitive } from '@base-ui/react/select';
import { ChevronDownIcon } from 'lucide-react';
import type { FilterOption } from './filterTypes.js';
import { InputGroupButton } from '../components/ui/input-group.js';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/select.js';

export interface FilterSelectProps<Value extends string = string> {
  options: readonly FilterOption<Value>[];
  value?: Value | null;
  onValueChange: (value: Value) => void;
  onClear?: () => void;
  label: string;
  placeholder?: string;
  inline?: boolean;
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
}

export function FilterSelect<Value extends string>({
  options,
  value = null,
  onValueChange,
  onClear,
  label,
  placeholder,
  inline = false,
  disabled = false,
  invalid,
  errorId,
}: FilterSelectProps<Value>) {
  const content = <SelectValue placeholder={placeholder} />;

  return (
    <Select<Value | null>
      items={
        onClear
          ? [{ value: null, label: placeholder ?? '未设置' }, ...options]
          : [...options]
      }
      value={value}
      disabled={disabled}
      onValueChange={next => {
        if (next === null) onClear?.();
        else onValueChange(next);
      }}
    >
      {inline ? (
        <SelectPrimitive.Trigger
          aria-label={label}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
          data-slot="input-group-control"
          render={<InputGroupButton size="sm" />}
        >
          {content}
          <SelectPrimitive.Icon>
            <ChevronDownIcon aria-hidden="true" />
          </SelectPrimitive.Icon>
        </SelectPrimitive.Trigger>
      ) : (
        <SelectTrigger
          aria-label={label}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
        >
          {content}
        </SelectTrigger>
      )}
      <SelectContent alignItemWithTrigger={false} align="start">
        <SelectGroup>
          {onClear && (
            <SelectItem value={null} disabled={disabled || value === null}>
              清空选择
            </SelectItem>
          )}
          {options.map(option => (
            <SelectItem
              key={option.value}
              value={option.value}
              data-value={option.value}
              disabled={option.disabled}
            >
              {option.label}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  );
}
