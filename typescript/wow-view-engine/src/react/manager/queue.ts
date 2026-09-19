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
 * One write at a time, in the order the clicks came — and only among the
 * commands that answer for the same inputs.
 *
 * Two rows deleted in quick succession are two writes against one list and one
 * `pending` slot: run together, the second one's completion clears the slot
 * while the first is still going, and the first row stops showing progress it
 * is still making. Chaining also keeps the reload each landing triggers from
 * reading a list the other write is halfway through.
 *
 * The tag is what keeps that chaining honest. A hook that swaps its inputs —
 * a workbench moving to another definition or another engine — while a write
 * hangs would otherwise queue the new inputs' first command behind it, and the
 * row the user just clicked would sit there showing nothing.
 *
 * There is no React in here: a queue is a plain mutable holder a hook keeps in
 * a ref, so any command hook can take one.
 */

/** Whether two tags name the same inputs. */
export type TagEquality<Tag> = (one: Tag, other: Tag) => boolean;

export interface CommandQueue<Tag> {
  /**
   * The task at the back of the queue and the tag it was queued under, or null
   * when the queue is idle. Only ever read through {@link enqueue}.
   */
  back: { tag: Tag; chain: Promise<unknown> } | null;
  readonly sameTag: TagEquality<Tag>;
}

/**
 * An idle queue. `sameTag` decides which tasks chain: the default is identity,
 * which is what a tag that is one object — a runtime, an engine — wants; a tag
 * assembled per render passes its own comparison.
 */
export function createCommandQueue<Tag>(
  sameTag: TagEquality<Tag> = Object.is,
): CommandQueue<Tag> {
  return { back: null, sameTag };
}

/**
 * Runs `task` after whatever this tag already has in flight, and answers what
 * it answered.
 *
 * A task tagged differently from the one at the back starts a fresh queue: the
 * queue it found belongs to inputs the caller has moved on from, so it settles
 * on its own with nobody reading its result, and this task starts now rather
 * than behind a write nobody is watching. An idle queue also starts the task
 * now rather than a microtask later, so the row the user just clicked shows
 * progress in that same event.
 */
export function enqueue<Tag, T>(
  queue: CommandQueue<Tag>,
  tag: Tag,
  task: () => Promise<T>,
): Promise<T> {
  const ahead = queue.back;
  const mine = ahead && queue.sameTag(ahead.tag, tag) ? ahead.chain : null;
  // A task is expected to resolve whatever happened, so the rejection arm is
  // only there to keep one broken link from stalling the queue for good.
  const landed = mine === null ? task() : mine.then(task, task);
  const queued = { tag, chain: landed };
  queue.back = queued;
  // The queue goes idle again once the last task settles, so the next one is
  // not chained behind a promise that is already over.
  const release = () => {
    if (queue.back === queued) queue.back = null;
  };
  void landed.then(release, release);
  return landed;
}
