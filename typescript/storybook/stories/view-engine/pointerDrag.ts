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
 * The browser's own mouse, where the story runs under the test runner
 * (`.storybook/realMouse.ts`, handed over in `.storybook/vitest.setup.ts`).
 * Points are this document's client coordinates. Absent in Storybook's own
 * panel, which has no hold of the browser's input.
 */
export interface RealMouse {
  move(x: number, y: number): Promise<void>;
  down(x: number, y: number): Promise<void>;
  up(x: number, y: number): Promise<void>;
  /** Off the story altogether, so nothing is left hovered under it. */
  away(): Promise<void>;
}

declare global {
  var storybookRealMouse: RealMouse | undefined;
}

/**
 * The mouse a gesture is made with: the browser's own, or — in Storybook's
 * panel — a stand-in built from `user-event`, one instance for the whole
 * gesture (called afresh, `userEvent.pointer` starts from a pointer with
 * nothing pressed, and the release never dispatches a `pointerup`).
 *
 * The stand-in is Chromium's and WebKit's mouse only. `user-event` numbers
 * its mouse 1; Firefox numbers its own 0 and knows it only once a real mouse
 * has moved, so a library that captures the pointer that started a drag
 * (`@dnd-kit/dom` does, and cancels the drag when it cannot) is refused there
 * at the first step — which is why the runner drives the real one.
 *
 * `pressed` is what the stand-in aims the press at; its moves and release go
 * to `surface`. From the moment a drag starts the library puts
 * `pointer-events: none` on what it is carrying, and user-event refuses to
 * dispatch at an element that cannot be pointed at.
 */
function mouseFor(pressed: HTMLElement, surface: HTMLElement): RealMouse {
  const real = globalThis.storybookRealMouse;
  if (real) return real;
  const user = userEvent.setup();
  const at = (clientX: number, clientY: number) => ({ clientX, clientY });
  return {
    move: (x, y) => user.pointer({ target: surface, coords: at(x, y) }),
    down: (x, y) =>
      user.pointer({
        keys: '[MouseLeft>]',
        target: pressed,
        coords: at(x, y),
      }),
    up: (x, y) =>
      user.pointer({ keys: '[/MouseLeft]', target: surface, coords: at(x, y) }),
    away: async () => {},
  };
}

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
 * Four things the gesture has to get right for the library to see it:
 *
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
 * The mouse is the browser's own (`mouseFor`), and it leaves the story when
 * the gesture is over.
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
  const mouse = mouseFor(handle, handle.ownerDocument.body);

  await mouse.down(x, from);
  await carrying(handle);

  for (let step = 1; step <= steps; step += 1) {
    await mouse.move(x, from + ((to - from) * step) / steps);
    await frame();
  }

  await mouse.up(x, to);
  await frame();
  await mouse.away();
}

/**
 * A pointer that takes hold of a column header's edge, walks sideways and
 * lets go.
 *
 * It belongs to the browser project for the same reason the drag above does,
 * and a narrower one of its own: the gesture reads the header's box on the
 * way in and writes a width on the way through, and in jsdom every box is
 * 0×0 — the drag would start from a width nobody has and end at one nobody
 * can check. Two of the four rules above still hold here (a settled surface,
 * a frame between the moves); the other two are about a library's collision
 * detection, which has nothing to do with this. The handle stays pointable
 * throughout — nothing is being carried — so the stand-in aims every event
 * at it, and the press binds the pointer to it anyway.
 */
export async function dragEdgeBy(
  handle: HTMLElement,
  by: number,
  steps = 6,
): Promise<void> {
  await settled(handle);
  const grip = handle.getBoundingClientRect();
  const from = grip.left + grip.width / 2;
  const y = grip.top + grip.height / 2;
  const mouse = mouseFor(handle, handle);

  await mouse.down(from, y);

  for (let step = 1; step <= steps; step += 1) {
    await mouse.move(from + (by * step) / steps, y);
    await frame();
  }

  await mouse.up(from + by, y);
  await frame();
  await mouse.away();
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
