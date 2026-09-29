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

import type { ReactNode } from "react";
import type { ViewEngine } from "@ahoo-wang/wow-view-engine";
import { useReactRouter } from "@ahoo-wang/wow-view-engine/react-router";
import { ViewHost } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n } from "@/i18n.tsx";
import { consoleEngine } from "@/views/engine.ts";
import { engineMessages } from "@/views/messages.ts";
import { ROUTES } from "@/views/routes.ts";

/** Wow's blue, the console's brand over the engine's porcelain preset. */
const BRAND = "oklch(0.546 0.245 262.881)";

/**
 * The console as the view engine's host (host-integration.md 4.2): its one
 * engine and route table, the router, the language in force, and the
 * engine's theme — the porcelain preset in Wow's blue, light or dark as
 * the system is until the reader picks one, kept on this machine. The
 * shell's own chrome wears the same theme through `fve-tokens` (`App`).
 */
export function ConsoleHost({
  engine = consoleEngine(),
  children,
}: {
  /** The console's one engine by default, a test's own otherwise. */
  engine?: ViewEngine;
  children: ReactNode;
}) {
  const { locale } = useI18n();
  return (
    <ViewHost
      engine={engine}
      router={useReactRouter()}
      locale={locale}
      messages={engineMessages(locale)}
      bindings={ROUTES}
      preset="porcelain"
      brand={BRAND}
      rememberColorMode="compensation-console.color-mode"
    >
      {children}
    </ViewHost>
  );
}
