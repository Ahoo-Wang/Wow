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

import { useCallback, useEffect, useRef, useState } from 'react';
import type { FieldOption } from '../../../model/index.js';
import type { OptionSource } from '../../../runtime/index.js';

/** How long typing may pause before the source is asked. */
export const SEARCH_DEBOUNCE_MS = 250;

export interface CandidateState {
  /** `idle` until the first search is asked for; `failed` keeps the last page. */
  status: 'idle' | 'loading' | 'success' | 'failed';
  items: FieldOption[];
  /** Where the next page starts, or `null` when this was the last. */
  nextCursor: string | null;
  /** True while a further page is on its way. */
  loadingMore: boolean;
}

const IDLE: CandidateState = {
  status: 'idle',
  items: [],
  nextCursor: null,
  loadingMore: false,
};

/**
 * The candidates a `reference` editor offers, searched from its source.
 *
 * Nothing is asked until the list is opened, and a search is asked only once
 * typing has paused: a pill on a saved view must not cost a request per
 * render, and a source must not be asked once per keystroke. Every request
 * carries an `AbortSignal`, and a query that changes under a request aborts
 * it — the source is told, and its answer, if it still arrives, is dropped.
 * A page that fails keeps what was already listed and says so, with a retry.
 */
export function useCandidates(
  source: OptionSource | null | undefined,
  query: string,
  active: boolean,
): { state: CandidateState; more(): void; retry(): void } {
  const [state, setState] = useState<CandidateState>(IDLE);
  const [attempt, setAttempt] = useState(0);
  const inFlight = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!source || !active) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      inFlight.current?.abort();
      inFlight.current = controller;
      setState(current => ({ ...current, status: 'loading' }));
      source.search({ query }, controller.signal).then(
        page => {
          if (controller.signal.aborted) return;
          setState({
            status: 'success',
            items: page.items,
            nextCursor: page.nextCursor,
            loadingMore: false,
          });
        },
        () => {
          if (controller.signal.aborted) return;
          setState(current => ({
            ...current,
            status: 'failed',
            loadingMore: false,
          }));
        },
      );
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [source, query, active, attempt]);

  const more = useCallback(() => {
    const cursor = state.nextCursor;
    if (!source || cursor === null || state.loadingMore) return;
    const controller = new AbortController();
    inFlight.current?.abort();
    inFlight.current = controller;
    setState(current => ({ ...current, loadingMore: true }));
    source.search({ query, cursor }, controller.signal).then(
      page => {
        if (controller.signal.aborted) return;
        setState(current => ({
          status: 'success',
          items: [...current.items, ...page.items],
          nextCursor: page.nextCursor,
          loadingMore: false,
        }));
      },
      () => {
        if (controller.signal.aborted) return;
        setState(current => ({
          ...current,
          status: 'failed',
          loadingMore: false,
        }));
      },
    );
  }, [source, query, state.nextCursor, state.loadingMore]);

  const retry = useCallback(() => setAttempt(count => count + 1), []);

  return { state, more, retry };
}
