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

import { useId, useRef, useState } from 'react';
import {
  CableIcon,
  CalendarIcon,
  ChevronDownIcon,
  FilterIcon,
  HashIcon,
  KeyRoundIcon,
  SearchIcon,
  Settings2Icon,
  ToggleLeftIcon,
  TypeIcon,
  Trash2Icon,
  type LucideIcon,
} from 'lucide-react';
import {
  filterControlValue,
  filterEditor,
  filterStoredValue,
} from '../../dashboard/index.js';
import { writeValue } from '../../filter/index.js';
import {
  DASHBOARD_FILTER_TYPES,
  filterTypeOf,
  type DashboardField,
  type DashboardFilterType,
  type FieldOption,
} from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import { CompactSelect } from '../analysis/CompactSelect.js';
import { CheckField, ChoiceField } from '../analysis/optionControls.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import {
  Field,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from '../components/field.js';
import { Input } from '../components/input.js';
import {
  Popover,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from '../components/popover.js';
import { ValueChips } from '../filter/inputs/chips.js';
import { FilterValueEditor } from '../FilterValueEditor.js';
import { IconTooltip } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent, PopoverContent } from '../popups.js';
import { ControlFrame } from '../variants.js';
import { searchPlaceholder } from './FilterBar.js';
import { boardOf, focusIn } from './landing.js';

const TYPE_ICONS: Record<DashboardFilterType, LucideIcon> = {
  date: CalendarIcon,
  text: TypeIcon,
  id: KeyRoundIcon,
  number: HashIcon,
  boolean: ToggleLeftIcon,
  search: SearchIcon,
};

/**
 * 「添加筛选」 on the edit bar (D22 G): a new filter of one of the six
 * types, named after its type until renamed, its settings opened at once;
 * and the board's time grouping, while it has none.
 */
export function AddFilterMenu({
  dashboard,
  onAdded,
}: {
  dashboard: DashboardController;
  /** The new filter's name: its settings open on it. */
  onAdded(name: string): void;
}) {
  const messages = useViewMessages();
  const edit = dashboard.edit;
  const trigger = useRef<HTMLButtonElement>(null);
  // Whether the time grouping was the pick: a new filter's settings open
  // and take the keyboard, but the grouping opens nothing — the keyboard
  // goes to the 「按日｜周｜月」 it put on the bar (U-02).
  const grouped = useRef(false);
  if (!edit) return null;
  return (
    <DropdownMenu
      onOpenChange={open => {
        if (open) grouped.current = false;
      }}
    >
      <DropdownMenuTrigger
        ref={trigger}
        render={
          <Button
            data-slot="dashboard-add-filter"
            variant="outline"
            size="sm"
          />
        }
      >
        <FilterIcon data-icon="inline-start" />
        {messages.label('label.filters.add')}
        <ChevronDownIcon data-icon="inline-end" />
      </DropdownMenuTrigger>
      <DropdownMenuContent
        className="min-w-44"
        finalFocus={() =>
          grouped.current &&
          (focusIn(
            boardOf(trigger.current)?.querySelector(
              '[data-slot="dashboard-grouping"]',
            ),
          ) ??
            false)
        }
      >
        <DropdownMenuGroup>
          {DASHBOARD_FILTER_TYPES.map(type => {
            const Icon = TYPE_ICONS[type];
            const label = messages.label(`label.filters.type.${type}`);
            return (
              <DropdownMenuItem
                key={type}
                data-slot={`add-filter-${type}`}
                onClick={() => {
                  const name = edit.addFilter({ type, label });
                  if (name !== null) onAdded(name);
                }}
              >
                <Icon />
                {label}
              </DropdownMenuItem>
            );
          })}
        </DropdownMenuGroup>
        {dashboard.timeGrouping === null && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem
                data-slot="add-grouping"
                onClick={() => {
                  grouped.current = true;
                  edit.setTimeGrouping({
                    units: ['DAY', 'WEEK', 'MONTH'],
                    default: 'DAY',
                  });
                }}
              >
                <CalendarIcon />
                {messages.label('label.filters.add-grouping')}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export interface FilterSettingsProps {
  field: DashboardField;
  dashboard: DashboardController;
  open: boolean;
  onOpenChange(open: boolean): void;
  /** 「接线」: the board goes into wiring this filter. */
  onWire(): void;
  /** Told once the filter is gone, by the control that took it away. */
  onRemoved(label: string, from: HTMLElement): void;
}

/**
 * A filter's settings (D22 G), in a popover off its chip while the board is
 * built: its type, its name, what it starts at (the same control the bar
 * edits it with), several values, required, and where its values come from
 * — the wired fields' own, or a list of its own; then 「接线」 and 「移除」.
 * Every change is an edit of the draft, seen on the board at once and saved
 * with it.
 */
export function FilterSettings({
  field,
  dashboard,
  open,
  onOpenChange,
  onWire,
  onRemoved,
}: FilterSettingsProps) {
  const messages = useViewMessages();
  const edit = dashboard.edit;
  const kinds = dashboard.kinds;
  const ids = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const title = messages.label('label.filters.settings-of', {
    filter: field.label,
  });
  if (!edit || !kinds) return null;
  const type = filterTypeOf(field.kind);
  const listable = type === 'text' || type === 'number';
  const severable = type === 'text' || type === 'id' || type === 'number';
  const needsDefault = field.required === true && field.default === undefined;
  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <IconTooltip
        label={title}
        render={
          <PopoverTrigger
            ref={trigger}
            render={
              <Button
                data-slot="dashboard-filter-settings"
                variant="ghost"
                size="icon-xs"
              />
            }
          />
        }
      >
        <Settings2Icon />
      </IconTooltip>
      <PopoverContent align="start" className="w-80">
        <PopoverHeader>
          <PopoverTitle>{title}</PopoverTitle>
        </PopoverHeader>
        <FieldGroup className="gap-4">
          <Field>
            <FieldLabel htmlFor={`${ids}-type`}>
              {messages.label('label.filters.type')}
            </FieldLabel>
            <CompactSelect
              id={`${ids}-type`}
              items={DASHBOARD_FILTER_TYPES.map(value => ({
                value,
                label: messages.label(`label.filters.type.${value}`),
              }))}
              value={type ?? 'text'}
              onChange={next => edit.retypeFilter(field.name, next)}
            />
          </Field>
          <NameField
            field={field}
            onRename={(name, label) => edit.renameFilter(name, label)}
          />
          <Field data-invalid={needsDefault || undefined}>
            <FieldLabel id={`${ids}-default`}>
              {messages.label('label.filters.default')}
            </FieldLabel>
            <ControlFrame
              role="group"
              aria-labelledby={`${ids}-default`}
              className="px-1 py-0.5"
            >
              <FilterValueEditor
                editor={filterEditor(
                  field,
                  field.default,
                  kinds,
                  dashboard.filterChoices(field.name),
                )}
                kind={field.kind}
                label={`${field.label} ${messages.label('label.filters.default')}`}
                value={filterControlValue(field, field.default)}
                invalid={needsDefault}
                onChange={next =>
                  edit.setFilterDefault(
                    field.name,
                    filterStoredValue(field, next, kinds),
                  )
                }
                options={field.options}
                source={
                  field.remote ? dashboard.filterOptions(field.remote) : null
                }
                candidates={dashboard.filterCandidates(field.name)}
                placeholder={searchPlaceholder(field, messages)}
              />
            </ControlFrame>
            {needsDefault && (
              <FieldError>
                {messages.label('label.filters.required-needs-default')}
              </FieldError>
            )}
          </Field>
          {severable && (
            <CheckField
              data-slot="filter-multiple"
              label={messages.label('label.filters.multiple')}
              checked={field.multiple === true}
              onChange={on => edit.setFilterMultiple(field.name, on)}
            />
          )}
          <CheckField
            data-slot="filter-required"
            label={messages.label('label.filters.required-toggle')}
            hint={messages.label('label.filters.required-hint')}
            checked={field.required === true}
            onChange={on => edit.setFilterRequired(field.name, on)}
          />
          {listable && (
            <SourceField
              field={field}
              numeric={type === 'number'}
              onChange={options => edit.setFilterOptions(field.name, options)}
            />
          )}
          <div className="flex flex-wrap gap-2">
            <Button
              data-slot="dashboard-filter-wire"
              variant="outline"
              size="sm"
              onClick={() => {
                onOpenChange(false);
                onWire();
              }}
            >
              <CableIcon data-icon="inline-start" />
              {messages.label('label.filters.wire')}
            </Button>
            <Button
              data-slot="dashboard-filter-remove"
              variant="ghost"
              size="sm"
              onClick={event => {
                // The settings are portalled: the board is found from the
                // chip's own gear, read before the chip is gone.
                const from = trigger.current ?? event.currentTarget;
                edit.removeFilter(field.name);
                onRemoved(field.label, from);
              }}
            >
              <Trash2Icon data-icon="inline-start" />
              {messages.label('label.filters.remove')}
            </Button>
          </div>
        </FieldGroup>
      </PopoverContent>
    </Popover>
  );
}

/**
 * The name, typed in place. A blank one is not taken, so the box keeps what
 * is typed and the filter takes it once it says something.
 */
function NameField({
  field,
  onRename,
}: {
  field: DashboardField;
  onRename(name: string, label: string): void;
}) {
  const messages = useViewMessages();
  const id = useId();
  const [typed, setTyped] = useState<{ over: string; text: string } | null>(
    null,
  );
  const text = typed?.over === field.label ? typed.text : field.label;
  return (
    <Field>
      <FieldLabel htmlFor={id}>
        {messages.label('label.filters.name')}
      </FieldLabel>
      <Input
        id={id}
        data-slot="dashboard-filter-name"
        value={text}
        onChange={event => {
          const next = event.target.value;
          if (next.trim().length > 0) {
            setTyped({ over: next.trim(), text: next });
            onRename(field.name, next);
          } else setTyped({ over: field.label, text: next });
        }}
      />
    </Field>
  );
}

/**
 * Where a filter's values come from (D22 G「值从哪来」): the fields it is
 * wired to — the values the data holds, counted — or a list of its own,
 * typed here as the condition editor's chips.
 */
function SourceField({
  field,
  numeric,
  onChange,
}: {
  field: DashboardField;
  numeric: boolean;
  onChange(options: FieldOption[] | null): void;
}) {
  const messages = useViewMessages();
  const listed = Array.isArray(field.options);
  const values = (field.options ?? []).map(option => option.value);
  return (
    <>
      <ChoiceField
        data-slot="filter-source"
        label={messages.label('label.filters.source')}
        hint={messages.label(
          listed
            ? 'label.filters.source.list-hint'
            : 'label.filters.source.fields-hint',
        )}
        items={[
          {
            value: 'fields',
            label: messages.label('label.filters.source.fields'),
          },
          { value: 'list', label: messages.label('label.filters.source.list') },
        ]}
        value={listed ? 'list' : 'fields'}
        onChange={source => onChange(source === 'list' ? [] : null)}
      />
      {listed && (
        <FieldSet>
          <FieldLegend variant="label">
            {messages.label('label.filters.list')}
          </FieldLegend>
          <ControlFrame className="px-1 py-0.5">
            <ValueChips<string | number>
              // The list's own identity keys what is being typed into it.
              value={writeValue(field.options ?? null)}
              values={values}
              label={messages.label('label.filters.list')}
              parse={text => {
                const trimmed = text.trim();
                if (!trimmed) return null;
                if (!numeric) return trimmed;
                const number = Number(trimmed);
                return Number.isFinite(number) ? number : null;
              }}
              unparsable={messages.label('label.filter.not-a-number')}
              inputMode={numeric ? 'decimal' : 'text'}
              onChange={next =>
                onChange(next.map(value => ({ value, label: String(value) })))
              }
            />
          </ControlFrame>
        </FieldSet>
      )}
    </>
  );
}
