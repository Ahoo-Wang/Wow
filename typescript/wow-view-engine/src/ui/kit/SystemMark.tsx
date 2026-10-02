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
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
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
 * A system view the store keeps and this user may edit (D81) keeps the
 * lock — it is still everyone's view, not this user's — and only the
 * sentence on pointing changes (`editable`): it can be changed, by whoever
 * the host lets, and the change reaches everyone.
 *
 * The glyph hangs off a span rather than being the tooltip's trigger itself,
 * for the reason `ViewList` gives for the kind icon: a row that is a
 * `Button` or a menu item draws `pointer-events: none` over every `<svg>`
 * inside it.
 */
export function SystemMark({
  className,
  editable = false,
  stored = false,
}: {
  className?: string;
  /** True when this user may change the view (D81); the sentence says so. */
  editable?: boolean;
  /**
   * True for a view the store keeps (D81): it did not come with the
   * definition, so a reader who may not change it is told only that.
   */
  stored?: boolean;
}) {
  const messages = useViewMessages();
  const Icon = SYSTEM_ICON;
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <span
            data-slot="view-system-tag"
            data-editable={editable || undefined}
            className={cn('fve:flex fve:shrink-0 fve:opacity-70', className)}
          />
        }
      >
        <Icon aria-hidden className="fve:size-3.5" />
        <span className="fve:sr-only">
          {messages.label('label.scope.tag.system')}
        </span>
      </TooltipTrigger>
      <TooltipContent>
        {messages.label(
          editable
            ? 'label.scope.system-editable'
            : stored
              ? 'label.scope.system-stored'
              : 'label.scope.system',
        )}
      </TooltipContent>
    </Tooltip>
  );
}
