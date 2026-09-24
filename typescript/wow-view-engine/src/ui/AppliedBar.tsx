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
import { XIcon } from 'lucide-react';
import { filterIndexes, type FilterSummaryItem } from '../filter/index.js';
import type { FilterEditorController } from '../react/index.js';
import { summaryText } from './summary.js';
import { IconButton } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';
import { TEXT_UI } from './layout.js';
import { WrappingBadge } from './variants.js';
import { useSurfaceDisplay } from './ViewSurface.js';

export interface AppliedBarProps {
  filter: FilterEditorController;
  /**
   * Whether a question has been put to the source — a result came back, one
   * is on its way, or the last one failed (`hasAsked`). `filter.applied`
   * describes the conditions it was put under, so an empty list means either
   * "no condition" or "nothing asked yet" and the bar cannot tell the two
   * apart on its own. The workbench knows — it holds the state — and says so
   * here, which keeps the bar off the runtime.
   */
  asked: boolean;
  disabled?: boolean;
  /**
   * Nothing here may be taken out of force. The ✕ is not rendered at all
   * rather than rendered disabled: an embedded view shows what somebody
   * already decided, and a control that is only ever grey offers a narrowing
   * the reader will never be given.
   */
  readOnly?: boolean;
  className?: string;
}

/**
 * The conditions the rows below were fetched under.
 *
 * It is deliberately not the editor's draft: applying starts a query, and
 * until that query answers the draft has already moved on. A bar that
 * followed the draft would describe rows that are not on screen yet, which is
 * worse than describing none.
 */
export function AppliedBar({
  filter,
  asked,
  disabled,
  readOnly = false,
  className,
}: AppliedBarProps) {
  const messages = useViewMessages();
  // The badge is the most visible text of the result area, so it is built
  // from the parts each kind hands over rather than from the English line
  // beside them: the words come from the catalogue in force, the values from
  // the surface's own language and zone.
  const display = useSurfaceDisplay();
  const say = (item: FilterSummaryItem) => summaryText(item, messages, display);
  // The host's own conditions, which are in force beside the view's own but
  // belong to the page rather than to the view. They read as `scoped` on the
  // controller precisely because no path here addresses them.
  const { applied, fixed, scoped, implied } = filter;
  // Nothing has been asked, so there is nothing to say it was asked under.
  if (!asked) return null;

  return (
    <div
      data-slot="applied-bar"
      role="region"
      aria-label={messages.label('label.applied.title')}
      className={cn('flex flex-wrap items-center gap-1', TEXT_UI, className)}
    >
      <span className="text-muted-foreground shrink-0">
        {messages.label('label.applied.title')}
      </span>
      {/* "All records" answers for everything in force, so a scope counts:
          rows narrowed by the page are not all of them. */}
      {applied.length === 0 &&
        fixed.length === 0 &&
        scoped.length === 0 &&
        implied.length === 0 && (
          <span className="text-muted-foreground">
            {messages.label('label.applied.all')}
          </span>
        )}
      {applied.map(item => (
        // A group reads out as one badge, its conditions joined by its own
        // operator, so the bar keeps the logic the tree has. Its remove
        // takes the condition out of force — the value goes back to
        // "nothing said yet" and the query runs again — while the field
        // stays in the editor for the next question. A read-out can be long,
        // so every badge in this bar is one that wraps inside it rather than
        // carrying the bar off the edge of the view.
        <WrappingBadge
          key={item.path.join('.')}
          // A condition whose field or kind the definition no longer
          // declares is named rather than hidden, and worn plainly: it is
          // still in force, and it is not something to go on building on.
          variant={item.unresolved ? 'outline' : 'secondary'}
          data-unresolved={item.unresolved || undefined}
        >
          {say(item)}
          {!readOnly && (
            // The ✕ inside a badge, in the one shape this surface has for
            // it: a ghost `icon-xs` button, which is what `UnsavedMark`
            // already puts in the badge beside the view's name. It used to
            // be a bare `<button>` carrying a hand-copied focus recipe —
            // the recipe was the vendored `Button`'s, so this is the
            // vendored `Button`. The name is the whole condition, so the
            // label that says what this removes is worth showing to a
            // pointer as well.
            <IconButton
              type="button"
              label={messages.label('label.filter.unset-of', {
                condition: say(item),
              })}
              variant="ghost"
              size="icon-xs"
              disabled={disabled}
              // Dimmed until pointed at, but never while focused: a focus
              // outline at 60% is a focus outline that fails its own
              // contrast. The pull is the badge's right padding, so the
              // pill does not grow a second box round the button.
              className="-mr-1.5 opacity-60 hover:opacity-100 focus-visible:opacity-100"
              onClick={() => {
                filter.clearValue(filterIndexes(item.path));
                filter.submit();
              }}
            >
              {/* No size class: the button sizes what is inside it, and at
                  `icon-xs` that is the 3 every other badge icon wears. The
                  pill grows with it, from 20px to 24 — which is the point.
                  A ✕ drawn at the inline icon size was a 14px target, under
                  the 24px WCAG 2.5.8 asks of one, and this is the control
                  that takes a condition out of force. */}
              <XIcon />
            </IconButton>
          )}
        </WrappingBadge>
      ))}
      {/* A dashboard's fixed scope (D26 Q31), worn like the page's: the
          board holds it in force, and no reader takes it out — so no ✕,
          and it says whose it is. Its own place beside the filter bar,
          removable while the board is built, is the Wow repo's (Q16). */}
      {fixed.map(item => (
        <WrappingBadge
          key={`fixed:${item.path.join('.')}`}
          variant="outline"
          data-fixed
          data-unresolved={item.unresolved || undefined}
        >
          {say(item)}
          <span className="sr-only">
            {' '}
            {messages.label('label.applied.fixed')}
          </span>
        </WrappingBadge>
      ))}
      {/* After the editable ones, and worn differently: the page put these
          in force, and they are nobody's here to take out — so they carry
          no ✕ at all, and say whose they are rather than leaving the reader
          to wonder why one badge in the row cannot be removed. */}
      {scoped.map(item => (
        <WrappingBadge
          key={`scoped:${item.path.join('.')}`}
          variant="outline"
          data-scoped
          data-unresolved={item.unresolved || undefined}
        >
          {say(item)}
          <span className="sr-only">
            {' '}
            {messages.label('label.applied.scoped')}
          </span>
        </WrappingBadge>
      ))}
      {/* Last, the readings nobody wrote and the source applies on its own —
          today, that a declared deletion dimension left blank shows the
          records that are not deleted (D17-2). Worn like the scope, with
          no ✕: it is not in the config to take out. Choosing otherwise is
          adding the field and answering it. */}
      {implied.map(item => (
        <WrappingBadge
          key={`implied:${item.field ?? ''}`}
          variant="outline"
          data-implied
        >
          {say(item)}
          <span className="sr-only">
            {' '}
            {messages.label('label.applied.implied')}
          </span>
        </WrappingBadge>
      ))}
    </div>
  );
}
