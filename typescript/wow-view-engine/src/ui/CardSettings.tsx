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
import { LayoutGridIcon } from 'lucide-react';
import {
  isFieldlessKind,
  type FieldDefinition,
  type RecordCardSpec,
} from '../model/index.js';
import type { RecordTableController } from '../react/index.js';
import { Button } from './components/button.js';
import { Checkbox } from './components/checkbox.js';
import {
  Field,
  FieldContent,
  FieldGroup,
  FieldLabel,
  FieldSet,
  FieldTitle,
} from './components/field.js';
import {
  Popover,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from './components/popover.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { PopoverContent, SelectContent, TooltipContent } from './popups.js';
import { useViewMessages } from './MessagesProvider.js';
import { ToolbarItem } from './toolbar.js';

export interface CardSettingsProps {
  table: RecordTableController;
  /** The fields the definition offers, in its order. */
  fields: readonly FieldDefinition[];
}

/** The value the image select carries for "none": no field is named this. */
const NO_IMAGE = '';
const PER_ROW: readonly (1 | 2 | 3 | 4)[] = [1, 2, 3, 4];

/**
 * What a card shows: the field that names it, the fields in its body, the
 * field its picture comes from, and how many stand in a row.
 *
 * It is the column settings' button under the card layout (D18 VI): the
 * same place in the toolbar, the same popover, and the question the column
 * settings answer for a row asked for a card. Before this the button did
 * nothing at all under cards, and `config.card` had no editor anywhere.
 *
 * The body keeps the order the config holds: a field switched on joins at
 * the end, one switched off leaves, and what a saved view already lists
 * stays where it was. Nothing here is dragged — a card's body is read top
 * to bottom, and the definition's order is the order most hosts want; a
 * reorder is the one thing left to the phase after this.
 */
export function CardSettings({ table, fields }: CardSettingsProps) {
  const messages = useViewMessages();
  const ids = useId();
  const spec = table.cardSpec;
  // A field-less kind's name is a handle for the editor, not a path into a
  // row, so it names nothing a card could show.
  const offered = fields.filter(field => !isFieldlessKind(field.kind));
  const labelOf = (name: string) =>
    offered.find(field => field.name === name)?.label ?? name;
  const patch = (next: Partial<RecordCardSpec>) => table.setCard(next);
  const toggle = (name: string, on: boolean) =>
    patch({
      fields: on
        ? [...spec.fields.filter(field => field !== name), name]
        : spec.fields.filter(field => field !== name),
    });

  return (
    <Popover>
      <Tooltip>
        <TooltipTrigger
          render={
            <ToolbarItem
              render={
                <PopoverTrigger
                  // The same handle the column settings wear, so a host
                  // or a test that reaches for "the arrangement control"
                  // finds it under either layout.
                  data-control="columns"
                  aria-label={messages.label('label.toolbar.card')}
                  render={<Button variant="outline" size="icon-sm" />}
                />
              }
            />
          }
        >
          <LayoutGridIcon />
        </TooltipTrigger>
        <TooltipContent>{messages.label('label.toolbar.card')}</TooltipContent>
      </Tooltip>
      <PopoverContent align="end" className="w-80">
        <PopoverHeader>
          <PopoverTitle>{messages.label('label.card.title')}</PopoverTitle>
          <PopoverDescription>
            {messages.label('label.card.hint')}
          </PopoverDescription>
        </PopoverHeader>

        <FieldGroup>
          <Field data-slot="card-title-field">
            <FieldLabel id={`${ids}-title`}>
              {messages.label('label.card.title-field')}
            </FieldLabel>
            <Select
              items={offered.map(field => ({
                value: field.name,
                label: field.label,
              }))}
              value={spec.title}
              onValueChange={value => {
                const next = offered.find(field => field.name === value);
                if (next) patch({ title: next.name });
              }}
            >
              <SelectTrigger size="sm" aria-labelledby={`${ids}-title`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {offered.map(field => (
                    <SelectItem key={field.name} value={field.name}>
                      {field.label}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>

          <FieldSet data-slot="card-body-fields">
            <FieldTitle>{messages.label('label.card.fields')}</FieldTitle>
            <FieldGroup className="gap-2">
              {offered
                .filter(field => field.name !== spec.title)
                .map(field => {
                  const on = spec.fields.includes(field.name);
                  const id = `${ids}-${field.name}`;
                  return (
                    <Field
                      key={field.name}
                      orientation="horizontal"
                      data-field={field.name}
                    >
                      {/* Named by the label beside it, which `Field` wires
                          up: the fieldset already says these are the body,
                          so the name is the field's own. */}
                      <Checkbox
                        id={id}
                        checked={on}
                        onCheckedChange={checked => toggle(field.name, checked)}
                      />
                      <FieldContent>
                        <FieldLabel htmlFor={id} className="font-normal">
                          {field.label}
                        </FieldLabel>
                      </FieldContent>
                    </Field>
                  );
                })}
            </FieldGroup>
          </FieldSet>

          <Field data-slot="card-image-field">
            <FieldLabel id={`${ids}-image`}>
              {messages.label('label.card.image-field')}
            </FieldLabel>
            <Select
              items={[
                {
                  value: NO_IMAGE,
                  label: messages.label('label.card.no-image'),
                },
                ...offered.map(field => ({
                  value: field.name,
                  label: field.label,
                })),
              ]}
              value={spec.image ?? NO_IMAGE}
              onValueChange={value =>
                patch({
                  image:
                    value === NO_IMAGE || typeof value !== 'string'
                      ? undefined
                      : value,
                })
              }
            >
              <SelectTrigger size="sm" aria-labelledby={`${ids}-image`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem value={NO_IMAGE}>
                    {messages.label('label.card.no-image')}
                  </SelectItem>
                  {offered.map(field => (
                    <SelectItem key={field.name} value={field.name}>
                      {labelOf(field.name)}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>

          <Field data-slot="card-per-row">
            <FieldTitle id={`${ids}-columns`}>
              {messages.label('label.card.per-row')}
            </FieldTitle>
            <ToggleGroup
              value={[String(spec.perRow ?? 3)]}
              onValueChange={value => {
                const next = PER_ROW.find(count => String(count) === value[0]);
                if (next) patch({ perRow: next });
              }}
              variant="outline"
              size="sm"
              spacing={0}
              aria-labelledby={`${ids}-columns`}
            >
              {PER_ROW.map(count => (
                <ToggleGroupItem
                  key={count}
                  value={String(count)}
                  aria-label={messages.label('label.card.per-row-option', {
                    count,
                  })}
                >
                  {count}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          </Field>
        </FieldGroup>
      </PopoverContent>
    </Popover>
  );
}
