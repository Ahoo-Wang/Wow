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
// The source as written, not the package's entry: the alias in
// `.storybook/main.ts` names the entry exactly, and a `?raw` query would
// not match it.
import themes from '../../../wow-view-engine/src/themes.css?raw';

/**
 * The presets `themes.css` declares, in the order it declares them (phase 5,
 * 5D) — read off the stylesheet rather than listed here, so the toolbar, the
 * theme gallery and the contrast matrix all take a preset the moment it is
 * written there, and none of them can name one it does not have.
 */
export const PRESETS: readonly string[] = [
  ...themes
    // The header comment spells the selector out as an example.
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .matchAll(/:where\(\[data-fve-preset=['"]?([a-z][a-z0-9-]*)['"]?\]\)/g),
].map(([, name]) => name);

/** The preset a surface wears with no attribute anywhere: the theme itself. */
export const DEFAULT_PRESET = 'neutral';

/** The modes a surface is shown in: pinned light, pinned dark, the system's. */
export const MODES = ['light', 'dark', 'system'] as const;

export type Mode = (typeof MODES)[number];
