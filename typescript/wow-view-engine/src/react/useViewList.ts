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

import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  Issue,
  ViewInstanceSummary,
  ViewPreferences,
} from '../model/index.js';
import { orderSummaries, type ViewEngine } from '../runtime/index.js';
import type { ViewPermissions } from '../store/ViewStore.js';
import { toIssue } from './issues.js';

export interface ViewListState {
  /** Summaries in the order the workbench shows them. */
  items: ViewInstanceSummary[];
  preferences: ViewPreferences | null;
  permissions: ViewPermissions;
  /** The view to open when the caller names none; null until preferences settle. */
  defaultInstanceId: string | null;
  loading: boolean;
  error: Issue | null;
  /** Kept apart: without preferences the list still works, in server order. */
  preferencesError: Issue | null;
  reload(): void;
}

/** A completed load, tagged with the request it answered. */
interface Loaded<T> {
  key: string;
  value: T | null;
  error: Issue | null;
}

const NOTHING_LOADED: Loaded<never> = { key: '', value: null, error: null };

/**
 * The list, the preferences and the permissions of one definition.
 *
 * They load independently and never block each other: a failed list leaves an
 * open view alone, and failed preferences only drop back to server order.
 * Loading is derived from whether the answer on hand belongs to the current
 * request, so the effect writes state only when a response arrives.
 */
export function useViewList(
  engine: ViewEngine,
  definitionId: string,
): ViewListState {
  const [token, setToken] = useState(0);
  const [list, setList] =
    useState<Loaded<ViewInstanceSummary[]>>(NOTHING_LOADED);
  const [preferences, setPreferences] =
    useState<Loaded<ViewPreferences>>(NOTHING_LOADED);
  const key = `${token}:${definitionId}`;

  useEffect(() => {
    let cancelled = false;

    void engine.list(definitionId).then(
      value => {
        if (!cancelled) setList({ key, value, error: null });
      },
      (error: unknown) => {
        if (!cancelled)
          setList({
            key,
            value: null,
            error: toIssue(error, 'view.list.failed'),
          });
      },
    );

    void engine.preferences(definitionId).then(
      value => {
        if (!cancelled) setPreferences({ key, value, error: null });
      },
      (error: unknown) => {
        if (!cancelled)
          setPreferences({
            key,
            value: null,
            error: toIssue(error, 'view.preferences.failed'),
          });
      },
    );

    return () => {
      cancelled = true;
    };
  }, [engine, definitionId, key]);

  const permissions = useMemo(
    () => engine.permissions(definitionId),
    [engine, definitionId],
  );
  const reload = useCallback(() => setToken(current => current + 1), []);

  const current = list.key === key ? list : NOTHING_LOADED;
  const preferencesSettled = preferences.key === key;
  const currentPreferences = preferencesSettled ? preferences : NOTHING_LOADED;

  const items = useMemo(
    () =>
      currentPreferences.value
        ? orderSummaries(current.value ?? [], currentPreferences.value)
        : (current.value ?? []),
    [current.value, currentPreferences.value],
  );

  return {
    items,
    preferences: currentPreferences.value,
    permissions,
    // No default until preferences have settled: answering from server order
    // in the meantime opens one view and then swaps it for another.
    defaultInstanceId: !preferencesSettled
      ? null
      : currentPreferences.value
        ? engine.resolveDefault(items, currentPreferences.value)
        : (items[0]?.id ?? null),
    loading: list.key !== key,
    error: current.error,
    preferencesError: currentPreferences.error,
    reload,
  };
}
