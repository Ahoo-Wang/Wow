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
 * The host's navigation as data (host-integration.md 4.3): the engine
 * draws views and the host draws its pages, so there is no page shell —
 * what the engine gives is what a shell is drawn from, each resource with
 * a route and its system views (a dashboard's: its system boards), where
 * they live in the host's address and which one the reader is on. The
 * host draws it with its own components: a shadcn `Sidebar`, a top bar.
 */

import { useMemo } from 'react';
import { systemInstanceId } from '../model/index.js';
import type { ViewLocation } from '../runtime/routes.js';
import { useSay } from './kit/MessagesProvider.js';
import {
  useBindings,
  useEngine,
  useViewRouter,
} from './workbench/ViewEngineProvider.js';

/** One system view (or system board) of a resource, as a link. */
export interface ViewNavigationView {
  /** Its instance id (`system:<definition>:<view>`). */
  id: string;
  /** Its title, said in the words in force here. */
  title: string;
  /** Where it lives in the host's address: its resource's `route`. */
  path: string;
  /** Whether the address is on it: its path and every parameter it names. */
  current: boolean;
}

/** One resource the host routes to, as a place of its navigation. */
export interface ViewNavigationItem {
  /** The definition's id. */
  id: string;
  /** A data resource's workbench, or a dashboard's. */
  kind: 'data' | 'dashboard';
  /** The definition's title, said in the words in force here. */
  title: string;
  /** Its page in the host's address: its `route` with no view named. */
  path: string;
  /**
   * Whether the address is on its page: its path and every parameter that
   * path names, whatever view the page has open.
   */
  current: boolean;
  /** Its system views, in the order the definition declares them. */
  views: readonly ViewNavigationView[];
}

/** `path` taken apart into its pathname and its parameters. */
function split(path: string): { pathname: string; params: URLSearchParams } {
  const at = path.indexOf('?');
  return at === -1
    ? { pathname: path, params: new URLSearchParams() }
    : {
        pathname: path.slice(0, at),
        params: new URLSearchParams(path.slice(at + 1)),
      };
}

/** Whether `location` is on `path`'s page. */
function onPage(location: ViewLocation | undefined, path: string): boolean {
  return location !== undefined && split(path).pathname === location.pathname;
}

/** Whether `location` is on `path`: its page, and every parameter it names. */
function onPath(location: ViewLocation | undefined, path: string): boolean {
  if (!location || !onPage(location, path)) return false;
  const here = new URLSearchParams(location.search);
  return [...split(path).params].every(
    ([name, value]) => here.get(name) === value,
  );
}

/**
 * The places of the host's navigation (host-integration.md 4.3), from the
 * `ViewHost` above: every registered resource the host bound a `route` to,
 * in the order they were registered, with its system views; titles said
 * in the words in force, so a change of language redraws it, and a
 * resource registered or bound differently changes it. `current` reads
 * the router's address; with no router, nothing is current.
 */
export function useViewNavigation(): readonly ViewNavigationItem[] {
  const engine = useEngine();
  const bindingOf = useBindings();
  const location = useViewRouter()?.location;
  const say = useSay();
  return useMemo(() => {
    const items: ViewNavigationItem[] = [];
    for (const definition of engine.definitions.values()) {
      const route = bindingOf(definition.id)?.route;
      if (!route) continue;
      const path = route(null);
      items.push({
        id: definition.id,
        kind: definition.kind,
        title: say(definition.title),
        path,
        current: onPath(location, path),
        views: (definition.views ?? []).map(view => {
          const id = systemInstanceId(definition.id, view.id);
          const at = route(id);
          return {
            id,
            title: say(view.title),
            path: at,
            current: onPath(location, at),
          };
        }),
      });
    }
    return items;
  }, [engine, bindingOf, location, say]);
}
