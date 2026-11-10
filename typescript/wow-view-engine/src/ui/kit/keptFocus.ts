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

/**
 * A popup that closes does not take the keyboard back from where the reader
 * went while it was closing (`useKeptFocus`): the one behaviour every popup
 * here shares about focus, used by the popups in `popups.tsx`.
 */

import type * as React from 'react';
import { useCallback, useRef } from 'react';
import { handBack, rememberOpener } from './focus.js';

/**
 * What a popup is given for `finalFocus`, and the ref it reads it with: a
 * function or a ref is asked only while the focus is still the popup's to
 * give (`focusMovedOn`); `false` passes as it is.
 *
 * Base UI keeps a focus that moved on where it went when `finalFocus` is
 * `true`, but it takes a function's answer — or a ref's element — as
 * explicit and sends the focus there regardless. A popup hands the focus
 * back only once its exit animation ends, so a press on another control in
 * that time had the focus pulled out from under it: a select opened right
 * after picking a dimension closed again (#3547, a menu). A dialog, a side
 * panel and a popover close the same way and take the same kind of
 * `finalFocus` — the board's dialogs hand the keyboard to a panel's 「⋯」,
 * the record's detail to its row, the field list to the condition it added —
 * so each of them keeps a focus that moved on, too.
 *
 * And each notes where it was opened from (`rememberOpener`), so a closing
 * whose opener — or whatever the caller named — has left the page lands
 * near it rather than on `<body>` (`handBack`, WCAG 2.4.3). Nothing given is
 * `true`, the popup's own default, except where `handsBack` is off: a
 * submenu hands nothing back by default, its menu holds the keyboard.
 */
export function useKeptFocus<Close>(
  finalFocus:
    | boolean
    | React.RefObject<HTMLElement | null>
    | ((closeType: Close) => boolean | HTMLElement | null | void)
    | undefined,
  /** The caller's own ref on the popup, which is given the element too. */
  outer: React.Ref<HTMLDivElement> | undefined,
  handsBack = true,
): {
  ref: React.RefCallback<HTMLDivElement>;
  finalFocus?:
    boolean | ((closeType: Close) => boolean | HTMLElement | null | void);
} {
  const popup = useRef<HTMLDivElement | null>(null);
  const ref = useCallback(
    (node: HTMLDivElement | null) => {
      popup.current = node;
      if (node) rememberOpener(node);
      assignRef(outer, node);
    },
    [outer],
  );
  if (finalFocus === false) return { ref, finalFocus };
  if (finalFocus === undefined && !handsBack) return { ref };
  return {
    ref,
    finalFocus: closeType => {
      if (focusMovedOn(popup.current)) return false;
      const asked =
        typeof finalFocus === 'function'
          ? finalFocus(closeType)
          : typeof finalFocus === 'object'
            ? (finalFocus.current ?? true)
            : true;
      return handBack(popup.current, asked);
    },
  };
}

function assignRef<T>(ref: React.Ref<T> | undefined, value: T | null): void {
  if (typeof ref === 'function') ref(value);
  else if (ref) ref.current = value;
}

/**
 * Whether the keyboard has already gone somewhere else while a popup closed:
 * not left inside it or one of a menu's submenus, and not dropped on
 * `<body>` by the popup's own removal.
 */
function focusMovedOn(popup: HTMLElement | null): boolean {
  const doc = popup?.ownerDocument ?? document;
  const active = doc.activeElement;
  return (
    active instanceof HTMLElement &&
    active !== doc.body &&
    !popup?.contains(active) &&
    !active.closest('[data-slot="dropdown-menu-sub-content"]')
  );
}
