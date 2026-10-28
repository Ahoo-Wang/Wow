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

import { useEffect, useState } from 'react';
import type { BoardChoice, SaveCommands } from '../../react/index.js';
import { Button } from '../components/button.js';
import {
  Dialog,
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/dialog.js';
import { Spinner } from '../components/spinner.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { DialogContent } from '../kit/popups.js';

/**
 * 「放进仪表盘…」: the boards this reader may change, and a press on
 * one puts the open view on it as one more panel (`addToDashboard`) — the
 * board's own save, at the first free place of its first tab. The boards
 * are read as the dialog opens; one that cannot take the view says why in
 * the dialog, which stays open for another pick.
 */
export function AddToDashboardDialog({
  open,
  onOpenChange,
  commands,
  title,
  onAdded,
}: {
  open: boolean;
  onOpenChange(open: boolean): void;
  commands: Required<Pick<SaveCommands, 'boards' | 'addToDashboard'>> &
    Pick<SaveCommands, 'state'>;
  /** The open view's title, in the heading. */
  title: string;
  /** Told the board it went on, once it landed. */
  onAdded(board: BoardChoice): void;
}) {
  const messages = useViewMessages();
  const [boards, setBoards] = useState<readonly BoardChoice[] | null>(null);
  const [adding, setAdding] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const { boards: read } = commands;
  // Each opening starts afresh — set while rendering, the way React has a
  // prop's change adjust state — and reads the boards anew.
  const [shown, setShown] = useState(open);
  if (shown !== open) {
    setShown(open);
    if (open) {
      setBoards(null);
      setFailed(false);
    }
  }
  useEffect(() => {
    if (!open) return;
    let live = true;
    void read().then(found => {
      if (live) setBoards(found);
    });
    return () => {
      live = false;
    };
  }, [open, read]);

  const add = (board: BoardChoice) => {
    setAdding(board.id);
    setFailed(false);
    void commands.addToDashboard(board.id).then(saved => {
      setAdding(null);
      if (!saved) return setFailed(true);
      onOpenChange(false);
      onAdded(board);
    });
  };
  // A board is named with its definition where there is more than one.
  const several = new Set(boards?.map(board => board.definitionId)).size > 1;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent data-slot="add-to-dashboard">
        <DialogHeader>
          <DialogTitle>
            {messages.label('label.board-pick.heading', { title })}
          </DialogTitle>
          <DialogDescription>
            {messages.label('label.board-pick.description')}
          </DialogDescription>
        </DialogHeader>
        {boards === null ? (
          <p className="fve:flex fve:items-center fve:gap-2 fve:text-sm">
            <Spinner />
            {messages.label('label.board-pick.loading')}
          </p>
        ) : boards.length === 0 ? (
          <p className="fve:text-sm">
            {messages.label('label.board-pick.none')}
          </p>
        ) : (
          <ul
            className="fve:flex fve:flex-col fve:gap-2"
            data-slot="board-choices"
          >
            {boards.map(board => (
              <li key={board.id}>
                <Button
                  variant="outline"
                  className="fve:w-full fve:justify-start"
                  disabled={adding !== null}
                  onClick={() => add(board)}
                >
                  {adding === board.id && <Spinner />}
                  {several
                    ? messages.label('label.board-pick.of', {
                        board: board.title,
                        definition: messages.say(board.definitionTitle),
                      })
                    : board.title}
                </Button>
              </li>
            ))}
          </ul>
        )}
        {failed && commands.state.error && (
          <p role="alert" className="fve:text-sm fve:text-destructive">
            {messages.issue(commands.state.error)}
          </p>
        )}
        <DialogFooter>
          <DialogClose render={<Button variant="outline" />}>
            {messages.label('label.board-pick.cancel')}
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
