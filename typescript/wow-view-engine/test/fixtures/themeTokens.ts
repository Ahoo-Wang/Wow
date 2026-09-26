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
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { themesSource } from '../../scripts/themes.mjs';
import { registryData } from '../../theme-check/registry';
import { createResolver } from '../../theme-check/resolve';

export {
  at,
  contrast,
  CONVENTIONS,
  type Convention,
  type Gamut,
  type HostVariables,
  type Mode,
  over,
  type Placement,
  presetBlocks,
  type Rgba,
} from '../../theme-check/resolve';

/** The resolver over the package's own sources. */
export const RESOLVER = createResolver({
  styles: readFileSync(
    join(import.meta.dirname, '..', '..', 'src', 'styles.css'),
    'utf8',
  ),
  presets: themesSource(),
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

/** The names of the built-in presets, in the order `themes.css` writes them. */
export const PRESET_NAMES = RESOLVER.presetNames;
