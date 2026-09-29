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
 * A way off a board or a view resolved to the host's address
 * (host-integration.md 4): the route a host bound to the definition it
 * leads to, the path and what the page there is handed. Headless — the
 * bindings that hold the routes are React's (`/ui`'s `bind`).
 */

import type { DashboardFilters } from '../model/index.js';
import type {
  DashboardTarget,
  SavedViewTarget,
  UnsavedViewTarget,
  ViewHandOver,
  ViewNavigation,
} from './navigation.js';

/** A way off a board or a view that leads to one of the host's resources. */
export type RoutedTarget =
  SavedViewTarget | UnsavedViewTarget | DashboardTarget;

/** What the page a route opens is handed with it, as history state. */
export interface ViewRouteState {
  /** A view to open as it came (`DataWorkbench`'s `handOver`). */
  handOver?: ViewHandOver;
  /** A board's filters (`DashboardWorkbench`'s `initialFilters`). */
  filters?: DashboardFilters;
  /** The tab a board opens on (`initialTab`); absent, where it was last read. */
  tab?: string | null;
}

/**
 * A way off a board or a view, resolved through its definition's `route`:
 * the host's path, and what the page there is handed.
 */
export interface ViewRoute {
  kind: 'route';
  path: string;
  state: ViewRouteState;
  /** What was asked for, for a host that wants more of it. */
  target: RoutedTarget;
}

/**
 * What the host's `navigate` is handed: a route, where the target's
 * definition has one; otherwise the target as it came — a URL, or a
 * definition the host bound no route for.
 */
export type ViewDestination = ViewRoute | ViewNavigation;

/** Where a view or a board of a definition lives in the host's address. */
export type ViewRouteOf = (
  instanceId: string | null,
  target: RoutedTarget,
) => string;

/**
 * `to` through the route of the definition it leads to, where it has one
 * (`bind`'s `route`, looked up by `bindingOf`): what the host's `navigate`
 * is handed. Pure, and in `/testing` too, so a host's tests of its
 * bindings run the engine's own resolution.
 */
export function resolveNavigation(
  to: ViewNavigation,
  bindingOf: (definitionId: string) => { route?: ViewRouteOf } | undefined,
): ViewDestination {
  if (to.kind === 'url') return to;
  const route = bindingOf(to.definitionId)?.route;
  if (!route) return to;
  if (to.kind === 'dashboard')
    return {
      kind: 'route',
      path: route(to.instanceId, to),
      state: {
        filters: to.filters,
        ...(to.tab !== undefined ? { tab: to.tab } : {}),
      },
      target: to,
    };
  return {
    kind: 'route',
    path: route(to.kind === 'view' ? to.instanceId : null, to),
    state: { handOver: to },
    target: to,
  };
}
