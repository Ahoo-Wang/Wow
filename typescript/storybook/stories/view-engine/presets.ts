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
import { BUILT_IN_PRESETS } from '@ahoo-wang/wow-view-engine/ui';

/**
 * The presets the package ships, in its order (phase 5, 5D; D35) — the list a
 * host builds its own picker from, `BUILT_IN_PRESETS`, which the package
 * holds to its stylesheet — so the toolbar, the theme gallery and the
 * contrast matrix take a preset the moment it ships, and none of them can
 * name one it does not have.
 */
export const PRESETS: readonly string[] = BUILT_IN_PRESETS;

/**
 * The preset a surface wears with no attribute anywhere: the theme itself.
 * It is the engine's default, and on the toolbar it means no attribute.
 */
export const ENGINE_PRESET = 'neutral';

/**
 * The example host themes Storybook loads beside the built-in presets
 * (`host-theme/<name>.css`, imported by the preview): themes written the
 * way a host writes its own, on the toolbar after the built-in ones.
 * `porcelain` was a built-in preset until D94 and is kept as one to copy.
 */
export const EXAMPLE_THEMES: readonly string[] = ['porcelain'];

/**
 * Every theme a page of themes shows and measures: the built-in presets,
 * then the example host themes, which are held to the same lines.
 */
export const SHOWN_THEMES: readonly string[] = [...PRESETS, ...EXAMPLE_THEMES];

/**
 * The preset Storybook opens in: the engine's own look, as a host that
 * names no preset gets it (it was `porcelain` while that shipped, the
 * user's call of 2026-09-25, until D94 took it out of the package).
 */
export const DEFAULT_PRESET = ENGINE_PRESET;

/** The modes a surface is shown in: pinned light, pinned dark, the system's. */
export const MODES = ['light', 'dark', 'system'] as const;

export type Mode = (typeof MODES)[number];
