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

/**
 * The theme's tokens as each preset, mode and change convention resolve
 * them off the shipped `styles.css` and the preset sources (`src/themes/`,
 * in the index's order through `scripts/themes.mjs`), by the one resolver
 * theme-check uses too (`theme-check/resolve.ts`, S7): the suites hold the
 * built-in presets to the arithmetic a host's theme is held to.
 *
 * **And the example host theme with them.** `porcelain` was a built-in
 * preset until D94 and is kept as a theme a host can copy (Storybook's
 * `host-theme/porcelain.css`). It is measured here after the built-in
 * ones, so it stays held to every gate they are — and so the roles only it
 * writes (a card's edge and lift, a striped row, corners by part, a filled
 * control's thumb) keep a theme that exercises them. What the package
 * *ships* is `themesSource()` alone, held by `themeFiles` and
 * `verify-package`.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { themesSource } from '../../scripts/themes.mjs';
import { registryData } from '../../theme-check/registry';
import { createResolver } from '../../theme-check/resolve';
import { stylesSource } from '../../scripts/styles.mjs';

export {
  at,
  contrast,
  type Gamut,
  over,
  type Rgba,
} from '../../theme-check/colour';
export {
  CONVENTIONS,
  type Convention,
  type HostVariables,
  type Mode,
  type Placement,
  presetBlocks,
} from '../../theme-check/resolve';

/** The example host theme's source, measured beside the built-in presets. */
const EXAMPLE = readFileSync(
  join(
    import.meta.dirname,
    '../../../storybook/stories/view-engine/host-theme/porcelain.css',
  ),
  'utf8',
);

/** Every preset the suites measure: the shipped ones, then the example. */
export const MEASURED_SOURCE = `${themesSource()}\n${EXAMPLE}`;

/** The resolver over the package's own sources and the example theme. */
export const RESOLVER = createResolver({
  styles: stylesSource(),
  presets: MEASURED_SOURCE,
  registry: registryData(),
});

export const {
  tokenVariable,
  presets,
  resetVariables,
  linkRule,
  declared,
  resolveTokens,
} = RESOLVER;

/** The built-in presets in the order `themes.css` writes them, then the example. */
export const PRESET_NAMES = RESOLVER.presetNames;
