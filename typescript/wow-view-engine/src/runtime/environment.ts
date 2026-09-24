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
 * The only interface between a runtime and its host. Relative dates, the
 * refresh timer and the "is anyone looking" question all pass through it, so
 * `model` through `store` stay free of the DOM and of the system clock, and a
 * test can drive time without faking globals.
 */
export interface RuntimeEnvironment {
  now(): Date;
  /** IANA zone used to resolve relative and preset dates. */
  timeZone: string;
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  visibility: VisibilitySource;
}

export interface VisibilitySource {
  isVisible(): boolean;
  subscribe(listener: () => void): () => void;
}

/** A host that never hides, which is what Node and a test both are. */
export const ALWAYS_VISIBLE: VisibilitySource = {
  isVisible: () => true,
  subscribe: () => () => {},
};

/**
 * The default host: the ambient clock and timers, the ambient zone, and a page
 * that is always visible. `/react` replaces `visibility` with one backed by
 * `document.visibilityState`.
 */
export function defaultRuntimeEnvironment(
  overrides: Partial<RuntimeEnvironment> = {},
): RuntimeEnvironment {
  return {
    now: () => new Date(),
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    setTimeout: (callback, ms) => globalThis.setTimeout(callback, ms),
    clearTimeout: handle => {
      globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>);
    },
    visibility: ALWAYS_VISIBLE,
    ...overrides,
  };
}
