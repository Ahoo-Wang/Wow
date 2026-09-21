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
import { Combobox as ComboboxPrimitive } from '@base-ui/react';
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
import {
  Combobox,
  ComboboxCollection,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxLabel,
  ComboboxList,
} from '../components/combobox.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ComboboxContent } from '../popups.js';

/** One section of the catalogue as the list draws it: a heading and its fields. */
interface FieldSection {
  /** The group's label, or the empty string for the ungrouped fields in front. */
  value: string;
  items: FieldDefinition[];
}

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
 * It is Base UI's `Combobox` in `multiple` mode with the input inside the
 * popup — "a searchable list the user ticks several things in without it
 * closing" is exactly that primitive's brief, and it brings what the
 * hand-written checklist never had: arrow keys and typeahead over the list,
 * `aria-activedescendant`, a filter that follows the label, grouping, and an
 * empty state, each of which was either missing or re-implemented here
 * before (D16). What stays this file's own is the meaning of a tick.
 *
 * The catalogue is laid out exactly as every other field picker lays it out
 * — ungrouped fields first and without a heading, then each declared group —
 * so a field is found in the same place whether it is being added as a
 * condition, a column or a grouping.
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
  const titleId = useId();

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
  const sections: FieldSection[] = fieldGroups(
    candidates,
    filter.fieldGroups,
    field => field.name,
  ).map(entry => ({ value: entry.group?.label ?? '', items: entry.items }));
  const ticked = candidates.filter(field => held.has(field.name));

  // One press changes one field, so the difference between what the list
  // reports and what the group holds is one addition or one removal. The
  // tree is the source of truth: the list re-reads it on the next render
  // rather than keeping a selection of its own.
  const onValueChange = (next: readonly FieldDefinition[]) => {
    const chosen = new Set(next.map(field => field.name));
    const added = next.find(field => !held.has(field.name));
    if (added) {
      filter.addLeaf(added.name, parent);
      return;
    }
    const removed = ticked.find(field => !chosen.has(field.name));
    const at = removed && held.get(removed.name);
    if (at !== undefined) filter.remove([...parent, at]);
  };

  return (
    <Combobox
      multiple
      items={sections}
      value={ticked}
      onValueChange={onValueChange}
      isItemEqualToValue={(item, value) => item.name === value.name}
      itemToStringLabel={field => field.label}
      open={open}
      onOpenChange={(next, details) => {
        // Ticking a field is not leaving the list: the popup stays open on
        // an item press, and closes on Escape, outside, or Done.
        if (!next && details.reason === 'item-press') {
          details.cancel();
          return;
        }
        setOpen(next);
      }}
      onInputValueChange={(_value, details) => {
        // Nor does a tick wipe what was typed: the filter survives the press
        // and resets when the popup closes, as the primitive has it.
        if (details.isItemPress) details.cancel();
      }}
      disabled={disabled}
    >
      {/* The primitive's trigger rather than the vendored `ComboboxTrigger`:
          that one is a select-like field with a chevron, and this is the
          "add" button the tray already had — an icon, a word, no chevron. */}
      <ComboboxPrimitive.Trigger
        aria-label={label}
        render={<Button variant="outline" size="sm" disabled={disabled} />}
      >
        <PlusIcon data-icon="inline-start" />
        {label}
      </ComboboxPrimitive.Trigger>
      <ComboboxContent
        align="start"
        aria-label={messages.label('label.filter.pick-fields')}
        className="flex w-(--available-width) max-w-100 flex-col"
      >
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <span
            id={titleId}
            data-slot="field-checklist-title"
            className="flex-1 truncate text-sm font-medium"
          >
            {messages.label('label.filter.pick-fields')}
          </span>
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

        {/* In a box of its own rather than as the popup's direct child: the
            registry tints an input that sits directly in a combobox popup
            (`border-input/30`, `bg-input/30`) into a quiet search line, and
            that edge measures under the 3:1 this package holds text inputs
            to (WCAG 1.4.11, `styles.css` on `--input`). One level down the
            input keeps its ordinary border. Layout only; no colour is set. */}
        <div className="px-3 pb-2">
          <ComboboxInput
            showTrigger={false}
            placeholder={messages.label('label.field.search')}
            aria-label={messages.label('label.field.search')}
          />
        </div>

        <ComboboxEmpty>{messages.label('label.field.none')}</ComboboxEmpty>
        {/* The listbox is named by the title above it: an ARIA input field
            without a name is what axe flagged in the browser story. */}
        <ComboboxList data-slot="field-checklist" aria-labelledby={titleId}>
          {(section: FieldSection) => (
            <ComboboxGroup key={section.value} items={section.items}>
              {section.value !== '' && (
                <ComboboxLabel>{section.value}</ComboboxLabel>
              )}
              <ComboboxCollection>
                {(field: FieldDefinition) => (
                  <ComboboxItem key={field.name} value={field}>
                    <span className="truncate">{field.label}</span>
                  </ComboboxItem>
                )}
              </ComboboxCollection>
            </ComboboxGroup>
          )}
        </ComboboxList>
      </ComboboxContent>
    </Combobox>
  );
}
