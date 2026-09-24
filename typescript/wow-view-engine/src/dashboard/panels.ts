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

import type {
  DashboardContentPanel,
  DashboardPanel,
  DashboardViewConfig,
  DashboardViewPanel,
  OwnedView,
  PanelBinding,
  PanelClick,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';

/** A panel backed by a referenced instance, and so by a child runtime. */
/**
 * Total over `unknown`: a stored config may hold a panel that is no object,
 * and the runtime asks this before admission has had its say.
 */
export function isViewPanel(panel: unknown): panel is DashboardViewPanel {
  return isPlainObject(panel) && panel.kind === 'view';
}

/** A static panel: no query, no global filter, no child runtime. */
export function isContentPanel(panel: unknown): panel is DashboardContentPanel {
  return isPlainObject(panel) && panel.kind !== 'view';
}

/**
 * A data panel showing a view the board owns (`owned`) rather than a saved
 * one. Read by the member alone — admission is what says a panel holding
 * both, or neither, is wrong (`dashboard.panel.source-invalid`).
 */
export function isOwnedPanel(
  panel: unknown,
): panel is DashboardViewPanel & { owned: OwnedView } {
  return isViewPanel(panel) && panel.owned !== undefined;
}

/** A panel's bindings, read as the untrusted thing a stored config is. */
export function bindingsOf(panel: DashboardViewPanel): PanelBinding[] {
  const bindings: unknown = panel.bindings;
  return Array.isArray(bindings)
    ? bindings.filter(
        (entry): entry is PanelBinding =>
          isPlainObject(entry) &&
          typeof entry.globalField === 'string' &&
          typeof entry.panelField === 'string',
      )
    : [];
}

/**
 * A panel's click as stored, or `null` for the follow-up menu: none set, or
 * one this reading cannot make out — admission says which
 * (`validatePanelClick`), and a press falls back to the menu meanwhile.
 */
export function clickOf(panel: unknown): PanelClick | null {
  if (!isViewPanel(panel)) return null;
  const click: unknown = panel.click;
  if (!isPlainObject(click)) return null;
  switch (click.kind) {
    case 'filter':
      return typeof click.filter === 'string'
        ? { kind: 'filter', filter: click.filter }
        : null;
    case 'view':
      return typeof click.instanceId === 'string' && click.instanceId.length > 0
        ? { kind: 'view', instanceId: click.instanceId }
        : null;
    case 'url':
      return typeof click.url === 'string'
        ? { kind: 'url', url: click.url }
        : null;
    case 'dashboard': {
      const values: unknown = click.values;
      return typeof click.instanceId === 'string' &&
        click.instanceId.length > 0 &&
        isPlainObject(values) &&
        Object.values(values).every(field => typeof field === 'string')
        ? {
            kind: 'dashboard',
            instanceId: click.instanceId,
            values: { ...(values as Record<string, string>) },
          }
        : null;
    }
    default:
      return null;
  }
}

/** Whether a click sets this filter: what unwiring the filter takes with it. */
export function clicksFilter(panel: unknown, name: string): boolean {
  const click = clickOf(panel);
  return click?.kind === 'filter' && click.filter === name;
}

/**
 * The saved view a data panel points at, or `undefined` for one the board
 * owns or one whose `instanceId` is no string.
 */
export function referencedInstance(panel: unknown): string | undefined {
  return isViewPanel(panel) &&
    panel.owned === undefined &&
    typeof panel.instanceId === 'string'
    ? panel.instanceId
    : undefined;
}

/**
 * The first `{prefix}-{n}` not among `taken`, counting from one. Ids are
 * keys inside one config and never shown, so a plain counter is enough, and
 * it keeps every edit a pure function of the config it edits.
 */
export function freshId(taken: Iterable<unknown>, prefix: string): string {
  const used = new Set(taken);
  let n = 1;
  while (used.has(`${prefix}-${n}`)) n += 1;
  return `${prefix}-${n}`;
}

/**
 * The tab a panel is read as sitting on: its own when the board has that
 * tab, the first otherwise, and `null` on a board without tabs. Admission
 * reports a panel that names no tab of the board (`dashboard.panel.tab-unknown`);
 * until it is fixed the panel is shown on the first rather than nowhere.
 */
export function panelTab(
  config: Pick<DashboardViewConfig, 'tabs'>,
  panel: Pick<DashboardPanel, 'tab'>,
): string | null {
  const tabs: unknown = config.tabs;
  if (!Array.isArray(tabs) || tabs.length === 0) return null;
  const ids = tabs.flatMap(tab =>
    isPlainObject(tab) && typeof tab.id === 'string' ? [tab.id] : [],
  );
  if (typeof panel.tab === 'string' && ids.includes(panel.tab))
    return panel.tab;
  return ids[0] ?? null;
}

/** Schemes a content panel may link to or load from. */
const ALLOWED_SCHEMES = new Set(['http', 'https', 'mailto']);

const SCHEME = /^([A-Za-z][A-Za-z0-9+.-]*):/;

// Written as escapes on purpose: the literal bytes are invisible in an
// editor and a careless reformat would silently drop them from a check
// whose whole job is to catch what a browser still reads as a scheme.
// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

/**
 * Whether a content panel's URL may be rendered.
 *
 * Only http, https, mailto and relative paths pass. A scheme-relative URL is
 * refused along with the rest: it inherits the host page's scheme, so it is
 * neither a checked absolute URL nor a path inside the application. A URL
 * being well-formed says nothing about the resource behind it, which is why
 * the UI layer still renders images and links defensively.
 */
export function isSafeContentUrl(raw: string): boolean {
  const value = raw.trim();
  if (value.length === 0) return false;
  // A control character hides a scheme here that a browser still reads.
  if (CONTROL_CHARACTERS.test(value)) return false;
  if (value.startsWith('//')) return false;
  const scheme = SCHEME.exec(value);
  return scheme === null || ALLOWED_SCHEMES.has(scheme[1].toLowerCase());
}
