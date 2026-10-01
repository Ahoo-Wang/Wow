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

import { useEffect, useRef, type RefObject } from 'react';

/** One panel on the board, by its id. */
function panelOf(
  board: Element | null,
  panelId: string,
): HTMLElement | undefined {
  return [
    ...(board?.querySelectorAll<HTMLElement>('[data-panel-id]') ?? []),
  ].find(item => item.dataset.panelId === panelId);
}

/** A panel's 「⋯」, while the board is built. */
function menuOf(panel: Element | null | undefined): HTMLElement | null {
  return panel?.querySelector<HTMLElement>('[data-slot="panel-menu"]') ?? null;
}

/**
 * A panel just added, brought where it can be seen (R2-38): placed from
 * the first row on screen, a full board puts it below everything — 2,500
 * pixels down on a board of eight panels — and 「已添加」 in the live region
 * was all a sighted builder got, who then added it a second time.
 *
 * Once the panel is drawn it is scrolled into view (`block: 'nearest'`, so a
 * panel already on screen does not move the page), and the keyboard — when
 * it is still on 「＋ 添加」 the dialog handed it back to, or fell — goes to
 * the panel's 「⋯」. `returnTo` is what a dialog closing after the panel
 * landed hands the keyboard to instead of 「＋ 添加」.
 */
export function usePanelReveal(
  board: RefObject<HTMLElement | null>,
  add: RefObject<HTMLElement | null>,
): {
  reveal(panelId: string): void;
  /** Forgets the last one: a new add is under way. */
  reset(): void;
  /** The last added panel's 「⋯」, while it is on the board. */
  returnTo(): HTMLElement | null;
} {
  const pending = useRef<string | null>(null);
  const last = useRef<string | null>(null);
  // No dependency list: the render that draws the panel is the one whose
  // effect has it to scroll to, and an add may land before or after the
  // dialog that asked for it has closed.
  useEffect(() => {
    const id = pending.current;
    if (id === null) return;
    const panel = panelOf(board.current, id);
    if (!panel) return;
    pending.current = null;
    panel.scrollIntoView?.({ block: 'nearest' });
    const active = document.activeElement;
    if (active === null || active === document.body || active === add.current)
      menuOf(panel)?.focus({ preventScroll: true });
  });
  return {
    reveal(panelId) {
      pending.current = panelId;
      last.current = panelId;
    },
    reset() {
      last.current = null;
    },
    returnTo() {
      return last.current === null
        ? null
        : menuOf(panelOf(board.current, last.current));
    },
  };
}
