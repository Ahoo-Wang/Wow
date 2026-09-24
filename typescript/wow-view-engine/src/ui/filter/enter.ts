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

import type { KeyboardEvent } from 'react';

/**
 * Whether this is a press of Enter the filter editor may act on at all.
 *
 * Two presses are not, wherever in the editor they land, and they are not
 * for reasons that have nothing to do with which control was focused:
 *
 * - one an IME is using to accept the characters being composed, which is
 *   not a press of Enter at all as far as the user is concerned;
 * - one held with a modifier, which is some other shortcut — very possibly
 *   the host page's, and the host cannot get it back from a control that
 *   swallowed it.
 *
 * `FilterPanel` asks this before its apply, and the number list's entry
 * field asks it before its add, so the two answer a modified Enter the same
 * way: by letting it through. It lives in its own file rather than beside
 * either of them because both would otherwise import the other.
 */
export function isPlainEnter(event: KeyboardEvent<HTMLElement>): boolean {
  if (event.key !== 'Enter') return false;
  if (event.nativeEvent.isComposing) return false;
  return !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey;
}
