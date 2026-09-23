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

import { useId, useRef, useState, type ReactNode, type RefObject } from 'react';
import { CheckIcon, PencilRulerIcon } from 'lucide-react';
import type { ViewInstance } from '../../model/index.js';
import { unsettled, type SaveCommands } from '../../react/index.js';
import { Button } from '../components/button.js';
import { Spinner } from '../components/spinner.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SaveAsDialog } from '../SaveAsDialog.js';
import { SharedSaveConfirm } from '../SaveActions.js';
import { RevertDialog } from '../ViewHeader.js';
import { AddMenu, type AddCommands } from './AddMenu.js';
import { cn } from 'cn';

export interface EditBarProps extends AddCommands {
  commands: SaveCommands;
  /** The board's title, which the shared-save question names. */
  title: string;
  /** The one-column reading: building there is renaming and removing (D22 J). */
  narrow: boolean;
  /** Leaves the building state; the board is then read as saved. */
  onLeave(): void;
  /** Told of the instance a 完成 wrote, as a save from the title bar tells. */
  onSaved?(instance: ViewInstance): void;
  /** The bar's own name, where the keyboard lands when a panel goes. */
  landingRef: RefObject<HTMLParagraphElement | null>;
  /** 「＋ 添加」, where the keyboard returns after an add from a dialog. */
  addRef: RefObject<HTMLButtonElement | null>;
  /** 「筛选 ＋」 beside 「＋ 添加」 (D22 G); a placement it is not, so narrow too. */
  addFilter?: ReactNode;
}

/**
 * The bar a board is built under (D22 A): 「正在编辑」 and what that means,
 * 「＋ 添加 ▾」, and the two ways out — 取消 puts back the saved board, 完成
 * saves it the way the title bar's Save does and leaves.
 *
 * 完成 is that save, not a second one: a shared board asks first, as Save
 * does (the question names the board, #1836); a board never saved asks for
 * its name and audience; and a write that did not land keeps the bar up,
 * with what became of it said under the title as ever (`WriteOutcome`).
 * Nothing to save is nothing to ask — 完成 just leaves.
 *
 * 取消 asks before it throws edits away, in the words ↺ uses for the same
 * loss; a board never saved has nothing to go back to, so it has no 取消 —
 * 完成 or leaving the view are the ways out.
 */
export function EditBar({
  commands,
  title,
  narrow,
  onLeave,
  onSaved,
  landingRef,
  addRef,
  add,
  canCreate,
  addFilter,
}: EditBarProps) {
  const messages = useViewMessages();
  const labelId = useId();
  const { state } = commands;
  const [confirming, setConfirming] = useState(false);
  const [naming, setNaming] = useState(false);
  const [reverting, setReverting] = useState(false);
  const busy = state.pending || unsettled(state.write);
  // The bar goes with the answer, and the keyboard goes back to 「编辑」,
  // which the workbench puts it on as the bar leaves (`onLeave`).
  const leftTo = useRef<HTMLHeadingElement | null>(null);

  const save = () =>
    void commands.save().then(made => {
      if (!made) return;
      onSaved?.(made);
      onLeave();
    });
  const done = () => {
    if (state.isNew) setNaming(true);
    else if (!state.dirty) onLeave();
    else if (state.audience === 'shared') setConfirming(true);
    else save();
  };
  const cancel = () => {
    if (state.dirty) setReverting(true);
    else onLeave();
  };

  return (
    <div
      data-slot="dashboard-edit-bar"
      role="region"
      aria-labelledby={labelId}
      className="bg-muted/50 flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2"
    >
      <p
        ref={landingRef}
        id={labelId}
        // Focus is sent here when a panel leaves the board, and never by Tab.
        tabIndex={-1}
        className="flex items-center gap-1.5 text-sm font-medium"
      >
        <PencilRulerIcon aria-hidden className="size-4" />
        {messages.label('label.dashboard.editing')}
      </p>
      <p className={cn('text-muted-foreground min-w-0 grow', TEXT_UI)}>
        {messages.label('label.dashboard.editing-hint')}
      </p>
      <div className="ml-auto flex items-center gap-2">
        {!narrow && (
          <AddMenu add={add} canCreate={canCreate} triggerRef={addRef} />
        )}
        {addFilter}
        {!state.isNew && (
          <Button
            data-slot="dashboard-cancel"
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={cancel}
          >
            {messages.label('label.dialog.cancel')}
          </Button>
        )}
        <Button
          data-slot="dashboard-done"
          size="sm"
          // A board whose draft cannot be saved says why in the status line
          // above; 完成 waits for it, and 取消 is still there.
          disabled={busy || (state.dirty && state.hasErrors && !state.isNew)}
          onClick={done}
        >
          {state.pending ? (
            <Spinner
              data-icon="inline-start"
              aria-label={messages.label('label.status.loading')}
            />
          ) : (
            <CheckIcon data-icon="inline-start" />
          )}
          {messages.label('label.dashboard.done')}
        </Button>
      </div>

      <SharedSaveConfirm
        open={confirming}
        title={title}
        onOpenChange={setConfirming}
        onConfirm={save}
      />
      <SaveAsDialog
        open={naming}
        onOpenChange={setNaming}
        commands={commands}
        title={title}
        intent="first"
        onSaved={made => {
          onSaved?.(made);
          onLeave();
        }}
      />
      <RevertDialog
        open={reverting}
        onOpenChange={setReverting}
        onConfirm={() => {
          commands.revert();
          onLeave();
        }}
        landing={leftTo}
      />
    </div>
  );
}
