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
import { DownloadIcon } from 'lucide-react';
import type { RecordExportController } from '../react/index.js';
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
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { Spinner } from './components/spinner.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import {
  DialogContent,
  DropdownMenuContent,
  TooltipContent,
} from './popups.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';

export interface ExportMenuProps {
  /** The three scopes and the one run; see `useRecordExport`. */
  control: RecordExportController;
}

/**
 * Taking the result away: what is picked, what is on screen, or everything
 * the applied conditions match.
 *
 * One bordered icon button at the end of the toolbar's right-hand block
 * (D12 Ⅳ), because exporting is a display facility like the columns and the
 * sort — it changes nothing about the view and nothing about the records.
 *
 * Each item carries its own count, and the picked scope is **absent** rather
 * than disabled while nothing is picked (D4). "All" says the conditions are
 * in force, since it is the one scope whose rows are not the ones on screen —
 * without that it reads as "everything there is".
 *
 * The menu is also where a long export lives: it stays open while the pages
 * come in, showing how far it has got and the way to stop, and it refuses to
 * be dismissed until it is over — closing the one place the cancel lives
 * would leave a run nobody could reach. The two immediate scopes never take
 * it over: their rows are already in hand.
 */
export function ExportMenu({ control }: ExportMenuProps) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  const { scopes, progress } = control;
  // Only the whole-result export takes time. Holding the menu open for the
  // other two would flash a progress line for one frame.
  const busy = control.running === 'all';

  return (
    <>
      <DropdownMenu
        // Open while it is being used, and while a run of its own is in
        // flight — but never behind the question, which is modal and would
        // leave an inert menu showing through it.
        open={(open || busy) && control.overLimit === null}
        onOpenChange={next => {
          if (!busy) setOpen(next);
        }}
      >
        <Tooltip>
          <TooltipTrigger
            render={
              <DropdownMenuTrigger
                data-control="export"
                aria-label={messages.label('label.export.title')}
                render={<Button variant="outline" size="icon-sm" />}
              />
            }
          >
            <DownloadIcon />
          </TooltipTrigger>
          <TooltipContent>
            {messages.label('label.export.title')}
          </TooltipContent>
        </Tooltip>
        <DropdownMenuContent align="end" data-slot="export-menu">
          {busy ? (
            <div
              data-slot="export-progress"
              className="flex flex-col gap-1 px-1.5 py-1"
            >
              {/* The spinner is the `status` here — it is what the
                  catalogue names "Exporting" — and the count beside it is
                  announced as it changes rather than being a second status
                  region saying the same thing. */}
              <span className="text-muted-foreground flex items-center gap-1.5 text-sm">
                <Spinner
                  aria-label={messages.label('label.export.running')}
                  className="size-4"
                />
                <span data-slot="export-count" aria-live="polite">
                  {progressText(progress, messages)}
                </span>
              </span>
              <Button
                variant="outline"
                size="xs"
                data-slot="export-cancel"
                onClick={control.cancel}
              >
                {messages.label('label.export.cancel')}
              </Button>
            </div>
          ) : (
            <DropdownMenuGroup>
              {/* Inside a group, which is what a label in this menu has to
                  be part of — and what makes the three scopes read as the
                  one question they answer. */}
              <DropdownMenuLabel>
                {messages.label('label.export.title')}
              </DropdownMenuLabel>
              {scopes.selected !== undefined && (
                <DropdownMenuItem
                  data-scope="selected"
                  onClick={() => {
                    setOpen(false);
                    control.run('selected');
                  }}
                >
                  {messages.label('label.export.selected', {
                    count: scopes.selected,
                  })}
                </DropdownMenuItem>
              )}
              <DropdownMenuItem
                data-scope="page"
                onClick={() => {
                  setOpen(false);
                  control.run('page');
                }}
              >
                {messages.label('label.export.page', { count: scopes.page })}
              </DropdownMenuItem>
              <DropdownMenuItem
                data-scope="all"
                // Kept open: this one either starts fetching, and the menu
                // becomes its progress, or stops to ask and the dialog takes
                // over. Closing here would do it behind the user's back.
                closeOnClick={false}
                onClick={() => control.run('all')}
              >
                {scopes.all === null
                  ? messages.label('label.export.all-unknown')
                  : messages.label('label.export.all', { count: scopes.all })}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      <OverLimitDialog control={control} onAnswered={() => setOpen(false)} />
    </>
  );
}

/**
 * The count, put to the user before anything is fetched.
 *
 * It says what the file will hold rather than only how many there are: the
 * ceiling still applies to the answer, so "export all" over the limit is an
 * export of the first {max}, and a dialog that hid that would be asking the
 * user to agree to something else.
 */
function OverLimitDialog({
  control,
  onAnswered,
}: {
  control: RecordExportController;
  onAnswered(): void;
}) {
  const messages = useViewMessages();
  const asked = control.overLimit;
  return (
    <Dialog
      open={asked !== null}
      onOpenChange={next => {
        // Dismissed — by Escape, by the overlay, by "cancel" — is an answer
        // of "no": the question is dropped and nothing is fetched.
        if (!next) {
          control.cancel();
          onAnswered();
        }
      }}
    >
      {asked && (
        <DialogContent data-slot="export-over-limit">
          <DialogHeader>
            <DialogTitle>
              {messages.label('label.export.over-limit', {
                count: asked.count,
              })}
            </DialogTitle>
            <DialogDescription>
              {messages.label('label.export.over-limit-body', {
                count: asked.count,
                max: asked.max,
              })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>
              {messages.label('label.export.cancel')}
            </DialogClose>
            <Button
              data-slot="export-confirm"
              onClick={() => {
                onAnswered();
                control.run('all', { force: true });
              }}
            >
              {messages.label('label.export.over-limit-confirm', {
                max: asked.max,
              })}
            </Button>
          </DialogFooter>
        </DialogContent>
      )}
    </Dialog>
  );
}

/** How far it has got, with the total where the source reports one. */
function progressText(
  progress: RecordExportController['progress'],
  messages: MessageFormatters,
): string {
  const fetched = progress?.fetched ?? 0;
  const total = progress?.total;
  return total === undefined
    ? messages.label('label.export.progress-unknown', { fetched })
    : messages.label('label.export.progress', { fetched, total });
}
