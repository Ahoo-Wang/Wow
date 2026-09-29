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

import { Fragment, useId, type ReactNode } from 'react';
import { EllipsisVerticalIcon, TriangleAlertIcon } from 'lucide-react';
import type { FieldOption } from '../../model/index.js';
import type { RecordRow } from '../../record/index.js';
import type {
  ActionInput,
  ActionPlace,
  RecordAction,
} from '../../runtime/actions.js';
import { choiceOf } from '../../runtime/actions.js';
import type { BulkActionView, RowActionView } from '../../react/index.js';
import { Button } from '../components/button.js';
import {
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { TooltipContent } from '../popups.js';
import {
  DialogMenuItem,
  HandOffMenu,
  HandOffMenuContent,
} from '../HandOffMenu.js';
import { IconTooltip } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';

/** Presses an action, where a choice's option is the input. */
type Start = (action: RecordAction, input?: ActionInput) => void;

/**
 * A choice's input for one option: the form's one field set to it.
 */
function inputFor(action: RecordAction, option: FieldOption): ActionInput {
  const choice = choiceOf(action);
  return choice ? { [choice.name]: option.value } : {};
}

/**
 * Whether an item opens something that takes the keyboard — a question, a
 * form — and so leaves it there when the menu closes (`DialogMenuItem`).
 */
function opensDialog(action: RecordAction, place: ActionPlace): boolean {
  return (
    place === 'bulk' ||
    (action.form !== undefined && choiceOf(action) === null) ||
    (action.confirm !== undefined &&
      (typeof action.confirm === 'function' || action.confirm.ask !== 'bulk'))
  );
}

/** A menu item for an action, or for one option of a choice. */
function ActionItem({
  action,
  place,
  disabled,
  onClick,
  children,
}: {
  action: RecordAction;
  place: ActionPlace;
  disabled: boolean;
  onClick(): void;
  children: ReactNode;
}) {
  const Item = opensDialog(action, place) ? DialogMenuItem : DropdownMenuItem;
  const danger = action.tone === 'danger';
  return (
    <Item
      variant={danger ? 'destructive' : 'default'}
      data-action={action.id}
      data-tone={danger ? 'danger' : undefined}
      disabled={disabled}
      onClick={onClick}
    >
      {danger && <TriangleAlertIcon />}
      {children}
    </Item>
  );
}

/** A choice's options, under its field's name, in a menu. */
function ChoiceItems({
  action,
  place,
  choices,
  disabled,
  onStart,
}: {
  action: RecordAction;
  place: ActionPlace;
  choices: readonly { option: FieldOption; available: boolean }[];
  disabled: boolean;
  onStart: Start;
}) {
  const { say } = useViewMessages();
  const choice = choiceOf(action);
  return (
    <DropdownMenuGroup data-action={action.id}>
      {choice && (
        <DropdownMenuLabel>{say(choice.field.label)}</DropdownMenuLabel>
      )}
      {choices.map(({ option, available }) => (
        <ActionItem
          key={String(option.value)}
          action={action}
          place={place}
          disabled={disabled || !available}
          onClick={() => onStart(action, inputFor(action, option))}
        >
          {say(option.label)}
        </ActionItem>
      ))}
    </DropdownMenuGroup>
  );
}

export interface RowActionButtonsProps {
  row: RecordRow;
  views: readonly RowActionView[];
  place: 'row' | 'detail';
  /** A command is running on the surface: nothing else starts. */
  busy: boolean;
  onStart: Start;
}

/**
 * A record's declared actions, in its row, on its card or in its detail:
 * the primary ones as buttons, the rest behind 「⋯」, named after the
 * record. An action the record does not take now is disabled, and why is
 * said on the button — its tooltip, and its accessible description — and
 * atop the menu, where a keyboard reaches it; each reason once.
 */
export function RowActionButtons({
  row,
  views,
  place,
  busy,
  onStart,
}: RowActionButtonsProps) {
  const messages = useViewMessages();
  const { say } = messages;
  const id = useId();
  const inline = views.filter(
    view => view.action.primary === true && view.choices === null,
  );
  const menu = views.filter(view => !inline.includes(view));
  const reasons = [
    ...new Set(
      views.flatMap(view =>
        view.available || view.reason === null ? [] : [say(view.reason)],
      ),
    ),
  ];
  const plain = menu.filter(view => view.choices === null);
  const choices = menu.filter(view => view.choices !== null);
  return (
    <>
      {inline.map((view, index) => {
        const reason = view.reason === null ? null : say(view.reason);
        const reasonId = `${id}-${index}`;
        const button = (
          <Button
            type="button"
            size="xs"
            variant={view.action.tone === 'danger' ? 'destructive' : 'outline'}
            data-action={view.action.id}
            disabled={busy || !view.available}
            aria-describedby={reason ? reasonId : undefined}
            onClick={() => onStart(view.action)}
          >
            {say(view.action.label)}
          </Button>
        );
        if (!reason) return <Fragment key={view.action.id}>{button}</Fragment>;
        return (
          <span key={view.action.id} className="fve:inline-flex">
            <span id={reasonId} className="fve:sr-only">
              {reason}
            </span>
            <Tooltip>
              {/* A disabled button takes no pointer; its box shows the tip. */}
              <TooltipTrigger render={<span className="fve:inline-flex" />}>
                {button}
              </TooltipTrigger>
              <TooltipContent>{reason}</TooltipContent>
            </Tooltip>
          </span>
        );
      })}
      {menu.length > 0 && (
        <HandOffMenu>
          <IconTooltip
            label={messages.label('label.action.more', {
              record: String(row.key),
            })}
            render={
              <DropdownMenuTrigger
                render={
                  <Button
                    type="button"
                    size="icon-xs"
                    variant="ghost"
                    disabled={busy}
                  />
                }
              />
            }
          >
            <EllipsisVerticalIcon />
          </IconTooltip>
          <HandOffMenuContent align="end" className="fve:min-w-56">
            {reasons.length > 0 && (
              <DropdownMenuGroup data-slot="action-reasons">
                {reasons.map(reason => (
                  <DropdownMenuLabel key={reason} className="fve:font-normal">
                    {reason}
                  </DropdownMenuLabel>
                ))}
              </DropdownMenuGroup>
            )}
            {plain.length > 0 && (
              <DropdownMenuGroup>
                {plain.map(view => (
                  <ActionItem
                    key={view.action.id}
                    action={view.action}
                    place={place}
                    disabled={!view.available}
                    onClick={() => onStart(view.action)}
                  >
                    {say(view.action.label)}
                  </ActionItem>
                ))}
              </DropdownMenuGroup>
            )}
            {choices.map((view, index) => (
              <ChoiceGroup
                key={view.action.id}
                separated={index > 0 || plain.length > 0}
              >
                <ChoiceItems
                  action={view.action}
                  place={place}
                  choices={view.choices ?? []}
                  disabled={!view.available}
                  onStart={onStart}
                />
              </ChoiceGroup>
            ))}
          </HandOffMenuContent>
        </HandOffMenu>
      )}
    </>
  );
}

function ChoiceGroup({
  separated,
  children,
}: {
  separated: boolean;
  children: ReactNode;
}) {
  return (
    <>
      {separated && <DropdownMenuSeparator />}
      {children}
    </>
  );
}

export interface BulkActionButtonsProps {
  views: readonly BulkActionView[];
  busy: boolean;
  onStart: Start;
}

/**
 * The declared actions over the selection, in the selection's bar: the
 * primary one counting what it is for (「准备 20 条」), the others by name,
 * a choice as a menu of its options. Every one asks first — how many, and
 * which of them will not take it (`ActionDialog`).
 */
export function BulkActionButtons({
  views,
  busy,
  onStart,
}: BulkActionButtonsProps) {
  const messages = useViewMessages();
  const { say } = messages;
  return (
    <>
      {views.map(({ action, count, choices }) => {
        const label = say(action.label);
        if (choices) {
          return (
            <HandOffMenu key={action.id}>
              <DropdownMenuTrigger
                render={
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    data-action={action.id}
                    disabled={busy}
                  />
                }
              >
                {label}
              </DropdownMenuTrigger>
              <HandOffMenuContent align="start">
                <ChoiceItems
                  action={action}
                  place="bulk"
                  choices={choices.map(option => ({ option, available: true }))}
                  disabled={false}
                  onStart={onStart}
                />
              </HandOffMenuContent>
            </HandOffMenu>
          );
        }
        return (
          <Button
            key={action.id}
            type="button"
            size="sm"
            variant={action.primary === true ? 'default' : 'outline'}
            data-action={action.id}
            data-tone={action.tone === 'danger' ? 'danger' : undefined}
            disabled={busy}
            onClick={() => onStart(action)}
          >
            {action.primary === true
              ? messages.label('label.action.bulk', {
                  action: label,
                  count,
                })
              : label}
          </Button>
        );
      })}
    </>
  );
}
