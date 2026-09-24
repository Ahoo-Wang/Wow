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

import { fileURLToPath } from 'node:url';
import prefixer from 'postcss-prefix-selector';

/**
 * Keeps every rule of the theme inside one of the two style boundaries.
 *
 * They are `.fve-root`, the surface `ViewSurface` renders, and `.fve-tokens`,
 * the boundary a host puts on its own chrome so the same primitives and the
 * same utilities paint there (D17-10). Both carry the tokens and the
 * utilities; only the surface is a surface — it paints a page and can pin a
 * mode with `data-theme`, which is why roots do not nest and a host reaches
 * for the tokens class instead.
 *
 * Tailwind's preflight resets `*`, `html`, headings, lists and buttons on the
 * whole page, its utilities are bare classes (`.flex`, `.container`,
 * `.collapse`) a host may share with Bootstrap, and the grid adapter's
 * `.react-grid-*` are bare classes too. Tailwind v4 has no prefix that leaves
 * the vendored components untouched, so `postcss-prefix-selector` rewrites
 * every selector instead. The subject gets the boundaries and their
 * descendants — `:where(.fve-root, .fve-root *, .fve-tokens, .fve-tokens *)` —
 * rather than a descendant prefix, because a popup carries the root class
 * itself, and `:where` adds no specificity, so the cascade is what the
 * sources produced. A rule whose subject can never be inside a boundary
 * (`html`) simply stops matching, which is how the host keeps its typography.
 *
 * A selector part that is exactly `:root` or `:host` becomes the boundaries
 * themselves; a part that already names one is left as it is. Nothing else is
 * exempt
 * — a rule that only sets custom properties is rewritten like any other,
 * because a host reads custom properties. Tailwind emits its theme variables
 * (`--spacing`, `--text-sm`, `--font-sans`, `--radius-md`, …) on `:root, :host`,
 * so left there they would overwrite a host Tailwind's values for the same
 * names, or be overwritten by them; and a variable derived from a token of
 * this root (`--radius-md: calc(var(--radius) * .8)`) is only valid where the
 * token is, which is a boundary and nowhere else. `*` becoming
 * `*:where(…)` is right for the same reason: Tailwind's `--tw-*` defaults
 * then apply to each boundary and to everything inside it.
 * Only `@property` registrations stay global, because registration has no
 * selector by nature — and what it registers is the `--tw-*` and animation
 * names a host Tailwind registers identically. The library skips `@keyframes`
 * steps itself.
 *
 * It runs after the Tailwind Vite plugin, which compiles ahead of PostCSS,
 * and only on the theme file: Storybook runs it too, so the stories show the
 * shipped rules, and its other stylesheets must stay as they are.
 */
const THEME = fileURLToPath(new URL('../src/styles.css', import.meta.url));
const PSEUDO_ELEMENT =
  /(?<!\\)(::[\w-]+|:(?:before|after|first-line|first-letter))/;

/**
 * The two style boundaries, in the order they are written everywhere: the
 * surface first, the host's tokens class second. `scripts/verify-package.mjs`
 * holds the built stylesheet to both — a scope that names one and not the
 * other fails the build.
 */
export const BOUNDARIES = ['.fve-root', '.fve-tokens'];

/** The subject every rule is pinned to: each boundary, and what is inside it. */
export const SCOPE = `:where(${BOUNDARIES.flatMap(boundary => [
  boundary,
  `${boundary} *`,
]).join(', ')})`;

export function scopeUtilities() {
  return prefixer({
    prefix: BOUNDARIES[0],
    includeFiles: [THEME],
    transform(_prefix, selector) {
      const part = selector.trim();
      if (part === ':root' || part === ':host') return BOUNDARIES.join(', ');
      if (BOUNDARIES.some(boundary => part.includes(boundary))) return part;
      const at = part.search(PSEUDO_ELEMENT);
      return at < 0 ? part + SCOPE : part.slice(0, at) + SCOPE + part.slice(at);
    },
  });
}
