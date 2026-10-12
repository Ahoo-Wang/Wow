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

import { useEffect, useState, type ReactNode } from 'react';
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { useReactRouter } from '@ahoo-wang/wow-view-engine/react-router';
import { ViewHost, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import { analyticsEngine, datasetResources } from './engine.ts';
import { analyticsBindings } from './routes.ts';

/** Wow's blue over the engine's porcelain preset. */
const BRAND = 'oklch(0.546 0.245 262.881)';

/**
 * The console as the view engine's host: its engine, the datasets the
 * server serves (read once when the console opens), the boards, the router
 * and the theme.
 */
export function AnalyticsHost({
  engine = analyticsEngine(),
  children,
}: {
  engine?: ViewEngine;
  children: ReactNode;
}) {
  const [datasets, setDatasets] = useState<readonly string[]>([]);
  useEffect(() => {
    let live = true;
    datasetResources(engine)
      .refresh()
      .then(served => {
        if (live) setDatasets(served.map(({ id }) => id));
      })
      .catch(error => console.error('[analytics] directory', error));
    return () => {
      live = false;
    };
  }, [engine]);
  return (
    <ViewHost
      engine={engine}
      router={useReactRouter()}
      locale="zh-CN"
      messages={zhCN}
      bindings={analyticsBindings(datasets)}
      preset="porcelain"
      brand={BRAND}
      rememberColorMode="analytics-console.color-mode"
    >
      {children}
    </ViewHost>
  );
}
