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

import { message } from '../lib/snapshot.js';
import type { SessionStore } from './SessionStore.js';

/**
 * Shared write-failure landing: optional pre-action, finish the operation with
 * the failure patch, then rethrow. Callers keep their own currency guards and
 * compute `patch` from their own flags — only the ordering contract lives here.
 */
export function reconcileWriteFailure(
  error: unknown,
  options: {
    finish: (onSettled: () => void) => void;
    patch: () => void;
    beforePatch?: () => void;
  },
): never {
  options.beforePatch?.();
  options.finish(options.patch);
  throw error;
}

/** Convenience builder: patch body is `{ writeStatus idle, writeError, requiresReload? }`. */
export function writeFailurePatch(
  store: SessionStore,
  id: string,
  error: unknown,
  requiresReload: boolean,
): () => void {
  return () =>
    store.patch(id, {
      writeStatus: 'idle',
      writeError: message(error),
      ...(requiresReload ? { requiresReload: true } : {}),
    });
}
