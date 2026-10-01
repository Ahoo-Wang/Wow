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
 * A grid item's `touchstart` listeners, held back while the board is only
 * read.
 *
 * `react-grid-layout` wraps every item in `react-draggable`'s `DraggableCore`
 * whether or not a panel may be moved, and that component adds a
 * `touchstart` listener to the item as it mounts — non-passive and in the
 * capture phase, so it may cancel a drag's scroll — and takes it away only
 * as it unmounts; `disabled` stops the drag and leaves the listener. On a
 * board nobody builds, every scroll a finger starts on a panel then waits
 * for the main thread to say it will not be cancelled, which on a busy page
 * is a scroll that sticks (a read board's charts draw on that thread).
 *
 * Turning the listener off by unmounting it would remount the panel with it:
 * the item is the panel's parent, and entering or leaving the building would
 * run every panel's query again. So the item keeps what the library adds
 * and hands it to the element only while the board is built: `open` adds
 * every held listener, `close` takes them away again, and a listener added
 * or removed meanwhile is only noted. Every other event goes straight
 * through. The library is not told — it added its listener once, and it
 * gets its drag whenever the board is built.
 */
export interface TouchGate {
  /** Hands the held listeners to the element (`true`) or takes them back. */
  set(open: boolean): void;
  /** Puts the element's own methods back. */
  release(): void;
}

const GATED = 'touchstart';

export function gateTouchStart(element: HTMLElement): TouchGate {
  const add = element.addEventListener;
  const remove = element.removeEventListener;
  // A listener is one per callback and capture phase, as the DOM counts it.
  const held = new Map<
    string,
    {
      listener: EventListenerOrEventListenerObject;
      options: boolean | AddEventListenerOptions | undefined;
    }
  >();
  const keys = new Map<EventListenerOrEventListenerObject, number>();
  const keyOf = (
    listener: EventListenerOrEventListenerObject,
    options: boolean | EventListenerOptions | undefined,
  ) => {
    if (!keys.has(listener)) keys.set(listener, keys.size);
    const capture =
      typeof options === 'boolean' ? options : options?.capture === true;
    return `${keys.get(listener)}:${capture}`;
  };
  let open = false;

  element.addEventListener = function (
    this: HTMLElement,
    type: string,
    listener: EventListenerOrEventListenerObject | null,
    options?: boolean | AddEventListenerOptions,
  ) {
    if (!listener) return;
    if (type !== GATED) return add.call(this, type, listener, options);
    held.set(keyOf(listener, options), { listener, options });
    if (open) add.call(this, type, listener, options);
  } as HTMLElement['addEventListener'];

  element.removeEventListener = function (
    this: HTMLElement,
    type: string,
    listener: EventListenerOrEventListenerObject | null,
    options?: boolean | EventListenerOptions,
  ) {
    if (!listener) return;
    if (type === GATED) held.delete(keyOf(listener, options));
    return remove.call(this, type, listener, options);
  } as HTMLElement['removeEventListener'];

  return {
    set(next) {
      if (next === open) return;
      open = next;
      for (const { listener, options } of held.values())
        if (open) add.call(element, GATED, listener, options);
        else remove.call(element, GATED, listener, options);
    },
    release() {
      // What the element holds now stays; only the methods are its own again.
      delete (element as Partial<HTMLElement>).addEventListener;
      delete (element as Partial<HTMLElement>).removeEventListener;
    },
  };
}
