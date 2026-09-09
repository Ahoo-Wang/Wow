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

import { useState, type ReactNode } from 'react';
import { PlusIcon } from 'lucide-react';
import { Button } from '../components/ui/button.js';
import { ButtonGroup } from '../components/ui/button-group.js';
import { Checkbox } from '../components/ui/checkbox.js';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../components/ui/popover.js';

export interface FieldChoice {
  value: string;
  label: string;
  group: string;
  /** Direct conditions for this field; undefined for root-level actions. */
  count?: number;
  repeatable?: boolean;
}

export function FilterFieldPicker({
  label,
  options,
  disabled,
  onAdd,
  onRemove,
  children,
}: {
  label: string;
  options: readonly FieldChoice[];
  disabled: boolean;
  onAdd(value: string): void;
  onRemove(value: string): void;
  children?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const groups = new Map<string, FieldChoice[]>();
  for (const option of options) {
    const items = groups.get(option.group) ?? [];
    items.push(option);
    groups.set(option.group, items);
  }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <ButtonGroup aria-label={label}>
        <PopoverTrigger
          aria-label={label}
          disabled={disabled}
          render={<Button type="button" variant="outline" size="sm" />}
        >
          <PlusIcon aria-hidden="true" />
          添加筛选
        </PopoverTrigger>
        {children}
      </ButtonGroup>
      <PopoverContent
        keepMounted
        align="start"
        className="fve:w-[min(28rem,calc(100vw-2rem))] fve:max-h-[min(28rem,var(--available-height))] fve:gap-0 fve:overflow-hidden fve:p-0"
      >
        <div className="fve:flex fve:shrink-0 fve:items-center fve:justify-between fve:gap-2 fve:border-b fve:px-3 fve:py-2">
          <PopoverTitle>选择筛选字段</PopoverTitle>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => setOpen(false)}
          >
            完成
          </Button>
        </div>
        <div
          data-slot="field-picker-options"
          className="fve:flex fve:min-h-0 fve:flex-col fve:gap-3 fve:overflow-y-auto fve:overscroll-contain fve:p-3"
        >
          {[...groups].map(([group, items]) => (
            <fieldset
              key={group}
              className="fve:m-0 fve:min-w-0 fve:border-0 fve:p-0"
            >
              {(group || groups.size > 1) && (
                <legend className="fve:mb-2 fve:p-0 fve:text-xs fve:text-muted-foreground">
                  {group || '其他字段'}
                </legend>
              )}
              <div className="fve:grid fve:grid-cols-[repeat(auto-fill,minmax(min(100%,10rem),1fr))] fve:gap-2">
                {items.map(option =>
                  option.count !== undefined ? (
                    <div
                      key={option.value}
                      className="fve:flex fve:min-w-0 fve:items-center fve:gap-1"
                    >
                      <label className="fve:flex fve:min-w-0 fve:flex-1 fve:cursor-pointer fve:items-center fve:gap-2 fve:rounded-md fve:p-1.5 fve:hover:bg-accent">
                        <Checkbox
                          checked={option.count > 0}
                          aria-description={
                            option.repeatable
                              ? `已添加 ${option.count} 条条件`
                              : undefined
                          }
                          disabled={disabled}
                          onCheckedChange={checked => {
                            if (disabled) return;
                            if (checked) onAdd(option.value);
                            else onRemove(option.value);
                          }}
                        />
                        <span className="fve:truncate">{option.label}</span>
                      </label>
                      {option.count > 0 && option.repeatable && (
                        <>
                          <span className="fve:shrink-0 fve:text-xs fve:text-muted-foreground">
                            {option.count} 条
                          </span>
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon-xs"
                            aria-label={`追加${option.label}条件`}
                            title="追加条件"
                            disabled={disabled}
                            onClick={() => onAdd(option.value)}
                          >
                            <PlusIcon aria-hidden="true" />
                          </Button>
                        </>
                      )}
                    </div>
                  ) : (
                    <Button
                      key={option.value}
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={disabled}
                      className="fve:justify-start"
                      onClick={() => {
                        if (!disabled) onAdd(option.value);
                      }}
                    >
                      <PlusIcon aria-hidden="true" />
                      <span className="fve:truncate">{option.label}</span>
                    </Button>
                  ),
                )}
              </div>
            </fieldset>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}
