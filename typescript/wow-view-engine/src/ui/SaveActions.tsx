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

import { useEffect, useState, type ReactNode } from 'react';
import {
  CheckIcon,
  ChevronDownIcon,
  CopyIcon,
  RotateCcwIcon,
  SaveIcon,
} from 'lucide-react';
import { unsettled, type SaveCommands } from '../react/index.js';
import { cn } from 'cn';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './components/alert-dialog.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import { IconButton, IconTooltip } from './IconButton.js';
import { ButtonGroup } from './components/button-group.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { Spinner } from './components/spinner.js';
import { useViewMessages } from './MessagesProvider.js';
import { AlertDialogContent, DropdownMenuContent } from './popups.js';
import { SaveAsDialog } from './SaveAsDialog.js';
import type { ViewWriteCallbacks } from './WriteOutcome.js';

/**
 * How long the button says a save landed. Long enough to be read, short
 * enough that it never describes a state that has since moved on.
 */
const SAVED_FOR = 2500;

/**
 * Two of the group, and only two: this button group saves and copies, so
 * those are the two landings it can report. Renaming and deleting are the
 * view manager's, and a recovered write is reported by the line that offered
 * the recovery — `WriteOutcome`, where {@link ViewWriteCallbacks} is
 * declared. The whole group used to be threaded through here as well, three
 * props of it never read: a host wiring `onDeleted` to this component would
 * have waited for a call that could not come.
 */
export interface SaveActionsProps extends Pick<
  ViewWriteCallbacks,
  'onSaved' | 'onCreated'
> {
  commands: SaveCommands;
  title: string;
}

/**
 * Saving the open view: one button for the thing to do now, and a menu for
 * the rest.
 *
 * Which button that is, is decided by permission rather than by state: a view
 * the user may write to saves, one they may not copies, and a view they can
 * do neither to shows no group at all. Nothing here is a disabled button for
 * a permission the user does not hold — a control that can never be pressed
 * teaches nothing and costs a click to find out.
 *
 * Commands resolve rather than reject, so nothing here is wrapped in a
 * try/catch: an outcome that needs a decision lands in `commands.state` and
 * `WriteOutcome` keeps it on screen until the user answers it.
 */
export function SaveActions({
  commands,
  title,
  onSaved,
  onCreated,
}: SaveActionsProps) {
  const messages = useViewMessages();
  const [copying, setCopying] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const { can, state } = commands;

  // The moment a save landed, said only as long as it is worth saying. What
  // is held is the moment that has run out rather than a flag, so the effect
  // sets nothing on its way in — it only arms a timer, and the word is
  // derived from comparing the two timestamps. Two saves in a row are two
  // moments, and the second one starts the count again.
  const [spent, setSpent] = useState<number | null>(null);
  const { lastSavedAt } = state;
  useEffect(() => {
    if (lastSavedAt === null) return;
    const timer = setTimeout(() => setSpent(lastSavedAt), SAVED_FOR);
    return () => clearTimeout(timer);
  }, [lastSavedAt]);

  // Saying "Saved" over a draft that has moved on since would be a lie; a new
  // write clears the moment on its way out, and has its own thing to say.
  const saved =
    lastSavedAt !== null &&
    spent !== lastSavedAt &&
    !state.dirty &&
    !state.pending;

  // An outcome the engine is still answering for — `writes.ts`'s rule, asked
  // rather than restated. Until it is settled, every way of undoing or
  // re-putting the draft is a second write over a first one whose result
  // nobody knows yet.
  const stillAnswering = unsettled(state.write);

  // A view made from nothing has no instance to write over: its first save
  // is a create, so the primary button opens the same form a copy does —
  // a title and an audience — under a heading that says "save". Which
  // audiences it offers is the dialog's own question (`can.createPersonal`
  // / `createShared`); what puts the button here is that at least one is.
  const first = state.isNew;
  const copy = can.saveAs && (
    <SaveAsDialog
      open={copying}
      onOpenChange={setCopying}
      commands={commands}
      title={title}
      intent={first ? 'first' : 'copy'}
      onSaved={saved => {
        onSaved?.(saved);
        onCreated?.(saved);
      }}
    />
  );

  // Save when it is allowed, else a copy. Nothing to write means nothing
  // here: the way back lives on the "edited" mark ({@link UnsavedMark}),
  // which the header draws whether or not a save is on offer.
  if (!can.save && !can.saveAs) return null;

  // Not for a first save: saving *is* creating, and a second way to create
  // the same view would be the same question asked twice.
  const menuSaveAs = can.save && can.saveAs && !first;
  // What the primary button does. A first save asks its two questions
  // first; a save in place writes; and without a save permission at all
  // it copies.
  const saves = can.save && !first;
  // Spelled out rather than taken from `blocked`, because the outcomes are
  // not one thing. A write in flight stops everything. An unknown outcome
  // must be settled first — the engine refuses the next write anyway. A
  // conflict refuses a blind save: the user has been asked which version
  // wins and has not answered. A refusal, though, is over — the store never
  // took it, nothing is pending, and trying again with a corrected draft is
  // exactly what the user should do next.
  const stopped = state.pending || stillAnswering;
  // `hasErrors` judges the draft *for the audience it already sits in*, which
  // is the question a save in place asks. The primary button asks a different
  // one when the user may not write here: Save As creates a copy somewhere
  // else, and a config the current audience refuses can be perfectly valid
  // there — a shared dashboard naming a personal view is the standard case.
  // So the dialog judges its own target, and this button does not judge it
  // for it.
  const blockedDraft = can.save && state.hasErrors;
  // A save in place over a shared view is everyone's view changing under
  // them, one click and no way back but the next save (2026-09-23 audit).
  // So that one asks first, naming the view; a personal view is the
  // author's alone, and saves on the press as it always did.
  const write = () =>
    void commands.save().then(made => made && onSaved?.(made));

  return (
    <div data-slot="save-actions" className="flex items-center gap-2">
      <ButtonGroup aria-label={messages.label('label.save.group')}>
        <Button
          variant="outline"
          size="sm"
          // A save with nothing to save is the one disabled button here: the
          // permission is held, so the button belongs on screen, and the
          // reason it does nothing is the state the user can see.
          disabled={stopped || blockedDraft || (saves && !state.dirty)}
          onClick={() => {
            if (!saves) setCopying(true);
            else if (state.audience === 'shared') setConfirming(true);
            else write();
          }}
        >
          <PrimaryFace
            saving={state.pending}
            saved={saved}
            writes={saves || first}
          />
        </Button>

        {menuSaveAs && (
          <DropdownMenu>
            <IconTooltip
              label={messages.label('label.header.more')}
              render={
                <DropdownMenuTrigger
                  render={
                    <Button
                      variant="outline"
                      size="icon-sm"
                      // Nothing in the menu may run while a write is in
                      // flight: reverting mid-save would leave the old config
                      // as a dirty draft over a baseline that has just become
                      // the new one. Nor while an outcome is unsettled: Retry
                      // or Keep mine is still to land, and a revert taken
                      // first would be undone by the write the user is about
                      // to choose.
                      disabled={stopped}
                    />
                  }
                />
              }
            >
              <ChevronDownIcon />
            </IconTooltip>
            <DropdownMenuContent align="end">
              <DropdownMenuGroup>
                <DropdownMenuItem onClick={() => setCopying(true)}>
                  <CopyIcon />
                  {messages.label('label.save.save-as')}
                </DropdownMenuItem>
              </DropdownMenuGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </ButtonGroup>

      {/* Announced rather than only shown: the word appears on the control
          the user just pressed, which a screen reader does not re-read. */}
      {saved && (
        <span role="status" className="sr-only">
          {messages.label('label.save.saved-announce')}
        </span>
      )}

      {copy}
      <SharedSaveConfirm
        open={confirming}
        title={title}
        onOpenChange={setConfirming}
        onConfirm={write}
      />
    </div>
  );
}

/**
 * 「这会更新所有人看到的「X」」: the one question before a shared view is
 * written over. An `AlertDialog`, as every confirmation here is: Base UI
 * holds it open under a stray click and keeps focus on the two answers.
 * Not destructive in tone — nothing is lost that the author did not mean
 * to change — but said, because the people it changes for are not here.
 */
export function SharedSaveConfirm({
  open,
  title,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  title: string;
  onOpenChange(open: boolean): void;
  onConfirm(): void;
}) {
  const messages = useViewMessages();
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent data-slot="shared-save-confirm">
        <AlertDialogHeader>
          <AlertDialogTitle>
            {messages.label('label.save.shared-heading')}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {messages.label('label.save.shared-description', { title })}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>
            {messages.label('label.dialog.cancel')}
          </AlertDialogCancel>
          {/* The registry's action is a plain button: it closes nothing by
              itself, so the answer closes the question and then writes. */}
          <AlertDialogAction
            onClick={() => {
              onOpenChange(false);
              onConfirm();
            }}
          >
            {messages.label('label.save.shared-confirm')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * The word on the primary button, which is the first thing it gives up.
 *
 * Below `@md` of the title bar (`@container/header`, `ViewHeader`) the
 * button is its icon: at a phone's width the bar's irreducible parts —
 * icons, the audience tag and these commands — added up to more than the
 * column, and Save is the one command a narrow screen most needs to keep.
 * `sr-only` takes the word out of the layout and leaves it in the
 * accessible name, so the button is still "Save" to a screen reader and
 * still `label.save.save` to a test; only the pixels go.
 */
function Face({ children }: { children: ReactNode }) {
  return <span className="@max-md/header:sr-only">{children}</span>;
}

/** What the primary button wears right now: saving, saved, or its own name. */
function PrimaryFace({
  saving,
  saved,
  writes,
}: {
  saving: boolean;
  saved: boolean;
  /** True when the button saves in place, false when it copies. */
  writes: boolean;
}) {
  const messages = useViewMessages();
  if (saving)
    return (
      <>
        {/* The vendored spinner hardcodes an English `aria-label`; the name
            comes from the catalogue at the call site. */}
        <Spinner
          data-icon="inline-start"
          aria-label={messages.label('label.status.loading')}
        />
        <Face>{messages.label('label.save.saving')}</Face>
      </>
    );
  if (saved)
    return (
      <>
        <CheckIcon data-icon="inline-start" />
        <Face>{messages.label('label.save.saved')}</Face>
      </>
    );
  return writes ? (
    <>
      <SaveIcon data-icon="inline-start" />
      <Face>{messages.label('label.save.save')}</Face>
    </>
  ) : (
    <>
      <CopyIcon data-icon="inline-start" />
      <Face>{messages.label('label.save.save-as')}</Face>
    </>
  );
}

/**
 * The "edited" mark, with the way back on it.
 *
 * Revert used to be the second item of the Save menu — two presses deep,
 * under a chevron, for the one command that answers the state this mark
 * announces. The user's call (2026-09-21): the command belongs beside the
 * fact it undoes. So the mark is the word and, when the saved config can be
 * put back, one icon button after it, named and tooltipped «Revert»; a
 * never-saved view has nothing to go back to and shows the word alone
 * (`ViewHeader` draws that one). The Save menu keeps only Save as.
 */
export function UnsavedMark({
  commands,
  lookOnly = false,
}: {
  commands: SaveCommands;
  /**
   * Whether only how the result is drawn differs from what was saved
   * (`presentationOnlyEdits`, D23 Q15): the mark says 「只改了展示」, since
   * switching 表格｜图表 is a reader changing how they look, and a bare
   * 「已修改」 read as a question edited.
   */
  lookOnly?: boolean;
}) {
  const messages = useViewMessages();
  const revert = messages.label('label.save.revert');
  return (
    <Badge
      variant="outline"
      data-slot="view-unsaved"
      data-look-only={lookOnly || undefined}
      // The word keeps its own padding; the button after it sits in the
      // badge's right padding so the pill does not grow a second box.
      className={cn('shrink-0', commands.can.revert && 'gap-0.5 pr-0.5')}
    >
      {messages.label(
        lookOnly ? 'label.header.unsaved-look' : 'label.header.unsaved',
      )}
      {commands.can.revert && (
        <IconButton
          type="button"
          variant="ghost"
          size="icon-xs"
          data-slot="view-revert"
          label={revert}
          // Taking the edits back while the same edits are being written
          // would leave what was reverted from as the baseline and what was
          // reverted to as a dirty draft over it. An unsettled outcome is
          // the same story one step earlier: Retry or Keep mine has yet to
          // land.
          disabled={commands.state.pending || unsettled(commands.state.write)}
          onClick={commands.revert}
        >
          <RotateCcwIcon />
        </IconButton>
      )}
    </Badge>
  );
}
