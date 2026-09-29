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

import { useState } from 'react';
import type { FilterValue, RecordKey } from '../../model/index.js';
import type { EditorDescriptor } from '../../filter/index.js';
import type { ActionFormField } from '../../runtime/actions.js';
import type { PendingAction } from '../../react/index.js';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../components/alert-dialog.js';
import { Button } from '../components/button.js';
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldTitle,
} from '../components/field.js';
import { FilterValueEditor } from '../FilterValueEditor.js';
import {
  useSayWith,
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { AlertDialogContent } from '../popups.js';
import { DestructiveAction } from '../variants.js';

export interface ActionDialogProps {
  /** What is waiting on the reader; nothing is open while `null`. */
  pending: PendingAction | null;
  onInput(name: string, value: unknown): void;
  onOnlyAble(): void;
  onConfirm(): void;
  onCancel(): void;
}

/** How many refused records are named by key before the rest are counted. */
const KEYS_NAMED = 3;

/**
 * The question an action asks before it is sent, and its form.
 *
 * How many records it is for and what it does beyond the obvious, in the
 * host's words (`confirm`) or, for a selection whose host said none, the
 * engine's; the fields the command needs, drawn by the condition editor's
 * own value controls; and over a selection, the records the engine already
 * knows will not take it, by reason with their keys — 「5 条里 3 条能重试」 —
 * with one press to pick only the ones that can. Sent anyway, those are
 * reported as refused and left selected; nothing is lost.
 *
 * An `AlertDialog`: a command is not something to click past, so an outside
 * press does not dismiss it and the keyboard stays inside until an answer.
 * The words stay while it animates closed, rather than blanking.
 */
export function ActionDialog({
  pending,
  onInput,
  onOnlyAble,
  onConfirm,
  onCancel,
}: ActionDialogProps) {
  const messages = useViewMessages();
  const sayWith = useSayWith();
  const [shown, setShown] = useState(pending);
  if (pending && pending !== shown) setShown(pending);
  const open = pending !== null;
  if (!shown) return null;
  const { action, place, keys, confirm, refused, able, missing } = shown;
  const count = keys.length;
  const values = {
    count,
    ...(shown.value === null ? {} : { value: shown.value }),
  };
  const named = sayWith(action.label, values);
  const title = confirm
    ? sayWith(confirm.title, values)
    : place === 'bulk'
      ? messages.label('label.action.confirm', { action: named, count })
      : named;
  const body = confirm?.body && sayWith(confirm.body, values);
  const answer = sayWith(confirm?.action ?? action.label, values);
  const danger = (confirm?.tone ?? action.tone) === 'danger';
  const bulk = place === 'bulk';
  const ready = open && missing.length === 0 && able.length > 0;
  const Confirm = danger ? DestructiveAction : AlertDialogAction;
  return (
    <AlertDialog
      open={open}
      onOpenChange={next => {
        if (!next) onCancel();
      }}
    >
      <AlertDialogContent data-slot="action-dialog" data-action={action.id}>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          {(body || bulk) && (
            <AlertDialogDescription>
              {[body, bulk ? messages.label('label.action.left') : '']
                .filter(Boolean)
                .join(' ')}
            </AlertDialogDescription>
          )}
        </AlertDialogHeader>
        {shown.form && action.form && (
          <FieldGroup data-slot="action-form">
            {Object.entries(action.form).map(([name, field]) => (
              <FormField
                key={name}
                name={name}
                field={field}
                value={shown.input[name]}
                missing={missing.includes(name)}
                onChange={value => onInput(name, value)}
                messages={messages}
              />
            ))}
          </FieldGroup>
        )}
        {refused.length > 0 && (
          <div
            data-slot="action-refused"
            className="fve:flex fve:flex-col fve:gap-1 fve:text-sm fve:text-muted-foreground"
          >
            <p>
              {messages.label(
                able.length === 0
                  ? 'label.action.none-able'
                  : 'label.action.able',
                { able: able.length, count, action: named },
              )}
            </p>
            <p>{messages.label('label.action.refused')}</p>
            <ul className="fve:list-disc fve:pl-5">
              {refused.map(group => (
                <li key={group.reason}>
                  <span>
                    {messages.label('label.bulk.reason', {
                      reason: messages.say(group.reason),
                      count: group.keys.length,
                    })}
                  </span>{' '}
                  <span className="fve:break-all">
                    {keysOf(group.keys, messages)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
        <AlertDialogFooter>
          <AlertDialogCancel>
            {messages.label('label.action.cancel')}
          </AlertDialogCancel>
          {bulk && refused.length > 0 && able.length > 0 && (
            <Button
              type="button"
              variant="outline"
              data-slot="action-only-able"
              onClick={onOnlyAble}
            >
              {messages.label('label.action.only-able', { count: able.length })}
            </Button>
          )}
          <Confirm disabled={!ready} onClick={onConfirm}>
            {answer}
          </Confirm>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** A few of the refused by key, and how many more. */
function keysOf(
  keys: readonly RecordKey[],
  messages: MessageFormatters,
): string {
  const first = keys.slice(0, KEYS_NAMED).map(String).join(', ');
  const more = keys.length - KEYS_NAMED;
  return more > 0
    ? messages.label('label.action.refused-keys-more', {
        keys: first,
        count: more,
      })
    : first;
}

/** The editor a form field is drawn with: the condition editor's own. */
function editorOf(field: ActionFormField): EditorDescriptor {
  if (field.options) return { input: 'select', options: [...field.options] };
  return { input: field.input ?? 'text' };
}

function FormField({
  name,
  field,
  value,
  missing,
  onChange,
  messages,
}: {
  name: string;
  field: ActionFormField;
  value: unknown;
  missing: boolean;
  onChange(value: FilterValue): void;
  messages: MessageFormatters;
}) {
  const label = messages.say(field.label);
  return (
    <Field data-field={name} data-invalid={missing || undefined}>
      <FieldTitle>{label}</FieldTitle>
      <FilterValueEditor
        editor={editorOf(field)}
        kind={field.options ? 'enum' : (field.input ?? 'string')}
        label={field.label}
        value={(value ?? null) as FilterValue}
        required={field.required !== false}
        {...(field.options ? { options: [...field.options] } : {})}
        onChange={onChange}
      />
      {missing && (
        <FieldDescription>
          {messages.label('label.action.required')}
        </FieldDescription>
      )}
    </Field>
  );
}
