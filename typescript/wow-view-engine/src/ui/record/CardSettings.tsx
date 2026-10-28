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

import { useId, type ReactElement } from 'react';
import { Columns3CogIcon, LayoutGridIcon } from 'lucide-react';
import {
  isFieldlessKind,
  type FieldDefinition,
  type RecordCardSpec,
} from '../../model/index.js';
import type {
  RecordKanbanController,
  RecordTableController,
} from '../../react/index.js';
import { Button } from '../components/button.js';
import { Checkbox } from '../components/checkbox.js';
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldSet,
  FieldTitle,
} from '../components/field.js';
import {
  Popover,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from '../components/popover.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import {
  PopoverContent,
  SelectContent,
  TooltipContent,
} from '../kit/popups.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import {
  ToolbarItem,
  libraryPopover,
  type DetachedPopover,
} from '../kit/toolbar.js';

interface CardSettingsProps {
  table: RecordTableController;
  /** The fields the definition offers, in its order. */
  fields: readonly FieldDefinition[];
  /**
   * The control that opens it, where the toolbar's is not on screen: the
   * error strip's worded button for a config that will not run (as
   * `ColumnSettings`' `trigger`). Left out, the toolbar's own button.
   */
  trigger?: ReactElement<Record<string, unknown>>;
  /** One half of it, as `ColumnSettings`' `detached`. */
  detached?: DetachedPopover;
  /**
   * Under the kanban (D83): its lanes are set here too, above the cards'
   * own settings, and a lane holds its cards one under another, so there is
   * no「每行几张」. Left out, the card settings alone.
   */
  kanban?: RecordKanbanController;
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
export function CardSettings({
  table,
  fields,
  trigger,
  detached,
  kanban,
}: CardSettingsProps) {
  const messages = useViewMessages();
  const name = messages.label(
    kanban ? 'label.toolbar.kanban' : 'label.toolbar.card',
  );
  // Not the layout switch's own icon: the switch's pressed segment stands
  // right beside this button, and two of one icon read as one control.
  const Icon = kanban ? Columns3CogIcon : LayoutGridIcon;
  const ids = useId();
  const spec = table.cardSpec;
  // A field-less kind's name is a handle for the editor, not a path into a
  // row, so it names nothing a card could show.
  // Named in the surface's words: this list is shown, and gives back names.
  const offered = fields
    .filter(field => !isFieldlessKind(field.kind))
    .map(field => ({ ...field, label: messages.say(field.label) }));
  const labelOf = (name: string) =>
    offered.find(field => field.name === name)?.label ?? name;
  const patch = (next: Partial<RecordCardSpec>) => table.setCard(next);
  const toggle = (name: string, on: boolean) =>
    patch({
      fields: on
        ? [...spec.fields.filter(field => field !== name), name]
        : spec.fields.filter(field => field !== name),
    });

  const handle = libraryPopover(detached?.handle);
  const opener = (
    <>
      {trigger ? (
        <PopoverTrigger
          handle={handle}
          data-control="columns"
          render={trigger}
        />
      ) : (
        <Tooltip>
          <TooltipTrigger
            render={
              <ToolbarItem
                render={
                  <PopoverTrigger
                    handle={handle}
                    // The same handle the column settings wear, so a host
                    // or a test that reaches for "the arrangement control"
                    // finds it under either layout.
                    data-control="columns"
                    aria-label={name}
                    render={<Button variant="outline" size="icon-sm" />}
                  />
                }
              />
            }
          >
            <Icon />
          </TooltipTrigger>
          <TooltipContent>{name}</TooltipContent>
        </Tooltip>
      )}
    </>
  );
  if (detached?.part === 'trigger') return opener;

  return (
    <Popover handle={handle}>
      {detached ? null : opener}
      <PopoverContent align="end" className="fve:w-80">
        <PopoverHeader>
          <PopoverTitle>
            {messages.label(kanban ? 'label.kanban.title' : 'label.card.title')}
          </PopoverTitle>
          <PopoverDescription>
            {messages.label(kanban ? 'label.kanban.hint' : 'label.card.hint')}
          </PopoverDescription>
        </PopoverHeader>

        <FieldGroup>
          {kanban && <KanbanLanes kanban={kanban} ids={ids} />}
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

          <CardBodyFields
            offered={offered}
            spec={spec}
            ids={ids}
            onToggle={toggle}
          />

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

          {!kanban && (
            <Field data-slot="card-per-row">
              <FieldTitle id={`${ids}-columns`}>
                {messages.label('label.card.per-row')}
              </FieldTitle>
              <ToggleGroup
                value={[String(spec.perRow ?? 3)]}
                onValueChange={value => {
                  const next = PER_ROW.find(
                    count => String(count) === value[0],
                  );
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
          )}
        </FieldGroup>
      </PopoverContent>
    </Popover>
  );
}

/**
 * The fields a card's body shows, one checkbox each, the title's field left
 * out: it names the card already.
 */
function CardBodyFields({
  offered,
  spec,
  ids,
  onToggle,
}: {
  offered: readonly { name: string; label: string }[];
  spec: RecordCardSpec;
  ids: string;
  onToggle: (name: string, on: boolean) => void;
}) {
  const messages = useViewMessages();
  return (
    <FieldSet data-slot="card-body-fields">
      <FieldTitle>{messages.label('label.card.fields')}</FieldTitle>
      <FieldGroup className="fve:gap-2">
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
                  onCheckedChange={checked => onToggle(field.name, checked)}
                />
                <FieldContent>
                  <FieldLabel htmlFor={id} className="fve:font-normal">
                    {field.label}
                  </FieldLabel>
                </FieldContent>
              </Field>
            );
          })}
      </FieldGroup>
    </FieldSet>
  );
}

/**
 * The kanban's lanes (D83): which field they are laid out by, and which of
 * its options are shown. Every option keeps its checkbox; once the shown
 * ones reach the most a kanban draws, the rest are switched off and say why.
 */
function KanbanLanes({
  kanban,
  ids,
}: {
  kanban: RecordKanbanController;
  ids: string;
}) {
  const messages = useViewMessages();
  const spec = kanban.spec;
  const hidden = new Set<unknown>(spec?.hidden ?? []);
  const shown = kanban.laneOptions.filter(
    option => !hidden.has(option.value),
  ).length;
  const full = shown >= kanban.maxLanes;
  const fields = kanban.laneFields.map(field => ({
    value: field.name,
    label: messages.say(field.label),
  }));
  const toggle = (value: string | number, on: boolean) =>
    kanban.setKanban({
      hidden: on
        ? [...hidden].filter(
            (entry): entry is string | number => entry !== value,
          )
        : [...(spec?.hidden ?? []), value],
    });
  return (
    <>
      <Field data-slot="kanban-lane-field">
        <FieldLabel id={`${ids}-lane`}>
          {messages.label('label.kanban.lane-field')}
        </FieldLabel>
        <Select
          items={fields}
          value={spec?.lane ?? null}
          onValueChange={value => {
            const next = fields.find(field => field.value === value);
            if (next) kanban.setKanban({ lane: next.value });
          }}
        >
          <SelectTrigger size="sm" aria-labelledby={`${ids}-lane`}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {fields.map(field => (
                <SelectItem key={field.value} value={field.value}>
                  {field.label}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      </Field>
      <FieldSet data-slot="kanban-lanes">
        <FieldTitle>{messages.label('label.kanban.lanes')}</FieldTitle>
        <FieldDescription>
          {messages.label('label.kanban.lanes-max', { max: kanban.maxLanes })}
        </FieldDescription>
        <FieldGroup className="fve:gap-2">
          {kanban.laneOptions.map(option => {
            const on = !hidden.has(option.value);
            const id = `${ids}-lane-${String(option.value)}`;
            return (
              <Field
                key={String(option.value)}
                orientation="horizontal"
                data-lane={String(option.value)}
                data-disabled={!on && full ? true : undefined}
              >
                <Checkbox
                  id={id}
                  checked={on}
                  disabled={!on && full}
                  onCheckedChange={checked => toggle(option.value, checked)}
                />
                <FieldContent>
                  <FieldLabel htmlFor={id} className="fve:font-normal">
                    {messages.say(option.label)}
                  </FieldLabel>
                </FieldContent>
              </Field>
            );
          })}
        </FieldGroup>
      </FieldSet>
    </>
  );
}
