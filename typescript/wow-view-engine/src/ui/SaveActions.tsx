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
import type { SaveCommands } from '../react/index.js';
import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from './components/alert.js';
import { Button } from './components/button.js';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './components/dialog.js';
import { Field, FieldGroup, FieldLabel } from './components/field.js';
import { Input } from './components/input.js';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { Spinner } from './components/spinner.js';

export interface SaveActionsProps {
  commands: SaveCommands;
  title: string;
  /** Called with the instance a save produced, so a host can open it. */
  onSaved?(instance: ViewInstance): void;
  onDeleted?(): void;
}

const SCOPES: { label: string; value: Exclude<ViewScope, 'system'> }[] = [
  { label: 'Only me', value: 'personal' },
  { label: 'Everyone', value: 'shared' },
];

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
  onDeleted,
}: SaveActionsProps) {
  const [copyOpen, setCopyOpen] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

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
        Save
      </Button>

      <Button
        variant="outline"
        size="sm"
        disabled={!commands.can.saveAs}
        onClick={() => setCopyOpen(true)}
      >
        Save as
      </Button>

      <Button
        variant="outline"
        size="sm"
        disabled={!commands.can.rename}
        onClick={() => setRenameOpen(true)}
      >
        Rename
      </Button>

      <Button
        variant="outline"
        size="sm"
        disabled={!commands.can.delete}
        onClick={() => setDeleteOpen(true)}
      >
        <TrashIcon data-icon="inline-start" />
        Delete
      </Button>

      <TitleDialog
        open={copyOpen}
        onOpenChange={setCopyOpen}
        heading="Save as a new view"
        description="The view you are looking at stays as it is."
        initialTitle={`${title} copy`}
        withScope
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
        heading="Rename this view"
        description="Only the title changes; the conditions stay."
        initialTitle={title}
        onSubmit={next => {
          void commands.rename(next);
          setRenameOpen(false);
        }}
      />

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete this view?</DialogTitle>
            <DialogDescription>
              It disappears for everyone who can see it. This cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>
              Keep it
            </DialogClose>
            <Button
              variant="destructive"
              onClick={() => {
                void commands.delete().then(done => done && onDeleted?.());
                setDeleteOpen(false);
              }}
            >
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <WriteOutcome commands={commands} />
    </div>
  );
}

function WriteOutcome({ commands }: { commands: SaveCommands }) {
  const { write, error } = commands.state;

  if (write?.kind === 'conflict') {
    return (
      <Alert variant="destructive" className="w-full">
        <AlertTitle>Someone else saved this view first</AlertTitle>
        <AlertDescription>
          Take their version and lose your edits, or write yours over theirs.
        </AlertDescription>
        <AlertAction>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void commands.resolveConflict('reload')}
          >
            Take theirs
          </Button>
          <Button
            size="sm"
            onClick={() => void commands.resolveConflict('overwrite')}
          >
            Keep mine
          </Button>
        </AlertAction>
      </Alert>
    );
  }

  if (write?.kind === 'unknown') {
    return (
      <Alert className="w-full">
        <AlertTitle>The result never came back</AlertTitle>
        <AlertDescription>
          It may well have been saved. Retrying asks again for the same write
          rather than making a second one.
        </AlertDescription>
        <AlertAction>
          <Button variant="outline" size="sm" onClick={commands.abandon}>
            Leave it
          </Button>
          <Button size="sm" onClick={() => void commands.retry()}>
            Retry
          </Button>
        </AlertAction>
      </Alert>
    );
  }

  const refused = write?.kind === 'rejected' ? write.issue : error;
  return refused ? <IssueAlert issue={refused} /> : null;
}

function IssueAlert({ issue }: { issue: Issue }) {
  return (
    <Alert variant="destructive" className="w-full">
      <AlertTitle>{issue.code}</AlertTitle>
      {issue.params?.reason !== undefined && (
        <AlertDescription>{String(issue.params.reason)}</AlertDescription>
      )}
    </Alert>
  );
}

function TitleDialog({
  open,
  onOpenChange,
  heading,
  description,
  initialTitle,
  withScope,
  onSubmit,
}: {
  open: boolean;
  onOpenChange(open: boolean): void;
  heading: string;
  description: string;
  initialTitle: string;
  withScope?: boolean;
  onSubmit(title: string, scope: Exclude<ViewScope, 'system'>): void;
}) {
  const [title, setTitle] = useState(initialTitle);
  const [scope, setScope] = useState<Exclude<ViewScope, 'system'>>('personal');

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
          <DialogTitle>{heading}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <FieldGroup>
          <Field>
            <FieldLabel htmlFor="view-title">Title</FieldLabel>
            <Input
              id="view-title"
              value={title}
              aria-invalid={title.trim().length === 0}
              onChange={event => setTitle(event.target.value)}
            />
          </Field>
          {withScope && (
            <Field>
              <FieldLabel htmlFor="view-scope">Who can see it</FieldLabel>
              <Select
                items={SCOPES}
                value={scope}
                onValueChange={value => {
                  if (value === 'personal' || value === 'shared')
                    setScope(value);
                }}
              >
                <SelectTrigger id="view-scope">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {SCOPES.map(item => (
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
            Cancel
          </DialogClose>
          <Button
            disabled={title.trim().length === 0}
            onClick={() => onSubmit(title.trim(), scope)}
          >
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
