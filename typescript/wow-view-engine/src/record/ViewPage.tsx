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
import type { RecordViewProps } from './RecordView.js';
import { ViewEngine } from './ViewEngine.js';
import { ViewPageContent } from './page/ViewPageContent.js';
import type { ViewEngineOptions } from './recordModel.js';

export { ViewPageContent } from './page/ViewPageContent.js';
export type { ViewPageContentProps } from './page/ViewPageContent.js';

export interface ViewPageProps
  extends
    Omit<ViewEngineOptions, 'filterCompilers'>,
    Omit<RecordViewProps, 'engine' | 'toolbarStart'> {
  /** Stable user/tenant/access identity. Changing it creates an isolated session. */
  scopeKey: string;
  initialSidebarCollapsed?: boolean;
}
/** Owns one engine per scope/definition; extensions.filters supplies complete filter definitions. */
export function ViewPage(props: ViewPageProps) {
  if (typeof props.scopeKey !== 'string' || !props.scopeKey.trim())
    return (
      <div className="fve-root fve:p-4" role="alert">
        scopeKey 必须标识当前用户与访问范围
      </div>
    );
  return (
    <OwnedViewPage
      key={JSON.stringify([props.scopeKey, props.definitionId])}
      {...props}
    />
  );
}
function OwnedViewPage(props: ViewPageProps) {
  const [initial] = useState(() => {
    // Compiler and renderer definitions share the engine scope's lifetime.
    const filters = props.extensions?.filters
      ? Object.fromEntries(
          Object.entries(props.extensions.filters).map(
            ([name, registration]) => [
              name,
              { ...registration, modes: [...registration.modes] },
            ],
          ),
        )
      : undefined;
    return {
      host: props.host,
      options: {
        definitionId: props.definitionId,
        definition: props.definition,
        instances: props.instances,
        filterCompilers: filters,
      },
      filters,
    };
  });
  const extensions = { ...props.extensions, filters: initial.filters };
  const [owned, setOwned] = useState<{
    engine: ViewEngine | null;
    error?: string;
  } | null>(null);
  useEffect(() => {
    const options: ViewEngineOptions = {
      ...initial.options,
      host: initial.host,
    };
    let engine: ViewEngine;
    try {
      engine = new ViewEngine(options);
    } catch (error) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- report invalid local input from external resource creation.
      setOwned({
        engine: null,
        error: error instanceof Error ? error.message : '视图数据无效',
      });
      return;
    }
    // A fresh subscription owner on every effect setup also supports React StrictMode cleanup/replay.
    setOwned({ engine });
    void engine.load().catch(() => {});
    return () => engine.dispose();
  }, [initial]);
  useLayoutEffect(() => {
    owned?.engine?.updateHost(props.host);
  }, [owned, props.host]);
  if (!owned)
    return (
      <div className="fve-root fve:p-4" role="status">
        正在加载视图…
      </div>
    );
  if (!owned.engine)
    return (
      <div className="fve-root fve:p-4" role="alert">
        {owned.error}
      </div>
    );
  return (
    <ViewPageContent
      key={props.definitionId}
      engine={owned.engine}
      extensions={extensions}
      filterContext={props.filterContext}
      selectable={props.selectable}
      autoRefreshPaused={props.autoRefreshPaused}
      className={props.className}
      initialSidebarCollapsed={props.initialSidebarCollapsed}
    />
  );
}
