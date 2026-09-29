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

import { useLayoutEffect, useMemo, type ReactNode } from 'react';
import type { ViewEngine } from '../runtime/index.js';
import type { ViewDestination, ViewRouter } from '../runtime/routes.js';
import {
  routerNavigate,
  useAddressedDetail,
  useLatestRouter,
} from './address.js';
import type { ViewBinding } from './bindings.js';
import { ColorModeHost, type HostColorMode } from './colorMode.js';
import type { ViewMessages } from './messages.js';
import type { ViewPreset } from './presets.js';
import { useHosted, ViewEngineProvider } from './ViewEngineProvider.js';

export interface ViewHostProps {
  /**
   * The application's engine, built once (host-integration.md 4) — the
   * data port: its `resources` and its `store`. An inner host without one
   * uses the outer one's. The definitions are said where they are shown, in
   * the words in force there; the words of the outermost host naming the
   * engine are the ones checked for keys they lack (`ViewEngine.setText`).
   */
  engine?: ViewEngine;
  /**
   * The language port: the language values show in, the outer host's when
   * left out. A change of it redraws what is open and rebuilds nothing.
   */
  locale?: string;
  /**
   * The language port's words, merged over those in force — the engine's
   * own and the definitions' keys (`text(key)`) alike — so switching them
   * redraws every open view in them without reopening, re-querying or
   * dirtying anything (3.1).
   */
  messages?: ViewMessages;
  /**
   * The router port (4.2): the host's router — `useReactRouter()` from
   * `/react-router`, or two members over another. With it the engine keeps
   * the address: every way off a board or a view goes through the route of
   * the resource it leads to; a workbench opens the address's `?view=` and
   * writes it back; a bound resource's record detail follows `?id=`; a view
   * handed over, and a board's filters and tab, travel as the history
   * entry's state; another site opens apart. A surface's own props still
   * win. The outer host's when left out.
   */
  router?: ViewRouter;
  /**
   * Every way off a board or a view, in the host's own hands instead of the
   * router's: handed resolved through the route of the resource it leads
   * to (`ViewRoute`); a URL, and a resource with no route, as it came.
   */
  navigate?(to: ViewDestination): void;
  /**
   * Each resource's behaviour in this host (`bind`) — its route, its
   * reading and the commands on its records; an inner host's wins by id.
   */
  bindings?: readonly ViewBinding[];
  /**
   * The theme port, the first of two paths (4.1): `host` — the engine
   * follows the host's shadcn theme (Tailwind v4), through
   * `shadcn-bridge.css`, which the host imports; no preset is named. The
   * other path is `preset`.
   */
  theme?: 'host';
  /**
   * The other path: the host follows the engine — a preset (its stylesheet
   * imported: `themes/<name>.css` or `themes.css`), named on `<html>`, and
   * worn by the host's own chrome through `fve-tokens`.
   */
  preset?: ViewPreset;
  /** The host's brand colour over the preset (`--fve-brand`, on `<html>`). */
  brand?: string;
  /**
   * Light or dark (4.1): `system` by default — the engine paints `<html>`'s
   * `.dark` and `color-scheme` and follows the system live — or `light` /
   * `dark` to start pinned; `host` where the host paints its own mode (as
   * next-themes does), and the engine only follows `.dark`. The reader
   * picks another through `useColorMode`. Only the outermost host paints.
   */
  colorMode?: HostColorMode;
  /**
   * Where the reader's pick of a mode is kept on this machine (a
   * `localStorage` key); not kept when left out.
   */
  rememberColorMode?: string;
  children: ReactNode;
}

/**
 * The host's one entry (host-integration.md 4.2): a few ports the host
 * fills — data (`engine`), router, theme, language, commands (`bindings`)
 * — around every surface under it, each taking only what differs where it
 * stands: `<DataWorkbench definitionId={ORDERS} />`. The router, the i18n
 * and the theme stay the host's; the ports are bridges to them.
 *
 * Hosts nest, the inner one over the outer one — a page's own bindings, a
 * second engine — and a surface's own `engine`, `messages`, `locale`,
 * `onNavigate` or `record` still wins. Only the outermost host paints
 * `<html>`: the mode, the preset, the brand.
 */
export function ViewHost({
  engine,
  locale,
  messages,
  router: own,
  navigate,
  bindings,
  theme,
  preset,
  brand,
  colorMode,
  rememberColorMode,
  children,
}: ViewHostProps) {
  const outer = useHosted();
  const detail = useAddressedDetail(own);
  // The router read as a way off is taken, so the route stays put as the
  // address moves.
  const latest = useLatestRouter(own);
  const hasRouter = own !== undefined;
  const routed = useMemo(
    () =>
      navigate ??
      (hasRouter ? routerNavigate(latest as () => ViewRouter) : undefined),
    [navigate, hasRouter, latest],
  );
  useHostTheme(!outer, theme, preset, brand);
  return (
    <ViewEngineProvider
      engine={engine}
      locale={locale}
      messages={messages}
      navigate={routed}
      bindings={bindings}
      router={own}
      detail={detail}
      hosted
    >
      <ColorModeHost
        colorMode={colorMode}
        rememberColorMode={rememberColorMode}
        paints={!outer}
      >
        {children}
      </ColorModeHost>
    </ViewEngineProvider>
  );
}

/**
 * Names the preset and the brand on `<html>` (4.1), where this host paints:
 * `html[data-fve-preset]` and `--fve-brand`, which every surface, its
 * popups and the host's `fve-tokens` read. Under `theme="host"` nothing is
 * named — the bridge applies while `<html>` names no preset. What was there
 * before is put back when the host goes.
 */
function useHostTheme(
  paints: boolean,
  theme: 'host' | undefined,
  preset: ViewPreset | undefined,
  brand: string | undefined,
): void {
  const named = paints && theme !== 'host' ? preset : undefined;
  const branded = paints && theme !== 'host' ? brand : undefined;
  useLayoutEffect(() => {
    if (named === undefined) return;
    const root = document.documentElement;
    const before = root.getAttribute('data-fve-preset');
    root.setAttribute('data-fve-preset', named);
    return () => {
      if (before === null) root.removeAttribute('data-fve-preset');
      else root.setAttribute('data-fve-preset', before);
    };
  }, [named]);
  useLayoutEffect(() => {
    if (branded === undefined) return;
    const root = document.documentElement;
    const before = root.style.getPropertyValue('--fve-brand');
    root.style.setProperty('--fve-brand', branded);
    return () => {
      if (before) root.style.setProperty('--fve-brand', before);
      else root.style.removeProperty('--fve-brand');
    };
  }, [branded]);
}
