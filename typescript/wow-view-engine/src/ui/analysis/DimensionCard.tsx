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

import { useCallback, useRef, useState } from 'react';
import { PlusIcon, XIcon } from 'lucide-react';
import {
  expressionText,
  groupableFields,
  groupOfType,
} from '../../analysis/index.js';
import type { AnalysisGroup } from '../../model/index.js';
import type {
  AnalysisEditorController,
  AnalysisFieldOption,
} from '../../react/index.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { GroupedMenu } from '../FieldMenu.js';
import { NumberInput } from '../FilterValueEditor.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { EditorCard, EditorSlot } from '../variants.js';
import { CardMenu, CardName } from './CardMenu.js';
import { CompactSelect } from './CompactSelect.js';
import { defaultGroup, usedAliases } from './editing.js';
import { useListFocus, type ListFocus } from './listFocus.js';

/**
 * The dimensions slot: one card per group, in the order they cut the
 * result, and the way to add one. Only fields the capability declares as
 * groupable are offered, so an unrunnable query cannot be built by
 * clicking; a field already cut by is not offered again — the kernel's
 * `groupableFields`, the list the follow-up menu's split reads too.
 */
export function DimensionSlot({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const groupable = groupableFields(analysis.fields, analysis.groups);
  // A dimension taken out leaves the keyboard on this slot, not on the
  // page's ground (`listFocus.ts`). It is held here rather than on the card
  // because the card pressed is the one that goes.
  const focus = useListFocus({
    list: '[data-slot="analysis-slot-dimensions"]',
    item: '[data-slot="dimension-card"]',
    add: '[data-slot="add-group"]',
  });
  // The last field left to cut by takes 「添加维度」 with it: the button is
  // disabled by the dimension it just added, and the menu handing the
  // keyboard back to a disabled trigger dropped it on `<body>` (the
  // 2026-09-25 keyboard walkthrough). The card that press made is where it
  // lands instead — the one thing on screen the press was about.
  const trigger = useRef<HTMLButtonElement>(null);
  const handBack = useCallback(() => {
    const button = trigger.current;
    if (!button?.disabled) return true;
    const cards = button
      .closest('[data-slot="analysis-slot-dimensions"]')
      ?.querySelectorAll<HTMLElement>('[data-slot="dimension-card"]');
    const added = cards?.[cards.length - 1];
    return (
      added?.querySelector<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), [tabindex="0"]',
      ) ?? true
    );
  }, []);
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
          focus={focus}
          disabled={disabled}
        />
      ))}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              ref={trigger}
              variant="ghost"
              size="sm"
              disabled={disabled || groupable.length === 0}
              data-slot="add-group"
              className="self-start"
            />
          }
        >
          <PlusIcon data-icon="inline-start" />
          {messages.label('label.analysis.add-group')}
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" finalFocus={handBack}>
          <GroupedMenu
            items={groupable}
            groups={analysis.fieldGroups}
            itemKey={field => field.field}
            render={field => (
              <DropdownMenuItem
                key={field.field}
                onClick={() =>
                  analysis.addGroup(
                    defaultGroup(
                      field,
                      usedAliases(analysis),
                      analysis.dateUnitFor(field),
                    ),
                  )
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
 * date's granularity, a calendar part (the weekday, the hour), a number's
 * band width, or nothing for "by value". A
 * field that can be cut more than one way gets the type select first. The
 * card's menu holds its display name and the two choices Wow keeps behind
 * its bucketing: whether records missing the value make a group of their
 * own (D20 空值), and whether a time dimension fills in its empty periods.
 */
function DimensionCard({
  analysis,
  group,
  index,
  focus,
  disabled,
}: {
  analysis: AnalysisEditorController;
  group: AnalysisGroup;
  index: number;
  /** Where the keyboard goes when this card is the one removed. */
  focus: ListFocus;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const [renaming, setRenaming] = useState(false);
  const menu = useRef<HTMLButtonElement>(null);
  const done = () => {
    setRenaming(false);
    menu.current?.focus();
  };
  const field = analysis.fields.find(entry => entry.field === group.field);
  // What the card is called, which is what every control on it is named
  // after: the name the analyst gave, else the field's display name — an
  // alias names the query and nobody chose it.
  // A band of a computed number names no field: it is called what it
  // computes, 「付款时间 → 发货时间」 (N3).
  const fallback =
    group.field === undefined
      ? expressionText(
          group.expression,
          name =>
            analysis.fields.find(entry => entry.field === name)?.label ?? name,
        )
      : (field?.label ?? group.field);
  const name = group.label ?? fallback;
  // Only a time dimension standing alone may fill its empty periods: a
  // second dimension would multiply the filling out (Wow refuses it).
  const alone = analysis.groups.length === 1;
  const types = (field?.groups ?? []).map(type => ({
    value: type,
    label: messages.label(
      `label.group.type.${type}`,
      undefined,
      type.split('_').join(' ').toLowerCase(),
    ),
  }));
  return (
    <EditorCard
      data-slot="dimension-card"
      data-field={group.field ?? `(${group.alias})`}
    >
      <CardName
        name={fallback}
        given={group.label}
        renaming={renaming}
        label={messages.label('label.analysis.display-name', { name })}
        onRename={label => analysis.renameGroup(index, label)}
        onDone={done}
      />
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
      {group.type === 'DATE_PART' && (
        <CompactSelect
          label={messages.label('label.analysis.date-part')}
          items={(field?.dateParts ?? [group.part]).map(part => ({
            value: part,
            label: messages.label(`label.date-part.${part}`),
          }))}
          value={group.part}
          disabled={disabled}
          onChange={part =>
            analysis.updateGroup(index, {
              part: part,
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
      <CardMenu
        ref={menu}
        name={name}
        disabled={disabled}
        onRename={() => setRenaming(true)}
      >
        {group.type === 'TERMS' && field?.missingKey && (
          <DropdownMenuCheckboxItem
            checked={group.missingKey !== undefined}
            onCheckedChange={on => analysis.setMissingBucket(index, on)}
          >
            {messages.label('label.analysis.missing-bucket')}
          </DropdownMenuCheckboxItem>
        )}
        {(group.type === 'DATE_HISTOGRAM' || group.type === 'DATE_PART') &&
          analysis.denseAllowed && (
            <DropdownMenuCheckboxItem
              checked={group.dense === true}
              disabled={!alone}
              onCheckedChange={on => analysis.setDense(index, on)}
            >
              {messages.label(
                group.type === 'DATE_PART'
                  ? alone
                    ? 'label.analysis.dense-part'
                    : 'label.analysis.dense-part-alone'
                  : alone
                    ? 'label.analysis.dense'
                    : 'label.analysis.dense-alone',
              )}
            </DropdownMenuCheckboxItem>
          )}
      </CardMenu>
      <IconButton
        label={messages.label('label.analysis.remove-group', { name })}
        variant="ghost"
        size="icon-xs"
        disabled={disabled}
        onClick={event => {
          focus.removing(event, index);
          analysis.removeGroup(index);
        }}
      >
        <XIcon />
      </IconButton>
    </EditorCard>
  );
}
