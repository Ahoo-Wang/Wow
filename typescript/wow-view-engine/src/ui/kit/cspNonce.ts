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

/*
 * The page's CSP nonce, and every `<style>` the engine's libraries add
 * carrying it (D61, D74). Under a strict policy (`style-src 'self'`, no
 * `'unsafe-inline'`) a `<style>` added to the page is applied only with the
 * nonce; three libraries add one while they work — the drag-and-drop
 * library while a list is dragged (`sortableList`), Base UI while a select's
 * list is open (`SelectContent`), and the grid's drag library while a panel
 * is moved or resized (`draggableStyle`) — and each is handed the one
 * nonce the page publishes.
 */

/**
 * The page's CSP nonce, as Vite and most servers publish it:
 * `<meta property="csp-nonce" nonce="…">` (the `nonce` attribute, which a
 * browser hides from `getAttribute` once the policy applies but keeps in the
 * `nonce` property), or the `content` of the same tag. `undefined` on a page
 * without one — and under no policy, nothing needs one.
 */
export function cspNonce(
  root: Document | undefined = globalThis.document,
): string | undefined {
  const meta = root?.querySelector<HTMLMetaElement>(
    'meta[property="csp-nonce"]',
  );
  const nonce = meta?.nonce || meta?.getAttribute('content') || '';
  return nonce === '' ? undefined : nonce;
}

/**
 * The id `react-draggable` — the library under the board's grid, which
 * moves a panel by its grip and sizes it by its corner — gives the one
 * `<style>` it adds when a drag starts (no text selection while the pointer
 * is held), and looks up before adding it.
 */
const DRAGGABLE_STYLE_ID = 'react-draggable-style-el';

/**
 * The grid's drag library adds its `<style>` without a nonce: the grid
 * passes none on, and its only other source is a bundler global. So the
 * engine adds that style itself, under the page's nonce and with the
 * library's own rules, before the first drag — the library finds it by its
 * id and adds nothing. Its rules match only while the library has marked
 * `<body>` for a drag, so it is inert until then. Nothing is added on a page
 * that publishes no nonce, where nothing needs one.
 */
export function draggableStyle(
  root: Document | undefined = globalThis.document,
): void {
  const nonce = cspNonce(root);
  if (!root || !nonce || root.getElementById(DRAGGABLE_STYLE_ID)) return;
  const style = root.createElement('style');
  style.id = DRAGGABLE_STYLE_ID;
  style.nonce = nonce;
  style.textContent =
    '.react-draggable-transparent-selection *::-moz-selection {all: inherit;}\n' +
    '.react-draggable-transparent-selection *::selection {all: inherit;}\n';
  root.head.append(style);
}
