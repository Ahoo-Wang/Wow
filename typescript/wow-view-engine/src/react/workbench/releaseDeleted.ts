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

import { useEffect, useRef } from 'react';
import type { OpenViewState } from '../useViewEngine.js';

/**
 * Lets the pinned view go once it is gone.
 *
 * A delete from the manager disposes the runtime; `useOpenView` asks for the
 * id again and is told there is no such view. That is the cue — and only for
 * a view that was open, never for an id the host asked for and the store
 * never had, which is a mistake to report rather than to navigate away from.
 *
 * Every workbench needs it, and for the same reason: a `chosen` id left
 * pinned to a deleted view answers "not found" for as long as the page is
 * open, so the page would never move on to the view that is still there.
 */
export function useReleaseDeleted(
  /** The id actually being opened: the pin, or the list's default. */
  openId: string | null,
  /** The pin alone; only a pinned id is this hook's to release. */
  chosen: string | null,
  opened: OpenViewState,
  setChosen: (id: string | null) => void,
): void {
  const wasOpen = useRef<string | null>(null);
  const { runtime } = opened;
  const failed = opened.error?.code;
  useEffect(() => {
    if (runtime) wasOpen.current = openId;
  }, [runtime, openId]);
  useEffect(() => {
    if (chosen === null || chosen !== wasOpen.current) return;
    if (failed !== 'view.open.failed.not_found') return;
    wasOpen.current = null;
    setChosen(null);
  }, [chosen, failed, setChosen]);
}
