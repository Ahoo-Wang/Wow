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

import { useSyncExternalStore } from 'react';

/**
 * Whether a chart may animate its marks into place: not when the reader has
 * asked their system for less motion (`prefers-reduced-motion: reduce`).
 *
 * The stylesheet already honours that preference for everything CSS moves
 * (`styles.css`), but a chart's marks are moved by the chart library in script, which
 * no media query reaches — so bars grew and slices swept for a reader who had
 * asked for neither. It is read live: the preference can change while a view
 * is open, and a chart redrawn afterwards follows it.
 *
 * Where the platform cannot say (no `matchMedia`, a server render), nobody
 * has asked for less, and the chart animates as it always has.
 */
export function useChartMotion(): boolean {
  return !useSyncExternalStore(subscribe, prefersReduced, () => false);
}

const QUERY = '(prefers-reduced-motion: reduce)';

function media(): MediaQueryList | null {
  return typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function'
    ? window.matchMedia(QUERY)
    : null;
}

function prefersReduced(): boolean {
  return media()?.matches ?? false;
}

function subscribe(change: () => void): () => void {
  const list = media();
  list?.addEventListener('change', change);
  return () => list?.removeEventListener('change', change);
}
