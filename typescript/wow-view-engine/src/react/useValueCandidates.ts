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
import type { Issue } from '../model/index.js';
import type { QueryStatus, ValueCandidateSource } from '../runtime/index.js';
import { sourceIssue } from '../runtime/failure/issues.js';

/** How long typing may pause before the values holding it are asked for. */
export const VALUE_CANDIDATE_DEBOUNCE_MS = 250;

/** When `useValueCandidates` asks, and for which text. */
export interface ValueCandidatesOptions {
  /** What is typed: the fragment the values are narrowed by. */
  query: string;
  /** Whether the list is open; nothing is asked while it is not. */
  active: boolean;
}

export interface ValueCandidatesController {
  /**
   * `idle` until the list is first opened; `loading` while an answer is on
   * its way, with the last one still in `values`; `error` when it failed,
   * with the last one kept and `error` saying why.
   */
  status: QueryStatus;
  /**
   * The text `values` answer. While it differs from what is typed now, the
   * answer to the new text is still on its way, and a control narrows what
   * it holds in the meantime rather than showing a list the text has left.
   */
  query: string;
  values: readonly ValueCandidate[];
  /** See `ValueCandidates.complete`; false until an answer says otherwise. */
  complete: boolean;
  /**
   * Why the last answer failed: `filter.values.failed`, with what the
   * source said in its own words (`sourceReason`) as its `reason`.
   */
  error: Issue | null;
  /** Asks the same question again after a failure. */
  retry(): void;
}

interface Answered {
  source: ValueCandidateSource | null | undefined;
  status: QueryStatus;
  query: string;
  values: readonly ValueCandidate[];
  complete: boolean;
  error: Issue | null;
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
  { query, active }: ValueCandidatesOptions,
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
        settle({ status: 'loading', error: null });
        source.search(text, controller.signal).then(
          answer =>
            settle({
              status: 'success',
              query: text,
              values: answer.values,
              complete: answer.complete,
            }),
          async (caught: unknown) => {
            const error = await sourceIssue(caught, 'filter.values.failed');
            settle({ status: 'error', error });
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
    error: current.error,
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
    error: null,
  };
}
