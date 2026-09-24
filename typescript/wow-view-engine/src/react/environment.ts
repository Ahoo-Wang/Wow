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

import {
  ALWAYS_VISIBLE,
  defaultRuntimeEnvironment,
  type RuntimeEnvironment,
  type VisibilitySource,
} from '../runtime/index.js';

/**
 * Page visibility as the browser reports it, so a hidden tab stops
 * auto-refreshing. Falls back to "always visible" where there is no document,
 * which is what server rendering and a plain Node test are.
 */
export function documentVisibility(): VisibilitySource {
  if (typeof document === 'undefined') return ALWAYS_VISIBLE;
  return {
    isVisible: () => document.visibilityState !== 'hidden',
    subscribe: listener => {
      document.addEventListener('visibilitychange', listener);
      return () => {
        document.removeEventListener('visibilitychange', listener);
      };
    },
  };
}

/** The host a browser provides: ambient clock and timers, real visibility. */
export function browserRuntimeEnvironment(
  overrides: Partial<RuntimeEnvironment> = {},
): RuntimeEnvironment {
  return defaultRuntimeEnvironment({
    visibility: documentVisibility(),
    ...overrides,
  });
}
