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

import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { DashboardFilters, RecordKey } from '../model/index.js';
import type { ViewHandOver } from '../runtime/index.js';
import type {
  ViewDestination,
  ViewLocation,
  ViewRouteState,
  ViewRouter,
} from '../runtime/routes.js';
import type { RecordDetailControl } from '../react/index.js';
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
 * Which view a workbench opens, and where it says it moved: the props, or
 * — with neither given, under a router — the address's `?view=`, a new
 * view a new history entry.
 */
export function useAddressedInstance(
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
  const named = addressed ? paramOf(router.location, VIEW_PARAM) : undefined;
  const latest = useLatestRouter(router);
  const change = useCallback(
    (id: string | null) => {
      const now = latest();
      if (!now || id === paramOf(now.location, VIEW_PARAM)) return;
      const path = withParam(now.location, VIEW_PARAM, id);
      // The view the page was handed, opened: named on the entry it came
      // with, which keeps it for a reload. Any other is a step of its own.
      const handed = routeStateOf(now.location).handOver;
      if (handed?.kind === 'view' && handed.instanceId === id)
        now.go(path, { state: now.location.state, replace: true });
      else now.go(path);
    },
    [latest],
  );
  return addressed
    ? { instanceId: named, onInstanceChange: change }
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
 * A board's filters and tab: the props, each pair on its own, or — left
 * out, under a router — the history entry's, written back to it (replacing
 * it) as the reader changes them, so a reload and the way back from a
 * workbench find the board as it was left.
 */
export function useAddressedBoard<P extends BoardAddress>(props: P): P {
  const router = useViewRouter();
  const latest = useLatestRouter(router);
  const keep = useCallback(
    (member: 'filters' | 'tab', value: unknown) => {
      const now = latest();
      if (!now) return;
      const state = routeStateOf(now.location);
      if (JSON.stringify(state[member]) === JSON.stringify(value)) return;
      const { pathname, search } = now.location;
      now.go(`${pathname}${search}`, {
        state: { ...state, [member]: value },
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
  const state = routeStateOf(router.location);
  const filtersHeld =
    props.initialFilters === undefined && props.onFiltersChange === undefined;
  const tabHeld =
    props.initialTab === undefined && props.onTabChange === undefined;
  if (!filtersHeld && !tabHeld) return props;
  return {
    ...props,
    ...(filtersHeld
      ? { initialFilters: state.filters ?? null, onFiltersChange }
      : {}),
    ...(tabHeld ? { initialTab: state.tab, onTabChange } : {}),
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
