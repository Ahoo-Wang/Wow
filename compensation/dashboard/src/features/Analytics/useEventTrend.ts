/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)]
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

import { useEffect, useState } from "react";
import { aggregateExecutionFailedEvents } from "../../services";
import {
  createEventTrendQueries,
  mergeTrendRows,
  trendWindowKey,
} from "./analyticsQueries.ts";
import type {
  TrendPoint,
  TrendRow,
  TrendRowsBySeries,
  TrendSeriesKey,
  TrendWindow,
} from "./analyticsQueries.ts";
import type { AnalyticsSection } from "./useSnapshotAnalytics.ts";

interface EventTrendState {
  section: AnalyticsSection<TrendPoint[]>;
  windowKey: string;
}

export function useEventTrend(
  window: TrendWindow,
  refreshToken: number,
): AnalyticsSection<TrendPoint[]> {
  const requestedWindowKey = trendWindowKey(window);
  const [state, setState] = useState<EventTrendState>(() => ({
    section: { loading: true },
    windowKey: requestedWindowKey,
  }));

  useEffect(() => {
    const abortController = new AbortController();
    const queries = createEventTrendQueries(window);

    queueMicrotask(() => {
      if (!abortController.signal.aborted) {
        setState((current) => ({
          section:
            current.windowKey === requestedWindowKey
              ? { ...current.section, error: undefined, loading: true }
              : { loading: true },
          windowKey: requestedWindowKey,
        }));
      }
    });

    void Promise.all(
      (Object.keys(queries) as TrendSeriesKey[]).map(async (key) =>
        [
          key,
          await aggregateExecutionFailedEvents<TrendRow>(
            queries[key],
            undefined,
            abortController,
          ),
        ] as const,
      ),
    ).then(
      (rows) => {
        if (!abortController.signal.aborted) {
          setState({
            section: {
              data: mergeTrendRows(
                window,
                Object.fromEntries(rows) as TrendRowsBySeries,
              ),
              loading: false,
              updatedAt: Date.now(),
            },
            windowKey: requestedWindowKey,
          });
        }
      },
      (reason: unknown) => {
        if (abortController.signal.aborted) {
          return;
        }
        if (
          typeof reason === "object" &&
          reason !== null &&
          "name" in reason &&
          reason.name === "AbortError"
        ) {
          setState((current) => ({
            section: { ...current.section, loading: false },
            windowKey: requestedWindowKey,
          }));
          return;
        }
        setState((current) => ({
          section: {
            ...(current.windowKey === requestedWindowKey
              ? current.section
              : {}),
            error: reason instanceof Error ? reason : new Error(String(reason)),
            loading: false,
          },
          windowKey: requestedWindowKey,
        }));
      },
    );

    return () => abortController.abort();
  }, [window, refreshToken, requestedWindowKey]);

  return state.windowKey === requestedWindowKey
    ? state.section
    : { loading: true };
}
