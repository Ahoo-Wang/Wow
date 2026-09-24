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
import type {
  SaveAbilities,
  SaveCommands,
  SaveCommandState,
} from '../react/index.js';
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
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldTitle,
} from './components/field.js';
import { Input } from './components/input.js';
import { RadioGroup, RadioGroupItem } from './components/radio-group.js';
import { Spinner } from './components/spinner.js';
import { useKindWord } from './kinds.js';
import type { MessageKey } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';
import { DialogContent } from './popups.js';
import type { FinalFocus } from './dashboard/commands.js';

/**
 * What the form needs of the save commands: which audiences a create may go
 * to, whether a write is out and what the last one said, and the create
 * itself. The open view's `SaveCommands` are one; a dashboard saving a view
 * it owns as a view of its own (D22 C) hands in another.
 */
export interface SaveAsCommands {
  can: Pick<SaveAbilities, 'createPersonal' | 'createShared'>;
  state: Pick<SaveCommandState, 'pending' | 'error'>;
  saveAs: SaveCommands['saveAs'];
}

export interface SaveAsDialogProps {
  open: boolean;
  onOpenChange(open: boolean): void;
  commands: SaveAsCommands;
  /** The view being copied; the title field opens on `{title} copy`. */
  title: string;
  /**
   * What the create is: a copy of a saved view, or the first save of one
   * made from nothing. The form is the same — a title and an audience — and
   * only its words change: a first save opens on the view's own name rather
   * than on "{title} copy", and its heading says "save" rather than "copy".
   * A `promote` is a dashboard's own analysis saved as a view (D22 C): it
   * opens on the panel's name, and `description` says what it becomes. A
   * `share` is the personal view a shared board's panel stands on, copied
   * for the board's readers (D22 B): it opens on the view's own name — the
   * copy lands in the shared group, apart from the original, and a panel
   * named after its view keeps its name on the board — and asks no
   * audience, since shared is the whole point.
   */
  intent?: 'copy' | 'first' | 'promote' | 'share';
  /** The sentence under the heading, where the intent's own does not say enough. */
  description?: string;
  /** The audience picked at first; the user's own when they may make one. */
  defaultScope?: ViewAudience;
  /** Called with the copy once the store took it. */
  onSaved?(instance: ViewInstance): void;
  /**
   * Where the keyboard goes as it closes (`FinalFocus`): the control that
   * asked — a menu's trigger, since the item that was pressed went with the
   * menu. Base UI's own choice when left out.
   */
  finalFocus?: FinalFocus;
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

/** The heading, the sentence under it and the button, by intent. */
const WORDS: Record<
  NonNullable<SaveAsDialogProps['intent']>,
  { heading: MessageKey; description: MessageKey; submit: MessageKey }
> = {
  copy: {
    heading: 'label.save-as.heading',
    description: 'label.save-as.description',
    submit: 'label.save-as.submit',
  },
  first: {
    heading: 'label.save.first-heading',
    description: 'label.save.first-description',
    submit: 'label.save.save',
  },
  promote: {
    heading: 'label.panel.save-owned.heading',
    description: 'label.panel.save-owned.description',
    submit: 'label.panel.save-owned.submit',
  },
  share: {
    heading: 'label.panel.copy-shared.heading',
    description: 'label.panel.copy-shared.description',
    submit: 'label.panel.copy-shared.submit',
  },
};

/** Whether a copy may be created in this audience. */
function allows(can: SaveAsCommands['can'], scope: ViewAudience): boolean {
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
  intent = 'copy',
  description,
  defaultScope,
  onSaved,
  finalFocus,
}: SaveAsDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent {...(finalFocus ? { finalFocus } : {})}>
        {/* Remounted on each opening: a copy is a fresh question, and the
            title left behind by the last one is not its answer. */}
        <SaveAsForm
          key={open ? 'asking' : 'idle'}
          commands={commands}
          title={title}
          intent={intent}
          description={description}
          defaultScope={defaultScope}
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
  intent = 'copy',
  description,
  defaultScope,
  onOpenChange,
  onSaved,
}: Omit<SaveAsDialogProps, 'open' | 'finalFocus'>) {
  const messages = useViewMessages();
  const fieldId = useId();
  const { can, state } = commands;
  // The scope a copy lands in when the user picks nothing: their own, unless
  // they may only publish. Both are offered either way — an option that is
  // simply missing reads as a scope this view cannot have.
  const asksAudience = intent !== 'share';
  const [scope, setScope] = useState<ViewAudience>(
    !asksAudience
      ? 'shared'
      : defaultScope && allows(can, defaultScope)
        ? defaultScope
        : can.createPersonal
          ? 'personal'
          : 'shared',
  );
  const first = intent !== 'copy';
  // A copy and a first save name the thing open — on a board, 仪表盘 (Q34).
  const word = useKindWord();
  const words = WORDS[intent];
  const [next, setNext] = useState(() =>
    first ? title : messages.label('label.save-as.copy-title', { title }),
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
        <DialogTitle>{messages.label(word(words.heading))}</DialogTitle>
        <DialogDescription>
          {description ?? messages.label(word(words.description))}
        </DialogDescription>
      </DialogHeader>

      <FieldGroup>
        {/* Both halves of the pair the forms rule asks for: `aria-invalid`
            marks the control, `data-invalid` marks the field around it, and
            without the second the label stayed in its ordinary colour beside
            a box that had turned. */}
        <Field data-invalid={named.length === 0 || undefined}>
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

        {asksAudience && (
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
        )}

        {/* The vendored component already carries `role="alert"`, so the
            refusal is still announced where it appears, and it renders
            nothing at all when there is nothing to say. */}
        {state.error && <FieldError>{messages.issue(state.error)}</FieldError>}
      </FieldGroup>

      <DialogFooter>
        <DialogClose render={<Button variant="outline" />}>
          {messages.label('label.dialog.cancel')}
        </DialogClose>
        <Button disabled={stopped} onClick={submit}>
          {/* The vendored spinner hardcodes an English `aria-label`; the name
              comes from the catalogue at the call site. */}
          {state.pending && (
            <Spinner
              data-icon="inline-start"
              aria-label={messages.label('label.status.loading')}
            />
          )}
          {messages.label(word(words.submit))}
        </Button>
      </DialogFooter>
    </>
  );
}
