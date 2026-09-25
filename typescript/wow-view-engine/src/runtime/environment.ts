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

import type { QueryViolation } from '@ahoo-wang/wow-client';

/**
 * The only interface between a runtime and its host. Relative dates, the
 * refresh timer, the "is anyone looking" question and the failures a host
 * wants to hear about all pass through it, so `model` through `store` stay
 * free of the DOM and of the system clock, and a test can drive time without
 * faking globals.
 */
export interface RuntimeEnvironment {
  now(): Date;
  /** IANA zone used to resolve relative and preset dates. */
  timeZone: string;
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  visibility: VisibilitySource;
  /**
   * Told once of every failure the engine meets doing its work — a query, a
   * store call, an export, a render, a chart — for a host to log or send to
   * its monitoring (D40). The failure is still answered on screen as it
   * always was; this is the host's copy. Whatever it throws is dropped, and
   * left out, nothing is logged anywhere.
   */
  onError?(event: ViewErrorEvent): void;
}

/** What failed, by the part of the engine it failed in (D40). */
export type ViewErrorKind = 'query' | 'store' | 'export' | 'render' | 'chart';

/**
 * Where a failure happened. `operation` is always said; the rest only where
 * the failing part knows it.
 */
export interface ViewErrorContext {
  /**
   * What was being done: for `query` one of `query` (the view's own),
   * `summaries`, `totals`, `split`, `record` (one row read whole) or
   * `candidates` (a condition's values); for `store` the port's method
   * (`list`, `get`, `create`, `save`, `rename`, `delete`, `getPreferences`,
   * `setPreferences`); for `export` `fetch`, `deliver` or `image`; for
   * `render` `render`; for `chart` `load` or `draw`.
   */
  operation: string;
  definitionId?: string;
  /** The saved view, when the failing one is saved. */
  instanceId?: string;
  /** The open view (`ViewRuntime.id`) it failed in. */
  runtimeId?: string;
  /** A store write's idempotency key, the same across its retries. */
  requestId?: string;
  /** For `render` and `chart`: the boundary that caught it. */
  boundary?: string;
  /** For `render` and `chart` in a dashboard panel: which panel. */
  panelId?: string;
  /** For `render` and `chart`: React's component stack, where given. */
  componentStack?: string;
  /**
   * For `query`: which rule a Wow service said the query broke and where —
   * its stable `code` (wow-client's `QueryErrorCodes`, an open list), the
   * JSON path or logical field path, and its message. Absent when the
   * source said none: a budget rejection, a failure the service did not
   * answer, a source that is not Wow. The report waits for the body the
   * source answered with to be read, so this is there when it is said.
   */
  violation?: QueryViolation;
}

/** One failure, as `RuntimeEnvironment.onError` is told of it. */
export interface ViewErrorEvent {
  kind: ViewErrorKind;
  /** What was thrown or rejected with, as it was: a stack, a code, a body. */
  error: unknown;
  context: ViewErrorContext;
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
