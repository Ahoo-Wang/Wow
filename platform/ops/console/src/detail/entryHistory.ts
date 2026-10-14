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

import type { Fetcher } from "@ahoo-wang/fetcher";
import { filter } from "@ahoo-wang/wow-client";
import type { EventStreamRecord, EventView } from "@ahoo-wang/wow-client/v10";
import {
  ParkedEventQueryClient,
  type ApplyRetrySpecRequested,
  type MarkRecoverableRequested,
  type ParkedRecorded,
  type ParkedResolved,
  type ResumeRequested,
  type SkipRequested,
} from "@/generated/index.ts";

/** The most event streams an entry's history reads: the service's list limit. */
export const HISTORY_LIMIT = 1000;

/** An entry's event streams, oldest first. */
export type HistorySource = (id: string) => Promise<EventStreamRecord[]>;

/**
 * The event streams of parked entry `id`, from the ops service's `events`
 * query model (`POST /ops/parked/events/query`): one aggregate's streams,
 * in version order.
 */
export function historySource(fetcher: Fetcher): HistorySource {
  const events = new ParkedEventQueryClient({ fetcher });
  return async (id) =>
    (await events.list({
      filter: filter.eq("aggregateId", id),
      limit: HISTORY_LIMIT,
    })) as EventStreamRecord[];
}

/** What happened to an entry, as its timeline says it. */
export type HistoryKind =
  | "parked"
  | "parkedAgain"
  | "retried"
  | "updated"
  | "resumeRequested"
  | "skipRequested"
  | "markRecoverableRequested"
  | "applyRetrySpecRequested"
  | "resolved"
  | "unknown";

/** One step of an entry's history. */
export interface HistoryItem {
  /** Unique within the history: the stream's version and the event's place. */
  key: string;
  kind: HistoryKind;
  /** The event's own name, without its context and aggregate. */
  name: string;
  version: number;
  at: number;
  /** Who asked for it: an operator's id, for an operator's request. */
  operator?: string;
  /** What the step says, as label-free facts the reading words. */
  facts: Partial<HistoryFacts>;
}

export interface HistoryFacts {
  status: string;
  reason: string;
  retries: number;
  maxRetries: number;
  recoverable: string;
  outcome: string;
  minBackoff: string;
  executionTimeout: string;
}

/**
 * An event's name without its prefix: the log names an event by its
 * context and aggregate (`ops.parked.ParkedRecorded`).
 */
export function eventName(name: string): string {
  return name.slice(name.lastIndexOf(".") + 1);
}

function recorded(
  body: ParkedRecorded,
  last: ParkedRecorded["entry"] | undefined,
): Pick<HistoryItem, "kind" | "facts"> {
  const entry = body.entry;
  const facts: Partial<HistoryFacts> = {
    status: entry.status,
    retries: entry.retries,
    maxRetries: entry.retrySpec.maxRetries,
  };
  if (!last)
    return { kind: "parked", facts: { ...facts, reason: entry.reason } };
  if (entry.failedAt !== last.failedAt)
    return { kind: "parkedAgain", facts: { ...facts, reason: entry.reason } };
  if ((entry.retries ?? 0) > (last.retries ?? 0))
    return { kind: "retried", facts };
  return {
    kind: "updated",
    facts: { ...facts, recoverable: entry.recoverable ?? undefined },
  };
}

/** One event of a stream, read as a step of the entry's history. */
function step(
  event: EventView,
  last: ParkedRecorded["entry"] | undefined,
): Pick<HistoryItem, "kind" | "facts"> {
  switch (eventName(event.name)) {
    case "ParkedRecorded":
      return recorded(event.body as ParkedRecorded, last);
    case "ResumeRequested":
      return {
        kind: "resumeRequested",
        facts: { reason: (event.body as ResumeRequested).reason ?? undefined },
      };
    case "SkipRequested":
      return {
        kind: "skipRequested",
        facts: { reason: (event.body as SkipRequested).reason ?? undefined },
      };
    case "MarkRecoverableRequested":
      return {
        kind: "markRecoverableRequested",
        facts: {
          recoverable: (event.body as MarkRecoverableRequested).recoverable,
        },
      };
    case "ApplyRetrySpecRequested": {
      const spec = event.body as ApplyRetrySpecRequested;
      return {
        kind: "applyRetrySpecRequested",
        facts: {
          maxRetries: spec.maxRetries ?? undefined,
          minBackoff: spec.minBackoff ?? undefined,
          executionTimeout: spec.executionTimeout ?? undefined,
        },
      };
    }
    case "ParkedResolved":
      return {
        kind: "resolved",
        facts: { outcome: (event.body as ParkedResolved).outcome },
      };
    default:
      return { kind: "unknown", facts: {} };
  }
}

/**
 * An entry's history out of its event streams, newest first: when it was
 * parked and parked again, each retry its service recorded, each request
 * of an operator (with who asked), and how it was resolved.
 */
export function historyOf(
  streams: readonly EventStreamRecord[],
): HistoryItem[] {
  const items: HistoryItem[] = [];
  let last: ParkedRecorded["entry"] | undefined;
  for (const stream of [...streams].sort((a, b) => a.version - b.version))
    stream.events.forEach((event, index) => {
      const { kind, facts } = step(event, last);
      if (eventName(event.name) === "ParkedRecorded")
        last = (event.body as ParkedRecorded).entry;
      items.push({
        key: `${stream.version}:${index}`,
        kind,
        name: eventName(event.name),
        version: stream.version,
        at: stream.commitTime,
        ...(stream.operator?.id ? { operator: stream.operator.id } : {}),
        facts: Object.fromEntries(
          Object.entries(facts).filter(([, value]) => value !== undefined),
        ),
      });
    });
  return items.reverse();
}
