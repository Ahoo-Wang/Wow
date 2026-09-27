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

import { desc, filter } from "@ahoo-wang/wow-client";
import type { ViewSource } from "@ahoo-wang/wow-view-engine";
import { useEffect, useState } from "react";
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

interface Answer {
  key: string;
  read: MomentsRead;
}

/**
 * An execution's story out of its event streams (`momentsOf`), read when the
 * detail draws it and again whenever the execution changed (`revision`, its
 * last event's time), so a command's result joins it.
 */
export function useMoments(
  source: ViewSource,
  id: string,
  revision: string,
): MomentsRead {
  const key = `${id}\u0000${revision}`;
  const [answer, setAnswer] = useState<Answer | null>(null);
  useEffect(() => {
    const abort = new AbortController();
    source
      .paged(
        {
          filter: filter.eq("aggregateId", id),
          // The latest, when there are more than a page: the story is read
          // from its end.
          sort: [desc("version")],
          pagination: { index: 1, size: STORY_LIMIT },
        },
        undefined,
        abort,
      )
      .then(
        ({ total, list }) =>
          setAnswer({
            key,
            read: { status: "read", moments: momentsOf(list), total },
          }),
        () => {
          if (!abort.signal.aborted)
            setAnswer({ key, read: { status: "failed" } });
        },
      );
    return () => abort.abort();
  }, [source, id, key]);
  return answer?.key === key ? answer.read : { status: "reading" };
}
