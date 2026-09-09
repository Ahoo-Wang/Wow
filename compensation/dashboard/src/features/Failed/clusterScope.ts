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

import { filter } from "@ahoo-wang/fetcher-wow";

const clusterFields = {
  errorCode: "state.error.errorCode",
  contextName: "state.function.contextName",
  processorName: "state.function.processorName",
  functionName: "state.function.name",
  functionKind: "state.function.functionKind",
} as const;

export type ClusterScope = Record<keyof typeof clusterFields, string> & {
  start: number;
  end: number;
};

export function createClusterHref(
  cluster: Record<keyof typeof clusterFields, string>,
  window: { start: number; end: number },
): string {
  const identity = Object.fromEntries(
    Object.keys(clusterFields).map((key) => [
      key,
      cluster[key as keyof typeof clusterFields],
    ]),
  );
  return `/active?${new URLSearchParams({ cluster: JSON.stringify({ ...identity, start: window.start, end: window.end }) })}`;
}

export function parseClusterScope(
  value: string | null,
): ClusterScope | null | undefined {
  if (value === null) return null;
  try {
    const parsed = JSON.parse(value);
    if (
      !parsed ||
      Object.keys(clusterFields).some(
        (key) => typeof parsed[key] !== "string",
      ) ||
      !Number.isSafeInteger(parsed.start) ||
      !Number.isSafeInteger(parsed.end) ||
      parsed.start < 0 ||
      parsed.end <= parsed.start ||
      parsed.end > 8_640_000_000_000_000
    )
      return undefined;
    return Object.fromEntries(
      [...Object.keys(clusterFields), "start", "end"].map((key) => [
        key,
        parsed[key],
      ]),
    ) as ClusterScope;
  } catch {
    return undefined;
  }
}

export function clusterCondition(scope: ClusterScope) {
  return filter.and([
    ...Object.entries(clusterFields).map(([key, field]) =>
      filter.eq(field, scope[key as keyof typeof clusterFields]),
    ),
    filter.gte("state.executeAt", scope.start),
    filter.lt("state.executeAt", scope.end),
  ]);
}
