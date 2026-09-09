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

import { Combobox } from '@base-ui/react/combobox';
import { CheckIcon, ChevronDownIcon } from 'lucide-react';
import type { FilterSelectProps } from './FilterSelect.js';
import type { FilterOption } from './filterTypes.js';
import { usePortalTheme } from '../lib/usePortalTheme.js';
import { Button } from '../components/ui/button.js';
import {
  InputGroup,
  InputGroupButton,
  InputGroupInput,
} from '../components/ui/input-group.js';

export interface FilterSearchSelectProps<
  Value extends string = string,
> extends FilterSelectProps<Value> {
  searchPlaceholder?: string;
  emptyText?: string;
}

export function FilterSearchSelect<Value extends string>({
  options,
  value = null,
  onValueChange,
  onClear,
  label,
  placeholder = '未设置',
  searchPlaceholder = '搜索选项…',
  emptyText = '没有匹配选项',
  inline = false,
  disabled = false,
  invalid,
  errorId,
}: FilterSearchSelectProps<Value>) {
  const { scope, theme, captureTheme } = usePortalTheme();
  const selected =
    value === null
      ? null
      : (options.find(option => option.value === value) ?? {
          value,
          label: value,
        });
  return (
    <span
      ref={scope}
      className="fve-root fve:inline-flex fve:max-w-full fve:items-center"
    >
      <Combobox.Root<FilterOption<Value>>
        items={options}
        value={selected}
        disabled={disabled}
        isItemEqualToValue={(a, b) => a.value === b.value}
        onOpenChange={open => {
          captureTheme(open);
        }}
        onValueChange={next => {
          if (next === null) onClear?.();
          else onValueChange(next.value);
        }}
      >
        <Combobox.Trigger
          aria-label={label}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
          data-slot={inline ? 'input-group-control' : undefined}
          render={
            inline ? (
              <InputGroupButton size="sm" />
            ) : (
              <Button variant="outline" />
            )
          }
        >
          <Combobox.Value placeholder={placeholder} />
          <ChevronDownIcon aria-hidden="true" data-icon="inline-end" />
        </Combobox.Trigger>
        {onClear && (
          <Combobox.Clear
            aria-label={`清空${label}`}
            disabled={disabled}
            render={<InputGroupButton />}
          >
            清空
          </Combobox.Clear>
        )}
        <Combobox.Portal className="fve-root" {...theme}>
          <Combobox.Positioner
            align="start"
            sideOffset={4}
            className="fve-root fve:isolate fve:z-50"
          >
            <Combobox.Popup
              aria-label={`${label}候选`}
              className="fve:group/combobox-content fve:max-h-(--available-height) fve:w-64 fve:max-w-(--available-width) fve:overflow-hidden fve:rounded-lg fve:bg-popover fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10"
            >
              <div className="fve:p-1">
                <InputGroup>
                  <Combobox.Input
                    aria-label={`${label}搜索`}
                    placeholder={searchPlaceholder}
                    disabled={disabled}
                    render={<InputGroupInput />}
                  />
                </InputGroup>
              </div>
              <Combobox.Empty>
                <div className="fve:p-2 fve:text-center fve:text-sm fve:text-muted-foreground">
                  {emptyText}
                </div>
              </Combobox.Empty>
              <Combobox.List
                aria-label={`${label}选项`}
                className="fve:max-h-60 fve:scroll-py-1 fve:overflow-y-auto fve:overscroll-contain fve:p-1 fve:data-empty:p-0"
              >
                {(option: FilterOption<Value>) => (
                  <Combobox.Item
                    key={option.value}
                    value={option}
                    disabled={disabled || option.disabled}
                    className="fve:relative fve:flex fve:w-full fve:cursor-default fve:items-center fve:gap-2 fve:rounded-md fve:py-1 fve:pr-8 fve:pl-1.5 fve:text-sm fve:outline-hidden fve:select-none fve:data-highlighted:bg-accent fve:data-highlighted:text-accent-foreground fve:data-disabled:pointer-events-none fve:data-disabled:opacity-50"
                  >
                    {option.label}
                    <Combobox.ItemIndicator className="fve:pointer-events-none fve:absolute fve:right-2 fve:flex fve:size-4 fve:items-center fve:justify-center">
                      <CheckIcon aria-hidden="true" className="fve:size-4" />
                    </Combobox.ItemIndicator>
                  </Combobox.Item>
                )}
              </Combobox.List>
            </Combobox.Popup>
          </Combobox.Positioner>
        </Combobox.Portal>
      </Combobox.Root>
    </span>
  );
}
