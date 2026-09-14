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

import { filter } from "@ahoo-wang/fetcher-wow";

export interface ExecutionWindow {
  start: number;
  end: number;
}

const MAX_TIMESTAMP = 8_640_000_000_000_000;

function isValidBound(value: unknown): value is number {
  return (
    Number.isSafeInteger(value) &&
    (value as number) >= 0 &&
    (value as number) <= MAX_TIMESTAMP
  );
}

/**
 * Builds a queue link that keeps the dashboard's execution window.
 *
 * The dashboard counts are scoped to `state.executeAt` within the applied range, so a bare route
 * would open a queue whose contents do not correspond to the number that was clicked.
 */
export function createExecutionWindowHref(
  path: string,
  window: ExecutionWindow,
): string {
  return `${path}?${new URLSearchParams({
    start: String(window.start),
    end: String(window.end),
  })}`;
}

/**
 * Reads the window back from the queue URL.
 *
 * @returns the window, `null` when the parameters are present but malformed, or `undefined` when absent
 */
export function parseExecutionWindow(
  params: URLSearchParams,
): ExecutionWindow | null | undefined {
  const rawStart = params.get("start");
  const rawEnd = params.get("end");
  if (rawStart === null && rawEnd === null) {
    return undefined;
  }
  if (rawStart === null || rawEnd === null) {
    return null;
  }
  // Number("") and Number(" ") are 0, which would smuggle the Unix epoch past the bounds check.
  if (rawStart.trim() === "" || rawEnd.trim() === "") {
    return null;
  }
  const start = Number(rawStart);
  const end = Number(rawEnd);
  if (!isValidBound(start) || !isValidBound(end) || end <= start) {
    return null;
  }
  return { start, end };
}

export function executionWindowCondition(window: ExecutionWindow) {
  return filter.and([
    filter.gte("state.executeAt", window.start),
    filter.lt("state.executeAt", window.end),
  ]);
}
