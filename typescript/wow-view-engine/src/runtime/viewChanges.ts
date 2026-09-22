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

import { issue } from '../filter/index.js';
import type { Issue } from '../model/index.js';
import { listenerSet } from './listeners.js';

/**
 * The write that changed a list — **not** the kind of the view it changed.
 *
 * These four are what a list of views can see: one appeared, one was saved
 * under a title or an audience it did not have, one was renamed, one is gone.
 * A preference write is not among them: it changes the order and the default,
 * which the caller that wrote them already holds.
 */
export type ViewChangeKind = 'create' | 'save' | 'rename' | 'delete';

/** What a confirmed write changed about one definition's list of views. */
export interface ViewChange {
  definitionId: string;
  kind: ViewChangeKind;
  id: string;
}

export type ViewChangeListener = (change: ViewChange) => void;

/**
 * Who is listening for list changes, and what they are told.
 *
 * The engine is the only place that knows when a write lands, so it says so
 * rather than leaving every caller to remember (D15). The shape is a
 * runtime's: `subscribe` returns the way to stop listening.
 */
export class ViewChanges {
  /**
   * A listener that throws is contained: `emit` is called from inside a write
   * that has just been confirmed, and letting it out would record a write
   * that landed as an outcome the user is asked to retry.
   */
  private readonly listeners = listenerSet<[ViewChange]>(error =>
    this.report(
      issue('view.change.notify-failed', [], { reason: reasonOf(error) }),
    ),
  );
  private readonly report: (found: Issue) => void;

  constructor(report: (found: Issue) => void) {
    this.report = report;
  }

  subscribe(listener: ViewChangeListener): () => void {
    return this.listeners.subscribe(listener);
  }

  emit(change: ViewChange): void {
    this.listeners.emit(change);
  }

  clear(): void {
    this.listeners.clear();
  }
}

function reasonOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
