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
 * The tag is what keeps that chaining honest, and it cuts both ways. A hook
 * that swaps its inputs — a workbench moving to another definition, or to
 * another view — while a write hangs must not queue the new inputs' first
 * command behind it, or the row the user just clicked sits there showing
 * nothing. And it must not lose the old chain either: the user can come back
 * to those inputs while their write is still in flight, and a second write
 * against the same target is one the engine refuses with
 * `view.write.in-flight`, which the caller would report as the failure of the
 * click that was only second. So the queue holds one chain per tag rather
 * than one altogether, and each is dropped as it settles.
 *
 * There is no React in here: a queue is a plain mutable holder a hook keeps in
 * a ref, so any command hook can take one.
 */

/** Whether two tags name the same inputs. */
export type TagEquality<Tag> = (one: Tag, other: Tag) => boolean;

export interface CommandQueue<Tag> {
  /**
   * The task at the back of each tag's chain, one entry per tag with something
   * still in flight. An entry is dropped as its chain settles, so an idle
   * queue is empty. Only ever read through {@link enqueue}.
   */
  backs: { tag: Tag; chain: Promise<unknown> }[];
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
  return { backs: [], sameTag };
}

/**
 * Runs `task` after whatever this tag already has in flight, and answers what
 * it answered.
 *
 * A task whose tag has nothing in flight starts now rather than a microtask
 * later, so the row the user just clicked shows progress in that same event —
 * and that is true of a tag nobody has used yet as much as of one whose last
 * write is over. Another tag's chain is never waited on: it answers for inputs
 * this task has nothing to do with.
 */
export function enqueue<Tag, T>(
  queue: CommandQueue<Tag>,
  tag: Tag,
  task: () => Promise<T>,
): Promise<T> {
  const at = queue.backs.findIndex(entry => queue.sameTag(entry.tag, tag));
  // A task is expected to resolve whatever happened, so the rejection arm is
  // only there to keep one broken link from stalling the queue for good.
  const landed = at < 0 ? task() : queue.backs[at].chain.then(task, task);
  const queued = { tag, chain: landed };
  if (at < 0) queue.backs.push(queued);
  else queue.backs[at] = queued;
  // This tag goes idle again once its last task settles, so the next one is
  // not chained behind a promise that is already over — and a tag the caller
  // never comes back to leaves nothing behind.
  const release = () => {
    const mine = queue.backs.indexOf(queued);
    if (mine >= 0) queue.backs.splice(mine, 1);
  };
  void landed.then(release, release);
  return landed;
}
