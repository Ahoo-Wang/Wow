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
  DashboardViewPanel,
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
