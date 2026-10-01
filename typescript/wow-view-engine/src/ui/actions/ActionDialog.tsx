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
  FieldLabel,
} from '../components/field.js';
import { FilterValueEditor } from '../filter/FilterValueEditor.js';
import {
  useSayWith,
  useViewMessages,
  type MessageFormatters,
} from '../kit/MessagesProvider.js';
import { focusIn, type FinalFocus } from '../kit/focus.js';
import { AlertDialogContent } from '../kit/popups.js';
import { DestructiveAction } from '../kit/variants.js';

export interface ActionDialogProps {
  /** What is waiting on the reader; nothing is open while `null`. */
  pending: PendingAction | null;
  onInput(name: string, value: unknown): void;
  onOnlyAble(): void;
  onConfirm(): void;
  onCancel(): void;
  /** Where the keyboard goes as it closes: the control that asked. */
  finalFocus?: FinalFocus;
}

/** How many refused records are named by key before the rest are counted. */
const KEYS_NAMED = 3;

/**
 * The question an action asks before it is sent, and its form.
 *
 * How many records it is for — or, for one, which: the reader confirms the
 * record they meant — and what it does beyond the obvious, in the host's
 * words (`confirm`) or, where the host said none, the engine's; the fields
 * the command needs, drawn by the condition editor's own value controls;
 * and over a selection, the records the engine already knows will not take
 * it, by reason with their keys — 「5 条里 3 条能重试」 — with one press to
 * pick only the ones that can, and the answer counting what will be sent
 * (「催发货 3 条」). Sent anyway, the refused are reported as not run and
 * left selected; nothing is lost.
 *
 * A modal that an outside press does not dismiss, the keyboard inside
 * until an answer. Only a dangerous question with nothing to fill is an
 * `alertdialog` — read out as urgent, which a loss is; a form, or a routine
 * question, is a `dialog`. The answer stays pressable while a required
 * field is blank: pressing it marks what is missing and takes the keyboard
 * there, rather than a grey button that says nothing of why. The words stay
 * while it animates closed, rather than blanking.
 */
export function ActionDialog({
  pending,
  onInput,
  onOnlyAble,
  onConfirm,
  onCancel,
  finalFocus,
}: ActionDialogProps) {
  const messages = useViewMessages();
  const sayWith = useSayWith();
  const id = useId();
  const form = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState(pending);
  if (pending && pending !== shown) setShown(pending);
  // Which opening has had an answer pressed with a field still blank: from
  // then on, what is missing is marked so.
  const [tried, setTried] = useState<string | null>(null);
  const open = pending !== null;
  if (!open && tried !== null) setTried(null);
  if (!shown) return null;
  const { action, place, keys, confirm, refused, able, missing } = shown;
  const opening = `${action.id}\u0000${place}\u0000${keys.join('\u0000')}`;
  const count = keys.length;
  // One record is named, not counted.
  const record = count === 1 ? String(keys[0]) : null;
  const values = {
    count,
    ...(record === null ? {} : { record }),
    ...(shown.value === null ? {} : { value: shown.value }),
  };
  const named = sayWith(action.label, values);
  const title = confirm
    ? sayWith(confirm.title, values)
    : record !== null
      ? messages.label(
          place === 'bulk'
            ? 'label.action.confirm-record'
            : 'label.action.on-record',
          { action: named, record },
        )
      : messages.label('label.action.confirm', { action: named, count });
  const body = [
    confirm?.body ? sayWith(confirm.body, values) : '',
    // The host's question may count rather than name: the record is said.
    confirm && record !== null
      ? messages.label('label.action.record', { record })
      : '',
    place === 'bulk' ? messages.label('label.action.left') : '',
  ]
    .filter(Boolean)
    .join(' ');
  const bulk = place === 'bulk';
  const answered = sayWith(confirm?.action ?? action.label, values);
  // Over a selection the answer counts what will be sent.
  const answer =
    bulk && count > 1
      ? messages.label('label.action.bulk', {
          action: answered,
          count: able.length,
        })
      : answered;
  const danger = (confirm?.tone ?? action.tone) === 'danger';
  const marked = tried === opening;
  const noneId = `${id}-none`;
  const Confirm = danger ? DestructiveAction : AlertDialogAction;
  const press = () => {
    if (missing.length > 0) {
      setTried(opening);
      // The first blank field, by its name (a name is the host's, so it is
      // matched, never written into a selector).
      const first = [
        ...(form.current?.querySelectorAll('[data-field]') ?? []),
      ].find(field => field.getAttribute('data-field') === missing[0]);
      focusIn(first);
      return;
    }
    onConfirm();
  };
  return (
    <AlertDialog
      open={open}
      onOpenChange={next => {
        if (!next) onCancel();
      }}
    >
      <AlertDialogContent
        data-slot="action-dialog"
        data-action={action.id}
        role={danger && !shown.form ? 'alertdialog' : 'dialog'}
        {...(finalFocus ? { finalFocus } : {})}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          {body && <AlertDialogDescription>{body}</AlertDialogDescription>}
        </AlertDialogHeader>
        {shown.form && action.form && (
          <FieldGroup ref={form} data-slot="action-form">
            {Object.entries(action.form).map(([name, field], index) => (
              <FormField
                key={name}
                id={`${id}-${index}`}
                name={name}
                field={field}
                value={shown.input[name]}
                invalid={marked && missing.includes(name)}
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
            <p id={noneId}>
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
          {/* Held off only when nothing would be sent, and said why: the
              sentence above that counts none able. */}
          <Confirm
            className="fve:aria-disabled:opacity-50"
            disabled={!open || able.length === 0}
            focusableWhenDisabled
            aria-describedby={able.length === 0 ? noneId : undefined}
            onClick={press}
          >
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

/**
 * One field of the form: its label pointing at its control, the control
 * required where the field is, and 「必填」 the control's description —
 * marked invalid only once an answer was pressed without it.
 */
function FormField({
  id,
  name,
  field,
  value,
  invalid,
  onChange,
  messages,
}: {
  id: string;
  name: string;
  field: ActionFormField;
  value: unknown;
  invalid: boolean;
  onChange(value: FilterValue): void;
  messages: MessageFormatters;
}) {
  const label = messages.say(field.label);
  const required = field.required !== false;
  const hint = `${id}-hint`;
  return (
    <Field data-field={name} data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <FilterValueEditor
        editor={editorOf(field)}
        kind={field.options ? 'enum' : (field.input ?? 'string')}
        label={field.label}
        value={(value ?? null) as FilterValue}
        invalid={invalid}
        control={{
          id,
          required,
          ...(required ? { describedBy: hint } : {}),
        }}
        {...(field.options ? { options: [...field.options] } : {})}
        onChange={onChange}
      />
      {required && (
        <FieldDescription id={hint}>
          {messages.label('label.action.required')}
        </FieldDescription>
      )}
    </Field>
  );
}
