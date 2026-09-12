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

import { useEffect, useLayoutEffect, useState } from 'react';
import { ViewEngine } from '../engine/ViewEngine.js';
import type { ViewEngineOptions } from '../contracts/viewModel.js';
import type { ViewExtensions } from '../view/viewReactTypes.js';
export interface UseViewEngineOptions extends Omit<
  ViewEngineOptions,
  'filterCompilers' | 'analysisCompilers'
> {
  scopeKey: string;
  extensions?: ViewExtensions;
}
export interface ViewEngineBinding {
  engine: ViewEngine | null;
  extensions?: ViewExtensions;
  error?: string;
}
function capture(options: UseViewEngineOptions, identity: string) {
  const filters = options.extensions?.filters
    ? Object.freeze(
        Object.fromEntries(
          Object.entries(options.extensions.filters).map(
            ([name, registration]) => [
              name,
              Object.freeze({
                ...registration,
                modes: Object.freeze([...registration.modes]),
              }),
            ],
          ),
        ),
      )
    : undefined;
  const analysis = options.extensions?.analysis
    ? Object.freeze(
        Object.fromEntries(
          Object.entries(options.extensions.analysis).map(
            ([name, registration]) => [
              name,
              Object.freeze({
                ...registration,
                roles: Array.isArray(registration?.roles)
                  ? Object.freeze([...registration.roles])
                  : registration?.roles,
              }),
            ],
          ),
        ),
      )
    : undefined;
  return {
    identity,
    filters,
    analysis,
    options: {
      definitionId: options.definitionId,
      definition: options.definition,
      instances: options.instances,
      host: options.host,
      filterCompilers: filters,
      analysisCompilers: analysis
        ? Object.fromEntries(
            Object.entries(analysis).map(([name, registration]) => [
              name,
              { roles: registration?.roles, compile: registration.compile },
            ]),
          )
        : undefined,
      limits: options.limits ? { ...options.limits } : undefined,
      onDiagnostic: options.onDiagnostic,
    } satisfies ViewEngineOptions,
  };
}
/** Own one engine and its paired compiler/editor definitions per explicit access scope. */
export function useViewEngine(
  options: UseViewEngineOptions,
): ViewEngineBinding {
  const identity = JSON.stringify([options.scopeKey, options.definitionId]);
  const [initial, setInitial] = useState(() => capture(options, identity));
  if (initial.identity !== identity) setInitial(capture(options, identity));
  const validScope =
    typeof options.scopeKey === 'string' && !!options.scopeKey.trim();
  const [owned, setOwned] = useState<{
    initial: typeof initial;
    engine: ViewEngine | null;
    error?: string;
  } | null>(null);
  useEffect(() => {
    if (!validScope) return;
    let engine: ViewEngine;
    try {
      engine = new ViewEngine(initial.options);
    } catch (error) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- external engine construction failures are observable binding state.
      setOwned({
        initial,
        engine: null,
        error: error instanceof Error ? error.message : '视图数据无效',
      });
      return;
    }
    setOwned({ initial, engine });
    void engine.load().catch(() => {});
    return () => engine.dispose();
  }, [initial, validScope]);
  const engine =
    owned?.initial === initial && initial.identity === identity
      ? owned.engine
      : null;
  useLayoutEffect(() => {
    engine?.updateHost(options.host);
  }, [engine, options.host]);
  return {
    engine: validScope ? engine : null,
    extensions: {
      ...options.extensions,
      filters: initial.filters,
      analysis: initial.analysis,
    },
    ...(!validScope
      ? { error: 'scopeKey 必须标识当前用户与访问范围' }
      : owned?.initial === initial && owned.error
        ? { error: owned.error }
        : {}),
  };
}
