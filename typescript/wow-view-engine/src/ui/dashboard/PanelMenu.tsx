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

import type { RefObject } from 'react';
import {
  ArrowRightLeftIcon,
  CopyIcon,
  DownloadIcon,
  ExternalLinkIcon,
  FolderInputIcon,
  MoreHorizontalIcon,
  MousePointerClickIcon,
  PaletteIcon,
  PencilIcon,
  RefreshCwIcon,
  SaveIcon,
  SquarePenIcon,
  Trash2Icon,
  Undo2Icon,
  UsersIcon,
} from 'lucide-react';
import { Button } from '../components/button.js';
import {
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import {
  DialogMenuItem,
  HandOffMenu,
  HandOffMenuContent,
} from '../HandOffMenu.js';
import { IconTooltip } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuSubContent } from '../popups.js';
import { RenameInput } from '../RenameInput.js';
import type { PanelCommands } from './commands.js';

/** Whether a panel has anything to put in its menu at all. */
export function hasMenu(commands: PanelCommands): boolean {
  return Boolean(
    commands.open ||
    commands.refresh ||
    commands.exportRows ||
    commands.rename ||
    commands.remove ||
    commands.replace ||
    commands.editContent,
  );
}

export interface PanelMenuProps {
  /** What the panel is called on screen, which the trigger is named after. */
  name: string;
  commands: PanelCommands;
  /** The trigger, for whatever hands the keyboard back to the panel. */
  triggerRef: RefObject<HTMLButtonElement | null>;
  /** 导出数据… was chosen: the panel opens the export window. */
  onExport?(): void;
}

/**
 * 「⋯」 on a panel's header (D22 D): 「看」 — the panel as a reader uses it
 * — and, while the board is built, 「改」. An item exists only when it can
 * be done here: the host's route for 在工作台中打开, a tab bar for 移到标签页,
 * a panel owning its analysis for 另存为视图 (D4). Never an empty menu: the
 * trigger is not drawn when there is nothing in it (`hasMenu`).
 */
export function PanelMenu({
  name,
  commands,
  triggerRef,
  onExport,
}: PanelMenuProps) {
  const messages = useViewMessages();
  const exports = commands.exportRows && onExport;
  const looks = commands.open || commands.refresh || exports;
  const changes = commands.rename || commands.remove;
  return (
    // An item that opens a dialog, or the title's box, or takes the panel
    // away (the keyboard lands on 「撤销」) is a `DialogMenuItem`: the menu
    // closing after it must not take the keyboard back to this trigger.
    <HandOffMenu>
      <IconTooltip
        label={messages.label('label.panel.menu', { title: name })}
        render={
          <DropdownMenuTrigger
            ref={triggerRef}
            render={
              <Button data-slot="panel-menu" variant="ghost" size="icon-xs" />
            }
          />
        }
      >
        <MoreHorizontalIcon />
      </IconTooltip>
      <HandOffMenuContent align="end" className="min-w-48">
        {looks && (
          <DropdownMenuGroup>
            <DropdownMenuLabel>
              {messages.label('label.panel.menu.view')}
            </DropdownMenuLabel>
            {commands.open && (
              <DropdownMenuItem data-slot="panel-open" onClick={commands.open}>
                <ExternalLinkIcon />
                {messages.label('label.panel.open')}
              </DropdownMenuItem>
            )}
            {commands.refresh && (
              <DropdownMenuItem
                data-slot="panel-refresh"
                onClick={commands.refresh}
              >
                <RefreshCwIcon />
                {messages.label('label.panel.refresh')}
              </DropdownMenuItem>
            )}
            {exports && (
              <DialogMenuItem
                data-slot="panel-export"
                onClick={() => onExport?.()}
              >
                <DownloadIcon />
                {messages.label('label.panel.export')}
              </DialogMenuItem>
            )}
          </DropdownMenuGroup>
        )}
        {looks && changes && <DropdownMenuSeparator />}
        {changes && (
          <DropdownMenuGroup>
            <DropdownMenuLabel>
              {messages.label('label.panel.menu.edit')}
            </DropdownMenuLabel>
            {commands.rename && (
              <DialogMenuItem
                data-slot="panel-rename"
                onClick={() => commands.rename?.()}
              >
                <PencilIcon />
                {messages.label('label.panel.rename')}
              </DialogMenuItem>
            )}
            {commands.editPresentation && (
              <DialogMenuItem onClick={() => commands.editPresentation?.()}>
                <PaletteIcon />
                {messages.label('label.panel.edit-presentation')}
              </DialogMenuItem>
            )}
            {commands.click && (
              <DialogMenuItem
                data-slot="panel-click"
                onClick={() => commands.click?.()}
              >
                <MousePointerClickIcon />
                {messages.label('label.click.menu-item')}
              </DialogMenuItem>
            )}
            {commands.resetPresentation && (
              <DropdownMenuItem
                data-slot="panel-reset-presentation"
                onClick={commands.resetPresentation}
              >
                <Undo2Icon />
                {messages.label('label.panel.presentation.reset')}
              </DropdownMenuItem>
            )}
            {commands.editContent && (
              <DialogMenuItem
                data-slot="panel-edit-content"
                onClick={commands.editContent}
              >
                <SquarePenIcon />
                {messages.label('label.panel.edit-content')}
              </DialogMenuItem>
            )}
            {commands.replace && (
              <DialogMenuItem
                data-slot="panel-replace"
                onClick={commands.replace}
              >
                <ArrowRightLeftIcon />
                {messages.label('label.panel.replace')}
              </DialogMenuItem>
            )}
            {commands.copyAsShared && (
              <DialogMenuItem
                data-slot="panel-copy-shared"
                onClick={() => commands.copyAsShared?.()}
              >
                <UsersIcon />
                {messages.label('label.panel.copy-shared')}
              </DialogMenuItem>
            )}
            {commands.duplicate && (
              <DropdownMenuItem
                data-slot="panel-duplicate"
                onClick={commands.duplicate}
              >
                <CopyIcon />
                {messages.label('label.panel.duplicate')}
              </DropdownMenuItem>
            )}
            {commands.moveTo && (
              <DropdownMenuSub>
                <DropdownMenuSubTrigger>
                  <FolderInputIcon />
                  {messages.label('label.panel.move-to-tab')}
                </DropdownMenuSubTrigger>
                <DropdownMenuSubContent>
                  <DropdownMenuGroup>
                    {commands.moveTo.tabs.map(tab => (
                      <DropdownMenuItem
                        key={tab.id}
                        onClick={() => commands.moveTo?.move(tab.id)}
                      >
                        {tab.name}
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuGroup>
                </DropdownMenuSubContent>
              </DropdownMenuSub>
            )}
            {commands.saveAsView && (
              <DialogMenuItem onClick={() => commands.saveAsView?.()}>
                <SaveIcon />
                {messages.label('label.panel.save-as-view')}
              </DialogMenuItem>
            )}
            {commands.remove && (
              <DialogMenuItem
                data-slot="panel-remove"
                variant="destructive"
                // Gone at once, and back with 「撤销」: the builder says so and
                // puts the keyboard on it (`BoardBuilding.removed`).
                onClick={() => commands.remove?.()}
              >
                <Trash2Icon />
                {messages.label('label.panel.remove')}
              </DialogMenuItem>
            )}
          </DropdownMenuGroup>
        )}
      </HandOffMenuContent>
    </HandOffMenu>
  );
}

export interface PanelTitleInputProps {
  /** What the title says now — a heading's words, or the panel's name. */
  initial: string;
  /** A heading's words are its content; another panel's, its title. */
  heading: boolean;
  renaming: NonNullable<PanelCommands['renaming']>;
  /** Where the keyboard goes once Enter or Escape ends the edit. */
  returnTo: RefObject<HTMLElement | null>;
}

/**
 * 改标题, in place (`RenameInput`): a blank title names the panel by what it
 * shows again. Only Enter and Escape hand the keyboard back to the panel's
 * menu; leaving by Tab or a press already put it somewhere.
 */
export function PanelTitleInput({
  initial,
  heading,
  renaming,
  returnTo,
}: PanelTitleInputProps) {
  const messages = useViewMessages();
  return (
    <RenameInput
      data-slot="panel-title-input"
      initial={initial}
      label={messages.label(
        heading ? 'label.panel.heading-input' : 'label.panel.title-input',
      )}
      onCommit={renaming.commit}
      onCancel={renaming.cancel}
      returnTo={returnTo}
      className="h-7 min-w-0 flex-1"
    />
  );
}
