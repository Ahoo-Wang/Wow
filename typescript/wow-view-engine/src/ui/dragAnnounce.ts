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
 * As much of a drag event as an announcement reads.
 *
 * The plugin's own option bag is untyped at the call site, so the callbacks
 * say what they need rather than inheriting `any` and losing every check
 * inside them.
 */
export interface DragAnnouncement {
  operation: { source?: { id: string | number } | null };
  canceled?: boolean;
}

/** The two sentences a drag says for itself, with the thing it carries named. */
export interface DragWording {
  picked(name: string): string;
  cancelled(name: string): string;
}

/**
 * What a screen reader hears while something is being dragged — the column
 * settings, the sort editor, the view manager and the panel's series list
 * are the lists that are, and they share this because the sentences differ
 * only in the words.
 *
 * The library ships English sentences built from the ids it is carrying,
 * which here are field names and instance ids: "Picked up draggable item
 * createdAt". Wording and language belong to the catalogue like every other
 * word on screen, and the ids are turned back into the labels the user is
 * reading.
 *
 * A completed drop says nothing on purpose. Where the row landed is announced
 * once by the list itself, for a drop and for an arrow key alike, so the same
 * move is never read out twice.
 */
export function dragAccessibility(
  instructions: string,
  say: DragWording,
  nameOf: (id: string) => string,
) {
  const named = (event: DragAnnouncement) =>
    event.operation.source ? nameOf(String(event.operation.source.id)) : null;

  return {
    screenReaderInstructions: { draggable: instructions },
    announcements: {
      dragstart: (event: DragAnnouncement) => {
        const name = named(event);
        return name === null ? undefined : say.picked(name);
      },
      dragend: (event: DragAnnouncement) => {
        const name = event.canceled ? named(event) : null;
        return name === null ? undefined : say.cancelled(name);
      },
    },
  };
}
