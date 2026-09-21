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
import { useViewMessages } from './MessagesProvider.js';

/**
 * Pinned to the corner of the bordered node it belongs to — a condition
 * pill or a group block, both of which are `relative` for it.
 */
export const PENDING_AT_CORNER = 'absolute -top-0.5 -right-0.5';

/**
 * The one credential for "said, but not yet asked".
 *
 * A draft is only worth keeping apart from what ran if the difference is
 * visible, and it is visible in one mark, drawn the same size wherever it
 * appears: pinned to a condition's corner, in the editor's toggle beside
 * the count of them, and on the Apply button itself. The three used to be
 * three copies of `size-1.5 rounded-full`, which is three chances for one
 * of them to end up a different dot.
 *
 * **It is named in exactly one of those places.** The pills carry the
 * wording, because that is where the reader can act on it — a condition
 * that has moved. The summaries beside a count and on a button sit next to
 * words that already say it, and a dot that repeats them is the same
 * sentence twice.
 */
export function PendingDot({
  /**
   * Whether this dot is the one that says so. Left off, it is decorative
   * and the words beside it do the talking.
   */
  named,
  /**
   * `on-primary` for a dot drawn on a filled `primary` surface — the Apply
   * button — where `primary` itself would be invisible.
   */
  tone = 'primary',
  /** Layout only: where the dot is put, when it is put somewhere. */
  className,
}: {
  named?: boolean;
  tone?: 'primary' | 'on-primary';
  className?: string;
}) {
  const messages = useViewMessages();
  return (
    <span
      data-slot="pending-dot"
      aria-hidden={named ? undefined : 'true'}
      className={cn(
        'size-1.5 rounded-full',
        tone === 'primary' ? 'bg-primary' : 'bg-primary-foreground',
        className,
      )}
    >
      {named && (
        <span className="sr-only">
          {messages.label('label.filter.pending')}
        </span>
      )}
    </span>
  );
}
