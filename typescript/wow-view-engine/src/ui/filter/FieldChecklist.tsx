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
import { PlusIcon, SearchIcon } from 'lucide-react';
import type { FieldDefinition } from '../../model/index.js';
import {
  conditions,
  fieldGroups,
  nodeAt,
  type FilterPath,
} from '../../filter/index.js';
import type { FilterTreeController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { Checkbox } from '../components/checkbox.js';
import { Empty, EmptyDescription, EmptyHeader } from '../components/empty.js';
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from '../components/field.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from '../components/input-group.js';
import {
  Popover,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from '../components/popover.js';
import { useViewMessages } from '../MessagesProvider.js';
import { PopoverContent } from '../popups.js';

/**
 * The fields of a group, as a grid of checkboxes to tick rather than a menu
 * to pick from.
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
 * **Every field wears a visible checkbox**, and the grid runs in two columns
 * wherever the popup is wide enough. D16 had replaced that grid with a Base
 * UI `Combobox multiple` for the list keyboard model it brought for free;
 * measured on a definition with 21 fields, what it cost was worse than what
 * it bought — one column showed 7 of them at a time, and the unticked ones
 * carried no mark at all next to the ticked ones' ✓, so the list read as a
 * menu of single picks. The user ruled on 2026-09-21 that the grid comes
 * back, and D16 records the exception. What this round kept from the
 * combobox is the part that earned its place on a wide definition: the
 * search line at the top (it narrows every group at once and drops the ones
 * left empty), the grouping, and the empty sentence.
 *
 * Each field is the registry's `Checkbox` inside a `Field` — one tab stop,
 * Space to toggle, its own label — and each catalogue group is a `FieldSet`
 * under its `FieldLegend`, which is the registry's own part for a set of
 * related checkboxes. The catalogue is laid out exactly as every other field
 * picker lays it out — ungrouped fields first and without a heading, then
 * each declared group — so a field is found in the same place whether it is
 * being added as a condition, a column or a grouping.
 */
export function FieldChecklist({
  filter,
  parent,
  disabled,
  label,
  name,
}: {
  filter: FilterTreeController;
  parent: FilterPath;
  disabled?: boolean;
  /** The button's text, and its accessible name unless `name` says more. */
  label: string;
  /**
   * The accessible name, where the text alone would not tell two of these
   * apart: a metric's condition block and the range both say 「添加条件」,
   * and the name says whose (「金额的总和 添加条件」). It keeps the text in
   * it, so what a reader hears is what a sighted user reads, plus whose.
   */
  name?: string;
}) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const search = useRef<HTMLInputElement>(null);
  const ids = useId();

  // Everything the group could hold, in catalogue order: what may still be
  // added, plus what is already there. `fieldsFor` answers only the first
  // half, and a list that dropped the second could never untick anything.
  // A negated condition is one of the group's conditions like any other,
  // and the kernel hands over the path that takes the whole of it out —
  // wrapper and all — so this list never asks how one is stored.
  const held = new Map(
    conditions(nodeAt(filter.tree, parent), parent).map(
      condition => [condition.leaf.field, condition.path] as const,
    ),
  );
  const addable = new Set(filter.fieldsFor(parent).map(field => field.name));
  const candidates = filter.fields.filter(
    field => addable.has(field.name) || held.has(field.name),
  );
  // The search narrows the fields, never the catalogue: a group with nothing
  // left to show is dropped rather than left as a heading over a gap.
  const needle = query.trim().toLocaleLowerCase();
  const listed =
    needle === ''
      ? candidates
      : candidates.filter(field =>
          field.label.toLocaleLowerCase().includes(needle),
        );
  const sections = fieldGroups(listed, filter.fieldGroups, field => field.name);

  // One box changes one field, and the tree is the source of truth: the grid
  // re-reads it on the next render rather than keeping a selection of its own.
  const toggle = (field: FieldDefinition, ticked: boolean) => {
    if (ticked) {
      filter.addLeaf(field.name, parent);
      return;
    }
    const at = held.get(field.name);
    if (at) filter.remove(at);
  };

  const grid = (fields: readonly FieldDefinition[]) => (
    // Two columns where there is room for two, one where there is not, and
    // the question is asked of the popup rather than of the window: this
    // list is the same width in a 420px browser as in a narrow host, and it
    // is the popup that knows which it is. `gap-2` is one class rather than
    // `gap-x`/`gap-y`, so that it replaces the registry's own `gap-5`
    // outright instead of racing it in the stylesheet.
    <FieldGroup className="grid grid-cols-1 gap-2 @md/checklist:grid-cols-2">
      {fields.map(field => (
        <Field key={field.name} orientation="horizontal" className="min-w-0">
          <Checkbox
            id={`${ids}-${field.name}`}
            disabled={disabled}
            checked={held.has(field.name)}
            onCheckedChange={ticked => toggle(field, ticked === true)}
          />
          {/* `font-normal` is the registry's own composition for a checkbox
              field (shadcn `FieldSet` example), and it is load-bearing here:
              the legends above are `font-medium`, and a list whose items
              weigh the same as the headings has no headings. */}
          <FieldLabel
            htmlFor={`${ids}-${field.name}`}
            className="min-w-0 font-normal"
          >
            <span className="truncate">{field.label}</span>
          </FieldLabel>
        </Field>
      ))}
    </FieldGroup>
  );

  return (
    <Popover
      open={open}
      onOpenChange={next => {
        setOpen(next);
        // What was typed belongs to the visit, not to the view: a picker
        // opened again starts on the whole catalogue.
        if (!next) setQuery('');
      }}
    >
      {/* The trigger is the "add" button the tray already had — an icon, a
          word, no chevron — so it is the primitive's trigger rendering a
          plain `Button` rather than a select-like field. */}
      <PopoverTrigger
        disabled={disabled}
        render={<Button variant="outline" size="sm" disabled={disabled} />}
        aria-label={name}
      >
        <PlusIcon data-icon="inline-start" />
        {label}
      </PopoverTrigger>
      <PopoverContent
        align="start"
        // The popup's own scroll port is handed to the grid below instead:
        // the title and the search line stay put while the fields go past
        // them, which is the whole point of having a search line.
        className="@container/checklist w-(--available-width) max-w-140 overflow-y-hidden"
        initialFocus={search}
      >
        <PopoverHeader className="flex-row items-center gap-2">
          <PopoverTitle className="flex-1 truncate">
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
        </PopoverHeader>

        {/* The same control, the same word and the same empty sentence as
            the column settings' list, so looking for a field is one skill
            rather than two. */}
        <InputGroup>
          <InputGroupAddon>
            <SearchIcon />
          </InputGroupAddon>
          <InputGroupInput
            ref={search}
            data-slot="field-checklist-search"
            type="text"
            value={query}
            placeholder={messages.label('label.field.search')}
            aria-label={messages.label('label.field.search')}
            onChange={event => setQuery(event.target.value)}
          />
        </InputGroup>

        {/* The scroll port sits 10px wider and 8px taller than its content
            and pads that back, so that the clipping edge is the popup's own
            padding edge rather than a line drawn through the rows. The
            vertical half is not cosmetic: the registry gives each checkbox a
            hit area 8px taller than its box, and a port fitted to the rows
            overflows by those 8px — a 15px scrollbar on a list of eight
            fields that fit. */}
        <div
          data-slot="field-checklist"
          className="-mx-2.5 -my-2 flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-2.5 py-2"
        >
          {sections.length === 0 ? (
            <Empty data-slot="field-checklist-none" className="p-0">
              <EmptyHeader>
                <EmptyDescription>
                  {messages.label('label.field.none')}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            sections.map(section =>
              // The fields in no group lead, with no heading over them:
              // a `FieldSet` here would be a named set that has no name.
              section.group === undefined ? (
                <div key="" data-slot="field-checklist-ungrouped">
                  {grid(section.items)}
                </div>
              ) : (
                <FieldSet key={section.group.id}>
                  <FieldLegend variant="label">
                    {section.group.label}
                  </FieldLegend>
                  {grid(section.items)}
                </FieldSet>
              ),
            )
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
