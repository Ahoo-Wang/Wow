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
 * descendants — `:is(.fve-root, .fve-root *, .fve-tokens, .fve-tokens *)` —
 * rather than a descendant prefix, because a popup carries the root class
 * itself. A rule whose subject can never be inside a boundary (`html`)
 * simply stops matching, which is how the host keeps its typography.
 *
 * **Every rule weighs one class more than its source wrote it, so the
 * host's import order stops mattering** (G16). The scope is `:is()`, which
 * weighs its heaviest argument — one class — and it is put on every rule of
 * the file, the ones that already name a boundary too (the `dark:` variant,
 * the rules written against `.fve-root`). The cascade among this file's own
 * rules is therefore exactly what the sources produced: each of them moved by
 * the same class. What changes is the tie with a host's Tailwind. Its
 * utilities and ours fill the same `utilities` layer, where a tie in weight
 * goes to whichever stylesheet came later, so a host that imported this file
 * before its own saw its bare `.w-full` and `.flex-col` beat our `md:w-64`
 * and `md:flex-row` on our own surfaces — a workbench whose view list took
 * the whole row (the compensation console, 2026-09-25). One class more and
 * ours win on our surfaces in either order; outside them ours match nothing,
 * so the host's own markup is untouched in either order too.
 *
 * A selector part that is exactly `:root` or `:host` becomes the boundaries
 * themselves. Nothing else is exempt
 * — a rule that only sets custom properties is rewritten like any other,
 * because a host reads custom properties. Tailwind emits its theme variables
 * (`--spacing`, `--text-sm`, `--font-sans`, `--radius-md`, …) on `:root, :host`,
 * so left there they would overwrite a host Tailwind's values for the same
 * names, or be overwritten by them; and a variable derived from a token of
 * this root (`--radius-md: calc(var(--radius) * .8)`) is only valid where the
 * token is, which is a boundary and nowhere else. `*` becoming
 * `*:is(…)` is right for the same reason: Tailwind's `--tw-*` defaults
 * then apply to each boundary and to everything inside it.
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
 * The subject every rule is pinned to: each boundary, and what is inside it,
 * weighing one class.
 */
export const SCOPE = `:is(${BOUNDARIES.flatMap(boundary => [
  boundary,
  `${boundary} *`,
]).join(', ')})`;

/** The preset reset's layer, whose one rule is left as it is written. */
export const RESET_LAYER = 'fve-reset';

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
      if (part.includes(SCOPE)) return part;
      return scoped(part);
    },
  });
}
