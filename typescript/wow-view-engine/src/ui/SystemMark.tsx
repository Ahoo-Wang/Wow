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
import { cn } from 'cn';
import { SYSTEM_ICON } from './kinds.js';
import { useViewMessages } from './MessagesProvider.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { TooltipContent } from './popups.js';

/**
 * The mark a view that came with the definition wears in a list of views: a
 * lock at the row's end, a word for a screen reader, and on pointing, the
 * sentence that says why it is locked.
 *
 * A glyph and not the word "system" after the name: every row of the shared
 * group then said the same two syllables, and the fact a reader needs from
 * it — this one cannot be renamed or deleted — is the lock's to say. The
 * sidebar and the switcher draw the same view, so they draw it with this
 * one mark; the title bar, which has room, keeps the word beside the lock.
 *
 * The glyph hangs off a span rather than being the tooltip's trigger itself,
 * for the reason `ViewList` gives for the kind icon: a row that is a
 * `Button` or a menu item draws `pointer-events: none` over every `<svg>`
 * inside it.
 */
export function SystemMark({ className }: { className?: string }) {
  const messages = useViewMessages();
  const Icon = SYSTEM_ICON;
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <span
            data-slot="view-system-tag"
            className={cn('flex shrink-0 opacity-70', className)}
          />
        }
      >
        <Icon aria-hidden className="size-3.5" />
        <span className="sr-only">
          {messages.label('label.scope.tag.system')}
        </span>
      </TooltipTrigger>
      <TooltipContent>{messages.label('label.scope.system')}</TooltipContent>
    </Tooltip>
  );
}
