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

/**
 * The address as the engine keeps it (host-integration.md 4.2): under a
 * `ViewHost` with a router, what a page opens on is read from the router
 * and written back to it — the open view (`?view=`), the open record
 * (`?id=`), and what a way off handed the page (the history entry's
 * `ViewRouteState`: a view, a board's filters and tab). A surface given
 * the matching props keeps them: the props are the host's route in
 * charge, the address the default.
 */

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  isViewStoreError,
  parseSystemInstanceId,
  type DashboardFilters,
  type RecordKey,
} from '../../model/index.js';
import type { ViewEngine, ViewHandOver } from '../../runtime/index.js';
import type {
  ViewDestination,
  ViewLocation,
  ViewRouteState,
  ViewRouter,
} from '../../runtime/routes.js';
import type { RecordDetailControl } from '../../react/index.js';
import { useViewRouter } from './ViewEngineProvider.js';

/** The search parameter naming the open view (or board). */
export const VIEW_PARAM = 'view';

/** The search parameter naming the record open in the detail. */
export const RECORD_PARAM = 'id';

/** A search parameter of `location`, or `null`. */
export function paramOf(location: ViewLocation, name: string): string | null {
  return new URLSearchParams(location.search).get(name);
}

/** `location`'s path and search with `name` set to `value`, or taken off. */
export function withParam(
  location: ViewLocation,
  name: string,
  value: string | null,
): string {
  const params = new URLSearchParams(location.search);
  if (value === null) params.delete(name);
  else params.set(name, value);
  const search = params.toString();
  return search ? `${location.pathname}?${search}` : location.pathname;
}

/** What the page was handed, read defensively off the history entry. */
export function routeStateOf(location: ViewLocation): ViewRouteState {
  const { state } = location;
  return state && typeof state === 'object' ? state : {};
}

/** Whether `url` is a page of the host's own: a path, not another site. */
function isOwnPath(url: string): boolean {
  return url.startsWith('/') && !url.startsWith('//');
}

/**
 * The router's handling of a way off: a route goes there with what the
 * page opens with, a path of the host's own goes there, another site opens
 * apart, and a target the host bound no route for goes nowhere.
 */
export function routerNavigate(
  router: () => ViewRouter,
): (to: ViewDestination) => void {
  return to => {
    if (to.kind === 'route') router().go(to.path, { state: to.state });
    else if (to.kind === 'url') {
      if (isOwnPath(to.url)) router().go(to.url);
      else window.open(to.url, '_blank', 'noopener,noreferrer');
    }
  };
}

/** The router, read at call time: callbacks stay put as it moves. */
export function useLatestRouter(
  router: ViewRouter | undefined,
): () => ViewRouter | undefined {
  const latest = useRef(router);
  useLayoutEffect(() => {
    latest.current = router;
  });
  return useCallback(() => latest.current, []);
}

/**
 * What a workbench has learned of which views are its definition's: its
 * list as last read (`asked` is the view that sent it to read it), and the
 * views it named in the address itself.
 */
interface Owned {
  listed: ReadonlySet<string>;
  failed: boolean;
  asked: string | null;
  named: ReadonlySet<string>;
}

const NOTHING_OWNED: Owned = {
  listed: new Set(),
  failed: false,
  asked: null,
  named: new Set(),
};

/**
 * Whether the view `id` is one of `definitionId`'s: `true`, `false`, or
 * `undefined` while nobody knows yet. `null` — the default — is anyone's.
 * A declared view says its definition in its id; a saved one is known by
 * the list, or by this workbench having named it. A list that failed to
 * load leaves an id it could not answer for as this workbench's: only a
 * view known to be another's is set aside.
 */
function owns(
  definitionId: string,
  id: string | null,
  owned: Owned,
): boolean | undefined {
  if (id === null) return true;
  const declared = parseSystemInstanceId(id);
  if (declared) return declared.definitionId === definitionId;
  if (owned.named.has(id) || owned.listed.has(id)) return true;
  if (owned.asked === id) return owned.failed;
  return undefined;
}

/**
 * Which view a workbench opens, and where it says it moved: the props, or
 * — with neither given, under a router — the address's `?view=`, a new
 * view a new history entry.
 *
 * Only a view of the workbench's own definition: two workbenches on one
 * page share the one `?view=`, and a view of the other's is no view of
 * this one's. Such a view is set aside — the workbench keeps the view it
 * had (or, opening on one, its default) — and is never answered: the
 * workbench does not write its default over the other's view. A saved
 * view is known by the definition's list, read once and again for an id
 * it did not name; until it answers, a workbench opening on one opens it
 * (`useWorkbench` refuses it before it runs if it is another's, and the
 * list's answer then turns it to the default), and one already open keeps
 * what it has.
 */
export function useAddressedInstance(
  engine: ViewEngine,
  definitionId: string,
  instanceId: string | null | undefined,
  onInstanceChange: ((id: string | null) => void) | undefined,
): {
  instanceId: string | null | undefined;
  onInstanceChange: ((id: string | null) => void) | undefined;
} {
  const router = useViewRouter();
  const addressed =
    router !== undefined &&
    instanceId === undefined &&
    onInstanceChange === undefined;
  const named = addressed ? paramOf(router.location, VIEW_PARAM) : null;
  const [owned, setOwned] = useState(NOTHING_OWNED);
  const verdict = owns(definitionId, named, owned);
  // The view on screen as far as the address goes: the last of its own
  // it named, held while it names another's or one not yet known.
  const [kept, keep] = useState(() => (verdict === false ? null : named));
  if (verdict === true && kept !== named) keep(named);
  // Opened on trust, and it was another's: the default, after all.
  if (verdict === false && kept === named) keep(null);
  const open = verdict === true ? named : kept;

  useEffect(() => {
    if (!addressed || named === null || verdict !== undefined) return;
    let cancelled = false;
    const answer = (listed: ReadonlySet<string> | null) => {
      if (cancelled) return;
      setOwned(current => ({
        ...current,
        listed: listed ?? current.listed,
        failed: listed === null,
        asked: named,
      }));
    };
    void engine.list(definitionId).then(
      async listing => {
        if (listing.failed) return answer(null);
        const listed = new Set(listing.items.map(item => item.id));
        if (listed.has(named)) return answer(listed);
        // A list holds what the store answers of it — a store may cut a
        // long one (WowViewStore lists 1000 of each audience) — so a view not in
        // it is asked for by id before it is judged another's: a link to
        // the 1001st shared view is this workbench's all the same.
        try {
          const found = await engine.store.get(named);
          if (found.definitionId === definitionId) listed.add(named);
          answer(listed);
        } catch (error) {
          answer(
            isViewStoreError(error) && error.code === 'NOT_FOUND'
              ? listed
              : null,
          );
        }
      },
      () => answer(null),
    );
    return () => {
      cancelled = true;
    };
  }, [addressed, named, verdict, engine, definitionId]);

  const latest = useLatestRouter(router);
  const judge = useRef(owned);
  useLayoutEffect(() => {
    judge.current = owned;
  });
  const change = useCallback(
    (id: string | null) => {
      const now = latest();
      if (!now) return;
      const at = paramOf(now.location, VIEW_PARAM);
      if (id === at) return;
      // Its default, while the address names a view that is not its own:
      // said, it would take another workbench's view out of the address.
      if (id === null && owns(definitionId, at, judge.current) !== true) return;
      if (id !== null)
        setOwned(current => ({
          ...current,
          named: new Set(current.named).add(id),
        }));
      const path = withParam(now.location, VIEW_PARAM, id);
      // The view the page was handed, opened: named on the entry it came
      // with, which keeps it for a reload. Any other is a step of its own.
      const handed = routeStateOf(now.location).handOver;
      if (handed?.kind === 'view' && handed.instanceId === id)
        now.go(path, { state: now.location.state, replace: true });
      else now.go(path);
    },
    [latest, definitionId, setOwned],
  );
  return addressed
    ? { instanceId: open, onInstanceChange: change }
    : { instanceId, onInstanceChange };
}

/**
 * The view a workbench is handed: the prop, or — left out, under a router
 * — the one the history entry holds. A browser hands the entry's state
 * back as a copy each time the entry is written (a record opened replaces
 * it), and a workbench opens each new object it is handed; so the same
 * view, as data, is the same object here, and opens once.
 */
export function useAddressedHandOver(
  handOver: ViewHandOver | null | undefined,
): ViewHandOver | null | undefined {
  const router = useViewRouter();
  const held = router ? (routeStateOf(router.location).handOver ?? null) : null;
  const text = held === null ? '' : JSON.stringify(held);
  // Kept by the view's text: a copy of the same view is the object kept.
  const [kept, keep] = useState({ text, held });
  if (kept.text !== text) keep({ text, held });
  if (handOver !== undefined || !router) return handOver;
  return kept.text === text ? kept.held : held;
}

/** What a board opens on and says as it changes; see `useAddressedBoard`. */
export interface BoardAddress {
  initialFilters?: DashboardFilters | null;
  onFiltersChange?(filters: DashboardFilters): void;
  initialTab?: string | null;
  onTabChange?(tab: string | null): void;
}

/**
 * Which board a surface's filters and tab are kept under in the history
 * entry (`ViewRouteState.boards`): the board it names, or — a workbench
 * left on its default — its definition's default.
 */
export function boardKey(
  instanceId: string | null | undefined,
  definitionId?: string,
): string {
  return instanceId ?? `default:${definitionId ?? ''}`;
}

/** What the history entry holds for the board kept under `key`. */
function boardState(
  state: ViewRouteState,
  key: string,
): { filters?: DashboardFilters; tab?: string | null } {
  const own = state.boards?.[key];
  // Each member the board wrote itself, or else what a way off handed the
  // page (`resolveNavigation`) — and what an entry written before boards
  // were kept apart holds.
  return {
    ...(state.filters !== undefined ? { filters: state.filters } : {}),
    ...(state.tab !== undefined ? { tab: state.tab } : {}),
    ...own,
  };
}

/**
 * A board's filters and tab: the props, each pair on its own, or — left
 * out, under a router — the history entry's, written back to it (replacing
 * it) as the reader changes them, so a reload and the way back from a
 * workbench find the board as it was left.
 *
 * Each board keeps its own under `key` (`boardKey`), in the entry's
 * `boards`: several boards on one page — two embeds, an embed beside a
 * workbench — each find their own again, where one shared pair went to
 * whichever wrote last. What the entry holds at its top (`filters`, `tab`:
 * what a way off hands the page it opens) is read by a board that has
 * written nothing of its own yet, and never written over.
 */
export function useAddressedBoard<P extends BoardAddress>(
  props: P,
  key: string,
): P {
  const router = useViewRouter();
  const latest = useLatestRouter(router);
  const latestKey = useRef(key);
  useLayoutEffect(() => {
    latestKey.current = key;
  });
  const keep = useCallback(
    (member: 'filters' | 'tab', value: unknown) => {
      const now = latest();
      if (!now) return;
      const board = latestKey.current;
      const state = routeStateOf(now.location);
      const held = boardState(state, board);
      if (JSON.stringify(held[member]) === JSON.stringify(value)) return;
      const { pathname, search } = now.location;
      now.go(`${pathname}${search}`, {
        state: {
          ...state,
          boards: {
            ...state.boards,
            [board]: { ...state.boards?.[board], [member]: value },
          },
        },
        replace: true,
      });
    },
    [latest],
  );
  const onFiltersChange = useCallback(
    (filters: DashboardFilters) => keep('filters', filters),
    [keep],
  );
  const onTabChange = useCallback(
    (tab: string | null) => keep('tab', tab),
    [keep],
  );
  if (!router) return props;
  const held = boardState(routeStateOf(router.location), key);
  const filtersHeld =
    props.initialFilters === undefined && props.onFiltersChange === undefined;
  const tabHeld =
    props.initialTab === undefined && props.onTabChange === undefined;
  if (!filtersHeld && !tabHeld) return props;
  return {
    ...props,
    ...(filtersHeld
      ? { initialFilters: held.filters ?? null, onFiltersChange }
      : {}),
    ...(tabHeld ? { initialTab: held.tab, onTabChange } : {}),
  };
}

/**
 * The record open in the address (`?id=`), for `ViewHost` to hand every
 * bound resource's detail: opening one record after another is reading,
 * not moving, so it replaces the entry. `undefined` without a router.
 */
export function useAddressedDetail(
  router: ViewRouter | undefined,
): RecordDetailControl | undefined {
  const open = router ? paramOf(router.location, RECORD_PARAM) : null;
  const latest = useLatestRouter(router);
  const onOpenChange = useCallback(
    (key: RecordKey | null) => {
      const now = latest();
      const next = key === null ? null : String(key);
      if (!now || next === paramOf(now.location, RECORD_PARAM)) return;
      now.go(withParam(now.location, RECORD_PARAM, next), {
        state: now.location.state,
        replace: true,
      });
    },
    [latest],
  );
  const routed = router !== undefined;
  return useMemo(
    () => (routed ? { open, onOpenChange } : undefined),
    [routed, open, onOpenChange],
  );
}
