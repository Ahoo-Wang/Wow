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

import { useCallback, useMemo } from 'react';
import type { Issue, ViewConfig } from '../model/index.js';
import type { ViewRuntime } from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';

/**
 * What an open view uses that its source no longer admits, and the one
 * press that takes it out (capabilities.md Q2, `ViewRuntime.unavailable`).
 */
export interface UnavailableController {
  /** The findings, each at the place in the config it is about. */
  issues: Issue[];
  /** 「移除不可用的条件」: see `ViewRuntime.removeUnavailable`. */
  remove(): void;
}

/**
 * The view's unavailable parts, read again whenever its snapshot changes,
 * or `null` when there are none — so a surface draws its strip only where
 * there is something to say.
 */
export function useUnavailable(
  runtime: ViewRuntime<ViewConfig> | null,
): UnavailableController | null {
  const state = useViewRuntime(runtime);
  const issues = useMemo(
    () => (runtime && state ? runtime.unavailable() : []),
    [runtime, state],
  );
  const remove = useCallback(() => runtime?.removeUnavailable(), [runtime]);
  return issues.length > 0 ? { issues, remove } : null;
}
