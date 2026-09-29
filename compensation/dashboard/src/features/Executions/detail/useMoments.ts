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
  desc,
  filter,
  type FilterPagedQuery,
} from "@ahoo-wang/wow-client";
import { usePagedQuery } from "@ahoo-wang/wow-react";
import type { ViewSource } from "@ahoo-wang/wow-view-engine";
import { momentsOf, type Moment } from "./attempts.ts";

/**
 * The most streams read for one execution's story: the largest page the
 * service answers over HTTP (Wow refuses a page size above 100). A retry is
 * two streams, so this is some fifty attempts — past the retry limits anyone
 * sets; beyond it the latest are read, the story says it is cut short, and
 * the full history is below it.
 */
export const STORY_LIMIT = 100;

export type MomentsRead =
  | { status: "reading" }
  | { status: "failed" }
  | { status: "read"; moments: Moment[]; total: number };

/**
 * The page the story reads, with what it is read for: the execution and its
 * revision. `key` never reaches the source; it makes a new revision a new
 * query, so the hook reads again, and it names what the answer shown was
 * asked for.
 */
type StoryQuery = FilterPagedQuery & { readonly key: string };

/**
 * An execution's story out of its event streams (`momentsOf`), read when the
 * detail draws it and again whenever the execution changed (`revision`, its
 * last event's time), so a command's result joins it.
 *
 * wow-react's `usePagedQuery` runs the read: the latest query wins, so an
 * earlier execution's answer that lands late never takes the place of the
 * current one's.
 */
export function useMoments(
  source: ViewSource,
  id: string,
  revision: string,
): MomentsRead {
  const key = `${id}\u0000${revision}`;
  const { status, result, getQuery } = usePagedQuery<
    Moment,
    string,
    Error,
    StoryQuery
  >({
    query: {
      key,
      filter: filter.eq("aggregateId", id),
      // The latest, when there are more than a page: the story is read from
      // its end.
      sort: [desc("version")],
      pagination: { index: 1, size: STORY_LIMIT },
    },
    execute: async ({ filter: where, sort, pagination }, attributes, abort) => {
      const { total, list } = await source.paged(
        { filter: where, sort, pagination },
        attributes,
        abort,
      );
      return { total, list: momentsOf(list) };
    },
  });
  // Until the hook has started this key's read, what it holds answers the
  // one before.
  if (getQuery()?.key !== key) return { status: "reading" };
  if (status === "success" && result)
    return { status: "read", moments: result.list, total: result.total };
  if (status === "error") return { status: "failed" };
  return { status: "reading" };
}
