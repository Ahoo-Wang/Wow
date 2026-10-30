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
import {
  ChevronDownIcon,
  CopyIcon,
  LayoutDashboardIcon,
  LinkIcon,
} from 'lucide-react';
import type { SaveCommands } from '../../react/index.js';
import type { ViewAudience } from '../../model/index.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { IconTooltip } from '../kit/IconButton.js';
import { AUDIENCE_ICON, useKindWord } from '../kit/kinds.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { DropdownMenuContent } from '../kit/popups.js';
import { useViewRouter } from './ViewEngineProvider.js';
import { AddToDashboardDialog } from './AddToDashboardDialog.js';

/**
 * 「更多视图操作」 beside the save button: what else is done to the open
 * view, where the reader is, rather than in 「管理视图」.
 *
 * - **另存为**, where a save in place is also offered (`saveAs`).
 * - **设为共享／设为个人**, the move to the other audience the manager's row
 *   offers, where this reader may make it (`can.changeAudience`). Said once
 *   it lands, in the manager's words; a refusal is the view's outcome, on
 *   its band.
 * - **复制链接**, where a router puts the view in the address: the page's
 *   own address, which is what opens it for someone else.
 *
 * Nothing here runs while a write is in flight or an outcome is unsettled
 * (`stopped`): a move taken under a save would be undone by it.
 */
export function MoreViewActions({
  commands,
  title,
  stopped,
  onSaveAs,
}: {
  commands: SaveCommands;
  /** The view's title, as the line said after a move names it. */
  title: string;
  stopped: boolean;
  /** Opens 另存为; absent where the menu does not offer it. */
  onSaveAs?(): void;
}) {
  const messages = useViewMessages();
  const word = useKindWord();
  const router = useViewRouter();
  const [said, say] = useState<string | null>(null);
  const [placing, setPlacing] = useState(false);
  const { can, state } = commands;
  const moveTo: ViewAudience | null =
    can.changeAudience === true &&
    commands.changeAudience !== undefined &&
    state.audience !== null &&
    !state.isNew
      ? state.audience === 'personal'
        ? 'shared'
        : 'personal'
      : null;
  // A view kept in this browser alone has an address that opens nothing
  // anywhere else: only a system view's link is worth handing on — the one
  // view such a store calls shared, since it shares none of its own.
  const linkable =
    router !== undefined &&
    !state.isNew &&
    typeof window !== 'undefined' &&
    (can.local !== true || state.audience === 'shared');
  // 放进仪表盘: offered on a saved view whose commands can; whether any
  // board will take it is read when the dialog opens.
  const { boards, addToDashboard } = commands;
  const placeable =
    !state.isNew && boards !== undefined && addToDashboard !== undefined;
  if (!onSaveAs && moveTo === null && !linkable && !placeable) return null;
  const Audience = moveTo === null ? null : AUDIENCE_ICON[moveTo];

  const move = (to: ViewAudience) => {
    say(null);
    void commands.changeAudience?.(to).then(moved => {
      if (moved)
        say(
          messages.label(
            to === 'shared'
              ? 'label.manage.shared'
              : 'label.manage.made-personal',
            { title },
          ),
        );
    });
  };
  const copyLink = () => {
    say(null);
    const written = navigator.clipboard?.writeText(window.location.href);
    void (written ?? Promise.reject(new Error('no clipboard'))).then(
      () => say(messages.label('label.header.link-copied')),
      () => say(messages.label('label.header.link-failed')),
    );
  };

  return (
    <>
      <DropdownMenu>
        <IconTooltip
          label={messages.label(word('label.header.more'))}
          render={
            <DropdownMenuTrigger
              render={
                <Button variant="outline" size="icon-sm" disabled={stopped} />
              }
            />
          }
        >
          <ChevronDownIcon />
        </IconTooltip>
        <DropdownMenuContent align="end">
          <DropdownMenuGroup>
            {onSaveAs && (
              <DropdownMenuItem onClick={onSaveAs}>
                <CopyIcon />
                {messages.label('label.save.save-as')}
              </DropdownMenuItem>
            )}
            {moveTo !== null && Audience && (
              <DropdownMenuItem
                data-audience-target={moveTo}
                onClick={() => move(moveTo)}
              >
                <Audience />
                {messages.label(
                  moveTo === 'shared'
                    ? 'label.manage.share'
                    : 'label.manage.make-personal',
                )}
              </DropdownMenuItem>
            )}
            {placeable && (
              <DropdownMenuItem
                data-slot="add-to-board"
                onClick={() => {
                  say(null);
                  setPlacing(true);
                }}
              >
                <LayoutDashboardIcon />
                {messages.label('label.header.add-to-board')}
              </DropdownMenuItem>
            )}
            {linkable && (
              <DropdownMenuItem data-slot="copy-link" onClick={copyLink}>
                <LinkIcon />
                {messages.label('label.header.copy-link')}
              </DropdownMenuItem>
            )}
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      {placeable && (
        <AddToDashboardDialog
          open={placing}
          onOpenChange={setPlacing}
          commands={{ boards, addToDashboard, state }}
          title={title}
          onAdded={board =>
            say(
              messages.label('label.board-pick.added', { board: board.title }),
            )
          }
        />
      )}
      {/* Drawn once there is something to say, as the save button's own
          「已保存」 is: a second region standing empty beside it would be
          one more place a reader is told nothing. */}
      {said && (
        <span role="status" className="fve:sr-only" data-slot="more-said">
          {said}
        </span>
      )}
    </>
  );
}
