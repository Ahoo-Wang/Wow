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
 * Admission and scheduling budgets. Configs arrive from a store, so the
 * validators apply these before anything reaches a compiler or a timer.
 */
export interface RuntimeLimits {
  /** Data queries running at once across every runtime of one engine. */
  maxConcurrentQueries: number;
  /** Queued data queries; beyond this a request is rejected, not buffered. */
  maxQueuedQueries: number;
  /** Upper bound of `RecordViewConfig.pageSize`. */
  maxPageSize: number;
  /** Upper bound of `AnalysisViewConfig.limit`. */
  maxAnalysisRows: number;
  /**
   * Rows one export may carry away, however many the conditions match.
   *
   * An export pages the source under the applied conditions with no regard
   * for the page on screen, so it is the one command here whose cost is the
   * size of the *result* rather than of a page. The ceiling is what keeps a
   * mis-aimed export from asking a backend for a million rows and building a
   * string of them in a browser tab; over it, the count is put to the user
   * before anything is fetched.
   */
  exportMax: number;
  /** Shortest auto-refresh interval, in seconds. */
  minRefreshInterval: number;
  /** Longest auto-refresh interval, in seconds; keeps the timer in range. */
  maxRefreshInterval: number;
  /** Nesting depth of a filter tree, checked iteratively before recursion. */
  maxFilterDepth: number;
  /** Total nodes of a filter tree, groups included. */
  maxFilterNodes: number;
  /** Panels of one dashboard, checked before any child runtime is created. */
  maxDashboardPanels: number;
}

export const DEFAULT_RUNTIME_LIMITS: Readonly<RuntimeLimits> = Object.freeze({
  maxConcurrentQueries: 4,
  maxQueuedQueries: 32,
  maxPageSize: 200,
  maxAnalysisRows: 10_000,
  exportMax: 10_000,
  minRefreshInterval: 5,
  maxRefreshInterval: 86_400,
  maxFilterDepth: 8,
  maxFilterNodes: 256,
  maxDashboardPanels: 24,
});

/**
 * Longest timer delay a 32-bit millisecond counter holds. `maxRefreshInterval`
 * stays well below it, so a long interval never collapses into a tight loop.
 */
export const MAX_TIMER_DELAY_MS = 2_147_483_647;
