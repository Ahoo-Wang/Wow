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

import { useEffect, useMemo, useState, type ReactNode } from "react";
import type { DatasetResource } from "@ahoo-wang/wow-view-engine";
import { useReactRouter } from "@ahoo-wang/wow-view-engine/react-router";
import { ViewHost } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n } from "@/i18n.tsx";
import { callerKey } from "@/services/caller.ts";
import { consoleBindings } from "@/views/bindings.ts";
import { engineMessages } from "@/views/messages.ts";
import { ServedProvider, type Served } from "./served.tsx";
import { useCaller, useServices } from "./services.tsx";

/** Wow's blue, the console's brand over the engine's porcelain preset. */
const BRAND = "oklch(0.546 0.245 262.881)";

/**
 * The datasets the node serves, read again for each caller (another one sees
 * other datasets) and on `refresh`.
 */
function useServed(): Served {
  const { datasets } = useServices();
  const caller = useCaller();
  const key = caller ? callerKey(caller) : null;
  const [round, setRound] = useState(0);
  const [served, setServed] = useState<Omit<Served, "refresh">>({
    datasets: [],
    error: null,
    loading: false,
  });
  useEffect(() => {
    if (key === null) return;
    let live = true;
    setServed((current) => ({ ...current, loading: true }));
    datasets.refresh().then(
      (resources: readonly DatasetResource[]) => {
        if (live)
          setServed({ datasets: resources, error: null, loading: false });
      },
      (error: unknown) => {
        if (live)
          setServed((current) => ({ ...current, error, loading: false }));
      },
    );
    return () => {
      live = false;
    };
  }, [datasets, key, round]);
  return useMemo(
    () => ({ ...served, refresh: () => setRound((value) => value + 1) }),
    [served],
  );
}

/**
 * The console as the view engine's host (host-integration.md 4.2): its one
 * engine, its route table and the data owner's actions, the datasets served,
 * the router, the language in force, and the engine's theme.
 */
export function CatalogHost({ children }: { children: ReactNode }) {
  const { engine, commands } = useServices();
  const { locale } = useI18n();
  const served = useServed();
  const bindings = useMemo(
    () => consoleBindings(commands, served.datasets),
    [commands, served.datasets],
  );
  return (
    <ServedProvider served={served}>
      <ViewHost
        engine={engine}
        router={useReactRouter()}
        locale={locale}
        messages={engineMessages(locale)}
        bindings={bindings}
        preset="porcelain"
        brand={BRAND}
        rememberColorMode="wow-analytics-catalog-console.color-mode"
      >
        {children}
      </ViewHost>
    </ServedProvider>
  );
}
