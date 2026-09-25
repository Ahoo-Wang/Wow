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
  systemInstanceId,
  type FilterNode,
  type FilterTree,
} from "@ahoo-wang/wow-view-engine";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";

/**
 * The failed executions' page, and the route parameters it reads: the open
 * view (`view`), the open execution (`id`), and the two narrowings a link
 * brings with it — a failure cluster (`cluster`) and an execution window
 * (`start`, `end`). The last two are the old queues' parameters, kept as they
 * were: alerts, tickets and the dashboard carry them.
 */
export const EXECUTIONS_PATH = "/executions";
export const VIEW_PARAM = "view";
export const CLUSTER_PARAM = "cluster";
export const START_PARAM = "start";
export const END_PARAM = "end";

/** The parameters that narrow the open view. */
export const SCOPE_PARAMS = [CLUSTER_PARAM, START_PARAM, END_PARAM] as const;

/** A system view of the failed executions, by its id in the definition. */
export function executionsView(id: string): string {
  return systemInstanceId(EXECUTION_FAILED, id);
}

/** The page on `view`, with the other `params` after it. */
export function executionsHref(
  view: string,
  params: Record<string, string> | URLSearchParams = {},
): string {
  const query = new URLSearchParams({ [VIEW_PARAM]: view });
  for (const [key, value] of new URLSearchParams(params))
    if (key !== VIEW_PARAM) query.append(key, value);
  return `${EXECUTIONS_PATH}?${query}`;
}

const clusterFields = {
  errorCode: "state.error.errorCode",
  contextName: "state.function.contextName",
  processorName: "state.function.processorName",
  functionName: "state.function.name",
  functionKind: "state.function.functionKind",
} as const;

type ClusterIdentity = Record<keyof typeof clusterFields, string>;

export interface ExecutionWindow {
  start: number;
  /** Exclusive. */
  end: number;
}

/** What a failure cluster link names, and the window it was counted in. */
export type ClusterScope = ClusterIdentity & ExecutionWindow;

const MAX_TIMESTAMP = 8_640_000_000_000_000;

function isValidBound(value: unknown): value is number {
  return (
    Number.isSafeInteger(value) &&
    (value as number) >= 0 &&
    (value as number) <= MAX_TIMESTAMP
  );
}

/**
 * The active executions of one failure cluster, as the dashboard counted
 * them: the cluster's full identity and the window it was counted in.
 */
export function createClusterHref(
  cluster: ClusterIdentity,
  window: ExecutionWindow,
): string {
  const identity = Object.fromEntries(
    Object.keys(clusterFields).map((key) => [
      key,
      cluster[key as keyof ClusterIdentity],
    ]),
  );
  return executionsHref(executionsView("active"), {
    [CLUSTER_PARAM]: JSON.stringify({
      ...identity,
      start: window.start,
      end: window.end,
    }),
  });
}

/**
 * A system view narrowed to the dashboard's execution window: the dashboard
 * counts `state.executeAt` within the applied range, so the bare view would
 * not hold what the number clicked counted.
 */
export function createExecutionWindowHref(
  view: string,
  window: ExecutionWindow,
): string {
  return executionsHref(executionsView(view), {
    [START_PARAM]: String(window.start),
    [END_PARAM]: String(window.end),
  });
}

/**
 * Reads a cluster back from its parameter.
 *
 * @returns the cluster, `null` when there is none, or `undefined` when the
 * parameter is there but malformed
 */
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
      !isValidBound(parsed.start) ||
      !isValidBound(parsed.end) ||
      parsed.end <= parsed.start
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

/**
 * Reads the window back from the parameters.
 *
 * @returns the window, `null` when the parameters are present but malformed,
 * or `undefined` when absent
 */
export function parseExecutionWindow(
  params: URLSearchParams,
): ExecutionWindow | null | undefined {
  const rawStart = params.get(START_PARAM);
  const rawEnd = params.get(END_PARAM);
  if (rawStart === null && rawEnd === null) return undefined;
  if (rawStart === null || rawEnd === null) return null;
  // Number("") and Number(" ") are 0, which would smuggle the Unix epoch past the bounds check.
  if (rawStart.trim() === "" || rawEnd.trim() === "") return null;
  const start = Number(rawStart);
  const end = Number(rawEnd);
  if (!isValidBound(start) || !isValidBound(end) || end <= start) return null;
  return { start, end };
}

/**
 * The window as a condition of the definition: `executeAt` from `start` up
 * to and including the millisecond before `end` — what the old queues'
 * exclusive `LT end` selected, in the operators a date-time field takes.
 */
function windowCondition({ start, end }: ExecutionWindow): FilterNode {
  return {
    field: "state.executeAt",
    operator: "BETWEEN",
    value: {
      type: "absolute",
      from: new Date(start).toISOString(),
      to: new Date(end - 1).toISOString(),
    },
  };
}

function clusterConditions(scope: ClusterScope): FilterNode[] {
  const fields = Object.keys(clusterFields) as (keyof ClusterIdentity)[];
  return [
    ...fields.map((key): FilterNode =>
      // The function kind is an enum, which takes a set.
      key === "functionKind"
        ? { field: clusterFields[key], operator: "IN", value: [scope[key]] }
        : { field: clusterFields[key], operator: "EQ", value: scope[key] },
    ),
    windowCondition(scope),
  ];
}

/** What a link's parameters narrow the page to. */
export type LinkScope =
  | { kind: "none" }
  | { kind: "scoped"; filter: FilterTree }
  | { kind: "invalid"; parameter: "cluster" | "window" };

/**
 * The narrowing a link brings — its cluster, its window, or both — as a
 * condition of the definition, for the open view's scope. A malformed one
 * is said, never dropped: dropping it would show every record under a link
 * that promised a few.
 */
export function readLinkScope(params: URLSearchParams): LinkScope {
  const cluster = parseClusterScope(params.get(CLUSTER_PARAM));
  if (cluster === undefined) return { kind: "invalid", parameter: "cluster" };
  const window = parseExecutionWindow(params);
  if (window === null) return { kind: "invalid", parameter: "window" };
  const children = [
    ...(cluster ? clusterConditions(cluster) : []),
    ...(window ? [windowCondition(window)] : []),
  ];
  return children.length === 0
    ? { kind: "none" }
    : { kind: "scoped", filter: { op: "and", children } };
}
