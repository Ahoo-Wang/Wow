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

import { useMemo, type ReactNode } from "react";
import { useReactRouter } from "@ahoo-wang/wow-view-engine/react-router";
import { ViewHost } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n } from "@/i18n.tsx";
import { consoleBindings } from "@/views/bindings.ts";
import { engineMessages } from "@/views/messages.ts";
import { useServices } from "./services.tsx";

/** Wow's blue, the console's brand over the engine's porcelain preset. */
const BRAND = "oklch(0.546 0.245 262.881)";

/**
 * The console as the view engine's host (host-integration.md 4.2): its one
 * engine, its route table and the operator's actions, the router, the
 * language in force, and the engine's theme.
 */
export function OpsHost({ children }: { children: ReactNode }) {
  const { engine, commands } = useServices();
  const { locale } = useI18n();
  const bindings = useMemo(() => consoleBindings(commands), [commands]);
  return (
    <ViewHost
      engine={engine}
      router={useReactRouter()}
      locale={locale}
      messages={engineMessages(locale)}
      bindings={bindings}
      preset="porcelain"
      brand={BRAND}
      rememberColorMode="wow-ops-console.color-mode"
    >
      {children}
    </ViewHost>
  );
}
