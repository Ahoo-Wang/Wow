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
import type { ViewAudience, ViewInstance } from '../model/index.js';
import type { SaveAbilities, SaveCommands } from '../react/index.js';
import { Button } from './components/button.js';
import {
  Dialog,
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './components/dialog.js';
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldTitle,
} from './components/field.js';
import { Input } from './components/input.js';
import { RadioGroup, RadioGroupItem } from './components/radio-group.js';
import { Spinner } from './components/spinner.js';
import type { MessageKey } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';
import { DialogContent } from './popups.js';

export interface SaveAsDialogProps {
  open: boolean;
  onOpenChange(open: boolean): void;
  commands: SaveCommands;
  /** The view being copied; the title field opens on `{title} copy`. */
  title: string;
  /** Called with the copy once the store took it. */
  onSaved?(instance: ViewInstance): void;
}

/** One audience on offer, still unworded: the catalogue says all of it. */
interface ScopeChoice {
  value: ViewAudience;
  labelKey: MessageKey;
  descriptionKey: MessageKey;
}

const SCOPES: readonly ScopeChoice[] = [
  {
    value: 'personal',
    labelKey: 'label.scope.only-me',
    descriptionKey: 'label.scope.personal.description',
  },
  {
    value: 'shared',
    labelKey: 'label.scope.everyone',
    descriptionKey: 'label.scope.shared.description',
  },
];

/** Whether a copy may be created in this audience. */
function allows(can: SaveAbilities, scope: ViewAudience): boolean {
  return scope === 'personal' ? can.createPersonal : can.createShared;
}

/**
 * Making a copy of the open view: a title and who it is for.
 *
 * It is one dialog used from two places — the save menu, and the copy way out
 * of a conflict — because both ask the same question and both must answer it
 * the same way. The dialog stays open until the store has actually taken the
 * copy: a create that was refused has something to say, and a dialog that
 * closed first would say it somewhere the user is no longer looking.
 */
export function SaveAsDialog({
  open,
  onOpenChange,
  commands,
  title,
  onSaved,
}: SaveAsDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        {/* Remounted on each opening: a copy is a fresh question, and the
            title left behind by the last one is not its answer. */}
        <SaveAsForm
          key={open ? 'asking' : 'idle'}
          commands={commands}
          title={title}
          onOpenChange={onOpenChange}
          onSaved={onSaved}
        />
      </DialogContent>
    </Dialog>
  );
}

function SaveAsForm({
  commands,
  title,
  onOpenChange,
  onSaved,
}: Omit<SaveAsDialogProps, 'open'>) {
  const messages = useViewMessages();
  const fieldId = useId();
  const { can, state } = commands;
  // The scope a copy lands in when the user picks nothing: their own, unless
  // they may only publish. Both are offered either way — an option that is
  // simply missing reads as a scope this view cannot have.
  const [scope, setScope] = useState<ViewAudience>(
    can.createPersonal ? 'personal' : 'shared',
  );
  const [next, setNext] = useState(() =>
    messages.label('label.save-as.copy-title', { title }),
  );

  const named = next.trim();
  const offered = allows(can, scope);
  // Nothing here is gated on `state.blocked`: that is the open draft judged
  // for the audience it already sits in, and a copy is judged for the one it
  // is headed for — a shared dashboard referencing a personal view is
  // refused where its personal copy is taken. Only `saveAs` knows the target,
  // so it decides, and a refusal is shown below with the dialog still open.
  // What does stop the button is what is true of this form alone: a write in
  // flight, no title, or a scope this user may not create in.
  const stopped = state.pending || named.length === 0 || !offered;
  const submit = () => {
    void commands.saveAs({ title: named, scope }).then(saved => {
      if (!saved) return;
      onSaved?.(saved);
      onOpenChange(false);
    });
  };

  return (
    <>
      <DialogHeader>
        <DialogTitle>{messages.label('label.save-as.heading')}</DialogTitle>
        <DialogDescription>
          {messages.label('label.save-as.description')}
        </DialogDescription>
      </DialogHeader>

      <FieldGroup>
        <Field>
          <FieldLabel htmlFor={`${fieldId}-title`}>
            {messages.label('label.save.title')}
          </FieldLabel>
          <Input
            id={`${fieldId}-title`}
            value={next}
            aria-invalid={named.length === 0}
            onChange={event => setNext(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter' && !stopped) submit();
            }}
          />
        </Field>

        <Field data-slot="save-as-scope">
          <FieldTitle id={`${fieldId}-scope`}>
            {messages.label('label.save.audience')}
          </FieldTitle>
          <RadioGroup
            aria-labelledby={`${fieldId}-scope`}
            value={scope}
            onValueChange={value => {
              const picked = SCOPES.find(choice => choice.value === value);
              if (picked) setScope(picked.value);
            }}
          >
            {SCOPES.map(choice => {
              const permitted = allows(can, choice.value);
              return (
                <Field key={choice.value} orientation="horizontal">
                  {/* The radio renders as a span, not an input, so the
                      label beside it is pointed at rather than relied on:
                      `for` names nothing that is not a form control. */}
                  <RadioGroupItem
                    id={`${fieldId}-${choice.value}`}
                    value={choice.value}
                    disabled={!permitted}
                    aria-labelledby={`${fieldId}-${choice.value}-name`}
                    aria-describedby={`${fieldId}-${choice.value}-why`}
                  />
                  <FieldContent>
                    <FieldLabel
                      id={`${fieldId}-${choice.value}-name`}
                      htmlFor={`${fieldId}-${choice.value}`}
                    >
                      {messages.label(choice.labelKey)}
                    </FieldLabel>
                    <FieldDescription id={`${fieldId}-${choice.value}-why`}>
                      {/* The option stays on offer when it is not allowed:
                          a scope that vanished would read as one this kind of
                          view cannot have, rather than one this user cannot
                          make. */}
                      {messages.label(choice.descriptionKey)}
                      {permitted
                        ? ''
                        : ` ${messages.label('label.scope.no-permission')}`}
                    </FieldDescription>
                  </FieldContent>
                </Field>
              );
            })}
          </RadioGroup>
        </Field>

        {state.error && (
          <p role="alert" className="text-destructive text-sm">
            {messages.issue(state.error)}
          </p>
        )}
      </FieldGroup>

      <DialogFooter>
        <DialogClose render={<Button variant="outline" />}>
          {messages.label('label.dialog.cancel')}
        </DialogClose>
        <Button disabled={stopped} onClick={submit}>
          {state.pending && <Spinner data-icon="inline-start" />}
          {messages.label('label.save-as.submit')}
        </Button>
      </DialogFooter>
    </>
  );
}
