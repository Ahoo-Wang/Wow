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
  ChevronDownIcon,
  HeadingIcon,
  ImageIcon,
  LayoutListIcon,
  LinkIcon,
  PlusIcon,
  SigmaIcon,
  TextIcon,
} from 'lucide-react';
import { Button } from '../components/button.js';
import {
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import {
  DialogMenuItem,
  HandOffMenu,
  HandOffMenuContent,
} from '../HandOffMenu.js';
import { useViewMessages } from '../MessagesProvider.js';

/** What can be put on a board: a view, or one of the static panels. */
export type AddChoice =
  'saved-view' | 'new-analysis' | 'heading' | 'markdown' | 'image' | 'links';

export interface AddCommands {
  /** Adds one kind of thing; 'new-analysis' only when `canCreate` says so. */
  add(choice: AddChoice): void;
  /**
   * Whether 「新建分析…」 is on offer — only when something provides the
   * dialog it opens (`DashboardEditExtensions.onAddOwnedAnalysis`).
   */
  canCreate: boolean;
}

/**
 * 「＋ 添加 ▾」 on the edit bar (D22 A): 数据 — a saved view, or a new
 * analysis of the board's own — and 内容 — a heading, text, an image,
 * links. A heading goes straight on and is named in place; the others that
 * need something typed ask for it first, which the ellipsis says.
 */
export function AddMenu({
  add,
  canCreate,
  triggerRef,
}: AddCommands & { triggerRef?: RefObject<HTMLButtonElement | null> }) {
  const messages = useViewMessages();
  // Every item hands the keyboard on: five open a dialog, and a heading is
  // named in place the moment it lands — the menu closing after them must
  // not take it back to this trigger, behind the dialog or out of the box.
  return (
    <HandOffMenu>
      <DropdownMenuTrigger
        ref={triggerRef}
        render={
          <Button data-slot="dashboard-add" variant="outline" size="sm" />
        }
      >
        <PlusIcon data-icon="inline-start" />
        {messages.label('label.dashboard.add')}
        <ChevronDownIcon data-icon="inline-end" />
      </DropdownMenuTrigger>
      <HandOffMenuContent className="min-w-48">
        <DropdownMenuGroup>
          <DropdownMenuLabel>
            {messages.label('label.dashboard.add.data')}
          </DropdownMenuLabel>
          <DialogMenuItem
            data-slot="add-saved-view"
            onClick={() => add('saved-view')}
          >
            <LayoutListIcon />
            {messages.label('label.dashboard.add.saved-view')}
          </DialogMenuItem>
          {canCreate && (
            <DialogMenuItem
              data-slot="add-new-analysis"
              onClick={() => add('new-analysis')}
            >
              <SigmaIcon />
              {messages.label('label.dashboard.add.new-analysis')}
            </DialogMenuItem>
          )}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuLabel>
            {messages.label('label.dashboard.add.content')}
          </DropdownMenuLabel>
          <DialogMenuItem
            data-slot="add-heading"
            onClick={() => add('heading')}
          >
            <HeadingIcon />
            {messages.label('label.dashboard.add.heading')}
          </DialogMenuItem>
          <DialogMenuItem
            data-slot="add-markdown"
            onClick={() => add('markdown')}
          >
            <TextIcon />
            {messages.label('label.dashboard.add.markdown')}
          </DialogMenuItem>
          <DialogMenuItem data-slot="add-image" onClick={() => add('image')}>
            <ImageIcon />
            {messages.label('label.dashboard.add.image')}
          </DialogMenuItem>
          <DialogMenuItem data-slot="add-links" onClick={() => add('links')}>
            <LinkIcon />
            {messages.label('label.dashboard.add.links')}
          </DialogMenuItem>
        </DropdownMenuGroup>
      </HandOffMenuContent>
    </HandOffMenu>
  );
}

/**
 * The same three first steps on an empty board (D22 A): a view, a new
 * analysis where one can be made, a heading. Plain buttons rather than the
 * menu — there is nothing else on the board to make room for.
 */
export function EmptyBoardActions({ add, canCreate }: AddCommands) {
  const messages = useViewMessages();
  return (
    <div className="flex flex-wrap justify-center gap-2">
      <Button data-slot="empty-add-view" onClick={() => add('saved-view')}>
        <LayoutListIcon data-icon="inline-start" />
        {messages.label('label.dashboard.empty.add-view')}
      </Button>
      {canCreate && (
        <Button variant="outline" onClick={() => add('new-analysis')}>
          <SigmaIcon data-icon="inline-start" />
          {messages.label('label.dashboard.add.new-analysis')}
        </Button>
      )}
      <Button
        data-slot="empty-add-heading"
        variant="outline"
        onClick={() => add('heading')}
      >
        <HeadingIcon data-icon="inline-start" />
        {messages.label('label.dashboard.empty.add-heading')}
      </Button>
    </div>
  );
}
