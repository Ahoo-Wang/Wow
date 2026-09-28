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
 * What a host binds to a resource (host-integration.md 4, D67): the
 * behaviour the headless core cannot hold — where a view of it lives in the
 * host's address, how one of its records is read, and, until H3's
 * declarative actions, the host's commands on its records. Registered once
 * on `ViewEngineProvider`, by the definition's id, and found by every
 * surface that draws that definition: its workbench, a board's record
 * panel, an embed. The host writes no `definition.id ===` branch.
 */

import type { DashboardFilters } from '../model/index.js';
import type {
  DashboardTarget,
  SavedViewTarget,
  UnsavedViewTarget,
  ViewHandOver,
  ViewNavigation,
} from '../runtime/index.js';
import type { BulkCommand, RecordActionSlots } from '../react/index.js';
import type { RecordDetailOptions } from './workbench/RecordParts.js';

/** A way off a board or a view that leads to one of the host's resources. */
export type RoutedTarget =
  SavedViewTarget | UnsavedViewTarget | DashboardTarget;

export interface ViewBindingOptions {
  /**
   * Where a view or a board of this definition lives in the host's
   * address: the path of the page that opens `instanceId` — `null` for a
   * view nobody saved (a follow-up on a group, a board's own analysis),
   * which opens on the page's default and is handed over whole. The engine
   * hands the host's `navigate` the path with what the page opens with
   * (`ViewRoute.state`); a definition with no route is handed over raw.
   */
  route?(instanceId: string | null, target: RoutedTarget): string;
  /**
   * How one of its records is read (D60): the detail's own reading of the
   * record, its title, its sections, who holds which record is open — what
   * `DataWorkbench` takes as `record.detail`, and what an `EmbeddedView`'s
   * `detail` opens with. A surface's own prop wins.
   */
  reading?: RecordDetailOptions;
  /**
   * The host's commands on its records — a row's, a selection's, the
   * view's — wherever its records are drawn: the workbench, and every
   * record panel of a board over it (D39). Today's slots; H3 declares them
   * instead (host-integration.md 5), and a surface's own prop wins.
   */
  actions?: RecordActionSlots;
  /** The bulk command whose line the record views over it say (`useBulkCommand`). */
  bulk?: BulkCommand;
}

/** One definition's binding, as `ViewEngineProvider` takes it. */
export interface ViewBinding extends ViewBindingOptions {
  readonly definitionId: string;
}

/** Binds a definition's behaviour in the host (host-integration.md 4). */
export function bind(
  definitionId: string,
  options: ViewBindingOptions = {},
): ViewBinding {
  return { ...options, definitionId };
}

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

/** `to` through the route of the definition it leads to, where it has one. */
export function resolveNavigation(
  to: ViewNavigation,
  bindingOf: (definitionId: string) => ViewBinding | undefined,
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
