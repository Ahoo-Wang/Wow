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
  ViewKind,
  ViewPreferences,
} from '../model/index.js';
import type { ViewEngine } from '../runtime/index.js';
import { orderSummaries } from '../runtime/preferences.js';
import type { ViewPermissions } from '../store/ViewStore.js';
import { toIssue } from './issues.js';

export interface ViewListOptions {
  /**
   * Narrows the list to these kinds, before it is ordered and before a
   * default is resolved from it.
   *
   * One data definition holds record and analysis instances together, and a
   * workbench draws the kinds it has parts for — both by default, one when a
   * host narrows it (D20). Without this the sidebar offers views the body
   * cannot render, and the effective default may land on one of them — which
   * is a blank page rather than a view. Left out, every kind is listed, which
   * is what a dashboard definition wants.
   */
  kinds?: readonly ViewKind[];
}

/** What a caller already knows about the list it is asking to be read again. */
export interface ViewListReloadOptions {
  /**
   * An instance that is gone: it is dropped from the summaries kept on screen
   * for the duration of the reload, and `defaultInstanceId` never names it,
   * before or after the answer lands.
   *
   * A reload refreshes rather than blanks, so without this the row deleted a
   * moment ago goes on being listed — and named as the default — until the
   * store answers, and a workbench riding on the default reopens a runtime
   * that has just been disposed.
   *
   * A delete through the engine fills this in by itself: the hook hears the
   * engine say which id went (D15). What is left for a caller is the delete
   * the engine never saw — a row removed by the host's own backend.
   */
  without?: string;
}

export interface ViewListState {
  /** Summaries in the order the workbench shows them. */
  items: ViewInstanceSummary[];
  /**
   * The same summaries before `options.kinds` narrowed them, in the same
   * order. Equal to `items` when no kinds were asked for.
   *
   * A reorder stores one order for the whole definition, and a narrowed
   * workbench lists only some kinds: submitting the order it can see would
   * drop every other id from `preferences.order`. So a caller that writes
   * the order reads it from here and moves the two ids it can see inside it,
   * leaving the kinds it does not draw where they were.
   */
  all: ViewInstanceSummary[];
  preferences: ViewPreferences | null;
  permissions: ViewPermissions;
  /** The view to open when the caller names none; null until preferences settle. */
  defaultInstanceId: string | null;
  loading: boolean;
  error: Issue | null;
  /** Kept apart: without preferences the list still works, in server order. */
  preferencesError: Issue | null;
  reload(options?: ViewListReloadOptions): void;
}

/**
 * A completed load, tagged with the request it answered and the definition
 * it is about. The two are asked separately: `key` says whether this is the
 * newest answer, `definitionId` whether it is still about the right thing.
 */
interface Loaded<T> {
  key: string;
  definitionId: string;
  value: T | null;
  error: Issue | null;
}

const NOTHING_LOADED: Loaded<never> = {
  key: '',
  definitionId: '',
  value: null,
  error: null,
};

/**
 * One request to read the list, and what the caller already knew when it
 * asked. `without` holds only until the answer arrives: if the delete did not
 * take after all, the row the store still has comes back rather than staying
 * hidden behind what the caller believed.
 */
interface ReloadRequest {
  token: number;
  without: string | null;
}

const FIRST_LOAD: ReloadRequest = { token: 0, without: null };

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
  options: ViewListOptions = {},
): ViewListState {
  // Read off the object rather than kept: a caller writes the options inline,
  // so the object — and the array in it — is new every render while what
  // they say is not. The kinds are held by what they say.
  const kindsKey = options.kinds?.join(' ');
  const kinds = useMemo(
    () =>
      kindsKey === undefined
        ? null
        : new Set(kindsKey.split(' ') as ViewKind[]),
    [kindsKey],
  );
  const [request, setRequest] = useState<ReloadRequest>(FIRST_LOAD);
  const [list, setList] =
    useState<Loaded<ViewInstanceSummary[]>>(NOTHING_LOADED);
  const [preferences, setPreferences] =
    useState<Loaded<ViewPreferences>>(NOTHING_LOADED);
  const key = `${request.token}:${definitionId}`;

  useEffect(() => {
    let cancelled = false;

    void engine.list(definitionId).then(
      // The store's failure rides beside what could be listed: the declared
      // views are there whatever the store said, so the sidebar keeps its
      // one guaranteed view and says why the rest are missing.
      listing => {
        if (cancelled) return;
        setList(current => ({
          key,
          definitionId,
          // A store that failed on a reload takes nothing off the screen:
          // the answer before it — the saved views included — stays, and
          // the failure is said beside it. Only a first read has nothing to
          // keep, and shows the declared views alone.
          value:
            listing.failed &&
            current.definitionId === definitionId &&
            current.value
              ? current.value
              : listing.items,
          error: listing.failed,
        }));
      },
      // A refusal of the whole list — an unknown definition, an invalid one.
      // What was on hand for this definition stays: a list that blanked here
      // took the default view with it, the workbench closed its runtime, and
      // an unsaved draft went with it without a question (F-05).
      (error: unknown) => {
        if (!cancelled)
          setList(current => ({
            key,
            definitionId,
            value: current.definitionId === definitionId ? current.value : null,
            error: toIssue(error, 'view.list.failed'),
          }));
      },
    );

    void engine.preferences(definitionId).then(
      value => {
        if (!cancelled)
          setPreferences({ key, definitionId, value, error: null });
      },
      (error: unknown) => {
        if (!cancelled)
          setPreferences({
            key,
            definitionId,
            value: null,
            error: toIssue(error, 'view.preferences.load-failed'),
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
  const reload = useCallback(
    (options?: ViewListReloadOptions) =>
      setRequest(current => ({
        token: current.token + 1,
        without: options?.without ?? null,
      })),
    [],
  );

  // Every write the engine confirms is one this list may be a revision
  // behind — whoever sent it, through the manager, through the open view's
  // header, or straight through `engine.delete` from the host's own code.
  // The engine says so; nobody has to remember to (D15). A delete brings the
  // id it removed, so the row goes now rather than when the store answers.
  useEffect(
    () =>
      engine.subscribe(change => {
        if (change.definitionId !== definitionId) return;
        reload(change.kind === 'delete' ? { without: change.id } : undefined);
      }),
    [engine, definitionId, reload],
  );

  // A reload refreshes; it does not blank. What is on hand for *this*
  // definition stays on screen until the new answer lands, because a list
  // that empties mid-reload has no default view for a moment — and a
  // workbench riding on the default would close its runtime and lose the
  // unsaved draft with it. Only a change of definition clears the answer,
  // since then what is on hand is about something else.
  const current = list.definitionId === definitionId ? list : NOTHING_LOADED;
  const preferencesSettled = preferences.definitionId === definitionId;
  const settled = list.key === key;
  // What the caller told us is gone, held only while the retained answer is
  // the one that still has it. Once this request's own answer lands, the
  // store has the last word again.
  const without = settled ? null : request.without;

  const retainedPreferences = preferencesSettled ? preferences : NOTHING_LOADED;
  // The stored default is a revision behind too, and it names the row that
  // has just gone. Answering with it would open a view that no longer exists.
  const currentPreferences = useMemo(() => {
    const value = retainedPreferences.value;
    if (without === null || !value || value.defaultInstanceId !== without)
      return retainedPreferences;
    return {
      ...retainedPreferences,
      value: { ...value, defaultInstanceId: null },
    };
  }, [retainedPreferences, without]);

  const all = useMemo(() => {
    const listed = current.value ?? [];
    const kept =
      without === null ? listed : listed.filter(item => item.id !== without);
    return currentPreferences.value
      ? orderSummaries(kept, currentPreferences.value)
      : kept;
  }, [current.value, currentPreferences.value, without]);

  // The narrowing happens last, over the ordered list, so both the order and
  // the default below are resolved among the views the caller can open while
  // `all` keeps the positions of the kinds this caller does not draw.
  const items = useMemo(
    () => (kinds ? all.filter(summary => kinds.has(summary.kind)) : all),
    [all, kinds],
  );

  return {
    items,
    all,
    preferences: currentPreferences.value,
    permissions,
    // No default until preferences have settled: answering from server order
    // in the meantime opens one view and then swaps it for another.
    defaultInstanceId: !preferencesSettled
      ? null
      : currentPreferences.value
        ? engine.resolveDefault(items, currentPreferences.value)
        : (items[0]?.id ?? null),
    loading: !settled,
    error: current.error,
    preferencesError: currentPreferences.error,
    reload,
  };
}
