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

import { PlusIcon, XIcon } from 'lucide-react';
import type { AnalysisGroup } from '../../model/index.js';
import type {
  AnalysisEditorController,
  AnalysisFieldOption,
} from '../../react/index.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { GroupedMenu } from '../FieldMenu.js';
import { NumberInput } from '../FilterValueEditor.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { EditorCard, EditorSlot } from '../variants.js';
import { CompactSelect } from './CompactSelect.js';
import { aliasesOf, defaultGroup, groupOfType } from './editing.js';

/**
 * The dimensions slot: one card per group, in the order they cut the
 * result, and the way to add one. Only fields the capability declares as
 * groupable are offered, so an unrunnable query cannot be built by clicking.
 */
export function DimensionSlot({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const groupable = analysis.fields.filter(field => field.groups.length > 0);
  return (
    <EditorSlot
      name="dimensions"
      title={messages.label('label.analysis.slot.dimensions')}
      hint={messages.label('label.analysis.hint.dimensions')}
    >
      {analysis.groups.map((group, index) => (
        <DimensionCard
          key={group.alias}
          analysis={analysis}
          group={group}
          index={index}
          disabled={disabled}
        />
      ))}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="ghost"
              size="sm"
              disabled={disabled || groupable.length === 0}
              className="self-start"
            />
          }
        >
          <PlusIcon data-icon="inline-start" />
          {messages.label('label.analysis.add-group')}
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <GroupedMenu
            items={groupable}
            groups={analysis.fieldGroups}
            itemKey={field => field.field}
            render={field => (
              <DropdownMenuItem
                key={field.field}
                onClick={() =>
                  analysis.addGroup(defaultGroup(field, aliasesOf(analysis)))
                }
              >
                {field.label}
              </DropdownMenuItem>
            )}
          />
        </DropdownMenuContent>
      </DropdownMenu>
    </EditorSlot>
  );
}

/**
 * One dimension: the field, and the second control its type asks for — a
 * date's granularity, a number's band width, or nothing for "by value". A
 * field that can be cut more than one way gets the type select first.
 */
function DimensionCard({
  analysis,
  group,
  index,
  disabled,
}: {
  analysis: AnalysisEditorController;
  group: AnalysisGroup;
  index: number;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const field = analysis.fields.find(entry => entry.field === group.field);
  // The field's display name, which is what every control in this card is
  // named after — an alias names the query and nobody chose it.
  const name = field?.label ?? group.field;
  const types = (field?.groups ?? []).map(type => ({
    value: type,
    label: messages.label(
      `label.group.type.${type}`,
      undefined,
      type.split('_').join(' ').toLowerCase(),
    ),
  }));
  return (
    <EditorCard data-slot="dimension-card" data-field={group.field}>
      <span className="truncate font-medium">{name}</span>
      {types.length > 1 ? (
        <CompactSelect
          label={messages.label('label.analysis.grouping-of', { name })}
          items={types}
          value={group.type}
          disabled={disabled}
          onChange={type =>
            analysis.updateGroup(
              index,
              groupOfType(field as AnalysisFieldOption, type, group.alias),
            )
          }
        />
      ) : (
        types[0] && (
          <span className="text-muted-foreground">{types[0].label}</span>
        )
      )}
      {group.type === 'DATE_HISTOGRAM' && (
        <CompactSelect
          label={messages.label('label.analysis.granularity')}
          items={(field?.dateUnits ?? [group.unit]).map(unit => ({
            value: unit,
            label: messages.label(`label.date-unit.${unit}`),
          }))}
          value={group.unit}
          disabled={disabled}
          onChange={unit =>
            analysis.updateGroup(index, {
              unit: unit as AnalysisGroup extends { unit: infer U } ? U : never,
            })
          }
        />
      )}
      {group.type === 'HISTOGRAM' && (
        <NumberInput
          label={messages.label('label.analysis.interval')}
          chrome="box"
          className="w-20"
          disabled={disabled}
          value={group.interval}
          onNumber={next => {
            if (next !== null && next > 0)
              analysis.updateGroup(index, { interval: next });
          }}
        />
      )}
      <IconButton
        label={messages.label('label.analysis.remove-group', { name })}
        variant="ghost"
        size="icon-xs"
        className="ml-auto"
        disabled={disabled}
        onClick={() => analysis.removeGroup(index)}
      >
        <XIcon />
      </IconButton>
    </EditorCard>
  );
}
