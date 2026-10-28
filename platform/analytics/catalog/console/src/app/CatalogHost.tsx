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
import { useDatasets } from "@ahoo-wang/wow-view-engine/react";
import { useReactRouter } from "@ahoo-wang/wow-view-engine/react-router";
import { ViewHost } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n } from "@/i18n.tsx";
import { callerKey } from "@/services/caller.ts";
import { consoleBindings } from "@/views/bindings.ts";
import { engineMessages } from "@/views/messages.ts";
import {
  REFERENCE_WORDS,
  referenceActions,
  type ReferenceActions,
} from "@/views/referenceActions.ts";
import { ServedProvider, type Served } from "./served.tsx";
import { useCaller, useServices } from "./services.tsx";

/** Wow's blue, the console's brand over the engine's porcelain preset. */
const BRAND = "oklch(0.546 0.245 262.881)";

/**
 * The actions each served dataset's rows offer, read again whenever the
 * datasets are; one whose cannot be read offers none.
 */
function useReferences(
  datasets: readonly DatasetResource[],
): Readonly<Record<string, ReferenceActions>> {
  const { references } = useServices();
  const [read, setRead] = useState<{
    for: readonly DatasetResource[];
    actions: Readonly<Record<string, ReferenceActions>>;
  }>({ for: [], actions: {} });
  useEffect(() => {
    let live = true;
    Promise.all(
      datasets.map(({ id }) =>
        references.actions(id).then(
          (actions): [string, ReferenceActions] => [id, actions],
          (error: unknown) => {
            console.warn(`[datasets] ${id} offers no row actions`, error);
            return null;
          },
        ),
      ),
    ).then((entries) => {
      if (live)
        setRead({
          for: datasets,
          actions: Object.fromEntries(
            entries.filter((entry) => entry !== null),
          ),
        });
    });
    return () => {
      live = false;
    };
  }, [datasets, references]);
  return read.for === datasets ? read.actions : {};
}

/**
 * The datasets the node serves (the view engine's `useDatasets`), read again
 * for each caller — another one sees other datasets and may act on other
 * rows — and on `refresh`, with the actions their rows offer.
 */
function useServed(): Served {
  const { datasets } = useServices();
  const caller = useCaller();
  const served = useDatasets(
    datasets,
    caller ? { key: callerKey(caller) } : { paused: true },
  );
  const references = useReferences(served.datasets);
  return useMemo(() => ({ ...served, references }), [served, references]);
}

/**
 * The console as the view engine's host (host-integration.md 4.2): its one
 * engine, its route table and the data owner's actions, the datasets served
 * with their rows' actions, the router, the language in force, and the
 * engine's theme.
 */
export function CatalogHost({ children }: { children: ReactNode }) {
  const { engine, commands, references } = useServices();
  const { locale } = useI18n();
  const served = useServed();
  const bindings = useMemo(
    () =>
      consoleBindings(commands, served.datasets, (id) => {
        const actions = served.references[id];
        return actions
          ? referenceActions(actions, references.send, REFERENCE_WORDS[locale])
          : undefined;
      }),
    [commands, served.datasets, served.references, references.send, locale],
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
