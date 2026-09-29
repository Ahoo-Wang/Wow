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

import type { MouseEvent } from 'react';
import {
  DataWorkbench,
  useViewNavigation,
} from '@ahoo-wang/wow-view-engine/ui';
import { ORDERS } from './ordersDefinition.js';

// Step 5: the page, under `OrdersHost`. The navigation is the host's own
// markup, drawn from the engine's navigation data; the workbench is the
// whole list — the views, the filters, the table, the actions, saving.
export function OrdersPage({ go }: { go(path: string): void }) {
  return (
    // `fve-tokens` lends the host's own chrome the engine's theme.
    <div className="fve-tokens" style={SHELL}>
      <OrdersNav go={go} />
      {/* No `instanceId`: under a router the workbench keeps the open view
          in the address's `?view=` itself. */}
      <div style={PAGE}>
        <DataWorkbench definitionId={ORDERS} />
      </div>
    </div>
  );
}

/**
 * Each resource bound to a route, with its system views: where each lives
 * (`path`) and whether the address is on it (`current`). Draw it with your
 * own components — a shadcn `Sidebar`, a top bar; `go` is your router's
 * navigate, or a `<Link to={path}>`.
 */
function OrdersNav({ go }: { go(path: string): void }) {
  const places = useViewNavigation();
  const follow = (path: string) => (event: MouseEvent) => {
    event.preventDefault();
    go(path);
  };
  return (
    <nav aria-label="应用导航" style={NAV}>
      {places.map(place => (
        <ul key={place.id} style={LIST}>
          <li>
            <a
              href={place.path}
              aria-current={place.current ? 'page' : undefined}
              onClick={follow(place.path)}
              style={{ ...LINK, fontWeight: 600 }}
            >
              {place.title}
            </a>
          </li>
          {place.views.map(view => (
            <li key={view.id}>
              <a
                href={view.path}
                aria-current={view.current ? 'page' : undefined}
                onClick={follow(view.path)}
                style={{ ...LINK, ...(view.current ? CURRENT : null) }}
              >
                {view.title}
              </a>
            </li>
          ))}
        </ul>
      ))}
    </nav>
  );
}

// The host's own layout; the colours are the theme's, through `fve-tokens`.
const SHELL = {
  display: 'flex',
  height: '100%',
  minHeight: '36rem',
  background: 'var(--background)',
  color: 'var(--foreground)',
} as const;
const NAV = {
  width: '11rem',
  flex: 'none',
  padding: '12px 8px',
  borderRight: '1px solid var(--border)',
} as const;
const PAGE = { flex: 1, minWidth: 0, minHeight: 0 } as const;
const LIST = { listStyle: 'none', margin: 0, padding: 0 } as const;
const LINK = {
  display: 'block',
  padding: '6px 10px',
  borderRadius: 6,
  color: 'inherit',
  textDecoration: 'none',
  fontSize: 14,
} as const;
const CURRENT = { background: 'var(--accent)' } as const;
