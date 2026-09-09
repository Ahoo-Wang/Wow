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
import { Combobox } from '@base-ui/react/combobox';
import { CheckIcon, ChevronDownIcon, XIcon } from 'lucide-react';
import { Button } from '../components/ui/button.js';
import {
  InputGroup,
  InputGroupButton,
  InputGroupInput,
} from '../components/ui/input-group.js';
import { usePortalTheme } from '../lib/usePortalTheme.js';
import type { FilterOption } from './filterTypes.js';
export interface FilterChoiceSelectProps<V extends string | number> {
  options: readonly FilterOption<V>[];
  selectedOptions?: readonly FilterOption<V>[];
  values: readonly V[];
  onValuesChange(values: V[], selected: FilterOption<V>[]): void;
  label: string;
  multiple?: boolean;
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
  inline?: boolean;
  placeholder?: string;
  searchPlaceholder?: string;
  emptyText?: string;
  onOpenChange?(open: boolean): void;
  search?: {
    value: string;
    onChange(value: string): void;
    onCompositionStart(): void;
    onCompositionEnd(value: string): void;
  };
  footer?: ReactNode;
}
export function FilterChoiceSelect<V extends string | number>({
  options,
  selectedOptions = [],
  values,
  onValuesChange,
  label,
  multiple = false,
  disabled = false,
  invalid,
  errorId,
  inline = false,
  placeholder = '未设置',
  searchPlaceholder = '搜索选项…',
  emptyText = '没有匹配选项',
  onOpenChange,
  search,
  footer,
}: FilterChoiceSelectProps<V>) {
  const { scope, theme, captureTheme } = usePortalTheme();
  const [localSearch, setLocalSearch] = useState('');
  const keyword = search?.value ?? localSearch;
  const selected = [...new Set(values)].map(
    value =>
      selectedOptions.find(item => item.value === value) ??
      options.find(item => item.value === value) ?? {
        value,
        label: String(value),
      },
  );
  const visible = [
    ...new Map(
      options
        .filter(
          item =>
            search ||
            item.label
              .toLocaleLowerCase()
              .includes(keyword.toLocaleLowerCase()),
        )
        .map(item => [item.value, item]),
    ).values(),
  ];
  const groups = new Map<string, FilterOption<V>[]>();
  for (const item of visible) {
    const name = item.group ?? '';
    const group = groups.get(name);
    if (group) group.push(item);
    else groups.set(name, [item]);
  }
  return (
    <span
      ref={scope}
      className="fve-root fve:inline-flex fve:max-w-full"
      onKeyDown={event => {
        if (event.key === 'Enter') event.stopPropagation();
      }}
    >
      <Combobox.Root<FilterOption<V>, boolean>
        multiple={multiple}
        items={[...groups.values()].flat()}
        filter={null}
        value={multiple ? selected : (selected[0] ?? null)}
        inputValue={keyword}
        onInputValueChange={(value, details) => {
          if (search) {
            if (details.reason === 'input-change') search.onChange(value);
          } else setLocalSearch(value);
        }}
        disabled={disabled}
        isItemEqualToValue={(a, b) => a.value === b.value}
        onOpenChange={open => {
          captureTheme(open);
          onOpenChange?.(open);
        }}
        onValueChange={next => {
          if (disabled) return;
          const items =
            next === null ? [] : Array.isArray(next) ? next : [next];
          onValuesChange(
            items.map(item => item.value),
            items,
          );
        }}
      >
        <Combobox.Trigger
          aria-label={label}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
          title={selected.map(item => item.label).join('、')}
          render={
            inline ? (
              <InputGroupButton size="sm" />
            ) : (
              <Button variant="outline" />
            )
          }
        >
          <span className="fve:max-w-48 fve:truncate">
            {!selected.length
              ? placeholder
              : selected.length === 1
                ? selected[0].label
                : `${selected[0].label} +${selected.length - 1}`}
          </span>
          <ChevronDownIcon aria-hidden="true" data-icon="inline-end" />
        </Combobox.Trigger>
        <Combobox.Portal className="fve-root" {...theme}>
          <Combobox.Positioner
            align="start"
            sideOffset={4}
            className="fve-root fve:isolate fve:z-50"
          >
            <Combobox.Popup
              aria-label={`${label}候选`}
              className="fve:flex fve:max-h-(--available-height) fve:w-72 fve:max-w-(--available-width) fve:flex-col fve:overflow-hidden fve:rounded-lg fve:bg-popover fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10"
            >
              <div className="fve:p-1">
                <InputGroup>
                  <Combobox.Input
                    aria-label={`${label}搜索`}
                    placeholder={searchPlaceholder}
                    render={<InputGroupInput />}
                    disabled={disabled}
                    onCompositionStart={() => search?.onCompositionStart()}
                    onCompositionEnd={event =>
                      search?.onCompositionEnd(event.currentTarget.value)
                    }
                  />
                </InputGroup>
              </div>
              {selected.length > 0 && (
                <details className="fve:px-2 fve:text-sm">
                  <summary>已选 {selected.length} 项</summary>
                  <ul className="fve:max-h-32 fve:overflow-auto">
                    {selected.map(item => (
                      <li
                        key={typeof item.value + ':' + item.value}
                        className="fve:flex fve:items-center fve:justify-between fve:gap-2"
                      >
                        <span className="fve:truncate">{item.label}</span>
                        <Button
                          size="icon-xs"
                          variant="ghost"
                          aria-label={`移除${item.label}`}
                          disabled={disabled}
                          onClick={() => {
                            const next = selected.filter(
                              other => other.value !== item.value,
                            );
                            onValuesChange(
                              next.map(other => other.value),
                              next,
                            );
                          }}
                        >
                          <XIcon aria-hidden="true" />
                        </Button>
                      </li>
                    ))}
                  </ul>
                </details>
              )}
              <Combobox.List
                aria-label={`${label}选项`}
                className="fve:min-h-0 fve:max-h-60 fve:overflow-y-auto fve:p-1"
              >
                {[...groups].map(([group, items]) => (
                  <Combobox.Group key={group}>
                    {group && (
                      <Combobox.GroupLabel className="fve:px-2 fve:py-1 fve:text-xs fve:text-muted-foreground">
                        {group}
                      </Combobox.GroupLabel>
                    )}
                    {items.map(item => (
                      <Combobox.Item
                        key={typeof item.value + ':' + item.value}
                        value={item}
                        disabled={disabled || item.disabled}
                        className="fve:flex fve:items-center fve:gap-2 fve:rounded-md fve:px-2 fve:py-1.5 fve:text-sm fve:outline-none fve:data-highlighted:bg-accent fve:data-disabled:opacity-50"
                      >
                        <span
                          aria-hidden="true"
                          className={
                            multiple
                              ? 'fve:flex fve:size-4 fve:shrink-0 fve:items-center fve:justify-center fve:rounded-sm fve:border fve:border-muted-foreground'
                              : 'fve:flex fve:size-4 fve:shrink-0'
                          }
                        >
                          <Combobox.ItemIndicator>
                            <CheckIcon
                              aria-hidden="true"
                              className="fve:size-4"
                            />
                          </Combobox.ItemIndicator>
                        </span>
                        <span className="fve:truncate">{item.label}</span>
                      </Combobox.Item>
                    ))}
                  </Combobox.Group>
                ))}
              </Combobox.List>
              {!visible.length && (
                <p className="fve:p-2 fve:text-sm fve:text-muted-foreground">
                  {emptyText}
                </p>
              )}
              {footer}
            </Combobox.Popup>
          </Combobox.Positioner>
        </Combobox.Portal>
      </Combobox.Root>
    </span>
  );
}
