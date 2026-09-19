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

import { FilterIcon } from 'lucide-react';
import type { FilterEditorController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The pair that ends an edit, and the count that stands in the way of it.
 *
 * Apply is the one primary button on the screen — submitting is explicit, and
 * nothing in the panel re-runs the query by itself.
 */
export function FilterActions({
  filter,
  disabled,
  overBudget,
}: {
  filter: FilterEditorController;
  disabled?: boolean;
  /**
   * Whether the stored tree broke the depth or node budget. Such a tree may
   * hold no leaf at all — deep groups — and clearing it is then the only way
   * back to an editable filter.
   */
  overBudget: boolean;
}) {
  const messages = useViewMessages();
  return (
    <div className="ml-auto flex items-center gap-2">
      {filter.blocked > 0 && (
        // Apply is refused and the pills say where; this says how many,
        // beside the button that will not move until they are gone.
        <span className="text-destructive text-xs">
          {messages.label('label.filter.blocked', {
            count: filter.blocked,
          })}
        </span>
      )}
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || (filter.count === 0 && !overBudget)}
        onClick={filter.clear}
      >
        {messages.label('label.filter.clear')}
      </Button>
      <Button
        size="sm"
        data-pending={filter.pending || undefined}
        disabled={disabled || filter.blocked > 0}
        onClick={filter.submit}
      >
        {filter.pending && (
          // The same dot the pills wear, in the one colour that shows on a
          // filled primary button. It names nothing: the pills it summarises
          // carry the wording.
          <span
            aria-hidden="true"
            className="bg-primary-foreground size-1.5 rounded-full"
          />
        )}
        <FilterIcon data-icon="inline-start" />
        {messages.label('label.filter.apply')}
      </Button>
    </div>
  );
}
