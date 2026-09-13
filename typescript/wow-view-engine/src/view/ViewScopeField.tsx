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

import { useId } from 'react';
import { RadioGroup, RadioGroupItem } from '../components/ui/radio-group.js';
import { cn } from '../lib/utils.js';

export function ViewScopeField({
  value,
  onValueChange,
  personal,
  shared,
  disabled = false,
}: {
  value: string | null;
  onValueChange(value: string): void;
  personal: boolean;
  shared: boolean;
  disabled?: boolean;
}) {
  const id = useId();
  const choices = [
    {
      value: 'personal',
      label: '个人视图',
      description: '仅自己可见，适合保存个人常用配置。',
      disabled: !personal,
    },
    {
      value: 'shared',
      label: '公共视图',
      description: '对有访问权限的用户可见，适合团队共享。',
      disabled: !shared,
    },
  ];
  return (
    <fieldset className="fve:m-0 fve:min-w-0 fve:border-0 fve:p-0 fve:text-sm">
      <legend id={id} className="fve:mb-3 fve:p-0 fve:font-medium">
        可见范围
      </legend>
      <RadioGroup
        aria-labelledby={id}
        value={value}
        onValueChange={value => {
          if (value) onValueChange(value);
        }}
        disabled={disabled}
        className="fve:gap-4"
      >
        {choices.map(choice => (
          <label
            key={choice.value}
            htmlFor={`${id}-${choice.value}`}
            className={cn(
              'fve:flex fve:cursor-pointer fve:items-start fve:gap-3',
              (disabled || choice.disabled) &&
                'fve:cursor-not-allowed fve:opacity-50',
            )}
          >
            <RadioGroupItem
              id={`${id}-${choice.value}`}
              value={choice.value}
              aria-labelledby={`${id}-${choice.value}-label`}
              aria-describedby={`${id}-${choice.value}-description`}
              disabled={choice.disabled}
              className="fve:mt-0.5"
            />
            <span className="fve:grid fve:gap-1">
              <span
                id={`${id}-${choice.value}-label`}
                className="fve:font-medium"
              >
                {choice.label}
              </span>
              <span
                id={`${id}-${choice.value}-description`}
                className="fve:text-muted-foreground"
              >
                {choice.description}
                {choice.disabled && '（无创建权限）'}
              </span>
            </span>
          </label>
        ))}
      </RadioGroup>
    </fieldset>
  );
}
