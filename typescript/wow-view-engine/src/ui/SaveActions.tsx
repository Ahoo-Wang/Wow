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
import { SaveIcon, TrashIcon } from 'lucide-react';
import type { Issue, ViewInstance, ViewScope } from '../model/index.js';
import type { WriteAction } from '../runtime/index.js';
import type { SaveAbilities, SaveCommands } from '../react/index.js';
import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from './components/alert.js';
import { Button } from './components/button.js';
import { useViewMessages } from './MessagesProvider.js';
import {
  Dialog,
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './components/dialog.js';
import { Field, FieldGroup, FieldLabel } from './components/field.js';
import { Input } from './components/input.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { Spinner } from './components/spinner.js';
import { DialogContent, SelectContent } from './popups.js';

export interface SaveActionsProps {
  commands: SaveCommands;
  title: string;
  /** Called with the instance a save produced, so a host can open it. */
  onSaved?(instance: ViewInstance): void;
  /** Called with the renamed instance, so a host can refresh its list. */
  onRenamed?(instance: ViewInstance): void;
  onDeleted?(): void;
  /** Called when a recovered write (retry, overwrite, reload) landed. */
  onRecovered?(action: WriteAction): void;
}

type SaveScope = Exclude<ViewScope, 'system'>;

/**
 * The audiences, as keys rather than words: the dialog resolves them through
 * the catalogue, so an application rewords or translates them like everything
 * else this package says.
 */
const SCOPES: { labelKey: string; value: SaveScope }[] = [
  { labelKey: 'label.scope.only-me', value: 'personal' },
  { labelKey: 'label.scope.everyone', value: 'shared' },
];

/** The scopes this user may create in, in the order they are offered. */
function scopesOf(can: SaveAbilities): ScopeChoice[] {
  return SCOPES.filter(scope =>
    scope.value === 'personal' ? can.createPersonal : can.createShared,
  );
}

/** One audience on offer, still unworded. */
type ScopeChoice = { labelKey: string; value: SaveScope };

/**
 * Save, save as, rename and delete, plus the recovery a write needs when it
 * did not simply succeed.
 *
 * Commands resolve rather than reject, so nothing here is wrapped in a
 * try/catch: an outcome that needs a decision stays on screen until the user
 * makes one.
 */
export function SaveActions({
  commands,
  title,
  onSaved,
  onRenamed,
  onDeleted,
  onRecovered,
}: SaveActionsProps) {
  const [copyOpen, setCopyOpen] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const messages = useViewMessages();

  /**
   * A recovered write lands like the original one would have: a recovered
   * create or save opens what it made, a rename keeps the instance current,
   * a delete lets the view go. Every landing also reports through
   * onRecovered, which is where a host that wires nothing else stays fresh.
   * A reload is not a landing: the server's state was adopted, so nothing is
   * opened or let go of — the list may still have moved.
   */
  const notify = (
    action: WriteAction | undefined,
    instance: ViewInstance | null,
    reloaded = false,
  ) => {
    if (!action) return;
    if (reloaded) {
      onRecovered?.(action);
      return;
    }
    switch (action) {
      case 'delete':
        onDeleted?.();
        onRecovered?.(action);
        return;
      case 'rename':
        if (instance) onRenamed?.(instance);
        onRecovered?.(action);
        return;
      default:
        if (instance) onSaved?.(instance);
        onRecovered?.(action);
    }
  };

  return (
    <div data-slot="save-actions" className="flex flex-wrap items-center gap-2">
      <Button
        size="sm"
        disabled={!commands.can.save || commands.state.pending}
        onClick={() => {
          void commands.save().then(saved => saved && onSaved?.(saved));
        }}
      >
        {commands.state.pending ? (
          <Spinner data-icon="inline-start" />
        ) : (
          <SaveIcon data-icon="inline-start" />
        )}
        {messages.label('label.save.save')}
      </Button>

      <Button
        variant="outline"
        size="sm"
        disabled={!commands.can.saveAs || commands.state.pending}
        onClick={() => setCopyOpen(true)}
      >
        {messages.label('label.save.save-as')}
      </Button>

      <Button
        variant="outline"
        size="sm"
        disabled={!commands.can.rename || commands.state.pending}
        onClick={() => setRenameOpen(true)}
      >
        {messages.label('label.save.rename')}
      </Button>

      <Button
        variant="outline"
        size="sm"
        disabled={!commands.can.delete || commands.state.pending}
        onClick={() => setDeleteOpen(true)}
      >
        <TrashIcon data-icon="inline-start" />
        {messages.label('label.save.delete')}
      </Button>

      <TitleDialog
        open={copyOpen}
        onOpenChange={setCopyOpen}
        headingKey="label.save-as.heading"
        descriptionKey="label.save-as.description"
        initialTitle={`${title} copy`}
        scopes={scopesOf(commands.can)}
        onSubmit={(next, scope) => {
          void commands
            .saveAs({ title: next, scope })
            .then(saved => saved && onSaved?.(saved));
          setCopyOpen(false);
        }}
      />

      <TitleDialog
        open={renameOpen}
        onOpenChange={setRenameOpen}
        headingKey="label.rename.heading"
        descriptionKey="label.rename.description"
        initialTitle={title}
        onSubmit={next => {
          void commands
            .rename(next)
            .then(instance => instance && onRenamed?.(instance));
          setRenameOpen(false);
        }}
      />

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{messages.label('label.delete.confirm')}</DialogTitle>
            <DialogDescription>
              {messages.label('label.delete.consequence')}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>
              {messages.label('label.delete.keep')}
            </DialogClose>
            <Button
              variant="destructive"
              onClick={() => {
                void commands.delete().then(done => done && onDeleted?.());
                setDeleteOpen(false);
              }}
            >
              {messages.label('label.save.delete')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <WriteOutcome commands={commands} notify={notify} />
    </div>
  );
}

function WriteOutcome({
  commands,
  notify,
}: {
  commands: SaveCommands;
  /** Reports a recovered write, by the action it carried and what it made. */
  notify(
    action: WriteAction | undefined,
    instance: ViewInstance | null,
    reloaded?: boolean,
  ): void;
}) {
  const { write, error } = commands.state;
  const messages = useViewMessages();

  if (write?.kind === 'conflict') {
    return (
      <Alert variant="destructive" className="w-full">
        <AlertTitle>{messages.label('label.write.conflict')}</AlertTitle>
        <AlertDescription>
          {messages.label('label.conflict.choice')}
        </AlertDescription>
        <AlertAction>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              const action = write.payload.action;
              void commands
                .resolveConflict('reload')
                .then(
                  result =>
                    result.landed && notify(action, result.instance, true),
                );
            }}
          >
            {messages.label('label.conflict.theirs')}
          </Button>
          <Button
            size="sm"
            onClick={() => {
              const action = write.payload.action;
              void commands
                .resolveConflict('overwrite')
                .then(
                  result => result.landed && notify(action, result.instance),
                );
            }}
          >
            {messages.label('label.conflict.mine')}
          </Button>
        </AlertAction>
      </Alert>
    );
  }

  if (write?.kind === 'unknown') {
    return (
      <Alert className="w-full">
        <AlertTitle>{messages.label('label.write.unknown')}</AlertTitle>
        <AlertDescription>
          {messages.label('label.unknown.consequence')}
        </AlertDescription>
        <AlertAction>
          <Button variant="outline" size="sm" onClick={commands.abandon}>
            {messages.label('label.unknown.leave')}
          </Button>
          <Button
            size="sm"
            onClick={() => {
              const action = write.payload.action;
              void commands
                .retry()
                .then(
                  result => result.landed && notify(action, result.instance),
                );
            }}
          >
            {messages.label('label.unknown.retry')}
          </Button>
        </AlertAction>
      </Alert>
    );
  }

  const refused = write?.kind === 'rejected' ? write.issue : error;
  return refused ? <IssueAlert issue={refused} /> : null;
}

function IssueAlert({ issue }: { issue: Issue }) {
  const messages = useViewMessages();
  return (
    <Alert variant="destructive" className="w-full">
      <AlertTitle>{messages.issue(issue)}</AlertTitle>
      {issue.params?.reason !== undefined && (
        <AlertDescription>{String(issue.params.reason)}</AlertDescription>
      )}
    </Alert>
  );
}

function TitleDialog({
  open,
  onOpenChange,
  headingKey,
  descriptionKey,
  initialTitle,
  scopes,
  onSubmit,
}: {
  open: boolean;
  onOpenChange(open: boolean): void;
  headingKey: string;
  descriptionKey: string;
  initialTitle: string;
  /** Audiences to offer; a rename asks for none and passes nothing. */
  scopes?: ScopeChoice[];
  onSubmit(title: string, scope: SaveScope): void;
}) {
  const [title, setTitle] = useState(initialTitle);
  // Null until the user picks: the default is the first scope on offer, and
  // what is on offer follows the permissions, which arrive with the view.
  const [picked, setPicked] = useState<SaveScope | null>(null);
  const messages = useViewMessages();
  const offered = (scopes ?? []).map(item => ({
    label: messages.label(item.labelKey),
    value: item.value,
  }));
  const scope =
    picked !== null && offered.some(item => item.value === picked)
      ? picked
      : (offered[0]?.value ?? 'personal');

  return (
    <Dialog
      open={open}
      onOpenChange={next => {
        if (next) setTitle(initialTitle);
        onOpenChange(next);
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{messages.label(headingKey)}</DialogTitle>
          <DialogDescription>
            {messages.label(descriptionKey)}
          </DialogDescription>
        </DialogHeader>
        <FieldGroup>
          <Field>
            <FieldLabel htmlFor="view-title">
              {messages.label('label.save.title')}
            </FieldLabel>
            <Input
              id="view-title"
              value={title}
              aria-invalid={title.trim().length === 0}
              onChange={event => setTitle(event.target.value)}
            />
          </Field>
          {offered.length > 0 && (
            <Field>
              <FieldLabel htmlFor="view-scope">
                {messages.label('label.save.audience')}
              </FieldLabel>
              <Select
                items={offered}
                value={scope}
                onValueChange={value => {
                  if (value === 'personal' || value === 'shared')
                    setPicked(value);
                }}
              >
                <SelectTrigger id="view-scope">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {offered.map(item => (
                      <SelectItem key={item.value} value={item.value}>
                        {item.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
          )}
        </FieldGroup>
        <DialogFooter>
          <DialogClose render={<Button variant="outline" />}>
            {messages.label('label.dialog.cancel')}
          </DialogClose>
          <Button
            disabled={title.trim().length === 0}
            onClick={() => onSubmit(title.trim(), scope)}
          >
            {messages.label('label.save.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
