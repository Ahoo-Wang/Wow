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

import { useCallback, useEffect, useState } from 'react';
import type { ValueCandidate } from '../analysis/index.js';
import { sourceReason, type ValueCandidateSource } from '../runtime/index.js';

/** How long typing may pause before the values holding it are asked for. */
export const VALUE_CANDIDATE_DEBOUNCE_MS = 250;

export interface ValueCandidatesController {
  /**
   * `idle` until the list is first opened; `loading` while an answer is on
   * its way, with the last one still in `values`; `error` when it failed,
   * with the last one kept and `reason` saying why.
   */
  status: 'idle' | 'loading' | 'success' | 'error';
  /**
   * The text `values` answer. While it differs from what is typed now, the
   * answer to the new text is still on its way, and a control narrows what
   * it holds in the meantime rather than showing a list the text has left.
   */
  query: string;
  values: readonly ValueCandidate[];
  /** See `ValueCandidates.complete`; false until an answer says otherwise. */
  complete: boolean;
  /** What the source said went wrong, in its own words (`sourceReason`). */
  reason: string | null;
  /** Asks the same question again after a failure. */
  retry(): void;
}

interface Answered {
  source: ValueCandidateSource | null | undefined;
  status: ValueCandidatesController['status'];
  query: string;
  values: readonly ValueCandidate[];
  complete: boolean;
  reason: string | null;
}

const NONE: readonly ValueCandidate[] = [];

/**
 * A condition's values offered from the data: the list of one
 * `ValueCandidateSource`, asked for while `active`.
 *
 * Nothing is asked until the list opens, because a panel holding a dozen
 * conditions must not cost a dozen aggregations to draw. The unnarrowed list
 * is asked for at once; a typed fragment only once typing has paused, and
 * every change of either aborts the request before it — as does closing the
 * list, which is what `active` going false means. The runtime keeps what
 * came back for the life of the view, so reopening the list asks nothing.
 *
 * The answer is kept per source: another field is another list, and what
 * the last one said is not shown under it for a moment.
 */
export function useValueCandidates(
  source: ValueCandidateSource | null | undefined,
  query: string,
  active: boolean,
): ValueCandidatesController {
  const [answered, setAnswered] = useState<Answered>(() => blank(source));
  const [attempt, setAttempt] = useState(0);
  const current = answered.source === source ? answered : blank(source);
  const text = query.trim();

  useEffect(() => {
    if (!source || !active) return;
    const controller = new AbortController();
    const settle = (patch: Partial<Answered>) => {
      if (controller.signal.aborted) return;
      setAnswered(last => ({
        ...(last.source === source ? last : blank(source)),
        ...patch,
      }));
    };
    const timer = setTimeout(
      () => {
        settle({ status: 'loading', reason: null });
        source.search(text, controller.signal).then(
          answer =>
            settle({
              status: 'success',
              query: text,
              values: answer.values,
              complete: answer.complete,
            }),
          async (error: unknown) => {
            const reason = await sourceReason(error);
            settle({ status: 'error', reason });
          },
        );
      },
      text ? VALUE_CANDIDATE_DEBOUNCE_MS : 0,
    );
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [source, text, active, attempt]);

  const retry = useCallback(() => setAttempt(count => count + 1), []);

  return {
    status: current.status,
    query: current.query,
    values: current.values,
    complete: current.complete,
    reason: current.reason,
    retry,
  };
}

function blank(source: ValueCandidateSource | null | undefined): Answered {
  return {
    source,
    status: 'idle',
    query: '',
    values: NONE,
    complete: false,
    reason: null,
  };
}
