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

import { userEvent } from 'storybook/test';

/**
 * A pointer that presses a drag handle, walks down onto another row and lets
 * go — the gesture itself, rather than the drop it is supposed to produce.
 *
 * It belongs to the browser project and nowhere else. `@dnd-kit/dom` picks
 * its drop target by *measuring*: it compares the box being carried against
 * the boxes of every droppable, so in jsdom — where every box is 0×0 at
 * (0, 0) — collision detection has nothing to compare, and the whole chain
 * from pointerdown to a committed order goes untested. Here the boxes are
 * real, which is also why every step below is about geometry.
 *
 * Five things the gesture has to get right for the library to see it:
 *
 * - **One user-event instance for the whole gesture.** `userEvent.pointer`
 *   called afresh each time starts from a pointer with nothing pressed, so
 *   the release finds no button down and never dispatches a `pointerup` —
 *   the drag then hangs, and the drop that was to commit the order never
 *   happens. It fails silently, which is the worst way for it to fail.
 * - **Measure after the surface has settled.** A popover animates itself
 *   into place, and boxes read while it is still moving describe a layout
 *   that no longer exists a frame later: the drag then travels the wrong
 *   distance and lands a row short.
 * - **Press on the handle itself.** The default activation constraints let a
 *   mouse press that lands on the source's own handle start the drag at
 *   once; anywhere else it waits out a delay and 5px of travel first.
 * - **Move straight down.** What is carried follows the pointer by the same
 *   delta, and the collision is answered for *it* rather than for the
 *   cursor — a sideways component would offer the carried box to the
 *   detector off to one side of the column of rows.
 * - **Leave a frame between the moves.** The library batches pointer moves
 *   onto animation frames, so a burst of them followed immediately by the
 *   release drops with a target it never got round to computing.
 *
 * Only the press is aimed at the handle. From the moment the drag starts the
 * library puts `pointer-events: none` on what it is carrying, and user-event
 * refuses to dispatch at an element that cannot be pointed at — so the moves
 * and the release are aimed at the document body, where the sensor listens
 * for them anyway.
 */
export async function dragHandleOnto(
  handle: HTMLElement,
  target: HTMLElement,
  steps = 8,
): Promise<void> {
  await settled(handle);
  await settled(target);
  const grip = handle.getBoundingClientRect();
  const onto = target.getBoundingClientRect();
  const x = grip.left + grip.width / 2;
  const from = grip.top + grip.height / 2;
  const to = onto.top + onto.height / 2;
  const surface = handle.ownerDocument.body;
  const user = userEvent.setup();

  await user.pointer({
    keys: '[MouseLeft>]',
    target: handle,
    coords: { clientX: x, clientY: from },
  });
  await carrying(handle);

  for (let step = 1; step <= steps; step += 1) {
    await user.pointer({
      target: surface,
      coords: { clientX: x, clientY: from + ((to - from) * step) / steps },
    });
    await frame();
  }

  await user.pointer({
    keys: '[/MouseLeft]',
    target: surface,
    coords: { clientX: x, clientY: to },
  });
  await frame();
}

/**
 * Waits for the press to have become a drag, and says so when it has not.
 *
 * The press on a handle activates the sensor at once, so on a fast machine
 * this returns on the first frame — but a slow or headless run is exactly
 * where it would not, and a drag that never started goes on to release over
 * the right row and commit nothing. That failure reads as "the drop was
 * ignored", which is the wrong thing to go looking at.
 */
async function carrying(handle: HTMLElement): Promise<void> {
  const document = handle.ownerDocument;
  for (let tick = 0; tick < 30; tick += 1) {
    if (document.querySelector('[data-dragging]')) return;
    await frame();
  }
  throw new Error('Pressing the handle did not start a drag.');
}

/** Waits until this element stops moving, so its box can be trusted. */
async function settled(element: HTMLElement): Promise<void> {
  let previous = Number.NaN;
  for (let tick = 0; tick < 30; tick += 1) {
    const { top } = element.getBoundingClientRect();
    if (top === previous) return;
    previous = top;
    await frame();
  }
}

/** One painted frame, which is the unit the library schedules moves in. */
function frame(): Promise<void> {
  return new Promise(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
  );
}
