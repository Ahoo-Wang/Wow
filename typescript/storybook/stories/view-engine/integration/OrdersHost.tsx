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

import { useMemo, type ReactNode } from 'react';
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import {
  bind,
  ViewHost,
  zhCN,
  type HostColorMode,
  type ViewRouter,
} from '@ahoo-wang/wow-view-engine/ui';
// The engine's stylesheet, and the presets; a host that names one may
// import it alone (`themes/porcelain.css`).
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes.css';
import { orderActions, type OrderCommands } from './orderActions.js';
import { ORDERS, ORDERS_WORDS } from './ordersDefinition.js';

/** The engine's own words in Chinese, and the definition's keys. */
const MESSAGES = { ...zhCN, ...ORDERS_WORDS };

/** Where the orders' views live: one page, the open view in `?view=`. */
export function ordersPath(view: string | null): string {
  return view === null ? '/orders' : `/orders?${new URLSearchParams({ view })}`;
}

// Step 3: `ViewHost`, the one thing the host writes around its pages — the
// engine, the router, the language, the theme, and what each resource does
// in this host (`bind`).
export function OrdersHost({
  engine,
  router,
  commands,
  colorMode = 'system',
  children,
}: {
  engine: ViewEngine;
  /** `useReactRouter()` from `/react-router`, or a port of your own. */
  router: ViewRouter;
  commands: OrderCommands;
  /** `host` where the host paints light and dark itself. */
  colorMode?: HostColorMode;
  children: ReactNode;
}) {
  const bindings = useMemo(
    () => [
      bind(ORDERS, {
        // Every way to the orders — a link, a board's follow-up, the
        // navigation — goes through this route.
        route: ordersPath,
        // The commands on an order (step 4), wherever its records are
        // shown: the workbench, its detail, a board's record panel.
        actions: orderActions(commands),
      }),
    ],
    [commands],
  );
  return (
    <ViewHost
      engine={engine}
      router={router}
      locale="zh-CN"
      messages={MESSAGES}
      bindings={bindings}
      // The host follows the engine's preset; a host with a shadcn theme
      // writes `theme="host"` and imports `shadcn-bridge.css` instead.
      preset="porcelain"
      // Light or dark on `<html>`: the system's until the reader picks.
      colorMode={colorMode}
      rememberColorMode="orders-app.color-mode"
    >
      {children}
    </ViewHost>
  );
}
