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

import { useRef, type RefObject } from 'react';
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
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';

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
  // A heading is named in place the moment it lands, so the keyboard goes
  // to its box rather than back to this trigger — which would take it
  // straight out of the box, and the box would close on the blur.
  const handedOff = useRef(false);
  return (
    <DropdownMenu
      onOpenChange={open => {
        if (open) handedOff.current = false;
      }}
    >
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
      <DropdownMenuContent
        className="min-w-48"
        finalFocus={() => !handedOff.current}
      >
        <DropdownMenuGroup>
          <DropdownMenuLabel>
            {messages.label('label.dashboard.add.data')}
          </DropdownMenuLabel>
          <DropdownMenuItem
            data-slot="add-saved-view"
            onClick={() => add('saved-view')}
          >
            <LayoutListIcon />
            {messages.label('label.dashboard.add.saved-view')}
          </DropdownMenuItem>
          {canCreate && (
            <DropdownMenuItem
              data-slot="add-new-analysis"
              onClick={() => {
                // The dialog it opens takes the keyboard, and gives it back
                // to this trigger as it closes: the menu going away after it
                // opened must not take it out of the dialog.
                handedOff.current = true;
                add('new-analysis');
              }}
            >
              <SigmaIcon />
              {messages.label('label.dashboard.add.new-analysis')}
            </DropdownMenuItem>
          )}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuLabel>
            {messages.label('label.dashboard.add.content')}
          </DropdownMenuLabel>
          <DropdownMenuItem
            data-slot="add-heading"
            onClick={() => {
              handedOff.current = true;
              add('heading');
            }}
          >
            <HeadingIcon />
            {messages.label('label.dashboard.add.heading')}
          </DropdownMenuItem>
          <DropdownMenuItem
            data-slot="add-markdown"
            onClick={() => add('markdown')}
          >
            <TextIcon />
            {messages.label('label.dashboard.add.markdown')}
          </DropdownMenuItem>
          <DropdownMenuItem data-slot="add-image" onClick={() => add('image')}>
            <ImageIcon />
            {messages.label('label.dashboard.add.image')}
          </DropdownMenuItem>
          <DropdownMenuItem data-slot="add-links" onClick={() => add('links')}>
            <LinkIcon />
            {messages.label('label.dashboard.add.links')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
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
