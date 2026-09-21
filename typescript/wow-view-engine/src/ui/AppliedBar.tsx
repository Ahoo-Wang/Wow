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
import type { FilterSummaryItem } from '../filter/index.js';
import type { FilterEditorController } from '../react/index.js';
import { Badge } from './components/badge.js';
import { summaryText } from './display.js';
import { IconTooltip } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';
import { FOCUS_RING, TEXT_UI } from './layout.js';
import { useSurfaceDisplay } from './ViewSurface.js';

export interface AppliedBarProps {
  filter: FilterEditorController;
  /**
   * Whether a result exists to describe. `filter.applied` reads the config
   * the result carries, so an empty list means either "no condition" or "no
   * answer yet" and the bar cannot tell the two apart on its own. The
   * workbench knows — it holds the state — and says so here, which keeps the
   * bar off the runtime.
   */
  hasResult: boolean;
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
  hasResult,
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
  const { applied, scoped } = filter;
  // Nothing has been fetched, so there is nothing to say the fetch ran under.
  if (!hasResult) return null;

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
      {applied.length === 0 && scoped.length === 0 && (
        <span className="text-muted-foreground">
          {messages.label('label.applied.all')}
        </span>
      )}
      {applied.map(item => (
        // A group reads out as one badge, its conditions joined by its own
        // operator, so the bar keeps the logic the tree has. Its remove
        // takes the condition out of force — the value goes back to
        // "nothing said yet" and the query runs again — while the field
        // stays in the editor for the next question.
        <Badge
          key={item.path.join('.')}
          // A condition whose field or kind the definition no longer
          // declares is named rather than hidden, and worn plainly: it is
          // still in force, and it is not something to go on building on.
          variant={item.unresolved ? 'outline' : 'secondary'}
          data-unresolved={item.unresolved || undefined}
          // A group's read-out can be long; it wraps inside the bar rather
          // than carrying the bar off the edge of the view.
          className="h-auto max-w-full text-left whitespace-normal"
        >
          {say(item)}
          {!readOnly && (
            // A chip's ✕ is not a `Button` — it wears the badge's own
            // geometry rather than a variant — so the tooltip is wrapped
            // round the element there is, which is what `IconTooltip` is
            // for. The name is the whole condition, so the label that says
            // what this removes is worth showing to a pointer as well.
            <IconTooltip
              label={messages.label('label.filter.unset-of', {
                condition: say(item),
              })}
              render={
                <button
                  type="button"
                  disabled={disabled}
                  // Dimmed until pointed at, but never while focused: a
                  // focus outline at 60% is a focus outline that fails its
                  // own contrast.
                  className={cn(
                    '-mr-1 rounded-full opacity-60 hover:opacity-100 focus-visible:opacity-100',
                    FOCUS_RING,
                  )}
                  onClick={() => {
                    // A summary path interleaves the `children` key with
                    // each index; the editor addresses nodes by the indexes
                    // alone.
                    filter.clearValue(
                      item.path.filter(
                        (segment): segment is number =>
                          typeof segment === 'number',
                      ),
                    );
                    filter.submit();
                  }}
                />
              }
            >
              {/* Sized like every other inline icon; Lucide's default 24px
                  stretched the badge to 30px where its neighbours are 20. */}
              <XIcon className="size-3.5" />
            </IconTooltip>
          )}
        </Badge>
      ))}
      {/* After the editable ones, and worn differently: the page put these
          in force, and they are nobody's here to take out — so they carry
          no ✕ at all, and say whose they are rather than leaving the reader
          to wonder why one badge in the row cannot be removed. */}
      {scoped.map(item => (
        <Badge
          key={`scoped:${item.path.join('.')}`}
          variant="outline"
          data-scoped
          data-unresolved={item.unresolved || undefined}
          className="h-auto max-w-full text-left whitespace-normal"
        >
          {say(item)}
          <span className="sr-only">
            {' '}
            {messages.label('label.applied.scoped')}
          </span>
        </Badge>
      ))}
    </div>
  );
}
