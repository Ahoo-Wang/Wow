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
 * Keeps the theme's own rules inside one of the two style boundaries.
 *
 * They are `.fve-root`, the surface `ViewSurface` renders, and `.fve-tokens`,
 * the boundary a host puts on its own chrome so the same primitives and the
 * same tokens reach there (D17-10). Both carry the tokens; only the surface
 * is a surface — it paints a page and can pin a mode with `data-theme`, which
 * is why roots do not nest and a host reaches for the tokens class instead.
 *
 * **The utilities are the engine's by name, not by weight** (D66). Tailwind
 * compiles them with the prefix `fve` (`@import 'tailwindcss' prefix(fve)` in
 * `src/styles.css`), so every one is a class no host writes by accident —
 * `.fve\:flex`, `.fve\:md\:w-64` — and a host's `.w-full` or
 * `sm:grid-cols-4` is never the same rule as ours. A selector whose subject
 * starts with such a class is left exactly as Tailwind wrote it: it can only
 * match an element that wears one of our classes, so it needs no boundary,
 * and it gains no weight, so a host's own breakpoint class on its own
 * markup inside our surface wins or loses by its own cascade alone (G16's
 * `:is()` weight, which made every host class with a base of ours lose
 * inside the scope, is gone).
 *
 * Everything else is still scoped: Tailwind's preflight resets `*`, `html`,
 * headings, lists and buttons on the whole page; the base layer, the theme's
 * rules on `data-slot` (a host's own shadcn components carry the same
 * slots) and the grid adapter's bare `.react-grid-*` classes would reach a
 * host's markup; and the token blocks set custom properties a host reads.
 * `postcss-prefix-selector` rewrites each such selector's subject to the
 * boundaries and their descendants —
 * `:where(.fve-root, .fve-root *, .fve-tokens, .fve-tokens *)` — rather than
 * a descendant prefix, because a popup carries the root class itself. The
 * scope is a `:where()`, weighing nothing, so every rule of the file keeps
 * the weight its source wrote and the cascade among them is exactly what the
 * sources produced. A rule whose subject can never be inside a boundary
 * (`html`) simply stops matching, which is how the host keeps its
 * typography.
 *
 * A selector part that is exactly `:root` or `:host` becomes the boundaries
 * themselves. Nothing else is exempt — a rule that only sets custom
 * properties is rewritten like any other, because a host reads custom
 * properties. `*` becoming `*:where(…)` is right for the same reason:
 * Tailwind's `--tw-*` defaults then apply to each boundary and to everything
 * inside it. Tailwind's own theme variables are not declared at all: the
 * theme is inlined (`theme(inline)`), because with the prefix they would be
 * named `--fve-*`, the host's namespace (`--fve-font-sans` is a host token).
 * Only `@property` registrations stay global, because registration has no
 * selector by nature — and what it registers is the `--tw-*` and animation
 * names a host Tailwind registers identically — and the preset reset
 * (`@layer fve-reset`), which sets nothing but `--fvp-*` to `initial` on
 * whatever names a preset, `<html>` included (theme-architecture.md 3.2).
 * The library skips `@keyframes` steps itself.
 *
 * A rewritten rule is visited again, so a part that already carries the
 * scope is left as it is.
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

/**
 * The subject every scoped rule is pinned to: each boundary, and what is
 * inside it, weighing nothing.
 */
export const SCOPE = `:where(${BOUNDARIES.flatMap(boundary => [
  boundary,
  `${boundary} *`,
]).join(', ')})`;

/** The preset reset's layer, whose one rule is left as it is written. */
export const RESET_LAYER = 'fve-reset';

/**
 * The start of every utility Tailwind compiles with the `fve` prefix, as it
 * escapes it in a selector: `.fve\:flex`.
 */
export const UTILITY = '.fve\\:';

/** `SCOPE` put on one selector part, before a pseudo-element if it has one. */
function scoped(part) {
  const at = part.search(PSEUDO_ELEMENT);
  return at < 0 ? part + SCOPE : part.slice(0, at) + SCOPE + part.slice(at);
}

export function scopeUtilities() {
  return prefixer({
    prefix: BOUNDARIES[0],
    includeFiles: [THEME],
    transform(_prefix, selector, _prefixed, _file, rule) {
      const part = selector.trim();
      const layer = rule.parent;
      if (
        layer?.type === 'atrule' &&
        layer.name === 'layer' &&
        layer.params === RESET_LAYER
      )
        return part;
      if (part === ':root' || part === ':host')
        return BOUNDARIES.map(scoped).join(', ');
      if (part.includes(SCOPE) || part.startsWith(UTILITY)) return part;
      return scoped(part);
    },
  });
}
