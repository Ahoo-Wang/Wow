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

import { FilterIcon, Undo2Icon } from 'lucide-react';
import type { FilterEditorController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { useViewMessages } from '../MessagesProvider.js';
import { PendingDot } from '../PendingDot.js';
import { TEXT_UI } from '../layout.js';
import { cn } from 'cn';

/**
 * The ways an edit can end, and the count that stands in the way of one.
 *
 * Apply is the one primary button on the screen — submitting is explicit, and
 * nothing in the panel re-runs the query by itself. It keeps that weight even
 * where a second submit button stands beside it: the analysis editor's Run
 * calls the same `runtime.apply()`, so it is one execution with two ways in,
 * and it is drawn `outline` rather than promoted to a second primary
 * (D17-3). Where the edits run on their own it may rest as an outline
 * (`quiet`) until something waits for it. Discard is the other end of the
 * same decision and appears only
 * when there is an edit to discard, so the row never offers to undo nothing.
 */
export function FilterActions({
  filter,
  disabled,
  overBudget,
  pending = filter.pending,
  quiet = false,
  words = 'filter',
  onApply,
}: {
  filter: FilterEditorController;
  disabled?: boolean;
  /**
   * Whether the draft holds something the last run did not. The filter's
   * own reading by default; an editor that runs more than the filter — the
   * analysis tray — says so for the whole draft.
   */
  pending?: boolean;
  /**
   * Whether the stored tree broke the depth or node budget. Such a tree may
   * hold no leaf at all — deep groups — and clearing it is then the only way
   * back to an editable filter.
   */
  overBudget: boolean;
  /**
   * Whether Apply may rest as an outline button while nothing is `pending`:
   * the editor runs its edits on its own (the analysis tray's auto-run), so
   * a filled button would ask for a press that does nothing new. It is
   * primary again the moment something waits for it.
   */
  quiet?: boolean;
  /**
   * What the conditions are called where these stand: a record view's
   * filter, or an analysis's range — the tray says 「范围」 over them, and
   * 「撤销筛选修改」 under it read as undoing the whole tray.
   */
  words?: 'filter' | 'range';
  /**
   * What Apply does, where a caller has something to settle first; the
   * filter's own `submit` when left out.
   */
  onApply?(): void;
}) {
  const messages = useViewMessages();
  const resting = quiet && !pending;
  return (
    <div className="ml-auto flex items-center gap-2">
      {filter.blocked > 0 && (
        // Apply is refused and the pills say where; this says how many,
        // beside the button that will not move until they are gone.
        <span className={cn('text-destructive', TEXT_UI)}>
          {messages.label('label.filter.blocked', {
            count: filter.blocked,
          })}
        </span>
      )}
      {filter.conditionsPending && (
        // Only while the conditions say something the rows do not: discard
        // puts the conditions back and nothing else, so a sort or a page
        // size waiting for Apply does not bring it out — a button that
        // would change nothing is a button that teaches nothing.
        <Button
          variant="ghost"
          size="sm"
          disabled={disabled}
          onClick={filter.discard}
        >
          <Undo2Icon data-icon="inline-start" />
          {messages.label(
            words === 'range'
              ? 'label.filter.range.discard'
              : 'label.filter.discard',
          )}
        </Button>
      )}
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || (filter.count === 0 && !overBudget)}
        onClick={filter.clear}
      >
        {messages.label(
          words === 'range' ? 'label.filter.range.clear' : 'label.filter.clear',
        )}
      </Button>
      <Button
        size="sm"
        variant={resting ? 'outline' : 'default'}
        // The emphasis said on the element (A-09): a fill is otherwise only
        // a class, which proves nothing about the screen.
        data-emphasis={resting ? 'quiet' : 'primary'}
        data-pending={pending || undefined}
        disabled={disabled || filter.blocked > 0}
        onClick={onApply ?? filter.submit}
      >
        {/* The same dot the pills wear, in the one colour that shows on a
            filled primary button — and a button with a dot is never the
            resting one. It names nothing: the pills it summarises carry the
            wording. */}
        {pending && <PendingDot tone="on-primary" />}
        <FilterIcon data-icon="inline-start" />
        {messages.label('label.filter.apply')}
      </Button>
    </div>
  );
}
