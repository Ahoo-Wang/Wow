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
 * Whether the page is being printed (themes.md 4.6).
 *
 * The stylesheet prints every surface in the light half of its preset, with
 * the chart patterns on (`styles.css`, `@media print`), but a chart is drawn
 * by script, in the colours it read off the stylesheet when it was drawn —
 * no media query reaches the drawing. So a chart reads its theme again when
 * printing starts, and once more when it ends: the browser answers the
 * `print` query while it lays the page out for paper, and the chart library
 * draws synchronously, before the page is captured.
 *
 * Where the platform cannot say (no `matchMedia`, a server render), nothing
 * is being printed.
 */
export function usePrinting(): boolean {
  return useSyncExternalStore(subscribe, printing, () => false);
}

function media(): MediaQueryList | null {
  return typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function'
    ? window.matchMedia('print')
    : null;
}

function printing(): boolean {
  return media()?.matches ?? false;
}

function subscribe(change: () => void): () => void {
  const list = media();
  list?.addEventListener('change', change);
  return () => list?.removeEventListener('change', change);
}
