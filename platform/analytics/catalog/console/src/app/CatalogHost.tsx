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
import type { CatalogServices } from "@/services/catalog.ts";
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

/** The datasets served, each with the actions its rows offer; one whose cannot be read offers none. */
async function readServed({
  datasets,
  references,
}: CatalogServices): Promise<Pick<Served, "datasets" | "references">> {
  const resources: readonly DatasetResource[] = await datasets.refresh();
  const read = await Promise.all(
    resources.map(({ id }) =>
      references.actions(id).then(
        (actions): [string, ReferenceActions] => [id, actions],
        (error: unknown) => {
          console.warn(`[datasets] ${id} offers no row actions`, error);
          return null;
        },
      ),
    ),
  );
  return {
    datasets: resources,
    references: Object.fromEntries(read.filter((entry) => entry !== null)),
  };
}

/**
 * The datasets the node serves, read again for each caller (another one sees
 * other datasets and may act on other rows) and on `refresh`.
 */
function useServed(): Served {
  const services = useServices();
  const caller = useCaller();
  const [round, setRound] = useState(0);
  // What is asked for now: the caller's datasets, read again on each refresh.
  const asked = caller ? `${callerKey(caller)}#${round}` : null;
  const [served, setServed] = useState<
    Omit<Served, "refresh" | "loading"> & { for: string | null }
  >({ for: null, datasets: [], references: {}, error: null });
  useEffect(() => {
    if (asked === null) return;
    let live = true;
    readServed(services).then(
      (read) => {
        if (live) setServed({ ...read, for: asked, error: null });
      },
      (error: unknown) => {
        if (live) setServed((current) => ({ ...current, for: asked, error }));
      },
    );
    return () => {
      live = false;
    };
  }, [services, asked]);
  return useMemo(
    () => ({
      datasets: served.datasets,
      references: served.references,
      error: served.error,
      loading: asked !== null && served.for !== asked,
      refresh: () => setRound((value) => value + 1),
    }),
    [served, asked],
  );
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
