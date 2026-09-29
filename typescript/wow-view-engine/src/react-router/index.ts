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
 * `@ahoo-wang/wow-view-engine/react-router`: the router port's adapter for
 * React Router (host-integration.md 4.2), the one the package ships —
 * React Router is an optional peer only this entry loads. A host on
 * another router writes the port's two members itself (`ViewRouter`).
 */

import { useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router';
import type { ViewRouter } from '../runtime/routes.js';

/**
 * React Router as `ViewHost`'s `router`, inside its `RouterProvider` (or
 * any router component): where it is, and a way to go — paths relative to
 * its `basename`, as its own links are.
 *
 * ```tsx
 * <ViewHost engine={engine} router={useReactRouter()} bindings={bindings}>
 * ```
 */
export function useReactRouter(): ViewRouter {
  const { pathname, search, state } = useLocation();
  const navigate = useNavigate();
  return useMemo(
    () => ({
      location: { pathname, search, state },
      go: (path, options) => {
        void navigate(path, {
          state: options?.state,
          replace: options?.replace,
        });
      },
    }),
    [pathname, search, state, navigate],
  );
}
