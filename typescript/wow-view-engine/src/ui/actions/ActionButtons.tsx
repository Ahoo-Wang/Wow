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

import {
  useId,
  useRef,
  type ReactElement,
  type ReactNode,
  type RefObject,
} from 'react';
import { EllipsisVerticalIcon, TriangleAlertIcon } from 'lucide-react';
import type { FieldOption } from '../../model/index.js';
import type { RecordRow } from '../../record/index.js';
import type {
  ActionInput,
  ActionPlace,
  RecordAction,
} from '../../runtime/actions.js';
import { asksFirst, choiceOf, initialInput } from '../../runtime/actions.js';
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
import { TooltipContent } from '../kit/popups.js';
import {
  DialogMenuItem,
  HandOffMenu,
  HandOffMenuContent,
} from '../kit/HandOffMenu.js';
import { IconTooltip } from '../kit/IconButton.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { ToolbarItem } from '../kit/toolbar.js';

/**
 * Presses an action, where a choice's option is the input, and names the
 * control the keyboard goes back to when a question it opens closes — the
 * button pressed, or the menu's button for an item, which goes with the
 * menu.
 */
type Start = (
  action: RecordAction,
  input?: ActionInput,
  from?: HTMLElement | null,
) => void;

/**
 * A button that stays where the keyboard is while it cannot be pressed: a
 * command running on the surface, or a record that does not take it.
 * `focusableWhenDisabled` keeps it in the Tab order, `aria-disabled` and a
 * swallowed press instead of the native `disabled` — which would hand the
 * keyboard to `<body>` the moment the press it just took began to run, and
 * keep a reason off a button the keyboard could not reach (A11Y-1, A11Y-15).
 * It reads as disabled from that attribute, the registry's look keyed on
 * the native one.
 */
const HELD = 'fve:aria-disabled:opacity-50';

/**
 * A choice's input for one option: the form's one field set to it.
 */
function inputFor(action: RecordAction, option: FieldOption): ActionInput {
  const choice = choiceOf(action);
  return choice ? { [choice.name]: option.value } : {};
}

/** A menu item for an action, or for one option of a choice. */
function ActionItem({
  action,
  place,
  input,
  disabled,
  onClick,
  children,
}: {
  action: RecordAction;
  place: ActionPlace;
  /** What the press gives: a choice's option; nothing else. */
  input?: ActionInput;
  disabled: boolean;
  onClick(): void;
  children: ReactNode;
}) {
  // Whether the press opens something that takes the keyboard — a
  // question, a form — and so leaves it there when the menu closes
  // (`DialogMenuItem`): the press's own rule, asked with the input it will
  // be asked with, so the item and the press never disagree.
  const opens = asksFirst(action, place, {
    ...initialInput(action),
    ...input,
  });
  const Item = opens ? DialogMenuItem : DropdownMenuItem;
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
  opener,
}: {
  action: RecordAction;
  place: ActionPlace;
  choices: readonly { option: FieldOption; available: boolean }[];
  disabled: boolean;
  onStart: Start;
  /** The menu's button, which the keyboard goes back to. */
  opener: RefObject<HTMLButtonElement | null>;
}) {
  const { say } = useViewMessages();
  const choice = choiceOf(action);
  return (
    <DropdownMenuGroup data-action={action.id}>
      {choice && (
        <DropdownMenuLabel>{say(choice.field.label)}</DropdownMenuLabel>
      )}
      {choices.map(({ option, available }) => {
        const input = inputFor(action, option);
        return (
          <ActionItem
            key={String(option.value)}
            action={action}
            place={place}
            input={input}
            disabled={disabled || !available}
            onClick={() => onStart(action, input, opener.current)}
          >
            {say(option.label)}
          </ActionItem>
        );
      })}
    </DropdownMenuGroup>
  );
}

/**
 * A button whose reason for being off is said on it: its tooltip, which
 * focus opens as hover does, and its accessible description.
 */
function Reasoned({
  reason,
  id,
  button,
}: {
  reason: string | null;
  id: string;
  button: ReactElement<Record<string, unknown>>;
}) {
  if (!reason) return button;
  return (
    <>
      <span id={id} className="fve:sr-only">
        {reason}
      </span>
      <Tooltip>
        <TooltipTrigger render={button} />
        <TooltipContent>{reason}</TooltipContent>
      </Tooltip>
    </>
  );
}

interface RowActionButtonsProps {
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
 * record. An action the record does not take now is held off, and why is
 * said on the button — its tooltip, which focus opens too, and its
 * accessible description — and atop the menu; each reason once. A button
 * held off stays where Tab finds it, and the 「⋯」 stays pressable while a
 * command runs, its items held off instead, so the keyboard is never handed
 * to the page by the press it just made.
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
  const more = useRef<HTMLButtonElement>(null);
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
        return (
          <Reasoned
            key={view.action.id}
            reason={reason}
            id={reasonId}
            button={
              <Button
                type="button"
                size="xs"
                variant={
                  view.action.tone === 'danger' ? 'destructive' : 'outline'
                }
                className={HELD}
                data-action={view.action.id}
                disabled={busy || !view.available}
                focusableWhenDisabled
                aria-describedby={reason ? reasonId : undefined}
                onClick={event => onStart(view.action, {}, event.currentTarget)}
              >
                {say(view.action.label)}
              </Button>
            }
          />
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
                ref={more}
                render={<Button type="button" size="icon-xs" variant="ghost" />}
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
                    disabled={busy || !view.available}
                    onClick={() => onStart(view.action, {}, more.current)}
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
                  disabled={busy || !view.available}
                  onStart={onStart}
                  opener={more}
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

interface BulkActionButtonsProps {
  views: readonly BulkActionView[];
  busy: boolean;
  onStart: Start;
}

/**
 * The declared actions over the selection, in the selection's bar: the
 * primary one counting what it is for (「准备 20 条」), and how many of
 * those take it where not all do (「催发货 2/4 条」), the others by name, a
 * choice as a menu of its options; one none of the selection takes is held
 * off with the reason. Every one asks first — how many, and which of them
 * will not take it (`ActionDialog`).
 *
 * Each is an item of the toolbar the bar stands in, so the bar stays one
 * Tab stop with the arrows inside it (A11Y-11); a host's own bulk slot
 * beside them is the host's markup, and keeps the stops it has.
 */
export function BulkActionButtons({
  views,
  busy,
  onStart,
}: BulkActionButtonsProps) {
  const messages = useViewMessages();
  const { say } = messages;
  const id = useId();
  return (
    <>
      {views.map(({ action, count, able, reason, choices }, index) => {
        const label = say(action.label);
        const why = able === 0 && reason !== null ? say(reason) : null;
        const reasonId = `${id}-${index}`;
        if (choices) {
          return (
            <BulkChoice
              key={action.id}
              action={action}
              label={label}
              why={why}
              reasonId={reasonId}
              able={able}
              choices={choices}
              busy={busy}
              onStart={onStart}
            />
          );
        }
        const named =
          action.primary !== true
            ? label
            : able < count && able > 0
              ? messages.label('label.action.bulk-able', {
                  action: label,
                  able,
                  count,
                })
              : messages.label('label.action.bulk', { action: label, count });
        return (
          <Reasoned
            key={action.id}
            reason={why}
            id={reasonId}
            button={
              <ToolbarItem
                disabled={busy || able === 0}
                aria-describedby={why ? reasonId : undefined}
                onClick={event => onStart(action, {}, event.currentTarget)}
                render={
                  <Button
                    type="button"
                    size="sm"
                    variant={action.primary === true ? 'default' : 'outline'}
                    className={HELD}
                    data-action={action.id}
                    data-tone={action.tone === 'danger' ? 'danger' : undefined}
                    focusableWhenDisabled
                  />
                }
              >
                {named}
              </ToolbarItem>
            }
          />
        );
      })}
    </>
  );
}

/** A choice over the selection: its button in the bar, its options in a menu. */
function BulkChoice({
  action,
  label,
  why,
  reasonId,
  able,
  choices,
  busy,
  onStart,
}: {
  action: RecordAction;
  label: string;
  why: string | null;
  reasonId: string;
  able: number;
  choices: readonly FieldOption[];
  busy: boolean;
  onStart: Start;
}) {
  const opener = useRef<HTMLButtonElement>(null);
  return (
    <HandOffMenu>
      <Reasoned
        reason={why}
        id={reasonId}
        button={
          <ToolbarItem
            disabled={able === 0}
            aria-describedby={why ? reasonId : undefined}
            render={
              <DropdownMenuTrigger
                ref={opener}
                render={
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    className={HELD}
                    data-action={action.id}
                  />
                }
              />
            }
          >
            {label}
          </ToolbarItem>
        }
      />
      <HandOffMenuContent align="start">
        <ChoiceItems
          action={action}
          place="bulk"
          choices={choices.map(option => ({ option, available: true }))}
          disabled={busy}
          onStart={onStart}
          opener={opener}
        />
      </HandOffMenuContent>
    </HandOffMenu>
  );
}
