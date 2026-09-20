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

import { useId, useState } from 'react';
import { PlusIcon } from 'lucide-react';
import type { FieldDefinition } from '../../model/index.js';
import {
  fieldGroups,
  isFilterGroup,
  isFilterLeaf,
  nodeAt,
  type FilterPath,
} from '../../filter/index.js';
import type { FilterTreeController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { Checkbox } from '../components/checkbox.js';
import { Input } from '../components/input.js';
import { Label } from '../components/label.js';
import {
  Popover,
  PopoverTitle,
  PopoverTrigger,
} from '../components/popover.js';
import { useViewMessages } from '../MessagesProvider.js';
import { PopoverContent } from '../popups.js';

/**
 * The fields of a group, as a list to tick rather than a menu to pick from.
 *
 * Building a filter is choosing several fields, and a menu that closed on
 * each one made that four round trips for four conditions. The list stays
 * open instead: a tick adds the condition, and clearing a tick takes it away
 * again — which the design's one rule about groups makes unambiguous, since
 * a group holds at most one condition per field and there is never a second
 * one to wonder about. A field that already holds a value is no exception:
 * the tick is the condition's existence, and a user who unticks one is
 * asking for it to go.
 *
 * The catalogue is laid out exactly as every other field picker lays it out
 * — ungrouped fields first and without a heading, then each declared group —
 * so a field is found in the same place whether it is being added as a
 * condition, a column or a grouping. Two columns, because a checkbox and a
 * label leave half a row empty and a long catalogue then scrolls twice as
 * far as it needs to.
 */
export function FieldChecklist({
  filter,
  parent,
  disabled,
  label,
}: {
  filter: FilterTreeController;
  parent: FilterPath;
  disabled?: boolean;
  /** The button's text, which is also its accessible name. */
  label: string;
}) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');

  // Everything the group could hold, in catalogue order: what may still be
  // added, plus what is already there. `fieldsFor` answers only the first
  // half, and a list that dropped the second could never untick anything.
  const group = nodeAt(filter.tree, parent);
  const held = new Map(
    isFilterGroup(group)
      ? group.children.flatMap((child, index) =>
          isFilterLeaf(child) ? [[child.field, index] as const] : [],
        )
      : [],
  );
  const addable = new Set(filter.fieldsFor(parent).map(field => field.name));
  const candidates = filter.fields.filter(
    field => addable.has(field.name) || held.has(field.name),
  );
  const needle = query.trim().toLocaleLowerCase();
  const matching = needle
    ? candidates.filter(field =>
        field.label.toLocaleLowerCase().includes(needle),
      )
    : candidates;

  const toggle = (field: FieldDefinition, checked: boolean) => {
    const at = held.get(field.name);
    if (checked) {
      if (at === undefined) filter.addLeaf(field.name, parent);
      return;
    }
    if (at !== undefined) filter.remove([...parent, at]);
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        aria-label={label}
        render={<Button variant="outline" size="sm" disabled={disabled} />}
      >
        <PlusIcon data-icon="inline-start" />
        {label}
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="flex w-(--available-width) max-w-100 flex-col gap-2 p-3"
      >
        <div className="flex items-center gap-2">
          <PopoverTitle className="flex-1 truncate text-sm font-medium">
            {messages.label('label.filter.pick-fields')}
          </PopoverTitle>
          {/* The way out is a button rather than only the Escape key: the
              list stays open on purpose, so it has to say when it is done.
              Base UI puts focus back on the trigger either way. */}
          <Button
            data-slot="field-checklist-done"
            variant="ghost"
            size="sm"
            onClick={() => setOpen(false)}
          >
            {messages.label('label.filter.pick-done')}
          </Button>
        </div>

        <Input
          value={query}
          onChange={event => setQuery(event.target.value)}
          placeholder={messages.label('label.field.search')}
          aria-label={messages.label('label.field.search')}
        />

        {matching.length === 0 ? (
          <p className="text-muted-foreground py-2 text-sm">
            {messages.label('label.field.none')}
          </p>
        ) : (
          <div
            data-slot="field-checklist"
            className="flex max-h-72 flex-col gap-2 overflow-y-auto"
          >
            {fieldGroups(matching, filter.fieldGroups, field => field.name).map(
              entry => (
                <FieldColumns
                  key={entry.group?.id ?? ''}
                  title={entry.group?.label}
                  fields={entry.items}
                  held={held}
                  disabled={disabled}
                  onToggle={toggle}
                />
              ),
            )}
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}

/** One section of the catalogue: its heading, and its fields in two columns. */
function FieldColumns({
  title,
  fields,
  held,
  disabled,
  onToggle,
}: {
  title: string | undefined;
  fields: readonly FieldDefinition[];
  held: ReadonlyMap<string, number>;
  disabled?: boolean;
  onToggle(field: FieldDefinition, checked: boolean): void;
}) {
  const labelId = useId();
  return (
    <div role="group" aria-labelledby={title ? labelId : undefined}>
      {title !== undefined && (
        <span id={labelId} className="text-muted-foreground px-1 text-xs">
          {title}
        </span>
      )}
      <div className="grid grid-cols-2 gap-x-2 gap-y-1">
        {fields.map(field => (
          <FieldCheck
            key={field.name}
            field={field}
            checked={held.has(field.name)}
            disabled={disabled}
            onToggle={onToggle}
          />
        ))}
      </div>
    </div>
  );
}

function FieldCheck({
  field,
  checked,
  disabled,
  onToggle,
}: {
  field: FieldDefinition;
  checked: boolean;
  disabled?: boolean;
  onToggle(field: FieldDefinition, checked: boolean): void;
}) {
  const id = useId();
  return (
    <Label htmlFor={id} className="min-w-0 py-1 font-normal">
      <Checkbox
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={next => onToggle(field, next === true)}
      />
      <span className="truncate">{field.label}</span>
    </Label>
  );
}
