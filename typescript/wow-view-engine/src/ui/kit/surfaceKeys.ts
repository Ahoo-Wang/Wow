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

/**
 * A key the view surface around `anchor` answers, wherever the keyboard is
 * inside it: ⌘S on the save button, 「/」 on the search box.
 *
 * Listened for on the surface's own element (`data-slot="view-surface"`),
 * not on the document: a host page keeps its keys, and two workbenches on
 * one page answer only for themselves. A dialog or a popup the surface
 * opened is drawn outside it, so a key pressed there is that popup's own.
 * `handle` is read at the press, so a caller hands a fresh one each render.
 */
export function useSurfaceKey(
  anchor: RefObject<HTMLElement | null>,
  handle: (event: KeyboardEvent) => void,
): void {
  const latest = useRef(handle);
  // The element listened on, and how to stop: found again after every
  // render, since the anchor may be drawn only later (a save button that
  // appears once the permissions are in), and moved only when it changed.
  const attached = useRef<{ on: HTMLElement; off(): void } | null>(null);
  useEffect(() => {
    latest.current = handle;
    const surface =
      anchor.current?.closest<HTMLElement>('[data-slot="view-surface"]') ??
      null;
    if (surface === (attached.current?.on ?? null)) return;
    attached.current?.off();
    attached.current = null;
    if (!surface) return;
    const listen = (event: KeyboardEvent) => latest.current(event);
    surface.addEventListener('keydown', listen);
    attached.current = {
      on: surface,
      off: () => surface.removeEventListener('keydown', listen),
    };
  });
  useEffect(
    () => () => {
      attached.current?.off();
      attached.current = null;
    },
    [],
  );
}

/** Whether a key goes to a field of its own: typed, not a shortcut. */
export function typing(target: EventTarget | null): boolean {
  return (
    target instanceof Element &&
    target.closest(
      'input, textarea, select, [contenteditable=""], [contenteditable="true"], [role="textbox"], [role="combobox"], [role="searchbox"]',
    ) !== null
  );
}

/** ⌘S／Ctrl+S, and nothing else held. */
export function isSaveKey(event: KeyboardEvent): boolean {
  return (
    (event.metaKey || event.ctrlKey) &&
    !event.altKey &&
    !event.shiftKey &&
    event.key.toLowerCase() === 's'
  );
}

/** The keys the save button answers, said on it (`aria-keyshortcuts`). */
export const SAVE_KEYS = 'Meta+S Control+S';
/** The key that puts the keyboard in the search box. */
export const SEARCH_KEYS = '/';
/** The keys a record's detail steps back and on by (`aria-keyshortcuts`). */
export const PREVIOUS_RECORD_KEYS = 'K';
export const NEXT_RECORD_KEYS = 'J';
