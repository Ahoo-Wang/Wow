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

import { useState, type ReactNode } from 'react';

export interface Announcer {
  /** Say this, out loud, once. */
  say(message: string): void;
  /**
   * The live region itself. It is handed back rather than rendered by the
   * caller's own markup so that one call is one region: a surface cannot
   * end up with two of them, and cannot end up with none.
   */
  region: ReactNode;
}

/**
 * One live region for one surface, and the sentence to put in it.
 *
 * Four surfaces here move a row by a keyboard shortcut of their own — the
 * column settings, the sort editor, the view manager and the visualization
 * panel's series list — and the first three each had their own
 * `role="status" aria-live="polite"` block, written out three times. The
 * region is not the interesting part of any of them: the wording is
 * (`dragAnnounce.ts`, `columns/drag.ts`, `manage/drag.ts`,
 * `analysis/SeriesList.tsx`), and the caller hands that over already
 * formatted.
 *
 * Why there is a region at all, next to a drag library that owns one:
 * `@dnd-kit`'s `Accessibility` plugin announces what the plugin itself
 * drives — pick up, drop, cancel — and every one of those surfaces also
 * offers a one-press arrow move that never reaches its `KeyboardSensor`. That move is
 * what this says. **One per surface** is the rule the shared hook exists to
 * keep: two polite regions on one screen are two voices reading over each
 * other, and a reader is given no way to tell which answered the key.
 *
 * The `slot` is the region's `data-slot`, so a suite can still read back
 * what a particular surface said.
 */
export function useAnnouncer(slot: string): Announcer {
  const [message, setMessage] = useState('');
  return {
    say: setMessage,
    region: (
      <div
        data-slot={slot}
        role="status"
        aria-live="polite"
        className="sr-only"
      >
        {message}
      </div>
    ),
  };
}
